package hosttest;

import android.location.Location;

import com.gpsquest96.gps.GeofenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test de spec 015/B5 — geovallas de agua desde OSM.
 * <p>
 * Lo que se prueba sin POCO ni red:
 *  1. La distancia se mide al **borde** de la polilínea, no al primer vértice.
 *  2. El umbral de seguridad es 20 m (RULES §2).
 *  3. Varios listeners reciben el evento (regresión: el último registrado
 *     dejaba mudo al anterior).
 *  4. `replaceWaterZones` sustituye y devuelve el número real de zonas.
 */
public class GeofenceHostTest {

    private static int checks = 0;

    public static void main(String[] args) {
        polylineDistance();
        proximityThreshold();
        bothListenersNotified();
        replaceWaterZonesCounts();
        System.out.println("GEOFENCE HOST TEST OK (" + checks + " comprobaciones)");
    }

    /** Río recto de ~222 m; el jugador está al lado del segmento medio. */
    private static void polylineDistance() {
        double[] lats = {0.0, 0.0, 0.0};
        double[] lons = {0.000, 0.001, 0.002};
        GeofenceManager.WaterZone zone =
                new GeofenceManager.WaterZone("rio", lats, lons, 0f);

        Location middle = at(0.000135, 0.001); // ~15 m al norte del centro
        float d = zone.distanceMeters(middle);
        check("borde medio < 20 m (fue " + d + ")", d < 20.0f);

        // A 30 m del borde: fuera.
        Location far = at(0.000270, 0.001);
        check("borde medio a 30 m queda fuera", zone.distanceMeters(far) > 20.0f);

        // Cerca del extremo: 10 m al norte del último vértice.
        Location end = at(0.000090, 0.002);
        check("extremo cuenta como borde", zone.distanceMeters(end) < 20.0f);
    }

    private static void proximityThreshold() {
        GeofenceManager gm = new GeofenceManager();
        List<GeofenceManager.WaterZone> zones = new ArrayList<>();
        zones.add(new GeofenceManager.WaterZone("lago",
                new double[]{0.0, 0.0, 0.001, 0.001},
                new double[]{0.0, 0.001, 0.001, 0.0}, 0f));
        gm.replaceWaterZones(zones);

        gm.updatePlayerLocation(at(0.0, 0.0005), null);   // sobre la orilla
        check("sobre la orilla => nearWater", gm.isNearWater());

        gm.updatePlayerLocation(at(0.0015, 0.0005), null); // ~167 m al norte
        check("lejos => sin nearWater", !gm.isNearWater());
    }

    private static void bothListenersNotified() {
        GeofenceManager gm = new GeofenceManager();
        List<GeofenceManager.WaterZone> zones = new ArrayList<>();
        zones.add(new GeofenceManager.WaterZone("rio",
                new double[]{0.0, 0.0}, new double[]{0.0, 0.002}, 0f));
        gm.replaceWaterZones(zones);

        final int[] a = {0};
        final int[] b = {0};
        gm.addGeofenceListener(new GeofenceManager.GeofenceListener() {
            public void onNearWater(boolean nearWater) { if (nearWater) a[0]++; }
            public void onEnterBlockedZone(GeofenceManager.StoryZone zone) { }
            public void onExitBlockedZone() { }
        });
        gm.addGeofenceListener(new GeofenceManager.GeofenceListener() {
            public void onNearWater(boolean nearWater) { if (nearWater) b[0]++; }
            public void onEnterBlockedZone(GeofenceManager.StoryZone zone) { }
            public void onExitBlockedZone() { }
        });

        gm.updatePlayerLocation(at(0.00005, 0.001), null);
        check("listener 1 recibe nearWater", a[0] == 1);
        check("listener 2 recibe nearWater (no se pisa)", b[0] == 1);

        gm.updatePlayerLocation(at(0.0015, 0.001), null);
        check("ambos reciben el 'despejado'", !gm.isNearWater());
    }

    private static void replaceWaterZonesCounts() {
        GeofenceManager gm = new GeofenceManager();
        List<GeofenceManager.WaterZone> zones = new ArrayList<>();
        zones.add(new GeofenceManager.WaterZone("a", 0.0, 0.0, 0f));
        zones.add(new GeofenceManager.WaterZone("b", 0.0, 0.0, 0f));
        check("replace devuelve 2", gm.replaceWaterZones(zones) == 2);
        check("getWaterZoneCount == 2", gm.getWaterZoneCount() == 2);
        gm.replaceWaterZones(new ArrayList<GeofenceManager.WaterZone>());
        check("reemplazo vacío limpia", gm.getWaterZoneCount() == 0);
    }

    private static Location at(double lat, double lon) {
        Location l = new Location("test");
        l.setLatitude(lat);
        l.setLongitude(lon);
        return l;
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            throw new AssertionError("FALLO: " + name);
        }
        System.out.println("  ok " + name);
    }
}
