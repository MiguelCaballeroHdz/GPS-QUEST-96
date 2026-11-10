package hosttest;

import com.gpsquest96.gps.OsmDataFetcher.Kind;
import com.gpsquest96.gps.OsmDataFetcher.OsmElement;
import com.gpsquest96.render.IllustratedMap;
import com.gpsquest96.route.RouteNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host test del pintor ilustrado (spec 018).
 * <p>
 * Compila la clase REAL `IllustratedMap` (sin OpenGL) y verifica:
 *  1. La escala metros→píxeles es la misma que la del raster (el avatar
 *     sigue midiendo 1 unidad de 8 m y el mapa no cambia de escala).
 *  2. La tabla kind→color cubre los 8 kinds y la tabla kind→icono solo
 *     dibuja iconos donde toca (POI, nunca calles ni edificios sueltos).
 *  3. La triangulación (ear clipping) conserva el área en polígonos
 *     convexos y cóncavos, y degenera sin romper.
 *  4. El recorte por viewport: lo fuera de la ventana no se dibuja.
 *  5. La escena: calles como quads, áreas como rellenos, iconos y nodos;
 *     sin datos la escena es válida y vacía (pergamino, D5).
 *  6. Determinismo: mismos datos → mismos vértices.
 *  7. Cero texto: la escena no lleva ninguna cadena (ni nombres de calles
 *     ni etiquetas de POI, decisión de Miguel).
 */
public class IllustratedMapHostTest {

    private static int checks = 0;

    private static final double LAT = 19.5628067;
    private static final double LON = -96.9179283;
    private static final double COS_LAT = Math.cos(Math.toRadians(LAT));

    public static void main(String[] args) {
        cellPixelsScale();
        colorTable();
        iconTable();
        isClosedDetection();
        triangulation();
        segmentRect();
        pointInPolygon();
        emptyScene();
        streetScene();
        fillScene();
        poiIcons();
        routeNodesScene();
        determinism();
        verticesAreOpaque();
        metersAreMeters();
        System.out.println("ILLUSTRATED MAP HOST TEST OK (" + checks + " comprobaciones)");
    }

    /** Misma escala que el raster: 1220x2712 a zoom 1 = ~19,5 px por 8 m. */
    private static void cellPixelsScale() {
        float px = IllustratedMap.cellPixels(1220, 2712, 1.0f);
        check("cellPx 1220x2712 zoom 1 ≈ 19,51",
                Math.abs(px - 2712f / 139f) < 0.01f);
        check("zoom 2 duplica la escala",
                Math.abs(IllustratedMap.cellPixels(1220, 2712, 2.0f) - px * 2f) < 0.01f);
        check("zoom 0.5 divide la escala",
                Math.abs(IllustratedMap.cellPixels(1220, 2712, 0.5f) - px / 2f) < 0.01f);
        check("zoom 0 se recorta a 0.5x",
                Math.abs(IllustratedMap.cellPixels(1220, 2712, 0f) - px / 2f) < 0.01f);
        check("zoom 99 se recorta a 4x",
                Math.abs(IllustratedMap.cellPixels(1220, 2712, 99f) - px * 4f) < 0.01f);
        check("sin viewport no hay escala",
                IllustratedMap.cellPixels(0, 2712, 1.0f) == 0f);
        check("escala de 8 m (metatile, spec 004 §5.2)",
                Math.abs(IllustratedMap.SCALE_METERS - 8.0) < 1e-9);
    }

    /** kind → color: los 8 kinds tienen color y el agua no es calle. */
    private static void colorTable() {
        for (Kind k : Kind.values()) {
            float[] c = IllustratedMap.colorFor(k);
            check("color no nulo para " + k, c != null && c.length == 4);
            check("color opaco para " + k, c[3] == 1f);
        }
        check("agua distinta de calle",
                !Arrays.equals(IllustratedMap.colorFor(Kind.WATER),
                        IllustratedMap.colorFor(Kind.HIGHWAY)));
        check("parque distinto de agua",
                !Arrays.equals(IllustratedMap.colorFor(Kind.PARK),
                        IllustratedMap.colorFor(Kind.WATER)));
        check("kind nulo = pergamino",
                Arrays.equals(IllustratedMap.colorFor(null),
                        IllustratedMap.C_PARCHMENT));
    }

