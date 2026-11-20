package hosttest;

import com.gpsquest96.emulator.WildEncounterGate;
import com.gpsquest96.emulator.WildEncounterGate.Decision;

/**
 * Host test de la puerta de disparo fiel por paso real (spec 009, slice 3).
 * La puerta solo decide si se puede llamar a la rutina del juego: estado
 * MAP (016), tabla en el mapa actual y fuera de tregua. No tira dados.
 */
public class WildEncounterGateHostTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean cond, String name) {
        checks++;
        if (!cond) {
            System.out.println("FAIL: " + name);
            failures++;
        }
    }

    public static void main(String[] args) {
        // Camino feliz: mapa + tabla + sin tregua.
        check(WildEncounterGate.decide(true, true, false) == Decision.ALLOW,
                "mapa+tabla+sin tregua permite");

        // Sin MAP no se dispara: batalla, texto, menu, transicion, intro.
        check(WildEncounterGate.decide(false, true, false) == Decision.NOT_MAP,
                "sin MAP bloquea aunque haya tabla");
        check(WildEncounterGate.decide(false, false, false) == Decision.NOT_MAP,
                "sin MAP bloquea aunque no haya tabla");

        // Tregua de 180 s tras un evento: ni un paso mas dispara.
        check(WildEncounterGate.decide(true, true, true) == Decision.IN_TRUCE,
                "en tregua bloquea aunque haya mapa y tabla");

        // Sin tabla no hay evento (RF-01/RF-05): interiores, ciudades.
        check(WildEncounterGate.decide(true, false, false) == Decision.NO_TABLE,
                "sin tabla bloquea aunque haya mapa");

        // Orden de la puerta: el estado manda sobre la tregua y la tabla.
        check(WildEncounterGate.decide(false, true, true) == Decision.NOT_MAP,
                "sin MAP manda sobre tregua");
        check(WildEncounterGate.decide(true, false, true) == Decision.IN_TRUCE,
                "tregua manda sobre falta de tabla");

        System.out.println("WildEncounterGateHostTest: " + checks
                + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
