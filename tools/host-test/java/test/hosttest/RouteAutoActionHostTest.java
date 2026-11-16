package hosttest;

import com.gpsquest96.route.RouteAutoAction;
import com.gpsquest96.route.RouteAutoAction.Sequence;
import com.gpsquest96.route.RouteAutoAction.Step;
import com.gpsquest96.route.RouteNode;

/**
 * Host test de spec 004 slice 5 (enter/talk automáticos).
 * <p>
 * Sin Android, sin red y sin POCO: compila la clase real
 * {@code com.gpsquest96.route.RouteAutoAction} contra stubs de Android.
 * <p>
 * Cubre:
 *  1. Enrutado R9 por acción: ENTER/TALK → auto-A; USE/BATTLE/WAIT →
 *     Modo Sofá; WALK → nada.
 *  2. Rumbo inicial entre dos puntos (para la cardinal del intento).
 *  3. Cardinal dominante del rumbo → bits de cruceta (mismos cuadrantes
 *     que MainActivity.applyJoypadFromBearing).
 *  4. Constantes del contrato con el joypad (bits de Mmu.setJoypad).
 *  5. Secuencia anti-atasco (R7): 3 intentos, 300 ms entre ellos, ni uno
 *     más; el cuarto intento no existe.
 *  6. Aborto ante cambio de pantalla: fuera de MAP no se intenta nada y
 *     la secuencia aborta (también a mitad de secuencia).
 *  7. Tiempo hasta el próximo intento.
 * <p>
 * No cubre (dispositivo): que el A abra de verdad la puerta en el POCO
 * (§7.7: enter dentro del radio cambia de mapa sin intervención del
 * usuario) ni el aviso háptico en el teléfono. Quedan "pendiente de
 * sesión de calle en POCO".
 */
public class RouteAutoActionHostTest {

    private static int checks = 0;

    public static void main(String[] args) {
        routing();
        bearing();
        dirBits();
        constants();
        sequenceThreeAttempts();
        abortOnScreenChange();
        millisUntilNext();
        System.out.println("ROUTE AUTO ACTION HOST TEST OK (" + checks + " comprobaciones)");
    }

    // ------------------------------------------------------------------
    // 1. Enrutado R9 por acción de nodo
    // ------------------------------------------------------------------

    private static void routing() {
        check("ENTER -> auto-A", RouteAutoAction.kindFor(RouteNode.Action.ENTER)
                == RouteAutoAction.Kind.AUTO_A);
        check("TALK -> auto-A", RouteAutoAction.kindFor(RouteNode.Action.TALK)
                == RouteAutoAction.Kind.AUTO_A);
        check("USE -> Modo Sofá", RouteAutoAction.kindFor(RouteNode.Action.USE)
                == RouteAutoAction.Kind.GO_SOFA);
        check("BATTLE -> Modo Sofá", RouteAutoAction.kindFor(RouteNode.Action.BATTLE)
                == RouteAutoAction.Kind.GO_SOFA);
        check("WAIT -> Modo Sofá", RouteAutoAction.kindFor(RouteNode.Action.WAIT)
                == RouteAutoAction.Kind.GO_SOFA);
        check("WALK -> nada", RouteAutoAction.kindFor(RouteNode.Action.WALK)
                == RouteAutoAction.Kind.NONE);
        check("acción nula -> nada", RouteAutoAction.kindFor(null)
                == RouteAutoAction.Kind.NONE);
    }

    // ------------------------------------------------------------------
    // 2. Rumbo inicial entre dos puntos
    // ------------------------------------------------------------------

    private static void bearing() {
        check("rumbo norte = 0", near(RouteAutoAction.bearingDeg(0, 0, 1, 0), 0f));
        check("rumbo este = 90", near(RouteAutoAction.bearingDeg(0, 0, 0, 1), 90f));
        check("rumbo sur = 180", near(RouteAutoAction.bearingDeg(0, 0, -1, 0), 180f));
        check("rumbo oeste = 270", near(RouteAutoAction.bearingDeg(0, 0, 0, -1), 270f));
        check("rumbo noreste ~ 45", near(RouteAutoAction.bearingDeg(0, 0, 1, 1), 45f));
    }

    private static boolean near(float got, float want) {
        return Math.abs(got - want) <= 1.0f;
    }

    // ------------------------------------------------------------------
    // 3. Cardinal dominante -> bits de cruceta
    // ------------------------------------------------------------------

