package com.gpsquest96.render;

import com.gpsquest96.gps.OsmDataFetcher.Kind;
import com.gpsquest96.gps.OsmDataFetcher.OsmElement;
import com.gpsquest96.route.RouteNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Pintor ilustrado (spec 018): los datos OSM reales dibujados como el mapa
 * turístico de Xalapa de la referencia (`specs/018-mapa-ilustrado/ref/`).
 * <p>
 * Java puro, sin OpenGL: construye la escena (triángulos con color) a partir
 * de los elementos OSM que la app ya descarga y clasifica. El dibujo GL vive
 * en {@link IllustratedMapRenderer}, que solo sube estos vértices y aplica
 * la escala y el desplazamiento por frame.
 * <p>
 * Reglas (D1, decisión de Miguel 2026-11-10):
 * <ul>
 *   <li>Misma geografía real, a escala, sigue al jugador, offline con la
 *       caché de vector del corredor. Sin celdas: geometría vectorial.</li>
 *   <li>Estilo de la referencia: fondo pergamino, calles en tono arena
 *       (líneas gruesas, uniones redondeadas), parques verde suave, agua
 *       azul, edificios como bloques tenues, iconos dibujados (sin texto)
 *       para lugares emblemáticos según POI.</li>
 *   <li>CERO texto en el mapa: ni nombres de calles ni etiquetas de POI.
 *       "Solo es un juego, no hay que llenarlo de información innecesaria".</li>
 *   <li>Nada inventado: solo se dibuja lo que trae un elemento OSM o un
 *       nodo de ruta. Sin datos de vector, la escena es solo el pergamino
 *       (D5) y el renderer la dibuja igual.</li>
 * </ul>
 * La escena vive en <b>metros</b> relativos al centro (este = +x, norte =
 * +y): así no depende del viewport ni del zoom y solo se reconstruye cuando
 * cambian los datos. El recorte es una ventana fija de ±1200 m, que cubre
 * el zoom mínimo (0.5x: 1008x2224 m); el resto lo recorta la GPU.
 */
public final class IllustratedMap {

    /** 1 unidad de escala = 8 m (misma escala que el raster, spec 004 §5.2). */
    public static final double SCALE_METERS = 8.0;
    /** Ancho cubierto a zoom 1 (igual que el raster 63x139 a 8 m). */
    public static final double VIEW_WIDTH_M = 504.0;
    /** Alto cubierto a zoom 1 (igual que el raster 63x139 a 8 m). */
    public static final double VIEW_HEIGHT_M = 1112.0;
    /** Metros por grado de latitud (misma aproximación que el raster). */
    public static final double METERS_PER_DEG = 111320.0;
    /** Ventana de recorte en metros (±): cubre el zoom mínimo (0.5x). */
    public static final double CULL_HALF_M = 1200.0;
    /** Ancho de calle en metros (sin dato de ancho en los elementos OSM). */
    public static final double STREET_WIDTH_M = 7.0;
    /** Ancho del borde de un área abierta recortada por el corredor. */
    public static final double AREA_EDGE_WIDTH_M = 3.0;
    /** Radio del icono de POI en metros. */
    public static final double ICON_RADIUS_M = 11.0;
    /** Radio del marcador de nodo de ruta en metros. */
    public static final double NODE_RADIUS_M = 9.0;
    /** Segmentos del abanico de un disco (uniones redondeadas, iconos). */
    public static final int DISC_SEGMENTS = 10;

    // ═══════════════════════════════════════════════════════
    // PALETA PERGAMINO (referencia: mapa turístico de Xalapa)
    // ═══════════════════════════════════════════════════════
    public static final float[] C_PARCHMENT = {0.949f, 0.910f, 0.808f, 1f};
    public static final float[] C_STREET    = {0.788f, 0.694f, 0.549f, 1f};
    public static final float[] C_PARK      = {0.663f, 0.776f, 0.545f, 1f};
    public static final float[] C_WOOD      = {0.561f, 0.714f, 0.451f, 1f};
    public static final float[] C_WATER     = {0.498f, 0.698f, 0.851f, 1f};
    public static final float[] C_BUILDING  = {0.863f, 0.812f, 0.659f, 1f};
    public static final float[] C_SHOP      = {0.890f, 0.784f, 0.561f, 1f};
    public static final float[] C_TOURISM   = {0.910f, 0.851f, 0.627f, 1f};
    public static final float[] C_AMENITY   = {0.898f, 0.769f, 0.639f, 1f};
    public static final float[] C_INK       = {0.200f, 0.278f, 0.357f, 1f};
    public static final float[] C_ICON_DISC = {0.969f, 0.945f, 0.863f, 1f};
    public static final float[] C_NODE      = {0.949f, 0.761f, 0.188f, 1f};
    public static final float[] C_NODE_RING = {0.541f, 0.427f, 0.106f, 1f};

