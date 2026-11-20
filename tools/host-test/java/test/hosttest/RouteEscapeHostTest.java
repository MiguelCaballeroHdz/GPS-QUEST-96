package hosttest;

import com.gpsquest96.route.Route;
import com.gpsquest96.route.RouteFormatException;
import com.gpsquest96.route.RouteNode;
import com.gpsquest96.route.RouteProgress;
import com.gpsquest96.route.RouteProgress.Outcome;
import com.gpsquest96.route.RouteProgress.SkipOutcome;
import com.gpsquest96.route.RouteValidator;
import com.gpsquest96.route.RouteValidator.GpsFix;
import com.gpsquest96.route.RouteValidator.RomSnapshot;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Host test de spec 004 slice 6b (válvula de escape / R10).
 * <p>
 * Sin Android, sin red y sin POCO: compila la clase real
 * {@code com.gpsquest96.route.RouteProgress} contra stubs de Android.
 * <p>
 * Cubre:
 *  1. Rechazos del salto: ruta terminada, índice fuera de rango, nodo
 *     ya resuelto, nodo ya saltado y nodo por delante de la posición.
 *  2. Salto de un pendiente por detrás: no mueve la posición, no
 *     resuelve nada y vacía la lista de pendientes.
 *  3. Salto del nodo actual: avanza la posición en uno y puede terminar
 *     la ruta; el salto nunca cuenta como resuelto.
 *  4. El re-enganche (R6) ya no vuelve a un nodo saltado.
 *  5. Persistencia: round-trip JSON con saltados, archivos viejos sin
 *     "skipped" cargan con cero saltados, y saltados corruptos
 *     (fuera de rango, sin orden, solapados con resueltos) se rechazan.
 */
public class RouteEscapeHostTest {

    private static int checks = 0;

    private static final double LAT0 = 19.5275;
    private static final double LON0 = -96.9242;
    private static final double RADIUS_M = 15.0;

    /** Metros a grados de latitud (suficiente para distancias de test). */
    private static double latPlus(double meters) {
        return LAT0 + meters / 111195.0;
    }

    public static void main(String[] args) throws Exception {
        skipRejects();
        skipPendingBehind();
        skipCurrentAdvances();
        skipStopsReengage();
        skipPersists();
        System.out.println("ROUTE ESCAPE HOST TEST OK (" + checks + " comprobaciones)");
    }

    // ------------------------------------------------------------------
    // Construcción de rutas de test (mismo patrón que RouteProgressHostTest)
    // ------------------------------------------------------------------