    /** kind → icono: solo POI; calles y edificios no llevan icono. */
    private static void iconTable() {
        check("tourism = museo",
                IllustratedMap.iconFor(Kind.TOURISM, true)
                        == IllustratedMap.IconKind.MUSEUM);
        check("tourism área = museo en centroide",
                IllustratedMap.iconFor(Kind.TOURISM, false)
                        == IllustratedMap.IconKind.MUSEUM);
        check("amenity = servicio",
                IllustratedMap.iconFor(Kind.AMENITY, true)
                        == IllustratedMap.IconKind.SERVICE);
        check("shop = comercio",
                IllustratedMap.iconFor(Kind.SHOP, true)
                        == IllustratedMap.IconKind.SHOP);
        check("parque puntual = árbol",
                IllustratedMap.iconFor(Kind.PARK, true)
                        == IllustratedMap.IconKind.TREE);
        check("parque área = sin icono (el relleno ya lo dice)",
                IllustratedMap.iconFor(Kind.PARK, false)
                        == IllustratedMap.IconKind.NONE);
        check("agua puntual = gota",
                IllustratedMap.iconFor(Kind.WATER, true)
                        == IllustratedMap.IconKind.WATER_DROP);
        check("calle = sin icono",
                IllustratedMap.iconFor(Kind.HIGHWAY, true)
                        == IllustratedMap.IconKind.NONE);
        check("edificio = sin icono",
                IllustratedMap.iconFor(Kind.BUILDING, true)
                        == IllustratedMap.IconKind.NONE);
        check("kind nulo = sin icono",
                IllustratedMap.iconFor(null, true)
                        == IllustratedMap.IconKind.NONE);
    }

    /** Anillo cerrado: primero ≈ último y ≥4 puntos. */
    private static void isClosedDetection() {
        double[] lats = {LAT, LAT, LAT + 0.001, LAT + 0.001, LAT};
        double[] lons = {LON, LON + 0.001, LON + 0.001, LON, LON};
        check("anillo cerrado", IllustratedMap.isClosed(lats, lons));
        double[] open = {LAT, LAT, LAT + 0.001, LAT + 0.001};
        double[] openLon = {LON, LON + 0.001, LON + 0.001, LON + 0.0005};
        check("vía abierta no es anillo", !IllustratedMap.isClosed(open, openLon));
        check("3 puntos no cierran",
                !IllustratedMap.isClosed(new double[]{1, 2, 1}, new double[]{1, 2, 1}));
        check("nulo no cierra", !IllustratedMap.isClosed(null, null));
    }

    /** Ear clipping: área conservada en cuadrado y en L cóncava. */
    private static void triangulation() {
        double[] sqX = {0, 10, 10, 0};
        double[] sqY = {0, 0, 10, 10};
        int[] sq = IllustratedMap.triangulate(sqX, sqY, 4);
        check("cuadrado = 2 triángulos", sq != null && sq.length == 6);
        check("cuadrado conserva área 100",
                sq != null && Math.abs(triArea(sqX, sqY, sq) - 100.0) < 1e-9);
        double[] lX = {0, 60, 60, 30, 30, 0};
        double[] lY = {0, 0, 30, 30, 60, 60};
        int[] l = IllustratedMap.triangulate(lX, lY, 6);
        check("L cóncava = 4 triángulos", l != null && l.length == 12);
        check("L conserva área 2700",
                l != null && Math.abs(triArea(lX, lY, l) - 2700.0) < 1e-9);
        int[] tri = IllustratedMap.triangulate(
                new double[]{0, 5, 0}, new double[]{0, 0, 5}, 3);
        check("triángulo = 1 triángulo", tri != null && tri.length == 3);
        check("colineal degenera (null)",
                IllustratedMap.triangulate(
                        new double[]{0, 5, 10}, new double[]{0, 0, 0}, 3) == null);
        check("menos de 3 puntos = null",
                IllustratedMap.triangulate(new double[]{0, 1}, new double[]{0, 1}, 2) == null);
    }

    private static double triArea(double[] xs, double[] ys, int[] idx) {
        double sum = 0.0;
        for (int i = 0; i + 2 < idx.length; i += 3) {
            double ax = xs[idx[i]], ay = ys[idx[i]];
            double bx = xs[idx[i + 1]], by = ys[idx[i + 1]];
            double cx = xs[idx[i + 2]], cy = ys[idx[i + 2]];
            sum += Math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0;
        }
        return sum;
    }

    /** Segmento contra rectángulo: cruce, fuera y extremo dentro. */
    private static void segmentRect() {
        check("segmento que cruza",
                IllustratedMap.segmentIntersectsRect(-20, 0, 20, 0, -10, -10, 10, 10));
        check("segmento fuera",
                !IllustratedMap.segmentIntersectsRect(20, 20, 30, 30, -10, -10, 10, 10));
        check("extremo dentro",
                IllustratedMap.segmentIntersectsRect(0, 0, 30, 0, -10, -10, 10, 10));
        check("segmento largo que envuelve el rectángulo",
                IllustratedMap.segmentIntersectsRect(-20, -20, 20, 20, -10, -10, 10, 10));
        check("segmento paralelo fuera",
                !IllustratedMap.segmentIntersectsRect(-20, 20, 20, 20, -10, -10, 10, 10));
    }

