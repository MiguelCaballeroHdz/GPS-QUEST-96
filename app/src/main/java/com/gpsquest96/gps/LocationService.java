package com.gpsquest96.gps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.gpsquest96.emulator.ControlModeManager;

/**
 * Servicio GPS en primer plano (STACK.md + RULES.md Â§2, Â§3).
 * API 19+ (KitKat) compatible, bajo consumo: 5s / 5m.
 */
public class LocationService extends Service implements LocationListener {

    private static final String TAG = "LocationService";
    private static final String CHANNEL_ID = "GPS_Quest_96_Location";
    private static final int NOTIFICATION_ID = 1;

    private static final long MIN_TIME_MS = 5000L;
    private static final float MIN_DISTANCE_M = 5.0f;
    // 7 km/h -> m/s (velocidad caminata humana, RULES Â§3)
    private static final float WALK_SPEED_MAX_MS = 7.0f / 3.6f;

    private LocationManager locationManager;
    private GeofenceManager geofenceManager;
    private ControlModeManager controlModeManager;
    private int[] eventFlags = new int[256];

    private volatile Location lastLocation;
    private volatile boolean walkingActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            // Android 14: FGS tipo location sin permiso â†’ SecurityException.
            Log.e(TAG, "startForeground rechazado (sin permiso de ubicación)", t);
            stopSelf();
            return;
        }
        if (geofenceManager == null) geofenceManager = new GeofenceManager();
        if (controlModeManager == null) controlModeManager = new ControlModeManager();
        attachGeofenceListener();
        startTracking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (locationManager == null) startTracking();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTracking();
        lastLocation = null;
        walkingActive = false;
        super.onDestroy();
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ---- InyecciÃ³n desde GameEngine/MainActivity (modular, sin singleton cruzado) ----
    public void setGeofenceManager(GeofenceManager mgr) {
        this.geofenceManager = mgr;
        attachGeofenceListener();
    }

    public void setControlModeManager(ControlModeManager mgr) {
        this.controlModeManager = mgr;
        attachGeofenceListener();
    }

    public void setEventFlags(int[] flags) {
        this.eventFlags = flags != null ? flags : new int[256];
    }

    public Location getLastLocation() { return lastLocation; }
    public boolean isWalkingActive() { return walkingActive; }

    // ---- GPS ----
    private void startTracking() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Log.e(TAG, "LocationManager no disponible");
            return;
        }
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.w(TAG, "GPS_PROVIDER deshabilitado");
                if (controlModeManager != null) controlModeManager.setGpsEnabled(false);
                return;
            }
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, this);
            Log.i(TAG, "Rastreo GPS iniciado (5s/5m)");
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permiso LOCATION, rastreo no iniciado", e);
            if (controlModeManager != null) controlModeManager.setGpsEnabled(false);
        }
    }

    private void stopTracking() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException e) {
                Log.w(TAG, "removeUpdates sin permiso", e);
            }
            locationManager = null;
        }
        walkingActive = false;
    }

    // ---- LocationListener ----
    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        lastLocation = location;

        // 1. Geovallas Â§2 + Â§4
        if (geofenceManager != null) {
            geofenceManager.updatePlayerLocation(location, eventFlags);
        }

        // 2. Filtro podÃ³metro Â§3: solo caminata < 7 km/h
        boolean walking = true;
        if (location.hasSpeed()) {
            walking = location.getSpeed() < WALK_SPEED_MAX_MS;
        }
        walkingActive = walking;
        if (controlModeManager != null) {
            controlModeManager.setGpsEnabled(walking);
            controlModeManager.setRealLocation(location.getLatitude(), location.getLongitude());
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) startTracking();
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider) && controlModeManager != null) {
            controlModeManager.setGpsEnabled(false);
        }
    }

    // ---- Geovalla -> Modo SofÃ¡ Â§2 ----
    private boolean geofenceListenerAttached = false;

    private void attachGeofenceListener() {
        if (geofenceManager == null || controlModeManager == null) return;
        if (geofenceListenerAttached) return;
        geofenceListenerAttached = true;
        // addGeofenceListener, no set: GameEngine ya reenvÃ­a el evento al HUD;
        // setGeofenceListener borraba esa cadena y dejaba mudo al HUD.
        geofenceManager.addGeofenceListener(new GeofenceManager.GeofenceListener() {
            @Override
            public void onNearWater(boolean nearWater) {
                controlModeManager.setGpsNearWater(nearWater);
            }
            @Override
            public void onEnterBlockedZone(GeofenceManager.StoryZone zone) { }
            @Override
            public void onExitBlockedZone() { }
        });
    }

    // ---- Foreground (API 19 seguro) ----
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Servicio de Ubicación", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Quest '96")
                .setContentText("Rastreador de mapa activo (Mundo Real)")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }
}