    /** Icono dibujado (geometría, sin texto) para un POI. */
    public enum IconKind {
        NONE, MUSEUM, SERVICE, SHOP, TREE, WATER_DROP
    }

    private IllustratedMap() {
    }

    /**
     * Lado de la unidad de escala (8 m) en píxeles con el viewport y zoom
     * actuales. Misma fórmula que el raster: el avatar sigue midiendo 1
     * unidad de ancho y el mapa no cambia de escala al enterrar el raster.
     */
    public static float cellPixels(int w, int h, float zoom) {
        if (w <= 0 || h <= 0) return 0f;
        if (zoom < 0.5f) zoom = 0.5f;
        if (zoom > 4.0f) zoom = 4.0f;
        float byW = (float) (w / (VIEW_WIDTH_M / SCALE_METERS));
        float byH = (float) (h / (VIEW_HEIGHT_M / SCALE_METERS));
        return Math.max(byW, byH) * zoom;
    }

    /** Color de relleno por kind (áreas cerradas). */
    public static float[] colorFor(Kind kind) {
        if (kind == null) return C_PARCHMENT;
        switch (kind) {
            case WATER: return C_WATER;
            case PARK: return C_PARK;
            case WOOD: return C_WOOD;
            case BUILDING: return C_BUILDING;
            case SHOP: return C_SHOP;
            case TOURISM: return C_TOURISM;
            case AMENITY: return C_AMENITY;
            case HIGHWAY:
            default: return C_STREET;
        }
    }

    /**
     * Icono por kind de POI. Solo para elementos puntuales o centroides de
     * áreas: un POI es un lugar emblemático, no una línea. Sin tags en los
     * elementos (el fetcher solo guarda el kind), el icono se decide por
     * kind: museo/monumento para tourism, servicio para amenity, comercio
     * para shop, árbol para parque/bosque puntual, gota para agua puntual.
     */
    public static IconKind iconFor(Kind kind, boolean isPoint) {
        if (kind == null) return IconKind.NONE;
        switch (kind) {
            case TOURISM: return IconKind.MUSEUM;
            case AMENITY: return IconKind.SERVICE;
            case SHOP: return IconKind.SHOP;
            case PARK:
            case WOOD: return isPoint ? IconKind.TREE : IconKind.NONE;
            case WATER: return isPoint ? IconKind.WATER_DROP : IconKind.NONE;
            case HIGHWAY:
            case BUILDING:
            default: return IconKind.NONE;
        }
    }

    /** ¿La vía es un anillo cerrado (≥3 puntos distintos, primero ≈ último)? */
    public static boolean isClosed(double[] lats, double[] lons) {
        if (lats == null || lons == null) return false;
        int n = Math.min(lats.length, lons.length);
        if (n < 4) return false;
        return Math.abs(lats[0] - lats[n - 1]) < 1e-9
                && Math.abs(lons[0] - lons[n - 1]) < 1e-9;
    }

    /** Escena construida: triángulos en metros relativos al centro. */
    public static final class Scene {
        /** Vértices: x, y, r, g, b, a por vértice (metros, este/norte). */
        public final float[] verts;
        /** Número de triángulos (verts.length / 18). */
        public final int triangles;
        public final double centerLat;
        public final double centerLon;
        public final int fills;
        public final int streets;
        public final int icons;
        public final int nodes;

        Scene(float[] verts, int triangles, double centerLat, double centerLon,
                int fills, int streets, int icons, int nodes) {
            this.verts = verts;
            this.triangles = triangles;
            this.centerLat = centerLat;
            this.centerLon = centerLon;
            this.fills = fills;
            this.streets = streets;
            this.icons = icons;
            this.nodes = nodes;
        }
    }

