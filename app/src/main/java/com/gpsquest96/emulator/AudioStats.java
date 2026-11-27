package com.gpsquest96.emulator;

/**
 * Contadores del camino de audio (spec 020, slice 2: D-AUD-1/2/3).
 * <p>
 * Java puro, sin Android: la lógica vive aquí para poder verificarla con
 * host tests; {@code GameEngine} solo alimenta los contadores desde el
 * hilo de audio y {@code AudioTrack}.
 * <p>
 * Qué se cuenta:
 * <ul>
 *   <li><b>underruns</b>: deltas de {@code AudioTrack.getUnderrunCount()}
 *       (veces que el AudioTrack se quedó sin muestras que reproducir:
 *       eso es un corte audible).</li>
 *   <li><b>tiradas</b>: deltas de {@code Apu.getDroppedSamples()} (muestras
 *       estéreo que la APU descartó porque su anillo estaba lleno).</li>
 *   <li><b>errores de escritura</b>: veces que {@code AudioTrack.write()}
 *       devolvió un valor negativo.</li>
 *   <li><b>escritos</b>: int16 entregados al AudioTrack con éxito.</li>
 * </ul>
 * El log sale como mucho cada {@link #LOG_INTERVAL_MS} ms; la primera
 * llamada a {@link #shouldLog(long)} ya pide loguear, para no esperar 5 s
 * al primer dato.
 */
public class AudioStats {

    /** Intervalo mínimo entre líneas de log AUD012. */
    public static final long LOG_INTERVAL_MS = 5000;

    private long underruns;
    private long droppedSamples;
    private long writeErrors;
    private long samplesWritten;

    // Últimos totales acumulados vistos (-1 = aún no se ha visto ninguno).
    private long lastUnderrunTotal = -1;
    private long lastDroppedTotal = -1;

    private long lastLogMs = -LOG_INTERVAL_MS;

    /** Suma el delta del total acumulado de underruns del AudioTrack. */
    public synchronized void noteUnderrunTotal(long total) {
        if (total < 0) return;
        if (lastUnderrunTotal >= 0 && total >= lastUnderrunTotal) {
            underruns += total - lastUnderrunTotal;
        }
        lastUnderrunTotal = total;
    }

    /** Suma el delta del total acumulado de muestras tiradas por la APU. */
    public synchronized void noteDroppedTotal(long total) {
        if (total < 0) return;
        if (lastDroppedTotal >= 0 && total >= lastDroppedTotal) {
            droppedSamples += total - lastDroppedTotal;
        }
        lastDroppedTotal = total;
    }

    /** Un write() del AudioTrack devolvió error (valor negativo). */
    public synchronized void noteWriteError() {
        writeErrors++;
    }

    /** int16 entregados al AudioTrack con éxito. */
    public synchronized void noteWritten(int count) {
        if (count > 0) samplesWritten += count;
    }

    /**
     * ¿Toca loguear? Como mucho una línea cada {@link #LOG_INTERVAL_MS} ms.
     * La primera llamada devuelve true.
     */
    public synchronized boolean shouldLog(long nowMs) {
        if (nowMs - lastLogMs >= LOG_INTERVAL_MS) {
            lastLogMs = nowMs;
            return true;
        }
        return false;
    }

    /** Línea de una sola pieza para el log AUD012 / PUMP012. */
    public synchronized String format() {
        return "aud underruns=" + underruns
                + " tiradas=" + droppedSamples
                + " errEscritura=" + writeErrors
                + " escritos=" + samplesWritten;
    }

    public synchronized long getUnderruns() {
        return underruns;
    }

    public synchronized long getDroppedSamples() {
        return droppedSamples;
    }

    public synchronized long getWriteErrors() {
        return writeErrors;
    }

    public synchronized long getSamplesWritten() {
        return samplesWritten;
    }
}
