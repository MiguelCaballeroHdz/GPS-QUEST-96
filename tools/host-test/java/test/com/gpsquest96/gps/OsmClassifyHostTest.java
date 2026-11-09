package com.gpsquest96.gps;

import org.json.JSONObject;

import java.util.List;

/**
 * Host test de spec 004 slice 1 — clasificación OSM POI-first.
 * <p>
 * Compila la clase REAL `OsmDataFetcher` contra stubs de Android y el `org.json` de la
 * caché de Gradle. Sin emulador, sin red y sin POCO.
 * <p>
 * Lo que se prueba:
 *  1. Un hospital (`building=yes` + `amenity=hospital`) sale AMENITY, no BUILDING.
 *  2. Un monumento (`historic=monument`) sale TOURISM.
 *  3. Un POI nunca queda tapado por un tag de terreno (shop, highway, building).
 *  4. Cada valor del enum es alcanzable y el enum tiene exactamente 8 valores.
 *  5. `parse()` conserva nodos y vías, y descarta lo que no tiene tipo.
 */
public class OsmClassifyHostTest {

    private static int checks = 0;

    public static void main(String[] args) {
        hospitalIsAmenity();
        monumentIsTourism();
        poiBeatsTerrain();
        terrainFallbacks();
        enumIsMinimalAndComplete();
        parseKeepsNodesAndWays();
        clipGeometry();
        System.out.println("OSM CLASSIFY HOST TEST OK (" + checks + " comprobaciones)");
    }

    /** Criterio 2: hospital con building=yes + amenity=hospital → AMENITY. */
    private static void hospitalIsAmenity() {
        check("hospital → AMENITY", kind("{\"building\":\"yes\",\"amenity\":\"hospital\"}")
                == OsmDataFetcher.Kind.AMENITY);
        check("farmacia (nodo) → AMENITY", kind("{\"amenity\":\"pharmacy\"}")
                == OsmDataFetcher.Kind.AMENITY);
        check("escuela con building → AMENITY", kind("{\"building\":\"school\",\"amenity\":\"school\"}")
                == OsmDataFetcher.Kind.AMENITY);
    }

    /** Criterio 3: monumento (historic=monument) → TOURISM. */
    private static void monumentIsTourism() {
        check("monumento → TOURISM", kind("{\"historic\":\"monument\"}")
                == OsmDataFetcher.Kind.TOURISM);
        check("museo con building → TOURISM", kind("{\"tourism\":\"museum\",\"building\":\"yes\"}")
                == OsmDataFetcher.Kind.TOURISM);
        check("hotel con building → TOURISM", kind("{\"tourism\":\"hotel\",\"building\":\"hotel\"}")
                == OsmDataFetcher.Kind.TOURISM);
    }

    /** Un POI no queda tapado por terreno genérico. */
    private static void poiBeatsTerrain() {
        check("tienda en edificio → SHOP", kind("{\"shop\":\"convenience\",\"building\":\"yes\"}")
                == OsmDataFetcher.Kind.SHOP);
        check("amenity sobre highway → AMENITY",
                kind("{\"amenity\":\"fuel\",\"highway\":\"services\"}") == OsmDataFetcher.Kind.AMENITY);
        check("historic sobre park → TOURISM",
                kind("{\"historic\":\"ruins\",\"leisure\":\"park\"}") == OsmDataFetcher.Kind.TOURISM);
    }

    /** El terreno sigue clasificando como antes. */
    private static void terrainFallbacks() {
        check("edificio solo → BUILDING", kind("{\"building\":\"yes\"}") == OsmDataFetcher.Kind.BUILDING);
        check("calle → HIGHWAY", kind("{\"highway\":\"residential\"}") == OsmDataFetcher.Kind.HIGHWAY);
        check("parque → PARK", kind("{\"leisure\":\"park\"}") == OsmDataFetcher.Kind.PARK);
        check("agua → WATER", kind("{\"natural\":\"water\"}") == OsmDataFetcher.Kind.WATER);
        check("bosque → WOOD", kind("{\"natural\":\"wood\"}") == OsmDataFetcher.Kind.WOOD);
        check("sin tags conocidos → null", kind("{\"name\":\"x\"}") == null);
        check("vacío → null", kind("{}") == null);
    }

    /** Criterio 4: cero valores muertos; el enum se queda en 8. */
    private static void enumIsMinimalAndComplete() {
        check("enum con 8 valores", OsmDataFetcher.Kind.values().length == 8);
        check("HIGHWAY alcanzable", kind("{\"highway\":\"path\"}") == OsmDataFetcher.Kind.HIGHWAY);
        check("PARK alcanzable", kind("{\"leisure\":\"park\"}") == OsmDataFetcher.Kind.PARK);
        check("WOOD alcanzable", kind("{\"natural\":\"wood\"}") == OsmDataFetcher.Kind.WOOD);
        check("BUILDING alcanzable", kind("{\"building\":\"yes\"}") == OsmDataFetcher.Kind.BUILDING);
        check("WATER alcanzable", kind("{\"natural\":\"water\"}") == OsmDataFetcher.Kind.WATER);
        check("SHOP alcanzable", kind("{\"shop\":\"bakery\"}") == OsmDataFetcher.Kind.SHOP);
        check("TOURISM alcanzable", kind("{\"tourism\":\"attraction\"}") == OsmDataFetcher.Kind.TOURISM);
        check("AMENITY alcanzable", kind("{\"amenity\":\"townhall\"}") == OsmDataFetcher.Kind.AMENITY);
    }

