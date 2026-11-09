package android.content;

import java.util.HashMap;
import java.util.Map;

/** Implementación en memoria de SharedPreferences para el host test. */
final class MemorySharedPreferences implements SharedPreferences {

    private final Map<String, String> strings = new HashMap<>();
    private final Map<String, Integer> ints = new HashMap<>();
    private final Map<String, Long> longs = new HashMap<>();

    @Override
    public String getString(String key, String defValue) {
        return strings.containsKey(key) ? strings.get(key) : defValue;
    }

    @Override
    public int getInt(String key, int defValue) {
        return ints.containsKey(key) ? ints.get(key) : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        return longs.containsKey(key) ? longs.get(key) : defValue;
    }

    @Override
    public Editor edit() {
        return new MemoryEditor(this);
    }

    void clearAll() {
        strings.clear();
        ints.clear();
        longs.clear();
    }

    private static final class MemoryEditor implements Editor {
        private final MemorySharedPreferences target;

        MemoryEditor(MemorySharedPreferences target) {
            this.target = target;
        }

        @Override
        public Editor putString(String key, String value) {
            target.strings.put(key, value);
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            target.ints.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            target.longs.put(key, value);
            return this;
        }

        @Override
        public Editor clear() {
            target.clearAll();
            return this;
        }

        @Override
        public void apply() {
            // En memoria: no hay nada que volcar.
        }
    }
}
