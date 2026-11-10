package hosttest;

import com.gpsquest96.route.Route;
import com.gpsquest96.route.RouteGenerator;
import com.gpsquest96.route.RouteNode;
import com.gpsquest96.route.RouteValidator;
import com.gpsquest96.route.RouteValidator.Destination;
import com.gpsquest96.route.RouteValidator.GpsFix;
import com.gpsquest96.route.RouteValidator.RomSnapshot;
import com.gpsquest96.route.RouteValidator.Validation;

import java.util.ArrayList;

/**
 * Host test de spec 004 slice 3 (doble validación R3 en modo lectura).
 * <p>
 * Sin Android, sin red y sin POCO: compila la clase real
 * {@code com.gpsquest96.route.RouteValidator} contra stubs de Android.
 * <p>
 * Cubre:
 *  1. Distancia haversine y codificación provisional de mapa.
 *  2. Señal 1 (llegada): dentro/fuera del radio del nodo.
 *  3. Señal 2 (consumo): cambio de posición ROM entre snapshots.
 *  4. Señal 3 (destino): match, mapa incompatible, casilla distinta y
 *     nodo sin destino declarado (NOT_APPLICABLE, sin inventar terreno).
 *  5. Lecturas dudosas: fix impreciso, precisión desconocida, sin fix,
 *     sin snapshot, avatar sin cambio.
 *  6. Cero escrituras: evaluar no modifica el nodo ni la ruta.
 */
public class RouteValidatorHostTest {

    private static int checks = 0;

    private static final double NODE_LAT = 19.5275;
    private static final double NODE_LON = -96.9242;
    private static final double RADIUS_M = 15.0;

    public static void main(String[] args) throws Exception {
        distanceAndMapId();
        snapshotParsing();
        arrival();
        consumption();
        destination();
        doubtfulReadings();
        cleanCaseAndLog();
        noWrites();
        System.out.println("ROUTE VALIDATOR HOST TEST OK (" + checks + " comprobaciones)");
    }

    private static RouteNode plainNode() {
        return RouteNode.at(0, RouteNode.Action.WALK, "synthetic",
                NODE_LAT, NODE_LON, RADIUS_M);
    }

    private static RouteNode destNode() {
        return new RouteNode(0, RouteNode.Action.WALK, "synthetic",
                NODE_LAT, NODE_LON, RADIUS_M,
                RouteValidator.mapIdOf(1, 1), 5, 7, false, null);
    }

    private static void distanceAndMapId() {
        double d0 = RouteGenerator.haversineM(NODE_LAT, NODE_LON, NODE_LAT, NODE_LON);
        check("distancia al mismo punto es 0", d0 == 0.0);
        double dLat = RouteGenerator.haversineM(0.0, 0.0, 1.0, 0.0);
        check("1 grado de latitud ~111195 m", Math.abs(dLat - 111194.9) < 1.0);
        double dMilli = RouteGenerator.haversineM(0.0, 0.0, 0.001, 0.0);
        check("0.001 grados de latitud ~111.19 m", Math.abs(dMilli - 111.19) < 0.5);
        check("mapIdOf(1,1) = 257", RouteValidator.mapIdOf(1, 1) == 257);
        check("mapIdOf(2,3) = 515", RouteValidator.mapIdOf(2, 3) == 515);
    }

    private static void snapshotParsing() {
        check("snapshot nulo es inválido",
                !RomSnapshot.fromCurrentMap(null).valid);
        check("snapshot corto es inválido",
                !RomSnapshot.fromCurrentMap(new int[]{1, 2, 3}).valid);
        RomSnapshot s = RomSnapshot.fromCurrentMap(new int[]{3, 12, 7, 9});
        check("snapshot válido con grupo/mapa/x/y",
                s.valid && s.mapGroup == 3 && s.mapNumber == 12
                        && s.y == 7 && s.x == 9);
    }

