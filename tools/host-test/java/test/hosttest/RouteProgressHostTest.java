package hosttest;

import com.gpsquest96.route.Route;
import com.gpsquest96.route.RouteFormatException;
import com.gpsquest96.route.RouteNode;
import com.gpsquest96.route.RouteProgress;
import com.gpsquest96.route.RouteProgress.Outcome;
import com.gpsquest96.route.RouteValidator;
import com.gpsquest96.route.RouteValidator.GpsFix;
import com.gpsquest96.route.RouteValidator.RomSnapshot;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Host test de spec 004 slice 4 (avance monotónico, resueltos[] y
 * re-enganche).
 * <p>
 * Sin Android, sin red y sin POCO: compila la clase real
 * {@code com.gpsquest96.route.RouteProgress} contra stubs de Android.
 * <p>
 * Cubre:
 *  1. Estado inicial y fin de ruta.
 *  2. Avance + resolución con lectura limpia (llegada + consumo).
 *  3. Anti-skip (R5): sin consumo no hay avance, aunque haya llegada.
 *  4. Sin llegada no hay avance aunque el avatar se mueva.
 *  5. Lectura bloqueada (fix impreciso) no avanza ni resuelve.
 *  6. Puerta de destino: MISMATCH avanza sin resolver; MATCH resuelve;
 *     re-enganche con mapa incompatible no mueve nada.
 *  7. Re-enganche (R6): solo hacia atrás, al pendiente más alto dentro
 *     de 120 m; fuera de radio o hacia adelante no se mueve.
 *  8. Monotonicidad (R8): posicion puede bajar, resueltos[] nunca se
 *     deshace; un nodo ya resuelto no se vuelve a reportar.
 *  9. Persistencia: round-trip JSON, guardado/carga en archivo, rechazos
 *     con motivo (corrupto, otra partida, rangos).
 */
public class RouteProgressHostTest {

    private static int checks = 0;

    private static final double LAT0 = 19.5275;
    private static final double LON0 = -96.9242;
    private static final double RADIUS_M = 15.0;

    /** Metros a grados de latitud (suficiente para distancias de test). */
    private static double latPlus(double meters) {
        return LAT0 + meters / 111195.0;
    }

    public static void main(String[] args) throws Exception {
        freshAndFinished();
        firstFixNoAdvance();
        resolveClean();
        antiSkip();
        noArrival();
        doubtfulFix();
        destinationGate();
        reengage();
        monotonicity();
        persistence();
        System.out.println("ROUTE PROGRESS HOST TEST OK (" + checks + " comprobaciones)");
    }

    // ------------------------------------------------------------------
    // Construcción de rutas de test (nodos en línea, 8 m entre ellos)
    // ------------------------------------------------------------------

    private static Route lineRoute(int n) {
        return lineRoute(n, "TEST-v0");
    }

    private static Route lineRoute(int n, String romKey) {
        List<RouteNode> nodes = new ArrayList<RouteNode>();
        for (int i = 0; i < n; i++) {
            nodes.add(RouteNode.at(i, RouteNode.Action.WALK, "synthetic",
                    latPlus(i * 8.0), LON0, RADIUS_M));
        }
        return new Route(romKey, LAT0, LON0, 5.0, 0L, nodes);
    }

    /**
     * Ruta con el nodo 1 con destino ROM declarado (mapa 1/1, casilla 5,7)
     * y nodos muy separados (0, 8, 208, 408, 608 m): con nodos a 8 m y
     * radio de llegada de 15 m, un fix junto a un nodo también llega al
     * vecino, y un pendiente a 8 m siempre dispara el re-enganche antes de
     * evaluar el nodo siguiente. La separación es lo que permite probar
     * "posicion por delante de un pendiente" sin ese solapamiento.
     */
    private static Route destRoute() {
        double[] at = {0.0, 8.0, 208.0, 408.0, 608.0};
        List<RouteNode> nodes = new ArrayList<RouteNode>();
        nodes.add(RouteNode.at(0, RouteNode.Action.WALK, "synthetic",
                latPlus(at[0]), LON0, RADIUS_M));
        nodes.add(new RouteNode(1, RouteNode.Action.WALK, "synthetic",
                latPlus(at[1]), LON0, RADIUS_M,
                RouteValidator.mapIdOf(1, 1), 5, 7, false, null));
        for (int i = 2; i < 5; i++) {
            nodes.add(RouteNode.at(i, RouteNode.Action.WALK, "synthetic",
                    latPlus(at[i]), LON0, RADIUS_M));
        }
        return new Route("TEST-v0", LAT0, LON0, 5.0, 0L, nodes);
    }

