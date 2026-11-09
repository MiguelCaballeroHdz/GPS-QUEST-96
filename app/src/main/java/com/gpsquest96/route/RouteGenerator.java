package com.gpsquest96.route;

import com.gpsquest96.gps.OsmDataFetcher;
import com.gpsquest96.gps.OsmDataFetcher.Kind;
import com.gpsquest96.gps.OsmDataFetcher.OsmElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generador POI-first de la Ruta Maestra (spec 004 §5.2 y R2).
 * <p>
 * Reglas que aplica:
 * <ul>
 *   <li>El ancla debe tener precisión ≤ 20 m (si no, no se genera nada).</li>
 *   <li>Los POIs se asignan por afinidad de categoría, en orden de vecino más
 *       cercano desde el ancla: mismo perfil + mismo ancla ⇒ misma ruta.</li>
 *   <li>Sin POI compatible, respaldo sintético a 8 m/metatile.</li>
 *   <li>Nunca se coloca un nodo sobre agua (polígono o cauce a &lt; 10 m).</li>
 *   <li>El terreno (calle, edificio) no se convierte en nodo de historia.</li>
 * </ul>
 * Es una clase pura: no toca Android, red ni ROM. Se prueba en host.
 */
public final class RouteGenerator {

    /** Escala metatile → metros (spec 004 §5.2, D-01). */
    public static final double METATILE_M = 8.0;
    /** Radio de llegada de cada nodo (spec 004 §5.2). */
    public static final double NODE_RADIUS_M = 15.0;
    /** Precisión máxima del ancla (spec 004 §3.1). */
    public static final double MAX_ANCHOR_ACCURACY_M = 20.0;
    /** Nodos objetivo por perfil (spec 004 §5.2, rango 150-200). */
    public static final int DEFAULT_NODE_TARGET = 180;
    /** Margen de seguridad alrededor de agua (m). */
    public static final double WATER_MARGIN_M = 10.0;
    /** Más allá de esto no hay datos OSM fiables: no se inventa terreno. */
    public static final double MAX_SYNTHETIC_RADIUS_M = 250.0;
    /**
     * Radio de trabajo para POIs reales (spec 004 R2: "buscar POIs... en el radio
     * de trabajo"). Un POI a kilómetros del ancla no es un nodo de esta ruta: sin
     * este tope, datos acumulados de otra celda generaban una ruta de 4 km con
     * nodos que el jugador nunca puede alcanzar caminando desde el punto 0.
     */
    public static final double MAX_POI_RADIUS_M = 250.0;

    private static final double EARTH_R = 6371000.0;

    private RouteGenerator() {
    }

    /**
     * Afinidad OSM → tipo de nodo de ruta (spec 004 R2/criterio 13).
     * <p>
     * {@code AMENITY} (farmacia, hospital) es el "punto de curación": el nodo se resuelve
     * con {@code enter}. {@code SHOP} → {@code use} (tienda), {@code TOURISM} →
     * {@code talk}, zonas verdes → {@code battle}. El terreno devuelve {@code null}.
     */
    public static RouteNode.Action actionFor(Kind kind) {
        if (kind == null) return null;
        switch (kind) {
            case AMENITY: return RouteNode.Action.ENTER;
            case SHOP: return RouteNode.Action.USE;
            case TOURISM: return RouteNode.Action.TALK;
            case PARK:
            case WOOD: return RouteNode.Action.BATTLE;
            case HIGHWAY:
            case BUILDING:
            case WATER:
            default: return null;
        }
    }

