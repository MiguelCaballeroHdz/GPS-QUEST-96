package com.gpsquest96.render;

import com.gpsquest96.gps.OsmDataFetcher;
import com.gpsquest96.gps.OsmDataFetcher.OsmElement;
import com.gpsquest96.route.RouteNode;

import java.util.List;

/**
 * Raster OSM puro: datos reales → rejilla de celdas tipadas (sin OpenGL).
 * <p>
 * Vive aparte de {@link GBMapRenderer} para poder probarlo en host y para que
 * el hilo GL solo reciba una rejilla ya resuelta. Regla de oro: aquí no se
 * inventa terreno; una celda solo se pinta si un elemento OSM o un nodo de la
 * Ruta Maestra cae dentro de ella.
 * <p>
 * Cobertura 2026-11-08: la ventana anterior (12×27 celdas de 4,4 m = 53×119 m)
 * no alcanzaba los POIs del radio de trabajo de la ruta (250 m), así que el
 * raster salía casi vacío aunque los datos existieran. La rejilla nueva usa
 * celdas de <b>8 m</b> (la escala metatile→metros de spec 004 §5.2) y
 * 63×139 celdas: 504 m de ancho por 1.112 m de alto, suficiente para que todo
 * el radio de 250 m quepa en pantalla en cualquier rumbo.
 */
public final class OsmRasterGrid {

    /** Columnas del raster (eje X, ancho). */
    public static final int COLS = 63;
    /** Filas del raster (eje Y, alto). */
    public static final int ROWS = 139;
    /** Lado de celda en metros: 1 celda = 1 metatile (spec 004 §5.2). */
    public static final double METERS_PER_CELL = 8.0;

    /** Tipos de celda (la prioridad se resuelve en {@link #stamp}). */
    public static final byte EMPTY = 0;   // ninguna etiqueta OSM aquí
    public static final byte PARK = 1;    // leisure=park / natural=wood
    public static final byte ROAD = 2;    // highway
    public static final byte BUILDING = 3;// building
    public static final byte SHOP = 4;    // shop
    public static final byte WATER = 5;   // natural=water
    public static final byte AMENITY = 6; // hospital, farmacia, banco… (spec 004 slice 1)
    public static final byte TOURISM = 7; // museo, monumento, hotel…
    public static final byte NODE = 8;    // nodo de la Ruta Maestra (spec 004 slice 2)

    private static final int TYPES = 9;

    private OsmRasterGrid() {
    }

    /** Resultado de rasterizar: rejilla + contadores para el log de evidencia. */
    public static final class Result {
        public final byte[] cells;
        public final int osmNodes;
        public final int routeStamped;
        public final int painted;
        public final int[] histogram;

        Result(byte[] cells, int osmNodes, int routeStamped, int painted,
                int[] histogram) {
            this.cells = cells;
            this.osmNodes = osmNodes;
            this.routeStamped = routeStamped;
            this.painted = painted;
            this.histogram = histogram;
        }

        public int count(byte type) {
            return (type >= 0 && type < histogram.length) ? histogram[type] : 0;
        }
    }

    /** Ancho cubierto por la rejilla en metros (independiente de la latitud). */
    public static double widthM() {
        return COLS * METERS_PER_CELL;
    }

    /** Alto cubierto por la rejilla en metros (independiente de la latitud). */
    public static double heightM() {
        return ROWS * METERS_PER_CELL;
    }

