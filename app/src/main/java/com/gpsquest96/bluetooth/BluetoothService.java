package com.gpsquest96.bluetooth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.gpsquest96.R;
import com.gpsquest96.ui.MainActivity;

/**
 * Servicio de fondo para la conexión Bluetooth LE (RULES.md §7).
 * <p>
 * Mantiene la conexión GATT con el accesorio BLE activa en segundo plano,
 * permitiendo que el LED RGB y el botón físico funcionen aunque la app
 * no esté en primer plano.
 * <p>
 * Características:
 * - Reconexión automática si se pierde la conexión
 * - Notificación persistente para mantener el servicio vivo
 * - Eventos de botón "A" y LED atmosférico
 */
public class BluetoothService extends Service {

    private static final String TAG = "BluetoothService";
    private static final String CHANNEL_ID = "gpsquest96_bluetooth";
    private static final int NOTIFICATION_ID = 1002;

    private BluetoothManager bluetoothManager;
    private AccessoryGattService accessoryService;

    private BluetoothDevice connectedDevice;
    private boolean shouldReconnect = false;

    public interface BluetoothServiceCallback {
        void onDeviceConnected(BluetoothDevice device);
        void onDeviceDisconnected();
        void onButtonAPressed();
        void onError(String message);
    }

    private BluetoothServiceCallback callback;

    public void setBluetoothServiceCallback(BluetoothServiceCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "BluetoothService creado");

        bluetoothManager = new BluetoothManager(this);
        accessoryService = new AccessoryGattService(this);

        createNotificationChannel();
        try {
            // Android 10+ exige tipo explícito; Android 14 lo valida contra el Manifest.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification("Esperando dispositivo..."),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Esperando dispositivo..."));
            }
        } catch (Throwable t) {
            // Sin BLUETOOTH_CONNECT el FGS no es válido en Android 14.
            Log.e(TAG, "startForeground rechazado (sin permiso BLE)", t);
            stopSelf();
            return;
        }

        setupAccessoryListener();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "BluetoothService iniciado");
        return START_STICKY;
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public BluetoothService getService() {
            return BluetoothService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shouldReconnect = false;
        disconnect();
        Log.i(TAG, "BluetoothService destruido");
    }

    /**
     * Inicia el escaneo de accesorios BLE.
     */
    public void startScan() {
        if (!bluetoothManager.isBluetoothAvailable()) {
            Log.w(TAG, "Bluetooth no disponible");
            if (callback != null) {
                callback.onError("Bluetooth no disponible");
            }
            return;
        }

        updateNotification("Buscando accesorio BLE...");

        bluetoothManager.startScan(new BluetoothManager.ScanListener() {
            @Override
            public void onDeviceFound(BluetoothDevice device) {
                Log.i(TAG, "Accesorio BLE encontrado: " + device.getName());
                connectToDevice(device);
            }

            @Override
            public void onScanComplete(java.util.List<BluetoothDevice> devices) {
                if (devices.isEmpty()) {
                    updateNotification("No se encontraron dispositivos");
                }
            }

            @Override
            public void onScanFailed(String error) {
                Log.e(TAG, "Error de escaneo: " + error);
                updateNotification("Error de escaneo");
            }
        });
    }

    /**
     * Conecta con un accesorio BLE.
     */
    public void connectToDevice(BluetoothDevice device) {
        connectedDevice = device;
        shouldReconnect = true;

        updateNotification("Conectando con " + device.getName() + "...");
        accessoryService.connect(device);
    }

    /**
     * Desconecta el dispositivo actual.
     */
    public void disconnect() {
        shouldReconnect = false;
        accessoryService.disconnect();
        updateNotification("Desconectado");
    }

    /**
     * Configura el listener del accesorio BLE.
     */
    private void setupAccessoryListener() {
        accessoryService.setListener(new AccessoryGattService.AccessoryListener() {
            @Override
            public void onConnected() {
                Log.i(TAG, "Accesorio BLE conectado");
                updateNotification("Accesorio BLE conectado");
                if (callback != null && connectedDevice != null) {
                    callback.onDeviceConnected(connectedDevice);
                }
            }

            @Override
            public void onDisconnected() {
                Log.i(TAG, "Accesorio BLE desconectado");
                updateNotification("Desconectado - Reconectando...");
                if (callback != null) {
                    callback.onDeviceDisconnected();
                }

                // Reconexión automática
                if (shouldReconnect && connectedDevice != null) {
                    reconnect();
                }
            }

            @Override
            public void onButtonAPressed() {
                Log.i(TAG, "Botón A presionado");
                if (callback != null) {
                    callback.onButtonAPressed();
                }
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Error: " + message);
                if (callback != null) {
                    callback.onError(message);
                }
            }
        });
    }

    /**
     * Intenta reconectar con el dispositivo.
     */
    private void reconnect() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (shouldReconnect && connectedDevice != null) {
                    Log.i(TAG, "Intentando reconexión...");
                    accessoryService.connect(connectedDevice);
                }
            }
        }, 3000); // Esperar 3 segundos antes de reconectar
    }

    /**
     * Obtiene el servicio del accesorio BLE.
     */
    public AccessoryGattService getAccessoryService() {
        return accessoryService;
    }

    /**
     * Verifica si hay un dispositivo conectado.
     */
    public boolean isConnected() {
        return accessoryService.isConnected();
    }

    /**
     * Crea el canal de notificaciones (requerido para Android 8.0+).
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GPS Quest '96 Bluetooth",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Conexión con accesorio BLE");

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Construye la notificación de primer plano.
     */
    private Notification buildNotification(String status) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Quest '96")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    /**
     * Actualiza la notificación de primer plano.
     */
    private void updateNotification(String status) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }
}