    private static RomSnapshot snap(int x) {
        return new RomSnapshot(1, 1, x, 0, true);
    }

    private static RomSnapshot snapAt(int group, int map, int x, int y) {
        return new RomSnapshot(group, map, x, y, true);
    }

    private static GpsFix fixAtNode(Route route, int index) {
        RouteNode n = route.nodes.get(index);
        return new GpsFix(n.lat, n.lon, 5.0f);
    }

    private static GpsFix fixAt(double lat, double lon) {
        return new GpsFix(lat, lon, 5.0f);
    }

    /** Un paso limpio: llegada + consumo sobre el nodo actual. */
    private static Outcome stepClean(RouteProgress p, RomSnapshot prev,
            RomSnapshot cur, GpsFix fix) {
        return p.evaluate(prev, cur, fix);
    }

    // ------------------------------------------------------------------
    // 1. Estado inicial y fin de ruta
    // ------------------------------------------------------------------

    private static void freshAndFinished() {
        RouteProgress p = new RouteProgress(lineRoute(5));
        check("posición inicial 0", p.getPosition() == 0);
        check("nada resuelto al inicio", p.resolvedCount() == 0);
        check("no terminada al inicio", !p.isFinished());
        check("5 nodos", p.getNodeCount() == 5);

        RouteProgress done = new RouteProgress(lineRoute(2));
        RomSnapshot a = snap(1);
        RomSnapshot b = snap(2);
        RomSnapshot c = snap(3);
        done.evaluate(null, a, fixAtNode(done.route, 0));
        Outcome o1 = done.evaluate(a, b, fixAtNode(done.route, 0));
        check("nodo 0 resuelto", o1.resolvedIndex == 0 && done.getPosition() == 1);
        Outcome o2 = done.evaluate(b, c, fixAtNode(done.route, 1));
        check("nodo 1 resuelto y ruta terminada",
                o2.resolvedIndex == 1 && done.isFinished() && done.getPosition() == 2);
        Outcome o3 = done.evaluate(c, snap(4), fixAtNode(done.route, 1));
        check("terminada: no cambia nada más",
                !o3.changed && o3.validation == null && done.getPosition() == 2);
    }

    // ------------------------------------------------------------------
    // 2. Primer fix sin previo no avanza (no se puede medir consumo)
    // ------------------------------------------------------------------

    private static void firstFixNoAdvance() {
        RouteProgress p = new RouteProgress(lineRoute(3));
        Outcome o = p.evaluate(null, snap(1), fixAtNode(p.route, 0));
        check("primer fix sin snapshot previo no avanza",
                !o.changed && p.getPosition() == 0 && p.resolvedCount() == 0);
        Outcome o2 = p.evaluate(snap(1), snap(2), fixAtNode(p.route, 0));
        check("segundo fix con movimiento sí resuelve el nodo 0",
                o2.resolvedIndex == 0 && p.getPosition() == 1);
    }

    // ------------------------------------------------------------------
    // 3. Avance + resolución con lectura limpia
    // ------------------------------------------------------------------

    private static void resolveClean() {
        RouteProgress p = new RouteProgress(lineRoute(3));
        p.evaluate(null, snap(10), fixAtNode(p.route, 0));
        Outcome o = stepClean(p, snap(10), snap(11), fixAtNode(p.route, 0));
        check("llegada + consumo resuelve y avanza",
                o.resolvedIndex == 0 && o.positionAfter == 1 && o.changed);
        check("línea R4 con pos y resuelto",
                o.formatLog().contains("pos=0→1") && o.formatLog().contains("resuelto=0"));
        Outcome o2 = stepClean(p, snap(11), snap(12), fixAtNode(p.route, 1));
        check("el nodo 1 también se resuelve en orden",
                o2.resolvedIndex == 1 && p.getPosition() == 2);
    }

    // ------------------------------------------------------------------
    // 4. Anti-skip (R5): llegada sin consumo no avanza
    // ------------------------------------------------------------------

    private static void antiSkip() {
        RouteProgress p = new RouteProgress(lineRoute(3));
        RomSnapshot a = snap(20);
        p.evaluate(null, a, fixAtNode(p.route, 0));
        // El avatar no se movió entre snapshots (atascado contra una pared).
        Outcome stuck = p.evaluate(a, snap(20), fixAtNode(p.route, 0));
        check("atascado: llegada sin consumo no avanza",
                !stuck.changed && p.getPosition() == 0 && p.resolvedCount() == 0);
        check("atascado: la lectura queda dudosa",
                stuck.validation != null && stuck.validation.doubtful);
        Outcome free = stepClean(p, snap(20), snap(21), fixAtNode(p.route, 0));
        check("al moverse, el mismo nodo sí se resuelve",
                free.resolvedIndex == 0 && p.getPosition() == 1);
    }

