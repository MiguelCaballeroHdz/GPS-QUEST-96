package com.gpsquest96.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Gestor de Bluetooth Low Energy (RULES.md §7).
 * <p>
 * Administra el escaneo y conexión con dispositivos BLE,
 * específicamente un accesorio BLE opcional.
 * <p>
 * Nota: El accesorio es estrictamente opcional y no afecta
 * la progresión del juego (RULES.md §7 - Neutralidad en Gameplay).
 */
public class BluetoothManager {

    private static final String TAG = "BluetoothManager";

    // UUIDs del accesorio BLE (documentación de la comunidad)
    private static final String ACCESSORY_NAME = "Pokemon GO Plus";
    private static final String ACCESSORY_PREFIX = "Pok";

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private ScanCallback scanCallback;

    private List<BluetoothDevice> discoveredDevices;
    private boolean isScanning = false;

    public interface ScanListener {
        void onDeviceFound(BluetoothDevice device);
        void onScanComplete(List<BluetoothDevice> devices);
        void onScanFailed(String error);
    }

    public BluetoothManager(Context context) {
        this.context = context;
        this.discoveredDevices = new ArrayList<>();

        android.bluetooth.BluetoothManager systemBtManager =
                (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (systemBtManager != null) {
            bluetoothAdapter = systemBtManager.getAdapter();
        }
    }

    /**
     * Verifica si el Bluetooth está disponible y habilitado.
     */
    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /**
     * Inicia el escaneo de dispositivos BLE.
     */
    public void startScan(ScanListener listener) {
        if (!isBluetoothAvailable()) {
            listener.onScanFailed("Bluetooth no disponible");
            return;
        }

        if (isScanning) {
            return;
        }

        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) {
            listener.onScanFailed("Scanner BLE no disponible");
            return;
        }

        discoveredDevices.clear();
        isScanning = true;

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = device.getName();

                // Filtrar por nombre del dispositivo
                if (name != null && (name.contains(ACCESSORY_NAME) || name.startsWith(ACCESSORY_PREFIX))) {
                    if (!discoveredDevices.contains(device)) {
                        discoveredDevices.add(device);
                        listener.onDeviceFound(device);
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                isScanning = false;
                listener.onScanFailed("Error de escaneo: " + errorCode);
            }
        };

        leScanner.startScan(scanCallback);
        Log.i(TAG, "Escaneo BLE iniciado");
    }

    /**
     * Detiene el escaneo.
     */
    public void stopScan() {
        if (leScanner != null && scanCallback != null && isScanning) {
            leScanner.stopScan(scanCallback);
            isScanning = false;
            Log.i(TAG, "Escaneo BLE detenido");
        }
    }

    /**
     * Obtiene la lista de dispositivos descubiertos.
     */
    public List<BluetoothDevice> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices);
    }

    /**
     * Verifica si se está escaneando.
     */
    public boolean isScanning() {
        return isScanning;
    }
}
