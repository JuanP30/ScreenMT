package io.github.juanp30.screenmt;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ScreenMT en la tablet.
 *
 * Inicio: espera al Mac y deja enviar archivos al Mac o abrir Descargas.
 * Segunda pantalla: cuando el Mac transmite, muestra su pantalla a pantalla completa. El video llega
 * en H.264 por TCP (por cable con «adb reverse», así que es «localhost») y se decodifica por hardware
 * con MediaCodec; los toques vuelven por la misma conexión. «Atrás» cierra la app.
 *
 * Ayudas táctiles (barra a la derecha): teclado con las teclas del Mac, modo de un dedo, cambiar de app,
 * ventanas, copiar/pegar/deshacer, y los interruptores de bocinas y micrófono.
 *
 * Protocolo: ver pantalla/main.swift en el Mac.
 */
public class Principal extends Activity {
    private static final int PUERTO_ARCHIVOS = 8732; // ScreenMT del Mac recibe aquí (solo por cable)
    private static final int ELEGIR = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService salida = Executors.newSingleThreadExecutor();
    private FrameLayout raiz;
    private SurfaceView video;
    private View inicio;
    private TextView estado;
    private Button botonCable, botonWifi;
    private volatile boolean vivo = true;
    private volatile Socket socket;
    private volatile DataOutputStream out;
    private volatile CountDownLatch superficie = new CountDownLatch(1);
    private String host = "127.0.0.1", clave = "";
    private int puerto = 8730;
    private int ancho = 16, alto = 10;
    private Thread conexion;
    private final Sonido sonido = new Sonido();
    private Teclado teclado;
    private LinearLayout panel, botonesPanel;
    private SharedPreferences pref;
    // ajustes de la barra: un dedo desplaza (como el celular) o arrastra (como el ratón), bocinas y micrófono
    private boolean modoToque = true, altavoz = false, mic = false;
    private static final int PERMISO_MIC = 2;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        pref = getSharedPreferences("screenmt", MODE_PRIVATE);
        modoToque = pref.getBoolean("modoToque", true);
        altavoz = pref.getBoolean("altavoz", false);
        mic = pref.getBoolean("mic", false) && tienePermisoMic();
        armarVista();
        leerDestino(getIntent());
        conexion = new Thread(this::conectarSiempre, "conexion");
        conexion.start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        leerDestino(i);
        cerrarSocket(); // se reconecta solo al nuevo destino
    }

    /* ---------------- a dónde conectarse ---------------- */

    // Dos modos, cada uno con su destino. Por cable siempre es 127.0.0.1 (adb reverse); por wifi el destino
    // llega del QR (screenmt://abrir?h=IP&p=8730&k=CLAVE) y se recuerda. El Mac, por cable, abre la app con extras.
    private volatile boolean porWifi = false;
    private String wifiHost = "", wifiClave = "";
    private int wifiPuerto = 8730;

    private void leerDestino(Intent i) {
        SharedPreferences p = getSharedPreferences("screenmt", MODE_PRIVATE);
        // (el emparejamiento por cable pudo escribir wifiHost/wifiClave mientras la app estaba cerrada)
        porWifi = p.getBoolean("wifi", false);
        wifiHost = p.getString("wifiHost", "");
        wifiPuerto = p.getInt("wifiPuerto", 8730);
        wifiClave = p.getString("wifiClave", "");
        if (i != null) {
            Uri d = i.getData();
            if (d != null && "screenmt".equals(d.getScheme()) && d.getQueryParameter("h") != null) {
                wifiHost = d.getQueryParameter("h");
                try { wifiPuerto = Integer.parseInt(d.getQueryParameter("p")); } catch (Exception ignorada) { }
                wifiClave = d.getQueryParameter("k") == null ? "" : d.getQueryParameter("k");
                porWifi = true;
            } else if (i.hasExtra("host")) {
                String h = i.getStringExtra("host");
                porWifi = !"127.0.0.1".equals(h);
                if (porWifi) {
                    wifiHost = h;
                    wifiPuerto = i.getIntExtra("puerto", 8730);
                    wifiClave = i.getStringExtra("clave") == null ? "" : i.getStringExtra("clave");
                }
            }
        }
        guardarDestino();
    }

    private void guardarDestino() {
        getSharedPreferences("screenmt", MODE_PRIVATE).edit().putBoolean("wifi", porWifi)
                .putString("wifiHost", wifiHost).putInt("wifiPuerto", wifiPuerto).putString("wifiClave", wifiClave).apply();
        if (porWifi) { host = wifiHost; puerto = wifiPuerto; clave = wifiClave; }
        else { host = "127.0.0.1"; puerto = 8730; clave = ""; }
    }

    private void cambiarModo(boolean wifi) {
        if (wifi == porWifi) return;
        porWifi = wifi;
        guardarDestino();
        cerrarSocket();
        mostrarInicio();
    }

    /* ---------------- la conexión con el Mac ---------------- */

    // Por wifi la tablet busca al Mac en la red (Bonjour, «_screenmt._tcp»): si el router le cambió la
    // dirección, la encuentra igual. La clave ya la tiene (del QR o del emparejamiento por cable).
    private volatile String hostEncontrado;
    private android.net.nsd.NsdManager nsd;
    private android.net.nsd.NsdManager.DiscoveryListener buscador;

    private void buscarMac() {
        if (buscador != null) return;
        nsd = (android.net.nsd.NsdManager) getSystemService(NSD_SERVICE);
        buscador = new android.net.nsd.NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) { buscador = null; }
            public void onStopDiscoveryFailed(String t, int e) { }
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onServiceLost(android.net.nsd.NsdServiceInfo i) { }
            @SuppressWarnings("deprecation")
            public void onServiceFound(android.net.nsd.NsdServiceInfo i) {
                nsd.resolveService(i, new android.net.nsd.NsdManager.ResolveListener() {
                    public void onResolveFailed(android.net.nsd.NsdServiceInfo i, int e) { }
                    public void onServiceResolved(android.net.nsd.NsdServiceInfo i) {
                        if (i.getHost() != null && i.getHost() instanceof java.net.Inet4Address) hostEncontrado = i.getHost().getHostAddress();
                    }
                });
            }
        };
        try { nsd.discoverServices("_screenmt._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, buscador); } catch (Exception e) { buscador = null; }
    }

    private void dejarDeBuscar() {
        if (nsd != null && buscador != null) try { nsd.stopServiceDiscovery(buscador); } catch (Exception ignorada) { }
        buscador = null;
    }

    private void conectarSiempre() {
        int fallos = 0;
        while (vivo) {
            if (porWifi) {
                ui.post(this::buscarMac);
                // el emparejamiento por cable pudo cambiar la clave mientras tanto
                if (fallos > 0) {
                    SharedPreferences p = getSharedPreferences("screenmt", MODE_PRIVATE);
                    wifiClave = p.getString("wifiClave", wifiClave);
                    if (hostEncontrado == null) wifiHost = p.getString("wifiHost", wifiHost);
                    guardarDestino();
                }
                // lo guardado no responde: probar donde se anunció el Mac
                String h = hostEncontrado;
                if (h != null && !h.equals(wifiHost) && fallos > 0) { wifiHost = h; guardarDestino(); }
                if (wifiHost.isEmpty() || wifiClave.isEmpty()) { dormir(800); continue; } // falta emparejar
            }
            try {
                transmitir();
                fallos = 0;
            } catch (Exception e) {
                // el Mac no está transmitiendo todavía o se cortó: volver al inicio y reintentar
                fallos++;
            }
            cerrarSocket();
            ui.post(this::mostrarInicio);
            dormir(1200);
        }
    }

    private void transmitir() throws Exception {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, puerto), 1500);
        socket = s;
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 1 << 16));
        out = new DataOutputStream(s.getOutputStream());
        mandar(0, clave.getBytes(StandardCharsets.UTF_8));
        s.setSoTimeout(6000);
        int largo = in.readInt();
        int tipo = in.readUnsignedByte();
        byte[] hola = new byte[largo - 1];
        in.readFully(hola);
        if (tipo != 0) throw new IllegalStateException("respuesta rara");
        String[] wh = new String(hola, StandardCharsets.UTF_8).trim().split(" ");
        boolean nitidez = wh.length < 3 || "nitidez".equals(wh[2]);
        MediaCodec dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        // lo máximo que este decodificador puede mostrar con la forma de la pantalla del Mac
        int[] t = tope(dec, Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), nitidez);
        ancho = t[0];
        alto = t[1];
        mandar(11, (t[0] + " " + t[1] + " " + t[2]).getBytes(StandardCharsets.UTF_8));
        mandarOpciones();
        sonido.altavoz(altavoz);
        if (mic) sonido.microfono(true, pcm -> mandar(12, pcm));
        s.setSoTimeout(0); // con la pantalla quieta pueden pasar ratos sin cuadros

        superficie = new CountDownLatch(1);
        ui.post(this::mostrarVideo);
        if (!superficie.await(3, TimeUnit.SECONDS)) { dec.release(); throw new IllegalStateException("sin superficie"); }

        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ancho, alto);
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0); // tiempo real
        dec.configure(f, video.getHolder().getSurface(), null, 0);
        dec.start();
        Thread pintor = new Thread(() -> pintar(dec), "pintor");
        pintor.start();
        try {
            byte[] buf = new byte[1 << 20];
            while (vivo) {
                largo = in.readInt();
                tipo = in.readUnsignedByte();
                int n = largo - 1;
                if (n > buf.length) buf = new byte[n];
                in.readFully(buf, 0, n);
                if (tipo == 4) { sonido.recibir(java.util.Arrays.copyOf(buf, n)); continue; }
                if (tipo == 5) { avisoDelMac(new String(buf, 0, n, StandardCharsets.UTF_8)); continue; }
                if (tipo < 1 || tipo > 3) continue;
                int i = dec.dequeueInputBuffer(200_000);
                if (i < 0) continue;
                ByteBuffer bb = dec.getInputBuffer(i);
                bb.clear();
                bb.put(buf, 0, n);
                dec.queueInputBuffer(i, 0, n, System.nanoTime() / 1000, tipo == 1 ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
            }
        } finally {
            sonido.apagarTodo();
            pintor.interrupt();
            try { dec.stop(); } catch (Exception ignorada) { }
            dec.release();
        }
    }

    /**
     * El tamaño más grande (misma forma que la pantalla del Mac) y los cuadros por segundo que el
     * decodificador de esta tablet acepta. «nitidez»: el mayor tamaño aunque baje a 30 cuadros;
     * si no, 60 cuadros con el tamaño que dé. Devuelve {ancho, alto, fps}.
     */
    private static int[] tope(MediaCodec dec, int w, int h, boolean nitidez) {
        MediaCodecInfo.VideoCapabilities c;
        try {
            c = dec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
        } catch (Exception e) {
            return new int[] {w, h, 60};
        }
        int aw = Math.max(2, c.getWidthAlignment()), ah = Math.max(2, c.getHeightAlignment());
        int[] cuadros = nitidez ? new int[] {30} : new int[] {60, 30};
        for (int fps : cuadros) {
            for (int pct = 100; pct >= 30; pct -= 2) {
                int x = (w * pct / 100) / aw * aw, y = (h * pct / 100) / ah * ah;
                if (c.isSizeSupported(x, y) && c.areSizeAndRateSupported(x, y, fps)) {
                    // con el tamaño elegido, los cuadros más altos que aguante
                    int mejor = fps;
                    for (int f = 60; f > fps; f -= 5) if (c.areSizeAndRateSupported(x, y, f)) { mejor = f; break; }
                    return new int[] {x, y, mejor};
                }
            }
        }
        return new int[] {Math.min(w, 1920) / 2 * 2, Math.min(h, 1080) / 2 * 2, 30};
    }

    // saca cada cuadro apenas está listo: nada de esperar al «tiempo de presentación»
    private void pintar(MediaCodec dec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int o = dec.dequeueOutputBuffer(info, 20_000);
                if (o >= 0) dec.releaseOutputBuffer(o, true);
            }
        } catch (Exception terminado) {
            // el decodificador se cerró
        }
    }

    private void mandar(int tipo, byte[] datos) {
        DataOutputStream o = out;
        if (o == null) return;
        salida.execute(() -> {
            try {
                synchronized (o) {
                    o.writeInt(datos.length + 1);
                    o.writeByte(tipo);
                    o.write(datos);
                    o.flush();
                }
            } catch (Exception e) {
                cerrarSocket();
            }
        });
    }

    private void toque(String t, float x, float y, float dx, float dy) {
        String j = String.format(Locale.US, "{\"t\":\"%s\",\"x\":%.5f,\"y\":%.5f,\"dx\":%.1f,\"dy\":%.1f}", t, x, y, dx, dy);
        mandar(10, j.getBytes(StandardCharsets.UTF_8));
    }

    private void mandarOpciones() {
        mandar(13, ("{\"altavoz\":" + altavoz + ",\"mic\":" + mic + "}").getBytes(StandardCharsets.UTF_8));
    }

    private void avisoDelMac(String json) {
        String t;
        try { t = new org.json.JSONObject(json).optString("aviso", ""); } catch (Exception e) { return; }
        if (t.isEmpty()) return;
        ui.post(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show());
    }

    private void texto(String s) {
        mandar(10, ("{\"t\":\"texto\",\"s\":" + org.json.JSONObject.quote(s) + "}").getBytes(StandardCharsets.UTF_8));
    }

    private void tecla(int k, String mods) {
        mandar(10, ("{\"t\":\"tecla\",\"k\":" + k + ",\"m\":\"" + mods + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private void cerrarSocket() {
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) try { s.close(); } catch (Exception ignorada) { }
    }

    /* ---------------- toques ----------------
     * Modo de un dedo (por defecto, como el celular): deslizar = desplazar (con inercia), tocar = clic,
     *   dejar el dedo quieto y luego moverlo = arrastrar o seleccionar.
     * Modo ratón: tocar = clic, mover = arrastrar, dejar el dedo quieto = clic derecho.
     * En los dos: dos dedos = desplazar, tocar con dos dedos = clic derecho, pellizcar = zoom.
     * El desplazamiento acelera: despacio se mueve poco (preciso), rápido se mueve mucho.
     */

    private static final int NADA = 0, ESPERANDO = 1, ARRASTRANDO = 2, LARGO = 3, DESPLAZANDO = 4, DOS = 5, PELLIZCO = 6;
    private int gesto = NADA;
    private float x0, y0, cx, cy, ux, uy, dist0, distZoom;
    private long ut, t2;
    private boolean recentrar, dosMovio;
    private android.view.VelocityTracker vt;
    private final Runnable arranque = () -> {
        if (gesto == ESPERANDO) { gesto = ARRASTRANDO; toque("d", x0, y0, 0, 0); }
    };
    // modo ratón: quieto = clic derecho
    private final Runnable largo = () -> {
        if (gesto == ESPERANDO || gesto == ARRASTRANDO) {
            if (gesto == ARRASTRANDO) toque("u", x0, y0, 0, 0);
            gesto = LARGO;
            toque("r", x0, y0, 0, 0);
        }
    };
    // modo de un dedo: quieto = empezar a arrastrar (con una vibración corta para avisar)
    private final Runnable sostener = () -> {
        if (gesto == ESPERANDO) {
            gesto = ARRASTRANDO;
            video.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            toque("d", x0, y0, 0, 0);
        }
    };

    // velocidad del dedo (dp/ms) → cuánto multiplicar: 0,3 muy despacio … 1,6 muy rápido (más alto se descontrola)
    private void desplazar(float dxPx, float dyPx, long dtMs, float x, float y) {
        float dp = getResources().getDisplayMetrics().density;
        float dx = dxPx / dp, dy = dyPx / dp;
        float v = (float) Math.hypot(dx, dy) / Math.max(1, dtMs);
        float g = Math.max(0.3f, Math.min(1.6f, 0.3f + 0.45f * v));
        toque("s", x, y, dx * g, dy * g);
    }

    // al soltar rápido sigue un poco, frenando, como en el celular
    private float ivx, ivy, ix, iy;
    private final Runnable inercia = new Runnable() {
        public void run() {
            ivx *= 0.88f; ivy *= 0.88f;
            if (Math.hypot(ivx, ivy) < 40) return;
            toque("s", ix, iy, ivx * 0.016f, ivy * 0.016f);
            ui.postDelayed(this, 16);
        }
    };
    private void lanzar(float x, float y) {
        if (vt == null) return;
        float dp = getResources().getDisplayMetrics().density;
        vt.computeCurrentVelocity(1000);
        float vx = vt.getXVelocity() / dp, vy = vt.getYVelocity() / dp;
        if (Math.hypot(vx, vy) < 500) return;  // solo si soltaste con ganas
        ivx = vx * 0.6f; ivy = vy * 0.6f; ix = x; iy = y;
        ui.postDelayed(inercia, 16);
    }

    private boolean alTocar(View v, MotionEvent e) {
        float w = v.getWidth(), h = v.getHeight(), dp = getResources().getDisplayMetrics().density;
        float x = e.getX() / w, y = e.getY() / h;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ui.removeCallbacks(inercia);
                if (vt != null) vt.recycle();
                vt = android.view.VelocityTracker.obtain();
                vt.addMovement(e);
                x0 = x; y0 = y; ux = e.getX(); uy = e.getY(); ut = e.getEventTime();
                gesto = ESPERANDO;
                if (modoToque) ui.postDelayed(sostener, 450);
                else {
                    // se espera un instante para saber si llega un segundo dedo antes de apretar el botón
                    ui.postDelayed(arranque, 130);
                    ui.postDelayed(largo, 650);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (gesto == ARRASTRANDO) toque("u", x, y, 0, 0);
                ui.removeCallbacks(arranque); ui.removeCallbacks(largo); ui.removeCallbacks(sostener);
                gesto = DOS;
                t2 = e.getEventTime();
                dosMovio = false;
                cx = centroX(e); cy = centroY(e); ut = e.getEventTime();
                dist0 = distZoom = distancia(e);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                recentrar = true; // con un dedo menos el centro salta: se toma de nuevo antes de seguir
                break;
            case MotionEvent.ACTION_MOVE:
                vt.addMovement(e);
                if (gesto == DOS || gesto == PELLIZCO || (gesto == DESPLAZANDO && e.getPointerCount() > 1)) {
                    float nx = centroX(e), ny = centroY(e), d = distancia(e);
                    if (recentrar) { recentrar = false; cx = nx; cy = ny; ut = e.getEventTime(); distZoom = d; break; }
                    if (gesto == DOS && dist0 > 0 && Math.abs(d / dist0 - 1) > 0.12f) { gesto = PELLIZCO; distZoom = d; }
                    if (gesto == PELLIZCO) {
                        if (d / distZoom > 1.15f) { mandarZoom(1); distZoom = d; }
                        else if (d / distZoom < 0.87f) { mandarZoom(-1); distZoom = d; }
                        break;
                    }
                    if (Math.hypot(nx - cx, ny - cy) > 2 * dp) dosMovio = true;
                    desplazar(nx - cx, ny - cy, e.getEventTime() - ut, x, y);
                    cx = nx; cy = ny; ut = e.getEventTime();
                    break;
                }
                boolean seMovio = Math.hypot((x - x0) * w, (y - y0) * h) > 8 * dp;
                if (modoToque) {
                    if (gesto == ESPERANDO && seMovio) {
                        ui.removeCallbacks(sostener);
                        gesto = DESPLAZANDO;
                    }
                    if (gesto == DESPLAZANDO) desplazar(e.getX() - ux, e.getY() - uy, e.getEventTime() - ut, x, y);
                    else if (gesto == ARRASTRANDO) toque("m", x, y, 0, 0);
                } else {
                    if (gesto == ESPERANDO && seMovio) {
                        ui.removeCallbacks(arranque); gesto = ARRASTRANDO; toque("d", x0, y0, 0, 0);
                    }
                    if (gesto == ARRASTRANDO) {
                        if (seMovio) ui.removeCallbacks(largo);
                        toque("m", x, y, 0, 0);
                    }
                }
                ux = e.getX(); uy = e.getY(); ut = e.getEventTime();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(arranque); ui.removeCallbacks(largo); ui.removeCallbacks(sostener);
                if (gesto == ESPERANDO) { toque("d", x0, y0, 0, 0); toque("u", x0, y0, 0, 0); }
                else if (gesto == ARRASTRANDO) toque("u", x, y, 0, 0);
                else if (gesto == DESPLAZANDO) lanzar(x, y);
                else if (gesto == DOS) {
                    // dos dedos que tocan y se levantan rápido, sin moverse: clic derecho
                    if (!dosMovio && e.getEventTime() - t2 < 350) toque("r", x0, y0, 0, 0);
                    else lanzar(x, y);
                }
                gesto = NADA;
                break;
            default:
                break;
        }
        return true;
    }

    private void mandarZoom(int dir) {
        mandar(10, ("{\"t\":\"zoom\",\"dir\":" + dir + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static float distancia(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static float centroX(MotionEvent e) { float s = 0; for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i); return s / e.getPointerCount(); }
    private static float centroY(MotionEvent e) { float s = 0; for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i); return s / e.getPointerCount(); }

    /* ---------------- pantallas ---------------- */

    private void armarVista() {
        float dp = getResources().getDisplayMetrics().density;
        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.BLACK);

        video = new SurfaceView(this);
        video.setVisibility(View.GONE);
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { superficie.countDown(); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int he) { }
            public void surfaceDestroyed(SurfaceHolder h) { cerrarSocket(); }
        });
        video.setOnTouchListener(this::alTocar);
        raiz.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // la forma de la imagen del Mac se respeta con franjas negras si no coincide con la tablet
        raiz.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) -> ajustarVideo());

        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setGravity(Gravity.CENTER_HORIZONTAL);
        caja.setPadding((int) (32 * dp), (int) (32 * dp), (int) (32 * dp), (int) (32 * dp));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0xFF1B1E24);
        fondo.setCornerRadius(18 * dp);
        caja.setBackground(fondo);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        caja.addView(logo, new LinearLayout.LayoutParams((int) (72 * dp), (int) (72 * dp)));

        TextView titulo = texto("ScreenMT", 26, Color.WHITE, true);
        titulo.setPadding(0, (int) (14 * dp), 0, 0);
        titulo.setGravity(Gravity.CENTER);
        caja.addView(titulo);
        estado = texto("Esperando al Mac…", 16, 0xFFA7ADB8, false);
        estado.setPadding(0, (int) (6 * dp), 0, (int) (20 * dp));
        estado.setGravity(Gravity.CENTER);
        caja.addView(estado);

        LinearLayout modos = new LinearLayout(this);
        modos.setOrientation(LinearLayout.HORIZONTAL);
        botonCable = boton("Por cable", false, v -> cambiarModo(false));
        botonWifi = boton("Por wifi", false, v -> cambiarModo(true));
        LinearLayout.LayoutParams mitad = new LinearLayout.LayoutParams(0, (int) (48 * dp), 1);
        LinearLayout.LayoutParams mitad2 = new LinearLayout.LayoutParams(0, (int) (48 * dp), 1);
        mitad2.leftMargin = (int) (8 * dp);
        modos.addView(botonCable, mitad);
        modos.addView(botonWifi, mitad2);
        caja.addView(modos, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sep = texto("", 4, 0, false);
        caja.addView(sep, new LinearLayout.LayoutParams(1, (int) (14 * dp)));
        caja.addView(boton("Enviar archivos al Mac", true, v -> elegirArchivos()));
        caja.addView(boton("Abrir Descargas", false, v -> abrirDescargas()));
        caja.addView(boton("Cerrar", false, v -> finish()));

        TextView ayuda = texto("Para la segunda pantalla, en el Mac abre ScreenMT y toca «Encender segunda pantalla».", 13, 0xFF7D8490, false);
        ayuda.setGravity(Gravity.CENTER);
        ayuda.setPadding(0, (int) (18 * dp), 0, 0);
        caja.addView(ayuda, new LinearLayout.LayoutParams((int) (340 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));

        inicio = caja;
        raiz.addView(caja, new FrameLayout.LayoutParams((int) (420 * dp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        teclado = new Teclado(this, new Teclado.Envio() {
            public void texto(String s) { Principal.this.texto(s); }
            public void tecla(int k, String m) { Principal.this.tecla(k, m); }
        });
        raiz.addView(teclado.campo, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        raiz.addView(teclado.barra, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        // la barra de teclas se sube encima del teclado de Android
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            raiz.setOnApplyWindowInsetsListener((v, ins) -> {
                teclado.barra.setTranslationY(-ins.getInsets(WindowInsets.Type.ime()).bottom);
                return ins;
            });
        }
        armarPanel(dp);
        setContentView(raiz);
    }

    /* ---------------- barra de ayudas ----------------
     * Un botón redondo flotante (ícono «i»): se arrastra para moverlo (se recuerda dónde quedó) y se toca
     * para abrir o cerrar la columna de íconos. Íconos de trazo en vector (res/drawable/ic_*.xml), sin texto.
     */

    private android.widget.ImageButton bAyuda;
    private android.widget.ImageButton iModo, iAltavoz, iMic;

    private void armarPanel(float dp) {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        bAyuda = new android.widget.ImageButton(this);
        bAyuda.setImageResource(R.drawable.ic_ayuda);
        bAyuda.setContentDescription("Ayudas");
        bAyuda.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        GradientDrawable fb = new GradientDrawable();
        fb.setShape(GradientDrawable.OVAL);
        fb.setColor(0xF02F5BD3);
        fb.setStroke((int) Math.max(1, 1.5f * dp), 0x55FFFFFF);
        bAyuda.setBackground(fb);
        bAyuda.setElevation(10 * dp);
        bAyuda.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), (int) (16 * dp));
        // arrastrar = mover; tocar sin mover = abrir o cerrar
        bAyuda.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy, x0, y0;
            boolean movio;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = panel.getX() - e.getRawX(); dy = panel.getY() - e.getRawY();
                        x0 = e.getRawX(); y0 = e.getRawY(); movio = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > 8 * dp) movio = true;
                        if (movio) moverPanel(e.getRawX() + dx, e.getRawY() + dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (movio) pref.edit().putFloat("panelX", panel.getX() / raiz.getWidth()).putFloat("panelY", panel.getY() / raiz.getHeight()).apply();
                        else contraer(botonesPanel.getVisibility() == View.VISIBLE);
                        return true;
                    default: return false;
                }
            }
        });
        panel.addView(bAyuda, new LinearLayout.LayoutParams((int) (60 * dp), (int) (60 * dp)));

        botonesPanel = new LinearLayout(this);
        botonesPanel.setOrientation(LinearLayout.VERTICAL);
        botonesPanel.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
        GradientDrawable f = new GradientDrawable();
        f.setColor(0xEB181B21);
        f.setCornerRadius(16 * dp);
        f.setStroke((int) Math.max(1, dp), 0x26FFFFFF);
        botonesPanel.setBackground(f);
        botonesPanel.setElevation(8 * dp);
        botonesPanel.addView(icono(R.drawable.ic_teclado, "Teclado", v -> { if (teclado.visible()) teclado.ocultar(); else teclado.mostrar(); }, dp));
        iModo = icono(R.drawable.ic_dedo, "Modo de toque", v -> { modoToque = !modoToque; pref.edit().putBoolean("modoToque", modoToque).apply(); pintarPanel(); }, dp);
        botonesPanel.addView(iModo);
        botonesPanel.addView(icono(R.drawable.ic_apps, "Cambiar de app", v -> tecla(48, "cmd"), dp));
        botonesPanel.addView(icono(R.drawable.ic_ventanas, "Ver ventanas", v -> tecla(126, "ctrl"), dp));
        botonesPanel.addView(icono(R.drawable.ic_copiar, "Copiar", v -> tecla(8, "cmd"), dp));
        botonesPanel.addView(icono(R.drawable.ic_pegar, "Pegar", v -> tecla(9, "cmd"), dp));
        botonesPanel.addView(icono(R.drawable.ic_deshacer, "Deshacer", v -> tecla(6, "cmd"), dp));
        iAltavoz = icono(R.drawable.ic_bocinas_no, "Bocinas", v -> cambiarAltavoz(), dp);
        botonesPanel.addView(iAltavoz);
        iMic = icono(R.drawable.ic_mic_no, "Micrófono", v -> cambiarMic(), dp);
        botonesPanel.addView(iMic);
        botonesPanel.addView(icono(R.drawable.ic_salir, "Salir", v -> finish(), dp));
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lb.topMargin = (int) (8 * dp);
        panel.addView(botonesPanel, lb);

        panel.setVisibility(View.GONE);
        raiz.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        contraer(pref.getBoolean("panelContraido", true));
        pintarPanel();
    }

    // posición guardada (como fracción de la pantalla); la primera vez, arriba a la derecha.
    // Se hace ya visible y medida: con la barra oculta su ancho es 0 y quedaría fuera de la pantalla.
    private void colocarPanel() {
        panel.post(() -> moverPanel(pref.getFloat("panelX", 1f) * raiz.getWidth(), pref.getFloat("panelY", 0.08f) * raiz.getHeight()));
    }

    // nunca fuera de la pantalla
    private void moverPanel(float x, float y) {
        float maxX = Math.max(0, raiz.getWidth() - panel.getWidth()), maxY = Math.max(0, raiz.getHeight() - panel.getHeight());
        panel.setX(Math.max(0, Math.min(maxX, x)));
        panel.setY(Math.max(0, Math.min(maxY, y)));
    }

    private void contraer(boolean si) {
        botonesPanel.setVisibility(si ? View.GONE : View.VISIBLE);
        pref.edit().putBoolean("panelContraido", si).apply();
        // al abrir crece hacia abajo: que no se salga
        panel.post(() -> moverPanel(panel.getX(), panel.getY()));
    }

    private android.widget.ImageButton icono(int img, String nombre, View.OnClickListener l, float dp) {
        android.widget.ImageButton b = new android.widget.ImageButton(this);
        b.setImageResource(img);
        b.setContentDescription(nombre);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(12 * dp);
        g.setColor(0x00000000);
        b.setBackground(g);
        b.setOnClickListener(l);
        if (Build.VERSION.SDK_INT >= 26) b.setTooltipText(nombre); // al mantener presionado dice qué hace
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (48 * dp), (int) (48 * dp));
        lp.topMargin = (int) (2 * dp);
        b.setLayoutParams(lp);
        return b;
    }

    private void pintarPanel() {
        if (iModo == null) return;
        iModo.setImageResource(modoToque ? R.drawable.ic_dedo : R.drawable.ic_raton);
        iAltavoz.setImageResource(altavoz ? R.drawable.ic_bocinas : R.drawable.ic_bocinas_no);
        iMic.setImageResource(mic ? R.drawable.ic_mic : R.drawable.ic_mic_no);
        ((GradientDrawable) iAltavoz.getBackground()).setColor(altavoz ? 0xFF2F5BD3 : 0x00000000);
        ((GradientDrawable) iMic.getBackground()).setColor(mic ? 0xFF2F5BD3 : 0x00000000);
    }

    private void cambiarAltavoz() {
        altavoz = !altavoz;
        pref.edit().putBoolean("altavoz", altavoz).apply();
        sonido.altavoz(altavoz);
        mandarOpciones();
        pintarPanel();
    }

    private void cambiarMic() {
        if (!mic && !tienePermisoMic()) {
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, PERMISO_MIC);
            return;
        }
        mic = !mic;
        pref.edit().putBoolean("mic", mic).apply();
        sonido.microfono(mic, pcm -> mandar(12, pcm));
        mandarOpciones();
        pintarPanel();
    }

    private boolean tienePermisoMic() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int pedido, String[] permisos, int[] r) {
        super.onRequestPermissionsResult(pedido, permisos, r);
        if (pedido == PERMISO_MIC && r.length > 0 && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) cambiarMic();
    }

    private TextView texto(String s, int sp, int color, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (negrita) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button boton(String s, boolean principal, View.OnClickListener l) {
        float dp = getResources().getDisplayMetrics().density;
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(principal ? 0xFF15181E : Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(10 * dp);
        g.setColor(principal ? Color.WHITE : 0xFF2A2E36);
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * dp));
        lp.topMargin = (int) (10 * dp);
        b.setLayoutParams(lp);
        return b;
    }

    private void pintarModo(Button b, boolean activo) {
        if (b == null) return;
        GradientDrawable g = (GradientDrawable) b.getBackground();
        g.setColor(activo ? 0xFF3567E8 : 0xFF2A2E36);
        b.setTextColor(Color.WHITE);
    }

    private void mostrarVideo() {
        inicio.setVisibility(View.GONE);
        video.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
        colocarPanel();
        ajustarVideo();
    }

    private void mostrarInicio() {
        video.setVisibility(View.GONE);
        panel.setVisibility(View.GONE);
        if (teclado.visible()) teclado.ocultar();
        inicio.setVisibility(View.VISIBLE);
        // lo que dejó el emparejamiento por cable mientras la app esperaba
        SharedPreferences p = getSharedPreferences("screenmt", MODE_PRIVATE);
        if (wifiClave.isEmpty() || wifiHost.isEmpty()) {
            wifiClave = p.getString("wifiClave", wifiClave);
            wifiHost = p.getString("wifiHost", wifiHost);
            if (porWifi) guardarDestino();
        }
        if (!porWifi) estado.setText("Esperando al Mac por cable…");
        else if (wifiClave.isEmpty()) estado.setText("Falta emparejar: escanea una vez el código QR del Mac, o conecta la tablet por cable un momento con ScreenMT abierta en el Mac.");
        else estado.setText("Esperando al Mac por wifi… En el Mac: ScreenMT → 2.ª pantalla → Por wifi → Encender.");
        pintarModo(botonCable, !porWifi);
        pintarModo(botonWifi, porWifi);
    }

    private void ajustarVideo() {
        int w = raiz.getWidth(), h = raiz.getHeight();
        if (w == 0 || h == 0) return;
        float esc = Math.min((float) w / ancho, (float) h / alto);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        int nw = Math.round(ancho * esc), nh = Math.round(alto * esc);
        if (lp.width != nw || lp.height != nh) { lp.width = nw; lp.height = nh; video.setLayoutParams(lp); }
    }

    /* ---------------- archivos ---------------- */

    private void elegirArchivos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, ELEGIR);
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent datos) {
        super.onActivityResult(pedido, resultado, datos);
        if (pedido != ELEGIR || resultado != RESULT_OK || datos == null) return;
        java.util.List<Uri> lista = new java.util.ArrayList<>();
        if (datos.getClipData() != null) for (int i = 0; i < datos.getClipData().getItemCount(); i++) lista.add(datos.getClipData().getItemAt(i).getUri());
        else if (datos.getData() != null) lista.add(datos.getData());
        estado.setText("Enviando " + lista.size() + " al Mac…");
        new Thread(() -> {
            int bien = 0;
            String error = null;
            for (Uri u : lista) {
                try { enviarAlMac(u); bien++; } catch (Exception e) { error = e.getMessage(); }
            }
            final int ok = bien;
            final String err = error;
            ui.post(() -> {
                mostrarInicio();
                Toast.makeText(this, err == null ? "Listo: " + ok + " en el Mac" : "No se pudo enviar. Conecta el cable y abre ScreenMT en el Mac.", Toast.LENGTH_LONG).show();
            });
        }, "envio").start();
    }

    // protocolo con ScreenMT del Mac: una línea JSON {"nombre","bytes"}, el archivo, y el Mac responde «OK»
    private void enviarAlMac(Uri u) throws Exception {
        String nombre = "archivo";
        long bytes = -1;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), t = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) nombre = c.getString(n);
                if (t >= 0 && !c.isNull(t)) bytes = c.getLong(t);
            }
        }
        if (bytes < 0) throw new IllegalStateException("tamaño desconocido");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PUERTO_ARCHIVOS), 2000);
            OutputStream o = s.getOutputStream();
            String cab = "{\"nombre\":" + org.json.JSONObject.quote(nombre) + ",\"bytes\":" + bytes + "}\n";
            o.write(cab.getBytes(StandardCharsets.UTF_8));
            try (InputStream in = getContentResolver().openInputStream(u)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            o.flush();
            s.setSoTimeout(30000);
            String r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (!"OK".equals(r)) throw new IllegalStateException(r == null ? "sin respuesta" : r);
        }
    }

    // la app de Archivos en Descargas (nunca el navegador)
    // se pide por nombre a la app Archivos del sistema: sin nombre, algunas marcas preguntan o mandan a otra app
    private void abrirDescargas() {
        Uri raiz = DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads");
        for (String app : new String[] {"com.google.android.documentsui", "com.android.documentsui", null}) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(raiz, DocumentsContract.Root.MIME_TYPE_ITEM);
            if (app != null) i.setPackage(app);
            try { startActivity(i); return; } catch (Exception siguiente) { }
        }
        Intent archivos = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.nbu.files");
        if (archivos != null) { startActivity(archivos); return; }
        Toast.makeText(this, "Abre la app Archivos y entra a Descargas.", Toast.LENGTH_LONG).show();
    }

    /* ---------------- pantalla completa y cierre ---------------- */

    @Override
    public void onWindowFocusChanged(boolean foco) {
        super.onWindowFocusChanged(foco);
        if (foco) pantallaCompleta();
    }

    @SuppressWarnings("deprecation")
    private void pantallaCompleta() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private static void dormir(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignorada) { } }

    @Override
    protected void onDestroy() {
        vivo = false;
        dejarDeBuscar();
        sonido.apagarTodo();
        cerrarSocket();
        salida.shutdownNow();
        super.onDestroy();
    }
}
