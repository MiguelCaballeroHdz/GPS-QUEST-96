package com.gpsquest96.route;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Nodo de la Ruta Maestra (spec 004 §3.2).
 * <p>
 * Un nodo declara {@code action}, {@code poi}, posición real ({@code lat}/{@code lon}),
 * radio de llegada y, opcionalmente, destino en la ROM y predicado de desbloqueo.
 * Es inmutable: la ruta generada es un archivo de solo lectura.
 */
public final class RouteNode {

    /** Tipos de nodo de ruta (spec 004 §3.4). No se mezclan con {@code OsmDataFetcher.Kind}. */
    public enum Action {
        WALK, ENTER, TALK, USE, BATTLE, WAIT;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Action fromWire(String s) {
            if (s == null) return null;
            for (Action a : values()) {
                if (a.wire().equals(s)) return a;
            }
            return null;
        }
    }

    public static final int NO_DEST = -1;

    public final int index;
    public final Action action;
    public final String poi;
    public final double lat;
    public final double lon;
    public final double radiusM;
    public final int destMapId;
    public final int destX;
    public final int destY;
    public final boolean blocked;
    /** Predicado de desbloqueo (R4). Obligatorio si {@code blocked}. */
    public final String unlock;

    public RouteNode(int index, Action action, String poi,
            double lat, double lon, double radiusM,
            int destMapId, int destX, int destY,
            boolean blocked, String unlock) {
        this.index = index;
        this.action = action;
        this.poi = poi;
        this.lat = lat;
        this.lon = lon;
        this.radiusM = radiusM;
        this.destMapId = destMapId;
        this.destX = destX;
        this.destY = destY;
        this.blocked = blocked;
        this.unlock = unlock;
    }

    /** Nodo de ruta sin destino ROM todavía (slice 2, sin validación ROM). */
    public static RouteNode at(int index, Action action, String poi,
            double lat, double lon, double radiusM) {
        return new RouteNode(index, action, poi, lat, lon, radiusM,
                NO_DEST, NO_DEST, NO_DEST, false, null);
    }

    void appendJson(StringBuilder sb) {
        sb.append("{\"index\":").append(index)
                .append(",\"action\":\"").append(action.wire()).append('"')
                .append(",\"poi\":\"").append(Route.escape(poi)).append('"')
                .append(",\"lat\":").append(Route.fmt(lat, 7))
                .append(",\"lon\":").append(Route.fmt(lon, 7))
                .append(",\"radius_m\":").append(Route.fmt(radiusM, 1))
                .append(",\"dest\":{\"map_id\":").append(destMapId)
                .append(",\"x\":").append(destX)
                .append(",\"y\":").append(destY).append('}')
                .append(",\"blocked\":").append(blocked)
                .append(",\"unlock\":");
        if (unlock == null) sb.append("null");
        else sb.append('"').append(Route.escape(unlock)).append('"');
        sb.append('}');
    }

    static RouteNode fromJson(JSONObject o, int expectedIndex, int total)
            throws RouteFormatException {
        if (o == null) throw new RouteFormatException("nodo nulo");
        int index = o.optInt("index", Integer.MIN_VALUE);
        if (index != expectedIndex) {
            throw new RouteFormatException("índice fuera de secuencia: se esperaba "
                    + expectedIndex + " y llegó " + index);
        }
        Action action = Action.fromWire(o.optString("action", null));
        if (action == null) {
            throw new RouteFormatException("nodo " + index + ": acción desconocida «"
                    + o.optString("action", "") + "»");
        }
        String poi = o.optString("poi", null);
        if (poi == null || poi.isEmpty()) {
            throw new RouteFormatException("nodo " + index + ": sin categoría poi");
        }
        double lat = o.optDouble("lat", Double.NaN);
        double lon = o.optDouble("lon", Double.NaN);
        if (Double.isNaN(lat) || lat < -90.0 || lat > 90.0
                || Double.isNaN(lon) || lon < -180.0 || lon > 180.0) {
            throw new RouteFormatException("nodo " + index + ": coordenadas inválidas");
        }
        double radius = o.optDouble("radius_m", Double.NaN);
        if (Double.isNaN(radius) || radius <= 0.0) {
            throw new RouteFormatException("nodo " + index + ": radio de llegada inválido");
        }
        int mapId = NO_DEST, x = NO_DEST, y = NO_DEST;
        JSONObject dest = o.optJSONObject("dest");
        if (dest != null) {
            mapId = dest.optInt("map_id", NO_DEST);
            x = dest.optInt("x", NO_DEST);
            y = dest.optInt("y", NO_DEST);
            if ((x == NO_DEST) != (y == NO_DEST)) {
                throw new RouteFormatException("nodo " + index
                        + ": destino a medias (x/y deben ir juntos)");
            }
        }
        boolean blocked = o.optBoolean("blocked", false);
        String unlock = o.isNull("unlock") ? null : o.optString("unlock", null);
        if (blocked && (unlock == null || unlock.isEmpty())) {
            throw new RouteFormatException("nodo " + index
                    + " blocked sin predicado de desbloqueo");
        }
        return new RouteNode(index, action, poi, lat, lon, radius,
                mapId, x, y, blocked, unlock);
    }

    @Override
    public String toString() {
        return "RouteNode{" + index + " " + action + " " + poi
                + " @" + String.format(Locale.ROOT, "%.6f,%.6f", lat, lon) + "}";
    }
}
