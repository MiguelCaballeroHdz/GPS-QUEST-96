package hosttest;

import com.gpsquest96.gps.OsmDataFetcher.Kind;
import com.gpsquest96.gps.OsmDataFetcher.OsmElement;
import com.gpsquest96.render.OsmRasterGrid;
import com.gpsquest96.route.RouteNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host test de la cobertura del raster OSM (2026-11-08).
 * <p>
 * Compila la clase REAL `OsmRasterGrid` (sin OpenGL) y verifica:
 *  1. La ventana cubre el radio de trabajo de la ruta (250 m) en cualquier rumbo.
 *  2. La proporción de la rejilla encaja con la pantalla de referencia (celdas
 *     cuadradas, spec 014 RF-04).
 *  3. La tabla `kindToCell` no pierde ningún tipo OSM y la prioridad es la
 *     declarada (nodo &gt; agua &gt; amenity &gt; tourism &gt; calle &gt; ...).
 *  4. Un POI a 217–239 m del centro SÍ entra en el raster (antes no entraba).
 *  5. Un POI fuera de la ventana NO se pinta (nada inventado).
 *  6. Las vías se interpolan y los nodos de ruta tienen prioridad máxima.
 */
public class RasterGridHostTest {

    private static int checks = 0;

    private static final double LAT = 19.5628067;
    private static final double LON = -96.9179283;

    public static void main(String[] args) {
        coverageFitsWorkingRadius();
        gridAspectMatchesScreen();
        kindToCellTable();
        priorityOrder();
        emptyRasterIsEmpty();
        poiInsideWindow();
        poiOutsideWindow();
        verticalCoverage();
        roadInterpolation();
        priorityOnSameCell();
        routeNodes();
        determinism();
        System.out.println("RASTER GRID HOST TEST OK (" + checks + " comprobaciones)");
    }

    /** La ventana debe cubrir el radio de 250 m del ancla a cualquier rumbo. */
    private static void coverageFitsWorkingRadius() {
        check("ancho de rejilla >= 500 m", OsmRasterGrid.widthM() >= 500.0);
        check("alto de rejilla >= 500 m", OsmRasterGrid.heightM() >= 500.0);
        check("medio ancho >= radio de trabajo (250 m)",
                OsmRasterGrid.widthM() / 2.0 >= 250.0);
        check("medio alto >= radio de trabajo (250 m)",
                OsmRasterGrid.heightM() / 2.0 >= 250.0);
        check("rejilla de 63x139 celdas", OsmRasterGrid.COLS * OsmRasterGrid.ROWS
                == 63 * 139);
        check("celda de 8 m (metatile, spec 004 §5.2)",
                Math.abs(OsmRasterGrid.METERS_PER_CELL - 8.0) < 1e-9);
    }

    /** 63x139 debe encajar con 1220x2712 (celdas cuadradas en pantalla). */
    private static void gridAspectMatchesScreen() {
        double grid = (double) OsmRasterGrid.COLS / OsmRasterGrid.ROWS;
        double screen = 1220.0 / 2712.0;
        check("proporción rejilla ≈ pantalla (celdas cuadradas)",
                Math.abs(grid - screen) < 0.01);
    }

    private static void kindToCellTable() {
        check("HIGHWAY→ROAD", OsmRasterGrid.kindToCell(Kind.HIGHWAY) == OsmRasterGrid.ROAD);
        check("PARK→PARK", OsmRasterGrid.kindToCell(Kind.PARK) == OsmRasterGrid.PARK);
        check("WOOD→PARK", OsmRasterGrid.kindToCell(Kind.WOOD) == OsmRasterGrid.PARK);
        check("BUILDING→BUILDING",
                OsmRasterGrid.kindToCell(Kind.BUILDING) == OsmRasterGrid.BUILDING);
        check("WATER→WATER", OsmRasterGrid.kindToCell(Kind.WATER) == OsmRasterGrid.WATER);
        check("SHOP→SHOP", OsmRasterGrid.kindToCell(Kind.SHOP) == OsmRasterGrid.SHOP);
        check("TOURISM→TOURISM",
                OsmRasterGrid.kindToCell(Kind.TOURISM) == OsmRasterGrid.TOURISM);
        check("AMENITY→AMENITY",
                OsmRasterGrid.kindToCell(Kind.AMENITY) == OsmRasterGrid.AMENITY);
        check("null→EMPTY", OsmRasterGrid.kindToCell(null) == OsmRasterGrid.EMPTY);
    }

