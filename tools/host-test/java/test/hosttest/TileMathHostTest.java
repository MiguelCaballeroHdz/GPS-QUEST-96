package hosttest;

import com.gpsquest96.gps.TileMath;

/**
 * Host test de spec 017 slice 2 (capa de tiles OSM).
 * <p>
 * Sin Android, sin red y sin POCO: compila la clase real
 * {@code com.gpsquest96.gps.TileMath} (Java puro). La descarga real, la
 * caché de disco y la subida GL quedan para PC/POCO.
 * <p>
 * Cubre:
 *  1. Conversión lat/lon ↔ tesela (valores conocidos, bordes, polos,
 *     antimeridiano) e inversas.
 *  2. Ancho de tesela en metros (Web Mercator).
 *  3. Correspondencia zoom UI → nivel z (0.5x–4.0x, con recorte).
 *  4. Clave de caché `z_x_y` y expiración TTL (misma semántica que
 *     `OsmDataFetcher`: mtime desconocido no caduca).
 *  5. Rejilla 3x3 alrededor del punto (centro, bordes, vuelta en x,
 *     recorte en y).
 *  6. Cobertura marco/vista (la garantía de capa única: el marco cubre
 *     la vista o toca respaldo, nunca a medias).
 *  7. Mitad del viewport en metros (misma escala que el raster, 8 m/celda).
 */
