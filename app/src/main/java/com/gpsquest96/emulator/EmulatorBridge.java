package com.gpsquest96.emulator;

import android.util.Log;

/**
 * Puente Java-NDK para el motor de emulaciÃ³n.
 * <p>
 * Carga la biblioteca nativa libgpsquest96.so y expone mÃ©todos
 * para interactuar con el cargador de ROM en C++.
 * <p>
 * SegÃºn RULES.md Â§1: Las ROMs son aportadas por el usuario final
 * de forma privada. Este puente solo lee archivos locales.
 */
public class EmulatorBridge {

    private static final String TAG = "EmulatorBridge";

    /** Familia de consola decidida por extension (puerta del core dual). */
    public enum Kind {
        GB,      // .gb / .gbc -> core LR35902 (este .so)
        GBA,     // .gba -> core ARM7 (epico en construccion)
        UNKNOWN
    }

    /**
     * Decide que emulacion abrir por la extension, sin tocar el archivo.
     * Punto de enrutado: cuando el core ARM aterrice, la rama GBA llamara
     * a su propio nativeOpenGbaRom en vez de degradar a null.
     */
    public static Kind detectKind(String romPath) {
        if (romPath == null) return Kind.UNKNOWN;
        String lower = romPath.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".gba")) return Kind.GBA;
        if (lower.endsWith(".gb") || lower.endsWith(".gbc")) return Kind.GB;
        return Kind.UNKNOWN;
    }

    static {
        System.loadLibrary("gpsquest96");
    }

    /**
     * Abre una ROM en modo streaming.
     *
     * @param romPath Ruta absoluta al archivo .gb/.gbc
     * @return 0 si es exitoso, negativo si hay error
     */
    public native int nativeOpenRom(String romPath);

    /**
     * Obtiene el Game Code (identificador de 4 bytes) de la ROM.
     *
     * @return Game Code o null si no hay ROM cargada
     */
    public native String nativeGetGameCode();

    /**
     * Obtiene el tÃ­tulo del juego.
     *
     * @return TÃ­tulo o null si no hay ROM cargada
     */
    public native String nativeGetTitle();

    /**
     * Obtiene el tipo de cartucho.
     *
     * @return Tipo de cartucho o -1 si hay error
     */
    public native int nativeGetCartridgeType();

    /**
     * Obtiene el tamaÃ±o de la ROM.
     *
     * @return TamaÃ±o de la ROM o -1 si hay error
     */
    public native int nativeGetRomSize();

    /**
     * Cierra la ROM y libera recursos.
     */
    public native void nativeCloseRom();

    /**
     * VacÃ­a la SRAM del cartucho al .sav (sin cerrar la ROM).
     * Seguro para llamar en onPause: no desmonta nada.
     */
    public native void nativeFlushSram();

    /**
     * Flush tolerante a fallos: si el .so no estÃ¡ o no hay ROM,
     * registra y sigue sin crashear el ciclo de vida.
     */
    public void flushSram() {
        try {
            nativeFlushSram();
        } catch (Throwable t) {
            Log.w(TAG, "Flush SRAM omitido", t);
        }
    }

    /**
     * DiagnÃ³stico SRAM cruda: tamaÃ±o del .sav en memoria (0 si no hay).
     */
    public native int nativeSramSize();

    /**
     * DiagnÃ³stico SRAM cruda: byte por offset (0xFF si fuera de rango).
     * Para mapear event flags reales con datos de POCO (VISION.md B3).
     */
    public native int nativeReadSramRaw(int offset);

    public native int nativeReadBgPalette(int index);
    public native int nativeReadObjPalette(int index);
    public native int nativeReadVramBank(int bank, int address);

    /**
     * Lectura tolerante a fallos para diagnÃ³stico.
     */
    public int readSramRaw(int offset) {
        try {
            return nativeReadSramRaw(offset) & 0xFF;
        } catch (Throwable t) {
            return 0xFF;
        }
    }

    public int readBgPalette(int index) {
        try {
            return nativeReadBgPalette(index) & 0xFF;
        } catch (Throwable t) {
            return 0;
        }
    }

    public int readObjPalette(int index) {
        try {
            return nativeReadObjPalette(index) & 0xFF;
        } catch (Throwable t) {
            return 0;
        }
    }

    public int readVramBank(int bank, int address) {
        try {
            return nativeReadVramBank(bank, address) & 0xFF;
        } catch (Throwable t) {
            return 0;
        }
    }

    public native int nativeReadAudioSamples(short[] dst);

    public int readAudioSamples(short[] dst) {
        try {
            return nativeReadAudioSamples(dst);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Tipos de tabla de encuentro (spec 009). */
    public static final int WILD_KIND_AUTO = 0;
    public static final int WILD_KIND_GRASS = 1;
    public static final int WILD_KIND_WATER = 2;

    /**
     * Tabla de encuentro de Gen 2 para un mapa (solo lectura).
     *
     * @param group grupo de mapa (wMapGroup)
     * @param map   nÃºmero de mapa (wMapNumber)
     * @param kind  0 auto, 1 grass, 2 water
     * @return int[] con formato documentado en native_bridge.cpp, o null
     */
    public native int[] nativeGetWildTable(int group, int map, int kind);

    /**
     * Mapa actual del jugador (spec 009, slice 2: solo lectura).
     *
     * @return int[]{wMapGroup, wMapNumber, wYCoord, wXCoord} o null si no
     *         hay ROM/MMU o el mapa no es un tramo valido.
     */
    public native int[] nativeGetCurrentMap();

    public int[] getCurrentMap() {
        try {
            return nativeGetCurrentMap();
        } catch (Throwable t) {
            Log.w(TAG, "Mapa actual no disponible", t);
            return null;
        }
    }

    /**
     * Estado actual del emulador (spec 016, slice 3).
     *
     * @return valor de EmulatorState (BOOT=0, INTRO=1, MAP=2, BATTLE=3,
     *         MENU=4, PAUSE=5) o -1 si el PPU aun no existe.
     */
    public native int nativeGetEmulatorState();

    /** Wrapper tolerante: nunca crashea la UI si el .so no tiene la funcion. */
    public int getEmulatorState() {
        try {
            return nativeGetEmulatorState();
        } catch (Throwable t) {
            Log.w(TAG, "Estado del emulador no disponible", t);
            return -1;
        }
    }

    /** Wrapper tolerante: nunca crashea la UI si el .so no tiene la funciÃ³n. */
    public int[] getWildTable(int group, int map, int kind) {
        try {
            return nativeGetWildTable(group, map, kind);
        } catch (Throwable t) {
            Log.w(TAG, "Tabla de encuentro no disponible", t);
            return null;
        }
    }

    public int[] getWildTable(int group, int map) {
        return getWildTable(group, map, WILD_KIND_AUTO);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // MÃ‰TODOS DE CONVENIENCIA (Java wrapper)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Carga una ROM y retorna su informaciÃ³n de cabecera.
     * <p>
     * Puerta dual-core: .gb/.gbc van al core GB; .gba se rechaza con log
     * honesto hasta que el core ARM aterrice (nada de boot basura).
     *
     * @param romPath Ruta absoluta al archivo
     * @return InformaciÃ³n de la ROM o null si hay error o es GBA pendiente
     */
    public RomInfo loadRom(String romPath) {
        try {
            Kind kind = detectKind(romPath);
            if (kind == Kind.GBA) {
                Log.w(TAG, "ROM GBA rechazada: core ARM pendiente (epico en construccion): "
                        + fileNameOf(romPath));
                return null;
            }
            int result = nativeOpenRom(romPath);
            if (result != 0) {
                return null;
            }

            RomInfo info = new RomInfo();
            info.kind = (kind == Kind.UNKNOWN) ? Kind.GB : kind;
            info.gameCode = nativeGetGameCode();
            info.title = nativeGetTitle();
            info.cartridgeType = nativeGetCartridgeType();
            info.romSize = nativeGetRomSize();

            // Si el header viene basura, usar el nombre del archivo.
            if (info.title == null || info.title.trim().isEmpty()
                    || !info.title.matches("[\\x20-\\x7E]+")) {
                info.title = fileNameOf(romPath);
            }

            return info;
        } catch (Throwable t) {
            // Cualquier fallo nativo (p. ej. JNI): no crashear, degradar a nombre.
            Log.e(TAG, "Error cargando ROM, fallback a nombre de archivo", t);
            return fallbackInfo(romPath);
        }
    }

    private static RomInfo fallbackInfo(String romPath) {
        RomInfo info = new RomInfo();
        info.title = fileNameOf(romPath);
        return info;
    }

    private static String fileNameOf(String romPath) {
        if (romPath == null) return "ROM";
        int slash = Math.max(romPath.lastIndexOf('/'), romPath.lastIndexOf('\\'));
        String name = (slash >= 0) ? romPath.substring(slash + 1) : romPath;
        return name.isEmpty() ? "ROM" : name;
    }

    /**
     * Estructura con la informaciÃ³n de cabecera de una ROM.
     */
    public static class RomInfo {
        public Kind kind = Kind.GB;
        public String gameCode;
        public String title;
        public int cartridgeType;
        public int romSize;

        @Override
        public String toString() {
            return "RomInfo{" +
                    "gameCode='" + gameCode + '\'' +
                    ", title='" + title + '\'' +
                    ", cartridgeType=0x" + Integer.toHexString(cartridgeType) +
                    ", romSize=0x" + Integer.toHexString(romSize) +
                    '}';
        }
    }
}

