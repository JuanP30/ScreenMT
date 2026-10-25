package io.github.juanp30.screenmt;

import android.view.MotionEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Modo lápiz, para lápices pasivos (de goma o de disco) en tablets sin lápiz propio: Android los ve como un
 * dedo más, así que aquí se adivina con software lo que un lápiz de verdad trae de fábrica.
 *
 * - Rechazo de palma por tamaño: la tablet dice cuánto mide cada contacto. La punta es pequeña; la palma,
 *   grande. Lo grande se ignora; si un contacto empieza pequeño y crece (la mano al apoyarse), se corta.
 *   Android 13+ además marca los toques que reconoce como palma (FLAG_CANCELED): también se cortan.
 * - Mientras hay un trazo, cualquier otro contacto se ignora (la mano o un dedo de apoyo).
 * - Dos dedos que llegan casi a la vez (antes de que empiece el trazo) siguen siendo desplazar y zoom.
 * - Trazo suave: filtro que suaviza más cuando el lápiz va despacio (temblor) y casi nada cuando va rápido
 *   (para no quedarse atrás). Se usan también los puntos intermedios que Android agrupa (historial).
 * - Presión simulada por la velocidad, como perfect-freehand (MIT): despacio = más presión.
 * - Tocar y soltar sin moverse = clic: el lápiz también sirve para botones y menús.
 *
 * Manda a la Salida: "pd" (apoya), "pm" (mueve), "pu" (levanta), "pc" (cortado por palma), con x e y de 0 a 1
 * y la presión de 0 a 1.
 */
final class Lapiz {
    interface Salida { void con(String t, float x, float y, float presion); }
    interface Dedos { void con(MotionEvent e); }

    // ajustes (Principal los guarda en las preferencias)
    float maxPuntaMm = 9f;     // contactos más grandes que esto = palma
    float suavizado = 0.5f;    // 0 = nada … 1 = mucho
    boolean presion = true;

    // para la pantalla de ajustes: así se calibra con el lápiz de cada uno
    volatile float ultimaPuntaMm = -1, ultimaPalmaMm = -1;
    volatile boolean sinTamano = false; // la tablet no informa el tamaño: sin rechazo de palma

    private final Salida salida;
    private final Dedos dedos;
    private final float mmPorPx;

    private int pluma = -1;                       // id del contacto que dibuja
    private boolean enviado;                      // ya se mandó "pd"
    private boolean conDedos;                     // se pasó el gesto a «dos dedos»
    private final Set<Integer> ignorados = new HashSet<>();
    private float ax, ay, sx, sy, px, py, pres;   // inicio, suavizado, último crudo, presión actual
    private long t0, tu;
    private float w = 1, h = 1;

    private static final long ESPERA_MS = 45;     // espera corta antes de apoyar: deja ver si es un gesto de dos dedos
    private static final float MOVER_MM = 1.2f;

    Lapiz(Salida salida, Dedos dedos, float xdpi) {
        this.salida = salida;
        this.dedos = dedos;
        this.mmPorPx = 25.4f / Math.max(80f, xdpi);
    }

    private float tamanoMm(MotionEvent e, int i) {
        float m = e.getTouchMajor(i);
        if (m <= 0) { sinTamano = true; return 0; }
        sinTamano = false;
        return m * mmPorPx;
    }