    // ------------------------------------------------------------------
    // 5. Sin llegada no hay avance aunque el avatar se mueva
    // ------------------------------------------------------------------

    private static void noArrival() {
        RouteProgress p = new RouteProgress(lineRoute(3));
        RomSnapshot a = snap(30);
        p.evaluate(null, a, fixAtNode(p.route, 0));
        // Fix a 100 m del nodo 0, avatar moviéndose.
        Outcome far = p.evaluate(a, snap(31), fixAt(latPlus(100.0), LON0));
        check("lejos del nodo: no avanza aunque el avatar se mueva",
                !far.changed && p.getPosition() == 0);
        check("lejos del nodo: la llegada es NO",
                far.validation != null && !far.validation.arrival);
    }

    // ------------------------------------------------------------------
    // 6. Lectura bloqueada (fix impreciso) no avanza ni resuelve
    // ------------------------------------------------------------------

    private static void doubtfulFix() {
        RouteProgress p = new RouteProgress(lineRoute(3));
        RouteNode n0 = p.route.nodes.get(0);
        RomSnapshot a = snap(40);
        p.evaluate(null, a, new GpsFix(n0.lat, n0.lon, 5.0f));
        GpsFix imprecise = new GpsFix(n0.lat, n0.lon, 50.0f);
        Outcome bad = p.evaluate(a, snap(41), imprecise);
        check("fix impreciso (±50 m > radio 15 m) bloquea el avance",
                !bad.changed && bad.validation != null && bad.validation.blocked);
        Outcome good = stepClean(p, snap(41), snap(42), fixAtNode(p.route, 0));
        check("con fix preciso el nodo se resuelve",
                good.resolvedIndex == 0 && p.getPosition() == 1);
    }

    // ------------------------------------------------------------------
    // 7. Puerta de destino: MISMATCH avanza sin resolver; MATCH resuelve
    // ------------------------------------------------------------------

    private static void destinationGate() {
        RouteProgress p = new RouteProgress(destRoute());
        // Nodo 0 (sin destino) se resuelve limpio.
        p.evaluate(null, snapAt(1, 1, 1, 0), fixAtNode(p.route, 0));
        Outcome o0 = p.evaluate(snapAt(1, 1, 1, 0), snapAt(1, 1, 2, 0),
                fixAtNode(p.route, 0));
        check("nodo 0 sin destino se resuelve", o0.resolvedIndex == 0);

        // Nodo 1 tiene destino (1/1, 5,7). El avatar está en otra casilla:
        // llegada + consumo, pero destino MISMATCH.
        RomSnapshot prev = snapAt(1, 1, 2, 0);
        RomSnapshot cur = snapAt(1, 1, 9, 9);
        Outcome mismatch = p.evaluate(prev, cur, fixAtNode(p.route, 1));
        check("destino distinto: posicion avanza pero NO resuelve",
                mismatch.changed && mismatch.resolvedIndex == -1
                        && p.getPosition() == 2 && !p.isResolved(1));
        check("destino distinto: la lectura NO está bloqueada (la decide el destino)",
                mismatch.validation != null && !mismatch.validation.blocked
                        && mismatch.validation.destination
                                == RouteValidator.Destination.MISMATCH);

        // Re-enganche con mapa incompatible no mueve nada. El fix está a
        // 100 m del nodo 1 (dentro de 120 m) y a 100 m del nodo 2 (sin
        // llegada): solo el mapa impide el re-enganche.
        RomSnapshot otherMap = snapAt(2, 1, 9, 9);
        Outcome noReengage = p.evaluate(cur, otherMap, fixAt(latPlus(108.0), LON0));
        check("mapa incompatible: no hay re-enganche",
                noReengage.reengagedFrom == -1 && p.getPosition() == 2);

        // Re-enganche al nodo 1 pendiente (mapa compatible 1/1). El fix
        // sigue a 100 m: hay re-enganche pero no llegada, así que la
        // posición se queda en 1 sin avanzar.
        RomSnapshot back = snapAt(1, 1, 9, 9);
        Outcome re = p.evaluate(otherMap, back, fixAt(latPlus(108.0), LON0));
        check("re-enganche al nodo 1 pendiente",
                re.reengagedFrom == 2 && p.getPosition() == 1);

        // Ahora el avatar sí está en la casilla destino: MATCH resuelve.
        Outcome match = p.evaluate(back, snapAt(1, 1, 5, 7), fixAtNode(p.route, 1));
        check("destino MATCH: el nodo 1 se resuelve",
                match.resolvedIndex == 1 && p.isResolved(1) && p.getPosition() == 2);
        check("destino MATCH: la validación es limpia",
                match.validation != null
                        && match.validation.destination == RouteValidator.Destination.MATCH
                        && !match.validation.blocked);
    }