    /** Criterio 1 por el camino real: `parse()` acepta nodos y vías. */
    private static void parseKeepsNodesAndWays() {
        String json = "{\"elements\":["
                + "{\"type\":\"node\",\"lat\":19.5,\"lon\":-96.9,"
                + "\"tags\":{\"amenity\":\"pharmacy\"}},"
                + "{\"type\":\"way\",\"tags\":{\"building\":\"yes\",\"amenity\":\"hospital\"},"
                + "\"geometry\":[{\"lat\":19.5,\"lon\":-96.9},{\"lat\":19.5001,\"lon\":-96.9}]},"
                + "{\"type\":\"way\",\"tags\":{\"highway\":\"residential\"},"
                + "\"geometry\":[{\"lat\":19.5,\"lon\":-96.9},{\"lat\":19.5002,\"lon\":-96.9}]},"
                + "{\"type\":\"node\",\"lat\":19.5,\"lon\":-96.9,\"tags\":{\"name\":\"sin tipo\"}}"
                + "]}";
        List<OsmDataFetcher.OsmElement> parsed = OsmDataFetcher.parse(json);
        check("parse conserva 3 de 4 elementos (descarta el sin tipo)", parsed.size() == 3);
        check("nodo farmacia → AMENITY", parsed.get(0).kind == OsmDataFetcher.Kind.AMENITY);
        check("vía hospital → AMENITY", parsed.get(1).kind == OsmDataFetcher.Kind.AMENITY);
        check("vía calle → HIGHWAY", parsed.get(2).kind == OsmDataFetcher.Kind.HIGHWAY);
        check("nodo con geometría de 1 punto", parsed.get(0).lats.length == 1);
        check("vía con geometría de 2 puntos", parsed.get(1).lats.length == 2);
    }

    /**
     * Criterio 2026-11-08: una vía que solo cruza el círculo no debe entrar
     * completa. Se conservan únicamente los puntos dentro de la unión de círculos.
     */
    private static void clipGeometry() {
        String json = "{\"elements\":["
                + "{\"type\":\"node\",\"id\":1,\"lat\":19.5,\"lon\":-96.9,"
                + "\"tags\":{\"amenity\":\"pharmacy\"}},"
                + "{\"type\":\"node\",\"id\":2,\"lat\":19.6,\"lon\":-96.9,"
                + "\"tags\":{\"amenity\":\"pharmacy\"}},"
                + "{\"type\":\"way\",\"id\":10,\"tags\":{\"highway\":\"residential\"},"
                + "\"geometry\":[{\"lat\":19.5,\"lon\":-96.9},"
                + "{\"lat\":19.5001,\"lon\":-96.9},{\"lat\":19.51,\"lon\":-96.9},"
                + "{\"lat\":19.5002,\"lon\":-96.9}]},"
                + "{\"type\":\"way\",\"id\":11,\"tags\":{\"highway\":\"residential\"},"
                + "\"geometry\":[{\"lat\":19.5,\"lon\":-96.9},{\"lat\":19.6,\"lon\":-96.9}]},"
                + "{\"type\":\"node\",\"id\":3,\"lat\":19.503,\"lon\":-96.9,"
                + "\"tags\":{\"amenity\":\"pharmacy\"}}"
                + "]}";
        List<OsmDataFetcher.OsmElement> clipped = OsmDataFetcher.parse(json,
                new double[][] {{19.5, -96.9}}, 150.0);
        check("recorte: nodo dentro se conserva", clipped.size() == 2);
        check("recorte: nodo a 11 km se descarta", clipped.get(0).id == 1);
        check("recorte: vía queda con 3 puntos", clipped.get(1).id == 10
                && clipped.get(1).lats.length == 3);
        List<OsmDataFetcher.OsmElement> union = OsmDataFetcher.parse(json,
                new double[][] {{19.5, -96.9}, {19.503, -96.9}}, 150.0);
        check("recorte: unión conserva el segundo centro",
                union.size() == 3 && union.get(2).id == 3);
        check("sin centros no recorta", OsmDataFetcher.parse(json).size() == 5);
    }

    private static OsmDataFetcher.Kind kind(String tagsJson) {
        try {
            return OsmDataFetcher.classify(new JSONObject(tagsJson));
        } catch (Exception e) {
            throw new RuntimeException("JSON de prueba inválido: " + tagsJson, e);
        }
    }

    private static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  OK   " : "  FALLO") + "  " + name);
        if (!ok) throw new AssertionError("Fallo: " + name);
    }
}
