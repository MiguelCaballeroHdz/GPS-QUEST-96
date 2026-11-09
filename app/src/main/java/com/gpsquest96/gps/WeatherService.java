package com.gpsquest96.gps;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Clima Open-Meteo ligero API 19+ (STACK.md + RULES.md §6).
 * HttpURLConnection en Thread propio. Sin OkHttp/Retrofit/Gson.
 */
public class WeatherService {

    private static final String TAG = "WeatherService";
    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    // Global para GameEngine / OpenGL ES 2.0 (paleta + spawn)
    private static volatile WeatherData lastData;

    public interface WeatherCallback {
        void onSuccess(WeatherData data);
        void onError(String message);
    }

    public WeatherService() { }

    public static WeatherData getLastData() { return lastData; }

    /**
     * Consulta async. No bloquea UI ni pipeline nativo.
     */
    public void fetchWeather(final double latitude, final double longitude,
                            final WeatherCallback callback) {
        // Locale.ROOT obligatorio: en es-MX el %.4f daría "19,5438" → HTTP 400.
        final String url = String.format(java.util.Locale.ROOT,
                "%s?latitude=%.4f&longitude=%.4f&current=weather_code&timezone=auto",
                BASE_URL, latitude, longitude);

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestMethod("GET");
                    conn.setDoInput(true);
                    conn.connect();

                    if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        postError(callback, "Error HTTP: " + conn.getResponseCode());
                        return;
                    }

                    StringBuilder sb = new StringBuilder();
                    try (InputStream in = conn.getInputStream();
                         BufferedReader r = new BufferedReader(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }

                    WeatherData data = parseWeatherResponse(sb.toString());
                    if (data == null) {
                        postError(callback, "Respuesta sin weather_code");
                        return;
                    }
                    lastData = data; // inyección tiempo real §6
                    callback.onSuccess(data);

                } catch (Exception e) {
                    Log.e(TAG, "Error de red", e);
                    postError(callback, "Error de red: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    private void postError(WeatherCallback callback, String msg) {
        if (callback != null) callback.onError(msg);
    }

    /**
     * Extrae current.weather_code y lo mapea vía WeatherData.setWeatherCode
     * (0-3 despejado, 51-67/80-82 lluvia, 45/48 neblina).
     */
    private WeatherData parseWeatherResponse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject current = root.optJSONObject("current");
            if (current == null || !current.has("weather_code")) return null;
            WeatherData data = new WeatherData();
            data.setWeatherCode(current.getInt("weather_code"));
            return data;
        } catch (Exception e) {
            Log.e(TAG, "Error parseando JSON", e);
            return null;
        }
    }
}
