package com.gpsquest96.emulator;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Gestor de guardado (RULES.md §5).
 * <p>
 * §5 — Gestión de Derrotas y Preservación de la Ubicación Real:
 * 1. Auto-guardado obligatorio antes de inicializar cualquier flujo de batalla.
 * 2. En caso de derrota, se mantienen las coordenadas intactas.
 * 3. Se aplica un estado temporal de "Tregua" (180 segundos).
 * <p>
 * Persistencia: SharedPreferences para configuraciones rápidas.
 */
public class SaveManager {

    private static final String TAG = "SaveManager";
    private static final String PREFS_NAME = "gpsquest96_save";
    private static final String KEY_ROM_PATH = "rom_path";
    private static final String KEY_PLAYER_NAME = "player_name";
    private static final String KEY_PLAYER_AGE = "player_age";
    private static final String KEY_PLAYER_GENDER = "player_gender";
    private static final String KEY_EVENT_FLAGS = "event_flags";
    private static final String KEY_TRUCE_UNTIL = "truce_until";
    private static final String KEY_LAST_LAT = "last_latitude";
    private static final String KEY_LAST_LON = "last_longitude";

    // Duración de la tregua en milisegundos (180 segundos)
    private static final long TRUCE_DURATION_MS = 180000;

    private Context context;
    private SharedPreferences prefs;

    public SaveManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Guarda el estado actual del juego.
     * RULES.md §5: Auto-guardado obligatorio.
     */
    public void saveState(int[] eventFlags) {
        SharedPreferences.Editor editor = prefs.edit();

        // Guardar flags de evento
        StringBuilder flagsBuilder = new StringBuilder();
        for (int i = 0; i < eventFlags.length; i++) {
            if (eventFlags[i] != 0) {
                if (flagsBuilder.length() > 0) flagsBuilder.append(",");
                flagsBuilder.append(i);
            }
        }
        editor.putString(KEY_EVENT_FLAGS, flagsBuilder.toString());

        editor.apply();
        Log.i(TAG, "Estado guardado");
    }

