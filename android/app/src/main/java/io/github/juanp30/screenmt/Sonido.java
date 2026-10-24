package io.github.juanp30.screenmt;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bocinas y micrófono de la tablet para el Mac.
 * Bocinas: el Mac manda PCM 16 bits estéreo 48 kHz (paquete 4) y aquí suena con poca latencia.
 * Micrófono: se graba PCM 16 bits mono 48 kHz en trozos de 20 ms y se entrega a quien lo mande al Mac.
 */
final class Sonido {
    interface Envio { void mandar(byte[] pcm); }

    private static final int TASA = 48000;
    // si el sonido se atrasa se tiran trozos: mejor un hueco que un retraso que crece
    private final ArrayBlockingQueue<byte[]> cola = new ArrayBlockingQueue<>(6);
    private Thread altavoz, microfono;
    private volatile boolean sonando, grabando;

    void recibir(byte[] pcm) {
        if (!sonando) return;
        if (!cola.offer(pcm)) { cola.poll(); cola.offer(pcm); }
    }

    synchronized void altavoz(boolean si) {
        if (si == sonando) return;
        sonando = si;
        cola.clear();
        if (!si) { if (altavoz != null) altavoz.interrupt(); return; }
        altavoz = new Thread(() -> {
            int min = AudioTrack.getMinBufferSize(TASA, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(TASA).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(min * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            AudioTrack t = b.build();
            t.play();
            try {
                while (sonando) {
                    byte[] p = cola.take();
                    t.write(p, 0, p.length);
                }
            } catch (InterruptedException fin) {
                // apagado
            } finally {
                t.stop();
                t.release();
            }
        }, "bocinas");
        altavoz.start();
    }

    /** Requiere el permiso RECORD_AUDIO ya concedido. */
    synchronized void microfono(boolean si, Envio envio) {
        if (si == grabando) return;
        grabando = si;
        if (!si) { if (microfono != null) microfono.interrupt(); return; }
        microfono = new Thread(() -> {
            int trozo = TASA / 50 * 2; // 20 ms
            int min = Math.max(trozo * 4, AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
            AudioRecord r;
            try {
                r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min);
                r.startRecording();
            } catch (SecurityException | IllegalStateException e) {
                grabando = false;
                return;
            }
            try {
                byte[] b = new byte[trozo];
                while (grabando && !Thread.currentThread().isInterrupted()) {
                    int n = r.read(b, 0, b.length);
                    if (n > 0) envio.mandar(java.util.Arrays.copyOf(b, n));
                }
            } finally {
                r.stop();
                r.release();
            }
        }, "microfono");
        microfono.start();
    }

    void apagarTodo() {
        altavoz(false);
        grabando = false;
        if (microfono != null) microfono.interrupt();
    }
}
