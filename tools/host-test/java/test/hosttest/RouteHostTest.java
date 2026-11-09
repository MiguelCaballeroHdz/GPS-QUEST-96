package hosttest;

import com.gpsquest96.gps.OsmAccumulator;
import com.gpsquest96.gps.OsmDataFetcher.Kind;
import com.gpsquest96.gps.OsmDataFetcher.OsmElement;
import com.gpsquest96.route.Route;
import com.gpsquest96.route.RouteFormatException;
import com.gpsquest96.route.RouteGenerator;
import com.gpsquest96.route.RouteNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host test de spec 004 slice 2 (y esquema del slice 0).
 * <p>
 * Sin Android, sin red y sin POCO: compila las clases reales de `com.gpsquest96.route`
 * y `com.gpsquest96.gps`.
 * <p>
 * Cubre:
 *  1. Criterio 1: dos POIs de categoría distinta → dos tipos de nodo distintos.
 *  2. Criterio 2: mismo perfil + misma ancla → misma ruta (JSON idéntico).
 *  3. Criterio 13 (proxy host): farmacia → nodo `enter` por afinidad AMENITY.
 *  4. Nunca un nodo sobre agua; el agua no se convierte en nodo.
 *  5. Respaldo sintético a 8 m/metatile.
 *  6. Esquema: versión, rom_key, acciones, índices, blocked sin unlock.
 *  7. Acumulación OSM sin duplicados (arreglo del "salto de mapa").
 */
