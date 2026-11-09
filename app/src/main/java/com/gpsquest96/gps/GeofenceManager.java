package com.gpsquest96.gps;

import android.location.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gestor de Geovallas (RULES.md §2, §4).
 * <p>
 * §2 — Seguridad en Entornos de Riesgo:
 * Si las coordenadas GPS se aproximan a un polígono de agua (<20m),
 * el motor fuerza el "Modo Sofá" (Joystick Virtual).
 * <p>
 * §4 — Geovallas de Historia (Anti-Skip):
 * Si el jugador se desplaza a una zona bloqueada por la historia
 * (requiere MO o medalla), se muestra una barrera visual y se
 * pausa la aparición de criaturas.
 */
public class GeofenceManager {

    private static final float WATER_PROXIMITY_THRESHOLD_METERS = 20.0f;

    /** Tope de seguridad: una carga OSM de 150 m no debe crecer sin límite. */
    private static final int MAX_WATER_ZONES = 512;

    private List<WaterZone> waterZones;
    private List<StoryZone> storyZones;

    private boolean nearWater = false;
    private boolean inBlockedStoryZone = false;

    public interface GeofenceListener {
        void onNearWater(boolean nearWater);
        void onEnterBlockedZone(StoryZone zone);
        void onExitBlockedZone();
    }

    /**
     * Varios interesados, no uno: `GameEngine` reenvía al HUD y
     * `LocationService` fuerza Modo Sofá. Con un único listener, el último en
     * registrarse dejaba al otro mudo (defecto real de 015/B5).
     */
    private final List<GeofenceListener> listeners = new CopyOnWriteArrayList<>();

    public GeofenceManager() {
        this.waterZones = new CopyOnWriteArrayList<>();
        this.storyZones = new CopyOnWriteArrayList<>();
    }

    public void setGeofenceListener(GeofenceListener listener) {
        listeners.clear();
        if (listener != null) listeners.add(listener);
    }

