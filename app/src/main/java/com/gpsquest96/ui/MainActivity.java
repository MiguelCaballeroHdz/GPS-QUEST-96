package com.gpsquest96.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLUtils;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.gpsquest96.R;
import com.gpsquest96.emulator.ControlModeManager;
import com.gpsquest96.emulator.DatabaseHelper;
import com.gpsquest96.emulator.EmulatorBridge;
import com.gpsquest96.emulator.GameEngine;
import com.gpsquest96.emulator.PuebloProfile;
import com.gpsquest96.emulator.core.EmulationPipeline;
import com.gpsquest96.render.GBMapRenderer;
import com.gpsquest96.bluetooth.BluetoothService;
import com.gpsquest96.gps.GeofenceManager;
import com.gpsquest96.gps.LocationService;
import com.gpsquest96.gps.OsmAccumulator;
import com.gpsquest96.gps.OsmDataFetcher;
import com.gpsquest96.gps.WeatherData;
import com.gpsquest96.gps.WeatherService;
import com.gpsquest96.route.Route;
import com.gpsquest96.route.RouteBundle;
import com.gpsquest96.route.RouteGenerator;
import com.gpsquest96.route.RouteNode;
import com.gpsquest96.utils.LocaleHelper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.opengl.GLES20;

/**
 * Actividad principal de GPS Quest '96.
 * <p>
 * Integra el GameEngine con todos los mÃ³dulos:
 * - CPU LR35902 + MMU + PPU (emulaciÃ³n)
 * - Renderer OpenGL ES 2.0 (visualizaciÃ³n)
 * - GPS + Geovallas (ubicaciÃ³n y seguridad)
 * - Weather Service (clima y modificadores)
 * - Accessory GATT Service (Bluetooth LE)
 * - SaveManager (auto-guardado)
 * <p>
 * HUD con reloj digital, podÃ³metro y clima (RULES.md Â§3).
 * Sistema de Control Dual: GPS Real / Modo SofÃ¡ (RULES.md Â§2).
 */
public class MainActivity extends Activity implements SensorEventListener {

    private GLSurfaceView glSurfaceView;
    private JoystickView joystickView;
    private Button btnModeSwitch;
    private TextView tvModeIndicator;
    private TextView tvHudTime;
    private TextView tvHudSteps;
    private TextView tvHudWeather;
    private TextView tvWeatherBadge;
    private TextView tvSystemTitle;
    private TextView tvPlayerName;
    private TextView tvOptSaves;

    private GameEngine gameEngine;
    private Handler hudHandler;
    private Runnable hudUpdater;

    private int stepCount = 0;

    // PodÃ³metro hardware (TYPE_STEP_COUNTER, acumulativo desde el arranque).
    // Solo cuenta en GPS Real + caminata < 7 km/h (RULES Â§3); el Joystick
    // jamÃ¡s lo toca.
    private SensorManager sensorManager;
    private Sensor stepSensor;
    private float stepBaseline = -1f;

    // Renderizador Overworld Retro GB (reemplaza mapas raster OSM)
    private GameRenderer gameRenderer;
    private DatabaseHelper dbHelper;
    private OsmDataFetcher osmFetcher;
    private volatile String lastOsmStatus = "Red: …";
    private volatile int lastAtlasTiles = 0;
    /**
     * Unión acumulada de OSM (spec 004 slice 2): antes cada celda reemplazaba el
     * conjunto y el mapa "saltaba" al cruzar 111 m. Ahora se acumula sin duplicados.
     */
    private final Object osmLock = new Object();
    private java.util.List<OsmDataFetcher.OsmElement> osmAccumulated =
            new java.util.ArrayList<OsmDataFetcher.OsmElement>();
    private volatile String lastRouteStatus = "Ruta: sin ancla";
    private volatile int routeNodeCount = 0;
    // Buffers reutilizados por el worker (sin basura por frame). Solo vramBuf:
    // el atlas de tiles VRAM (TileAtlas). Los buffers del sprite ROM y el
    // extractor se retiraron en la spec 017 slice 1: el mapa ya no dibuja
    // el sprite extraido de la ROM.
    private final byte[] vramBuf = new byte[8192];
    private long lastAtlasErrorLogMs = 0;

