package com.gpsquest96.gps;

import com.gpsquest96.gps.OsmDataFetcher.OsmElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Une respuestas OSM de celdas contiguas sin duplicar elementos (spec 004 slice 2).
 * <p>
 * Antes cada consulta <b>reemplazaba</b> el conjunto y el mapa "saltaba" al cruzar
 * de celda: lo que había detrás desaparecía. La unión es acumulativa, deduplica por
 * identidad OSM ({@code type#id}) y conserva el orden de llegada.
 * <p>
 * Clase pura (sin Android ni red): se prueba en host.
 */
public final class OsmAccumulator {

    /**
     * Radio de poda por defecto. El acumulador es para el mapa y la ruta cercanos;
     * guardar datos de otra ciudad (caso 2026-11-08: 278 elementos a 4 km) solo
     * inflaba el overlay y el raster.
     */
    public static final double KEEP_RADIUS_M = 1000.0;

    private OsmAccumulator() {
    }

    /**
     * @param base     elementos ya acumulados (puede ser null)
     * @param incoming respuesta nueva (puede ser null)
     * @param max      tope de elementos; se descartan los más antiguos
     * @return lista nueva, sin duplicados, en orden de llegada
     */
    public static List<OsmElement> merge(List<OsmElement> base,
            List<OsmElement> incoming, int max) {
        return merge(base, incoming, max, Double.NaN, Double.NaN, 0.0);
    }

    /**
     * Igual que {@link #merge(List, List, int)}, pero descarta los elementos que
     * no tengan ningún punto dentro de {@code keepRadiusM} del centro consultado.
     * Así el acumulador no conserva datos de una celda a kilómetros de distancia.
     *
     * @param centerLat   latitud del centro de la consulta (NaN = sin poda)
     * @param centerLon   longitud del centro de la consulta (NaN = sin poda)
     * @param keepRadiusM radio de conservación en metros (<=0 = sin poda)
     */
    public static List<OsmElement> merge(List<OsmElement> base,
            List<OsmElement> incoming, int max,
            double centerLat, double centerLon, double keepRadiusM) {
        Set<String> keys = new LinkedHashSet<String>();
        List<OsmElement> out = new ArrayList<OsmElement>();
        addAll(out, keys, base, centerLat, centerLon, keepRadiusM);
        addAll(out, keys, incoming, centerLat, centerLon, keepRadiusM);
        if (max > 0 && out.size() > max) {
            return new ArrayList<OsmElement>(out.subList(out.size() - max, out.size()));
        }
        return out;
    }

    private static void addAll(List<OsmElement> out, Set<String> keys,
            List<OsmElement> source,
            double centerLat, double centerLon, double keepRadiusM) {
        if (source == null) return;
        for (OsmElement el : source) {
            if (el == null) continue;
            if (!within(el, centerLat, centerLon, keepRadiusM)) continue;
            String key = keyOf(el);
            if (keys.add(key)) out.add(el);
        }
    }

    /** ¿Algún punto del elemento cae dentro del radio de conservación? */
    static boolean within(OsmElement el, double centerLat, double centerLon,
            double keepRadiusM) {
        if (Double.isNaN(centerLat) || Double.isNaN(centerLon) || keepRadiusM <= 0.0) {
            return true;
        }
        if (el.lats == null || el.lons == null) return false;
        int n = Math.min(el.lats.length, el.lons.length);
        for (int i = 0; i < n; i++) {
            double dLat = (el.lats[i] - centerLat) * 111320.0;
            double dLon = (el.lons[i] - centerLon) * 111320.0
                    * Math.cos(Math.toRadians(centerLat));
            if (Math.sqrt(dLat * dLat + dLon * dLon) <= keepRadiusM) return true;
        }
        return false;
    }

    static String keyOf(OsmElement el) {
        if (el.id > 0) {
            return el.type + "#" + el.id;
        }
        // Sin id (elemento sintético o JSON recortado): identidad por tipo y punto.
        String kind = el.kind == null ? "?" : el.kind.name();
        double lat = (el.lats != null && el.lats.length > 0) ? el.lats[0] : 0.0;
        double lon = (el.lons != null && el.lons.length > 0) ? el.lons[0] : 0.0;
        return kind + "|" + String.format(Locale.ROOT, "%.6f|%.6f", lat, lon);
    }
}