    /**
     * Construye la escena. Sin elementos o sin nodos la escena sigue siendo
     * válida: solo el pergamino (D5). Nunca devuelve null.
     */
    public static Scene build(List<OsmElement> elements, List<RouteNode> nodes,
            double centerLat, double centerLon) {
        Tris tris = new Tris();
        double cosLat = Math.cos(Math.toRadians(centerLat));
        int fills = 0;
        int streets = 0;
        int icons = 0;

        if (elements != null) {
            for (OsmElement el : elements) {
                if (el == null || el.kind == null) continue;
                if (el.lats == null || el.lons == null) continue;
                int n = Math.min(el.lats.length, el.lons.length);
                if (n == 0) continue;
                double[] xs = new double[n];
                double[] ys = new double[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = (el.lons[i] - centerLon) * METERS_PER_DEG * cosLat;
                    ys[i] = (el.lats[i] - centerLat) * METERS_PER_DEG;
                }
                if (n == 1) {
                    // POI puntual: solo icono (una calle de un punto no dibuja).
                    IconKind icon = iconFor(el.kind, true);
                    if (icon != IconKind.NONE && insideCull(xs[0], ys[0])) {
                        addIcon(tris, xs[0], ys[0], icon);
                        icons++;
                    }
                    continue;
                }
                if (isClosed(el.lats, el.lons)) {
                    // Área: relleno triangulado + icono en el centroide si toca.
                    if (!polyVisible(xs, ys, n)) continue;
                    int before = tris.triangles;
                    addFill(tris, xs, ys, n, colorFor(el.kind));
                    if (tris.triangles > before) {
                        fills++;
                        IconKind icon = iconFor(el.kind, false);
                        if (icon != IconKind.NONE) {
                            double[] c = centroid(xs, ys, n);
                            if (insideCull(c[0], c[1])) {
                                addIcon(tris, c[0], c[1], icon);
                                icons++;
                            }
                        }
                    }
                } else if (el.kind == Kind.HIGHWAY) {
                    // Calle: quads gruesos con uniones redondeadas.
                    if (!polyVisible(xs, ys, n)) continue;
                    addStreet(tris, xs, ys, n);
                    streets++;
                } else {
                    // Área abierta (anillo recortado por el corredor): su borde,
                    // en el color del relleno, para no perder el área del todo.
                    if (!polyVisible(xs, ys, n)) continue;
                    addPolyline(tris, xs, ys, n, AREA_EDGE_WIDTH_M, colorFor(el.kind));
                }
            }
        }

        int nodeCount = 0;
        if (nodes != null) {
            for (RouteNode node : nodes) {
                if (node == null) continue;
                double x = (node.lon - centerLon) * METERS_PER_DEG * cosLat;
                double y = (node.lat - centerLat) * METERS_PER_DEG;
                if (!insideCull(x, y)) continue;
                addDisc(tris, x, y, NODE_RADIUS_M + 1.5, C_NODE_RING);
                addDisc(tris, x, y, NODE_RADIUS_M - 1.5, C_NODE);
                nodeCount++;
            }
        }

        float[] verts = tris.toArray();
        return new Scene(verts, tris.triangles, centerLat, centerLon,
                fills, streets, icons, nodeCount);
    }

    /** ¿El punto cae dentro de la ventana de recorte? */
    static boolean insideCull(double x, double y) {
        return x >= -CULL_HALF_M && x <= CULL_HALF_M
                && y >= -CULL_HALF_M && y <= CULL_HALF_M;
    }

    /**
     * ¿La polilínea/polígono toca la ventana de recorte? Un vértice dentro,
     * un segmento que la cruza, o la ventana entera dentro del polígono.
     */
    static boolean polyVisible(double[] xs, double[] ys, int n) {
        for (int i = 0; i < n; i++) {
            if (insideCull(xs[i], ys[i])) return true;
        }
        for (int i = 1; i < n; i++) {
            if (segmentIntersectsRect(xs[i - 1], ys[i - 1], xs[i], ys[i],
                    -CULL_HALF_M, -CULL_HALF_M, CULL_HALF_M, CULL_HALF_M)) {
                return true;
            }
        }
        // Polígono mayor que la ventana: el centro de la ventana está dentro.
        if (n >= 4 && pointInPolygon(0.0, 0.0, xs, ys, n)) return true;
        return false;
    }

