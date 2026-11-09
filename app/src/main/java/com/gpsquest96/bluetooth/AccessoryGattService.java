package com.gpsquest96.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import com.gpsquest96.gps.WeatherData;

import java.util.UUID;

/**
 * Servicio GATT para un accesorio BLE (RULES.md §7).
 * <p>
 * Funcionalidades:
 * - LED RGB atmosférico: Color según clima (Azul=Lluvia, Blanco=Neblina, Amarillo=Sol)
 * - Botón físico → Botón "A": Mapeo directo al botón A del emulador
 *   (avanzar texto, confirmar opciones, interactuar con mapa, usar MOs)
 * - Triggers de combate: Destello Multicolor (captura), Parpadeo (veneno/quemadura)
 * - Vibración: Respuesta háptica en eventos críticos
 * <p>
 * El accesorio es estrictamente opcional y no afecta la progresión.
 */
public class AccessoryGattService {

    private static final String TAG = "AccessoryGattService";

    // UUIDs del accesorio BLE (ingeniería inversa de la comunidad)
    private static final UUID SERVICE_BUTTON = UUID.fromString("0000fef1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_BUTTON = UUID.fromString("00000010-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_LED = UUID.fromString("00000011-0000-1000-8000-00805f9b34fb");

    private Context context;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic ledCharacteristic;
    private BluetoothGattCharacteristic buttonCharacteristic;
    private Vibrator vibrator;

    private Handler handler;
    private boolean isConnected = false;

    // Estado del LED
    private LedColor currentLedColor = LedColor.OFF;
    private boolean isBlinking = false;
    private Runnable blinkRunnable;

    public enum LedColor {
        OFF(0x00, 0x00, 0x00),
        RED(0xFF, 0x00, 0x00),
        GREEN(0x00, 0xFF, 0x00),
        BLUE(0x00, 0x00, 0xFF),
        YELLOW(0xFF, 0xFF, 0x00),
        WHITE(0xFF, 0xFF, 0xFF),
        MULTICOLOR(0xFF, 0x00, 0xFF);

        public final int r, g, b;

        LedColor(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    /**
     * Interfaz de eventos del accesorio BLE.
     * RULES.md §7: El botón físico se mapea como botón "A" del emulador.
     */
    public interface AccessoryListener {
        void onConnected();
        void onDisconnected();
        /**
         * Botón físico presionado → Botón "A" del emulador.
         * Permite: avanzar texto, confirmar opciones, interactuar con mapa,
         * usar Movimientos Ocultos (Corte, Surf, etc.)
         */
        void onButtonAPressed();
        void onError(String message);
    }

    private AccessoryListener listener;

    public AccessoryGattService(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void setListener(AccessoryListener listener) {
        this.listener = listener;
    }

    /**
     * Conecta con el accesorio BLE.
     */
    public void connect(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        }
    }

    /**
     * Desconecta el dispositivo.
     */
    public void disconnect() {
        stopBlinking();
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        isConnected = false;
    }

    /**
     * Establece el color del LED según el clima (RULES.md §7).
     * - Azul: Lluvia
     * - Blanco: Neblina
     * - Amarillo: Día despejado
     */
    public void setWeatherLed(WeatherData.WeatherCondition condition) {
        switch (condition) {
            case RAIN:
                setLedColor(LedColor.BLUE);
                break;
            case FOG:
                setLedColor(LedColor.WHITE);
                break;
            case CLEAR:
                setLedColor(LedColor.YELLOW);
                break;
            case CLOUDY:
                setLedColor(LedColor.GREEN);
                break;
            case THUNDERSTORM:
                setLedColor(LedColor.MULTICOLOR);
                break;
            default:
                setLedColor(LedColor.OFF);
                break;
        }
    }

    /**
     * Establece un color fijo del LED.
     */
    public void setLedColor(LedColor color) {
        currentLedColor = color;
        isBlinking = false;
        stopBlinking();
        writeLedColor(color);
    }

    /**
     * Destello Multicolor de celebración (captura exitosa - RULES.md §7).
     * LED: Destello multicolor continuo
     * Vibración: Patrón de celebración
     */
    public void triggerCaptureSuccess() {
        stopBlinking();
        isBlinking = true;
        final LedColor[] colors = {LedColor.RED, LedColor.GREEN, LedColor.BLUE, LedColor.YELLOW};
        final int[] index = {0};

        blinkRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isBlinking) return;
                writeLedColor(colors[index[0] % colors.length]);
                index[0]++;
                handler.postDelayed(this, 200);
            }
        };
        handler.post(blinkRunnable);

        // Vibración de celebración
        vibratePattern(new long[]{0, 100, 50, 100, 50, 200});
    }

    /**
     * Parpadeo intermitente de alerta (veneno/quemadura - RULES.md §7).
     * LED: Parpadeo blanco intermitente (genérico, no usa color del síntoma)
     * Vibración: Pulso intermitente de alerta
     */
    public void triggerStatusAlert() {
        stopBlinking();
        isBlinking = true;
        final boolean[] state = {true};

        blinkRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isBlinking) return;
                if (state[0]) {
                    writeLedColor(LedColor.WHITE);
                } else {
                    writeLedColor(LedColor.OFF);
                }
                state[0] = !state[0];
                handler.postDelayed(this, 500);
            }
        };
        handler.post(blinkRunnable);

        // Vibración de alerta
        vibratePattern(new long[]{0, 200, 300, 200});
    }

    /**
     * Detiene el parpadeo y apaga el LED.
     */
    public void stopBlinking() {
        isBlinking = false;
        if (blinkRunnable != null) {
            handler.removeCallbacks(blinkRunnable);
            blinkRunnable = null;
        }
    }

    /**
     * Envía un pulso de vibración simple (confirmación de botón A).
     */
    public void vibrateButtonPress() {
        vibratePattern(new long[]{0, 50});
    }

    private void writeLedColor(LedColor color) {
        if (ledCharacteristic == null || bluetoothGatt == null) return;

        byte[] data = new byte[]{(byte) color.r, (byte) color.g, (byte) color.b};
        ledCharacteristic.setValue(data);
        bluetoothGatt.writeCharacteristic(ledCharacteristic);
    }

    private void vibratePattern(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        // Toggle de ajustes: sin vibración si el usuario la desactivó.
        if (!context.getSharedPreferences("gpsquest96_prefs", Context.MODE_PRIVATE)
                .getBoolean("vibration_enabled", true)) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    private BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Accesorio BLE conectado");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Accesorio BLE desconectado");
                isConnected = false;
                if (listener != null) {
                    listener.onDisconnected();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_BUTTON);
                if (service != null) {
                    ledCharacteristic = service.getCharacteristic(CHAR_LED);
                    buttonCharacteristic = service.getCharacteristic(CHAR_BUTTON);
                    isConnected = true;
                    Log.i(TAG, "Servicios del accesorio BLE descubiertos");
                    if (listener != null) {
                        listener.onConnected();
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // Botón físico presionado → Botón "A" del emulador (RULES.md §7)
            if (characteristic.getUuid().equals(CHAR_BUTTON)) {
                Log.i(TAG, "Botón físico del accesorio BLE → Botón A");
                // Vibración de confirmación
                vibrateButtonPress();
                if (listener != null) {
                    listener.onButtonAPressed();
                }
            }
        }
    };
}
