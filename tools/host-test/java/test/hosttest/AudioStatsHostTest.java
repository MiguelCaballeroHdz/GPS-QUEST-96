package hosttest;

import com.gpsquest96.emulator.AudioStats;

/**
 * Host test de spec 020 slice 2 (audio Java: D-AUD-1/2/3).
 * <p>
 * Sin Android, sin red y sin POCO: compila la clase real
 * {@code com.gpsquest96.emulator.AudioStats} contra stubs de Android.
 * <p>
 * Cubre:
 *  1. Estado inicial: todo a cero y la línea de log con ese estado.
 *  2. Deltas de underruns y de muestras tiradas: el primer total visto
 *     es la base (no suma nada) y los siguientes suman solo la diferencia.
 *  3. Totales que retroceden (p. ej. tras un reset de la APU) no restan.
 *  4. Errores de escritura y muestras escritas se acumulan; los conteos
 *     negativos o cero no suman.
 *  5. Intervalo de log: la primera llamada pide loguear, las siguientes
 *     dentro de 5 s no, y al cumplir los 5 s vuelve a pedirlo.
 * <p>
 * No cubre (dispositivo): que el AudioTrack de verdad deje de vaciarse
 * en el POCO (gate: 5 min de juego sin rachas regulares de silencio en
 * silencedetect) ni la prioridad del hilo ni el join del stop. Quedan
 * "pendiente de gate POCO".
 */
public class AudioStatsHostTest {

    private static int checks = 0;

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) {
            System.out.println("FAIL: " + what);
            throw new AssertionError(what);
        }
    }

    public static void main(String[] args) {
        initialState();
        underrunDeltas();
        droppedDeltas();
        backwardsTotalsDoNotSubtract();
        writeErrorsAndWritten();
        logInterval();

        System.out.println("AudioStatsHostTest: " + checks + " checks, 0 failures");
    }

    private static void initialState() {
        AudioStats s = new AudioStats();
        check(s.getUnderruns() == 0, "underruns iniciales 0");
        check(s.getDroppedSamples() == 0, "tiradas iniciales 0");
        check(s.getWriteErrors() == 0, "errores iniciales 0");
        check(s.getSamplesWritten() == 0, "escritos iniciales 0");
        check(s.format().equals(
                "aud underruns=0 tiradas=0 errEscritura=0 escritos=0"),
                "formato inicial exacto: " + s.format());
    }

    private static void underrunDeltas() {
        AudioStats s = new AudioStats();
        s.noteUnderrunTotal(3); // base: no suma
        check(s.getUnderruns() == 0, "primer total es base, no suma");
        s.noteUnderrunTotal(3); // sin cambio
        check(s.getUnderruns() == 0, "mismo total no suma");
        s.noteUnderrunTotal(5); // +2
        check(s.getUnderruns() == 2, "delta +2");
        s.noteUnderrunTotal(12); // +7
        check(s.getUnderruns() == 9, "delta acumulado 9");
        s.noteUnderrunTotal(-1); // inválido: se ignora
        check(s.getUnderruns() == 9, "total negativo se ignora");
    }

    private static void droppedDeltas() {
        AudioStats s = new AudioStats();
        s.noteDroppedTotal(0);
        check(s.getDroppedSamples() == 0, "base 0 no suma");
        s.noteDroppedTotal(665); // +665 (12 frames sin drenar)
        check(s.getDroppedSamples() == 665, "delta +665");
        s.noteDroppedTotal(665);
        check(s.getDroppedSamples() == 665, "mismo total no suma");
        s.noteDroppedTotal(700); // +35
        check(s.getDroppedSamples() == 700, "delta acumulado 700");
    }

    private static void backwardsTotalsDoNotSubtract() {
        AudioStats s = new AudioStats();
        s.noteUnderrunTotal(10);
        s.noteUnderrunTotal(4); // retroceso (reset): no resta...
        check(s.getUnderruns() == 0, "retroceso de underruns no resta");
        // ...y el 4 pasa a ser la base nueva: desde ahí solo suman deltas.
        s.noteUnderrunTotal(6); // +2 desde la nueva base
        check(s.getUnderruns() == 2, "delta desde la nueva base");
        s.noteUnderrunTotal(9); // +3
        check(s.getUnderruns() == 5, "delta acumulado desde la nueva base");
    }

    private static void writeErrorsAndWritten() {
        AudioStats s = new AudioStats();
        s.noteWriteError();
        s.noteWriteError();
        check(s.getWriteErrors() == 2, "2 errores de escritura");
        s.noteWritten(1024);
        s.noteWritten(512);
        check(s.getSamplesWritten() == 1536, "1536 int16 escritos");
        s.noteWritten(0);
        s.noteWritten(-5);
        check(s.getSamplesWritten() == 1536, "cero/negativo no suma");
        check(s.format().equals(
                "aud underruns=0 tiradas=0 errEscritura=2 escritos=1536"),
                "formato con datos: " + s.format());
    }

    private static void logInterval() {
        AudioStats s = new AudioStats();
        check(s.shouldLog(0), "primera llamada pide log");
        check(!s.shouldLog(0), "misma marca no repite");
        check(!s.shouldLog(4999), "a 4999 ms no toca");
        check(s.shouldLog(5000), "a 5000 ms toca");
        check(!s.shouldLog(9999), "a 9999 ms no toca");
        check(s.shouldLog(10000), "a 10000 ms toca");
    }
}