    private static Route lineRoute(int n) {
        List<RouteNode> nodes = new ArrayList<RouteNode>();
        for (int i = 0; i < n; i++) {
            nodes.add(RouteNode.at(i, RouteNode.Action.WALK, "synthetic",
                    latPlus(i * 8.0), LON0, RADIUS_M));
        }
        return new Route("TEST-v0", LAT0, LON0, 5.0, 0L, nodes);
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

    /**
     * Progreso con el nodo 1 pendiente: resuelve el 0, pasa el 1 sin
     * resolver (MISMATCH de destino) y resuelve el 2. Termina en pos 3,
     * resueltos 0 y 2, pendiente el 1.
     */
    private static RouteProgress pendingOne() {
        RouteProgress p = new RouteProgress(destRoute());
        p.evaluate(null, snapAt(1, 1, 1, 0), fixAtNode(p.route, 0));
        p.evaluate(snapAt(1, 1, 1, 0), snapAt(1, 1, 2, 0), fixAtNode(p.route, 0));
        p.evaluate(snapAt(1, 1, 2, 0), snapAt(1, 1, 9, 9), fixAtNode(p.route, 1));
        p.evaluate(snapAt(1, 1, 9, 9), snapAt(1, 1, 10, 0), fixAtNode(p.route, 2));
        return p;
    }

    // ------------------------------------------------------------------
    // 1. Rechazos del salto
    // ------------------------------------------------------------------

    private static void skipRejects() {
        RouteProgress p = new RouteProgress(lineRoute(4));
        check("índice negativo se rechaza",
                !p.skip(-1).isOk() && p.skip(-1).reason != null);
        check("índice == N se rechaza", !p.skip(4).isOk());
        check("nodo por delante de la posición se rechaza",
                !p.skip(1).isOk() && p.getPosition() == 0);

        // Resuelve el nodo 0: saltarlo después se rechaza.
        p.evaluate(null, snap(10), fixAtNode(p.route, 0));
        p.evaluate(snap(10), snap(11), fixAtNode(p.route, 0));
        check("preparación: nodo 0 resuelto, pos 1",
                p.isResolved(0) && p.getPosition() == 1);
        check("nodo ya resuelto se rechaza", !p.skip(0).isOk());

        // Salta el nodo actual (1) y vuelve a intentarlo.
        SkipOutcome ok = p.skip(1);
        check("salto del nodo actual se aplica",
                ok.isOk() && ok.skippedIndex == 1 && p.getPosition() == 2);
        check("nodo ya saltado se rechaza", !p.skip(1).isOk());
        check("salto no resuelve: resueltos siguen en 1",
                p.resolvedCount() == 1 && !p.isResolved(1) && p.isSkipped(1));

        // Termina la ruta (resuelve 2 y 3) y el salto se rechaza.
        p.evaluate(snap(11), snap(12), fixAtNode(p.route, 2));
        p.evaluate(snap(12), snap(13), fixAtNode(p.route, 3));
        check("preparación: ruta terminada", p.isFinished());
        check("ruta terminada: el salto se rechaza", !p.skip(3).isOk());
    }

    // ------------------------------------------------------------------
    // 2. Salto de un pendiente por detrás
    // ------------------------------------------------------------------

    private static void skipPendingBehind() {
        RouteProgress p = pendingOne();
        check("preparación: pos 3, resueltos 0 y 2, pendiente el 1",
                p.getPosition() == 3 && p.resolvedCount() == 2
                        && !p.isResolved(1) && p.pendingCount() == 1
                        && p.pendingIndices().size() == 1
                        && p.pendingIndices().get(0) == 1);
        SkipOutcome o = p.skip(1);
        check("salto del pendiente se aplica",
                o.isOk() && o.skippedIndex == 1);
        check("salto por detrás no mueve la posición",
                o.positionBefore == 3 && o.positionAfter == 3
                        && p.getPosition() == 3 && !o.finished);
        check("el nodo queda saltado, no resuelto",
                p.isSkipped(1) && !p.isResolved(1) && p.skippedCount() == 1);
        check("resueltos[] no se toca al saltar",
                p.resolvedCount() == 2 && p.isResolved(0) && p.isResolved(2));
        check("ya no hay pendientes", p.pendingCount() == 0);
        check("línea R6b con salto y posición",
                o.formatLog().contains("salto nodo=1")
                        && o.formatLog().contains("pos=3→3"));
        check("toString muestra los saltados",
                p.toString().contains("saltados 1"));
    }

    // ------------------------------------------------------------------
    // 3. Salto del nodo actual: avanza y puede terminar la ruta
    // ------------------------------------------------------------------

    private static void skipCurrentAdvances() {
        RouteProgress p = new RouteProgress(lineRoute(3));
        SkipOutcome o0 = p.skip(0);
        check("saltar el nodo actual avanza la posición en uno",
                o0.isOk() && o0.positionBefore == 0 && o0.positionAfter == 1
                        && p.getPosition() == 1 && !o0.finished);
        check("el nodo actual saltado no se resuelve",
                p.isSkipped(0) && !p.isResolved(0) && p.resolvedCount() == 0);

        // El nodo 1 sí se resuelve limpio.
        p.evaluate(null, snap(20), fixAtNode(p.route, 1));
        Outcome o1 = p.evaluate(snap(20), snap(21), fixAtNode(p.route, 1));
        check("tras el salto, el nodo 1 se resuelve normal",
                o1.resolvedIndex == 1 && p.getPosition() == 2);

        // Saltar el último nodo actual termina la ruta.
        SkipOutcome o2 = p.skip(2);
        check("saltar el último nodo termina la ruta",
                o2.isOk() && o2.finished && p.isFinished() && p.getPosition() == 3);
        check("línea R6b con FIN", o2.formatLog().contains("FIN"));
        check("al terminar, resueltos solo el 1 y saltados 0 y 2",
                p.resolvedCount() == 1 && p.skippedCount() == 2
                        && p.isResolved(1) && p.isSkipped(0) && p.isSkipped(2));
    }

    // ------------------------------------------------------------------
    // 4. El re-enganche ya no vuelve a un nodo saltado
    // ------------------------------------------------------------------

    private static void skipStopsReengage() {
        // Control: sin saltar, el fix junto al nodo 1 re-engancha 3→1.
        RouteProgress control = pendingOne();
        Outcome back = control.evaluate(snapAt(1, 1, 10, 0), snapAt(1, 1, 11, 0),
                fixAtNode(control.route, 1));
        check("control: sin saltar, el fix re-engancha al pendiente",
                back.reengagedFrom == 3);

        // Con el salto, el mismo fix no mueve nada.
        RouteProgress p = pendingOne();
        p.skip(1);
        Outcome stayed = p.evaluate(snapAt(1, 1, 10, 0), snapAt(1, 1, 11, 0),
                fixAtNode(p.route, 1));
        check("saltado: el fix ya no re-engancha",
                stayed.reengagedFrom == -1 && p.getPosition() == 3);
        check("saltado: el nodo sigue sin resolver y sin re-enganche",
                !p.isResolved(1) && p.isSkipped(1) && p.pendingCount() == 0);
    }

    // ------------------------------------------------------------------
    // 5. Persistencia
    // ------------------------------------------------------------------

    private static void skipPersists() throws Exception {
        RouteProgress p = pendingOne();
        p.skip(1);
        String json = p.toJson();
        check("el JSON guarda los saltados",
                json.contains("\"skipped\":[1]"));

        RouteProgress back = RouteProgress.fromJson(json, p.route);
        check("round-trip JSON: mismos saltados, posición y resueltos",
                back.isSkipped(1) && back.getPosition() == 3
                        && back.resolvedCount() == 2 && back.skippedCount() == 1
                        && back.pendingCount() == 0);

        // Archivos guardados antes del slice (sin "skipped") cargan con
        // cero saltados: el campo es opcional.
        RouteProgress legacy = RouteProgress.fromJson(
                json.replace(",\"skipped\":[1]", ""), p.route);
        check("JSON viejo sin saltados carga con cero saltados",
                legacy.skippedCount() == 0 && !legacy.isSkipped(1)
                        && legacy.getPosition() == 3 && legacy.resolvedCount() == 2);

        File dir = Files.createTempDirectory("route-escape").toFile();
        File dest = RouteProgress.fileFor(dir, p.route.romKey);
        p.save(dest);
        RouteProgress loaded = RouteProgress.load(dest, p.route);
        check("carga desde archivo: mismo salto",
                loaded != null && loaded.isSkipped(1) && loaded.skippedCount() == 1);
        for (File f : dir.listFiles()) f.delete();
        dir.delete();

        expectFormat("saltado fuera de rango se rechaza",
                json.replace("\"skipped\":[1]", "\"skipped\":[9]"), p.route);
        expectFormat("saltados sin orden se rechazan",
                json.replace("\"skipped\":[1]", "\"skipped\":[1,0]"), p.route);
        expectFormat("saltado ya resuelto se rechaza",
                json.replace("\"skipped\":[1]", "\"skipped\":[0]"), p.route);
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
