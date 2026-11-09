package com.gpsquest96.emulator;

/**
 * Gestor del Sistema de Control Dual (RULES.md Â§2, Â§3).
 * <p>
 * Administra la alternancia entre:
 * - Modo GPS Real: Usa coordenadas del sensor fÃ­sico
 * - Modo SofÃ¡: Usa coordenadas simuladas del joystick virtual
 * <p>
 * Reglas de seguridad (RULES.md Â§2):
 * - Si el GPS detecta proximidad a agua (<20m), se fuerza Modo SofÃ¡
 * - El podÃ³metro solo acumula pasos en Modo GPS Real (RULES.md Â§3)
 */
public class ControlModeManager {

    public enum ControlMode {
        GPS_REAL,
        SOFA
    }

    private ControlMode currentMode = ControlMode.GPS_REAL;
    private boolean gpsNearWater = false;
    private boolean gpsEnabled = false;

    // Coordenadas simuladas (Modo SofÃ¡) - Xalapa Centro por defecto
    private double simLatitude = 19.5275;
    private double simLongitude = -96.9242;

    // Coordenadas reales del GPS (Modo GPS Real)
    private double realLatitude = simLatitude;
    private double realLongitude = simLongitude;

    // Callback para notificar cambios de modo
    private ModeChangeListener listener;

    public interface ModeChangeListener {
        void onModeChanged(ControlMode newMode);
    }

    public void setModeChangeListener(ModeChangeListener listener) {
        this.listener = listener;
    }

    /**
     * Cambia el modo de control.
     * No se puede activar GPS Real si hay riesgo de agua cercana.
     */
    public boolean setMode(ControlMode mode) {
        if (mode == ControlMode.GPS_REAL && gpsNearWater) {
            // RULES.md Â§2: Forzar Modo SofÃ¡ en zonas de riesgo
            return false;
        }

        if (mode == ControlMode.GPS_REAL && !gpsEnabled) {
            return false;
        }

        currentMode = mode;
        if (listener != null) {
            listener.onModeChanged(mode);
        }
        return true;
    }

    /**
     * Alterna entre modos.
     */
    public ControlMode toggleMode() {
        ControlMode newMode = (currentMode == ControlMode.GPS_REAL)
                ? ControlMode.SOFA
                : ControlMode.GPS_REAL;
        setMode(newMode);
        return currentMode;
    }

    /**
     * Actualiza el estado de proximidad a agua.
     * Si se detecta agua cerca, se fuerza Modo SofÃ¡ automÃ¡ticamente.
     */
    public void setGpsNearWater(boolean nearWater) {
        this.gpsNearWater = nearWater;
        if (nearWater && currentMode == ControlMode.GPS_REAL) {
            // RULES.md Â§2: Forzar cambio a Modo SofÃ¡
            currentMode = ControlMode.SOFA;
            if (listener != null) {
                listener.onModeChanged(ControlMode.SOFA);
            }
        }
    }

    /**
     * Actualiza las coordenadas simuladas (Modo SofÃ¡).
     */
    public void updateSimulatedPosition(double lat, double lon) {
        this.simLatitude = lat;
        this.simLongitude = lon;
    }

    /**
     * Establece las coordenadas reales del GPS (Modo GPS Real).
     * Llamado desde LocationService.onLocationChanged().
     */
    public void setRealLocation(double lat, double lon) {
        this.realLatitude = lat;
        this.realLongitude = lon;
    }

    /**
     * Obtiene las coordenadas reales del GPS (Modo GPS Real).
     */
    public double[] getRealLocation() {
        return new double[]{realLatitude, realLongitude};
    }

    /**
     * Obtiene las coordenadas actuales segÃºn el modo activo.
     * En GPS_REAL devuelve la ubicación real del GPS; en SOFA, las simuladas.
     */
    public double[] getCurrentCoordinates() {
        if (currentMode == ControlMode.SOFA) {
            return new double[]{simLatitude, simLongitude};
        }
        // GPS_REAL: devolver ubicación real del LocationService
        return new double[]{realLatitude, realLongitude};
    }

    public ControlMode getCurrentMode() {
        return currentMode;
    }

    public boolean isGpsNearWater() {
        return gpsNearWater;
    }

    public void setGpsEnabled(boolean enabled) {
        this.gpsEnabled = enabled;
    }

    public boolean isGpsEnabled() {
        return gpsEnabled;
    }

    /**
     * Verifica si el podÃ³metro debe acumular pasos.
     * RULES.md Â§3: Solo en Modo GPS Real con velocidad de caminata.
     */
    public boolean shouldCountSteps() {
        return currentMode == ControlMode.GPS_REAL && gpsEnabled;
    }
}


