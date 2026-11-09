package com.gpsquest96.gps;

/**
 * Modelo de datos del clima según Open-Meteo.
 * <p>
 * RULES.md §6: El juego adapta el clima visual y las tasas de spawn
 * según las condiciones meteorológicas reales de la ubicación del usuario.
 */
public class WeatherData {

    public enum WeatherCondition {
        CLEAR,          // Despejado
        CLOUDY,         // Nublado
        RAIN,           // Lluvia
        FOG,            // Neblina
        SNOW,           // Nieve
        THUNDERSTORM,   // Tormenta
        UNKNOWN
    }

    private double temperature;
    private int weatherCode;
    private WeatherCondition condition;
    private double windSpeed;
    private int humidity;

    public WeatherData() {
        this.condition = WeatherCondition.UNKNOWN;
    }

    /**
     * Convierte el código WMO de Open-Meteo a condición climática.
     * Códigos: https://open-meteo.com/en/docs
     */
    public static WeatherCondition fromWmoCode(int code) {
        if (code == 0) return WeatherCondition.CLEAR;
        if (code >= 1 && code <= 3) return WeatherCondition.CLOUDY;
        if (code >= 45 && code <= 48) return WeatherCondition.FOG;
        if (code >= 51 && code <= 67) return WeatherCondition.RAIN;
        if (code >= 71 && code <= 77) return WeatherCondition.SNOW;
        if (code >= 80 && code <= 82) return WeatherCondition.RAIN;
        if (code >= 95 && code <= 99) return WeatherCondition.THUNDERSTORM;
        return WeatherCondition.UNKNOWN;
    }

    // Getters y Setters

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getWeatherCode() {
        return weatherCode;
    }

    public void setWeatherCode(int weatherCode) {
        this.weatherCode = weatherCode;
        this.condition = fromWmoCode(weatherCode);
    }

    public WeatherCondition getCondition() {
        return condition;
    }

    public double getWindSpeed() {
        return windSpeed;
    }

    public void setWindSpeed(double windSpeed) {
        this.windSpeed = windSpeed;
    }

    public int getHumidity() {
        return humidity;
    }

    public void setHumidity(int humidity) {
        this.humidity = humidity;
    }

    /**
     * Retorna un string legible para el HUD.
     */
    public String getDisplayString() {
        switch (condition) {
            case CLEAR: return "Sol";
            case CLOUDY: return "Nublado";
            case RAIN: return "Lluvia";
            case FOG: return "Neblina";
            case SNOW: return "Nieve";
            case THUNDERSTORM: return "Tormenta";
            default: return "--";
        }
    }

    /**
     * Verifica si el clima afecta el spawn de criaturas.
     * RULES.md §6: Ciertos climas priorizan tipos específicos.
     */
    public boolean affectsSpawn() {
        return condition == WeatherCondition.RAIN
                || condition == WeatherCondition.FOG
                || condition == WeatherCondition.THUNDERSTORM;
    }
}
