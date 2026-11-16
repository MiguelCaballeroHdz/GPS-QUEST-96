package com.gpsquest96.route;

/**
 * Acción automática de ruta (spec 004, slice 5; R7, R9).
 * <p>
 * Decide qué hace el motor con un nodo al llegar a su radio y acota el
 * anti-atasco. Es Java puro, sin Android, para poder verificarse con host
 * tests. No toca el juego: solo decide; el disparo (joypad) y el cambio
 * a Modo Sofá viven en MainActivity, y el armado vive en GameEngine.
 * <p>
 * Reglas (spec 004 §4):
 * <ul>
 *   <li><b>R9 — Interacción:</b> {@code enter} y {@code talk} se ejecutan
 *       dentro del radio, sin cambiar de modo; {@code use}, {@code battle}
 *       y {@code wait} pasan a Modo Sofá (los hace el jugador a mano con
 *       los botones en pantalla).</li>
 *   <li><b>R7 — Anti-atasco:</b> máximo 3 intentos de 300 ms, solo en
 *       estado {@code MAP}. Cada intento aprieta la cardinal del vector
 *       de ruta (rumbo del fix al nodo) + A. Aborta ante cambio de
 *       pantalla (el estado deja de ser {@code MAP}) o al resolverse el
 *       nodo. Aviso háptico una vez por nodo.</li>
 * </ul>
 * <p>
 * La secuencia es una por nodo: una vez agotados los 3 intentos, el
 * nodo queda para el jugador (botón A en pantalla). No se rearma solo:
 * rearmar sin límite convertiría el anti-atasco acotado en un
 * pulsador infinito.
 */
public final class RouteAutoAction {

    private RouteAutoAction() {
    }

    /** Máximo de intentos de auto-A por nodo (R7). */
    public static final int MAX_ATTEMPTS = 3;

    /** Separación entre intentos (R7): 300 ms. */
    public static final long ATTEMPT_INTERVAL_MS = 300L;

    /** Duración del aviso háptico, una vez por nodo (R7). */
    public static final long HAPTIC_MS = 60L;

    // Bits del joypad (Mmu.setJoypad; 0 = presionado). Mismos valores que
    // MainActivity (joyDir bits 4-7, joyAction bits 0-3).
    /** Botón A (bit 0). */
    public static final int BTN_A = 0x01;
    /** Cruceta: derecha (bit 4). */
    public static final int DIR_RIGHT = 0x10;
    /** Cruceta: izquierda (bit 5). */
    public static final int DIR_LEFT = 0x20;
    /** Cruceta: arriba (bit 6). */
    public static final int DIR_UP = 0x40;
    /** Cruceta: abajo (bit 7). */
    public static final int DIR_DOWN = 0x80;

    /** Qué hace el motor con un nodo al llegar a su radio (R9). */
    public enum Kind {
        /** WALK: nada automático; el jugador camina. */
        NONE,
        /** ENTER/TALK: auto-A dentro del radio, sin cambiar de modo. */
        AUTO_A,
        /** USE/BATTLE/WAIT: no se dispara solo; pasa a Modo Sofá. */
        GO_SOFA
    }

    /**
     * Enrutado R9 por acción de nodo.
     */
    public static Kind kindFor(RouteNode.Action action) {
        if (action == null) return Kind.NONE;
        switch (action) {
            case ENTER:
            case TALK:
                return Kind.AUTO_A;
            case USE:
            case BATTLE:
            case WAIT:
                return Kind.GO_SOFA;
            default:
                return Kind.NONE;
        }
    }

