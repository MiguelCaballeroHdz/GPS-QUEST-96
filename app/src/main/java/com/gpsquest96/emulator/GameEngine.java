package com.gpsquest96.emulator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.gpsquest96.bluetooth.AccessoryGattService;
import com.gpsquest96.gps.GeofenceManager;
import com.gpsquest96.gps.WeatherData;
import com.gpsquest96.gps.WeatherService;
import com.gpsquest96.route.Route;
import com.gpsquest96.route.RouteFormatException;
import com.gpsquest96.route.RouteProgress;
import com.gpsquest96.route.RouteValidator;
import com.gpsquest96.emulator.core.EmulationPipeline;

/**
 * Motor principal de GPS Quest '96.
 * <p>
 * Integra todos los mÃƒÂ³dulos:
 * - CPU LR35902 + MMU + PPU (emulaciÃƒÂ³n)
 * - Renderer OpenGL ES 2.0 (visualizaciÃƒÂ³n)
 * - GPS + Geovallas (ubicaciÃƒÂ³n y seguridad)
 * - Weather Service (clima y modificadores)
 * - Accessory GATT Service (Bluetooth LE)
 * - SaveManager (auto-guardado)
 * <p>
 * RULES.md Ã‚Â§5: Auto-guardado obligatorio antes de inicializar cualquier flujo de batalla.
 */
public class GameEngine {

    static {
        System.loadLibrary("gpsquest96");
    }

    private static final String TAG = "GameEngine";

    // RULES.md Ã‚Â§8 Post-Game
    public static final int LEAGUE_COMPLETE_FLAG = 200;
    public static final int BOSS_MIN_LEVEL = 90;
    private static final int SHINY_BIT = 0x01;

    private Context context;
    private Handler mainHandler;

    // MÃƒÂ³dulos de emulaciÃƒÂ³n
    private EmulatorBridge emulatorBridge;
    private ControlModeManager controlModeManager;
    private EmulationPipeline emulationPipeline;

    // MÃƒÂ³dulos de ubicaciÃƒÂ³n y clima
    private WeatherService weatherService;
    private GeofenceManager geofenceManager;

    // MÃƒÂ³dulos de Bluetooth
    private AccessoryGattService accessoryService;

    // MÃƒÂ³dulos de persistencia
    private SaveManager saveManager;

    // ÃƒÅ¡ltima cabecera leÃƒÂ­da; identidad para el perfil .pueblo.
    private EmulatorBridge.RomInfo currentRomInfo;

    // Estado del juego (volatile: UI escribe, EmuLoop lee).
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;

    // Estado actual del emulador (spec 016, slice 3). Volatile: lo escribe
    // el hilo de emulacion y lo lee la UI.
    private volatile EmulatorState currentState = EmulatorState.BOOT;
    private int[] eventFlags = new int[256]; // Variables de progreso (RULES.md Ã‚Â§4)

    // Spec 004, slice 4: avance monotónico (R5, R6, R8). El progreso
    // (posicion + resueltos[]) lo lleva RouteProgress y vive en
    // filesDir/routes/progress_<rom_key>.json, aparte de la ruta (que es
    // de solo lectura). La ruta y el archivo los pone MainActivity con
    // setRoute(); por defecto no hay ruta activa y no se evalúa nada.
    private volatile RouteProgress routeProgress;
    private java.io.File routeProgressFile;
    private RouteValidator.RomSnapshot lastRomSnapshot;
    private int lastEvaluatedFixCount = -1;
    private RouteValidator.Validation lastValidation;

    // RULES.md Ã‚Â§6: throttle de clima (la bomba de frames corre a 4fps;
    // Open-Meteo no debe consultarse mÃƒÂ¡s de 1 vez por minuto).
    private static final long WEATHER_INTERVAL_MS = 60000;
    private long lastWeatherFetchMs = 0;

    // Callback para eventos del juego
    private GameEngineListener listener;

    public interface GameEngineListener {
        void onFrameRendered();
        void onWeatherChanged(WeatherData data);
        void onNearWater(boolean nearWater);
        void onEnterBlockedZone(GeofenceManager.StoryZone zone);
        void onCaptureSuccess();
        void onStatusAlert();
        void onSaveCompleted();
        void onWildEncounter(int species, int level, int mapGroup, int mapNumber);