public class TileMathHostTest {

    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        tileXY();
        inverses();
        tileWidthM();
        zoomForUiZoom();
        cacheKeyAndTtl();
        tilesForCenter();
        covers();
        halfSpanM();
        System.out.println("TILE MATH HOST TEST OK (" + checks + " comprobaciones)");
    }

    private static void check(boolean cond, String name) {
        checks++;
        if (!cond) throw new AssertionError("FALLO: " + name);
        System.out.println("  OK     " + name);
    }

    private static void near(double got, double want, double tol, String name) {
        check(Math.abs(got - want) <= tol, name + " (fue " + got + ", esperado " + want + ")");
    }

    private static void tileXY() {
        check(TileMath.tileX(0.0, 0) == 0, "origen z0 x=0");
        check(TileMath.tileY(0.0, 0) == 0, "origen z0 y=0");
        // Xalapa Centro (19.5275, -96.9242), verificado con el cálculo de
        // referencia en Python (misma fórmula slippy-map).
        check(TileMath.tileX(-96.9242, 16) == 15123, "Xalapa z16 x=15123");
        check(TileMath.tileY(19.5275, 16) == 29142, "Xalapa z16 y=29142");
        check(TileMath.tileX(-96.9242, 15) == 7561, "Xalapa z15 x=7561");
        check(TileMath.tileY(19.5275, 15) == 14571, "Xalapa z15 y=14571");
        check(TileMath.tileX(-96.9242, 17) == 30246, "Xalapa z17 x=30246");
        check(TileMath.tileY(19.5275, 17) == 58284, "Xalapa z17 y=58284");
        check(TileMath.tileX(-96.9242, 18) == 60493, "Xalapa z18 x=60493");
        check(TileMath.tileY(19.5275, 18) == 116568, "Xalapa z18 y=116568");
        // Antimeridiano: lon 180 da la vuelta a x=0.
        check(TileMath.tileX(180.0, 2) == 0, "lon 180 z2 x=0 (vuelta)");
        check(TileMath.tileX(-180.0, 2) == 0, "lon -180 z2 x=0");
        check(TileMath.tileX(179.999, 2) == 3, "lon 179.999 z2 x=3");
        // Polos: la latitud se recorta a +-85.0511 y la fila queda en rango.
        check(TileMath.tileY(90.0, 2) == 0, "lat 90 z2 y=0 (recorte norte)");
        check(TileMath.tileY(-90.0, 2) == 3, "lat -90 z2 y=3 (recorte sur)");
        check(TileMath.tileY(85.05112878, 2) == 0, "lat maxima z2 y=0");
        check(TileMath.tileY(-85.05112878, 2) == 3, "lat minima z2 y=3");
    }

    private static void inverses() {
        // La inversa acota el punto: oeste <= lon < oeste de la siguiente.
        double[][] points = {
                {19.5275, -96.9242}, {0.0, 0.0}, {51.5074, -0.1278}, {-33.8688, 151.2093}
        };
        for (double[] p : points) {
            for (int z = 0; z <= 18; z += 6) {
                int x = TileMath.tileX(p[1], z);
                int y = TileMath.tileY(p[0], z);
                double w = TileMath.lonWest(x, z);
                double e = TileMath.lonWest(x + 1, z);
                check(w <= p[1] && p[1] < e,
                        "lon dentro de su tesela z" + z + " (" + p[1] + ")");
                double n = TileMath.latNorth(y, z);
                double s = TileMath.latNorth(y + 1, z);
                check(s < p[0] && p[0] <= n,
                        "lat dentro de su tesela z" + z + " (" + p[0] + ")");
            }
        }
    }

    private static void tileWidthM() {
        // Xalapa z16: ~576 m por tesela (156543.03392 * cos(lat) / 2^z * 256).
        near(TileMath.tileWidthM(19.5275, 16), 576.3, 1.0, "ancho tesela Xalapa z16");
        // Cada nivel menos duplica el ancho.
        near(TileMath.tileWidthM(19.5275, 15), 1152.6, 2.0, "ancho tesela Xalapa z15");
        near(TileMath.tileWidthM(19.5275, 17), 288.2, 1.0, "ancho tesela Xalapa z17");
        // Ecuador z16: ~611,5 m.
        near(TileMath.tileWidthM(0.0, 16), 611.5, 1.0, "ancho tesela ecuador z16");
    }

    private static void zoomForUiZoom() {
        check(TileMath.zoomForUiZoom(1.0f) == 16, "zoom UI 1.0x -> z16");
        check(TileMath.zoomForUiZoom(0.5f) == 15, "zoom UI 0.5x -> z15");
        check(TileMath.zoomForUiZoom(2.0f) == 17, "zoom UI 2.0x -> z17");
        check(TileMath.zoomForUiZoom(4.0f) == 18, "zoom UI 4.0x -> z18");
        check(TileMath.zoomForUiZoom(1.5f) == 17, "zoom UI 1.5x -> z17");
        check(TileMath.zoomForUiZoom(3.0f) == 18, "zoom UI 3.0x -> z18");
        check(TileMath.zoomForUiZoom(0.25f) == 15, "zoom UI 0.25x -> z15 (recorte)");
        check(TileMath.zoomForUiZoom(8.0f) == 18, "zoom UI 8.0x -> z18 (recorte)");
        check(TileMath.zoomForUiZoom(0.0f) == 16, "zoom UI 0 -> z16 (defecto)");
        check(TileMath.zoomForUiZoom(-1.0f) == 16, "zoom UI negativo -> z16");
        check(TileMath.zoomForUiZoom(Float.NaN) == 16, "zoom UI NaN -> z16");
    }

    private static void cacheKeyAndTtl() {
        check("16_15123_29142".equals(TileMath.cacheKey(16, 15123, 29142)),
                "clave de cache z_x_y");
        check("0_0_0".equals(TileMath.cacheKey(0, 0, 0)), "clave de cache z0");
        long ttl = TileMath.DISK_TTL_MS;
        long now = 1800000000000L;
        check(!TileMath.isExpired(0L, now, ttl), "mtime 0 no caduca");
        check(!TileMath.isExpired(-5L, now, ttl), "mtime negativo no caduca");
        check(!TileMath.isExpired(now - 1000L, now, ttl), "fichero fresco no caduca");
        check(!TileMath.isExpired(now - ttl, now, ttl), "justo en TTL no caduca");
        check(TileMath.isExpired(now - ttl - 1L, now, ttl), "TTL+1ms caduca");
        check(TileMath.isExpired(now - 8L * 24 * 60 * 60 * 1000L, now, ttl),
                "8 dias caduca (TTL 7 dias)");
    }

    private static void tilesForCenter() {
        int[][] g = TileMath.tilesForCenter(19.5275, -96.9242, 16, 1);
        check(g.length == 9, "rejilla 3x3 = 9 teselas");
        check(g[4][0] == 15123 && g[4][1] == 29142, "centro de la rejilla = Xalapa");
        check(g[0][0] == 15122 && g[0][1] == 29141, "esquina NO de la rejilla");
        check(g[8][0] == 15124 && g[8][1] == 29143, "esquina SE de la rejilla");
        check(g[1][0] == 15123 && g[1][1] == 29141, "norte de la rejilla");
        check(g[3][0] == 15122 && g[3][1] == 29142, "oeste de la rejilla");
        // Antimeridiano z2: el centro es x=3 y la columna este da la vuelta a 0.
        int[][] a = TileMath.tilesForCenter(0.0, 179.999, 2, 1);
        check(a[4][0] == 3, "antimeridiano centro x=3");
        check(a[5][0] == 0, "antimeridiano este x=0 (vuelta)");
        check(a[3][0] == 2, "antimeridiano oeste x=2");
        // Polo norte z2: todas las filas quedan en [0,3], la de arriba es 0.
        int[][] p = TileMath.tilesForCenter(85.0, 0.0, 2, 1);
        boolean inRange = true;
        for (int[] t : p) inRange &= (t[1] >= 0 && t[1] <= 3);
        check(inRange, "polo norte: filas en rango");
        check(p[0][1] == 0 && p[1][1] == 0 && p[2][1] == 0,
                "polo norte: fila superior recortada a 0");
    }

    private static void covers() {
        // Marco (N20 S19 W-97 E-96) contra vistas dentro y fuera.
        check(TileMath.covers(20.0, 19.0, -97.0, -96.0,
                19.6, 19.4, -96.6, -96.4), "vista dentro del marco cubre");
        check(TileMath.covers(20.0, 19.0, -97.0, -96.0,
                20.0, 19.0, -97.0, -96.0), "vista igual al marco cubre (borde)");
        check(!TileMath.covers(20.0, 19.0, -97.0, -96.0,
                20.1, 19.4, -96.6, -96.4), "vista que sale por el norte no cubre");
        check(!TileMath.covers(20.0, 19.0, -97.0, -96.0,
                19.6, 18.9, -96.6, -96.4), "vista que sale por el sur no cubre");
        check(!TileMath.covers(20.0, 19.0, -97.0, -96.0,
                19.6, 19.4, -97.1, -96.4), "vista que sale por el oeste no cubre");
        check(!TileMath.covers(20.0, 19.0, -97.0, -96.0,
                19.6, 19.4, -96.6, -95.9), "vista que sale por el este no cubre");
        check(!TileMath.covers(20.0, 19.0, -97.0, -96.0,
                21.0, 18.0, -98.0, -95.0), "vista mayor que el marco no cubre");
    }

    private static void halfSpanM() {
        // Pantalla POCO 1220x2712, celda 19,5 px: mitad ~250 m x ~556 m,
        // la misma ventana que el raster (504 x 1112 m).
        double[] s = TileMath.halfSpanM(1220, 2712, 19.5f);
        near(s[0], 250.3, 1.0, "mitad ancho viewport ~250 m");
        near(s[1], 556.3, 1.0, "mitad alto viewport ~556 m");
        double[] z = TileMath.halfSpanM(1220, 2712, 0f);
        check(z[0] == 0.0 && z[1] == 0.0, "celda 0 -> mitad 0");
    }
}