    /** ¿El segmento toca el rectángulo (extremos dentro o cruce de un borde)? */
    public static boolean segmentIntersectsRect(double x0, double y0, double x1, double y1,
            double minX, double minY, double maxX, double maxY) {
        if (pointInRect(x0, y0, minX, minY, maxX, maxY)
                || pointInRect(x1, y1, minX, minY, maxX, maxY)) {
            return true;
        }
        return segmentsIntersect(x0, y0, x1, y1, minX, minY, maxX, minY)
                || segmentsIntersect(x0, y0, x1, y1, maxX, minY, maxX, maxY)
                || segmentsIntersect(x0, y0, x1, y1, maxX, maxY, minX, maxY)
                || segmentsIntersect(x0, y0, x1, y1, minX, maxY, minX, minY);
    }

    private static boolean pointInRect(double x, double y,
            double minX, double minY, double maxX, double maxY) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    private static int orient(double ax, double ay, double bx, double by,
            double cx, double cy) {
        double v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (v > 0) return 1;
        if (v < 0) return -1;
        return 0;
    }

    /** B sobre el segmento AC (colineal supuesto). */
    private static boolean onSegment(double ax, double ay, double bx, double by,
            double cx, double cy) {
        return bx >= Math.min(ax, cx) && bx <= Math.max(ax, cx)
                && by >= Math.min(ay, cy) && by <= Math.max(ay, cy);
    }

    /**
     * ¿Los segmentos se tocan? Inclusivo: un cruce por la esquina del
     * rectángulo cuenta (el test estricto lo perdía y una calle diagonal
     * que roza la esquina desaparecía del mapa).
     */
    private static boolean segmentsIntersect(double x0, double y0, double x1, double y1,
            double x2, double y2, double x3, double y3) {
        int o1 = orient(x0, y0, x1, y1, x2, y2);
        int o2 = orient(x0, y0, x1, y1, x3, y3);
        int o3 = orient(x2, y2, x3, y3, x0, y0);
        int o4 = orient(x2, y2, x3, y3, x1, y1);
        if (o1 != o2 && o3 != o4) return true;
        if (o1 == 0 && onSegment(x0, y0, x2, y2, x1, y1)) return true;
        if (o2 == 0 && onSegment(x0, y0, x3, y3, x1, y1)) return true;
        if (o3 == 0 && onSegment(x2, y2, x0, y0, x3, y3)) return true;
        if (o4 == 0 && onSegment(x2, y2, x1, y1, x3, y3)) return true;
        return false;
    }