        /**
         * Spec 016, slice 3: el estado del emulador cambio. Metodo default
         * para que los implementadores existentes sigan compilando; el
         * slice 4 lo implementa en MainActivity con la reaccion de UI.
         */
        default void onEmulatorStateChanged(EmulatorState newState) {
        }

        /**
         * Spec 004, slice 4: un nodo de la ruta quedó resuelto. Método
         * default para que los implementadores existentes sigan compilando;
         * MainActivity lo usa para marcar `route_nodo0_resuelto` (el ancla
         * se vuelve inmutable, R1) y actualizar la línea de ruta.
         */
        default void onRouteNodeResolved(int nodeIndex) {
        }
    }

    /**
     * Estado generico del emulador (spec 016, slice 3). Los valores coinciden
     * con el enum nativo de la PPU (BOOT=0, INTRO=1, MAP=2, BATTLE=3,
     * MENU=4, PAUSE=5); -1 o desconocido se mapea a BOOT (aun no arrancado).
     */
    public enum EmulatorState {
        BOOT, INTRO, MAP, BATTLE, MENU, PAUSE;

        public static EmulatorState fromInt(int value) {
            switch (value) {
                case 1: return INTRO;
                case 2: return MAP;
                case 3: return BATTLE;
                case 4: return MENU;
                case 5: return PAUSE;
                default: return BOOT;
            }
        }
    }

    public GameEngine(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Inicializar mÃƒÂ³dulos
        this.emulatorBridge = new EmulatorBridge();
        this.controlModeManager = new ControlModeManager();
        this.weatherService = new WeatherService();
        this.geofenceManager = new GeofenceManager();
        this.accessoryService = new AccessoryGattService(context);
        this.saveManager = new SaveManager(context);
        this.emulationPipeline = new EmulationPipeline();

        setupGeofenceListener();
        setupAccessoryListener();
    }

    public void setGameEngineListener(GameEngineListener listener) {
        this.listener = listener;
    }

    /**
     * Inicializa el motor del juego.
     */
    public void initialize() {
        Log.i(TAG, "Inicializando GameEngine...");

        // Inicializar pipeline de emulaciÃƒÂ³n
        emulationPipeline.initialize();

        // Cargar ROM si existe (puerta dual-core: la GBA guardada de una
        // version anterior se limpia para no esconder el panel sin ROM).
        // Misma purga para rutas rancias: si el archivo ya no estÃƒÂ¡ en el
        // dispositivo o la carga falla, se limpia el pref para que vuelva
        // el prompt obligatorio en vez de girar sobre la ROM anterior.
        String romPath = saveManager.getRomPath();
        if (romPath != null
                && EmulatorBridge.detectKind(romPath) == EmulatorBridge.Kind.GBA) {
            Log.w(TAG, "Ruta GBA obsoleta limpiada (epico ARM pendiente): " + romPath);
            saveManager.setRomPath(null);
            romPath = null;
        }
        if (romPath != null && !(new java.io.File(romPath).exists())) {
            Log.w(TAG, "Ruta ROM rancia limpiada (archivo ausente): " + romPath);
            saveManager.setRomPath(null);
            romPath = null;
        }
        if (romPath != null) {
            EmulatorBridge.RomInfo romInfo = emulatorBridge.loadRom(romPath);
            if (romInfo != null) {
                Log.i(TAG, "ROM cargada: " + romInfo.title);
            } else {
                Log.w(TAG, "Carga ROM fallida, pref limpiado (reimportar): " + romPath);
                saveManager.setRomPath(null);
            }
        }

        // Cargar estado guardado
        saveManager.loadState(eventFlags);

        isRunning = true;
        startAudioThread();
        Log.i(TAG, "GameEngine inicializado");
    }

    private android.media.AudioTrack audioTrack = null;
    private Thread audioThread = null;
    private volatile boolean audioRunning = false;

