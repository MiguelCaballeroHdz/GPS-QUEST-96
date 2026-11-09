package android.content;

/**
 * Stub de SharedPreferences para el host test. Guarda en memoria; suficiente
 * para comprobar que `prefs.json` viaja y se aplica.
 */
public interface SharedPreferences {

    String getString(String key, String defValue);

    int getInt(String key, int defValue);

    long getLong(String key, long defValue);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor clear();
        void apply();
    }
}