    /** Punto dentro del polígono (ray casting, borde = dentro). */
    public static boolean pointInPolygon(double x, double y,
            double[] xs, double[] ys, int n) {
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = xs[i], yi = ys[i];
            double xj = xs[j], yj = ys[j];
            if ((yi > y) != (yj > y)) {
                double xInt = (xj - xi) * (y - yi) / (yj - yi) + xi;
                if (x < xInt) inside = !inside;
            }
        }
        return inside;
    }

    /** Centroide (media de vértices; bastante para colocar el icono). */
    static double[] centroid(double[] xs, double[] ys, int n) {
        double sx = 0.0, sy = 0.0;
        int m = Math.min(n, xs.length);
        m = Math.min(m, ys.length);
        if (m == 0) return new double[]{0.0, 0.0};
        for (int i = 0; i < m; i++) {
            sx += xs[i];
            sy += ys[i];
        }
        return new double[]{sx / m, sy / m};
    }

    /** Relleno de un polígono (ear clipping; abanico si el polígono degenera). */
    static void addFill(Tris tris, double[] xs, double[] ys, int n, float[] color) {
        int m = Math.min(n, Math.min(xs.length, ys.length));
        if (m < 3) return;
        // Sin el punto de cierre duplicado para triangular.
        if (m > 3 && Math.abs(xs[0] - xs[m - 1]) < 1e-9
                && Math.abs(ys[0] - ys[m - 1]) < 1e-9) {
            m--;
        }
        if (m < 3) return;
        int[] idx = triangulate(xs, ys, m);
        if (idx == null) {
            // Polígono degenerado: abanico desde el centroide (nunca vacío).
            double[] c = centroid(xs, ys, m);
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                tris.tri(xs[i], ys[i], xs[j], ys[j], c[0], c[1], color);
            }
            return;
        }
        for (int i = 0; i + 2 < idx.length; i += 3) {
            tris.tri(xs[idx[i]], ys[idx[i]], xs[idx[i + 1]], ys[idx[i + 1]],
                    xs[idx[i + 2]], ys[idx[i + 2]], color);
        }
    }

    /**
     * Triangulación ear clipping (polígono simple, sin agujeros). Devuelve
     * índices (3 por triángulo) o null si el polígono degenera.
     */
    public static int[] triangulate(double[] xs, double[] ys, int n) {
        int m = Math.min(n, Math.min(xs.length, ys.length));
        if (m < 3) return null;
        ArrayList<Integer> v = new ArrayList<Integer>(m);
        // Sentido antihorario: el área con signo decide (el ear test lo exige).
        double area2 = 0.0;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            area2 += xs[i] * ys[j] - xs[j] * ys[i];
        }
        if (area2 > 0) {
            for (int i = 0; i < m; i++) v.add(i);
        } else if (area2 < 0) {
            for (int i = m - 1; i >= 0; i--) v.add(i);
        } else {
            return null; // área cero: línea, no polígono
        }
        ArrayList<Integer> out = new ArrayList<Integer>((m - 2) * 3);
        int guard = 0;
        while (v.size() > 3 && guard < m * m * 3) {
            guard++;
            boolean clipped = false;
            for (int i = 0; i < v.size(); i++) {
                int prev = v.get((i + v.size() - 1) % v.size());
                int cur = v.get(i);
                int next = v.get((i + 1) % v.size());
                if (isEar(xs, ys, prev, cur, next, v)) {
                    out.add(prev);
                    out.add(cur);
                    out.add(next);
                    v.remove(i);
                    clipped = true;
                    break;
                }
            }
            if (!clipped) return null;
        }
        if (v.size() == 3) {
            out.add(v.get(0));
            out.add(v.get(1));
            out.add(v.get(2));
        }
        if (out.size() < 3) return null;
        int[] res = new int[out.size()];
        for (int i = 0; i < res.length; i++) res[i] = out.get(i);
        return res;
    }

    /** ¿El vértice `cur` es una oreja (convexo y sin vértices dentro)? */
    private static boolean isEar(double[] xs, double[] ys,
            int prev, int cur, int next, List<Integer> v) {
        double ax = xs[prev], ay = ys[prev];
        double bx = xs[cur], by = ys[cur];
        double cx = xs[next], cy = ys[next];
        // Convexo en sentido antihorario: giro a la izquierda.
        if (cross(bx - ax, by - ay, cx - bx, cy - by) <= 0) return false;
        for (int k : v) {
            if (k == prev || k == cur || k == next) continue;
            if (pointInTriangle(xs[k], ys[k], ax, ay, bx, by, cx, cy)) return false;
        }
        return true;
    }

    private static boolean pointInTriangle(double px, double py,
            double ax, double ay, double bx, double by, double cx, double cy) {
        double d1 = cross(px - ax, py - ay, bx - ax, by - ay);
        double d2 = cross(px - bx, py - by, cx - bx, cy - by);
        double d3 = cross(px - cx, py - cy, ax - cx, ay - cy);
        boolean neg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean pos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(neg && pos);
    }

    /** Calle: quads gruesos por segmento + discos en cada vértice (uniones redondeadas). */
    static void addStreet(Tris tris, double[] xs, double[] ys, int n) {
        addPolyline(tris, xs, ys, n, STREET_WIDTH_M, C_STREET);
        for (int i = 0; i < n; i++) {
            addDisc(tris, xs[i], ys[i], STREET_WIDTH_M / 2.0, C_STREET);
        }
    }

    /** Polilínea gruesa: un quad por segmento (sin discos en los vértices). */
    static void addPolyline(Tris tris, double[] xs, double[] ys, int n,
            double widthM, float[] color) {
        double half = widthM / 2.0;
        for (int i = 1; i < n; i++) {
            double dx = xs[i] - xs[i - 1];
            double dy = ys[i] - ys[i - 1];
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-9) continue;
            double nx = -dy / len * half;
            double ny = dx / len * half;
            tris.quad(
                    xs[i - 1] + nx, ys[i - 1] + ny,
                    xs[i - 1] - nx, ys[i - 1] - ny,
                    xs[i] + nx, ys[i] + ny,
                    xs[i] - nx, ys[i] - ny, color);
        }
    }

    /** Disco (abanico) centrado, en metros. */
    static void addDisc(Tris tris, double cx, double cy, double r, float[] color) {
        double step = 2.0 * Math.PI / DISC_SEGMENTS;
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;
            tris.tri(cx, cy,
                    cx + Math.cos(a0) * r, cy + Math.sin(a0) * r,
                    cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, color);
        }
    }

    /**
     * Icono dibujado: medallón pergamino + glifo en tinta, sin texto.
     * Museo = frontón + base; servicio = cruz; comercio = casa; árbol =
     * copa + tronco; gota = elipse de agua. Todo geometría en metros.
     */
    static void addIcon(Tris tris, double cx, double cy, IconKind icon) {
        double r = ICON_RADIUS_M;
        addDisc(tris, cx, cy, r, C_ICON_DISC);
        switch (icon) {
            case MUSEUM: {
                // Frontón triangular + cuerpo + base.
                tris.tri(cx - r * 0.55, cy + r * 0.10,
                        cx + r * 0.55, cy + r * 0.10,
                        cx, cy + r * 0.62, C_INK);
                tris.quad(cx - r * 0.45, cy - r * 0.45,
                        cx + r * 0.45, cy - r * 0.45,
                        cx + r * 0.45, cy + r * 0.10,
                        cx - r * 0.45, cy + r * 0.10, C_INK);
                tris.quad(cx - r * 0.55, cy - r * 0.62,
                        cx + r * 0.55, cy - r * 0.62,
                        cx + r * 0.55, cy - r * 0.45,
                        cx - r * 0.55, cy - r * 0.45, C_INK);
                break;
            }
            case SERVICE: {
                // Cruz de servicio.
                tris.quad(cx - r * 0.16, cy - r * 0.55,
                        cx + r * 0.16, cy - r * 0.55,
                        cx + r * 0.16, cy + r * 0.55,
                        cx - r * 0.16, cy + r * 0.55, C_INK);
                tris.quad(cx - r * 0.55, cy - r * 0.16,
                        cx + r * 0.55, cy - r * 0.16,
                        cx + r * 0.55, cy + r * 0.16,
                        cx - r * 0.55, cy + r * 0.16, C_INK);
                break;
            }
            case SHOP: {
                // Casa: cuerpo + tejado.
                tris.quad(cx - r * 0.45, cy - r * 0.45,
                        cx + r * 0.45, cy - r * 0.45,
                        cx + r * 0.45, cy + r * 0.05,
                        cx - r * 0.45, cy + r * 0.05, C_INK);
                tris.tri(cx - r * 0.55, cy + r * 0.05,
                        cx + r * 0.55, cy + r * 0.05,
                        cx, cy + r * 0.55, C_INK);
                break;
            }
            case TREE: {
                // Copa + tronco.
                addDisc(tris, cx, cy + r * 0.18, r * 0.42, C_WOOD);
                tris.quad(cx - r * 0.12, cy - r * 0.60,
                        cx + r * 0.12, cy - r * 0.60,
                        cx + r * 0.12, cy - r * 0.05,
                        cx - r * 0.12, cy - r * 0.05, C_INK);
                break;
            }
            case WATER_DROP: {
                // Gota: disco + punta.
                addDisc(tris, cx, cy - r * 0.10, r * 0.42, C_WATER);
                tris.tri(cx - r * 0.42, cy - r * 0.05,
                        cx + r * 0.42, cy - r * 0.05,
                        cx, cy + r * 0.62, C_WATER);
                break;
            }
            case NONE:
            default:
                break;
        }
    }

    /** Acumulador de triángulos: 6 floats por vértice (x, y, r, g, b, a). */
    static final class Tris {
        static final int FLOATS_PER_VERTEX = 6;
        private float[] data = new float[1024];
        private int size = 0;
        int triangles = 0;

        void tri(double ax, double ay, double bx, double by,
                double cx, double cy, float[] color) {
            ensure(3 * FLOATS_PER_VERTEX);
            put((float) ax, (float) ay, color);
            put((float) bx, (float) by, color);
            put((float) cx, (float) cy, color);
            triangles++;
        }

        /** Quad (a, b, c, d) = triángulos (a,b,c) + (a,c,d). */
        void quad(double ax, double ay, double bx, double by,
                double cx, double cy, double dx, double dy, float[] color) {
            tri(ax, ay, bx, by, cx, cy, color);
            tri(ax, ay, cx, cy, dx, dy, color);
        }

        private void put(float x, float y, float[] color) {
            data[size++] = x;
            data[size++] = y;
            data[size++] = color[0];
            data[size++] = color[1];
            data[size++] = color[2];
            data[size++] = color[3];
        }

        private void ensure(int need) {
            if (size + need > data.length) {
                float[] nd = new float[Math.max(data.length * 2, size + need)];
                System.arraycopy(data, 0, nd, 0, size);
                data = nd;
            }
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}
