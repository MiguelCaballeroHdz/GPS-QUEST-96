package com.gpsquest96.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Utilidad para cambiar el idioma de la aplicación dinámicamente.
 * Persiste la preferencia en SharedPreferences.
 */
public class LocaleHelper {

    private static final String PREFS_NAME = "gpsquest96_prefs";
    private static final String KEY_LANGUAGE = "language";

    /**
     * Cambia el idioma de la aplicación y lo persiste.
     *
     * @param context  Contexto de la aplicación
     * @param language Código de idioma ("es" o "en")
     */
    public static void setLocale(Context context, String language) {
        persistLanguage(context, language);
        updateResources(context, language);
    }

    /**
     * Obtiene el idioma guardado o "es" por defecto.
     */
    public static String getLanguage(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, "es");
    }

    /**
     * Aplica el idioma guardado al contexto.
     * Debe llamarse antes de setContentView() en cada Activity.
     */
    public static Context applyLocale(Context context) {
        String language = getLanguage(context);
        return updateResources(context, language);
    }

    private static void persistLanguage(Context context, String language) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, language)
                .apply();
    }

    private static Context updateResources(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        config.setLocale(locale);

        return context.createConfigurationContext(config);
    }
}