    public void addGeofenceListener(GeofenceListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    /**
     * Agrega una zona de agua (río, lago, etc.).
     */
    public void addWaterZone(WaterZone zone) {
        if (zone == null) return;
        if (waterZones.size() >= MAX_WATER_ZONES) return;
        waterZones.add(zone);
    }

    /**
     * Reemplaza las zonas de agua por la última descarga OSM (spec 015/B5).
     * Se llama desde el worker de Overpass: la lista es copy-on-write, así que
     * el hilo de GPS puede recorrerla sin bloquearse.
     *
     * @return zonas efectivamente registradas
     */
    public int replaceWaterZones(List<WaterZone> zones) {
        waterZones.clear();
        if (zones != null) {
            for (WaterZone z : zones) {
                if (waterZones.size() >= MAX_WATER_ZONES) break;
                waterZones.add(z);
            }
        }
        int n = waterZones.size();
        // No se toca `nearWater` a propósito: si el jugador está junto al agua
        // y llega una carga nueva, soltar la protección antes del siguiente
        // fix GPS sería un fallo de seguridad (§2). El próximo
        // updatePlayerLocation recalcula y avisa solo si cambia.
        return n;
    }

    public int getWaterZoneCount() {
        return waterZones.size();
    }

    /**
     * Agrega una zona de historia bloqueada.
     */
    public void addStoryZone(StoryZone zone) {
        if (zone != null) storyZones.add(zone);
    }

    /**
     * Actualiza la posición del jugador y verifica geovallas.
     *
     * @param playerLocation Ubicación actual del jugador
     * @param eventFlags    Variables de progreso de la partida
     */
    public void updatePlayerLocation(Location playerLocation, int[] eventFlags) {
        // §2: Verificar proximidad a agua
        boolean wasNearWater = nearWater;
        nearWater = checkWaterProximity(playerLocation);

        if (nearWater != wasNearWater) {
            for (GeofenceListener l : listeners) {
                l.onNearWater(nearWater);
            }
        }

        // §4: Verificar zonas de historia bloqueadas
        StoryZone blockedZone = checkStoryZones(playerLocation, eventFlags);
        boolean wasInBlockedZone = inBlockedStoryZone;
        inBlockedStoryZone = (blockedZone != null);

        if (inBlockedStoryZone && !wasInBlockedZone) {
            for (GeofenceListener l : listeners) l.onEnterBlockedZone(blockedZone);
        } else if (!inBlockedStoryZone && wasInBlockedZone) {
            for (GeofenceListener l : listeners) l.onExitBlockedZone();
        }
    }

    /**
     * Verifica si el jugador está cerca de una zona de agua.
     * RULES.md §2: Rango menor a 20 metros, medido al borde del agua.
     */
    private boolean checkWaterProximity(Location playerLocation) {
        if (playerLocation == null) return false;
        for (WaterZone zone : waterZones) {
            if (zone.distanceMeters(playerLocation) < WATER_PROXIMITY_THRESHOLD_METERS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verifica si el jugador está en una zona de historia bloqueada.
     * RULES.md §4: Zonas que requieren MO o medalla específica.
     */
    private StoryZone checkStoryZones(Location playerLocation, int[] eventFlags) {
        for (StoryZone zone : storyZones) {
            if (zone.contains(playerLocation) && !zone.isUnlocked(eventFlags)) {
                return zone;
            }
        }
        return null;
    }

    public boolean isNearWater() {
        return nearWater;
    }

    public boolean isInBlockedStoryZone() {
        return inBlockedStoryZone;
    }

    // ═══════════════════════════════════════════════════════
    // CLASES INTERNAS
    // ═══════════════════════════════════════════════════════

    /**
     * Zona de agua (río, lago, etc.).
     * <p>
     * Guarda la geometría real de OSM (polilínea o polígono ya resuelto) y
     * mide la distancia **al borde**, no a un centroide: con centroides, un
     * río largo nunca quedaba a menos de 20 m del punto registrado.
     */
    public static class WaterZone {
        private Location location;
        private float radius;
        private String name;
        private final double[] lats;
        private final double[] lons;

        public WaterZone(String name, double latitude, double longitude, float radius) {
            this(name, new double[]{latitude}, new double[]{longitude}, radius);
        }

        public WaterZone(String name, double[] lats, double[] lons, float radius) {
            this.name = name;
            this.radius = radius;
            this.lats = (lats != null) ? lats.clone() : new double[0];
            this.lons = (lons != null) ? lons.clone() : new double[0];
            this.location = new Location("geofence");
            if (this.lats.length > 0 && this.lons.length > 0) {
                this.location.setLatitude(this.lats[0]);
                this.location.setLongitude(this.lons[0]);
            }
        }

        public Location getLocation() {
            return location;
        }

        public String getName() {
            return name;
        }

        public float getRadius() {
            return radius;
        }

        /**
         * Distancia mínima (m) del jugador al borde de la zona: al vértice o
         * al segmento más cercano. Proyección local equirectangular, error
         * despreciable a la escala de 20 m.
         */
        public float distanceMeters(Location player) {
            if (player == null) return Float.MAX_VALUE;
            int n = Math.min(lats.length, lons.length);
            if (n == 0) return Float.MAX_VALUE;
            double lat0 = player.getLatitude();
            double lon0 = player.getLongitude();
            double mPerLat = 111320.0;
            double mPerLon = 111320.0 * Math.cos(Math.toRadians(lat0));
            double best = Double.MAX_VALUE;
            double px = 0.0, py = 0.0;
            for (int i = 0; i < n; i++) {
                double x = (lons[i] - lon0) * mPerLon;
                double y = (lats[i] - lat0) * mPerLat;
                if (i > 0) {
                    best = Math.min(best, segmentDistance(px, py, x, y));
                }
                best = Math.min(best, Math.hypot(x, y));
                px = x;
                py = y;
            }
            return (float) best;
        }

        /** Distancia del origen al segmento (a→b), en la proyección local. */
        private static double segmentDistance(double ax, double ay, double bx, double by) {
            double dx = bx - ax;
            double dy = by - ay;
            double len2 = dx * dx + dy * dy;
            if (len2 <= 1e-9) return Math.hypot(ax, ay);
            double t = -(ax * dx + ay * dy) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            return Math.hypot(ax + t * dx, ay + t * dy);
        }
    }

    /**
     * Zona de historia bloqueada.
     */
    public static class StoryZone {
        private Location center;
        private float radius;
        private String name;
        private int requiredFlag; // Event Flag requerido para desbloquear

        public StoryZone(String name, double latitude, double longitude, float radius, int requiredFlag) {
            this.name = name;
            this.center = new Location("geofence");
            this.center.setLatitude(latitude);
            this.center.setLongitude(longitude);
            this.radius = radius;
            this.requiredFlag = requiredFlag;
        }

        public boolean contains(Location location) {
            return center.distanceTo(location) <= radius;
        }

        public boolean isUnlocked(int[] eventFlags) {
            if (requiredFlag <= 0) return true;
            if (eventFlags == null) return false;
            if (requiredFlag < eventFlags.length) {
                return eventFlags[requiredFlag] != 0;
            }
            return false;
        }

        public String getName() {
            return name;
        }

        public String getBlockMessage() {
            return "Zona bloqueada: " + name;
        }
    }
}