    /**
     * Clave de ROM estable: título de cabecera + versión (spec 004 §6: código de juego
     * y revisión, nunca el hash del dump).
     */
    public static String romKeyFromHeader(byte[] rom) {
        if (rom == null || rom.length < 0x14D) return "rom-v0";
        StringBuilder title = new StringBuilder();
        for (int i = 0x134; i <= 0x143 && i < rom.length; i++) {
            int b = rom[i] & 0xFF;
            if (b == 0) break;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9') || b == '_' || b == '-' || b == '.') {
                title.append((char) b);
            }
        }
        int version = rom[0x14C] & 0xFF;
        String t = title.toString();
        if (t.isEmpty()) t = "rom";
        return t + "-v" + version;
    }

    /**
     * Genera la ruta desde un ancla confirmada.
     *
     * @throws IllegalArgumentException si el ancla no cumple precisión o coordenadas.
     */
    public static Route generate(String romKey, double anchorLat, double anchorLon,
            double accuracyM, long confirmedAt, List<OsmElement> elements, int targetNodes) {
        if (romKey == null || romKey.isEmpty()) romKey = "rom-v0";
        if (Double.isNaN(anchorLat) || anchorLat < -90.0 || anchorLat > 90.0
                || Double.isNaN(anchorLon) || anchorLon < -180.0 || anchorLon > 180.0) {
            throw new IllegalArgumentException("ancla con coordenadas inválidas");
        }
        if (Double.isNaN(accuracyM) || accuracyM <= 0.0) {
            throw new IllegalArgumentException("ancla sin precisión GPS");
        }
        if (accuracyM > MAX_ANCHOR_ACCURACY_M) {
            throw new IllegalArgumentException("precisión de ancla ±"
                    + Math.round(accuracyM) + " m > " + (int) MAX_ANCHOR_ACCURACY_M + " m");
        }
        if (targetNodes <= 0) targetNodes = DEFAULT_NODE_TARGET;

        List<Candidate> cands = collectPois(elements, anchorLat, anchorLon);
        List<RouteNode> out = new ArrayList<RouteNode>(Math.min(targetNodes, 256));
        double curLat = anchorLat;
        double curLon = anchorLon;

        while (out.size() < targetNodes && !cands.isEmpty()) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < cands.size(); i++) {
                Candidate c = cands.get(i);
                double d = haversineM(curLat, curLon, c.lat, c.lon);
                if (d < bestD - 1e-9) {
                    bestD = d;
                    best = i;
                } else if (Math.abs(d - bestD) <= 1e-9 && c.compareTo(cands.get(best)) < 0) {
                    best = i;
                }
            }
            Candidate pick = cands.remove(best);
            // Tramo de caminata real hasta el POI: 8 m por metatile (spec 004 §5.2).
            // Sin esto la ruta saltaba del ancla al POI y no había nodos en el mapa
            // alrededor del punto 0; además la longitud dejaba de ser medible.
            double d = haversineM(curLat, curLon, pick.lat, pick.lon);
            int steps = (int) Math.floor(d / METATILE_M);
            for (int k = 1; k <= steps && out.size() < targetNodes - 1; k++) {
                double t = (k * METATILE_M) / d;
                double wLat = curLat + (pick.lat - curLat) * t;
                double wLon = curLon + (pick.lon - curLon) * t;
                if (isOverWater(wLat, wLon, elements)) break; // no se camina sobre agua
                if (tooClose(out, wLat, wLon, 1.0)) continue;
                out.add(RouteNode.at(out.size(), RouteNode.Action.WALK,
                        "synthetic", wLat, wLon, NODE_RADIUS_M));
            }
            if (out.size() < targetNodes) {
                out.add(RouteNode.at(out.size(), pick.action, pick.kind.name(),
                        pick.lat, pick.lon, NODE_RADIUS_M));
            }
            curLat = pick.lat;
            curLon = pick.lon;
        }

        // Respaldo sintético declarado: pasos de 8 m desde el último nodo, sin agua.
        int attempts = 0;
        int step = 0;
        final int maxAttempts = Math.max(64, targetNodes * 8);
        while (out.size() < targetNodes && attempts < maxAttempts) {
            attempts++;
            step++;
            double bearing = (step * 137.508) % 360.0;
            for (int turn = 0; turn < 12; turn++) {
                double[] p = destination(curLat, curLon, METATILE_M,
                        (bearing + turn * 30.0) % 360.0);
                if (haversineM(anchorLat, anchorLon, p[0], p[1]) > MAX_SYNTHETIC_RADIUS_M) {
                    continue;
                }
                if (isOverWater(p[0], p[1], elements)) continue;
                if (!tooClose(out, p[0], p[1], 1.0)) {
                    out.add(RouteNode.at(out.size(), RouteNode.Action.WALK,
                            "synthetic", p[0], p[1], NODE_RADIUS_M));
                    curLat = p[0];
                    curLon = p[1];
                    break;
                }
            }
        }
        return new Route(romKey, anchorLat, anchorLon, accuracyM, confirmedAt, out);
    }

    private static List<Candidate> collectPois(List<OsmElement> elements,
            double anchorLat, double anchorLon) {
        List<Candidate> cands = new ArrayList<Candidate>();
        if (elements == null) return cands;
        List<String> seen = new ArrayList<String>();
        for (OsmElement el : elements) {
            if (el == null || el.kind == null) continue;
            RouteNode.Action action = actionFor(el.kind);
            if (action == null) continue; // terreno o agua: no es nodo de historia
            if (el.lats == null || el.lons == null || el.lats.length == 0
                    || el.lats.length != el.lons.length) continue;
            double lat = 0.0, lon = 0.0;
            for (int i = 0; i < el.lats.length; i++) {
                lat += el.lats[i];
                lon += el.lons[i];
            }
            lat /= el.lats.length;
            lon /= el.lons.length;
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            // Solo POIs del radio de trabajo: nunca nodos a kilómetros del ancla.
            if (haversineM(anchorLat, anchorLon, lat, lon) > MAX_POI_RADIUS_M) continue;
            if (isOverWater(lat, lon, elements)) continue;
            String key = el.kind.name() + "|"
                    + String.format(Locale.ROOT, "%.6f|%.6f", lat, lon);
            if (seen.contains(key)) continue;
            seen.add(key);
            cands.add(new Candidate(el.kind, action, lat, lon));
        }
        return cands;
    }

    /** ¿El punto cae dentro de un polígono de agua o a menos de 10 m de un cauce? */
    public static boolean isOverWater(double lat, double lon, List<OsmElement> elements) {
        if (elements == null) return false;
        for (OsmElement el : elements) {
            if (el == null || el.kind != Kind.WATER) continue;
            if (el.lats == null || el.lons == null
                    || el.lats.length < 2 || el.lats.length != el.lons.length) continue;
            int n = el.lats.length;
            boolean closed = near(el.lats[0], el.lats[n - 1])
                    && near(el.lons[0], el.lons[n - 1]);
            if (closed && n >= 4 && pointInRing(lat, lon, el.lats, el.lons)) {
                return true;
            }
            for (int i = 1; i < n; i++) {
                if (distanceToSegmentM(lat, lon,
                        el.lats[i - 1], el.lons[i - 1], el.lats[i], el.lons[i])
                        < WATER_MARGIN_M) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean pointInRing(double lat, double lon, double[] lats, double[] lons) {
        boolean inside = false;
        for (int i = 0, j = lats.length - 1; i < lats.length; j = i++) {
            boolean cross = (lats[i] > lat) != (lats[j] > lat);
            if (cross) {
                double x = (lons[j] - lons[i]) * (lat - lats[i]) / (lats[j] - lats[i])
                        + lons[i];
                if (lon < x) inside = !inside;
            }
        }
        return inside;
    }

    private static double distanceToSegmentM(double lat, double lon,
            double la0, double lo0, double la1, double lo1) {
        double cos = Math.cos(Math.toRadians(lat));
        double ax = (lo0 - lon) * 111320.0 * cos;
        double ay = (la0 - lat) * 111320.0;
        double bx = (lo1 - lon) * 111320.0 * cos;
        double by = (la1 - lat) * 111320.0;
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 <= 0.0 ? 0.0 : -(ax * dx + ay * dy) / len2;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        double px = ax + t * dx;
        double py = ay + t * dy;
        return Math.sqrt(px * px + py * py);
    }

    private static boolean tooClose(List<RouteNode> nodes, double lat, double lon, double minM) {
        for (RouteNode n : nodes) {
            if (haversineM(n.lat, n.lon, lat, lon) < minM) return true;
        }
        return false;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    /** Punto destino a {@code distM} metros con rumbo {@code bearingDeg}. */
    static double[] destination(double lat, double lon, double distM, double bearingDeg) {
        double br = Math.toRadians(bearingDeg);
        double dLat = (distM / EARTH_R) * Math.cos(br);
        double dLon = (distM / (EARTH_R * Math.cos(Math.toRadians(lat)))) * Math.sin(br);
        return new double[] {
                lat + Math.toDegrees(dLat),
                lon + Math.toDegrees(dLon)
        };
    }

    public static double haversineM(double la0, double lo0, double la1, double lo1) {
        double dLat = Math.toRadians(la1 - la0);
        double dLon = Math.toRadians(lo1 - lo0);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la0)) * Math.cos(Math.toRadians(la1))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static final class Candidate implements Comparable<Candidate> {
        final Kind kind;
        final RouteNode.Action action;
        final double lat;
        final double lon;

        Candidate(Kind kind, RouteNode.Action action, double lat, double lon) {
            this.kind = kind;
            this.action = action;
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public int compareTo(Candidate o) {
            int c = Integer.compare(kind.ordinal(), o.kind.ordinal());
            if (c != 0) return c;
            c = Double.compare(lat, o.lat);
            if (c != 0) return c;
            return Double.compare(lon, o.lon);
        }
    }
}