    /**
     * Rasteriza los elementos OSM y los nodos de ruta alrededor de un centro.
     *
     * @param elements   elementos ya recortados por {@code OsmDataFetcher.parse}
     * @param centerLat  latitud del centro del raster (posición del jugador)
     * @param centerLon  longitud del centro del raster
     * @param routeNodes nodos de la Ruta Maestra (puede ser null)
     */
    public static Result rasterize(List<OsmElement> elements,
            double centerLat, double centerLon, List<RouteNode> routeNodes) {
        byte[] grid = new byte[COLS * ROWS];
        double cellH = METERS_PER_CELL / 111320.0;
        double cellW = cellH / Math.cos(Math.toRadians(centerLat));
        int nodeCount = 0;
        if (elements != null) {
            for (OsmElement el : elements) {
                if (el == null) continue;
                byte type = kindToCell(el.kind);
                if (type == EMPTY) continue;
                if (el.lats == null || el.lons == null) continue;
                int n = Math.min(el.lats.length, el.lons.length);
                nodeCount += n;
                for (int i = 0; i < n; i++) {
                    stamp(grid, centerLat, centerLon, cellH, cellW,
                            el.lats[i], el.lons[i], type);
                    if (i > 0) {
                        // Interpola segmentos cada ~1,5 m para vías continuas.
                        double la0 = el.lats[i - 1];
                        double lo0 = el.lons[i - 1];
                        double la1 = el.lats[i];
                        double lo1 = el.lons[i];
                        double distM = haversineM(la0, lo0, la1, lo1);
                        int steps = (int) (distM / 1.5);
                        if (steps > 1024) steps = 1024;
                        for (int s = 1; s < steps; s++) {
                            double f = (double) s / (steps + 1);
                            stamp(grid, centerLat, centerLon, cellH, cellW,
                                    la0 + (la1 - la0) * f, lo0 + (lo1 - lo0) * f, type);
                        }
                    }
                }
            }
        }
        int routeStamped = 0;
        if (routeNodes != null) {
            for (RouteNode n : routeNodes) {
                if (n == null) continue;
                stamp(grid, centerLat, centerLon, cellH, cellW,
                        n.lat, n.lon, NODE);
                routeStamped++;
            }
        }
        int[] hist = new int[TYPES];
        int painted = 0;
        for (byte b : grid) {
            if (b >= 0 && b < TYPES) hist[b]++;
            if (b != EMPTY) painted++;
        }
        return new Result(grid, nodeCount, routeStamped, painted, hist);
    }

    static void stamp(byte[] grid, double centerLat, double centerLon,
            double cellH, double cellW, double lat, double lon, byte type) {
        int col = (int) Math.floor(COLS / 2.0 + (lon - centerLon) / cellW);
        int row = (int) Math.floor(ROWS / 2.0 + (centerLat - lat) / cellH);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return;
        int idx = row * COLS + col;
        // spec 015 B2: una calle no debe quedar sepultada por el contorno del
        // edificio que la bordea (Overpass devuelve `building` como perímetro).
        if (priority(type) > priority(grid[idx])) {
            grid[idx] = type;
        }
    }

    /**
     * Prioridad de pintado por celda (spec 015 B2 + spec 004 slice 2):
     * nodo de ruta 8 &gt; agua 7 &gt; amenity 6 &gt; tourism 5 &gt; calle 4 &gt;
     * comercio 3 &gt; edificio 2 &gt; parque 1 &gt; vacío 0.
     */
    public static int priority(byte type) {
        switch (type) {
            case NODE: return 8;
            case WATER: return 7;
            case AMENITY: return 6;
            case TOURISM: return 5;
            case ROAD: return 4;
            case SHOP: return 3;
            case BUILDING: return 2;
            case PARK: return 1;
            case EMPTY:
            default: return 0;
        }
    }

    /** Tipo OSM → tipo de celda. Es la tabla que usa el raster (spec 004). */
    public static byte kindToCell(OsmDataFetcher.Kind kind) {
        if (kind == null) return EMPTY;
        switch (kind) {
            case WATER: return WATER;
            case BUILDING: return BUILDING;
            case SHOP: return SHOP;
            case HIGHWAY: return ROAD;
            case AMENITY: return AMENITY;
            case TOURISM: return TOURISM;
            case PARK:
            case WOOD: return PARK;
            default: return EMPTY;
        }
    }

    static double haversineM(double la0, double lo0, double la1, double lo1) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(la1 - la0);
        double dLon = Math.toRadians(lo1 - lo0);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la0)) * Math.cos(Math.toRadians(la1))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