public class RouteHostTest {

    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        schemaRoundTrip();
        schemaRejectsInvalid();
        affinityAndCriterion13();
        determinism();
        neverWater();
        syntheticFallback();
        poiRadiusBound();
        romKey();
        accumulator();
        System.out.println("ROUTE HOST TEST OK (" + checks + " comprobaciones)");
    }

    private static void schemaRoundTrip() throws Exception {
        Route r = RouteGenerator.generate("TEST_ROM_1-v1", 19.5275, -96.9242,
                8.0, 1700000000000L, pois(), 4);
        String json = r.toJson();
        check("JSON con schema_version 1", json.contains("\"schema_version\":1"));
        check("JSON con anchor", json.contains("\"anchor\":"));
        Route back = Route.fromJson(json);
        check("round-trip rom_key", "TEST_ROM_1-v1".equals(back.romKey));
        check("round-trip nodos", back.nodes.size() == r.nodes.size());
        check("round-trip JSON idéntico", r.toJson().equals(back.toJson()));
        check("ancla conserva precisión", Math.abs(back.anchorAccuracyM - 8.0) < 1e-6);
    }

    private static void schemaRejectsInvalid() {
        String valid = RouteGenerator.generate("TEST_ROM_1-v1", 19.5275, -96.9242,
                8.0, 1L, pois(), 3).toJson();

        expectReject("versión no soportada",
                valid.replace("\"schema_version\":1", "\"schema_version\":2"), "versión");
        expectReject("falta rom_key",
                valid.replace("\"rom_key\":\"TEST_ROM_1-v1\",", ""), "rom_key");
        expectReject("blocked sin predicado",
                valid.replace("\"blocked\":false,\"unlock\":null",
                        "\"blocked\":true,\"unlock\":null"), "blocked sin predicado");
        expectReject("índice fuera de secuencia",
                valid.replace("\"index\":1,", "\"index\":7,"), "índice");
        expectReject("acción desconocida",
                valid.replaceFirst("\"action\":\"[a-z]+\"", "\"action\":\"dance\""),
                "acción");
        expectReject("JSON roto", "{no es json", "JSON inválido");
    }

    private static void affinityAndCriterion13() {
        check("AMENITY (farmacia) → enter", RouteGenerator.actionFor(Kind.AMENITY)
                == RouteNode.Action.ENTER);
        check("TOURISM → talk", RouteGenerator.actionFor(Kind.TOURISM)
                == RouteNode.Action.TALK);
        check("SHOP → use", RouteGenerator.actionFor(Kind.SHOP) == RouteNode.Action.USE);
        check("PARK → battle", RouteGenerator.actionFor(Kind.PARK) == RouteNode.Action.BATTLE);
        check("HIGHWAY no es nodo de historia", RouteGenerator.actionFor(Kind.HIGHWAY) == null);
        check("WATER no es nodo de historia", RouteGenerator.actionFor(Kind.WATER) == null);

        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(el(Kind.AMENITY, 19.52755, -96.92420, 101L, "node"));
        els.add(el(Kind.TOURISM, 19.52745, -96.92420, 102L, "node"));
        Route r = RouteGenerator.generate("k", 19.5275, -96.9242, 5.0, 1L, els, 4);
        RouteNode amenity = null;
        RouteNode tourism = null;
        for (RouteNode n : r.nodes) {
            if ("AMENITY".equals(n.poi)) amenity = n;
            if ("TOURISM".equals(n.poi)) tourism = n;
        }
        check("farmacia real aparece en la ruta", amenity != null);
        check("farmacia → nodo enter (punto de curación)", amenity != null
                && amenity.action == RouteNode.Action.ENTER);
        check("monumento → nodo talk", tourism != null
                && tourism.action == RouteNode.Action.TALK);
        check("dos categorías → dos tipos de nodo",
                amenity != null && tourism != null && amenity.action != tourism.action);
    }

    private static void determinism() {
        List<OsmElement> a = pois();
        List<OsmElement> b = new ArrayList<OsmElement>(a);
        Collections.reverse(b);
        Route r1 = RouteGenerator.generate("TEST_ROM_1-v1", 19.5275, -96.9242,
                6.0, 42L, a, 12);
        Route r2 = RouteGenerator.generate("TEST_ROM_1-v1", 19.5275, -96.9242,
                6.0, 42L, b, 12);
        check("misma entrada → mismo JSON", r1.toJson().equals(r2.toJson()));
        check("orden de llegada no altera la ruta",
                r1.nodes.size() == r2.nodes.size());
        Route r3 = RouteGenerator.generate("TEST_ROM_1-v1", 19.5275, -96.9242,
                6.0, 42L, a, 12);
        check("dos arranques → misma ruta", r1.toJson().equals(r3.toJson()));
    }

    private static void neverWater() {
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(waterRing(19.5275, -96.9242));
        els.add(el(Kind.AMENITY, 19.52840, -96.92420, 200L, "node")); // fuera del agua
        Route r = RouteGenerator.generate("k", 19.5275, -96.9242, 5.0, 1L, els, 10);
        boolean anyOverWater = false;
        boolean waterNode = false;
        boolean pharmacy = false;
        for (RouteNode n : r.nodes) {
            if (RouteGenerator.isOverWater(n.lat, n.lon, els)) anyOverWater = true;
            if ("WATER".equals(n.poi)) waterNode = true;
            if ("AMENITY".equals(n.poi)) pharmacy = true;
        }
        check("ningún nodo cae sobre agua", !anyOverWater);
        check("el agua nunca es nodo", !waterNode);
        check("POI fuera del agua sí entra", pharmacy);
        check("ancla sobre agua no genera sintéticos dentro",
                !RouteGenerator.isOverWater(r.nodes.get(0).lat, r.nodes.get(0).lon, els));
    }

    private static void syntheticFallback() {
        Route r = RouteGenerator.generate("k", 19.5275, -96.9242, 5.0, 1L,
                new ArrayList<OsmElement>(), 6);
        check("sin POIs se llenan nodos sintéticos", r.nodes.size() == 6);
        boolean allSynthetic = true;
        for (RouteNode n : r.nodes) {
            if (n.action != RouteNode.Action.WALK || !"synthetic".equals(n.poi)) {
                allSynthetic = false;
            }
        }
        check("todos son walk/synthetic", allSynthetic);
        double firstStep = RouteGenerator.haversineM(19.5275, -96.9242,
                r.nodes.get(0).lat, r.nodes.get(0).lon);
        check("primer paso a 8 m (metatile)", Math.abs(firstStep - 8.0) < 0.5);
    }

    /**
     * Defecto encontrado en POCO (2026-11-07): la unión acumulada de celdas metía
     * POIs de Xalapa Centro a 4 km del ancla y la ruta quedaba impracticable.
     * El radio de trabajo debe excluirlos y rellenar con sintéticos cercanos.
     */
    private static void poiRadiusBound() {
        double anchorLat = 19.562820;
        double anchorLon = -96.917910;
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(el(Kind.AMENITY, anchorLat + 0.00090, anchorLon, 301L, "node")); // ~100 m
        els.add(el(Kind.SHOP, 19.5275, -96.9242, 302L, "node"));                 // ~4 km
        Route r = RouteGenerator.generate("k", anchorLat, anchorLon, 5.0, 1L, els, 12);
        int poi = 0;
        boolean farNode = false;
        for (RouteNode n : r.nodes) {
            if (!"synthetic".equals(n.poi)) poi++;
            if (RouteGenerator.haversineM(anchorLat, anchorLon, n.lat, n.lon)
                    > RouteGenerator.MAX_POI_RADIUS_M + 0.5) {
                farNode = true;
            }
        }
        check("POI a 4 km queda excluido", poi == 1);
        check("ningún nodo fuera del radio de trabajo", !farNode);
        check("la ruta se rellena hasta el objetivo", r.nodes.size() == 12);

        Route full = RouteGenerator.generate("k", anchorLat, anchorLon, 5.0, 1L,
                new ArrayList<OsmElement>(), 180);
        boolean allWithin = true;
        for (RouteNode n : full.nodes) {
            if (RouteGenerator.haversineM(anchorLat, anchorLon, n.lat, n.lon)
                    > RouteGenerator.MAX_SYNTHETIC_RADIUS_M + 0.5) {
                allWithin = false;
            }
        }
        check("ruta de 180 nodos (spec 004 §5.2)", full.nodes.size() == 180);
        check("180 nodos dentro de 250 m del ancla", allWithin);
    }

    private static void romKey() {
        byte[] rom = new byte[0x150];
        byte[] title = "TEST_ROM_1".getBytes();
        System.arraycopy(title, 0, rom, 0x134, title.length);
        rom[0x14C] = 1;
        check("rom_key desde cabecera", "TEST_ROM_1-v1".equals(
                RouteGenerator.romKeyFromHeader(rom)));
        boolean rejected = false;
        try {
            RouteGenerator.generate("k", 19.5, -96.9, 25.0, 1L, pois(), 4);
        } catch (IllegalArgumentException e) {
            rejected = e.getMessage().contains("precisión");
        }
        check("ancla > 20 m rechazada", rejected);
    }

    private static void accumulator() {
        List<OsmElement> base = Arrays.asList(
                el(Kind.SHOP, 19.5, -96.9, 1L, "node"),
                el(Kind.PARK, 19.5001, -96.9, 2L, "way"));
        List<OsmElement> incoming = Arrays.asList(
                el(Kind.SHOP, 19.5, -96.9, 1L, "node"),          // duplicado por id
                el(Kind.AMENITY, 19.5002, -96.9, 3L, "node"),
                el(Kind.SHOP, 19.5, -96.9, 0L, "node"));          // sin id
        List<OsmElement> merged = OsmAccumulator.merge(base, incoming, 100);
        check("unión deduplica por id", merged.size() == 4);
        check("la unión crece", merged.size() > base.size());
        List<OsmElement> noIdsA = Arrays.asList(el(Kind.SHOP, 19.5, -96.9, 0L, "node"));
        List<OsmElement> noIdsB = Arrays.asList(el(Kind.SHOP, 19.5, -96.9, 0L, "node"));
        check("sin id deduplica por coordenada",
                OsmAccumulator.merge(noIdsA, noIdsB, 100).size() == 1);
        check("tope descarta los más antiguos",
                OsmAccumulator.merge(base, incoming, 2).size() == 2);
        check("nulo no rompe la unión",
                OsmAccumulator.merge(null, null, 100).isEmpty());

        List<OsmElement> far = Arrays.asList(
                el(Kind.SHOP, 19.5, -96.9, 20L, "node"),
                el(Kind.PARK, 19.6, -96.9, 21L, "node"));
        List<OsmElement> pruned = OsmAccumulator.merge(far, null, 100,
                19.5, -96.9, OsmAccumulator.KEEP_RADIUS_M);
        check("poda descarta el elemento a 11 km",
                pruned.size() == 1 && pruned.get(0).id == 20);
        OsmElement longWay = new OsmElement(Kind.HIGHWAY,
                new double[] {19.5, 19.6}, new double[] {-96.9, -96.9}, 30L, "way");
        List<OsmElement> prunedWay = OsmAccumulator.merge(
                Arrays.asList(longWay), null, 100,
                19.5, -96.9, OsmAccumulator.KEEP_RADIUS_M);
        check("poda conserva vía con un punto dentro", prunedWay.size() == 1);
        check("sin centro no poda",
                OsmAccumulator.merge(far, null, 100).size() == 2);
    }

    // ── utilidades ──────────────────────────────────────────────────────

    private static List<OsmElement> pois() {
        List<OsmElement> els = new ArrayList<OsmElement>();
        els.add(el(Kind.AMENITY, 19.52760, -96.92415, 11L, "node"));
        els.add(el(Kind.TOURISM, 19.52740, -96.92430, 12L, "node"));
        els.add(el(Kind.SHOP, 19.52765, -96.92435, 13L, "node"));
        els.add(el(Kind.PARK, 19.52730, -96.92405, 14L, "way"));
        return els;
    }

    private static OsmElement el(Kind kind, double lat, double lon, long id, String type) {
        return new OsmElement(kind, new double[] {lat}, new double[] {lon}, id, type);
    }

    /** Anillo cuadrado de ~44x42 m alrededor del punto. */
    private static OsmElement waterRing(double lat, double lon) {
        double d = 0.00020;
        return new OsmElement(Kind.WATER,
                new double[] {lat - d, lat + d, lat + d, lat - d, lat - d},
                new double[] {lon - d, lon - d, lon + d, lon + d, lon - d},
                900L, "way");
    }

    private static void expectReject(String name, String json, String needle) {
        try {
            Route.fromJson(json);
            check(name + " (NO rechazó)", false);
        } catch (RouteFormatException e) {
            check(name, e.getMessage() != null && e.getMessage().contains(needle));
        }
    }

    private static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  OK   " : "  FALLO") + "  " + name);
        if (!ok) throw new AssertionError("Fallo: " + name);
    }
}