    private static void arrival() {
        RomSnapshot prev = new RomSnapshot(1, 1, 7, 4, true);
        RomSnapshot cur = new RomSnapshot(1, 1, 7, 5, true);
        RouteNode node = plainNode();
        Validation at = RouteValidator.evaluate(prev, cur,
                new GpsFix(NODE_LAT, NODE_LON, 5f), node);
        check("llegada: fix sobre el nodo", at.arrival);
        check("llegada: distancia ~0 m", at.distanceM < 0.01);
        double dLat100 = 100.0 / 111194.9;
        Validation far = RouteValidator.evaluate(prev, cur,
                new GpsFix(NODE_LAT + dLat100, NODE_LON, 5f), node);
        check("llegada: fix a 100 m no llega", !far.arrival);
        double dLat14 = 14.0 / 111194.9;
        Validation near = RouteValidator.evaluate(prev, cur,
                new GpsFix(NODE_LAT + dLat14, NODE_LON, 5f), node);
        check("llegada: fix a 14 m sí llega (radio 15 m)", near.arrival);
    }

    private static void consumption() {
        RouteNode node = plainNode();
        GpsFix fix = new GpsFix(NODE_LAT, NODE_LON, 5f);
        RomSnapshot a = new RomSnapshot(1, 1, 7, 5, true);
        RomSnapshot same = new RomSnapshot(1, 1, 7, 5, true);
        Validation still = RouteValidator.evaluate(a, same, fix, node);
        check("consumo: mismo snapshot no consume", !still.consumption);
        check("consumo: avatar parado es dudoso",
                still.doubtful && contains(still, "sin cambio"));
        RomSnapshot moved = new RomSnapshot(1, 1, 7, 6, true);
        Validation walk = RouteValidator.evaluate(a, moved, fix, node);
        check("consumo: cambio de casilla sí consume", walk.consumption);
        RomSnapshot otherMap = new RomSnapshot(1, 2, 7, 5, true);
        Validation changed = RouteValidator.evaluate(a, otherMap, fix, node);
        check("consumo: cambio de mapa sí consume", changed.consumption);
        Validation noPrev = RouteValidator.evaluate(null, moved, fix, node);
        check("consumo: sin snapshot previo no se mide",
                !noPrev.consumption && contains(noPrev, "sin snapshot previo"));
        // GPS se movió pero la ROM no: anti-skip (R5) en modo lectura.
        Validation gpsOnly = RouteValidator.evaluate(a, same,
                new GpsFix(NODE_LAT + 0.001, NODE_LON, 5f), node);
        check("consumo: GPS solo no consume sin cambio ROM",
                !gpsOnly.consumption && !gpsOnly.arrival);
    }

    private static void destination() {
        RouteNode plain = plainNode();
        // RomSnapshot(grupo, mapa, x, y): el destino es x=5, y=7.
        RomSnapshot cur = new RomSnapshot(1, 1, 5, 7, true);
        RomSnapshot prev = new RomSnapshot(1, 1, 4, 7, true);
        GpsFix fix = new GpsFix(NODE_LAT, NODE_LON, 5f);
        Validation na = RouteValidator.evaluate(prev, cur, fix, plain);
        check("destino: nodo sin destino es N-A",
                na.destination == Destination.NOT_APPLICABLE);
        check("destino: nodo sin destino es dudoso",
                na.doubtful && contains(na, "sin destino"));

        RouteNode dest = destNode();
        Validation match = RouteValidator.evaluate(prev, cur, fix, dest);
        check("destino: casilla del nodo es MATCH",
                match.destination == Destination.MATCH);
        RomSnapshot otherMap = new RomSnapshot(1, 2, 5, 7, true);
        Validation wrongMap = RouteValidator.evaluate(prev, otherMap, fix, dest);
        check("destino: otro mapa es MISMATCH",
                wrongMap.destination == Destination.MISMATCH);
        check("destino: otro mapa se marca incompatible",
                wrongMap.doubtful && contains(wrongMap, "incompatible"));
        RomSnapshot otherTile = new RomSnapshot(1, 1, 6, 7, true);
        Validation wrongTile = RouteValidator.evaluate(prev, otherTile, fix, dest);
        check("destino: misma mapa, otra casilla, es MISMATCH",
                wrongTile.destination == Destination.MISMATCH);
        check("destino: otra casilla se marca como tal",
                wrongTile.doubtful && contains(wrongTile, "casilla"));
    }

