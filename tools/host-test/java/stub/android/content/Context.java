package android.content;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Stub mínimo de Context para el host test de `.pueblo`.
 * Solo implementa lo que usan SaveManager y PuebloProfile.
 */
public class Context {

    public static final int MODE_PRIVATE = 0;

    private final File filesDir;
    private final Map<String, SharedPreferences> prefs = new HashMap<>();

    public Context(File filesDir) {
        this.filesDir = filesDir;
    }

    public Context getApplicationContext() {
        return this;
    }

    public File getFilesDir() {
        return filesDir;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences existing = prefs.get(name);
        if (existing != null) return existing;
        SharedPreferences created = new MemorySharedPreferences();
        prefs.put(name, created);
        return created;
    }
}