    private synchronized void startAudioThread() {
        if (audioRunning) return;
        audioRunning = true;
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int sampleRate = 44100;
                    int minBufSize = android.media.AudioTrack.getMinBufferSize(
                            sampleRate,
                            android.media.AudioFormat.CHANNEL_OUT_STEREO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT);
                    int bufSize = Math.max(minBufSize, 4096 * 2);
                    audioTrack = new android.media.AudioTrack(
                            android.media.AudioManager.STREAM_MUSIC,
                            sampleRate,
                            android.media.AudioFormat.CHANNEL_OUT_STEREO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT,
                            bufSize,
                            android.media.AudioTrack.MODE_STREAM);
                    audioTrack.play();
                    short[] sampleBuffer = new short[1024];
                    while (audioRunning && isRunning) {
                        if (isPaused) {
                            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                            continue;
                        }
                        int count = emulatorBridge.readAudioSamples(sampleBuffer);
                        if (count > 0 && audioTrack != null) {
                            audioTrack.write(sampleBuffer, 0, count);
                        } else {
                            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "AudioTrack deshabilitado o no disponible", t);
                } finally {
                    if (audioTrack != null) {
                        try {
                            audioTrack.stop();
                            audioTrack.release();
                        } catch (Throwable ignored) {}
                        audioTrack = null;
                    }
                }
            }
        }, "GPSQuest96Audio");
        audioThread.start();
    }

    private synchronized void stopAudioThread() {
        audioRunning = false;
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
    }

    /**
     * Inicia el loop principal del juego.
     */
    public void start() {
        if (isRunning) return;
        isRunning = true;
        startAudioThread();
        Log.i(TAG, "GameEngine iniciado");
    }

    /**
     * Pausa el juego. VacÃƒÂ­a la SRAM al .sav para que un kill del
     * sistema en pausa no pierda el progreso dentro del juego.
     */
    public void pause() {
        isPaused = true;
        if (emulatorBridge != null) {
            emulatorBridge.flushSram();
        }
        Log.i(TAG, "GameEngine pausado");
    }

    /**
     * Reanuda el juego.
     */
    public void resume() {
        isPaused = false;
        startAudioThread();
        Log.i(TAG, "GameEngine reanudado");
    }

    /**
     * Detiene el juego y guarda el estado.
     */
    public void stop() {
        isRunning = false;
        stopAudioThread();
        if (emulatorBridge != null) {
            emulatorBridge.flushSram();
        }
        saveManager.saveState(eventFlags);
        Log.i(TAG, "GameEngine detenido");
    }

    /**
     * Actualiza el estado del juego (llamado cada frame).
     */
    public void update() {
        if (!isRunning || isPaused) return;

        // Ejecutar pipeline de emulaciÃƒÂ³n (CPU -> MMU -> PPU -> Renderer).
        // Un frame completo en un solo JNI (stepFrame): sin el loop Java
        // de ~17.5k llamadas por frame la bomba corrÃƒÂ­a a 4 fps y la
        // textura PPU pulsaba contra el refresco GL (titileo azul).
        emulationPipeline.getCpu().stepFrame();

        // Spec 016, slice 3: sondear el estado del emulador una vez por frame.
        checkEmulatorState();

        // Spec 004, slice 4: avance monotónico (R5, R6, R8) sobre la
        // doble validación del slice 3.
        maybeAdvanceRoute();

        // Actualizar clima periÃƒÂ³dicamente
        updateWeather();

        // Verificar geovallas (RULES.md Â§2, Â§4)
        double[] coords = controlModeManager.getCurrentCoordinates();
        android.location.Location playerLoc = new android.location.Location("gps");
        playerLoc.setLatitude(coords[0]);
        playerLoc.setLongitude(coords[1]);
        geofenceManager.updatePlayerLocation(playerLoc, eventFlags);
    }

    /**
     * Spec 016, slice 3: sondea el estado del emulador una vez por frame y
     * avisa al listener solo cuando cambia. Corre en el hilo de emulacion;
     * la notificacion se publica en el hilo principal porque la UI
     * (applyViewMode) no es segura fuera de el.
     */
    private void checkEmulatorState() {
        if (emulatorBridge == null) return;
        final EmulatorState next = EmulatorState.fromInt(emulatorBridge.getEmulatorState());
        if (next == currentState) return;
        currentState = next;
        if (listener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onEmulatorStateChanged(next);
                    }
                }
            });
        }
    }

    /**
     * Spec 004, slice 4: avance monotónico (R5, R6, R8) sobre la doble
     * validación del slice 3 (R3).
     * <p>
     * En cada fix GPS nuevo (LocationService, 5 s / 5 m) toma un snapshot
     * de la posición ROM en el frame en curso (tras stepFrame, es decir,
     * en VBlank) y deja que {@link RouteProgress#evaluate} mueva
     * {@code posicion} y {@code resueltos[]} según las tres señales. No
     * escribe en el juego ni toca eventFlags ni SaveManager: solo el
     * progreso de la ruta, que se persiste en su archivo cuando cambia.
     * <p>
     * El snapshot NO se toma cada frame: `getCurrentMap()` loguea en
     * nativo en cada llamada (009-B2) y a 60 fps saturaría el log. La
     * comparación es por fix, así que el snapshot se toma en el frame en
     * que se procesa cada fix; la posición ROM entre fixes no se necesita.
     * El fix es el REAL (`getRealLocation()`), nunca el simulado de Modo
     * Sofá (`getCurrentCoordinates()`). No corre en pausa: `update()`
     * retorna antes si el motor está pausado (p. ej. más de 7 km/h), así
     * que cruzar un nodo en vehículo no avanza `posicion` (criterio 6).
     */
    private void maybeAdvanceRoute() {
        if (routeProgress == null) return;
        if (routeProgress.isFinished()) return;
        if (controlModeManager == null || emulatorBridge == null) return;
        int fixCount = controlModeManager.getRealFixCount();
        if (fixCount == lastEvaluatedFixCount) return; // sin fix nuevo
        lastEvaluatedFixCount = fixCount;

        double[] real = controlModeManager.getRealLocation();
        RouteValidator.RomSnapshot cur =
                RouteValidator.RomSnapshot.fromCurrentMap(emulatorBridge.getCurrentMap());
        RouteProgress.Outcome outcome = routeProgress.evaluate(
                lastRomSnapshot, cur,
                new RouteValidator.GpsFix(real[0], real[1],
                        controlModeManager.getRealAccuracyM()));
        lastRomSnapshot = cur;
        lastValidation = outcome.validation;
        if (outcome.validation != null) {
            Log.i(TAG, outcome.validation.formatLog(outcome.evaluatedNodeIndex));
        }
        if (!outcome.changed) return;
        Log.i(TAG, outcome.formatLog());
        if (routeProgressFile != null) {
            try {
                routeProgress.save(routeProgressFile);
            } catch (java.io.IOException e) {
                Log.w(TAG, "No se pudo guardar el progreso de la ruta", e);
            }
        }
        if (outcome.resolvedIndex >= 0 && listener != null) {
            final int resolvedIndex = outcome.resolvedIndex;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onRouteNodeResolved(resolvedIndex);
                    }
                }
            });
        }
    }

    /**
     * Spec 004, slice 4: ruta activa + progreso (posicion/resueltos[]).
     * La pone MainActivity al generar/importar la ruta y al cargar la ROM
     * con ruta guardada; null desactiva el avance. Carga el progreso
     * guardado de {@code filesDir/routes/progress_<rom_key>.json} si
     * existe y es válido; si no existe, o es inválido, empieza en 0 (el
     * archivo corrupto se avisa en log y se sobrescribe al primer cambio,
     * nunca se aplica a medias).
     */
    public void setRoute(Route route) {
        if (route == null) {
            routeProgress = null;
            routeProgressFile = null;
            lastRomSnapshot = null;
            lastEvaluatedFixCount = -1;
            lastValidation = null;
            return;
        }
        java.io.File dir = new java.io.File(context.getFilesDir(), "routes");
        routeProgressFile = RouteProgress.fileFor(dir, route.romKey);
        RouteProgress loaded = null;
        try {
            loaded = RouteProgress.load(routeProgressFile, route);
        } catch (RouteFormatException | java.io.IOException e) {
            Log.w(TAG, "Progreso guardado inválido, se empieza en 0: " + e.getMessage());
        }
        routeProgress = (loaded != null) ? loaded : new RouteProgress(route);
        lastRomSnapshot = null;
        lastEvaluatedFixCount = -1;
        lastValidation = null;
        Log.i(TAG, "Ruta activa: " + route.romKey + " · " + route.nodes.size()
                + " nodos · " + routeProgress.toString());
    }

    /** Spec 004, slice 4: progreso de la ruta activa, o null si no hay. */
    public RouteProgress getRouteProgress() {
        return routeProgress;
    }

    /** Spec 004, slice 3: última validación R3, o null si aún no hay. */
    public RouteValidator.Validation getLastRouteValidation() {
        return lastValidation;
    }

    /** Estado actual del emulador (spec 016, slice 3). */
    public EmulatorState getCurrentEmulatorState() {
        return currentState;
    }

    private final java.util.Random random = new java.util.Random();
    private int lastEvaluatedStep = 0;

    /**
     * InyecciÃƒÂ³n de Spawns por Paso Real (Spec 009 Slice 3).
     * Dispara la probabilidad de encuentro usando las tablas de la ROM
     * asociadas al mapa actual. Solo en Modo GPS Real.
     */
    public boolean onRealStep(int stepCount) {
        // TODO (Slice 3 reescrito): reimplementar sobre API genérica de estados del emulador.
        // Por ahora no dispara encuentros; espera a la arquitectura base genérica (Spec 016).
        return false;
    }

    /**
     * Actualiza el clima usando Open-Meteo.
     * Throttle 60s + respeta el toggle de ajustes (RULES.md Ã‚Â§6).
     */
    private void updateWeather() {
        long now = System.currentTimeMillis();
        if (now - lastWeatherFetchMs < WEATHER_INTERVAL_MS) return;
        boolean enabled = context.getSharedPreferences("gpsquest96_prefs", Context.MODE_PRIVATE)
                .getBoolean("weather_enabled", true);
        if (!enabled) return;
        lastWeatherFetchMs = now;

        double[] coords = controlModeManager.getCurrentCoordinates();
        weatherService.fetchWeather(coords[0], coords[1], new WeatherService.WeatherCallback() {
            @Override
            public void onSuccess(WeatherData data) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onWeatherChanged(data);
                        }
                        // Actualizar LED del accesorio BLE segÃƒÂºn clima
                        accessoryService.setWeatherLed(data.getCondition());
                    }
                });
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Error obteniendo clima: " + message);
            }
        });
    }

    /**
     * Configura el listener de geovallas.
     */
    private void setupGeofenceListener() {
        geofenceManager.addGeofenceListener(new GeofenceManager.GeofenceListener() {
            @Override
            public void onNearWater(boolean nearWater) {
                // RULES.md Ã‚Â§2: Forzar Modo SofÃƒÂ¡
                controlModeManager.setGpsNearWater(nearWater);
                if (listener != null) {
                    listener.onNearWater(nearWater);
                }
            }

            @Override
            public void onEnterBlockedZone(GeofenceManager.StoryZone zone) {
                // RULES.md Ã‚Â§4: Mostrar barrera visual
                if (listener != null) {
                    listener.onEnterBlockedZone(zone);
                }
            }

            @Override
            public void onExitBlockedZone() {
                // Salir de zona bloqueada
            }
        });
    }

    /**
     * Configura el listener del accesorio BLE.
     */
    private void setupAccessoryListener() {
        accessoryService.setListener(new AccessoryGattService.AccessoryListener() {
            @Override
            public void onConnected() {
                Log.i(TAG, "Accesorio BLE conectado");
            }

            @Override
            public void onDisconnected() {
                Log.i(TAG, "Accesorio BLE desconectado");
            }

            @Override
            public void onButtonAPressed() {
                // RULES.md Ã‚Â§7: BotÃƒÂ³n fÃƒÂ­sico Ã¢â€ â€™ BotÃƒÂ³n "A" del emulador
                Log.i(TAG, "Botón A presionado (accesorio BLE)");
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Error accesorio BLE: " + message);
            }
        });
    }

    // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
    // MÃƒâ€°TODOS PÃƒÅ¡BLICOS
    // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

    /**
     * Carga una ROM desde el almacenamiento.
     */
    public boolean loadRom(String romPath) {
        EmulatorBridge.RomInfo romInfo = emulatorBridge.loadRom(romPath);
        if (romInfo != null) {
            currentRomInfo = romInfo;
            saveManager.setRomPath(romPath);
            return true;
        }
        return false;
    }

    /** ÃƒÅ¡ltima cabecera de ROM leÃƒÂ­da (tÃƒÂ­tulo, game code, tamaÃƒÂ±o). */
    public EmulatorBridge.RomInfo getRomInfo() {
        return currentRomInfo;
    }

    /** VacÃƒÂ­a la SRAM al `.sav` sin cerrar la ROM (exportar perfil, pausa). */
    public void flushSram() {
        if (emulatorBridge != null) {
            emulatorBridge.flushSram();
        }
    }

    /**
     * Cierra y reabre la ROM actual para que el core vuelva a cargar el `.sav`
     * desde disco. Necesario tras importar un `.pueblo`.
     */
    public boolean reloadRom() {
        return reloadRom(true);
    }

    /**
     * @param flushBefore false tras importar un `.pueblo`: el `.sav` en disco es
     *                    la fuente y no debe pisarse con la SRAM vieja en memoria.
     */
    public boolean reloadRom(boolean flushBefore) {
        String romPath = saveManager.getRomPath();
        if (romPath == null) return false;
        try {
            if (flushBefore) {
                emulatorBridge.flushSram();
            }
            emulatorBridge.nativeCloseRom();
            currentRomInfo = null;
            return loadRom(romPath);
        } catch (Throwable t) {
            Log.e(TAG, "No se pudo recargar la ROM", t);
            return false;
        }
    }

    /**
     * Reemplaza los flags en memoria por los persistidos. A diferencia de
     * {@link #loadState()}, no mezcla flags de la sesiÃƒÂ³n anterior.
     */
    public void reloadState() {
        java.util.Arrays.fill(eventFlags, 0);
        saveManager.loadState(eventFlags);
    }

    /**
     * Guarda el estado actual (RULES.md Ã‚Â§5: Auto-guardado).
     */
    public void saveState() {
        saveManager.saveState(eventFlags);
        if (listener != null) {
            listener.onSaveCompleted();
        }
    }

    /**
     * Carga el estado guardado.
     */
    public void loadState() {
        saveManager.loadState(eventFlags);
    }

    /**
     * Establece un flag de evento (RULES.md Ã‚Â§4).
     */
    public void setEventFlag(int flag) {
        if (flag >= 0 && flag < eventFlags.length) {
            eventFlags[flag] = 1;
        }
    }

    /**
     * Verifica si un flag de evento estÃƒÂ¡ activo.
     */
    public boolean getEventFlag(int flag) {
        if (flag >= 0 && flag < eventFlags.length) {
            return eventFlags[flag] == 1;
        }
        return false;
    }

    // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
    // VISION.md B3: flags reales de SRAM (mecanismo listo, sin mapa).
    // Cada entrada es {offset en el .sav, bit}. Tabla VACÃƒÂA a propÃƒÂ³sito:
    // las direcciones se llenan con el volcado POCO (sram_dump.txt),
    // nunca adivinadas. Mientras tanto todo sigue por eventFlags[].
    // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
    private static final int[][] SRAM_FLAG_MAP = {
    };

    /**
     * Lee un bit de la SRAM cruda del cartucho. -1 = sin SRAM o sin mapa.
     */
    public int readSramFlagBit(int mapIndex) {
        try {
            if (emulatorBridge == null) return -1;
            if (mapIndex < 0 || mapIndex >= SRAM_FLAG_MAP.length) return -1;
            int off = SRAM_FLAG_MAP[mapIndex][0];
            int bit = SRAM_FLAG_MAP[mapIndex][1] & 0x07;
            int size = emulatorBridge.nativeSramSize();
            if (off < 0 || off >= size) return -1;
            return (emulatorBridge.readSramRaw(off) >> bit) & 0x01;
        } catch (Throwable t) {
            return -1;
        }
    }

    // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â
    // RULES.md Ã‚Â§8: Post-Game (BOSS + Varicolor)
    // Ã¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢ÂÃ¢â€¢Â

    public boolean isLeagueComplete() { return getEventFlag(LEAGUE_COMPLETE_FLAG); }

    public static boolean isShinyVariant(int speciesFlags) { return (speciesFlags & SHINY_BIT) != 0; }

    /**
     * Ã‚Â§8.1 BOSS bloqueado sin Liga.
     */
    public boolean canEncounterBoss(int level, boolean isLegendary) {
        if (isLegendary && level > BOSS_MIN_LEVEL && !isLeagueComplete()) return false;
        return true;
    }

    /**
     * Ã‚Â§5 + Ã‚Â§8.2 entrada ÃƒÂºnica de batalla con auto-guardado.
     * Ã‚Â§8 congelado (RULES.md regla 5): no se inyecta nada en RAM.
     */
    public boolean startBossEncounter(int level, boolean isLegendary, int speciesFlags) {
        saveState();
        // Post-game Ã‚Â§8 congelado: nunca se inicia un BOSS.
        return false;
    }

    /**
     * Ã‚Â§8.3 captura 100% shiny (ignora ballRate/hp).
     */
    public boolean tryCalculateCapture(boolean isShiny, float ballRate, float hpFraction) {
        if (isShiny) { notifyCaptureSuccess(); return true; }
        return ballRate > hpFraction;
    }

    /**
     * Notifica una captura exitosa (RULES.md Ã‚Â§7).
     */
    public void notifyCaptureSuccess() {
        accessoryService.triggerCaptureSuccess();
        if (listener != null) {
            listener.onCaptureSuccess();
        }
    }

    /**
     * Notifica un efecto de estado (veneno/quemadura) (RULES.md Ã‚Â§7).
     */
    public void notifyStatusAlert() {
        accessoryService.triggerStatusAlert();
        if (listener != null) {
            listener.onStatusAlert();
        }
    }

    /**
     * Conecta con el accesorio BLE.
     */
    public void connectAccessory(android.bluetooth.BluetoothDevice device) {
        accessoryService.connect(device);
    }

    /**
     * Desconecta el accesorio BLE.
     */
    public void disconnectAccessory() {
        accessoryService.disconnect();
    }

    /**
     * Obtiene el gestor de modos de control.
     */
    public ControlModeManager getControlModeManager() {
        return controlModeManager;
    }

    /**
     * Obtiene el servicio de clima.
     */
    public WeatherService getWeatherService() {
        return weatherService;
    }

    /**
     * Obtiene el gestor de geovallas.
     */
    public GeofenceManager getGeofenceManager() {
        return geofenceManager;
    }

    /**
     * Obtiene el servicio de guardado.
     */
    public SaveManager getSaveManager() {
        return saveManager;
    }

    /**
     * Obtiene el pipeline de emulaciÃƒÂ³n (para el renderer Java).
     */
    public EmulationPipeline getEmulationPipeline() {
        return emulationPipeline;
    }

    /**
     * Tabla de encuentro de Gen 2 para diagnÃƒÂ³stico (spec 009, solo lectura).
     * No modifica la ROM ni el estado del juego.
     */
    public int[] getWildTable(int group, int map, int kind) {
        if (emulatorBridge == null) {
            return null;
        }
        return emulatorBridge.getWildTable(group, map, kind);
    }

    /**
     * Mapa actual del jugador (spec 009, slice 2: solo lectura).
     * No modifica la ROM ni el estado del juego.
     */
    public int[] getCurrentMap() {
        if (emulatorBridge == null) {
            return null;
        }
        return emulatorBridge.getCurrentMap();
    }

    /**
     * Verifica si el juego estÃƒÂ¡ en ejecuciÃƒÂ³n.
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Verifica si el juego estÃƒÂ¡ pausado.
     */
    public boolean isPaused() {
        return isPaused;
    }
}