    void alTocar(MotionEvent e, float ancho, float alto) {
        w = ancho; h = alto;
        int a = e.getActionMasked();
        if (conDedos) {
            dedos.con(e);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) reiniciar();
            return;
        }
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex(), id = e.getPointerId(i);
                float mm = tamanoMm(e, i);
                if (mm > maxPuntaMm) { ignorados.add(id); ultimaPalmaMm = mm; break; }
                if (pluma == -1) { empezar(e, i, id, mm); break; }
                if (!enviado && e.getPointerCount() == 2 && ignorados.isEmpty()) {
                    // dos dedos casi a la vez: es desplazar o zoom, no un trazo
                    pluma = -1;
                    conDedos = true;
                    MotionEvent d = MotionEvent.obtain(e.getDownTime(), e.getEventTime(), MotionEvent.ACTION_DOWN, e.getX(0), e.getY(0), 0);
                    dedos.con(d);
                    d.recycle();
                    dedos.con(e);
                    break;
                }
                ignorados.add(id); // ya hay trazo: este contacto es la mano o un dedo de apoyo
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pluma == -1) break;
                int i = e.findPointerIndex(pluma);
                if (i < 0) break;
                float mm = tamanoMm(e, i);
                if (mm > maxPuntaMm * 1.4f) { cortarPorPalma(mm); break; } // empezó pequeño y creció: era la mano
                for (int k = 0; k < e.getHistorySize(); k++) punto(e.getHistoricalX(i, k), e.getHistoricalY(i, k), e.getHistoricalEventTime(k));
                punto(e.getX(i), e.getY(i), e.getEventTime());
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                int i = e.getActionIndex(), id = e.getPointerId(i);
                ignorados.remove(id);
                if (id == pluma) {
                    if ((e.getFlags() & MotionEvent.FLAG_CANCELED) != 0) cortarPorPalma(-1);
                    else terminar(e.getX(i), e.getY(i));
                }
                if (a == MotionEvent.ACTION_UP) reiniciar();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                if (pluma != -1 && enviado) salida.con("pc", sx / w, sy / h, 0);
                reiniciar();
                break;
            default:
                break;
        }
    }

    private void empezar(MotionEvent e, int i, int id, float mm) {
        pluma = id;
        enviado = false;
        ax = sx = px = e.getX(i);
        ay = sy = py = e.getY(i);
        t0 = tu = e.getEventTime();
        pres = 0.5f;
        if (mm > 0) ultimaPuntaMm = mm;
    }

    private void punto(float x, float y, long t) {
        long dt = Math.max(1, t - tu);
        float vel = (float) Math.hypot(x - px, y - py) * mmPorPx / dt; // mm por ms
        px = x; py = y; tu = t;
        // filtro: despacio suaviza (quita temblor), rápido sigue al lápiz
        float min = 1f - 0.85f * suavizado;
        float alfa = min + (1f - min) * Math.min(1f, vel / 0.6f);
        sx += (x - sx) * alfa;
        sy += (y - sy) * alfa;
        // presión simulada: rápido = trazo fino, despacio = grueso; cambia suave para no hacer escalones
        float objetivo = presion ? 1f - 0.7f * Math.min(1f, vel / 1.2f) : 1f;
        pres += (objetivo - pres) * 0.25f;
        if (!enviado) {
            boolean movio = Math.hypot(x - ax, y - ay) * mmPorPx > MOVER_MM;
            if (!movio && t - t0 < ESPERA_MS) return;
            enviado = true;
            salida.con("pd", ax / w, ay / h, presion ? pres : 1f);
        }
        salida.con("pm", sx / w, sy / h, presion ? pres : 1f);
    }

    private void terminar(float x, float y) {
        if (!enviado) {
            // tocar y soltar sin moverse: clic donde apoyó
            salida.con("pd", ax / w, ay / h, 1f);
            salida.con("pu", ax / w, ay / h, 0);
        } else {
            punto(x, y, tu + 8);
            salida.con("pu", sx / w, sy / h, 0);
        }
        pluma = -1;
        enviado = false;
    }

    private void cortarPorPalma(float mm) {
        if (mm > 0) ultimaPalmaMm = mm;
        if (enviado) salida.con("pc", sx / w, sy / h, 0);
        ignorados.add(pluma);
        pluma = -1;
        enviado = false;
    }

    private void reiniciar() {
        pluma = -1;
        enviado = false;
        conDedos = false;
        ignorados.clear();
    }
}
