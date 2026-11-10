package com.gpsquest96.route;

import com.gpsquest96.route.RouteValidator.Destination;
import com.gpsquest96.route.RouteValidator.GpsFix;
import com.gpsquest96.route.RouteValidator.RomSnapshot;
import com.gpsquest96.route.RouteValidator.Validation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * Avance monotónico de la Ruta Maestra (spec 004 §3.3 y §4 R5, R6, R8;
 * slice 4).
 * <p>
 * Dos estados separados, como fija la spec:
 * <ul>
 *   <li>{@code posicion}: dónde está el jugador en la secuencia. Puede
 *       bajar (re-enganche R6) y subir (avance). {@code posicion == N}
 *       (N = número de nodos) es el estado explícito de fin de ruta (R10).
 *   </li>
 *   <li>{@code resueltos[]}: qué eventos ya ocurrieron. Solo crece: una
 *       vez resuelto, un nodo nunca se deshace (R8).</li>
 * </ul>
 * <p>
 * <b>Modelo de avance (decisión del slice, §10 de la spec):</b> en cada fix
 * GPS se evalúa SOLO el nodo en {@code posicion} con la doble validación
 * del slice 3 ({@link RouteValidator#evaluate}). Un nodo distinto de
 * {@code posicion} nunca se resuelve por proximidad: eso impediría saltar
 * nodos caminando (o en vehículo) por encima de la secuencia.
 * <ul>
 *   <li><b>Avance:</b> si llegada y consumo son verdad y la lectura no está
 *       bloqueada ({@link Validation#blocked}), {@code posicion} sube en uno.
 *       Sin cambio de posición ROM no hay avance aunque el GPS recorra
 *       kilómetros (R5, anti-skip: caminar con el avatar atascado contra
 *       una pared no regala nodos).</li>
 *   <li><b>Resolución:</b> el nodo se marca resuelto cuando, además, el
 *       destino está bien: {@code MATCH}, o {@code NOT_APPLICABLE} cuando
 *       el nodo no declara destino. Hoy TODOS los nodos generados son
 *       {@code NO_DEST}, así que resolver = llegar + moverse; en cuanto el
 *       volcado POCO (005) declare destinos reales, el {@code MATCH} se
 *       exige solo, sin cambiar código (la puerta ya está cableada y
 *       probada en host con nodos con destino).</li>
 *   <li><b>Avance sin resolver:</b> si llegada y consumo son verdad pero el
 *       destino es {@code MISMATCH}, {@code posicion} avanza pero el nodo
 *       queda pendiente: el jugador pasó por el sitio sin completar el
 *       evento en el juego. El re-enganche puede devolverlo allí.</li>
 *   <li><b>Re-enganche (R6):</b> antes de evaluar el nodo actual, si el fix
 *       está a ≤ 120 m de un nodo pendiente con índice menor que
 *       {@code posicion} y con {@code map_id} compatible, {@code posicion}
 *       baja al pendiente más alto de esos. Solo hacia atrás, nunca
 *       incrementa {@code posicion}. La compatibilidad de mapa usa
 *       {@link RouteValidator#mapIdOf} (codificación provisional, una sola
 *       función en todo el repo); un nodo sin destino declarado
 *       ({@code NO_DEST}) es compatible con cualquier mapa, así que hoy el
 *       re-enganche solo depende de la distancia.</li>
 *   <li><b>Lecturas dudosas:</b> una lectura bloqueada
 *       ({@code Validation#blocked}: sin fix, fix impreciso o de precisión
 *       desconocida, cadena de snapshots rota) no avanza ni resuelve nada.
 *       Las dudas de destino no bloquean el avance (las decide la regla de
 *       destino de arriba). Una vez terminada la ruta no se evalúa más:
 *       los pendientes que queden son trabajo de la válvula de escape
 *       (slice 6b), no de este slice.</li>
 * </ul>
 * <p>
 * <b>Persistencia:</b> el progreso vive APARTE de la ruta (la ruta es un
 * archivo de solo lectura): {@code progress_<rom_key>.json} en el mismo
 * directorio {@code filesDir/routes/}, con guardado atómico
 * ({@code .tmp} → {@code .bak} → rename, mismo patrón que
 * {@link RouteBundle#save}). No usa SQLite: {@code DatabaseHelper} no
 * tiene tablas por diseño (purga VISION; el schema fiel a ROM llega con
 * la Fase B) y crear una tabla solo para el progreso contradiría esa
 * purga. Tampoco usa SharedPreferences: el progreso es un dato por ruta
 * (hasta 500 índices) y pertenece junto a su archivo de ruta, no entre
 * las configuraciones rápidas de {@code SaveManager}.
 * <p>
 * Es Java puro, sin Android, para poder verificarse con host tests.
 */
public final class RouteProgress {

    /** Versión del esquema de {@code progress_<rom_key>.json}. */
    public static final int SCHEMA_VERSION = 1;

    /** Re-enganche máximo (spec 004 §5.2, R6): 120 m. */
    public static final double REENGAGE_RADIUS_M = 120.0;

    /** La ruta que se recorre (solo lectura; el progreso vive aquí). */
    public final Route route;

    private int posicion;
    private final BitSet resolved;
    private long updatedAtMs;

    /** Progreso nuevo: posición 0, nada resuelto. */
    public RouteProgress(Route route) {
        if (route == null) throw new IllegalArgumentException("ruta nula");
        if (route.nodes.isEmpty()) throw new IllegalArgumentException("ruta sin nodos");
        this.route = route;
        this.posicion = 0;
        this.resolved = new BitSet(route.nodes.size());
        this.updatedAtMs = System.currentTimeMillis();
    }

    private RouteProgress(Route route, int posicion, BitSet resolved, long updatedAtMs) {
        this.route = route;
        this.posicion = posicion;
        this.resolved = resolved;
        this.updatedAtMs = updatedAtMs;
    }

    /** Índice del nodo actual (0..N; N = ruta terminada). */
    public int getPosition() {
        return posicion;
    }

    /** Número de nodos de la ruta. */
    public int getNodeCount() {
        return route.nodes.size();
    }

    /** true si el nodo {@code index} quedó resuelto (nunca se deshace). */
    public boolean isResolved(int index) {
        if (index < 0 || index >= route.nodes.size()) return false;
        return resolved.get(index);
    }

    /** Cuántos nodos están resueltos. */
    public int resolvedCount() {
        return resolved.cardinality();
    }

    /** true si {@code posicion} llegó al final de la ruta (R10). */
    public boolean isFinished() {
        return posicion >= route.nodes.size();
    }

    // ------------------------------------------------------------------
    // Resultado de un paso de avance
    // ------------------------------------------------------------------

    /** Resultado de {@link #evaluate(RomSnapshot, RomSnapshot, GpsFix)}. */
    public static final class Outcome {
        /** Validación R3 del nodo evaluado; null si no se evaluó nada. */
        public final Validation validation;
        /** Índice del nodo evaluado (-1 si no se evaluó). */
        public final int evaluatedNodeIndex;
        /** Posición antes del paso (previa al re-enganche). */
        public final int positionBefore;
        /** Posición después del paso. */
        public final int positionAfter;
        /** Posición previa al re-enganche (-1 si no hubo). */
        public final int reengagedFrom;
        /** Nodo resuelto en este paso (-1 si ninguno). */
        public final int resolvedIndex;
        /** true si {@code posicion} cambió (avance o re-enganche). */
        public final boolean changed;
        /** true si la ruta quedó terminada tras este paso. */
        public final boolean finished;

        Outcome(Validation validation, int evaluatedNodeIndex,
                int positionBefore, int positionAfter, int reengagedFrom,
                int resolvedIndex, boolean changed, boolean finished) {
            this.validation = validation;
            this.evaluatedNodeIndex = evaluatedNodeIndex;
            this.positionBefore = positionBefore;
            this.positionAfter = positionAfter;
            this.reengagedFrom = reengagedFrom;
            this.resolvedIndex = resolvedIndex;
            this.changed = changed;
            this.finished = finished;
        }

        static Outcome noChange() {
            return new Outcome(null, -1, -1, -1, -1, -1, false, false);
        }

        /** Una línea de log: `R4 pos=A→B resuelto=I reenganche [FIN]`. */
        public String formatLog() {
            StringBuilder sb = new StringBuilder();
            sb.append("R4 pos=").append(positionBefore).append("→").append(positionAfter);
            if (resolvedIndex >= 0) sb.append(" resuelto=").append(resolvedIndex);
            if (reengagedFrom >= 0) sb.append(" reenganche(desde ").append(reengagedFrom).append(')');
            if (finished) sb.append(" FIN");
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // Avance (un fix GPS)
    // ------------------------------------------------------------------

    /**
     * Aplica un fix GPS con sus snapshots ROM y mueve el progreso según
     * R5/R6/R8. No toca el juego: solo {@code posicion} y
     * {@code resueltos[]}. No inventa destinos ni escribe en la ROM.
     *
     * @param prev snapshot ROM previo (null en el primer fix: sin consumo
     *             medible, no hay avance)
     * @param cur  snapshot ROM actual
     * @param fix  fix GPS real (null si no hay)
     */
    public Outcome evaluate(RomSnapshot prev, RomSnapshot cur, GpsFix fix) {
        if (isFinished()) return Outcome.noChange();
        int positionBefore = posicion;
        int reengagedFrom = -1;

        // 1. Re-enganche (R6) antes de evaluar el nodo actual: solo hacia
        // atrás, al pendiente más alto dentro de 120 m con mapa compatible.
        // El fix debe ser fiable para mover la posición (precisión
        // desconocida se permite, como en el validador; > 120 m no).
        if (fix != null && cur != null && cur.valid
                && (Float.isNaN(fix.accuracyM) || fix.accuracyM <= REENGAGE_RADIUS_M)) {
            int best = -1;
            for (int i = 0; i < posicion; i++) {
                if (resolved.get(i)) continue;
                RouteNode n = route.nodes.get(i);
                if (RouteGenerator.haversineM(fix.lat, fix.lon, n.lat, n.lon)
                        > REENGAGE_RADIUS_M) continue;
                if (n.destMapId != RouteNode.NO_DEST
                        && n.destMapId != RouteValidator.mapIdOf(cur.mapGroup, cur.mapNumber)) {
                    continue; // mapa incompatible (codificación provisional)
                }
                if (i > best) best = i;
            }
            if (best >= 0) {
                reengagedFrom = posicion;
                posicion = best;
            }
        }

        // 2. Doble validación (R3) sobre el nodo actual.
        RouteNode node = route.nodes.get(posicion);
        Validation v = RouteValidator.evaluate(prev, cur, fix, node);

        // 3. Avance (R5) y resolución (R8): llegada + consumo + lectura no
        // bloqueada. El destino decide solo si el nodo se marca resuelto.
        int resolvedIndex = -1;
        boolean advanced = false;
        if (v.arrival && v.consumption && !v.blocked) {
            boolean destinationOk = v.destination == Destination.MATCH
                    || v.destination == Destination.NOT_APPLICABLE;
            if (destinationOk && !resolved.get(posicion)) {
                resolved.set(posicion);
                resolvedIndex = posicion;
            }
            posicion = Math.min(posicion + 1, route.nodes.size());
            advanced = true;
        }

        updatedAtMs = System.currentTimeMillis();
        return new Outcome(v, node.index, positionBefore, posicion,
                reengagedFrom, resolvedIndex,
                advanced || reengagedFrom >= 0, isFinished());
    }

    // ------------------------------------------------------------------
    // Serialización (determinista; el progreso vive aparte de la ruta)
    // ------------------------------------------------------------------

    public String toJson() {
        StringBuilder sb = new StringBuilder(128 + resolvedCount() * 4);
        sb.append("{\"schema_version\":").append(SCHEMA_VERSION)
                .append(",\"rom_key\":\"").append(Route.escape(route.romKey)).append('"')
                .append(",\"posicion\":").append(posicion)
                .append(",\"resolved\":[");
        boolean first = true;
        for (int i = resolved.nextSetBit(0); i >= 0; i = resolved.nextSetBit(i + 1)) {
            if (!first) sb.append(',');
            first = false;
            sb.append(i);
        }
        sb.append("],\"updated_at\":").append(updatedAtMs).append('}');
        return sb.toString();
    }

    /**
     * Valida y carga un progreso para {@code route}. Lanza
     * {@link RouteFormatException} con el motivo exacto (rechazo con
     * motivo, como la ruta): un progreso corrupto o de otra partida nunca
     * se aplica a medias.
     */
    public static RouteProgress fromJson(String json, Route route)
            throws RouteFormatException {
        if (route == null) throw new IllegalArgumentException("ruta nula");
        if (json == null || json.isEmpty()) {
            throw new RouteFormatException("progreso vacío");
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            throw new RouteFormatException("progreso con JSON inválido: " + e.getMessage());
        }
        int version = root.optInt("schema_version", Integer.MIN_VALUE);
        if (version != SCHEMA_VERSION) {
            throw new RouteFormatException("progreso con versión no soportada: " + version);
        }
        String romKey = root.optString("rom_key", null);
        if (romKey == null || !romKey.equals(route.romKey)) {
            throw new RouteFormatException("progreso de otra partida: "
                    + (romKey == null ? "sin rom_key" : romKey));
        }
        int posicion = root.optInt("posicion", Integer.MIN_VALUE);
        if (posicion < 0 || posicion > route.nodes.size()) {
            throw new RouteFormatException("progreso con posicion fuera de rango: "
                    + posicion);
        }
        JSONArray arr = root.optJSONArray("resolved");
        if (arr == null) {
            throw new RouteFormatException("progreso sin lista de resueltos");
        }
        BitSet resolved = new BitSet(route.nodes.size());
        int prev = -1;
        for (int i = 0; i < arr.length(); i++) {
            int idx = arr.optInt(i, Integer.MIN_VALUE);
            if (idx < 0 || idx >= route.nodes.size()) {
                throw new RouteFormatException("progreso con resuelto fuera de rango: "
                        + idx);
            }
            if (idx <= prev) {
                throw new RouteFormatException("progreso con resueltos sin orden: "
                        + idx + " tras " + prev);
            }
            prev = idx;
            resolved.set(idx);
        }
        long updatedAt = root.optLong("updated_at", 0L);
        return new RouteProgress(route, posicion, resolved, updatedAt);
    }

    /** Archivo del progreso: {@code <dir>/progress_<rom_key>.json}. */
    public static File fileFor(File dir, String romKey) {
        String safe = (romKey == null ? "rom-v0" : romKey).replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir, "progress_" + safe + ".json");
    }

    /**
     * Carga el progreso guardado para {@code route}; null si no hay archivo
     * (primera vez en esta ruta). Lanza {@link RouteFormatException} si el
     * archivo existe pero es inválido: el llamador decide (típico: empezar
     * de cero y avisar, nunca aplicar un progreso a medias).
     */
    public static RouteProgress load(File file, Route route)
            throws RouteFormatException, IOException {
        if (file == null || !file.isFile()) return null;
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            int n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
                off += n;
            }
            if (off != data.length) throw new IOException("Lectura incompleta");
        }
        return fromJson(new String(data, StandardCharsets.UTF_8), route);
    }

    /**
     * Escribe el progreso de forma atómica en {@code dest}: {@code .tmp} y
     * rename al final (mismo patrón que {@link RouteBundle#save}).
     */
    public void save(File dest) throws IOException {
        if (dest == null) throw new IOException("Sin destino para el progreso");
        File dir = dest.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("No se pudo crear el directorio de rutas");
        }
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        File bak = new File(dest.getAbsolutePath() + ".bak");

        byte[] data = toJson().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
        if (bak.exists() && !bak.delete()) {
            tmp.delete();
            throw new IOException("No se pudo limpiar el respaldo");
        }
        if (dest.exists() && !dest.renameTo(bak)) {
            tmp.delete();
            throw new IOException("No se pudo respaldar el progreso anterior");
        }
        if (!tmp.renameTo(dest)) {
            if (bak.exists()) bak.renameTo(dest);
            tmp.delete();
            throw new IOException("No se pudo promover el progreso nuevo");
        }
    }

    @Override
    public String toString() {
        return "RouteProgress{" + route.romKey + " · pos " + posicion + "/"
                + route.nodes.size() + " · resueltos " + resolvedCount() + "}";
    }
}