    private static void dirBits() {
        check("rumbo 0 -> UP (0xB0)",
                RouteAutoAction.dirBitsForBearing(0f) == 0xB0);
        check("rumbo 90 -> RIGHT (0xE0)",
                RouteAutoAction.dirBitsForBearing(90f) == 0xE0);
        check("rumbo 180 -> DOWN (0x70)",
                RouteAutoAction.dirBitsForBearing(180f) == 0x70);
        check("rumbo 270 -> LEFT (0xD0)",
                RouteAutoAction.dirBitsForBearing(270f) == 0xD0);
        check("límite 45 -> RIGHT",
                RouteAutoAction.dirBitsForBearing(45f) == 0xE0);
        check("límite 135 -> DOWN",
                RouteAutoAction.dirBitsForBearing(135f) == 0x70);
        check("límite 225 -> LEFT",
                RouteAutoAction.dirBitsForBearing(225f) == 0xD0);
        check("límite 315 -> UP",
                RouteAutoAction.dirBitsForBearing(315f) == 0xB0);
        check("rumbo -10 envuelve a UP",
                RouteAutoAction.dirBitsForBearing(-10f) == 0xB0);
        check("rumbo 370 envuelve a UP",
                RouteAutoAction.dirBitsForBearing(370f) == 0xB0);
    }

    // ------------------------------------------------------------------
    // 4. Constantes del contrato con el joypad
    // ------------------------------------------------------------------

    private static void constants() {
        check("máximo 3 intentos por nodo", RouteAutoAction.MAX_ATTEMPTS == 3);
        check("intervalo 300 ms", RouteAutoAction.ATTEMPT_INTERVAL_MS == 300L);
        check("háptico 60 ms", RouteAutoAction.HAPTIC_MS == 60L);
        check("bit A = 0x01", RouteAutoAction.BTN_A == 0x01);
        check("bit RIGHT = 0x10", RouteAutoAction.DIR_RIGHT == 0x10);
        check("bit LEFT = 0x20", RouteAutoAction.DIR_LEFT == 0x20);
        check("bit UP = 0x40", RouteAutoAction.DIR_UP == 0x40);
        check("bit DOWN = 0x80", RouteAutoAction.DIR_DOWN == 0x80);
    }

    // ------------------------------------------------------------------
    // 5. Secuencia anti-atasco: 3 intentos de 300 ms, ni uno más
    // ------------------------------------------------------------------

    private static void sequenceThreeAttempts() {
        Sequence s = new Sequence(7);
        check("nueva secuencia: 3 intentos", s.attemptsLeft() == 3);
        check("nueva secuencia: 0 usados", s.attemptsUsed() == 0);
        check("nueva secuencia: no terminada", !s.isDone());
        check("secuencia es para su nodo", s.isFor(7));
        check("secuencia no es para otro nodo", !s.isFor(8));

        check("primer intento en MAP -> PRESS", s.step(1000L, true) == Step.PRESS);
        check("tras 1 intento quedan 2", s.attemptsLeft() == 2);
        check("tras 1 intento usado 1", s.attemptsUsed() == 1);
        check("reintento inmediato -> WAIT", s.step(1000L, true) == Step.WAIT);
        check("1 ms antes -> WAIT", s.step(1299L, true) == Step.WAIT);
        check("a los 300 ms -> PRESS", s.step(1300L, true) == Step.PRESS);
        check("a los 200 ms del 2o -> WAIT", s.step(1500L, true) == Step.WAIT);
        check("a los 300 ms del 2o -> PRESS", s.step(1600L, true) == Step.PRESS);
        check("tras 3 intentos quedan 0", s.attemptsLeft() == 0);
        check("tras 3 intentos terminada", s.isDone());
        check("cuarto intento no existe -> DONE", s.step(2000L, true) == Step.DONE);
        check("DONE es estable", s.step(5000L, true) == Step.DONE);
    }

    // ------------------------------------------------------------------
    // 6. Aborto ante cambio de pantalla (fuera de MAP)
    // ------------------------------------------------------------------

    private static void abortOnScreenChange() {
        Sequence s = new Sequence(3);
        check("fuera de MAP no se intenta -> ABORTED",
                s.step(1000L, false) == Step.ABORTED);
        check("abortada: isAborted", s.isAborted());
        check("abortada: isDone", s.isDone());
        check("abortada: se queda ABORTED",
                s.step(1500L, true) == Step.ABORTED);

        Sequence mid = new Sequence(4);
        check("1er intento en MAP -> PRESS", mid.step(1000L, true) == Step.PRESS);
        check("cambio de pantalla a mitad -> ABORTED",
                mid.step(1300L, false) == Step.ABORTED);

        Sequence manual = new Sequence(5);
        manual.abort();
        check("abort() manual marca terminada", manual.isDone());
        check("abort() manual -> ABORTED",
                manual.step(1000L, true) == Step.ABORTED);
    }

    // ------------------------------------------------------------------
    // 7. Tiempo hasta el próximo intento
    // ------------------------------------------------------------------

    private static void millisUntilNext() {
        Sequence s = new Sequence(1);
        check("primer intento toca ya", s.millisUntilNextAttempt(5000L) == 0L);
        s.step(1000L, true); // PRESS: próximo a 1300
        check("tras 1 intento faltan 200 ms",
                s.millisUntilNextAttempt(1100L) == 200L);
        check("al llegar la hora faltan 0 ms",
                s.millisUntilNextAttempt(1300L) == 0L);
    }

    private static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  OK   " : "  FALLO") + "  " + name);
        if (!ok) throw new AssertionError("Fallo: " + name);
    }
}
