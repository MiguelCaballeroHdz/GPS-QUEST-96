package com.gpsquest96.emulator;

/**
 * Puerta de disparo fiel por paso real (spec 009, slice 3).
 *
 * Decisión pura (sin Android, testeable en host) sobre si un paso real
 * puede intentar un encuentro:
 * - el emulador debe estar en el mapa de exploración (estado MAP de la
 *   spec 016: overworld; sin batalla, texto, menú ni transición);
 * - el mapa actual debe tener tabla de encuentro (sin tabla no hay
 *   evento, RF-01/RF-05);
 * - no debe estar en tregua (180 s tras cada evento, SaveManager).
 *
 * La puerta NO tira dados ni elige especie/nivel: eso lo hace la rutina
 * del propio juego (`TryWildEncounter`). Solo dice si se le puede llamar.
 */
public final class WildEncounterGate {

    private WildEncounterGate() {
    }

    public enum Decision {
        ALLOW,
        NOT_MAP,
        NO_TABLE,
        IN_TRUCE
    }

    /**
     * @param inMapState true si el estado del emulador es MAP (016)
     * @param hasTable true si el mapa actual tiene tabla de encuentro
     * @param inTruce true si corre la tregua de 180 s tras un evento
     */
    public static Decision decide(boolean inMapState, boolean hasTable,
                                  boolean inTruce) {
        if (!inMapState) {
            return Decision.NOT_MAP;
        }
        if (inTruce) {
            return Decision.IN_TRUCE;
        }
        if (!hasTable) {
            return Decision.NO_TABLE;
        }
        return Decision.ALLOW;
    }
}