    /**
     * Carga el estado guardado.
     */
    public void loadState(int[] eventFlags) {
        String flagsStr = prefs.getString(KEY_EVENT_FLAGS, "");
        if (!flagsStr.isEmpty()) {
            String[] flags = flagsStr.split(",");
            for (String flag : flags) {
                try {
                    int flagIndex = Integer.parseInt(flag.trim());
                    if (flagIndex >= 0 && flagIndex < eventFlags.length) {
                        eventFlags[flagIndex] = 1;
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parseando flag: " + flag);
                }
            }
        }
        Log.i(TAG, "Estado cargado");
    }

    /**
     * Establece la ruta de la ROM cargada.
     */
    public void setRomPath(String romPath) {
        prefs.edit().putString(KEY_ROM_PATH, romPath).apply();
    }

    /**
     * Obtiene la ruta de la ROM cargada.
     */
    public String getRomPath() {
        return prefs.getString(KEY_ROM_PATH, null);
    }

    /**
     * Guarda el perfil del jugador (Intro). Género: "BOY" o "GIRL".
     */
    public void savePlayer(String name, int age, String gender) {
        prefs.edit()
                .putString(KEY_PLAYER_NAME, name)
                .putInt(KEY_PLAYER_AGE, age)
                .putString(KEY_PLAYER_GENDER, gender)
                .apply();
    }

    public boolean hasPlayer() {
        String name = prefs.getString(KEY_PLAYER_NAME, null);
        return name != null && !name.trim().isEmpty();
    }

    public String getPlayerName() {
        return prefs.getString(KEY_PLAYER_NAME, null);
    }

    public int getPlayerAge() {
        return prefs.getInt(KEY_PLAYER_AGE, 10);
    }

    public String getPlayerGender() {
        return prefs.getString(KEY_PLAYER_GENDER, "BOY");
    }

    /**
     * Guarda la última ubicación conocida.
     * RULES.md §5: Preservación de la ubicación real.
     */
    public void saveLastLocation(double latitude, double longitude) {
        prefs.edit()
                .putLong(KEY_LAST_LAT, Double.doubleToRawLongBits(latitude))
                .putLong(KEY_LAST_LON, Double.doubleToRawLongBits(longitude))
                .apply();
    }

    /**
     * Obtiene la última ubicación conocida.
     */
    public double[] getLastLocation() {
        double lat = Double.longBitsToDouble(prefs.getLong(KEY_LAST_LAT, 0));
        double lon = Double.longBitsToDouble(prefs.getLong(KEY_LAST_LON, 0));
        return new double[]{lat, lon};
    }

    /**
     * Inicia el período de tregua (RULES.md §5).
     * Bloquea encuentros salvajes por 180 segundos.
     */
    public void startTruce() {
        long truceUntil = System.currentTimeMillis() + TRUCE_DURATION_MS;
        prefs.edit().putLong(KEY_TRUCE_UNTIL, truceUntil).apply();
        Log.i(TAG, "Tregua iniciada por 180 segundos");
    }

    /**
     * Verifica si el jugador está en tregua.
     */
    public boolean isInTruce() {
        long truceUntil = prefs.getLong(KEY_TRUCE_UNTIL, 0);
        return System.currentTimeMillis() < truceUntil;
    }

    /**
     * Obtiene los segundos restantes de tregua.
     */
    public int getTruceRemainingSeconds() {
        long truceUntil = prefs.getLong(KEY_TRUCE_UNTIL, 0);
        long remaining = truceUntil - System.currentTimeMillis();
        if (remaining <= 0) return 0;
        return (int) (remaining / 1000);
    }

    /**
     * Exporta el estado editable a un JSON para el perfil `.pueblo`.
     * No incluye `rom_path`: es específico del dispositivo.
     */
    public org.json.JSONObject exportPrefs() throws org.json.JSONException {
        org.json.JSONObject json = new org.json.JSONObject();
        json.put(KEY_PLAYER_NAME, prefs.getString(KEY_PLAYER_NAME, ""));
        json.put(KEY_PLAYER_AGE, prefs.getInt(KEY_PLAYER_AGE, 10));
        json.put(KEY_PLAYER_GENDER, prefs.getString(KEY_PLAYER_GENDER, "BOY"));
        json.put(KEY_EVENT_FLAGS, prefs.getString(KEY_EVENT_FLAGS, ""));
        json.put(KEY_TRUCE_UNTIL, prefs.getLong(KEY_TRUCE_UNTIL, 0L));
        json.put(KEY_LAST_LAT, prefs.getLong(KEY_LAST_LAT, 0L));
        json.put(KEY_LAST_LON, prefs.getLong(KEY_LAST_LON, 0L));
        return json;
    }

    /**
     * Aplica un perfil `.pueblo` sobre las preferencias actuales. Solo escribe
     * las claves presentes; no borra nada que el perfil no traiga.
     */
    public void importPrefs(org.json.JSONObject json) {
        if (json == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (json.has(KEY_PLAYER_NAME)) {
            editor.putString(KEY_PLAYER_NAME, json.optString(KEY_PLAYER_NAME, ""));
        }
        if (json.has(KEY_PLAYER_AGE)) {
            int age = json.optInt(KEY_PLAYER_AGE, 10);
            if (age < 0) age = 0;
            if (age > 120) age = 120;
            editor.putInt(KEY_PLAYER_AGE, age);
        }
        if (json.has(KEY_PLAYER_GENDER)) {
            String gender = json.optString(KEY_PLAYER_GENDER, "BOY");
            editor.putString(KEY_PLAYER_GENDER,
                    "GIRL".equalsIgnoreCase(gender) ? "GIRL" : "BOY");
        }
        if (json.has(KEY_EVENT_FLAGS)) {
            editor.putString(KEY_EVENT_FLAGS, json.optString(KEY_EVENT_FLAGS, ""));
        }
        if (json.has(KEY_TRUCE_UNTIL)) {
            editor.putLong(KEY_TRUCE_UNTIL, json.optLong(KEY_TRUCE_UNTIL, 0L));
        }
        if (json.has(KEY_LAST_LAT)) {
            editor.putLong(KEY_LAST_LAT, json.optLong(KEY_LAST_LAT, 0L));
        }
        if (json.has(KEY_LAST_LON)) {
            editor.putLong(KEY_LAST_LON, json.optLong(KEY_LAST_LON, 0L));
        }
        editor.apply();
        Log.i(TAG, "Perfil importado en preferencias");
    }

    /**
     * Limpia todos los datos guardados.
     */
    public void clearAll() {
        prefs.edit().clear().apply();
        Log.i(TAG, "Datos guardados eliminados");
    }
}