    private static void doubtfulReadings() {
        RouteNode node = plainNode();
        RomSnapshot prev = new RomSnapshot(1, 1, 7, 4, true);
        RomSnapshot cur = new RomSnapshot(1, 1, 7, 5, true);
        Validation imprecise = RouteValidator.evaluate(prev, cur,
                new GpsFix(NODE_LAT, NODE_LON, 30f), node);
        check("dudosa: fix ±30 m con radio 15 m",
                imprecise.doubtful && contains(imprecise, "impreciso"));
        check("dudosa: fix impreciso no anula la llegada", imprecise.arrival);
        Validation unknown = RouteValidator.evaluate(prev, cur,
                new GpsFix(NODE_LAT, NODE_LON), node);
        check("dudosa: precisión desconocida",
                unknown.doubtful && contains(unknown, "desconocida"));
        Validation noFix = RouteValidator.evaluate(prev, cur, null, node);
        check("dudosa: sin fix no hay llegada",
                !noFix.arrival && contains(noFix, "sin fix"));
        Validation noRom = RouteValidator.evaluate(prev,
                RomSnapshot.invalid(), new GpsFix(NODE_LAT, NODE_LON, 5f), node);
        check("dudosa: sin snapshot ROM no se mide nada",
                !noRom.arrival && !noRom.consumption
                        && noRom.destination == Destination.NOT_APPLICABLE
                        && contains(noRom, "sin snapshot ROM"));
        boolean threw = false;
        try {
            RouteValidator.evaluate(prev, cur, new GpsFix(NODE_LAT, NODE_LON, 5f), null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("nodo nulo se rechaza", threw);
    }

    private static void cleanCaseAndLog() {
        RouteNode dest = destNode();
        // RomSnapshot(grupo, mapa, x, y): destino x=5, y=7.
        RomSnapshot prev = new RomSnapshot(1, 1, 4, 7, true);
        RomSnapshot cur = new RomSnapshot(1, 1, 5, 7, true);
        Validation v = RouteValidator.evaluate(prev, cur,
                new GpsFix(NODE_LAT, NODE_LON, 5f), dest);
        check("caso limpio: las tres señales en verde",
                v.arrival && v.consumption && v.destination == Destination.MATCH);
        check("caso limpio: no es dudoso", !v.doubtful && v.doubts.isEmpty());
        String log = v.formatLog(0);
        check("log: contiene R3 y las tres señales",
                log.contains("R3 nodo=0") && log.contains("llegada=SI")
                        && log.contains("consumo=SI") && log.contains("destino=OK")
                        && log.contains("dudosa=NO"));
    }

    private static void noWrites() throws Exception {
        Route route = RouteGenerator.generate("TEST_ROM_1-v1", NODE_LAT, NODE_LON,
                8.0, 1700000000000L, new ArrayList<com.gpsquest96.gps.OsmDataFetcher.OsmElement>(), 4);
        String before = route.toJson();
        RouteNode node = route.nodes.get(0);
        RomSnapshot prev = new RomSnapshot(1, 1, 7, 4, true);
        RomSnapshot cur = new RomSnapshot(1, 1, 7, 5, true);
        RouteValidator.evaluate(prev, cur, new GpsFix(NODE_LAT, NODE_LON, 5f), node);
        check("cero escrituras: la ruta no cambia al evaluar",
                route.toJson().equals(before));
        check("cero escrituras: el nodo generado no tiene destino",
                node.destMapId == RouteNode.NO_DEST);
    }

    private static boolean contains(Validation v, String needle) {
        for (String d : v.doubts) {
            if (d.contains(needle)) return true;
        }
        return false;
    }

    private static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  OK   " : "  FALLO") + "  " + name);
        if (!ok) throw new AssertionError("Fallo: " + name);
    }
}