    /** Punto en polígono (ray casting). */
    private static void pointInPolygon() {
        double[] xs = {0, 10, 10, 0};
        double[] ys = {0, 0, 10, 10};
        check("centro dentro",
                IllustratedMap.pointInPolygon(5, 5, xs, ys, 4));
        check("fuera",
                !IllustratedMap.pointInPolygon(20, 5, xs, ys, 4));
        check("esquina fuera",
                !IllustratedMap.pointInPolygon(-1, -1, xs, ys, 4));
    }

    /** Sin datos la escena es válida y vacía (pergamino, D5). */
    private static void emptyScene() {
        IllustratedMap.Scene s = IllustratedMap.build(null, null, LAT, LON);
        check("escena nula no rompe", s != null);
        check("escena vacía = 0 triángulos", s.triangles == 0);
        check("escena vacía = 0 rellenos", s.fills == 0);
        check("escena vacía = 0 calles", s.streets == 0);
        check("escena vacía = 0 iconos", s.icons == 0);
        check("escena vacía = 0 nodos", s.nodes == 0);
        check("escena guarda su centro",
                Math.abs(s.centerLat - LAT) < 1e-9 && Math.abs(s.centerLon - LON) < 1e-9);
        IllustratedMap.Scene s2 = IllustratedMap.build(
                new ArrayList<OsmElement>(), new ArrayList<RouteNode>(), LAT, LON);
        check("listas vacías = escena vacía", s2.triangles == 0);
    }

    /** Una calle de 100 m se dibuja; una a 3 km no (recorte). */
    private static void streetScene() {
        double dLon = 100.0 / (111320.0 * COS_LAT);
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(new OsmElement(Kind.HIGHWAY,
                new double[]{LAT, LAT}, new double[]{LON, LON + dLon}));
        IllustratedMap.Scene s = IllustratedMap.build(els, null, LAT, LON);
        check("calle contada", s.streets == 1);
        check("calle genera triángulos", s.triangles > 0);
        List<OsmElement> far = new ArrayList<OsmElement>();
        far.add(new OsmElement(Kind.HIGHWAY,
                new double[]{LAT + 0.03, LAT + 0.03},
                new double[]{LON, LON + dLon}));
        IllustratedMap.Scene sFar = IllustratedMap.build(far, null, LAT, LON);
        check("calle a 3 km no se dibuja", sFar.streets == 0 && sFar.triangles == 0);
        List<OsmElement> one = new ArrayList<OsmElement>();
        one.add(new OsmElement(Kind.HIGHWAY, new double[]{LAT}, new double[]{LON}));
        check("calle de un punto no dibuja",
                IllustratedMap.build(one, null, LAT, LON).triangles == 0);
    }

    /** Un parque cuadrado 50x50 m = 1 relleno de 2 triángulos. */
    private static void fillScene() {
        double dLat = 25.0 / 111320.0;
        double dLon = 25.0 / (111320.0 * COS_LAT);
        double[] lats = {LAT - dLat, LAT - dLat, LAT + dLat, LAT + dLat, LAT - dLat};
        double[] lons = {LON - dLon, LON + dLon, LON + dLon, LON - dLon, LON - dLon};
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(new OsmElement(Kind.PARK, lats, lons));
        IllustratedMap.Scene s = IllustratedMap.build(els, null, LAT, LON);
        check("parque contado", s.fills == 1);
        check("parque cuadrado = 2 triángulos", s.triangles == 2);
        List<OsmElement> water = new ArrayList<OsmElement>();
        water.add(new OsmElement(Kind.WATER, lats, lons));
        IllustratedMap.Scene sw = IllustratedMap.build(water, null, LAT, LON);
        check("agua = 1 relleno", sw.fills == 1);
        List<OsmElement> open = new ArrayList<OsmElement>();
        open.add(new OsmElement(Kind.BUILDING,
                new double[]{LAT, LAT + dLat}, new double[]{LON, LON + dLon}));
        check("edificio abierto dibuja su borde",
                IllustratedMap.build(open, null, LAT, LON).triangles > 0);
    }

