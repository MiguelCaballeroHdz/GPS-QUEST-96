package com.gpsquest96.gps;

/**
 * Matemática slippy-map para tiles raster OSM (spec 017 slice 2).
 * <p>
 * Java puro, sin Android: se verifica con host tests sin emulador ni POCO.
 * <p>
 * Referencia vieja: el `TileQueueManager` de oct 2026 tenía su propio
 * `tileFor()` slippy-map; el archivo se borró el 9 oct 2026 ("adiós OSM") y
 * aquí se reimplementa desde cero con la misma semántica (endpoint
 * `tile.openstreetmap.org`, rejilla 3x3, zoom 16 base). La lección de
 * entonces (dos capas base a la vez) no se repite: esta clase solo resuelve
 * números; la decisión de qué capa se dibuja vive en `OsmTileLayer` y en
 * `GameRenderer` (una sola capa base por frame, nunca tiles + raster).
 */
public final class TileMath {

    /** Lado de una tesela en píxeles (estándar slippy-map). */
    public static final int TILE_PX = 256;

    /** Latitud máxima del mapa Mercator (los polos no existen en slippy). */
    public static final double MAX_LAT = 85.05112878;

    /** Nivel de zoom del mapa: UI 1.0x → z16 (rejilla 3x3 cubre ~1,7 km). */
    public static final int BASE_Z = 16;
    /** Zoom mínimo (UI 0.5x) y máximo (UI 4.0x) permitidos. */
    public static final int MIN_Z = 15;
    public static final int MAX_Z = 18;

    /** TTL de caché en disco, igual que `OsmDataFetcher` (7 días). */
    public static final long DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Radio de la rejilla de teselas alrededor del jugador (1 = 3x3). */
    public static final int GRID_RADIUS = 1;

    private TileMath() {
    }

    /**
     * Columna de tesela para una longitud (0 .. 2^z - 1, con vuelta al
     * antimeridiano: lon 180 cae en x=0).
     */
    public static int tileX(double lon, int z) {
        int n = 1 << z;
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        return ((x % n) + n) % n;
    }

    /**
     * Fila de tesela para una latitud (0 .. 2^z - 1). La latitud se recorta
     * a ±{@link #MAX_LAT}: fuera de ahí el Mercator no está definido y la
     * fila se fijaría fuera de rango.
     */
    public static int tileY(double lat, int z) {
        int n = 1 << z;
        double clamped = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        double latRad = Math.toRadians(clamped);
        double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad))
                / Math.PI) / 2.0 * n;
        int yi = (int) Math.floor(y);
        if (yi < 0) return 0;
        if (yi > n - 1) return n - 1;
        return yi;
    }

    /** Longitud oeste (borde izquierdo) de la tesela x en el zoom z. */
    public static double lonWest(int x, int z) {
        return x / (double) (1 << z) * 360.0 - 180.0;
    }

    /** Latitud norte (borde superior) de la tesela y en el zoom z. */
    public static double latNorth(int y, int z) {
        int n = 1 << z;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n))));
    }

    /** Ancho de una tesela en metros a esa latitud y zoom (Web Mercator). */
    public static double tileWidthM(double lat, int z) {
        return 156543.03392 * Math.cos(Math.toRadians(lat)) / (1 << z) * TILE_PX;
    }

    /**
     * Nivel de zoom de tesela para el zoom UI del mapa (0.5x–4.0x, pasos de
     * 0.5). Cada duplicación de zoom UI sube un nivel z, como en el mapa
     * viejo (zoom 16 a 1.0x). Fuera de rango se recorta a [{@link #MIN_Z},
     * {@link #MAX_Z}].
     */
    public static int zoomForUiZoom(float uiZoom) {
        if (!(uiZoom > 0f) || Float.isNaN(uiZoom) || Float.isInfinite(uiZoom)) {
            return BASE_Z;
        }
        int z = BASE_Z + (int) Math.round(Math.log(uiZoom) / Math.log(2.0));
        if (z < MIN_Z) return MIN_Z;
        if (z > MAX_Z) return MAX_Z;
        return z;
    }

    /** Clave de caché y de RAM para una tesela, igual que en disco. */
    public static String cacheKey(int z, int x, int y) {
        return z + "_" + x + "_" + y;
    }

    /**
     * ¿Caducó el fichero en disco? Misma semántica que `OsmDataFetcher`:
     * un mtime &le; 0 es edad desconocida (fichero restaurado/copiado sin
     * tiempo) y NO se descarta por edad.
     */
    public static boolean isExpired(long mtimeMs, long nowMs, long ttlMs) {
        if (mtimeMs <= 0) return false;
        return nowMs - mtimeMs > ttlMs;
    }

    /**
     * Rejilla (2·radio+1)² de teselas centrada en la que contiene el punto.
     * Devuelve pares {x, y} en orden de filas (norte a sur, oeste a este).
     * La x da la vuelta al antimeridiano; la y se recorta a [0, 2^z - 1]
     * (en los polos no hay más teselas).
     */
    public static int[][] tilesForCenter(double lat, double lon, int z, int radius) {
        int n = 1 << z;
        int cx = tileX(lon, z);
        int cy = tileY(lat, z);
        int size = 2 * radius + 1;
        int[][] out = new int[size * size][2];
        int i = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            int y = cy + dy;
            if (y < 0) y = 0;
            if (y > n - 1) y = n - 1;
            for (int dx = -radius; dx <= radius; dx++) {
                int x = ((cx + dx) % n + n) % n;
                out[i][0] = x;
                out[i][1] = y;
                i++;
            }
        }
        return out;
    }

    /**
     * ¿La cobertura de teselas (marco f) cubre entera la vista (marco v)?
     * Norte/sur en latitud, oeste/este en longitud. Tocar el borde cuenta
     * como cubrir: la vista no se sale del marco.
     */
    public static boolean covers(double fNorth, double fSouth, double fWest, double fEast,
            double vNorth, double vSouth, double vWest, double vEast) {
        return fNorth >= vNorth && fSouth <= vSouth
                && fWest <= vWest && fEast >= vEast;
    }

    /**
     * Mitad del viewport en metros, con la misma escala que el raster
     * (celda de 8 m → cellPx en pantalla). Es la mitad de lo que se ve a
     * cada lado del centro; con ella se decide si el marco de teselas
     * cubre la vista o hay que caer al raster de respaldo.
     *
     * @return {mitadAnchoM, mitadAltoM}
     */
    public static double[] halfSpanM(int surfW, int surfH, float cellPx) {
        if (cellPx <= 0f) return new double[] {0.0, 0.0};
        double metersPerPx = 8.0 / cellPx;
        return new double[] {(surfW / 2.0) * metersPerPx, (surfH / 2.0) * metersPerPx};
    }
}