    // ------------------------------------------------------------------
    // 8. Re-enganche (R6): solo atrás, dentro de 120 m, al más alto
    // ------------------------------------------------------------------

    private static void reengage() {
        RouteProgress p = new RouteProgress(destRoute());
        // Resuelve 0, pasa 1 sin resolver (MISMATCH), resuelve 2. El nodo
        // 2 está a 200 m del 1: fuera del radio de re-enganche, así que la
        // posición sí puede adelantarse con el 1 pendiente.
        p.evaluate(null, snapAt(1, 1, 1, 0), fixAtNode(p.route, 0));
        p.evaluate(snapAt(1, 1, 1, 0), snapAt(1, 1, 2, 0), fixAtNode(p.route, 0));
        p.evaluate(snapAt(1, 1, 2, 0), snapAt(1, 1, 9, 9), fixAtNode(p.route, 1));
        p.evaluate(snapAt(1, 1, 9, 9), snapAt(1, 1, 10, 0), fixAtNode(p.route, 2));
        check("preparación: pos 3, resueltos 0 y 2, pendiente el 1",
                p.getPosition() == 3 && p.resolvedCount() == 2 && !p.isResolved(1));

        // Fuera de radio (fix a 150 m: 142 m del nodo 1): no hay re-enganche
        // y el nodo 3 queda lejos (258 m), así que nada cambia.
        Outcome far = p.evaluate(snapAt(1, 1, 10, 0), snapAt(1, 1, 11, 0),
                fixAt(latPlus(150.0), LON0));
        check("a 142 m del pendiente: sin re-enganche",
                far.reengagedFrom == -1 && p.getPosition() == 3);

        // Hacia adelante nunca: fix junto al nodo 4 (pendiente, índice 4
        // > 3) y lejos del nodo 3 (200 m): nada se mueve.
        Outcome fwd = p.evaluate(snapAt(1, 1, 11, 0), snapAt(1, 1, 12, 0),
                fixAtNode(p.route, 4));
        check("nunca hacia adelante: el nodo 4 no mueve la posición",
                fwd.reengagedFrom == -1 && p.getPosition() == 3);

        // Dentro de 120 m del nodo 1: re-enganche 3→1. La evaluación del
        // nodo 1 con ese mismo fix vuelve a avanzar (MISMATCH: llegó pero
        // sin completar el destino), así que la posición termina en 2 con
        // el 1 todavía pendiente: el re-enganche queda registrado y el
        // avance sin resolver también. Resolverlo exige el MATCH.
        Outcome back = p.evaluate(snapAt(1, 1, 12, 0), snapAt(1, 1, 13, 0),
                fixAtNode(p.route, 1));
        check("re-enganche: 3→1 al pendiente dentro de 120 m",
                back.reengagedFrom == 3 && p.getPosition() == 2
                        && !p.isResolved(1));
        check("re-enganche: lo resuelto no se toca",
                p.isResolved(0) && p.isResolved(2) && p.resolvedCount() == 2);

        // Con el destino ya en MATCH, el nodo 1 se resuelve al volver.
        Outcome match = p.evaluate(snapAt(1, 1, 13, 0), snapAt(1, 1, 5, 7),
                fixAtNode(p.route, 1));
        check("tras el MATCH el nodo 1 se resuelve",
                match.resolvedIndex == 1 && p.isResolved(1) && p.getPosition() == 2);
    }

    // ------------------------------------------------------------------
    // 9. Monotonicidad (R8)
    // ------------------------------------------------------------------