    /**
     * Rumbo inicial de ({@code lat1},{@code lon1}) a ({@code lat2},
     * {@code lon2}), en grados 0-360 (0=N, 90=E, 180=S, 270=W).
     */
    public static float bearingDeg(double lat1, double lon1,
            double lat2, double lon2) {
        double lat1r = Math.toRadians(lat1);
        double lat2r = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(lat2r);
        double x = Math.cos(lat1r) * Math.sin(lat2r)
                - Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLon);
        double brg = Math.toDegrees(Math.atan2(y, x));
        return (float) ((brg + 360.0) % 360.0);
    }

    /**
     * Byte de cruceta (bits 4-7) para la cardinal dominante de un rumbo.
     * Mismos cuadrantes que {@code MainActivity.applyJoypadFromBearing}:
     * N→UP, E→RIGHT, S→DOWN, W→LEFT. Los demás bits quedan en 1 (sin
     * pulsar); el llamador combina con {@link #BTN_A} si toca.
     */
    public static int dirBitsForBearing(float bearingDeg) {
        float b = ((bearingDeg % 360f) + 360f) % 360f;
        int bits = 0xF0;
        if (b >= 315f || b < 45f) bits &= ~DIR_UP;          // N → UP
        else if (b >= 45f && b < 135f) bits &= ~DIR_RIGHT;  // E → RIGHT
        else if (b >= 135f && b < 225f) bits &= ~DIR_DOWN;  // S → DOWN
        else bits &= ~DIR_LEFT;                             // W → LEFT
        return bits;
    }

    /** Resultado de un tick de la secuencia anti-atasco. */
    public enum Step {
        /** Toca pulsar ahora (cardinal + A durante el intento). */
        PRESS,
        /** Aún no toca el siguiente intento; esperar. */
        WAIT,
        /** Abortada: la pantalla cambió (ya no es MAP). */
        ABORTED,
        /** Terminada: se agotaron los intentos. */
        DONE
    }

    /**
     * Secuencia anti-atasco de un nodo (R7): como mucho 3 intentos
     * separados 300 ms, solo en estado MAP.
     */
    public static final class Sequence {
        /** Nodo al que pertenece la secuencia. */
        public final int nodeIndex;

        private int attemptsLeft;
        private long nextAttemptAtMs;
        private boolean aborted;

        public Sequence(int nodeIndex) {
            this.nodeIndex = nodeIndex;
            this.attemptsLeft = MAX_ATTEMPTS;
            this.nextAttemptAtMs = 0L; // el primer intento toca ya
            this.aborted = false;
        }

        /** true si la secuencia es para este nodo. */
        public boolean isFor(int nodeIndex) {
            return this.nodeIndex == nodeIndex;
        }

        /** Intentos que quedan. */
        public int attemptsLeft() {
            return attemptsLeft;
        }

        /** Intentos ya consumidos. */
        public int attemptsUsed() {
            return MAX_ATTEMPTS - attemptsLeft;
        }

        /** true si se abortó o se agotaron los intentos. */
        public boolean isDone() {
            return aborted || attemptsLeft <= 0;
        }

        /** true si se abortó por cambio de pantalla. */
        public boolean isAborted() {
            return aborted;
        }

        /** Milisegundos hasta el próximo intento (0 si ya toca). */
        public long millisUntilNextAttempt(long nowMs) {
            return Math.max(0L, nextAttemptAtMs - nowMs);
        }

        /**
         * Un tick: decide si toca pulsar ahora.
         *
         * @param nowMs reloj en milisegundos
         * @param inMap true si el emulador está en estado MAP; fuera de
         *              MAP no se intenta nada y la secuencia aborta
         *              (cambio de pantalla)
         */
        public Step step(long nowMs, boolean inMap) {
            if (aborted) return Step.ABORTED;
            if (attemptsLeft <= 0) return Step.DONE;
            if (!inMap) {
                aborted = true;
                return Step.ABORTED;
            }
            if (nowMs < nextAttemptAtMs) return Step.WAIT;
            attemptsLeft--;
            nextAttemptAtMs = nowMs + ATTEMPT_INTERVAL_MS;
            return Step.PRESS;
        }

        /** Aborta la secuencia (p. ej. el nodo se resolvió). */
        public void abort() {
            aborted = true;
        }
    }
}