    /** POI puntual = icono; área tourism = relleno + icono en centroide. */
    private static void poiIcons() {
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(new OsmElement(Kind.TOURISM, new double[]{LAT}, new double[]{LON}));
        IllustratedMap.Scene s = IllustratedMap.build(els, null, LAT, LON);
        check("museo puntual = 1 icono", s.icons == 1);
        check("museo puntual no es relleno", s.fills == 0);
        List<OsmElement> shop = new ArrayList<OsmElement>();
        shop.add(new OsmElement(Kind.SHOP, new double[]{LAT}, new double[]{LON}));
        check("comercio puntual = 1 icono",
                IllustratedMap.build(shop, null, LAT, LON).icons == 1);
        double dLat = 25.0 / 111320.0;
        double dLon = 25.0 / (111320.0 * COS_LAT);
        double[] lats = {LAT - dLat, LAT - dLat, LAT + dLat, LAT + dLat, LAT - dLat};
        double[] lons = {LON - dLon, LON + dLon, LON + dLon, LON - dLon, LON - dLon};
        List<OsmElement> area = new ArrayList<OsmElement>();
        area.add(new OsmElement(Kind.TOURISM, lats, lons));
        IllustratedMap.Scene sa = IllustratedMap.build(area, null, LAT, LON);
        check("museo área = relleno + icono", sa.fills == 1 && sa.icons == 1);
        List<OsmElement> far = new ArrayList<OsmElement>();
        far.add(new OsmElement(Kind.TOURISM,
                new double[]{LAT + 0.03}, new double[]{LON}));
        check("museo a 3 km no se dibuja",
                IllustratedMap.build(far, null, LAT, LON).icons == 0);
    }

    /** Nodos de ruta: marcador dorado encima; fuera de ventana no. */
    private static void routeNodesScene() {
        List<RouteNode> nodes = new ArrayList<RouteNode>();
        nodes.add(RouteNode.at(0, RouteNode.Action.WALK, "walk", LAT, LON, 15.0));
        nodes.add(RouteNode.at(1, RouteNode.Action.WALK, "walk",
                LAT + 0.001, LON, 15.0));
        IllustratedMap.Scene s = IllustratedMap.build(null, nodes, LAT, LON);
        check("2 nodos contados", s.nodes == 2);
        check("nodos generan triángulos", s.triangles > 0);
        List<RouteNode> far = new ArrayList<RouteNode>();
        far.add(RouteNode.at(0, RouteNode.Action.WALK, "walk",
                LAT + 0.03, LON, 15.0));
        check("nodo a 3 km no se dibuja",
                IllustratedMap.build(null, far, LAT, LON).nodes == 0);
        check("nodo nulo no rompe",
                IllustratedMap.build(null, Arrays.asList((RouteNode) null), LAT, LON).nodes == 0);
    }

    /** Determinismo: mismos datos → mismos vértices. */
    private static void determinism() {
        double dLon = 100.0 / (111320.0 * COS_LAT);
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(new OsmElement(Kind.HIGHWAY,
                new double[]{LAT, LAT}, new double[]{LON, LON + dLon}));
        els.add(new OsmElement(Kind.PARK,
                new double[]{LAT, LAT}, new double[]{LON, LON}));
        List<RouteNode> nodes = new ArrayList<RouteNode>();
        nodes.add(RouteNode.at(0, RouteNode.Action.WALK, "walk", LAT, LON, 15.0));
        IllustratedMap.Scene a = IllustratedMap.build(els, nodes, LAT, LON);
        IllustratedMap.Scene b = IllustratedMap.build(els, nodes, LAT, LON);
        check("escena determinista (vértices idénticos)",
                Arrays.equals(a.verts, b.verts));
    }

    /** Todos los vértices opacos: nada translúcido sobre el pergamino. */
    private static void verticesAreOpaque() {
        double dLon = 100.0 / (111320.0 * COS_LAT);
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(new OsmElement(Kind.HIGHWAY,
                new double[]{LAT, LAT}, new double[]{LON, LON + dLon}));
        els.add(new OsmElement(Kind.TOURISM, new double[]{LAT}, new double[]{LON}));
        IllustratedMap.Scene s = IllustratedMap.build(els, null, LAT, LON);
        boolean allOpaque = true;
        for (int i = 5; i < s.verts.length; i += 6) {
            if (s.verts[i] != 1f) {
                allOpaque = false;
                break;
            }
        }
        check("todos los vértices opacos", allOpaque && s.triangles > 0);
    }

    /** La proyección es metros reales: calle de 100 m mide 100 m en la escena. */
    private static void metersAreMeters() {
        double dLon = 100.0 / (111320.0 * COS_LAT);
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(new OsmElement(Kind.HIGHWAY,
                new double[]{LAT, LAT}, new double[]{LON, LON + dLon}));
        IllustratedMap.Scene s = IllustratedMap.build(els, null, LAT, LON);
        double maxX = 0.0;
        double maxY = 0.0;
        for (int i = 0; i + 1 < s.verts.length; i += 6) {
            maxX = Math.max(maxX, Math.abs(s.verts[i]));
            maxY = Math.max(maxY, Math.abs(s.verts[i + 1]));
        }
        check("calle de 100 m llega a x ≈ 103,5 m (disco del extremo)",
                Math.abs(maxX - 103.5) < 0.01);
        check("ancho de calle ≈ 7 m (±3,5 m en y)",
                Math.abs(maxY - 3.5) < 0.01);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("FALLO: " + name);
    }
}
