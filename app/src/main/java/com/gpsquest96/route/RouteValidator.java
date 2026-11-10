package com.gpsquest96.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Doble validación en modo lectura (spec 004, R3, slice 3).
 * <p>
 * Mide las tres señales de la regla R3 SIN mover nada: no escribe en el
 * juego, no avanza {@code posicion} (aún no existe: eso es el slice 4) y no
 * modifica la ruta ni el nodo. Es Java puro, sin Android, para poder
 * verificarse con host tests.
 * <p>
 * Las tres señales:
 * <ol>
 *   <li><b>Llegada:</b> el fix GPS está dentro del radio del nodo
 *       ({@code node.radiusM}, 15 m según §5.2).</li>
 *   <li><b>Consumo:</b> la posición ROM cambió entre el snapshot previo y el
 *       actual (el avatar no está bloqueado).</li>
 *   <li><b>Destino:</b> el motor alcanzó la casilla del nodo
 *       ({@code destMapId} + x,y del destino del nodo).</li>
 * </ol>
 * La posición ROM llega como snapshot de {@code nativeGetCurrentMap}
 * ({@code wMapGroup, wMapNumber, wYCoord, wXCoord}, spec 009 slice 2): se usa
 * lo que ya existe, sin offsets nuevos. La comparación de mapa usa la
 * codificación provisional {@link #mapIdOf(int, int)}: el {@code map_id} de
 * la spec 004 no tiene codificación definida en ningún sitio del repo, así
 * que este slice la fija por escrito y queda pendiente confirmarla con el
 * volcado POCO (005) antes de que el slice 4 la use para avanzar
 * {@code posicion}.
 * <p>
 * Un nodo sin destino ROM declarado ({@code destMapId == NO_DEST}, que es el
 * caso de todos los nodos que genera hoy {@link RouteGenerator}) no inventa
 * destino: la señal 3 queda {@code NOT_APPLICABLE} y la lectura se marca
 * dudosa.
 */
public final class RouteValidator {

    private RouteValidator() {
    }

    // ------------------------------------------------------------------
    // Snapshot de posición ROM (una lectura de nativeGetCurrentMap)
    // ------------------------------------------------------------------

    /** Posición del avatar en la ROM en un instante (VBlank). */
    public static final class RomSnapshot {
        public final int mapGroup;
        public final int mapNumber;
        public final int x;
        public final int y;
        public final boolean valid;

        public RomSnapshot(int mapGroup, int mapNumber, int x, int y, boolean valid) {
            this.mapGroup = mapGroup;
            this.mapNumber = mapNumber;
            this.x = x;
            this.y = y;
            this.valid = valid;
        }

        /** Snapshot inválido: sin ROM, sin MMU o mapa fuera de tramo. */
        public static RomSnapshot invalid() {
            return new RomSnapshot(-1, -1, -1, -1, false);
        }

        /**
         * Construye el snapshot desde el {@code int[]} de
         * {@code nativeGetCurrentMap}: {@code {wMapGroup, wMapNumber, wYCoord,
         * wXCoord}}. Un array nulo o corto es un snapshot inválido.
         */
        public static RomSnapshot fromCurrentMap(int[] m) {
            if (m == null || m.length < 4) return invalid();
            return new RomSnapshot(m[0], m[1], m[3], m[2], true);
        }

        /** true si la posición cambió respecto a {@code other}. */
        public boolean movedFrom(RomSnapshot other) {
            if (other == null || !other.valid || !valid) return false;
            return mapGroup != other.mapGroup || mapNumber != other.mapNumber
                    || x != other.x || y != other.y;
        }

        @Override
        public String toString() {
            if (!valid) return "RomSnapshot{inválido}";
            return "RomSnapshot{" + mapGroup + "/" + mapNumber
                    + " y=" + y + " x=" + x + "}";
        }
    }

    // ------------------------------------------------------------------
    // Fix GPS (LocationService, 5 s / 5 m)
    // ------------------------------------------------------------------

    /** Un fix GPS. {@code accuracyM} es {@code NaN} si se desconoce. */
    public static final class GpsFix {
        public final double lat;
        public final double lon;
        public final float accuracyM;

        public GpsFix(double lat, double lon, float accuracyM) {
            this.lat = lat;
            this.lon = lon;
            this.accuracyM = accuracyM;
        }

        public GpsFix(double lat, double lon) {
            this(lat, lon, Float.NaN);
        }
    }

    // ------------------------------------------------------------------
    // Resultado
    // ------------------------------------------------------------------

    /** Señal 3 (destino): alcanzado, no alcanzado, o sin destino declarado. */
    public enum Destination {
        MATCH, MISMATCH, NOT_APPLICABLE
    }

    /** Resultado de evaluar las tres señales de R3 sobre un nodo. */
    public static final class Validation {
        /** Señal 1: el fix está dentro del radio del nodo. */
        public final boolean arrival;
        /** Señal 2: la posición ROM cambió entre snapshots. */
        public final boolean consumption;
        /** Señal 3: el motor está en la casilla destino del nodo. */
        public final Destination destination;
        /** Distancia fix → nodo, en metros. */
        public final double distanceM;
        /** La posición ROM cambió (detalle de la señal 2). */
        public final boolean romMoved;
        /** true si alguna señal es dudosa (ver {@link #doubts}). */
        public final boolean doubtful;
        /** Motivos de duda; vacío si la lectura es limpia. */
        public final List<String> doubts;
        /**
         * true si alguna duda impide ACTUAR sobre la lectura (slice 4).
         * Bloquean: sin fix, fix impreciso o de precisión desconocida, y
         * cadena de snapshots rota (sin previo). NO bloquean las dudas de
         * destino (nodo sin destino declarado, mapa incompatible o casilla
         * distinta): esas las decide la regla de destino del slice 4
         * (avance sin resolver, o re-enganche), no la calidad del fix.
         */
        public final boolean blocked;

        Validation(boolean arrival, boolean consumption, Destination destination,
                double distanceM, boolean romMoved, List<String> doubts, boolean blocked) {
            this.arrival = arrival;
            this.consumption = consumption;
            this.destination = destination;
            this.distanceM = distanceM;
            this.romMoved = romMoved;
            this.doubts = Collections.unmodifiableList(doubts);
            this.doubtful = !doubts.isEmpty();
            this.blocked = blocked;
        }

        /** Una línea de log: `R3 nodo=N llegada=S/N consumo=S/N destino=OK/NO/N-A dudosa=S/N [motivos]`. */
        public String formatLog(int nodeIndex) {
            StringBuilder sb = new StringBuilder();
            sb.append("R3 nodo=").append(nodeIndex)
                    .append(" llegada=").append(arrival ? "SI" : "NO")
                    .append(" consumo=").append(consumption ? "SI" : "NO")
                    .append(" destino=");
            switch (destination) {
                case MATCH: sb.append("OK"); break;
                case MISMATCH: sb.append("NO"); break;
                default: sb.append("N-A"); break;
            }
            sb.append(" dudosa=").append(doubtful ? "SI" : "NO");
            sb.append(" dist=").append(String.format(Locale.ROOT, "%.1f", distanceM)).append("m");
            if (!doubts.isEmpty()) {
                sb.append(" [");
                for (int i = 0; i < doubts.size(); i++) {
                    if (i > 0) sb.append("; ");
                    sb.append(doubts.get(i));
                }
                sb.append(']');
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // Codificación de identidad de mapa (provisional, ver javadoc de clase)
    // ------------------------------------------------------------------

    /**
     * Codifica la identidad de mapa (grupo, número) en el {@code map_id}
     * entero que declara {@link RouteNode#destMapId}.
     * <p>
     * Codificación provisional: {@code grupo * 256 + número}. Debe
     * confirmarse con el volcado POCO (005) antes de usarse para avanzar
     * {@code posicion} (slice 4).
     */
    public static int mapIdOf(int group, int map) {
        return group * 256 + map;
    }

    // ------------------------------------------------------------------
    // Evaluación (solo lectura: no toca ruta, nodo ni juego)
    // ------------------------------------------------------------------

    /**
     * Evalúa las tres señales de R3 para {@code node} con el snapshot ROM
     * actual ({@code cur}), el snapshot previo ({@code prev}, null si no
     * hay) y el fix GPS ({@code fix}, null si no hay).
     * <p>
     * Nunca escribe: no modifica el nodo, la ruta ni el estado del juego.
     * Las lecturas dudosas quedan marcadas en
     * {@link Validation#doubts}: fix impreciso, avatar bloqueado o parado,
     * mapa incompatible y nodo sin destino declarado.
     */
    public static Validation evaluate(RomSnapshot prev, RomSnapshot cur,
            GpsFix fix, RouteNode node) {
        if (node == null) throw new IllegalArgumentException("nodo nulo");

        List<String> doubts = new ArrayList<String>();
        boolean blocked = false;

        // Sin snapshot ROM válido no se puede medir nada.
        if (cur == null || !cur.valid) {
            doubts.add("sin snapshot ROM válido");
            return new Validation(false, false, Destination.NOT_APPLICABLE,
                    Double.NaN, false, doubts, true);
        }

        // Señal 1 — Llegada: fix dentro del radio del nodo.
        boolean arrival = false;
        double distanceM = Double.NaN;
        if (fix == null) {
            doubts.add("sin fix GPS");
            blocked = true;
        } else {
            distanceM = RouteGenerator.haversineM(fix.lat, fix.lon, node.lat, node.lon);
            arrival = distanceM <= node.radiusM;
            if (Float.isNaN(fix.accuracyM)) {
                doubts.add("precisión del fix desconocida");
                blocked = true;
            } else if (fix.accuracyM > node.radiusM) {
                doubts.add("fix impreciso: ±" + Math.round(fix.accuracyM)
                        + " m > radio " + Math.round(node.radiusM) + " m");
                blocked = true;
            }
        }

        // Señal 2 — Consumo: la posición ROM cambió entre snapshots.
        boolean romMoved = cur.movedFrom(prev);
        boolean consumption = romMoved;
        if (prev == null || !prev.valid) {
            doubts.add("sin snapshot previo (no se puede medir consumo)");
            blocked = true;
        } else if (!romMoved) {
            doubts.add("avatar sin cambio de posición ROM (bloqueado o parado)");
        }

        // Señal 3 — Destino: el motor está en la casilla del nodo.
        Destination destination;
        if (node.destMapId == RouteNode.NO_DEST) {
            destination = Destination.NOT_APPLICABLE;
            doubts.add("nodo sin destino ROM declarado");
        } else if (mapIdOf(cur.mapGroup, cur.mapNumber) == node.destMapId
                && cur.x == node.destX && cur.y == node.destY) {
            destination = Destination.MATCH;
        } else {
            destination = Destination.MISMATCH;
            if (mapIdOf(cur.mapGroup, cur.mapNumber) != node.destMapId) {
                doubts.add("mapa incompatible (ROM " + cur.mapGroup + "/" + cur.mapNumber
                        + " ≠ dest map_id " + node.destMapId + ")");
            } else {
                doubts.add("casilla ROM distinta de la del nodo");
            }
        }

        return new Validation(arrival, consumption, destination,
                distanceM, romMoved, doubts, blocked);
    }
}