    private static void priorityOrder() {
        check("nodo_ruta > agua",
                OsmRasterGrid.priority(OsmRasterGrid.NODE)
                        > OsmRasterGrid.priority(OsmRasterGrid.WATER));
        check("agua > amenity",
                OsmRasterGrid.priority(OsmRasterGrid.WATER)
                        > OsmRasterGrid.priority(OsmRasterGrid.AMENITY));
        check("amenity > tourism",
                OsmRasterGrid.priority(OsmRasterGrid.AMENITY)
                        > OsmRasterGrid.priority(OsmRasterGrid.TOURISM));
        check("tourism > calle",
                OsmRasterGrid.priority(OsmRasterGrid.TOURISM)
                        > OsmRasterGrid.priority(OsmRasterGrid.ROAD));
        check("calle > comercio",
                OsmRasterGrid.priority(OsmRasterGrid.ROAD)
                        > OsmRasterGrid.priority(OsmRasterGrid.SHOP));
        check("comercio > edificio",
                OsmRasterGrid.priority(OsmRasterGrid.SHOP)
                        > OsmRasterGrid.priority(OsmRasterGrid.BUILDING));
        check("edificio > parque",
                OsmRasterGrid.priority(OsmRasterGrid.BUILDING)
                        > OsmRasterGrid.priority(OsmRasterGrid.PARK));
        check("parque > vacío",
                OsmRasterGrid.priority(OsmRasterGrid.PARK)
                        > OsmRasterGrid.priority(OsmRasterGrid.EMPTY));
    }

    private static void emptyRasterIsEmpty() {
        OsmRasterGrid.Result r = OsmRasterGrid.rasterize(null, LAT, LON, null);
        check("sin datos: 0 celdas pintadas", r.painted == 0);
        check("sin datos: todo vacío",
                r.count(OsmRasterGrid.EMPTY) == OsmRasterGrid.COLS * OsmRasterGrid.ROWS);
        check("histograma suma la rejilla",
                sum(r.histogram) == OsmRasterGrid.COLS * OsmRasterGrid.ROWS);
    }

    /** Los POIs reales del ancla están a 217 m y 239 m: deben caer dentro. */
    private static void poiInsideWindow() {
        OsmElement shop = node(Kind.SHOP, east(239.0));
        OsmElement amenity = node(Kind.AMENITY, north(217.0));
        OsmRasterGrid.Result r = OsmRasterGrid.rasterize(
                Arrays.asList(shop, amenity), LAT, LON, null);
        check("POI a 239 m al este se pinta", r.count(OsmRasterGrid.SHOP) == 1);
        check("POI a 217 m al norte se pinta",
                r.count(OsmRasterGrid.AMENITY) == 1);
        check("2 POIs → 2 celdas pintadas", r.painted == 2);
    }

    private static void poiOutsideWindow() {
        OsmElement far = node(Kind.BUILDING, east(300.0));
        OsmRasterGrid.Result r = OsmRasterGrid.rasterize(
                Arrays.asList(far), LAT, LON, null);
        check("POI a 300 m al este queda fuera de la ventana", r.painted == 0);
        check("nada inventado al recortar", r.count(OsmRasterGrid.BUILDING) == 0);
    }