    private static void monotonicity() {
        RouteProgress p = new RouteProgress(destRoute());
        p.evaluate(null, snapAt(1, 1, 1, 0), fixAtNode(p.route, 0));
        p.evaluate(snapAt(1, 1, 1, 0), snapAt(1, 1, 2, 0), fixAtNode(p.route, 0));
        int resolvedBefore = p.resolvedCount();
        // Pasar el nodo 1 sin resolver y volver: resueltos[] no baja nunca.
        p.evaluate(snapAt(1, 1, 2, 0), snapAt(1, 1, 9, 9), fixAtNode(p.route, 1));
        p.evaluate(snapAt(1, 1, 9, 9), snapAt(1, 1, 10, 0), fixAtNode(p.route, 2));
        check("resueltos[] nunca baja tras avance y re-enganche",
                p.resolvedCount() == resolvedBefore + 1 && p.isResolved(0)
                        && p.isResolved(2) && !p.isResolved(1));
        // Atascado junto al nodo 1 pendiente: el re-enganche baja la
        // posición a 1 pero sin consumo no hay avance; lo resuelto intacto.
        Outcome stuck = p.evaluate(snapAt(1, 1, 10, 0), snapAt(1, 1, 10, 0),
                fixAtNode(p.route, 1));
        check("atascado: re-enganche sin avance, resueltos intactos",
                stuck.reengagedFrom == 3 && p.getPosition() == 1
                        && p.resolvedCount() == 2 && p.isResolved(0) && p.isResolved(2));
        // Resolver el nodo 1 (MATCH) y pasar el 2: el nodo 2 ya resuelto no
        // se vuelve a reportar al pasar por él.
        p.evaluate(snapAt(1, 1, 10, 0), snapAt(1, 1, 5, 7), fixAtNode(p.route, 1));
        check("el nodo 1 queda resuelto", p.isResolved(1) && p.getPosition() == 2);
        Outcome again = p.evaluate(snapAt(1, 1, 5, 7), snapAt(1, 1, 11, 0),
                fixAtNode(p.route, 2));
        check("nodo ya resuelto: avanza sin volver a reportarlo",
                again.resolvedIndex == -1 && p.getPosition() == 3);
    }

    // ------------------------------------------------------------------
    // 10. Persistencia
    // ------------------------------------------------------------------

    private static void persistence() throws Exception {
        Route route = lineRoute(4);
        RouteProgress p = new RouteProgress(route);
        p.evaluate(null, snap(50), fixAtNode(route, 0));
        p.evaluate(snap(50), snap(51), fixAtNode(route, 0));
        p.evaluate(snap(51), snap(52), fixAtNode(route, 1));
        check("preparación: pos 2, resueltos 0 y 1",
                p.getPosition() == 2 && p.resolvedCount() == 2);

        String json = p.toJson();
        RouteProgress back = RouteProgress.fromJson(json, route);
        check("round-trip JSON: misma posición y resueltos",
                back.getPosition() == 2 && back.resolvedCount() == 2
                        && back.isResolved(0) && back.isResolved(1)
                        && !back.isResolved(2));

        File dir = Files.createTempDirectory("route-progress").toFile();
        File dest = RouteProgress.fileFor(dir, route.romKey);
        check("nombre de archivo progress_<rom_key>.json",
                dest.getName().equals("progress_TEST-v0.json"));
        p.save(dest);
        check("guardado atómico: el archivo existe y no queda .tmp",
                dest.isFile() && !new File(dest.getAbsolutePath() + ".tmp").exists());
        RouteProgress loaded = RouteProgress.load(dest, route);
        check("carga desde archivo: misma posición y resueltos",
                loaded != null && loaded.getPosition() == 2 && loaded.resolvedCount() == 2);

        expectFormat("JSON corrupto se rechaza", "{no json", route);
        expectFormat("otra partida se rechaza",
                json.replace("TEST-v0", "OTRO-v9"), route);
        expectFormat("posicion fuera de rango se rechaza",
                json.replace("\"posicion\":2", "\"posicion\":99"), route);
        expectFormat("resuelto fuera de rango se rechaza",
                json.replace("\"resolved\":[0,1]", "\"resolved\":[0,9]"), route);
        expectFormat("resueltos sin orden se rechazan",
                json.replace("\"resolved\":[0,1]", "\"resolved\":[1,0]"), route);
        expectFormat("versión desconocida se rechaza",
                json.replace("\"schema_version\":1", "\"schema_version\":7"), route);

        // Limpieza del directorio temporal.
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    private static void expectFormat(String name, String json, Route route) {
        boolean thrown = false;
        try {
            RouteProgress.fromJson(json, route);
        } catch (RouteFormatException e) {
            thrown = true;
        }
        check(name, thrown);
    }

    private static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  OK   " : "  FALLO") + "  " + name);
        if (!ok) throw new AssertionError("Fallo: " + name);
    }
}
