package com.gpsquest96.gps;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Datos vectoriales OSM vía Overpass (Bloque C: mapa → tiles GBC).
 * <p>
 * Solo HttpURLConnection + pool propio. Sin raster PNG, sin Glide.
 * Cache JSON 7 días en filesDir/osm/. Todo en workers, jamás en UI/GL.
 */
public class OsmDataFetcher {

    private static final String TAG = "OsmDataFetcher";
    /**
     * Endpoints Overpass, en orden. El espejo solo se usa si el principal falla:
     * la query y el parseo son idénticos (spec 014 RF-02).
     */
    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    };
    private static final String CONTACT_URL = "https://github.com/MiguelCaballeroHdz";
    /**
     * Medido en POCO el 2026-10-29: la misma query tarda 25,9–33,8 s en Overpass.
     * Con 10 s los 3 intentos morían en read-timeout (spec 014 RF-01).
     */
    private static final int TIMEOUT_MS = 40000;
    private static final long DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final double RADIUS_M = 150.0;
    /** Margen del recorte: conserva el tramo que roza el borde del círculo. */
    private static final double CLIP_MARGIN_M = 10.0;
    private static final long BACKOFF_BASE_MS = 2000;

    /**
     * Tipos OSM observables (spec 004 §3.5). Enum **solo OSM**: los tipos de nodo de ruta
     * (`walk`, `enter`, `talk`, `use`, `battle`, `wait`) viven en el modelo de ruta.
     * Poda 2026-11-07: fuera GOODS, GYM, LAB, POKESHOP, CAVE, HOME y WATERZONE
     * (0 usos y sin clave OSM de origen).
     */
    public enum Kind {
        HIGHWAY, PARK, WOOD, BUILDING, WATER, SHOP, TOURISM, AMENITY
    }

    /**
     * Geometría ya resuelta: listas paralelas lat/lon (nodos o vía muestreada).
     * `id`/`type` son la identidad OSM (para deduplicar entre celdas).
     */
    public static class OsmElement {
        public final Kind kind;
        public final double[] lats;
        public final double[] lons;
        public final long id;
        public final String type;

        public OsmElement(Kind kind, double[] lats, double[] lons) {
            this(kind, lats, lons, 0L, "");
        }

        public OsmElement(Kind kind, double[] lats, double[] lons, long id, String type) {
            this.kind = kind;
            this.lats = lats;
            this.lons = lons;
            this.id = id;
            this.type = type == null ? "" : type;
        }
    }

    public interface OsmCallback {
        void onData(List<OsmElement> elements, double centerLat, double centerLon);
        void onError(String message);
    }

    private final File osmDir;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile OsmCallback callback;
    /** Ultimo diagnostico de red para el overlay Red: (legible sin logcat). */
    private volatile String lastError = null;

    public OsmDataFetcher(Context context) {
        File base = context.getFilesDir();
        this.osmDir = (base != null) ? new File(base, "osm") : null;
        if (osmDir != null && !osmDir.exists()) osmDir.mkdirs();
    }

    public void setCallback(OsmCallback callback) {
        this.callback = callback;
    }

    /**
     * Pide el área (caché 7 días → red). Nunca bloquea al llamador.
     */
    public void requestArea(final double lat, final double lon) {
        requestArea(lat, lon, RADIUS_M);
    }

    /**
     * Área con radio explícito (spec 004 slice 2: `RADIUS_M` deja de ser constante
     * global y pasa a parámetro del tramo consultado).
     */
    public void requestArea(final double lat, final double lon, final double radiusM) {
        final String key = cacheKeyArea(lat, lon, radiusM);
        final String legacy = legacyAreaKey(lat, lon, radiusM);
        final String query = buildAreaQuery(lat, lon, radiusM);
        request(key, legacy, query, lat, lon,
                new double[][] {{lat, lon}}, radiusM);
    }

    /**
     * Consulta por corredor (spec 004 §9): una unión de áreas a lo largo del tramo
     * (ancla + 4 cardinales) en lugar de un único círculo. Sirve para no perder POIs
     * al avanzar y para que el conjunto acumulado crezca sin saltos.
     */
    public void requestCorridor(final double lat, final double lon,
            final double radiusM, final double spanM) {
        final String key = String.format(Locale.ROOT, "corr_%.5f_%.5f_%d_%d",
                lat, lon, (int) radiusM, (int) spanM);
        final String legacy = String.format(Locale.ROOT, "corr_%.3f_%.3f_%d_%d",
                lat, lon, (int) radiusM, (int) spanM);
        final double[][] points = corridorPoints(lat, lon, spanM);
        final String query = buildCorridorQuery(lat, lon, radiusM, spanM);
        request(key, legacy, query, lat, lon, points, radiusM);
    }

    private void request(final String cacheKey, final String legacyKey,
            final String query, final double lat, final double lon,
            final double[][] clipCenters, final double clipRadiusM) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = loadFromDisk(cacheKey);
                    if (json == null && legacyKey != null) {
                        // Migración 2026-11-08: la clave vieja redondeaba a ~111 m.
                        // Se reutiliza el JSON y se reescribe con la clave exacta.
                        json = loadFromDisk(legacyKey);
                        if (json != null) saveToDisk(cacheKey, json);
                    }
                    if (json == null) {
                        json = download(query);
                        if (json != null) saveToDisk(cacheKey, json);
                    }
                    if (json == null) {
                        String diag = lastError;
                        fireError(diag != null ? diag : "sin datos OSM");
                        return;
                    }
                    List<OsmElement> elements = parse(json, clipCenters,
                            clipRadiusM + CLIP_MARGIN_M);
                    OsmCallback cb = callback;
                    if (cb != null) cb.onData(elements, lat, lon);
                } catch (Throwable t) {
                    Log.w(TAG, "requestArea fallido", t);
                    fireError(t.getMessage());
                }
            }
        });
    }

    private void fireError(String message) {
        OsmCallback cb = callback;
        if (cb != null) cb.onError(message);
    }

    private static String cacheKeyArea(double lat, double lon, double radiusM) {
        return String.format(Locale.ROOT, "r%d_%.5f_%.5f",
                (int) radiusM, lat, lon);
    }

    /** Clave anterior (3 decimales ≈ 111 m) para no perder caché ya descargada. */
    private static String legacyAreaKey(double lat, double lon, double radiusM) {
        if (Math.abs(radiusM - RADIUS_M) < 0.5) {
            return String.format(Locale.ROOT, "%.3f_%.3f", lat, lon);
        }
        return String.format(Locale.ROOT, "r%d_%.3f_%.3f", (int) radiusM, lat, lon);
    }

    private File cacheFile(String key) {
        if (osmDir == null) return null;
        return new File(osmDir, key + ".json");
    }

    private String loadFromDisk(String key) {
        File f = cacheFile(key);
        if (f == null || !f.exists()) return null;
        // mtime <= 0 = edad desconocida (fichero restaurado/copiado sin tiempo).
        // No se puede demostrar que caducó, así que se usa si el contenido es
        // JSON válido; solo un mtime real fuera de TTL lo invalida (RF-05).
        long modified = f.lastModified();
        if (modified > 0 && System.currentTimeMillis() - modified > DISK_TTL_MS) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        try (InputStream in = new FileInputStream(f);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String json = sb.toString();
            if (json.isEmpty() || json.indexOf("\"elements\"") < 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            Log.i(TAG, "Caché OSM reutilizada (" + json.length() + " B, mtime=" + modified + ")");
            return json;
        } catch (Exception e) {
            Log.w(TAG, "Caché OSM ilegible", e);
            return null;
        }
    }

    private void saveToDisk(String key, String json) {
        File f = cacheFile(key);
        if (f == null) return;
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "No se pudo cachear OSM", e);
        }
    }

    /**
     * Query de área: una unión de claves sobre un único `around`.
     * POI-first (spec 004 slice 1): nodos Y vías de amenity/tourism/historic.
     */
    static String buildAreaQuery(double lat, double lon, double radiusM) {
        String around = around(lat, lon, radiusM);
        StringBuilder sb = new StringBuilder("[out:json][timeout:25];(");
        appendClauses(sb, around);
        sb.append(");out geom;");
        return sb.toString();
    }

    /**
     * Query de corredor: repite la unión de claves para cada punto del tramo
     * (ancla + 4 cardinales). Overpass devuelve la unión deduplicada por id.
     */
    static String buildCorridorQuery(double lat, double lon,
            double radiusM, double spanM) {
        double[][] points = corridorPoints(lat, lon, spanM);
        StringBuilder sb = new StringBuilder("[out:json][timeout:40];(");
        for (double[] p : points) {
            appendClauses(sb, around(p[0], p[1], radiusM));
        }
        sb.append(");out geom;");
        return sb.toString();
    }

    /** Centros del corredor: ancla + 4 cardinales (mismo orden que la query). */
    private static double[][] corridorPoints(double lat, double lon, double spanM) {
        return new double[][] {
                {lat, lon},
                offset(lat, lon, spanM, 0.0),
                offset(lat, lon, spanM, 90.0),
                offset(lat, lon, spanM, 180.0),
                offset(lat, lon, spanM, 270.0)
        };
    }

    private static void appendClauses(StringBuilder sb, String around) {
        sb.append("way[\"highway\"]").append(around).append(';')
                .append("way[\"building\"]").append(around).append(';')
                .append("way[\"natural\"~\"wood|water\"]").append(around).append(';')
                .append("way[\"leisure\"=\"park\"]").append(around).append(';')
                .append("node[\"shop\"]").append(around).append(';')
                .append("way[\"shop\"]").append(around).append(';')
                .append("node[\"amenity\"]").append(around).append(';')
                .append("way[\"amenity\"]").append(around).append(';')
                .append("node[\"tourism\"]").append(around).append(';')
                .append("way[\"tourism\"]").append(around).append(';')
                .append("node[\"historic\"]").append(around).append(';')
                .append("way[\"historic\"]").append(around).append(';');
    }

    private static String around(double lat, double lon, double radiusM) {
        return String.format(Locale.ROOT, "(around:%d,%.6f,%.6f)",
                (int) radiusM, lat, lon);
    }

    /** Punto a {@code distM} metros con rumbo {@code bearingDeg}. */
    private static double[] offset(double lat, double lon, double distM, double bearingDeg) {
        double br = Math.toRadians(bearingDeg);
        double dLat = (distM / 6371000.0) * Math.cos(br);
        double dLon = (distM / (6371000.0 * Math.cos(Math.toRadians(lat)))) * Math.sin(br);
        return new double[] {lat + Math.toDegrees(dLat), lon + Math.toDegrees(dLon)};
    }

    private String download(String query) {
        byte[] body = query.getBytes(StandardCharsets.UTF_8);
        lastError = null;
        int lastCode = -1;
        String lastExc = null;
        final int totalAttempts = ENDPOINTS.length * 2;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            final String endpoint = ENDPOINTS[(attempt - 1) % ENDPOINTS.length];
            final String host = endpoint.replace("https://", "").split("/")[0];
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", "GPSQuest96/0.1.0 (" + CONTACT_URL + ")");
                conn.setRequestProperty("Referer", CONTACT_URL);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = conn.getInputStream();
                         BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        Log.i(TAG, "OSM OK desde " + host + " (" + sb.length() + " B)");
                        return sb.toString();
                    }
                }
                lastCode = code;
                Log.w(TAG, "Overpass HTTP " + code + " en " + host
                        + " (intento " + attempt + "/" + totalAttempts + ")");
                // 5xx y 429 son transitorios (Overpass devuelve 502/504 a
                // menudo): se reintenta y se prueba el espejo. Cualquier otro
                // código es un fallo de la query y no mejora reintentando.
                boolean transientError = (code == 429 || code >= 500);
                if (!transientError) {
                    lastError = "Overpass HTTP " + code;
                    return null;
                }
                lastError = "Overpass HTTP " + code + " (" + attempt + "/" + totalAttempts + ")";
            } catch (Exception e) {
                lastExc = e.getMessage();
                lastError = (lastExc != null) ? ("red: " + lastExc) : "red: fallo de red";
                Log.w(TAG, "Descarga OSM fallida en " + host
                        + " (intento " + attempt + "/" + totalAttempts + ")", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (attempt < totalAttempts) {
                try {
                    Thread.sleep(BACKOFF_BASE_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        if (lastError == null) lastError = (lastCode > 0) ? ("Overpass HTTP " + lastCode) : "sin datos OSM";
        return null;
    }

    /**
     * Extrae elementos con geometría (`out geom`). Sin geometría no hay dibujo.
     */
    static List<OsmElement> parse(String json) {
        return parse(json, null, 0.0);
    }

    /**
     * Igual que {@link #parse(String)}, pero recorta la geometría a la unión de
     * círculos de la consulta. Overpass devuelve la vía completa aunque solo un
     * tramo caiga dentro de `around`; sin este recorte una calle de 1 km entraba
     * entera al mapa y al acumulador. Un punto se conserva si está a
     * {@code clipRadiusM} de cualquiera de los centros (ancla + cardinales).
     */
    static List<OsmElement> parse(String json, double[][] clipCenters, double clipRadiusM) {
        List<OsmElement> out = new ArrayList<OsmElement>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null) return out;
            for (int i = 0; i < elements.length(); i++) {
                JSONObject el = elements.optJSONObject(i);
                if (el == null) continue;
                JSONObject tags = el.optJSONObject("tags");
                if (tags == null) continue;
                Kind kind = classify(tags);
                if (kind == null) continue;
                String type = el.optString("type", "");
                boolean isWay = "way".equals(type);
                ArrayList<Double> lats = new ArrayList<Double>();
                ArrayList<Double> lons = new ArrayList<Double>();
                if (el.has("lat") && el.has("lon")) {
                    double lat = el.optDouble("lat", Double.NaN);
                    double lon = el.optDouble("lon", Double.NaN);
                    if (!Double.isNaN(lat) && !Double.isNaN(lon)
                            && insideClip(lat, lon, clipCenters, clipRadiusM)) {
                        lats.add(lat);
                        lons.add(lon);
                    }
                }
                JSONArray geom = el.optJSONArray("geometry");
                if (geom != null) {
                    for (int j = 0; j < geom.length(); j++) {
                        JSONObject p = geom.optJSONObject(j);
                        if (p == null) continue;
                        double lat = p.optDouble("lat", Double.NaN);
                        double lon = p.optDouble("lon", Double.NaN);
                        if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                        if (!insideClip(lat, lon, clipCenters, clipRadiusM)) continue;
                        lats.add(lat);
                        lons.add(lon);
                    }
                }
                if (lats.isEmpty()) continue;
                // Una vía recortada a un solo punto no dibuja nada.
                if (isWay && lats.size() < 2) continue;
                double[] la = new double[lats.size()];
                double[] lo = new double[lons.size()];
                for (int j = 0; j < la.length; j++) {
                    la[j] = lats.get(j);
                    lo[j] = lons.get(j);
                }
                out.add(new OsmElement(kind, la, lo,
                        el.optLong("id", 0L), type));
            }
        } catch (Exception e) {
            Log.w(TAG, "Parse OSM fallido", e);
        }
        return out;
    }

    /** ¿El punto cae dentro de algún círculo de recorte? */
    private static boolean insideClip(double lat, double lon,
            double[][] centers, double radiusM) {
        if (centers == null || centers.length == 0 || radiusM <= 0.0) return true;
        for (double[] c : centers) {
            if (distanceM(lat, lon, c[0], c[1]) <= radiusM) return true;
        }
        return false;
    }

    private static double distanceM(double lat0, double lon0, double lat1, double lon1) {
        double dLat = (lat1 - lat0) * 111320.0;
        double dLon = (lon1 - lon0) * 111320.0 * Math.cos(Math.toRadians(lat0));
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    /**
     * Clasificación OSM (spec 004 slice 1). Orden deliberado: un POI nunca queda tapado
     * por un tag genérico de terreno y `building` es el último recurso (antes un hospital
     * con `building=yes` + `amenity=hospital` salía como BUILDING).
     */
    static Kind classify(JSONObject tags) {
        if (tags.has("amenity")) return Kind.AMENITY;                  // hospital, farmacia, escuela
        if (tags.has("tourism") || tags.has("historic")) return Kind.TOURISM; // museo, monumento, hotel
        if (tags.has("shop")) return Kind.SHOP;
        String natural = tags.optString("natural");
        if ("water".equals(natural)) return Kind.WATER;
        if ("wood".equals(natural)) return Kind.WOOD;
        if ("park".equals(tags.optString("leisure"))) return Kind.PARK;
        if (tags.has("highway")) return Kind.HIGHWAY;
        if (tags.has("building")) return Kind.BUILDING;                // último recurso
        return null;
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
