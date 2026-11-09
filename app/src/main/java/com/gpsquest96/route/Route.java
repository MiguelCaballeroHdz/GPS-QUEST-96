package com.gpsquest96.route;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ruta Maestra de un perfil (spec 004 §6).
 * <p>
 * Formato: {@code route_[rom_key].json} con {@code schema_version}, {@code rom_key},
 * {@code anchor} y {@code nodes[]}. Archivo de solo lectura; el progreso
 * ({@code posicion}, {@code resueltos[]}) vive aparte (slice 4).
 * <p>
 * La serialización es determinista (orden fijo de claves y 7 decimales), requisito
 * del criterio 2 de la spec: mismo perfil + mismo ancla ⇒ misma ruta.
 */
public final class Route {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NODES = 500;

    public final String romKey;
    public final double anchorLat;
    public final double anchorLon;
    public final double anchorAccuracyM;
    public final long anchorConfirmedAt;
    public final List<RouteNode> nodes;

    public Route(String romKey, double anchorLat, double anchorLon,
            double anchorAccuracyM, long anchorConfirmedAt, List<RouteNode> nodes) {
        this.romKey = romKey;
        this.anchorLat = anchorLat;
        this.anchorLon = anchorLon;
        this.anchorAccuracyM = anchorAccuracyM;
        this.anchorConfirmedAt = anchorConfirmedAt;
        this.nodes = Collections.unmodifiableList(new ArrayList<RouteNode>(nodes));
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(256 + nodes.size() * 180);
        sb.append("{\"schema_version\":").append(SCHEMA_VERSION)
                .append(",\"rom_key\":\"").append(escape(romKey)).append('"')
                .append(",\"anchor\":{\"lat\":").append(fmt(anchorLat, 7))
                .append(",\"lon\":").append(fmt(anchorLon, 7))
                .append(",\"accuracy_m\":").append(fmt(anchorAccuracyM, 1))
                .append(",\"confirmed_at\":").append(anchorConfirmedAt).append('}')
                .append(",\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sb.append(',');
            nodes.get(i).appendJson(sb);
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Valida y carga una ruta. Lanza {@link RouteFormatException} con el motivo exacto
     * (criterio 4 de la spec: rechazo con motivo, no silencio).
     */
    public static Route fromJson(String json) throws RouteFormatException {
        if (json == null || json.isEmpty()) {
            throw new RouteFormatException("ruta vacía");
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            throw new RouteFormatException("JSON inválido: " + e.getMessage());
        }
        int version = root.optInt("schema_version", Integer.MIN_VALUE);
        if (version != SCHEMA_VERSION) {
            throw new RouteFormatException("versión de esquema no soportada: " + version);
        }
        String romKey = root.optString("rom_key", null);
        if (romKey == null || romKey.isEmpty()) {
            throw new RouteFormatException("falta rom_key");
        }
        JSONObject anchor = root.optJSONObject("anchor");
        if (anchor == null) {
            throw new RouteFormatException("falta el bloque anchor");
        }
        double lat = anchor.optDouble("lat", Double.NaN);
        double lon = anchor.optDouble("lon", Double.NaN);
        if (Double.isNaN(lat) || lat < -90.0 || lat > 90.0
                || Double.isNaN(lon) || lon < -180.0 || lon > 180.0) {
            throw new RouteFormatException("ancla con coordenadas inválidas");
        }
        double accuracy = anchor.optDouble("accuracy_m", Double.NaN);
        if (Double.isNaN(accuracy) || accuracy <= 0.0) {
            throw new RouteFormatException("ancla con precisión inválida");
        }
        long confirmedAt = anchor.optLong("confirmed_at", 0L);
        JSONArray arr = root.optJSONArray("nodes");
        if (arr == null || arr.length() == 0) {
            throw new RouteFormatException("la ruta no tiene nodos");
        }
        if (arr.length() > MAX_NODES) {
            throw new RouteFormatException("demasiados nodos: " + arr.length()
                    + " (máx " + MAX_NODES + ")");
        }
        List<RouteNode> list = new ArrayList<RouteNode>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            list.add(RouteNode.fromJson(arr.optJSONObject(i), i, arr.length()));
        }
        return new Route(romKey, lat, lon, accuracy, confirmedAt, list);
    }

    static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Route{" + romKey + " · " + nodes.size() + " nodos · ancla "
                + String.format(Locale.ROOT, "%.6f,%.6f ±%.0f m",
                        anchorLat, anchorLon, anchorAccuracyM) + "}";
    }
}