    /**
     * Foto de tiles VRAM en el worker throttled (6 KB por JNI, sin hilos
     * nuevos): el atlas de tiles del mapa (B3b). Corre con y sin red,
     * porque la VRAM no depende de Overpass.
     * <p>
     * synchronized: la llaman el hilo de emulacion y el worker de OSM; ambos
     * comparten vramBuf. El sprite del mapa ya no sale de la ROM (spec 017
     * slice 1: avatar propio de Miguel); el extractor queda sin uso.
     */
    private synchronized void refreshAtlasSnapshot() {
        try {
            if (gameEngine != null && gameEngine.getEmulationPipeline() != null) {
                com.gpsquest96.emulator.core.EmulationPipeline.Mmu mmu =
                        gameEngine.getEmulationPipeline().getMmu();
                // Un cruce JNI (antes: 6.144 + 8.192 byte a byte). Solo el
                // banco 0: el atlas de tiles vive ahi. El banco 1 era del
                // sprite ROM, retirado en la spec 017 slice 1.
                mmu.readVramBlock(0, 0x8000, vramBuf, vramBuf.length);
                byte[] vram = vramBuf;
                com.gpsquest96.render.TileAtlas snapshot =
                        com.gpsquest96.render.TileAtlas.fromVram(
                                vram, mmu.read(com.gpsquest96.render.TileAtlas.BGP_ADDR) & 0xFF);
                lastAtlasTiles = snapshot.getTileCount();
                if (lastOsmStatus != null && lastOsmStatus.contains("· tiles:")) {
                    lastOsmStatus = lastOsmStatus.substring(0, lastOsmStatus.indexOf("· tiles:")) + "· tiles:" + lastAtlasTiles;
                }
                if (tvOptSaves != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvOptSaves.setText("Partidas Guardadas · " + lastOsmStatus);
                        }
                    });
                }
                // Subir pÃ­xeles del atlas a GL (worker â†’ hilo GL, volatile).
                // Solo con tiles reales: una VRAM vacÃ­a darÃ­a un atlas plano
                // (tono 0) y el mapa saldrÃ­a pintado de amarillo.
                if (gameRenderer != null && lastAtlasTiles > 0) {
                    gameRenderer.setAtlasPixels(snapshot.getAtlasPixels());
                }
                // Spec 017 slice 1: el sprite del mapa es el avatar propio
                // de Miguel (AvatarSprites); ya no se identifica ni se
                // compone nada desde la ROM. El GameRenderer elige el cuadro
                // en el hilo GL segun rumbo y movimiento (updateAvatarSprite).
            }
        } catch (Throwable t) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastAtlasErrorLogMs > 5000) {
                lastAtlasErrorLogMs = now;
                android.util.Log.w("AtlasSnapshot", "Snapshot VRAM falló", t);
            }
        }
    }
    private long lastOsmRequestMs = 0;
    private double lastOsmLat = 999.0;
    private double lastOsmLon = 999.0;

    // Pantalla de inicio
    private static final int REQUEST_CODE_OPEN_ROM = 2001;
    private static final int REQUEST_CODE_EXPORT_PROFILE = 2002;
    private static final int REQUEST_CODE_IMPORT_PROFILE = 2003;
    private static final int REQUEST_CODE_EXPORT_ROUTE = 2004;
    private static final int REQUEST_CODE_IMPORT_ROUTE = 2005;
    private View startPanel;
    private Button btnLoadRom;
    private Button btnSettings;
    private View settingsOverlay;
    private ImageButton btnGearOverlay;

    // Bomba de frames con catch-up a TIEMPO REAL en hilo dedicado (fuera del
    // UI). spec 012 RF-01: cada tick mide el dt real, convierte tiempoâ†’frames
    // GB (59.7275 fps de hardware) y ejecuta los que compiten, con tope por
    // tick (RF-04: tras un stall NO se arrastra deuda). GameEngine.update()
    // hace 1 transiciÃ³n JNI por frame (stepFrame); la deriva del postDelayed
    // la absorbe el acumulador, no el juego.
    private static final long FRAME_INTERVAL_MS = 16;
    private static final double GB_FPS = 59.7275;          // fps reales de GB
    private static final long MAX_TICK_NS = 100_000_000L;  // clamp de dt (RF-04)
    private static final int FRAMES_TICK_TOPE = 3;         // tope de frames/tick

    private android.os.HandlerThread emuThread;
    private Handler emuHandler;
    private FramePumperTask framePumper;

    /** Bombeo con acumulador de tiempoâ†’frames GB (spec 012, RF-01/RF-04). */
    private final class FramePumperTask implements Runnable {
        private long lastNs;    // ancla del reloj monotÃ³nico; 0 = sin anclar
        private double deuda;   // fracciÃ³n de frame GB pendiente

        // InstrumentaciÃ³n de diagnÃ³stico (spec 012 slice 1): cadencia real.
        private long dbgTicks = 0, dbgFrames = 0, dbgDtNs = 0;

        @Override
        public void run() {
            try {
                long now = android.os.SystemClock.elapsedRealtimeNanos();
                if (lastNs == 0) {
                    lastNs = now;   // primer tick solo ancla el reloj
                } else {
                    long dt = now - lastNs;
                    lastNs = now;
                    if (dt > MAX_TICK_NS) dt = MAX_TICK_NS; // stall: no herdar deuda
                    deuda += dt * GB_FPS / 1.0e9;
                    int n = (int) deuda;
                    if (n > FRAMES_TICK_TOPE) {
                        n = FRAMES_TICK_TOPE;
                        deuda = 0.0;    // descarta el exceso (RF-04)
                    }
                    deuda -= n;
                    if (n > 0) {
                        for (int i = 0; i < n; i++) {
                            gameEngine.update(); // 1 frame GB por llamada (JNI)
                        }
                    }
                    dbgTicks++;
                    dbgFrames += n;
                    dbgDtNs += dt;
                    if (dbgTicks % 512 == 0) {
                        Log.d("GameEngine", String.format(
                                "PUMP012 ticks=%d frames=%d dt_medio_ms=%.1f fps=%.1f",
                                dbgTicks, dbgFrames, dbgDtNs / 1.0e6 / dbgTicks,
                                dbgFrames * 1000.0 / (dbgDtNs / 1.0e6)));
                        dbgFrames = 0;
                        dbgDtNs = 0;
                    }
                }
                refreshAtlasSnapshot();
            } catch (Throwable t) {
                Log.e("GameEngine", "Tick de emulación blindado", t);
            } finally {
                if (emuHandler != null) {
                    emuHandler.postDelayed(this, FRAME_INTERVAL_MS);
                }
            }
        }
    }

    private void startFramePump() {
        stopFramePump();
        try {
            emuThread = new android.os.HandlerThread("EmuLoop");
            emuThread.start();
            emuHandler = new Handler(emuThread.getLooper());
        } catch (Throwable t) {
            Log.e("GameEngine", "Sin hilo de emulación", t);
            return;
        }
        // onResume() llama startFramePump(): la instancia nueva re-ancla el
        // reloj (lastNs=0) â€” reanudar tras pausa no arrastra deuda (RF-04).
        framePumper = new FramePumperTask();
        emuHandler.post(framePumper);
    }

    private void stopFramePump() {
        framePumper = null;
        if (emuHandler != null) {
            try {
                emuHandler.removeCallbacksAndMessages(null);
            } catch (Exception ignored) {
            }
            emuHandler = null;
        }
        if (emuThread != null) {
            try {
                emuThread.quitSafely();
            } catch (Exception ignored) {
            }
            emuThread = null;
        }
    }

    /**
     * Inmersivo sticky (API 19+): oculta barras de HyperOS/MIUI de forma
     * estable; se reaplica al recuperar el foco (diÃ¡logos, picker SAF).
     */
    private void setupImmersiveMode() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable t) {
            Log.w("GameEngine", "Inmersivo no disponible", t);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setupImmersiveMode();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    // ConexiÃ³n con LocationService (Task 1): reutiliza los managers que YA
    // existen en GameEngine. No crea managers nuevos.
    private boolean locationBindingActive = false;
    private LocationService locationService = null;

    private final ServiceConnection locationConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            locationService = ((LocationService.LocalBinder) binder).getService();
            if (locationService != null) {
                locationService.setGeofenceManager(gameEngine.getGeofenceManager());
                locationService.setControlModeManager(gameEngine.getControlModeManager());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            locationService = null;
        }

        @Override
        public void onNullBinding(ComponentName name) {
            // Sin Binder: el servicio sigue en foreground vÃ­a startService.
        }
    };

    private android.location.Location getLastLocationFix() {
        return locationService != null ? locationService.getLastLocation() : null;
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean startLocationService() {
        if (!hasLocationPermission()) {
            Log.w("GameEngine", "LocationService omitido: sin permiso de ubicación");
            return false;
        }
        Intent intent = new Intent(this, LocationService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            // SecurityException (FGS type sin permiso) o
            // ForegroundServiceStartNotAllowedException en Android 12+.
            Log.e("GameEngine", "LocationService no arrancó", t);
            return false;
        }
        locationBindingActive = bindService(intent, locationConnection, Context.BIND_AUTO_CREATE);
        return true;
    }

    // ConexiÃ³n con BluetoothService (RULES Â§7): mismo patrÃ³n que LocationService.
    private boolean bluetoothBindingActive = false;
    private final ServiceConnection bluetoothConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BluetoothService service = ((BluetoothService.LocalBinder) binder).getService();
            service.setBluetoothServiceCallback(new BluetoothService.BluetoothServiceCallback() {
                @Override
                public void onDeviceConnected(BluetoothDevice device) { }

                @Override
                public void onDeviceDisconnected() { }

                @Override
                public void onButtonAPressed() {
                    // RULES.md Â§7: BotÃ³n fÃ­sico â†’ BotÃ³n "A" del emulador
                }

                @Override
                public void onError(String message) { }
            });
            try {
                service.startScan();
            } catch (SecurityException e) {
                // Permiso BLE denegado (p. ej. Modo Demo): sin crash.
                Log.w("GameEngine", "Scan BLE omitido sin permiso", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) { }

        @Override
        public void onNullBinding(ComponentName name) {
            // Sin Binder: el servicio sigue en foreground vÃ­a startService.
        }
    };

    private boolean startBluetoothService() {
        if (!hasBluetoothPermission()) {
            Log.w("GameEngine", "BluetoothService omitido: sin permiso BLE");
            return false;
        }
        Intent intent = new Intent(this, BluetoothService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            Log.e("GameEngine", "BluetoothService no arrancó", t);
            return false;
        }
        bluetoothBindingActive = bindService(intent, bluetoothConnection, Context.BIND_AUTO_CREATE);
        return true;
    }

    // Permisos runtime (API 23+): sin ellos los servicios nacen sin sensores
    // en Android 6+ / targetSdk 34. En KitKat se concede todo en instalaciÃ³n.
    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private boolean backgroundServicesStarted = false;
    private boolean locationServiceStarted = false;
    private boolean bluetoothServiceStarted = false;

    // Onboarding de primer arranque (solo MainActivity, todo programÃ¡tico).
    private boolean onboardingActive = false;
    private boolean demoMode = false;
    private AlertDialog permissionDialog;
    private BottomSheetDialog languageSheet;

    private String[] requiredPermissions() {
        java.util.List<String> perms = new java.util.ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    private boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        for (String p : requiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void startBackgroundServices() {
        if (backgroundServicesStarted) return;
        backgroundServicesStarted = true;
        // Cada servicio solo arranca con su permiso: en Android 14 un FGS de
        // ubicaciÃ³n/BLE sin permiso lanza SecurityException y tumba la app.
        if (!locationServiceStarted) {
            locationServiceStarted = startLocationService();
        }
        if (!bluetoothServiceStarted) {
            bluetoothServiceStarted = startBluetoothService();
        }
    }

    private void ensurePermissionsAndStart() {
        if (hasAllPermissions()) {
            startBackgroundServices();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(requiredPermissions(), REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (onboardingActive) {
                onboardingPermissionsResolved();
            } else {
                // Arranca lo posible con lo concedido; los servicios toleran denegaciones.
                Log.i("GameEngine", "Permiso otorgado manualmente por el usuario.");
                startBackgroundServices();
                seedInitialMap();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Intro del jugador primero: sin perfil no hay mapa.
        if (!new com.gpsquest96.emulator.SaveManager(this).hasPlayer()) {
            startActivity(new Intent(this, IntroActivity.class));
            finish();
            return;
        }
        setupImmersiveMode();
        // Notch + insets (API 28+, guard minSdk 19): render bajo el recorte,
        // superficie con dimensiones fÃ­sicas > 0 en HyperOS/MIUI.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_main);
        // Pruebas largas: no apagar la pantalla mientras la actividad estÃ© visible.
        // El flag vive con la ventana; en background/otra app vuelve el timeout normal.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Cuadro cero: tacto visible antes que cualquier init pesado.
        // assertJoystickVisibility() lo corrige por modo al final.
        joystickView = findViewById(R.id.joystick_view);
        joystickView.setVisibility(View.VISIBLE);

        // Inicializar GameEngine
        gameEngine = new GameEngine(this);
        gameEngine.setGameEngineListener(gameEngineListener);
        gameEngine.initialize();

        // Base relacional (esquema async en pool propio; no toca el layout).
        dbHelper = new DatabaseHelper(this);
        dbHelper.openAsync(null);

        // Datos OSM (Overpass, worker propio; raster y dibujo en sus hilos).
        osmFetcher = new OsmDataFetcher(this);
        osmFetcher.setCallback(new OsmDataFetcher.OsmCallback() {
            @Override
            public void onData(java.util.List<OsmDataFetcher.OsmElement> elements,
                    double centerLat, double centerLon) {
                refreshAtlasSnapshot();
                java.util.List<OsmDataFetcher.OsmElement> merged;
                synchronized (osmLock) {
                    osmAccumulated = OsmAccumulator.merge(osmAccumulated, elements, 6000,
                            centerLat, centerLon, OsmAccumulator.KEEP_RADIUS_M);
                    merged = osmAccumulated;
                }
                lastOsmStatus = "Red: OK " + merged.size()
                        + " elem · tiles:" + lastAtlasTiles;
                if (gameRenderer != null) gameRenderer.feedOsmData(merged, centerLat, centerLon);
                syncWaterGeofences(elements);
                regenerateRouteIfAnchored();
                refreshOsmStatusLine();
            }

            @Override
            public void onError(String message) {
                refreshAtlasSnapshot();
                lastOsmStatus = "Red: " + message + " · tiles:" + lastAtlasTiles;
                Log.w("GameEngine", "OSM sin datos: " + message);
                refreshOsmStatusLine();
            }
        });

        // Permisos primero, servicios despuÃ©s (o directo en KitKat)
        // Onboarding de primer arranque; si ya existe registro, flujo normal.
        if (shouldSkipOnboarding()) {
            ensurePermissionsAndStart();
        } else {
            startOnboarding();
        }

        hudHandler = new Handler(Looper.getMainLooper());

        initViews();
        setupJoystick();
        setupModeSwitch();
        setupStartPanel();
        setupOverlayToggle();
        setupOverlayActions();
        setupHudUpdater();
        applySavedState();
        startFramePump();
        seedInitialMap();
        assertJoystickVisibility();
        // Joystick en capa superior: el tacto llega aunque haya overlays.
        if (joystickView != null) joystickView.bringToFront();
        // Anti-freeform (API 24+, guard minSdk 19): avisar si MIUI nos mete
        // en multiventana; el lienzo GL es exclusivo a pantalla completa.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
            Log.w("GameEngine", "Multiventana detectada: el render exige pantalla completa");
        }
    }

    private void initViews() {
        glSurfaceView = findViewById(R.id.gl_surface_view);
        // Layout estable a prueba de insets nulos (HyperOS): nunca altura 0.
        glSurfaceView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        joystickView = findViewById(R.id.joystick_view);
        btnModeSwitch = findViewById(R.id.btn_mode_switch);
        tvModeIndicator = findViewById(R.id.tv_mode_indicator);
        tvHudTime = findViewById(R.id.tv_hud_time);
        tvHudSteps = findViewById(R.id.tv_hud_steps);
        tvHudWeather = findViewById(R.id.tv_hud_weather);
        tvWeatherBadge = findViewById(R.id.tv_weather_badge);
        tvSystemTitle = findViewById(R.id.tv_system_title);
        tvPlayerName = findViewById(R.id.tv_player_name);
        String playerName = gameEngine.getSaveManager().getPlayerName();
        if (playerName != null) tvPlayerName.setText(playerName);
        // TÃ­tulo de sistema: monospace + negrita + verde GB forzados por cÃ³digo.
        tvSystemTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvSystemTitle.setTextColor(0xFF9BBC0F);
        // TÃ­tulo colapsable: libera el centro para el tacto sin perder el acceso.
        tvSystemTitle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (startPanel != null) {
                    startPanel.setVisibility(
                            startPanel.getVisibility() == View.VISIBLE
                                    ? View.GONE : View.VISIBLE);
                }
            }
        });
        startPanel = findViewById(R.id.start_panel);
        btnLoadRom = findViewById(R.id.btn_load_rom);
        btnSettings = findViewById(R.id.btn_settings);
        settingsOverlay = findViewById(R.id.settings_overlay);
        btnGearOverlay = findViewById(R.id.btn_gear_overlay);
        tvOptSaves = findViewById(R.id.tv_opt_saves);
        bindPadButtons();

        // Init sÃ­ncrona en onCreate (revertido el post perezoso): el contexto
        // se conserva con setPreserveEGLContextOnPause ante cambios de flags.
        glSurfaceView.setEGLContextClientVersion(2);
        // EGL explÃ­cito RGBA8888+depth16: evita configs exÃ³ticas del vendor
        // (Mali/MediaTek) que cuelgan el RenderThread. Sin perfiles de vendor.
        try {
            glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        } catch (IllegalArgumentException e) {
            Log.w("GameEngine", "EGLConfig por defecto", e);
        }
        // Retener texturas 256px al pausar (API 18+, guard minSdk 19).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            glSurfaceView.setPreserveEGLContextOnPause(true);
        }
        gameRenderer = new GameRenderer(gameEngine.getEmulationPipeline());
        gameRenderer.setAvatarGender(com.gpsquest96.avatar.AvatarData.genderOf(
                new com.gpsquest96.emulator.SaveManager(this).getPlayerGender()));
        glSurfaceView.setRenderer(gameRenderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        refreshRomTitle();
        setupZoomButtons();
    }

    /**
     * TÃ­tulo = nombre de la ROM en juego (sale de su cabecera, VISION.md).
     * Sin ROM: marca del proyecto.
     */
    private void refreshRomTitle() {
        try {
            String title = null;
            try {
                title = new com.gpsquest96.emulator.EmulatorBridge().nativeGetTitle();
            } catch (Throwable ignored) {
            }
            if (tvSystemTitle == null) return;
            if (title == null || title.trim().isEmpty()) {
                tvSystemTitle.setText("GPS QUEST 96");
            } else {
                tvSystemTitle.setText(title.trim());
            }
        } catch (Throwable t) {
            Log.w("GameEngine", "Título ROM omitido", t);
        }
    }

    /**
     * Zoom +/- del mapa (detalle del entorno, cromo UI). Persiste en prefs.
     */
    private void setupZoomButtons() {
        try {
            float z = onboardPrefs().getFloat("map_zoom", 1.0f);
            applyMapZoom(z, false);
            View zin = findViewById(R.id.btn_zoom_in);
            View zout = findViewById(R.id.btn_zoom_out);
            if (zin != null) {
                zin.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        float cur = onboardPrefs().getFloat("map_zoom", 1.0f);
                        applyMapZoom(cur + 0.5f, true);
                    }
                });
            }
            if (zout != null) {
                zout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        float cur = onboardPrefs().getFloat("map_zoom", 1.0f);
                        applyMapZoom(cur - 0.5f, true);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w("GameEngine", "Zoom omitido", t);
        }
    }

    private void applyMapZoom(float z, boolean announce) {
        if (z < 0.5f) z = 0.5f;
        if (z > 4.0f) z = 4.0f;
        try {
            onboardPrefs().edit().putFloat("map_zoom", z).apply();
        } catch (Throwable ignored) {
        }
        if (gameRenderer != null) gameRenderer.setMapZoom(z);
        if (announce) showBlockMessage(getString(R.string.msg_zoom_level, (int) z));
    }

    /**
     * Overlay de ajustes: solo conmuta visibilidad. Sin lÃ³gica interna:
     * no toca el hilo GL ni los logs.
     */
    private void setupOverlayToggle() {
        btnGearOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (settingsOverlay.getVisibility() == View.VISIBLE) {
                    settingsOverlay.setVisibility(View.GONE);
                } else {
                    settingsOverlay.setVisibility(View.VISIBLE);
                    paintOverlayStatus();
                }
            }
        });
    }

    /**
     * Pantalla de inicio: botÃ³n CARGAR ROM (SAF) y engranaje de ajustes.
     * Sin permisos de almacenamiento: el SAF otorga acceso al archivo elegido
     * y se copia a filesDir/roms para el cargador nativo (fopen).
     */
    private void setupStartPanel() {
        btnLoadRom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRomPicker();
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ConfigActivity.class));
            }
        });
    }

    private void openRomPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_CODE_OPEN_ROM);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.msg_rom_error), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Perfil portable `.pueblo` (spec 008): la ROM no viaja, solo su SHA-256.
     */
    private String currentRomPath() {
        if (gameEngine == null || gameEngine.getSaveManager() == null) return null;
        return gameEngine.getSaveManager().getRomPath();
    }

    private void openProfileExportPicker() {
        final String romPath = currentRomPath();
        if (romPath == null) {
            showBlockMessage(getString(R.string.msg_open_rom_first));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(PuebloProfile.MIME);
        intent.putExtra(Intent.EXTRA_TITLE, profileFileName(romPath));
        try {
            startActivityForResult(intent, REQUEST_CODE_EXPORT_PROFILE);
        } catch (android.content.ActivityNotFoundException e) {
            showBlockMessage(getString(R.string.msg_no_file_manager));
        }
    }

    private void openProfileImportPicker() {
        if (currentRomPath() == null) {
            showBlockMessage(getString(R.string.msg_open_rom_before_import));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT_PROFILE);
        } catch (android.content.ActivityNotFoundException e) {
            showBlockMessage(getString(R.string.msg_no_file_manager));
        }
    }

    private String profileFileName(String romPath) {
        String name = new java.io.File(romPath).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name + PuebloProfile.EXTENSION;
    }

    /**
     * Exporta en segundo plano. Antes se pausa el motor para que la SRAM estÃ©
     * volcada y no se mueva mientras se empaqueta.
     */
    private void exportPuebloProfile(final Uri uri) {
        final String romPath = currentRomPath();
        if (romPath == null) {
            showBlockMessage(getString(R.string.msg_open_rom_first));
            return;
        }
        gameEngine.pause();
        saveGameState();
        gameEngine.saveState();
        final String title = gameEngine.getRomInfo() != null
                ? gameEngine.getRomInfo().title : "";
        final String gameCode = gameEngine.getRomInfo() != null
                ? gameEngine.getRomInfo().gameCode : "";
        showBlockMessage(getString(R.string.msg_exporting_profile));

        new Thread(new Runnable() {
            @Override
            public void run() {
                PuebloProfile.Result result = null;
                String failure = null;
                try (java.io.OutputStream out =
                             getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        failure = "Sin acceso al archivo";
                    } else {
                        result = new PuebloProfile(MainActivity.this)
                                .export(out, romPath, title, gameCode);
                    }
                } catch (Throwable t) {
                    Log.e("Pueblo", "Error exportando perfil", t);
                    failure = t.getMessage();
                }
                final PuebloProfile.Result finalResult = result;
                final String finalFailure = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        gameEngine.resume();
                        if (finalResult != null && finalResult.ok) {
                            StringBuilder detail = new StringBuilder();
                            detail.append(finalResult.battery ? "partida" : "sin partida");
                            if (finalResult.geo) detail.append(" + geo");
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.msg_profile_exported, detail.toString()),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            String msg = finalResult != null
                                    ? finalResult.message : finalFailure;
                            if (msg == null || msg.isEmpty()) msg = "error desconocido";
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.msg_profile_export_failed, msg),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "PuebloExport").start();
    }

    /**
     * Importa en segundo plano y recarga la ROM para que el core lea el `.sav`
     * nuevo. El orden importa: `pause()` vuelca y congela la SRAM antes de que
     * el perfil la reemplace.
     */
    private void importPuebloProfile(final Uri uri) {
        final String romPath = currentRomPath();
        if (romPath == null) {
            showBlockMessage(getString(R.string.msg_open_rom_before_import));
            return;
        }
        gameEngine.pause();
        showBlockMessage(getString(R.string.msg_importing_profile));

        new Thread(new Runnable() {
            @Override
            public void run() {
                PuebloProfile.Result result = null;
                String failure = null;
                try (java.io.InputStream in =
                             getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        failure = "Sin acceso al archivo";
                    } else {
                        result = new PuebloProfile(MainActivity.this)
                                .importFrom(in, romPath);
                    }
                } catch (Throwable t) {
                    Log.e("Pueblo", "Error importando perfil", t);
                    failure = t.getMessage();
                }
                final PuebloProfile.Result finalResult = result;
                final String finalFailure = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalResult == null || !finalResult.ok) {
                            gameEngine.resume();
                            String msg = finalResult != null
                                    ? finalResult.message : finalFailure;
                            if (msg == null || msg.isEmpty()) msg = "error desconocido";
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.msg_profile_import_failed, msg),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        boolean reloaded = gameEngine.reloadRom(false);
                        if (reloaded) {
                            gameEngine.reloadState();
                            loadGameState();
                            if (gameRenderer != null) gameRenderer.noteRomLoaded();
                            applyViewMode(true);
                            if (startPanel != null) startPanel.setVisibility(View.GONE);
                        }
                        gameEngine.resume();
                        String detail = finalResult.battery
                                ? getString(R.string.msg_profile_reloaded_partida)
                                : getString(R.string.msg_profile_reloaded_no_battery);
                        Toast.makeText(MainActivity.this,
                                reloaded
                                        ? getString(R.string.msg_profile_imported_reloaded, detail)
                                        : getString(R.string.msg_profile_imported_reopen),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "PuebloImport").start();
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXPORT/IMPORT DE RUTA (spec 004 slice 6)
    // Solo datos: el ZIP lleva manifest + route.json, nunca contenido que se
    // ejecute. La importación exige que rom_key coincida con la partida abierta.
    // ═══════════════════════════════════════════════════════════════════

    private java.io.File currentRouteFile() {
        String safe = romKeyForCurrentRom().replaceAll("[^A-Za-z0-9._-]", "_");
        return new java.io.File(new java.io.File(getFilesDir(), "routes"),
                "route_" + safe + ".json");
    }

    private void openRouteExportPicker() {
        if (currentRomPath() == null) {
            showBlockMessage(getString(R.string.msg_open_rom_first));
            return;
        }
        if (!currentRouteFile().isFile()) {
            showBlockMessage(getString(R.string.msg_route_missing));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(RouteBundle.MIME);
        String safe = romKeyForCurrentRom().replaceAll("[^A-Za-z0-9._-]", "_");
        intent.putExtra(Intent.EXTRA_TITLE, "route_" + safe + RouteBundle.EXTENSION);
        try {
            startActivityForResult(intent, REQUEST_CODE_EXPORT_ROUTE);
        } catch (android.content.ActivityNotFoundException e) {
            showBlockMessage(getString(R.string.msg_no_file_manager));
        }
    }

    private void openRouteImportPicker() {
        if (currentRomPath() == null) {
            showBlockMessage(getString(R.string.msg_open_rom_before_import));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT_ROUTE);
        } catch (android.content.ActivityNotFoundException e) {
            showBlockMessage(getString(R.string.msg_no_file_manager));
        }
    }

    private Route readCurrentRoute() throws Exception {
        java.io.File f = currentRouteFile();
        if (!f.isFile()) return null;
        byte[] data = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0;
            int n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
                off += n;
            }
            if (off != data.length) throw new java.io.IOException("Lectura incompleta");
        }
        return Route.fromJson(new String(data, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Exporta la ruta persistida; no toca el motor ni el progreso. */
    private void exportRouteBundle(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                RouteBundle.Result result = null;
                String failure = null;
                try (java.io.OutputStream out =
                             getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        failure = "Sin acceso al archivo";
                    } else {
                        Route route = readCurrentRoute();
                        result = RouteBundle.export(route, out);
                    }
                } catch (Throwable t) {
                    Log.e("GameEngine", "Error exportando ruta", t);
                    failure = t.getMessage();
                }
                final RouteBundle.Result finalResult = result;
                final String finalFailure = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalResult != null && finalResult.ok) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.msg_route_exported,
                                            finalResult.route.romKey),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            String msg = finalResult != null
                                    ? finalResult.message : finalFailure;
                            if (msg == null || msg.isEmpty()) msg = "error desconocido";
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.msg_route_export_failed, msg),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "RouteExport").start();
    }

    /**
     * Importa una ruta ya validada (esquema, tamaño, checksum y rom_key), la
     * persiste y la publica en el mapa. El ancla confirmada pasa a ser la del
     * paquete para que una regeneración posterior no la pise.
     */
    private void importRouteBundle(final Uri uri) {
        final String romKey = romKeyForCurrentRom();
        new Thread(new Runnable() {
            @Override
            public void run() {
                RouteBundle.Result result = null;
                String failure = null;
                try (java.io.InputStream in =
                             getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        failure = "Sin acceso al archivo";
                    } else {
                        result = RouteBundle.importFrom(in, romKey);
                    }
                } catch (Throwable t) {
                    Log.e("GameEngine", "Error importando ruta", t);
                    failure = t.getMessage();
                }
                final RouteBundle.Result finalResult = result;
                final String finalFailure = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalResult == null || !finalResult.ok) {
                            String msg = finalResult != null
                                    ? finalResult.message : finalFailure;
                            if (msg == null || msg.isEmpty()) msg = "error desconocido";
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.msg_route_import_failed, msg),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Route route = finalResult.route;
                        try {
                            RouteBundle.save(route, new java.io.File(getFilesDir(), "routes"));
                        } catch (Throwable t) {
                            Log.e("GameEngine", "No se pudo guardar la ruta importada", t);
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.msg_route_import_failed,
                                            t.getMessage()),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        onboardPrefs().edit()
                                .putBoolean(P_ANCHOR, true)
                                .putString(P_ANCHOR_LAT, String.valueOf(route.anchorLat))
                                .putString(P_ANCHOR_LON, String.valueOf(route.anchorLon))
                                .putFloat(P_ANCHOR_ACC, (float) route.anchorAccuracyM)
                                .putLong(P_ANCHOR_TIME, route.anchorConfirmedAt)
                                .putString(P_ROM_KEY, route.romKey)
                                .apply();
                        int poi = 0;
                        int synthetic = 0;
                        for (RouteNode n : route.nodes) {
                            if ("synthetic".equals(n.poi)) synthetic++;
                            else poi++;
                        }
                        routeNodeCount = route.nodes.size();
                        lastRouteStatus = "Ruta: " + route.nodes.size()
                                + " nodos (" + poi + " POI)";
                        if (gameRenderer != null) gameRenderer.feedRouteNodes(route.nodes);
                        Log.i("GameEngine", "RUTA IMPORTADA " + route.nodes.size()
                                + " nodos (" + poi + " POI, " + synthetic
                                + " sintéticos) rom_key=" + route.romKey);
                        refreshOsmStatusLine();
                        Toast.makeText(MainActivity.this,
                                getString(R.string.msg_route_imported,
                                        route.nodes.size(), poi),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "RouteImport").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode == REQUEST_CODE_EXPORT_ROUTE) {
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    exportRouteBundle(data.getData());
                }
                return;
            }
            if (requestCode == REQUEST_CODE_IMPORT_ROUTE) {
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    importRouteBundle(data.getData());
                }
                return;
            }
            if (requestCode == REQUEST_CODE_EXPORT_PROFILE) {
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    exportPuebloProfile(data.getData());
                }
                return;
            }
            if (requestCode == REQUEST_CODE_IMPORT_PROFILE) {
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    importPuebloProfile(data.getData());
                }
                return;
            }
            if (requestCode != REQUEST_CODE_OPEN_ROM) return;
            if (onboardingActive) {
                // Paso C: cancelado â†’ Demo; el handler valida extensiÃ³n.
                handleOnboardingRomResult(data);
                return;
            }
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importRomFromUri(data.getData());
            }
        } catch (Throwable t) {
            Log.e("GameEngine", "Error en resultado del picker", t);
            Toast.makeText(this, getString(R.string.msg_rom_error), Toast.LENGTH_SHORT).show();
        }
    }

    private void importRomFromUri(Uri uri) {
        try {
            importRomFromUriUnsafe(uri);
        } catch (Throwable t) {
            Log.e("GameEngine", "Error importando ROM", t);
            Toast.makeText(this, getString(R.string.msg_rom_error), Toast.LENGTH_SHORT).show();
        }
    }

    private void importRomFromUriUnsafe(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.w("GameEngine", "Sin permiso persistente para ROM", e);
        }

        String name = queryDisplayName(uri);
        if (name == null || name.isEmpty()) {
            name = "rom_" + System.currentTimeMillis() + ".gb";
        }
        // Puerta dual-core (Â§GBA): la GBA no se copia ni se guarda; el core
        // ARM avisara cuando aterrice. Mensaje honesto en vez de boot basura.
        if (com.gpsquest96.emulator.EmulatorBridge.detectKind(name)
                == com.gpsquest96.emulator.EmulatorBridge.Kind.GBA) {
            Log.w("GameEngine", "ROM GBA pendiente (epico ARM): " + name);
            Toast.makeText(this, getString(R.string.msg_gba_pending),
                    Toast.LENGTH_LONG).show();
            return;
        }
        java.io.File romsDir = new java.io.File(getFilesDir(), "roms");
        if (!romsDir.exists()) romsDir.mkdirs();
        java.io.File dest = new java.io.File(romsDir, name);

        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            if (in == null) throw new java.io.IOException("Sin stream de lectura");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (java.io.IOException e) {
            Log.e("GameEngine", "Error copiando ROM", e);
            Toast.makeText(this, getString(R.string.msg_rom_error), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean ok = gameEngine.loadRom(dest.getAbsolutePath());
        // Log solo con ruta y tamaÃ±o: el tÃ­tulo ya viene sanitizado del bridge.
        Log.i("GameEngine", "ROM abierta OK: " + dest.getAbsolutePath()
                + " (" + dest.length() + " bytes)");
        // Superficie en modo continuo: no-op inocuo que confirma el pipeline.
        glSurfaceView.requestRender();
        Toast.makeText(this,
                getString(ok ? R.string.msg_rom_loaded : R.string.msg_rom_error),
                Toast.LENGTH_SHORT).show();
        if (ok) {
            // La intro manda desde el primer cuadro: base transparente
            // regenerada + vista a Juego.
            if (gameRenderer != null) gameRenderer.noteRomLoaded();
            applyViewMode(true);
            if (startPanel != null) {
                startPanel.setVisibility(View.GONE);
            }
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w("GameEngine", "Sin nombre visible para ROM", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /**
     * Restaura estado guardado: oculta el panel si ya hay ROM y aplica
     * el modo SofÃ¡ si quedÃ³ seleccionado en ajustes.
     */
    private void applySavedState() {
        String romPath = gameEngine.getSaveManager().getRomPath();
        if (romPath != null && startPanel != null) {
            startPanel.setVisibility(View.GONE);
        }
        // Vista inicial: Juego opaco con ROM (intro intacta), Mapa sin ROM.
        if (gameRenderer != null) gameRenderer.setPpuFullscreen(currentViewMode());
        String mode = getSharedPreferences("gpsquest96_prefs", MODE_PRIVATE)
                .getString("control_mode", "gps");
        if ("sofa".equals(mode)) {
            gameEngine.getControlModeManager().setMode(ControlModeManager.ControlMode.SOFA);
            handleModeSwitch(ControlModeManager.ControlMode.SOFA);
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // ONBOARDING DE PRIMER ARRANQUE (Pasos A â†’ B â†’ C â†’ Demo)
    // Todo programÃ¡tico (sin XML nuevo), API 19+.
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private android.content.SharedPreferences onboardPrefs() {
        return getSharedPreferences("gpsquest96_prefs", MODE_PRIVATE);
    }

    /**
     * Paso 0: si hay registro vÃ¡lido o ROM previa, saltar el flujo.
     */
    private boolean shouldSkipOnboarding() {
        if (onboardPrefs().getBoolean("onboarding_done", false)) return true;
        if (gameEngine.getSaveManager().getRomPath() != null) return true;
        // Binario vÃ¡lido â†’ retoma storyFlag y salta el onboarding.
        return loadGameState();
    }

    /**
     * Paso A: diÃ¡logo pixelado de permisos. Continuar â†’ flujo de sistema;
     * Omitir/cancelar â†’ Modo Demo.
     */
    private void startOnboarding() {
        onboardingActive = true;
        // Reanudar tras recreate() del paso de idioma: ir directo al picker.
        if ("rom".equals(onboardPrefs().getString("onboarding_step", ""))) {
            startBackgroundServices();
            openRomPicker();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        layout.setBackgroundColor(0xFF0F380F);
        TextView msg = new TextView(this);
        msg.setText("GPS QUEST 96 necesita ubicacion y Bluetooth para el mundo real. Puedes omitir los permisos; igual te pediremos tu ROM (sin ella solo hay Demo).");
        msg.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        msg.setTextColor(0xFF9BBC0F);
        msg.setTextSize(14);
        layout.addView(msg);

        dismissPermissionDialog();
        permissionDialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setCancelable(true)
                .setPositiveButton("Continuar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (hasAllPermissions()) {
                            onboardingPermissionsResolved();
                        } else {
                            ensurePermissionsAndStart();
                        }
                    }
                })
                .setNegativeButton("Omitir", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        dismissPermissionDialog();
                        offerRomOrDemo();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        offerRomOrDemo();
                    }
                })
                .create();
        permissionDialog.show();
    }

    private void dismissPermissionDialog() {
        if (permissionDialog != null && permissionDialog.isShowing()) {
            permissionDialog.dismiss();
        }
        permissionDialog = null;
    }

    private void onboardingPermissionsResolved() {
        startBackgroundServices();
        seedInitialMap();
        if (hasAllPermissions()) {
            dismissPermissionDialog();
            showLanguageSheet();
        } else {
            dismissPermissionDialog();
            offerRomOrDemo();
        }
    }

    /**
     * Paso B: Bottom Sheet con los 10 idiomas (monospace). Guarda clave "lang"
     * en mayÃºsculas ("ES" por defecto) y aplica el locale en minÃºsculas.
     */
    private void showLanguageSheet() {
        showLanguageSheet(false);
    }

    private void showLanguageSheet(final boolean settingsMode) {
        dismissLanguageSheet();
        final String[] codes = {"es", "en", "pt", "fr", "de", "ja", "ko", "zh", "ru", "it"};
        String[] names = {"Español", "English", "Português", "Français", "Deutsch",
                "日本語", "한국어", "中文", "Русский", "Italiano"};

        ListView list = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_single_choice, names) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.BOLD);
                tv.setTextColor(0xFF9BBC0F);
                return tv;
            }
        };
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setBackgroundColor(0xFF0F380F);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (settingsMode) {
                    applySettingsLanguage(codes[position]);
                } else {
                    applyOnboardingLanguage(codes[position]);
                }
            }
        });

        languageSheet = new BottomSheetDialog(this);
        languageSheet.setContentView(list);
        languageSheet.setCancelable(true);
        languageSheet.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                if (!settingsMode) {
                    applyOnboardingLanguage("es");
                }
            }
        });
        languageSheet.show();
    }

    private void dismissLanguageSheet() {
        if (languageSheet != null && languageSheet.isShowing()) {
            languageSheet.dismiss();
        }
        languageSheet = null;
    }

    private void applyOnboardingLanguage(String code) {
        String c = (code == null || code.isEmpty()) ? "es" : code.toLowerCase(java.util.Locale.ROOT);
        onboardPrefs().edit()
                .putString("lang", c.toUpperCase(java.util.Locale.ROOT))
                .putString("onboarding_step", "rom")
                .apply();
        LocaleHelper.setLocale(this, c);
        dismissLanguageSheet();
        recreate();
    }

    /**
     * Idioma desde ajustes: misma BottomSheet, sin tocar el flujo de onboarding.
     */
    private void applySettingsLanguage(String code) {
        String c = (code == null || code.isEmpty()) ? "es" : code.toLowerCase(java.util.Locale.ROOT);
        onboardPrefs().edit()
                .putString("lang", c.toUpperCase(java.util.Locale.ROOT))
                .apply();
        LocaleHelper.setLocale(this, c);
        dismissLanguageSheet();
        recreate();
    }

    /**
     * Paso C: valida extensiÃ³n tras el picker. InvÃ¡lido â†’ Toast + reintento;
     * GBA â†’ aviso de Ã©pico pendiente + reintento (sin Demo forzado);
     * cancelado â†’ Modo Demo; GB/GBC â†’ flujo normal de importaciÃ³n.
     */
    private void handleOnboardingRomResult(Intent data) {
        if (data == null || data.getData() == null) {
            enterDemoMode();
            return;
        }
        String name = queryDisplayName(data.getData());
        String lower = (name != null) ? name.toLowerCase(java.util.Locale.ROOT) : "";
        if (!lower.endsWith(".gb") && !lower.endsWith(".gbc") && !lower.endsWith(".gba")) {
            android.widget.Toast.makeText(this,
                    getString(R.string.msg_only_gb_gbc_gba), android.widget.Toast.LENGTH_SHORT).show();
            openRomPicker();
            return;
        }
        if (lower.endsWith(".gba")) {
            android.widget.Toast.makeText(this,
                    getString(R.string.msg_gba_pending_picker),
                    android.widget.Toast.LENGTH_LONG).show();
            openRomPicker();
            return;
        }
        importRomFromUri(data.getData());
        finishOnboarding();
    }

    private void finishOnboarding() {
        onboardPrefs().edit()
                .putBoolean("onboarding_done", true)
                .putBoolean("demo_mode", demoMode)
                .remove("onboarding_step")
                .apply();
        onboardingActive = false;
        dismissPermissionDialog();
        dismissLanguageSheet();
    }

    /**
     * VISION.md: sin ROM no hay juego. Todo camino a Demo ofrece primero
     * el picker; cancelarlo lleva a Demo (picker cancelado = sin ROM).
     * El SAF no necesita permiso de almacenamiento.
     */
    private void offerRomOrDemo() {
        if (!onboardingActive) {
            enterDemoMode();
            return;
        }
        try {
            openRomPicker();
        } catch (Throwable t) {
            Log.w("GameEngine", "Picker ROM omitido, a Demo", t);
            enterDemoMode();
        }
    }

    /**
     * Modo Demo: mapa verde sin POIs (no existen sistemas de POIs),
     * avatar movible vÃ­a Joystick (SOFA forzado), cero interacciones.
     */
    private void enterDemoMode() {
        demoMode = true;
        finishOnboarding();
        if (startPanel != null) startPanel.setVisibility(View.GONE);
        gameEngine.getControlModeManager().setMode(ControlModeManager.ControlMode.SOFA);
        updateModeUI(ControlModeManager.ControlMode.SOFA);
        if (joystickView != null) joystickView.setVisibility(View.VISIBLE);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // PERSISTENCIA GEOGRÃFICA (binario filesDir/saves + storyFlag)
    // Solo MainActivity. El mapa es fÃ­sico, el contenido es lÃ³gico.
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private static final String SAVE_DIR = "saves";
    private static final String SAVE_FILE = "gpsquest96.sav";
    private static final int SAVE_MAGIC = 0x47513936; // "GQ96"
    private static final int SAVE_VERSION = 1;

    // Umbral BOSS espejo de RULES Â§8 (LEAGUE_COMPLETE_FLAG = 200).
    private static final int BOSS_FLAG_THRESHOLD = 200;

    private int storyFlag = 0;

    // FEATURE FASE 2 â€” Dojos Xalapa: PURGADO (VISION.md, Task 8 slice 3).
    // Sin POIs inventados: matriz vacia. El mapeo global (monumento real
    // -> locacion del juego) lo define la Fase C. La clase DojoPOI y
    // nearestDojoWithin quedan congelados para el modal de derrota.
    private static class DojoPOI {
        final String name;
        final double lat, lon;
        final int requiredFlag;

        DojoPOI(String name, double lat, double lon, int requiredFlag) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.requiredFlag = requiredFlag;
        }
    }

    private static final DojoPOI[] DOJOS = {};

    private java.io.File saveFile() {
        java.io.File dir = new java.io.File(getFilesDir(), SAVE_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new java.io.File(dir, SAVE_FILE);
    }

    /**
     * Guardado sÃ­ncrono inmediato del storyFlag.
     */
    private synchronized void saveGameState() {
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.FileOutputStream(saveFile()))) {
            out.writeInt(SAVE_MAGIC);
            out.writeInt(SAVE_VERSION);
            out.writeInt(storyFlag);
            out.writeLong(System.currentTimeMillis());
            Log.i("GameEngine", "Save OK: flag=" + storyFlag);
        } catch (java.io.IOException e) {
            Log.e("GameEngine", "Error guardando", e);
        }
    }

    /**
     * Carga el binario si es vÃ¡lido. false = inicio fresco.
     */
    private boolean loadGameState() {
        java.io.File f = new java.io.File(
                new java.io.File(getFilesDir(), SAVE_DIR), SAVE_FILE);
        if (!f.exists()) return false;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.FileInputStream(f))) {
            if (in.readInt() != SAVE_MAGIC || in.readInt() != SAVE_VERSION) return false;
            storyFlag = in.readInt();
            long when = in.readLong();
            Log.i("GameEngine", "Save cargado: flag=" + storyFlag + " (" + when + ")");
            return true;
        } catch (java.io.IOException e) {
            Log.w("GameEngine", "Save ilegible, inicio fresco", e);
            return false;
        }
    }

    /**
     * BOSS prohibido si storyFlag < 200 (RULES Â§8, sin tocar GameEngine).
     */
    public boolean canSpawnBoss() {
        return storyFlag >= BOSS_FLAG_THRESHOLD;
    }

    /**
     * Filtro de contenido por historia. false = bloqueado con aviso monospace.
     */
    public boolean tryInteractWithPoi(String name, int requiredFlag) {
        if (storyFlag >= requiredFlag) return true;
        showBlockMessage(getString(R.string.msg_need_story_progress));
        return false;
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Haversine para el modal de derrota (API congelada); compuerta purgada.
    // Corre en hilo UI/1Hz, jamÃ¡s en el hilo GL. Sin JNI directo.
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Distancia lineal en metros (Tierra R = 6371000 m).
     */
    private double calculateDistanceHaversine(double lat1, double lon1,
            double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }


    /**
     * Pinta el overlay en UI: guardados + diagnostico OSM (Red:).
     * Sin medallas (dojos purgados, VISION.md). Sin hilos: llamada directa.
     */
    private void paintOverlayStatus() {
        refreshAtlasSnapshot();
        if (tvOptSaves != null) {
            tvOptSaves.setText("Partidas Guardadas · " + lastOsmStatus);
        }
        View tvView = findViewById(R.id.tv_opt_view);
        if (tvView instanceof TextView) {
            ((TextView) tvView).setText(currentViewMode() ? "Vista: Juego" : "Vista: Mapa");
        }
        TextView tvAnchor = findViewById(R.id.tv_opt_anchor);
        if (tvAnchor != null) {
            if (anchorConfirmed()) {
                tvAnchor.setText(lastRouteStatus + " · ancla ±"
                        + Math.round(anchorAccuracyM()) + " m");
            } else {
                tvAnchor.setText("Anclar ruta aquí");
            }
        }
    }

    /**
     * Vista Juego/Mapa (engrane, cromo UI): con ROM, Juego opaco por defecto
     * para la intro intacta; sin ROM, Mapa. La elecciÃ³n persiste en prefs y
     * el paso a Mapa es manual (sin autodetecciÃ³n por flags: 5c pendiente).
     */
    private boolean currentViewMode() {
        boolean def = gameEngine != null
                && gameEngine.getSaveManager().getRomPath() != null;
        try {
            return onboardPrefs().getBoolean("view_ppu_full", def);
        } catch (Throwable t) {
            return def;
        }
    }

    private void applyViewMode(boolean full) {
        // No forzar Vista: Juego si no hay ROM cargada
        if (full && (gameEngine == null || gameEngine.getSaveManager().getRomPath() == null)) {
            Log.w("GameEngine", "applyViewMode(true) ignorado: sin ROM cargada");
            return;
        }
        try {
            onboardPrefs().edit().putBoolean("view_ppu_full", full).apply();
        } catch (Throwable ignored) {
        }
        if (gameRenderer != null) gameRenderer.setPpuFullscreen(full);
        paintOverlayStatus();
    }

    /** Repinta la linea del engrane si esta visible (callbacks OSM llegan en worker). */
    private void refreshOsmStatusLine() {
        if (settingsOverlay == null || tvOptSaves == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (settingsOverlay.getVisibility() != View.VISIBLE) return;
                paintOverlayStatus();
            }
        });
    }

    /**
     * spec 015/B5 â€” agua de OSM â†’ geovallas reales.
     * <p>
     * Cada elemento `WATER` de Overpass se registra con su geometrÃ­a completa
     * (polilÃ­nea de rÃ­o o polÃ­gono de lago/estanque), no con un centroide: el
     * aviso de &lt;20 m tiene que saltar en cualquier punto de la orilla.
     * Corre en el worker de Overpass; la lista de zonas es copy-on-write.
     */
    private void syncWaterGeofences(java.util.List<OsmDataFetcher.OsmElement> elements) {
        if (gameEngine == null) return;
        GeofenceManager gm = gameEngine.getGeofenceManager();
        if (gm == null) return;
        java.util.List<GeofenceManager.WaterZone> zones = new java.util.ArrayList<>();
        int idx = 0;
        if (elements != null) {
            for (OsmDataFetcher.OsmElement e : elements) {
                if (e == null || e.kind != OsmDataFetcher.Kind.WATER) continue;
                if (e.lats == null || e.lons == null || e.lats.length == 0) continue;
                if (e.lats.length != e.lons.length) continue;
                zones.add(new GeofenceManager.WaterZone(
                        "agua-" + (idx++), e.lats, e.lons, 0f));
            }
        }
        int n = gm.replaceWaterZones(zones);
        Log.i("GameEngine", "Geovallas agua: " + n + " zonas desde OSM");
    }

    // ═══════════════════════════════════════════════════════════════════
    // ANCLA + RUTA MAESTRA (spec 004 slices 2 y 6)
    // Prefs: ancla confirmada explícitamente (nunca el primer fix).
    // Generación pura en `RouteGenerator` (host-testable); el mapa la pinta
    // como celdas NODE y la ruta queda en filesDir/routes/route_<rom_key>.json.
    // ═══════════════════════════════════════════════════════════════════

    private static final String P_ANCHOR = "route_anchor_confirmed";
    private static final String P_ANCHOR_LAT = "route_anchor_lat";
    private static final String P_ANCHOR_LON = "route_anchor_lon";
    private static final String P_ANCHOR_ACC = "route_anchor_accuracy";
    private static final String P_ANCHOR_TIME = "route_anchor_time";
    private static final String P_ROM_KEY = "route_rom_key";

    private boolean anchorConfirmed() {
        try {
            return onboardPrefs().getBoolean(P_ANCHOR, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private double anchorLat() {
        return parsePrefDouble(onboardPrefs().getString(P_ANCHOR_LAT, "0"), 0.0);
    }

    private double anchorLon() {
        return parsePrefDouble(onboardPrefs().getString(P_ANCHOR_LON, "0"), 0.0);
    }

    private float anchorAccuracyM() {
        try {
            return onboardPrefs().getFloat(P_ANCHOR_ACC, 99f);
        } catch (Throwable t) {
            return 99f;
        }
    }

    private static double parsePrefDouble(String s, double def) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Ancla confirmada por el usuario (spec 004 §3.1): exige fix exterior
     * (precisión ≤ 20 m) y confirmación explícita; nunca toma el primer fix.
     * Re-anclable solo hasta resolver el nodo 0.
     */
    private void confirmAnchorFlow() {
        android.location.Location fix = getLastLocationFix();
        if (fix == null) {
            showBlockMessage("Ancla: sin fix GPS");
            return;
        }
        if (onboardPrefs().getBoolean("route_nodo0_resuelto", false)) {
            showBlockMessage("Ancla inmutable: nodo 0 resuelto");
            return;
        }
        final float acc = fix.hasAccuracy() ? fix.getAccuracy() : 999f;
        if (acc > RouteGenerator.MAX_ANCHOR_ACCURACY_M) {
            showBlockMessage(String.format(Locale.ROOT,
                    "Ancla: precisión ±%.0f m (máx %d m)",
                    acc, (int) RouteGenerator.MAX_ANCHOR_ACCURACY_M));
            return;
        }
        final double lat = fix.getLatitude();
        final double lon = fix.getLongitude();
        new AlertDialog.Builder(this)
                .setTitle("Confirmar ancla")
                .setMessage(String.format(Locale.ROOT,
                        "Punto 0: %.6f, %.6f (±%.0f m).\nLa Ruta Maestra se generará desde aquí.",
                        lat, lon, acc))
                .setPositiveButton("Anclar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        saveAnchor(lat, lon, acc);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void saveAnchor(double lat, double lon, float acc) {
        String romKey = romKeyForCurrentRom();
        onboardPrefs().edit()
                .putBoolean(P_ANCHOR, true)
                .putString(P_ANCHOR_LAT, String.valueOf(lat))
                .putString(P_ANCHOR_LON, String.valueOf(lon))
                .putFloat(P_ANCHOR_ACC, acc)
                .putLong(P_ANCHOR_TIME, System.currentTimeMillis())
                .putString(P_ROM_KEY, romKey)
                .apply();
        Log.i("GameEngine", String.format(Locale.ROOT,
                "ANCLA OK %.6f,%.6f ±%.0f m rom_key=%s", lat, lon, acc, romKey));
        regenerateRouteIfAnchored();
        // Corredor (spec 004 §9): unión de áreas a lo largo del tramo, no un círculo.
        if (osmFetcher != null) {
            osmFetcher.requestCorridor(lat, lon, OSM_CORRIDOR_RADIUS_M, OSM_CORRIDOR_SPAN_M);
        }
        paintOverlayStatus();
    }

    /** Clave estable de ROM: cabecera (título + versión), nunca el hash del dump. */
    private String romKeyForCurrentRom() {
        try {
            String path = (gameEngine != null) ? gameEngine.getSaveManager().getRomPath() : null;
            if (path == null || path.isEmpty()) return "rom-v0";
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return "rom-v0";
            byte[] head = new byte[0x150];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                int n;
                while (off < head.length
                        && (n = in.read(head, off, head.length - off)) > 0) {
                    off += n;
                }
            }
            return RouteGenerator.romKeyFromHeader(head);
        } catch (Throwable t) {
            Log.w("GameEngine", "rom_key ilegible", t);
            return "rom-v0";
        }
    }

    /**
     * Regenera la ruta desde el ancla confirmada. Determinista: mismos datos y
     * mismo ancla ⇒ mismo JSON (criterio 2). Corre en worker o UI; el repintado
     * del overlay se postea a UI.
     */
    private void regenerateRouteIfAnchored() {
        if (!anchorConfirmed()) return;
        java.util.List<OsmDataFetcher.OsmElement> snapshot;
        synchronized (osmLock) {
            snapshot = new java.util.ArrayList<OsmDataFetcher.OsmElement>(osmAccumulated);
        }
        if (snapshot.isEmpty()) {
            lastRouteStatus = "Ruta: sin datos OSM";
            return;
        }
        try {
            Route route = RouteGenerator.generate(
                    onboardPrefs().getString(P_ROM_KEY, "rom-v0"),
                    anchorLat(), anchorLon(), anchorAccuracyM(),
                    onboardPrefs().getLong(P_ANCHOR_TIME, 0L),
                    snapshot, RouteGenerator.DEFAULT_NODE_TARGET);
            int poi = 0;
            int synthetic = 0;
            for (RouteNode n : route.nodes) {
                if ("synthetic".equals(n.poi)) synthetic++;
                else poi++;
            }
            routeNodeCount = route.nodes.size();
            lastRouteStatus = "Ruta: " + route.nodes.size() + " nodos (" + poi + " POI)";
            writeRouteFile(route);
            if (gameRenderer != null) gameRenderer.feedRouteNodes(route.nodes);
            Log.i("GameEngine", "RUTA OK " + route.nodes.size() + " nodos ("
                    + poi + " POI, " + synthetic + " sintéticos) · "
                    + (poi > 0 ? "afinidad OSM" : "solo respaldo sintético"));
            refreshOsmStatusLine();
        } catch (IllegalArgumentException e) {
            lastRouteStatus = "Ruta: " + e.getMessage();
            Log.w("GameEngine", "Ruta no generada: " + e.getMessage());
        }
    }

    /** Ruta de solo lectura: filesDir/routes/route_<rom_key>.json (spec 004 §6). */
    private void writeRouteFile(Route route) {
        try {
            java.io.File dir = new java.io.File(getFilesDir(), "routes");
            java.io.File f = RouteBundle.save(route, dir);
            Log.i("GameEngine", "Ruta escrita: " + f.getName() + " (" + f.length() + " B)");
        } catch (Throwable t) {
            Log.w("GameEngine", "No se pudo escribir la ruta", t);
        }
    }

    /**
     * Aviso monospace en pantalla reutilizando el indicador (restaura solo).
     */
    private void showBlockMessage(final String message) {
        final ControlModeManager.ControlMode mode =
                gameEngine.getControlModeManager().getCurrentMode();
        tvModeIndicator.setText(message);
        tvModeIndicator.setTextColor(0xFFFF5555);
        hudHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateModeUI(mode);
            }
        }, 2500);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // ANTI-SOFTLOCK (RULES.md Â§5). Sin teletransporte: las coordenadas
    // GPS jamÃ¡s se tocan en derrota. API lista para el flujo de batalla.
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private AlertDialog defeatDialog;

    /**
     * Checkpoint pre-combate: .sav sÃ­ncrono + flags de evento.
     */
    public void preBattleCheckpoint() {
        if (gameEngine != null) gameEngine.flushSram();
        saveGameState();
        gameEngine.saveState();
        Log.i("GameEngine", "Checkpoint pre-combate OK");
    }

    /**
     * Derrota: GPS intacto + tregua 180 s (SaveManager) + modal.
     * API lista para el futuro flujo de batalla fiel a ROM (VISION.md).
     */
    public void onBattleDefeat() {
        gameEngine.getSaveManager().startTruce();
        Log.i("GameEngine", "Derrota: tregua 180 s, GPS intacto");
        showDefeatModal();
    }

    private DojoPOI nearestDojoWithin(double lat, double lon, double radiusM) {
        DojoPOI best = null;
        double bestDist = Double.MAX_VALUE;
        for (DojoPOI d : DOJOS) {
            double dist = calculateDistanceHaversine(lat, lon, d.lat, d.lon);
            if (dist < radiusM && dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }

    private void dismissDefeatModal() {
        if (defeatDialog != null && defeatDialog.isShowing()) {
            defeatDialog.dismiss();
        }
        defeatDialog = null;
    }

    private void showDefeatModal() {
        dismissDefeatModal();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        layout.setBackgroundColor(0xFF0F380F);
        TextView msg = new TextView(this);
        msg.setText("DERROTA — Tregua 180 s. El GPS no se movió.");
        msg.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        msg.setTextColor(0xFF9BBC0F);
        msg.setTextSize(14);
        layout.addView(msg);

        defeatDialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setCancelable(true)
                .setPositiveButton("Restaurar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        loadGameState();
                        gameEngine.loadState();
                        Log.i("GameEngine", "Snapshot restaurada tras derrota");
                        dismissDefeatModal();
                    }
                })
                .setNeutralButton("Curar (dojo <25 m)", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        android.location.Location fix = getLastLocationFix();
                        DojoPOI care = (fix != null)
                                ? nearestDojoWithin(fix.getLatitude(), fix.getLongitude(), 25.0)
                                : null;
                        if (care != null) {
                            showBlockMessage(getString(R.string.msg_healed_at, care.name));
                            Log.i("GameEngine", "Curado en " + care.name);
                        } else {
                            showBlockMessage(getString(R.string.msg_no_heal_point));
                        }
                        dismissDefeatModal();
                    }
                })
                .setNegativeButton("Seguir", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        dismissDefeatModal();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        dismissDefeatModal();
                    }
                })
                .create();
        defeatDialog.show();
        // Curar solo con dojo cercano real (sin punto <25 m se deshabilita).
        android.location.Location fix = getLastLocationFix();
        boolean nearCare = fix != null
                && nearestDojoWithin(fix.getLatitude(), fix.getLongitude(), 25.0) != null;
        android.widget.Button healBtn =
                defeatDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (healBtn != null) healBtn.setEnabled(nearCare);
    }

    /**
     * Engrane 100% funcional: idioma, ROM, guardado, resumen e info de tema.
     */
    private void setupOverlayActions() {
        findViewById(R.id.tv_opt_language).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLanguageSheet(true);
            }
        });
        findViewById(R.id.tv_opt_rom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRomPicker();
            }
        });
        findViewById(R.id.tv_opt_theme).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBlockMessage(getString(R.string.msg_theme_classic));
            }
        });
        findViewById(R.id.tv_opt_view).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyViewMode(!currentViewMode());
            }
        });
        findViewById(R.id.tv_opt_anchor).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmAnchorFlow();
            }
        });
        findViewById(R.id.tv_opt_saves).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                java.io.File f = saveFile();
                if (f.exists()) {
                    showBlockMessage(getString(R.string.msg_save_ok_flag, storyFlag));
                } else {
                    showBlockMessage(getString(R.string.msg_no_saves));
                }
            }
        });
        // DiagnÃ³stico B3 (VISION.md): long-press vuelca la SRAM cruda a
        // filesDir/sram_dump.txt para mapear event flags reales en POCO.
        findViewById(R.id.tv_opt_saves).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                dumpSramToFile();
                return true;
            }
        });
        findViewById(R.id.tv_opt_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProfileExportPicker();
            }
        });
        findViewById(R.id.tv_opt_import).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProfileImportPicker();
            }
        });
        findViewById(R.id.tv_opt_export_route).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRouteExportPicker();
            }
        });
        findViewById(R.id.tv_opt_import_route).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRouteImportPicker();
            }
        });
        // DiagnÃ³stico B4: long-press en "Vista" vuelca frame+OAM+VRAM crudos
        // para verificar orientaciÃ³n y sprite OW sin la UI encima.
        findViewById(R.id.tv_opt_view).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                dumpPpuFrame();
                return true;
            }
        });
        findViewById(R.id.btn_overlay_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gameEngine.flushSram();
                gameEngine.saveState();
                saveGameState();
                showBlockMessage(getString(R.string.msg_save_ok_flag, storyFlag));
            }
        });
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // DIAGNÃ“STICO SRAM (VISION.md B3). Vuelca el .sav en memoria a
    // filesDir/sram_dump.txt (16 B/lÃ­nea). Sin interpretar: el mapeo de
    // event flags se hace comparando volcado fresco vs con progreso.
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    private void dumpSramToFile() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String path = null;
                int size = 0;
                try {
                    com.gpsquest96.emulator.EmulatorBridge bridge =
                            new com.gpsquest96.emulator.EmulatorBridge();
                    size = bridge.nativeSramSize();
                    java.io.File out = new java.io.File(getFilesDir(), "sram_dump.txt");
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        StringBuilder line = new StringBuilder();
                        for (int i = 0; i < size; i++) {
                            if (i % 16 == 0) {
                                if (line.length() > 0) {
                                    fos.write((line.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    line.setLength(0);
                                }
                                line.append(String.format(java.util.Locale.ROOT, "%04X: ", i));
                            }
                            line.append(String.format(java.util.Locale.ROOT, "%02X ",
                                    bridge.readSramRaw(i)));
                        }
                        if (line.length() > 0) {
                            fos.write((line.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    path = out.getAbsolutePath();
                    Log.i("GameEngine", "SRAM dump OK: " + path + " (" + size + " B)");
                } catch (Throwable t) {
                    Log.w("GameEngine", "SRAM dump omitido", t);
                }
                final String msg = (path != null)
                        ? "SRAM dump: " + size + " B"
                        : "Sin SRAM";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showBlockMessage(msg);
                    }
                });
            }
        }).start();
    }

    /**
     * DiagnÃ³stico de spec 009 (slice 1): lee la tabla de ruta inicial desde la ROM
     * y la registra. Solo lectura; no toca el juego.
     */
    private void dumpWildTableProbe(GameEngine engine) {
        logWildTableProbe(engine, 24, 3, "ruta inicial");
        logWildTableProbe(engine, 24, 7, "Casa jugador");
    }

    private void logWildTableProbe(GameEngine engine, int group, int map,
                                   String label) {
        int[] t = engine.getWildTable(group, map, EmulatorBridge.WILD_KIND_GRASS);
        if (t == null) {
            Log.i("GameEngine", "B2 probe " + group + "/" + map + " (" + label
                    + "): sin tabla");
            return;
        }
        Log.i("GameEngine", "B2 probe " + group + "/" + map + " (" + label
                + "): kind=" + t[0]
                + " tabla=0x" + Integer.toHexString(t[5])
                + " entrada=0x" + Integer.toHexString(t[6])
                + " n=" + t[7]
                + " tasas=" + t[8] + "/" + t[9] + "/" + t[10]
                + " primer=" + t[11] + "/" + t[12]);
    }

    /**
     * DiagnÃ³stico B4: vuelca a filesDir el frame PPU crudo (160x144 RGBA),
     * la OAM (160 B) y la VRAM (8 KB). Permite verificar orientaciÃ³n y el
     * sprite OW con datos exactos, sin depender de capturas con UI encima.
     * Trigger: long-press en "Vista: Juego/Mapa".
     */
    /**
     * Spec 009 slice 2: lee el mapa actual de WRAM y loguea la tabla que le
     * corresponde. Solo lectura; no dispara encuentros ni toca el juego.
     */
    private void logCurrentMapProbe(GameEngine engine) {
        int[] m = engine.getCurrentMap();
        if (m == null) {
            Log.i("GameEngine", "B2 mapa actual: invalido/sin ROM");
            return;
        }
        int group = m[0];
        int map = m[1];
        int y = m[2];
        int x = m[3];
        int[] t = engine.getWildTable(group, map, EmulatorBridge.WILD_KIND_AUTO);
        if (t == null) {
            Log.i("GameEngine", "B2 mapa actual " + group + "/" + map
                    + " (y=" + y + " x=" + x + "): sin tabla");
            return;
        }
        int ratesCount = t[3];
        StringBuilder rates = new StringBuilder();
        for (int i = 0; i < ratesCount; i++) {
            if (i > 0) rates.append('/');
            rates.append(t[8 + i]);
        }
        int firstLevel = t[8 + ratesCount];
        int firstSpecies = t[9 + ratesCount];
        Log.i("GameEngine", "B2 mapa actual " + group + "/" + map
                + " (y=" + y + " x=" + x + "): kind=" + t[0]
                + " tabla=0x" + Integer.toHexString(t[5])
                + " entrada=0x" + Integer.toHexString(t[6])
                + " n=" + t[7]
                + " tasas=" + rates
                + " primer=" + firstLevel + "/" + firstSpecies);
    }

    private void dumpPpuFrame() {
        final GameEngine engine = gameEngine;
        if (engine == null) {
            showBlockMessage("Dump frame: sin motor");
            return;
        }
        // Prueba de solo lectura de spec 009 (slice 1). Se usa ruta inicial
        // (grupo 24, mapa 3) como sonda fija; el slice 2 leerÃ¡ el mapa actual.
        dumpWildTableProbe(engine);
        // El volcado escribe VBK y los Ã­ndices BCPS/OCPS para leer ambos bancos
        // y las paletas. Si el hilo de emulaciÃ³n sigue corriendo, esas escrituras
        // pueden caer a mitad de frame y corromper el color del juego. Pausar y
        // dejar terminar el frame en curso hace el snapshot consistente.
        final boolean wasPaused = engine.isPaused();
        engine.pause();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    Thread.sleep(60); // deja que el render loop vea isPaused
                    logCurrentMapProbe(engine);
                    com.gpsquest96.emulator.core.EmulationPipeline p =
                            engine.getEmulationPipeline();
                    if (p == null || p.getPpu() == null) {
                        msg = "Dump frame: sin PPU";
                    } else {
                        java.nio.ByteBuffer buf = p.getPpu().getFrameBufferDirect();
                        if (buf == null || buf.capacity() != 160 * 144 * 4) {
                            msg = "Dump frame: buffer inválido";
                        } else {
                            buf.position(0);
                            byte[] frame = new byte[160 * 144 * 4];
                            buf.get(frame);
                            buf.position(0);
                            byte[] oam = new byte[160];
                            byte[] vram = new byte[8192];
                            byte[] vram1 = new byte[8192];
                            byte[] wram = new byte[0x2000];
                            byte[] hram = new byte[0x7F];
                            com.gpsquest96.emulator.core.EmulationPipeline.Mmu mmu = p.getMmu();
                            mmu.readBlock(0xFE00, oam, oam.length);
                            // CGB: el banco 0 tiene tiles; el banco 1 atributos
                            // Usar funciones de solo lectura seguras de EmulatorBridge sin tocar registros MMU
                            com.gpsquest96.emulator.EmulatorBridge bridge = new com.gpsquest96.emulator.EmulatorBridge();
                            mmu.readVramBlock(0, 0x8000, vram, vram.length);
                            mmu.readVramBlock(1, 0x8000, vram1, vram1.length);
                            mmu.readBlock(0xC000, wram, wram.length);
                            mmu.readBlock(0xFF80, hram, hram.length);
                            byte[] bgPal = new byte[64];
                            byte[] objPal = new byte[64];
                            for (int i = 0; i < 64; i++) {
                                bgPal[i] = (byte) bridge.readBgPalette(i);
                                objPal[i] = (byte) bridge.readObjPalette(i);
                            }
                            java.io.File dir = getFilesDir();
                            writeDump(dir, "ppu_frame.rgba", frame);
                            writeDump(dir, "ppu_oam.bin", oam);
                            writeDump(dir, "ppu_vram.bin", vram);
                            writeDump(dir, "ppu_vram1.bin", vram1);
                            writeDump(dir, "ppu_wram.bin", wram);
                            writeDump(dir, "ppu_hram.bin", hram);
                            writeDump(dir, "ppu_bg_palette.bin", bgPal);
                            writeDump(dir, "ppu_obj_palette.bin", objPal);
                            // DiagnÃ³stico B4: registros PPU/CPU vivos para
                            // distinguir LCD apagado de pantalla en blanco.
                            StringBuilder rg = new StringBuilder();
                            rg.append("LCDC=0x").append(hex(mmu.read(0xFF40))).append('\n');
                            rg.append("STAT=0x").append(hex(mmu.read(0xFF41))).append('\n');
                            rg.append("SCY=0x").append(hex(mmu.read(0xFF42))).append('\n');
                            rg.append("SCX=0x").append(hex(mmu.read(0xFF43))).append('\n');
                            rg.append("LY=0x").append(hex(mmu.read(0xFF44))).append('\n');
                            rg.append("LYC=0x").append(hex(mmu.read(0xFF45))).append('\n');
                            rg.append("BGP=0x").append(hex(mmu.read(0xFF47))).append('\n');
                            rg.append("OBP0=0x").append(hex(mmu.read(0xFF48))).append('\n');
                            rg.append("OBP1=0x").append(hex(mmu.read(0xFF49))).append('\n');
                            rg.append("WY=0x").append(hex(mmu.read(0xFF4A))).append('\n');
                            rg.append("WX=0x").append(hex(mmu.read(0xFF4B))).append('\n');
                            rg.append("IF=0x").append(hex(mmu.read(0xFF0F))).append('\n');
                            rg.append("IE=0x").append(hex(mmu.read(0xFFFF))).append('\n');
                            rg.append("JOYP=0x").append(hex(mmu.read(0xFF00))).append('\n');
                            // CGB: velocidad, bancos y selecciÃ³n de paletas.
                            rg.append("KEY1=0x").append(hex(mmu.read(0xFF4D))).append('\n');
                            rg.append("VBK=0x").append(hex(mmu.read(0xFF4F))).append('\n');
                            rg.append("SVBK=0x").append(hex(mmu.read(0xFF70))).append('\n');
                            rg.append("BCPS=0x").append(hex(mmu.read(0xFF68))).append('\n');
                            rg.append("OCPS=0x").append(hex(mmu.read(0xFF6A))).append('\n');
                            rg.append("OPRI=0x").append(hex(mmu.read(0xFF6C))).append('\n');
                            // Estado del motor de script/texto (diagnÃ³stico del PROMPT).
                            rg.append("D454=0x").append(hex(mmu.read(0xD454))).append('\n');
                            rg.append("D456=0x").append(hex(mmu.read(0xD456))).append('\n');
                            rg.append("D457=0x").append(hex(mmu.read(0xD457))).append('\n');
                            rg.append("D45E=0x").append(hex(mmu.read(0xD45E))).append('\n');
                            rg.append("D45F=0x").append(hex(mmu.read(0xD45F))).append('\n');
                            rg.append("B1=0x").append(hex(mmu.read(0xFFB1))).append('\n');
                            rg.append("B3=0x").append(hex(mmu.read(0xFFB3))).append('\n');
                            rg.append("F5=0x").append(hex(mmu.read(0xFFF5))).append('\n');
                            writeDump(dir, "ppu_regs.txt", rg.toString().getBytes("US-ASCII"));
                            Log.i("GameEngine", "PPU dump OK: frame+OAM+VRAM+regs en " + dir);
                            msg = "Dump frame OK · " + dir;
                        }
                    }
                } catch (Throwable t) {
                    Log.w("GameEngine", "PPU dump omitido", t);
                    msg = "Dump frame: error";
                }
                final String out = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!wasPaused && engine == gameEngine) {
                            engine.resume();
                        }
                        showBlockMessage(out);
                    }
                });
            }
        }).start();
    }

    private static void writeDump(java.io.File dir, String name, byte[] data)
            throws java.io.IOException {
        java.io.FileOutputStream fos =
                new java.io.FileOutputStream(new java.io.File(dir, name));
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }

    private static String hex(int v) {
        String s = Integer.toHexString(v & 0xFF).toUpperCase();
        return s.length() < 2 ? "0" + s : s;
    }

    // (purga VISION slice 2: avatar y paleta fuera; marcador neutro en GameRenderer)

    /**
     * Desacoplamiento crÃ­tico (RULES.md): el avatar SIEMPRE usa GPS fÃ­sico
     * en vivo. JamÃ¡s se usan coords guardadas (anti-teletransporte).
     */
    private void syncAvatarToRealGps() {
        if (gameEngine.getControlModeManager().getCurrentMode()
                != ControlModeManager.ControlMode.GPS_REAL) return;
        android.location.Location fix = getLastLocationFix();
        if (fix == null) return;
        gameEngine.getControlModeManager().updateSimulatedPosition(
                fix.getLatitude(), fix.getLongitude());
        requestMapTiles(fix.getLatitude(), fix.getLongitude());
        if (gameRenderer != null && fix.hasSpeed() && fix.getSpeed() > 0.5f) {
            float bearing = fix.hasBearing() ? fix.getBearing() : 180f;
            gameRenderer.noteMovement(bearing);
            // VISION.md: caminar tÃº mueve al personaje del juego (hÃ­brido).
            // El bearing GPS se traduce a la cruceta de la ROM.
            applyJoypadFromBearing(bearing);
        } else {
            releaseJoypad();
        }
    }

    /**
     * Traduce el bearing GPS (0=N, 90=E, 180=S, 270=W) a la cruceta de la ROM.
     * Gen 1/2 camina ortogonal: se presiona la cardinal dominante.
     */
    private void applyJoypadFromBearing(float bearingDeg) {
        float b = ((bearingDeg % 360f) + 360f) % 360f;
        int bits = 0xF0;
        if (b >= 315f || b < 45f) bits &= ~0x40;        // N â†’ UP
        else if (b >= 45f && b < 135f) bits &= ~0x10;   // E â†’ RIGHT
        else if (b >= 135f && b < 225f) bits &= ~0x80;  // S â†’ DOWN
        else bits &= ~0x20;                             // W â†’ LEFT
        setDirBits(bits);
    }

    private void requestMapTiles(double lat, double lon) {
        if (gameRenderer != null) gameRenderer.setMapCenter(lat, lon);
        requestOsmData(lat, lon);
    }

    /**
     * Pide datos OSM con throttle 8 s y puerta de 30 m (cortesÃ­a Overpass).
     */
    private void requestOsmData(double lat, double lon) {
        if (osmFetcher == null) return;
        long now = System.currentTimeMillis();
        if (now - lastOsmRequestMs < 8000) return;
        double dLatM = (lat - lastOsmLat) * 111320.0;
        double dLonM = (lon - lastOsmLon) * 111320.0
                * Math.cos(Math.toRadians(lat));
        if (lastOsmLat < 900.0
                && Math.sqrt(dLatM * dLatM + dLonM * dLonM) < 30.0) {
            return;
        }
        lastOsmRequestMs = now;
        lastOsmLat = lat;
        lastOsmLon = lon;
        // Corredor también en el movimiento normal: un área de 150 m perdía los
        // POIs de 150-270 m que sí están dentro del radio de trabajo de la ruta.
        osmFetcher.requestCorridor(lat, lon, OSM_CORRIDOR_RADIUS_M, OSM_CORRIDOR_SPAN_M);
    }

    // Xalapa Centro: fallback cuando no hay fix GPS ni ancla confirmada (interiores).
    private static final double HOME_LAT = 19.5275;
    private static final double HOME_LON = -96.9242;
    private static final double OSM_CORRIDOR_RADIUS_M = 150.0;
    private static final double OSM_CORRIDOR_SPAN_M = 120.0;

    /**
     * Siembra inicial: con fix usa GPS real; sin fix usa el ancla si existe y,
     * solo como último recurso, Xalapa. Antes se sembraba siempre Xalapa y sus
     * 278 elementos se acumulaban aunque el ancla estuviera a 4 km.
     */
    private void seedInitialMap() {
        gameEngine.start();
        double lat = HOME_LAT, lon = HOME_LON;
        android.location.Location fix = getLastLocationFix();
        if (fix != null) {
            lat = fix.getLatitude();
            lon = fix.getLongitude();
        } else if (anchorConfirmed()) {
            lat = anchorLat();
            lon = anchorLon();
        }
        requestMapTiles(lat, lon);
        if (fix == null) {
            // Sin GPS (interiores): SofÃ¡ visible y jugable desde el arranque.
            gameEngine.getControlModeManager().setMode(ControlModeManager.ControlMode.SOFA);
            updateModeUI(ControlModeManager.ControlMode.SOFA);
            if (joystickView != null) joystickView.invalidate();
        }
    }

    // Fisica del Modo Sofa: paso humano, no vuelo.
    private static final double SOFA_WALK_MPS = 1.4;
    private long lastJoyMoveNs = 0;

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Manejador Ãºnico del joypad (evita escritores que se pisan):
    // - joyDir:   bits 4-7 (cruceta). 0 = presionado.
    // - joyAction: bits 0-3 (A,B,Select,Start). 0 = presionado.
    // Un solo updateJoypad() escribe el byte combinado a la ROM.
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    private int joyDir = 0xF0;
    private int joyAction = 0x0F;

    private void updateJoypad() {
        android.util.Log.d("GPSQuest96", "updateJoypad joyDir=" + joyDir + " joyAction=" + joyAction);
        // joyDir ocupa el nibble alto (bits 4-7) y joyAction el bajo (0-3).
        // Son nibbles disjuntos: se combinan con OR, no con AND
        // (AND con 0xF0 & 0x0F = 0x00 => los 8 botones presionados).
        int bits = (joyDir & 0xF0) | (joyAction & 0x0F);
        try {
            gameEngine.getEmulationPipeline().getMmu().setJoypad(bits);
        } catch (Throwable ignored) {
        }
    }

    private void setDirBits(int bits) {
        if (bits == joyDir) return;
        joyDir = bits;
        updateJoypad();
    }

    private void setActionBits(int bits) {
        android.util.Log.d("GPSQuest96", "setActionBits bits=" + bits + " joyAction=" + joyAction);
        if (bits == joyAction) return;
        joyAction = bits;
        updateJoypad();
    }

    /**
     * Gamepad nativo A/B/START/SELECT (sin libs, STACK.md).
     * Cada botÃ³n limpia su bit (0 = presionado) al tocar y lo restaura al soltar.
     * Al usar bits independientes, se pueden combinar (p. ej. B + cruceta).
     */
    private void bindPadButtons() {
        bindPadButton(R.id.btn_pad_a, 0x01);      // A
        bindPadButton(R.id.btn_pad_b, 0x02);      // B
        bindPadButton(R.id.btn_pad_select, 0x04); // Select
        bindPadButton(R.id.btn_pad_start, 0x08);  // Start
    }

    private void bindPadButton(int viewId, final int mask) {
        View v = findViewById(viewId);
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.setAlpha(0.55f);
                        setActionBits(joyAction & ~mask);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.setAlpha(1.0f);
                        setActionBits(joyAction | mask);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * Mapea ejes del joystick a la cruceta de la hardware de 8 bits (P1, 0 = presionado).
     * x/y normalizados [-1,1]; y positivo = abajo en pantalla.
     */
    private void applyJoypad(float x, float y) {
        int bits = 0xF0;
        if (x < -0.3f) bits &= ~0x20; // LEFT
        if (x > 0.3f) bits &= ~0x10;  // RIGHT
        if (y < -0.3f) bits &= ~0x40; // UP
        if (y > 0.3f) bits &= ~0x80;  // DOWN
        setDirBits(bits);
    }

    private void releaseJoypad() {
        setDirBits(0xF0);
    }

    private void setupJoystick() {
        joystickView.setJoystickListener(new JoystickView.JoystickListener() {
            @Override
            public void onMove(float x, float y) {
                ControlModeManager.ControlMode mode = gameEngine.getControlModeManager().getCurrentMode();
                if (mode == ControlModeManager.ControlMode.SOFA) {
                    // Fisica de sofa a paso humano (1.4 m/s): el paso fijo
                    // 0.0001/evento (~11 m) volaba kilometros por fling y
                    // sacaba la rejilla 20x18 (88x79 m) de pantalla = verde.
                    long nowNs = System.nanoTime();
                    float dt = (lastJoyMoveNs == 0) ? 0.016f
                            : Math.min(0.5f, (nowNs - lastJoyMoveNs) / 1e9f);
                    lastJoyMoveNs = nowNs;
                    double mag = Math.sqrt(x * x + y * y);
                    if (mag < 0.15) return; // zona muerta: sin deriva
                    double stepM = SOFA_WALK_MPS * dt * Math.min(1.0, mag);
                    double[] coords = gameEngine.getControlModeManager().getCurrentCoordinates();
                    double newLat = coords[0] + (y / mag * stepM) / 111320.0;
                    double newLon = coords[1] + (x / mag * stepM)
                            / (111320.0 * Math.cos(Math.toRadians(coords[0])));
                    gameEngine.getControlModeManager().updateSimulatedPosition(newLat, newLon);
                    // RULES Â§3: el joystick mueve el mapa pero jamÃ¡s toca pasos.
                    requestMapTiles(newLat, newLon);
                    if (gameRenderer != null) {
                        gameRenderer.noteMovement((float) ((Math.toDegrees(
                                Math.atan2(x, -y)) + 360.0) % 360.0));
                    }
                    // RULES Â§2: el joystick tambiÃ©n alimenta la cruceta de la ROM
                    // para que el personaje del juego se mueva en overworld.
                    applyJoypad(x, y);
                }
            }

            @Override
            public void onRelease() {
                releaseJoypad();
            }
        });
    }

    private static final double DEFAULT_LAT = 19.5275;
    private static final double DEFAULT_LON = -96.9242;

    private void handleModeSwitch(ControlModeManager.ControlMode newMode) {
        resetMapPipeline();
        updateModeUI(newMode);
        if (newMode == ControlModeManager.ControlMode.SOFA) {
            double lat = DEFAULT_LAT;
            double lon = DEFAULT_LON;
            android.location.Location fix = getLastLocationFix();
            if (fix != null && (fix.getLatitude() != 0.0 || fix.getLongitude() != 0.0)) {
                lat = fix.getLatitude();
                lon = fix.getLongitude();
            } else {
                double[] current = gameEngine.getControlModeManager().getCurrentCoordinates();
                if (current != null && (current[0] != 0.0 || current[1] != 0.0)) {
                    lat = current[0];
                    lon = current[1];
                }
            }
            android.location.Location sofaLoc = new android.location.Location("sofa");
            sofaLoc.setLatitude(lat);
            sofaLoc.setLongitude(lon);
            gameEngine.getControlModeManager().updateSimulatedPosition(sofaLoc.getLatitude(), sofaLoc.getLongitude());
            requestMapTiles(sofaLoc.getLatitude(), sofaLoc.getLongitude());
        } else if (newMode == ControlModeManager.ControlMode.GPS_REAL) {
            syncAvatarToRealGps();
        }
    }

    private void setupModeSwitch() {
        btnModeSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ControlModeManager.ControlMode newMode =
                            gameEngine.getControlModeManager().toggleMode();
                    handleModeSwitch(newMode);
                } catch (SecurityException e) {
                    // Sin permiso de ubicaciÃ³n: refugio en Modo SofÃ¡, sin crash.
                    Log.w("GameEngine", "Modo Sofá forzado sin permiso", e);
                    gameEngine.getControlModeManager().setMode(
                            ControlModeManager.ControlMode.SOFA);
                    handleModeSwitch(ControlModeManager.ControlMode.SOFA);
                }
            }
        });

        gameEngine.getControlModeManager().setModeChangeListener(new ControlModeManager.ModeChangeListener() {
            @Override
            public void onModeChanged(final ControlModeManager.ControlMode newMode) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleModeSwitch(newMode);
                    }
                });
            }
        });
    }

    /**
     * Limpia teselas pendientes y reinicia el throttle al cambiar de modo.
     * Evita arrastrar descargas del modo anterior al hilo GL.
     */
    private void resetMapPipeline() {
        if (glSurfaceView != null) glSurfaceView.requestLayout();
    }

    private void updateModeUI(ControlModeManager.ControlMode mode) {
        if (mode == ControlModeManager.ControlMode.GPS_REAL) {
            tvModeIndicator.setText("GPS Real");
            tvModeIndicator.setTextColor(0xFF00FF00);
            joystickView.setVisibility(View.GONE);
            // Sin ROM el panel vuelve (con ROM sigue oculto por applySavedState).
            if (startPanel != null
                    && gameEngine.getSaveManager().getRomPath() == null) {
                startPanel.setVisibility(View.VISIBLE);
            }
        } else {
            tvModeIndicator.setText("Modo Sofá");
            tvModeIndicator.setTextColor(0xFFFFFF00);
            joystickView.setVisibility(View.VISIBLE);
            // En SofÃ¡ el mapa es tÃ¡ctil: colapsar panel superior (ROM vÃ­a engrane).
            if (startPanel != null) startPanel.setVisibility(View.GONE);
        }
    }

    /**
     * Entrada tÃ¡ctil desde el cuadro cero: visible sin fix GPS, oculta en
     * GPS Real con fix. Sin esperar GPU ni red (updateModeUI manda despuÃ©s).
     */
    private void assertJoystickVisibility() {
        if (joystickView == null) return;
        boolean sofa = gameEngine.getControlModeManager().getCurrentMode()
                == ControlModeManager.ControlMode.SOFA
                || getLastLocationFix() == null;
        joystickView.setVisibility(sofa ? View.VISIBLE : View.GONE);
    }

    private void setupHudUpdater() {
        if (hudHandler == null) return;
        if (hudUpdater != null) hudHandler.removeCallbacks(hudUpdater);
        hudUpdater = new Runnable() {
            @Override
            public void run() {
                updateHud();
                hudHandler.postDelayed(this, 1000);
            }
        };
        hudHandler.post(hudUpdater);
    }

    private void updateHud() {
        // Reloj digital (RULES.md Â§3)
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        tvHudTime.setText(sdf.format(new Date()));

        // PodÃ³metro
        tvHudSteps.setText("Pasos: " + stepCount);

        // Clima (RULES.md Â§6)
        tvHudWeather.setText("Clima: --");

        // Avatar siempre en GPS fÃ­sico en vivo (anti-teletransporte).
        syncAvatarToRealGps();
    }

    /**
     * PodÃ³metro hardware (RULES Â§3). Registro con try-catch para
     * dispositivos sin sensor (KitKat modificado, emuladores).
     */
    private void registerStepCounter() {
        try {
            if (sensorManager == null) {
                sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            }
            if (sensorManager == null) return;
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (stepSensor != null) {
                sensorManager.registerListener(this, stepSensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
            }
        } catch (Exception e) {
            Log.w("GameEngine", "Podómetro no disponible", e);
            stepSensor = null;
        }
    }

    private void unregisterStepCounter() {
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        } catch (Exception e) {
            Log.w("GameEngine", "Error liberando podómetro", e);
        }
        stepSensor = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
        // Filtro Â§3a: solo Modo GPS Real (el Joystick queda aislado).
        if (gameEngine == null || gameEngine.getControlModeManager().getCurrentMode()
                != ControlModeManager.ControlMode.GPS_REAL) return;
        // Filtro Â§3b: velocidad humana < 7 km/h con seÃ±al GPS real.
        android.location.Location fix = getLastLocationFix();
        if (fix == null || !fix.hasSpeed()) return;
        if (fix.getSpeed() * 3.6f >= 7.0f) return;
        // Diferencial de sesiÃ³n (el sensor es acumulativo desde el arranque).
        float total = event.values[0];
        if (stepBaseline < 0 || total < stepBaseline) {
            stepBaseline = total;
            return;
        }
        stepCount = (int) (total - stepBaseline);
        if (gameEngine != null) {
            gameEngine.onRealStep(stepCount);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private GameEngine.GameEngineListener gameEngineListener = new GameEngine.GameEngineListener() {
        @Override
        public void onFrameRendered() {
            // Frame renderizado
        }

        @Override
        public void onWeatherChanged(WeatherData data) {
            if (data == null) return; // Weather null-safe: HUD conserva Ãºltimo valor
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        tvHudWeather.setText("Clima: " + data.getDisplayString());
                        // Badge flotante: mismo dato, cadencia del throttle (â‰¤60s).
                        tvWeatherBadge.setText(data.getDisplayString());
                    } catch (Throwable t) {
                        Log.w("GameEngine", "HUD clima omitido", t);
                    }
                }
            });
        }

        @Override
        public void onNearWater(final boolean nearWater) {
            // RULES.md Â§2: agua a <20 m â†’ Modo SofÃ¡ inmediato.
            // Llega desde el hilo del GPS: el cambio de modo es seguro ahÃ­,
            // pero el HUD se toca en el hilo de UI.
            if (gameEngine != null && gameEngine.getControlModeManager() != null) {
                gameEngine.getControlModeManager().setGpsNearWater(nearWater);
            }
            Log.i("GameEngine", "Geovalla agua: " + (nearWater
                    ? "a <20 m → Modo Sofá forzado"
                    : "despejada → control real"));
            final String msg = getString(nearWater ? R.string.msg_water_sofa : R.string.msg_water_clear);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        showBlockMessage(msg);
                    } catch (Throwable t) {
                        Log.w("GameEngine", "HUD agua omitido", t);
                    }
                }
            });
        }

        @Override
        public void onEnterBlockedZone(GeofenceManager.StoryZone zone) {
            if (zone != null) {
                showBlockMessage(getString(R.string.msg_need_story_progress));
            }
        }

        @Override
        public void onCaptureSuccess() {
            // RULES.md Â§7: Captura exitosa
        }

        @Override
        public void onStatusAlert() {
            // RULES.md Â§7: Efecto de estado
        }

        @Override
        public void onSaveCompleted() {
            // Estado guardado
        }

        @Override
        public void onWildEncounter(final int species, final int level, final int mapGroup, final int mapNumber) {
            Log.i("GameEngine", "B2 spawn ROM inyectado: #" + species
                    + " Nv " + level + " mapa " + mapGroup + "/" + mapNumber);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        showBlockMessage("¡Criatura salvaje #" + species + " Nivel " + level + "!");
                        applyViewMode(true);
                    } catch (Throwable t) {
                        Log.w("GameEngine", "Auto-switch batalla omitido", t);
                    }
                }
            });
        }

        @Override
        public void onEmulatorStateChanged(final GameEngine.EmulatorState newState) {
            // Spec 016, slice 4: la UI reacciona sola al estado del emulador.
            // Ya llega en el hilo principal (GameEngine lo publica ahi).
            // BATTLE -> pantalla completa; MAP -> mapa real; INTRO/BOOT no
            // abren el mapa aun; MENU/PAUSE no cambian la vista.
            if (newState == null) return;
            Log.i("GameEngine", "016 estado emulador: " + newState);
            switch (newState) {
                case BATTLE:
                    applyViewMode(true);
                    break;
                case MAP:
                    applyViewMode(false);
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (glSurfaceView != null) glSurfaceView.onResume();
        gameEngine.resume();
        registerStepCounter();
        startFramePump();
        setupHudUpdater(); // onPause quitÃ³ el loop: sin esto el HUD se congela
        assertJoystickVisibility();
        refreshRomTitle(); // p. ej. vuelve de Config o del picker con ROM nueva
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Sin guardar durante el onboarding (evita binario flag-0 prematuro).
        if (!onboardingActive) saveGameState();
        if (glSurfaceView != null) glSurfaceView.onPause();
        if (gameEngine != null) gameEngine.pause();
        if (hudHandler != null) hudHandler.removeCallbacks(hudUpdater);
        stopFramePump();
        unregisterStepCounter();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!onboardingActive) saveGameState();
        // Anti WindowLeaked en recreate(): ningÃºn diÃ¡logo sobrevive.
        dismissPermissionDialog();
        dismissLanguageSheet();
        dismissDefeatModal();
        if (locationBindingActive) {
            try {
                unbindService(locationConnection);
            } catch (IllegalArgumentException ignored) {
                // ConexiÃ³n ya liberada por el sistema.
            }
            locationBindingActive = false;
        }
        if (bluetoothBindingActive) {
            try {
                unbindService(bluetoothConnection);
            } catch (IllegalArgumentException ignored) {
                // ConexiÃ³n ya liberada por el sistema.
            }
            bluetoothBindingActive = false;
        }
        if (dbHelper != null) dbHelper.shutdown();
        if (osmFetcher != null) osmFetcher.shutdown();
        // Puerta de Intro: onCreate pudo salir antes de inicializar el motor.
        if (gameEngine != null) gameEngine.stop();
        if (hudHandler != null) hudHandler.removeCallbacks(hudUpdater);
        stopFramePump();
    }

    /**
     * Renderer OpenGL ES 2.0.
     * Sube el frameBuffer_ nativo (ByteBuffer directo, 92.160 B) a textura y
     * lo dibuja a pantalla completa. Sin frame vÃ¡lido: checkerboard gris.
     * Log limitado a 1/300 frames. Todo corre en el hilo GL (contexto vÃ¡lido).
     */
    private static class GameRenderer implements GLSurfaceView.Renderer {

        private static final int GB_W = 160;
        private static final int GB_H = 144;

        private static final String GRID_VERTEX =
                "attribute vec2 a_position;\n" +
                "void main() { gl_Position = vec4(a_position, 0.0, 1.0); }\n";
        private static final String GRID_FRAGMENT =
                "precision mediump float;\n" +
                "uniform vec4 u_color;\n" +
                "void main() { gl_FragColor = u_color; }\n";
        private static final String TEX_VERTEX =
                "attribute vec2 a_position;\n" +
                "attribute vec2 a_texCoord;\n" +
                "varying vec2 v_texCoord;\n" +
                "void main() { gl_Position = vec4(a_position, 0.0, 1.0); v_texCoord = a_texCoord; }\n";
        private static final String TEX_FRAGMENT =
                "precision mediump float;\n" +
                "varying vec2 v_texCoord;\n" +
                "uniform sampler2D u_texture;\n" +
                "uniform int u_weatherMode;\n" +
                "uniform float u_overlayAlpha;\n" +
                "void main() {\n" +
                "    vec4 ppu = texture2D(u_texture, v_texCoord);\n" +
                "    vec3 color = ppu.rgb;\n" +
                "    if (u_weatherMode == 1) {\n" +
                "        color.b *= 1.15;\n" +
                "        color.rgb *= 0.85;\n" +
                "    } else if (u_weatherMode == 2) {\n" +
                "        color = mix(color, vec3(0.75, 0.75, 0.75), 0.25);\n" +
                "    }\n" +
                "    gl_FragColor = vec4(color, ppu.a * u_overlayAlpha);\n" +
                "}\n";
        // spec 015 B1: en Vista: Mapa la PPU NO se dibuja. El mapa es la capa
        // Ãºnica y opaca (VISION L16); la ROM solo aparece a pantalla completa
        // en Vista: Juego / Modo SofÃ¡, con alfa 1.0.

        // Limiter no bloqueante: 60 FPS por retorno pasivo (sin sleep en GL).
        private static final long TARGET_FPS = 60;
        private static final long FRAME_INTERVAL_NS = 1000000000L / TARGET_FPS;
        private long lastFrameNs = 0;

        private final EmulationPipeline pipeline;

        private volatile double mapLat = 0.0;
        private volatile double mapLon = 0.0;

        private int gridProgram = 0;
        private int texProgram = 0;
        private FloatBuffer quadBuffer;
        private FloatBuffer quadTexBuffer;
        private FloatBuffer markerBuffer;
        private int frameTexId = 0;
        private int atlasTexId = 0;
        private volatile int[] atlasPixels = null;
        private volatile boolean atlasDirty = false;
        // Avatar propio de Miguel (32x56 ARGB, indice 0 transparente).
        private int spriteTexId = 0;
        private volatile int[] spritePixels = null;
        private volatile boolean spriteDirty = false;
        private boolean spriteReady = false;
        private FloatBuffer spriteQuadBuffer;
        private GBMapRenderer gbMap;
        private volatile long lastMoveMs = 0;
        private volatile float facingDeg = 180f; // abajo por defecto
        private int frameCount = 0;
        // Modo Juego (intro intacta): solo la ROM a pantalla completa y
        // opaca, sin mapa debajo. Toggle manual en el engrane (cromo UI);
        // sin autodetecciÃ³n por flags SRAM (5c pendiente).
        private volatile boolean ppuFullscreen = false;

        public void setPpuFullscreen(boolean full) {
            ppuFullscreen = full;
        }

        /**
         * ROM (re)cargada: el initialize nativo restaura su patrÃ³n de prueba
         * opaco, asÃ­ que la base transparente se regenera en el prÃ³ximo
         * upload (ver prefillPpuOnce).
         */
        public void noteRomLoaded() {
            ppuPrefilled = false;
        }

        /**
         * Actualiza los pÃ­xeles del atlas VRAM (worker â†’ hilo GL, volatile).
         * Llamado por el worker de TileAtlas tras cada snapshot.
         */
        public void setAtlasPixels(int[] pixels) {
            atlasPixels = pixels;
            atlasDirty = true;
        }

        /**
         * Recibe el cuadro del avatar propio (32x56 ARGB, spec 017 slice 1).
         * volatile: el hilo GL lo sube en el prÃ³ximo frame.
         */
        public void setSpritePixels(int[] pixels) {
            if (pixels == null || pixels.length != com.gpsquest96.avatar.AvatarSprites.FRAME_W * com.gpsquest96.avatar.AvatarSprites.FRAME_H) return;
            spritePixels = pixels;
            spriteDirty = true;
        }

        /** Suelta el sprite (identificaciÃ³n perdida) â†’ vuelve el marcador. */
        public void clearSpritePixels() {
            if (spritePixels != null) {
                spritePixels = null;
                spriteDirty = true;
            }
        }

        GameRenderer(EmulationPipeline pipeline) {
            this.pipeline = pipeline;
        }

        /**
         * Marca movimiento + rumbo (joystick atan2 o bearing GPS). El avatar
         * propio (spec 017 slice 1) lo usa para elegir direccion y paso.
         */
        public void noteMovement(float degrees) {
            lastMoveMs = System.currentTimeMillis();
            facingDeg = degrees;
        }

        // Avatar propio (spec 017 slice 1): genero del perfil + cuadro actual.
        private com.gpsquest96.avatar.AvatarData.Gender avatarGender =
                com.gpsquest96.avatar.AvatarData.Gender.BOY;
        private int lastAvatarKey = -1;

        /** Genero del perfil (IntroActivity / SaveManager); BOY por defecto. */
        public void setAvatarGender(com.gpsquest96.avatar.AvatarData.Gender gender) {
            if (gender != null) avatarGender = gender;
            lastAvatarKey = -1; // fuerza la subida del cuadro que toque
        }

        /**
         * Elige el cuadro del avatar segun rumbo y movimiento y lo sube si
         * cambio. Hilo GL, cada frame: el paso alterna cada 180 ms mientras
         * hay movimiento; parado = cuadro A. Sin hilos nuevos.
         */
        private void updateAvatarSprite() {
            com.gpsquest96.avatar.AvatarSprites.Direction dir =
                    com.gpsquest96.avatar.AvatarSprites.directionFor(facingDeg);
            boolean mirror = com.gpsquest96.avatar.AvatarSprites.mirrorFor(facingDeg);
            int phase = com.gpsquest96.avatar.AvatarSprites.phaseFor(
                    System.currentTimeMillis(), lastMoveMs);
            int key = (avatarGender.ordinal() * 100)
                    + (dir.ordinal() * 10) + phase * 2 + (mirror ? 1 : 0);
            if (key == lastAvatarKey) return;
            lastAvatarKey = key;
            int[] px = mirror
                    ? com.gpsquest96.avatar.AvatarSprites.pixelsMirrored(avatarGender, dir, phase)
                    : com.gpsquest96.avatar.AvatarSprites.pixels(avatarGender, dir, phase);
            setSpritePixels(px);
        }


        /**
         * PosiciÃ³n exacta para el offset sub-pÃ­xel (hilo UI â†’ hilo GL).
         */
        public void setMapCenter(double lat, double lon) {
            mapLat = lat;
            mapLon = lon;
        }

        /**
         * Recibe el raster OSM (hilo worker â†’ hilo GL, swap volÃ¡til).
         * spec 015 B2/D-23: el worker suele entregar los datos ANTES de que
         * exista `gbMap` (se crea en onSurfaceCreated, hilo GL). Se guarda el
         * Ãºltimo payload y se aplica en cuanto el mapa nace; si no, el
         * lienzo se queda plano hasta moverse 30 m.
         */
        public void feedOsmData(java.util.List<OsmDataFetcher.OsmElement> elements,
                double centerLat, double centerLon) {
            pendingOsm = elements;
            pendingOsmLat = centerLat;
            pendingOsmLon = centerLon;
            pendingOsmReady = true;
            if (gbMap != null) {
                gbMap.setElements(elements, centerLat, centerLon);
                pendingOsmReady = false;
            }
        }

        /**
         * Nodos de la Ruta Maestra (spec 004 slice 2, hilo worker → hilo GL).
         * Mismo patrón que el raster: si el mapa aún no existe, se guarda.
         */
        public void feedRouteNodes(java.util.List<RouteNode> nodes) {
            pendingRoute = nodes;
            if (gbMap != null) {
                gbMap.setRouteNodes(nodes);
                pendingRoute = null;
            }
        }

        private volatile java.util.List<RouteNode> pendingRoute = null;

        private volatile java.util.List<OsmDataFetcher.OsmElement> pendingOsm = null;
        private volatile double pendingOsmLat = 0.0;
        private volatile double pendingOsmLon = 0.0;
        private volatile boolean pendingOsmReady = false;

        /**
         * Zoom UI delegado al mapa (cromo, hilo seguro: volatile).
         */
        public void setMapZoom(float z) {
            if (gbMap != null) gbMap.setZoom(z);
        }

        private int compileShader(int type, String src) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, src);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                android.util.Log.e("GLAudit", "Error compilando shader: "
                        + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private int linkProgram(String vsSrc, String fsSrc) {
            int vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc);
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            return program;
        }

        private static boolean checkShader(int shader, String tag) {
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e("GameEngine", "Shader Mali rechazado [" + tag + "]: "
                        + GLES20.glGetShaderInfoLog(shader));
                return false;
            }
            return true;
        }

        private static boolean checkProgram(int program) {
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e("GameEngine", "Link Mali rechazado: "
                        + GLES20.glGetProgramInfoLog(program));
                return false;
            }
            return true;
        }

        private boolean gridOk = false;
        private boolean texOk = false;

        private static FloatBuffer floatBufferOf(float[] verts) {
            FloatBuffer buf = ByteBuffer.allocateDirect(verts.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buf.put(verts).position(0);
            return buf;
        }

        private void buildTexturedQuad() {
            // Quad a pantalla completa. OpenGL toma la primera fila subida a la
            // textura como V=0, y la fila 0 del PPU es la de arriba: por eso el
            // borde inferior del quad lleva V=1 y el superior V=0.
            quadBuffer = floatBufferOf(new float[]{
                    -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
            quadTexBuffer = floatBufferOf(new float[]{
                    0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f});
            buildMarkerQuad();
        }

        // Tamano del marcador en NDC (se reescala con aspecto real abajo).
        private static final float MARKER_H = 0.05f;
        private int surfW = 0;
        private int surfH = 0;

        /**
         * Punto neutro de posicion (VISION.md: cromo de UI, no contenido).
         * Blanco sobre verde GB; el avatar propio lo reemplaza (spec 017 slice 1).
         * qw = qh * (surfH/surfW) para pixeles cuadrados.
         */
        private void buildMarkerQuad() {
            // spec 015 B2/D-10: el punto neutro mide lo mismo que el sprite
            // (1 celda del mapa) para que la posiciÃ³n no "flote" al hacer zoom.
            float px = 0f;
            if (gbMap != null) px = gbMap.cellPixels();
            if (px <= 0f) px = Math.min(surfW, surfH) * MARKER_H;
            if (px < 32f) px = 32f;
            float qh = (surfH > 0) ? (px / surfH) * 2f : MARKER_H;
            float qw = (surfW > 0) ? (px / surfW) * 2f : MARKER_H;
            float hw = qw / 2f;
            float hh = qh / 2f;
            markerBuffer = floatBufferOf(new float[]{
                    -hw, -hh, hw, -hh,
                    -hw, hh, hw, hh});
        }

        // ---- Quad del avatar propio (32x56 escalado, centrado) ----
        private static final float SPRITE_FRAC = 1f / 6f; // respaldo sin mapa
        private FloatBuffer spriteTexBuffer;
        /** Zoom con el que se dimensionÃ³ el quad (spec 015 B2/D-10). */
        private float spriteQuadZoom = -1f;

        private void buildSpriteQuad() {
            // spec 015 B2/D-10 + spec 017 slice 1: el avatar ocupa 1 celda de
            // ANCHO y conserva la proporcion del cuadro (32x56, alto 1,75
            // celdas), centrado en la posicion. Crece y decrece con el zoom
            // en vez de flotar a 1/6 de pantalla. Precedente: el viejo
            // PlayerRenderer 16x24, misma convencion de UV invertida.
            float px = 0f;
            if (gbMap != null) px = gbMap.cellPixels();
            if (px <= 0f) px = Math.min(surfW, surfH) * SPRITE_FRAC;
            if (px < 32f) px = 32f;
            float w = surfW > 0 ? (px / surfW) * 2f : 0.2f;
            float h = surfH > 0
                    ? (px * com.gpsquest96.avatar.AvatarSprites.FRAME_H
                            / com.gpsquest96.avatar.AvatarSprites.FRAME_W / surfH) * 2f
                    : 0.2f * com.gpsquest96.avatar.AvatarSprites.FRAME_H
                            / com.gpsquest96.avatar.AvatarSprites.FRAME_W;
            float hw = w / 2f;
            float hh = h / 2f;
            spriteQuadBuffer = floatBufferOf(new float[]{
                    -hw, -hh, hw, -hh,
                    -hw, hh, hw, hh});
            // V invertida: la fila 0 del sprite (arriba) debe quedar arriba.
            spriteTexBuffer = floatBufferOf(new float[]{
                    0f, 1f, 1f, 1f,
                    0f, 0f, 1f, 0f});
            spriteQuadZoom = (gbMap != null) ? gbMap.getZoom() : 1f;
        }

        private void createFrameTexture() {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            frameTexId = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // Pre-llenar los 92.160 B (160x144 RGBA8888) con un patrÃ³n retro GB
            java.nio.ByteBuffer initialBuffer = java.nio.ByteBuffer.allocateDirect(GB_W * GB_H * 4)
                    .order(java.nio.ByteOrder.nativeOrder());
            int colorLight = 0xFF0FBC9B; // #9BBC0F RGBA (bytes R,G,B,A)
            int colorDark = 0xFF0F380F;  // #0F380F RGBA
            for (int y = 0; y < GB_H; y++) {
                for (int x = 0; x < GB_W; x++) {
                    boolean check = ((x / 8) + (y / 8)) % 2 == 0;
                    initialBuffer.putInt(check ? colorLight : colorDark);
                }
            }
            initialBuffer.position(0);

            // Sincronizar tambiÃ©n con el DirectByteBuffer nativo de la PPU si estÃ¡ inicializado
            if (pipeline != null && pipeline.getPpu() != null) {
                try {
                    java.nio.ByteBuffer ppuBuffer = pipeline.getPpu().getFrameBufferDirect();
                    if (ppuBuffer != null && ppuBuffer.capacity() == GB_W * GB_H * 4) {
                        ppuBuffer.position(0);
                        ppuBuffer.put(initialBuffer);
                        ppuBuffer.position(0);
                        initialBuffer.position(0);
                    }
                } catch (Throwable ignored) {
                }
            }

            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    GB_W, GB_H, 0, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, initialBuffer);
        }

        // 384 tiles Ã— 8px = 3072px de ancho, 8px de alto. Textura del atlas VRAM.
        private static final int ATLAS_TEX_W = 384 * 8;
        private static final int ATLAS_TEX_H = 8;

        private void createAtlasTexture() {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            atlasTexId = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            // Pre-llenar con transparente (hasta que el worker suba datos reales).
            java.nio.ByteBuffer initial = java.nio.ByteBuffer.allocateDirect(
                    ATLAS_TEX_W * ATLAS_TEX_H * 4)
                    .order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < ATLAS_TEX_W * ATLAS_TEX_H; i++) {
                initial.put((byte) 0x00);
                initial.put((byte) 0x00);
                initial.put((byte) 0x00);
                initial.put((byte) 0x00);
            }
            initial.position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    ATLAS_TEX_W, ATLAS_TEX_H, 0, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, initial);
        }

        /**
         * Sube los pÃ­xeles del atlas VRAM a la textura GL (hilo GL).
         * Thread-safe: lee volatile, marca dirty=false al final.
         */
        private void uploadAtlasTexture() {
            if (!atlasDirty || atlasPixels == null) return;
            if (atlasTexId == 0) return;
            try {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(
                        ATLAS_TEX_W * ATLAS_TEX_H * 4)
                        .order(java.nio.ByteOrder.nativeOrder());
                for (int i = 0; i < ATLAS_TEX_W * ATLAS_TEX_H; i++) {
                    int px = atlasPixels[i];
                    buf.put((byte) ((px >> 16) & 0xFF)); // R
                    buf.put((byte) ((px >> 8) & 0xFF));  // G
                    buf.put((byte) (px & 0xFF));         // B
                    buf.put((byte) ((px >> 24) & 0xFF)); // A
                }
                buf.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexId);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        ATLAS_TEX_W, ATLAS_TEX_H,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
                atlasDirty = false;
                // spec 015 B3b: desde aquÃ­ el mapa puede pintar pixel art real
                // (antes el atlas existÃ­a en GL pero nadie lo muestreaba).
                if (gbMap != null) gbMap.setAtlasTexture(atlasTexId);
            } catch (Throwable t) {
                Log.w("GameEngine", "Atlas upload omitido", t);
            }
        }

        // ---- Textura del avatar propio (32x56, RGBA, NEAREST) ----
        private void createSpriteTexture() {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            spriteTexId = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, spriteTexId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            java.nio.ByteBuffer empty = java.nio.ByteBuffer.allocateDirect(
                    com.gpsquest96.avatar.AvatarSprites.FRAME_W * com.gpsquest96.avatar.AvatarSprites.FRAME_H * 4).order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < com.gpsquest96.avatar.AvatarSprites.FRAME_W * com.gpsquest96.avatar.AvatarSprites.FRAME_H; i++) {
                empty.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
            }
            empty.position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    com.gpsquest96.avatar.AvatarSprites.FRAME_W, com.gpsquest96.avatar.AvatarSprites.FRAME_H, 0, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, empty);
        }

        /** Sube el sprite compuesto (hilo GL). Sin datos: conserva el previo. */
        private void uploadSpriteTexture() {
            if (!spriteDirty) return;
            if (spritePixels == null || spriteTexId == 0) {
                // Sprite soltado (sin evidencia): no subir nada.
                spriteDirty = false;
                return;
            }
            try {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(
                        com.gpsquest96.avatar.AvatarSprites.FRAME_W * com.gpsquest96.avatar.AvatarSprites.FRAME_H * 4).order(java.nio.ByteOrder.nativeOrder());
                for (int i = 0; i < com.gpsquest96.avatar.AvatarSprites.FRAME_W * com.gpsquest96.avatar.AvatarSprites.FRAME_H; i++) {
                    int px = spritePixels[i];
                    // ByteBuffer RGBA: el ARGB de Java es 0xAARRGGBB.
                    buf.put((byte) ((px >> 16) & 0xFF)); // R
                    buf.put((byte) ((px >> 8) & 0xFF));  // G
                    buf.put((byte) (px & 0xFF));         // B
                    buf.put((byte) ((px >>> 24) & 0xFF)); // A
                }
                buf.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, spriteTexId);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        com.gpsquest96.avatar.AvatarSprites.FRAME_W, com.gpsquest96.avatar.AvatarSprites.FRAME_H, GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE, buf);
                spriteDirty = false;
                spriteReady = true;
            } catch (Throwable t) {
                Log.w("GameEngine", "Sprite upload omitido", t);
            }
        }

        /**
         * Dibuja un quad fullscreen con la textura indicada.
         */
        private void drawTexturedQuad(int texId, float overlayAlpha) {            if (!texOk) return; // Contingencia: programa de textura rechazado
            GLES20.glUseProgram(texProgram);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(texProgram, "u_overlayAlpha"), overlayAlpha);
            int posLoc = GLES20.glGetAttribLocation(texProgram, "a_position");
            int texLoc = GLES20.glGetAttribLocation(texProgram, "a_texCoord");
            GLES20.glUniform1i(
                    GLES20.glGetUniformLocation(texProgram, "u_texture"), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glEnableVertexAttribArray(posLoc);
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
            GLES20.glEnableVertexAttribArray(texLoc);
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, quadTexBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(posLoc);
            GLES20.glDisableVertexAttribArray(texLoc);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.05f, 0.22f, 0.05f, 1.0f); // Veredicto GL: hilo vivo
            // Blending para la cuadrÃ­cula translÃºcida sobre el mapa base.
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            gridProgram = linkProgram(GRID_VERTEX, GRID_FRAGMENT);
            gridOk = checkProgram(gridProgram);
            texProgram = linkProgram(TEX_VERTEX, TEX_FRAGMENT);
            texOk = checkProgram(texProgram);
            buildTexturedQuad();
            createFrameTexture();
            createAtlasTexture();
            createSpriteTexture();
            gbMap = new GBMapRenderer();
            if (!gbMap.initialize()) {
                Log.e("GameEngine", "Mapa GBC rechazado: sin capa de mapa");
                gbMap = null;
            } else {
                gbMap.setViewport(surfW, surfH);
                // spec 015 D-23: el raster pudo llegar antes que el mapa.
                if (pendingOsmReady) {
                    gbMap.setElements(pendingOsm, pendingOsmLat, pendingOsmLon);
                    pendingOsmReady = false;
                    Log.i("GameEngine", "OSM pendiente aplicado al mapa");
                }
                if (pendingRoute != null) {
                    gbMap.setRouteNodes(pendingRoute);
                    pendingRoute = null;
                    Log.i("GameEngine", "Ruta pendiente aplicada al mapa");
                }
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            surfW = width;
            surfH = height;
            buildMarkerQuad(); // punto neutro con la superficie real
            buildSpriteQuad(); // sprite OW con la superficie real
            if (gbMap != null) gbMap.setViewport(width, height); // celdas cuadradas
        }

        /**
         * Mapea el clima real a modo del shader (RULES.md Â§6).
         * 0 = base, 1 = lluvia, 2 = neblina.
         */
        private int weatherMode() {
            WeatherData data = WeatherService.getLastData();
            if (data == null) return 0;
            switch (data.getCondition()) {
                case RAIN: return 1;
                case FOG: return 2;
                default: return 0;
            }
        }

        /**
         * Sube el frame nativo a su textura (hilo GL). true = frame vÃ¡lido.
         */
        private boolean uploadPpuTexture() {
            try {
                if (pipeline == null) return false;
                EmulationPipeline.Ppu ppu = pipeline.getPpu();
                if (ppu == null || frameTexId == 0) return false;
                java.nio.ByteBuffer pixels = ppu.getFrameBufferDirect();
                if (pixels == null) return false;
                // Anti-overread: 160x144 RGBA8888 = 92.160 B exactos.
                if (pixels.remaining() != GB_W * GB_H * 4) return false;
                prefillPpuOnce(pixels);

                // El frame nativo lo escribe el hilo de emulaciÃ³n mientras
                // aquÃ­ se sube a GL: si no se posiciona a 0 cada vez, tras
                // el prefill (que deja position al final) glTexSubImage2D
                // lee fuera del buffer y aborta sin log. Posicionar siempre.
                pixels.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexId);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, GB_W, GB_H,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private volatile boolean ppuPrefilled = false;

        /**
         * Pre-llena una vez la RAM nativa con verde GB TRANSPARENTE
         * (R=0x0F G=0x38 B=0x0F A=0x00): pÃ­xeles vÃ¡lidos sin tapar las calles;
         * al correr la ROM, sus pÃ­xeles opacos cubren el mapa (mix por alfa).
         */
        private void prefillPpuOnce(java.nio.ByteBuffer pixels) {
            if (ppuPrefilled) return;
            ppuPrefilled = true;
            try {
                for (int i = 0, n = pixels.remaining() / 4; i < n; i++) {
                    pixels.put((byte) 0x0F);
                    pixels.put((byte) 0x38);
                    pixels.put((byte) 0x0F);
                    pixels.put((byte) 0x00);
                }
                pixels.position(0);
            } catch (Throwable t) {
                Log.w("GameEngine", "Pre-fill PPU omitido", t);
            }
        }

        /**
         * Frame PPU a pantalla completa. Con mundo debajo usa alfa bajo
         * para no tapar el mapa; sin base mantiene el alfa completo.
         */
        private void drawPpuFullscreen(float alpha) {
            GLES20.glUseProgram(texProgram);
            int modeLoc = GLES20.glGetUniformLocation(texProgram, "u_weatherMode");
            GLES20.glUniform1i(modeLoc, weatherMode());
            // ProporciÃ³n GB 160:144 (10:9). El quad es NDC a pantalla completa;
            // sin esto el frame se estiraba a la superficie del mÃ³vil y los
            // bordes del cuadro de texto se veÃ­an como barras verticales.
            // Se dibuja centrado, sin deformar, y se restaura el viewport.
            int dw = surfW;
            int dh = Math.round(surfW * 144.0f / 160.0f);
            if (dh > surfH) {
                dh = surfH;
                dw = Math.round(surfH * 160.0f / 144.0f);
            }
            int dx = (surfW - dw) / 2;
            int dy = (surfH - dh) / 2;
            GLES20.glViewport(dx, dy, dw, dh);
            drawTexturedQuad(frameTexId, alpha);
            GLES20.glViewport(0, 0, surfW, surfH);
        }

        /**
         * Punto neutro de posicion (VISION.md): quad blanco sobre el mapa.
         * Sin avatar listo; el avatar propio lo reemplaza (spec 017 slice 1).
         */
        private void drawMarker() {
            if (!gridOk || markerBuffer == null) return;
            try {
                GLES20.glUseProgram(gridProgram);
                int posLoc = GLES20.glGetAttribLocation(gridProgram, "a_position");
                int colLoc = GLES20.glGetUniformLocation(gridProgram, "u_color");
                if (posLoc < 0 || colLoc < 0) return;
                GLES20.glUniform4f(colLoc, 1f, 1f, 1f, 1f);
                GLES20.glEnableVertexAttribArray(posLoc);
                markerBuffer.position(0);
                GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, markerBuffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDisableVertexAttribArray(posLoc);
            } catch (Throwable t) {
                Log.w("GameEngine", "Marcador omitido", t);
            }
        }

        /**
         * Avatar propio sobre el mapa (spec 017 slice 1). false = sin sprite listo,
         * el llamador conserva el punto blanco neutro (RF-04).
         */
        private boolean drawSprite() {
            if (!spriteReady || spritePixels == null || spriteTexId == 0
                    || spriteQuadBuffer == null || spriteTexBuffer == null
                    || !texOk) {
                return false;
            }
            try {
                GLES20.glUseProgram(texProgram);
                GLES20.glUniform1i(
                        GLES20.glGetUniformLocation(texProgram, "u_texture"), 0);
                GLES20.glUniform1i(
                        GLES20.glGetUniformLocation(texProgram, "u_weatherMode"), 0);
                GLES20.glUniform1f(
                        GLES20.glGetUniformLocation(texProgram, "u_overlayAlpha"), 1.0f);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, spriteTexId);
                int posLoc = GLES20.glGetAttribLocation(texProgram, "a_position");
                int texLoc = GLES20.glGetAttribLocation(texProgram, "a_texCoord");
                if (posLoc < 0 || texLoc < 0) return false;
                GLES20.glEnableVertexAttribArray(posLoc);
                GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT,
                        false, 0, spriteQuadBuffer);
                GLES20.glEnableVertexAttribArray(texLoc);
                GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT,
                        false, 0, spriteTexBuffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDisableVertexAttribArray(posLoc);
                GLES20.glDisableVertexAttribArray(texLoc);
                return true;
            } catch (Throwable t) {
                Log.w("GameEngine", "Sprite omitido", t);
                return false;
            }
        }

        // Emergency log: solo si PPU y mapa fallan (throttle 5 s).
        private long lastEmergencyMs = 0;
        private boolean shiftWarned = false;

        @Override
        public void onDrawFrame(GL10 gl) {
            // Retorno pasivo si no tocan 16,67 ms (60 fps): el hardware
            // distribuye el refresco sin congelar el DisplayManager.
            long nowNs = System.nanoTime();
            if (nowNs - lastFrameNs < FRAME_INTERVAL_NS) return;
            lastFrameNs = nowNs;
            try {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                boolean ppuValid = uploadPpuTexture();
                uploadAtlasTexture();
                // Avatar propio (spec 017 slice 1): el cuadro se elige aqui
                // para que la subida del sprite lo recoja en este mismo frame.
                if (!ppuFullscreen) updateAvatarSprite();
                uploadSpriteTexture();
                if (ppuFullscreen && ppuValid) {
                    // Modo Juego: la ROM manda (intro 100% intacta y opaca).
                    drawPpuFullscreen(1.0f);
                } else {
                    // spec 015 B1: Vista: Mapa = solo datos OSM, capa Ãºnica y
                    // opaca. Sin datos no se pinta terreno (ni pasto procedural
                    // ni damero): queda el glClearColor. La PPU no se mezcla
                    // aquÃ­; se ve a pantalla completa en Vista: Juego.
                    if (gbMap != null) gbMap.draw(mapLat, mapLon);
                    if (gridProgram != 0) {
                        // spec 015 B2/D-10: el quad del avatar se rehace
                        // cuando cambia el zoom.
                        if (gbMap != null && gbMap.getZoom() != spriteQuadZoom) {
                            buildSpriteQuad();
                        }
                        // Avatar propio de Miguel; punto blanco si aun no hay.
                        if (!drawSprite()) drawMarker();
                    }
                }
            } catch (Throwable t) {
                // Hilo GL blindado: jamÃ¡s muere
                long now = System.currentTimeMillis();
                if (now - lastEmergencyMs > 5000) {
                    lastEmergencyMs = now;
                    Log.e("GameEngine", "onDrawFrame blindado", t);
                }
            }
            frameCount++;
            if (frameCount % 300 == 0) {
                Log.d("GameEngine", "onDraw frame " + frameCount);
            }
        }
    }
}