    /** El eje largo sí debe cubrir 500 m: un POI a 500 m al sur se pinta. */
    private static void verticalCoverage() {
        OsmElement south = node(Kind.TOURISM, north(-500.0));
        OsmRasterGrid.Result r = OsmRasterGrid.rasterize(
                Arrays.asList(south), LAT, LON, null);
        check("POI a 500 m al sur se pinta", r.count(OsmRasterGrid.TOURISM) == 1);
        check("POI a 600 m al sur queda fuera",
                OsmRasterGrid.rasterize(Arrays.asList(node(Kind.TOURISM, north(-600.0))),
                        LAT, LON, null).painted == 0);
    }

    private static void roadInterpolation() {
        double[] lats = {north(100.0)[0], north(200.0)[0]};
        double[] lons = {north(100.0)[1], north(200.0)[1]};
        OsmElement way = new OsmElement(Kind.HIGHWAY, lats, lons, 1L, "way");
        OsmRasterGrid.Result r = OsmRasterGrid.rasterize(
                Arrays.asList(way), LAT, LON, null);
        int road = r.count(OsmRasterGrid.ROAD);
        check("vía de 100 m se interpola en varias celdas", road >= 10 && road <= 20);
        check("vía interpolada no inventa otros tipos",
                r.painted == road);
    }

    /** Una calle y un POI en la misma celda: gana el POI (prioridad). */
    private static void priorityOnSameCell() {
        OsmElement road = node(Kind.HIGHWAY, north(120.0));
        OsmElement amenity = node(Kind.AMENITY, north(120.0));
        OsmRasterGrid.Result r = OsmRasterGrid.rasterize(
                Arrays.asList(road, amenity), LAT, LON, null);
        check("AMENITY tapa a ROAD en la misma celda",
                r.count(OsmRasterGrid.AMENITY) == 1 && r.count(OsmRasterGrid.ROAD) == 0);
    }

    private static void routeNodes() {
        List<RouteNode> nodes = new ArrayList<RouteNode>();
        for (int i = 1; i <= 30; i++) {
            double[] p = north(i * 8.0);
            nodes.add(RouteNode.at(i, RouteNode.Action.WALK, "synthetic",
                    p[0], p[1], 15.0));
        }
        OsmRasterGrid.Result r = OsmRasterGrid.rasterize(null, LAT, LON, nodes);
        check("30 nodos de ruta se estampan", r.routeStamped == 30);
        check("nodos de ruta pintan celdas", r.count(OsmRasterGrid.NODE) >= 20);
        check("nodo de ruta tapa la calle",
                OsmRasterGrid.rasterize(Arrays.asList(node(Kind.HIGHWAY, north(8.0))),
                        LAT, LON, nodes).count(OsmRasterGrid.NODE) >= 20);
        List<RouteNode> outside = Arrays.asList(RouteNode.at(1, RouteNode.Action.WALK,
                "synthetic", east(300.0)[0], east(300.0)[1], 15.0));
        OsmRasterGrid.Result o = OsmRasterGrid.rasterize(null, LAT, LON, outside);
        check("nodo fuera de ventana no pinta", o.count(OsmRasterGrid.NODE) == 0);
        check("pero cuenta como estampado", o.routeStamped == 1);
    }

    private static void determinism() {
        List<OsmElement> els = Arrays.asList(
                node(Kind.SHOP, east(239.0)),
                node(Kind.AMENITY, north(217.0)));
        byte[] a = OsmRasterGrid.rasterize(els, LAT, LON, null).cells;
        byte[] b = OsmRasterGrid.rasterize(els, LAT, LON, null).cells;
        check("mismo dato → misma rejilla", Arrays.equals(a, b));
    }

    private static OsmElement node(Kind kind, double[] p) {
        return new OsmElement(kind, new double[] {p[0]}, new double[] {p[1]}, 1L, "node");
    }

    private static double[] north(double meters) {
        return new double[] {LAT + meters / 111320.0, LON};
    }

    private static double[] east(double meters) {
        return new double[] {LAT, LON + meters / (111320.0 * Math.cos(Math.toRadians(LAT)))};
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) total += v;
        return total;
    }

    private static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  OK   " : "  FALLO") + "  " + name);
        if (!ok) throw new AssertionError("Fallo: " + name);
    }
}
