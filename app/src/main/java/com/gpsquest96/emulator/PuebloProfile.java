package com.gpsquest96.emulator;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Perfil portable {@code .pueblo} (spec 008, slice 2).
 *
 * <p>Un perfil NO contiene la ROM (legalidad y tamaño): contiene su SHA-256 para
 * comprobar que se aplica sobre el cartucho correcto. Dentro del ZIP viajan:
 * <ul>
 *   <li>{@code manifest.json}: formato, versión, título, game code, hash y tamaños.</li>
 *   <li>{@code battery.sav}: SRAM cruda del cartucho (la partida del juego).</li>
 *   <li>{@code geo.sav}: guardado geográfico {@code filesDir/saves/gpsquest96.sav}.</li>
 *   <li>{@code prefs.json}: perfil del jugador, flags de evento, tregua y última ubicación.</li>
 * </ul>
 *
 * <p>El {@code .sav} se trata como binario opaco: no se interpretan offsets. La
 * escritura es atómica ({@code .tmp} → {@code .bak} → rename) para no dejar una
 * partida a medias. La importación rechaza cualquier perfil cuyo hash no coincida
 * con la ROM cargada.
 */
public final class PuebloProfile {

    private static final String TAG = "PuebloProfile";

    /** MIME para el selector SAF de exportación. */
    public static final String MIME = "application/zip";
    /** Extensión propia; el contenido es un ZIP estándar. */
    public static final String EXTENSION = ".pueblo";

    private static final String FORMAT = "gpsquest96.pueblo";
    private static final int VERSION = 1;

    private static final String ENTRY_MANIFEST = "manifest.json";
    private static final String ENTRY_BATTERY = "battery.sav";
    private static final String ENTRY_GEO = "geo.sav";
    private static final String ENTRY_PREFS = "prefs.json";

    // Límites defensivos: una SRAM de GB/GBC nunca pasa de 128 KB. El perfil
    // importado puede venir de fuera; no se confía en el tamaño declarado.
    private static final long MAX_MANIFEST = 64L * 1024L;
    private static final long MAX_BATTERY = 1024L * 1024L;
    private static final long MAX_GEO = 1024L * 1024L;
    private static final long MAX_PREFS = 256L * 1024L;

    private final Context context;
    private final SaveManager saveManager;

    public PuebloProfile(Context context) {
        this.context = context.getApplicationContext();
        this.saveManager = new SaveManager(this.context);
    }

    /** Resultado de exportar/importar, con qué partes se aplicaron. */
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final boolean battery;
        public final boolean geo;
        public final boolean prefs;

        private Result(boolean ok, String message, boolean battery, boolean geo, boolean prefs) {
            this.ok = ok;
            this.message = message;
            this.battery = battery;
            this.geo = geo;
            this.prefs = prefs;
        }

        static Result fail(String message) {
            return new Result(false, message, false, false, false);
        }

        static Result ok(boolean battery, boolean geo, boolean prefs) {
            return new Result(true, "OK", battery, geo, prefs);
        }
    }

    /**
     * Exporta el perfil a un stream ya abierto por SAF.
     *
     * @param output    stream de {@code ACTION_CREATE_DOCUMENT}
     * @param romPath   ruta de la ROM copiada en {@code filesDir/roms}
     * @param title     título de cabecera (informativo)
     * @param gameCode  game code de cabecera (informativo)
     */
    public Result export(OutputStream output, String romPath, String title, String gameCode) {
        if (output == null) {
            return Result.fail("Sin destino para el perfil");
        }
        File rom = new File(romPath);
        if (!rom.isFile()) {
            return Result.fail("ROM no encontrada");
        }

        File sav = new File(savPathFor(romPath));
        File geo = geoSaveFile();
        try {
            final String hash = sha256(rom);
            final boolean hasBattery = sav.isFile() && sav.length() > 0;
            final boolean hasGeo = geo.isFile() && geo.length() > 0;

            JSONObject manifest = new JSONObject();
            manifest.put("format", FORMAT);
            manifest.put("version", VERSION);
            manifest.put("rom", rom.getName());
            manifest.put("title", title == null ? "" : title);
            manifest.put("gameCode", gameCode == null ? "" : gameCode);
            manifest.put("sha256", hash);
            manifest.put("createdAt", System.currentTimeMillis());
            manifest.put("batterySize", hasBattery ? sav.length() : -1L);
            manifest.put("hasGeo", hasGeo);

            JSONObject prefs = saveManager.exportPrefs();
            // Leer antes de abrir el ZIP: si algo falla, no queda un perfil a medias.
            final byte[] battery = hasBattery ? readAll(sav, MAX_BATTERY) : null;
            final byte[] geoBytes = hasGeo ? readAll(geo, MAX_GEO) : null;

            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(output), StandardCharsets.UTF_8)) {
                putEntry(zip, ENTRY_MANIFEST,
                        manifest.toString().getBytes(StandardCharsets.UTF_8));
                if (battery != null) {
                    putEntry(zip, ENTRY_BATTERY, battery);
                }
                if (geoBytes != null) {
                    putEntry(zip, ENTRY_GEO, geoBytes);
                }
                putEntry(zip, ENTRY_PREFS,
                        prefs.toString().getBytes(StandardCharsets.UTF_8));
                // finish() escribe el directorio central antes del close automático.
                zip.finish();
                zip.flush();
            }

            Log.i(TAG, "Perfil exportado: battery=" + hasBattery + " geo=" + hasGeo
                    + " sha256=" + hash);
            return Result.ok(hasBattery, hasGeo, true);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error exportando perfil", e);
            return Result.fail("No se pudo exportar: " + e.getMessage());
        }
    }

    /**
     * Importa un perfil sobre la ROM cargada. Valida el SHA-256 antes de escribir
     * nada: un perfil de otro juego se rechaza sin tocar la partida actual.
     *
     * @param input   stream de {@code ACTION_OPEN_DOCUMENT}
     * @param romPath ruta de la ROM cargada en {@code filesDir/roms}
     */
    public Result importFrom(InputStream input, String romPath) {
        if (input == null) {
            return Result.fail("Sin archivo de perfil");
        }
        File rom = new File(romPath);
        if (!rom.isFile()) {
            return Result.fail("Abre la ROM antes de importar");
        }

        try {
            Map<String, byte[]> entries = readZip(input);

            byte[] manifestBytes = entries.get(ENTRY_MANIFEST);
            if (manifestBytes == null) {
                return Result.fail("Perfil sin manifest.json");
            }
            JSONObject manifest = new JSONObject(
                    new String(manifestBytes, StandardCharsets.UTF_8));
            if (!FORMAT.equals(manifest.optString("format"))) {
                return Result.fail("Formato de perfil desconocido");
            }
            if (manifest.optInt("version", -1) != VERSION) {
                return Result.fail("Versión de perfil no soportada");
            }

            final String expected = manifest.optString("sha256", "");
            final String actual = sha256(rom);
            if (expected.isEmpty() || !actual.equalsIgnoreCase(expected)) {
                return Result.fail("El perfil no corresponde a esta ROM");
            }

            final long batterySize = manifest.optLong("batterySize", -1L);
            final byte[] battery = entries.get(ENTRY_BATTERY);
            if (batterySize > 0) {
                if (battery == null) {
                    return Result.fail("Perfil sin battery.sav");
                }
                if (battery.length != batterySize) {
                    return Result.fail("battery.sav incompleto");
                }
            }

            final byte[] geo = entries.get(ENTRY_GEO);
            final byte[] prefsBytes = entries.get(ENTRY_PREFS);
            final JSONObject prefs = prefsBytes == null ? null : new JSONObject(
                    new String(prefsBytes, StandardCharsets.UTF_8));
            if (geo != null && !isValidGeo(geo)) {
                return Result.fail("geo.sav inválido");
            }

            // 1) SRAM del cartucho (la partida real del juego).
            if (battery != null && batterySize > 0) {
                writeAtomic(new File(savPathFor(romPath)), battery);
            }
            // 2) Guardado geográfico (storyFlag + coordenadas propias del juego).
            if (geo != null) {
                writeAtomic(geoSaveFile(), geo);
            }
            // 3) Preferencias del jugador y flags de evento.
            if (prefs != null) {
                saveManager.importPrefs(prefs);
            }

            Log.i(TAG, "Perfil importado: battery=" + (battery != null)
                    + " geo=" + (geo != null) + " prefs=" + (prefs != null));
            return Result.ok(batterySize > 0 && battery != null, geo != null, prefs != null);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error importando perfil", e);
            return Result.fail("No se pudo importar: " + e.getMessage());
        }
    }

    /** SHA-256 en hex minúsculas; identidad estable de la ROM. */
    public static String sha256(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("ROM no encontrada");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 no disponible", e);
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0x0F, 16));
            hex.append(Character.forDigit(b & 0x0F, 16));
        }
        return hex.toString();
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static Map<String, byte[]> readZip(InputStream input) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(input), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 64) {
                    throw new IOException("Demasiadas entradas en el perfil");
                }
                final String name = entry.getName();
                final long max = maxForEntry(name);
                if (max > 0) {
                    entries.put(name, readEntry(zip, max, name));
                }
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static long maxForEntry(String name) {
        if (ENTRY_MANIFEST.equals(name)) return MAX_MANIFEST;
        if (ENTRY_BATTERY.equals(name)) return MAX_BATTERY;
        if (ENTRY_GEO.equals(name)) return MAX_GEO;
        if (ENTRY_PREFS.equals(name)) return MAX_PREFS;
        return -1; // entrada desconocida: se ignora
    }

    private static byte[] readEntry(InputStream in, long max, String name)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > max) {
                throw new IOException("Entrada demasiado grande: " + name);
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readAll(File file, long max) throws IOException {
        long length = file.length();
        if (length <= 0 || length > max) {
            throw new IOException("Tamaño inválido: " + file.getName());
        }
        byte[] data = new byte[(int) length];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int offset = 0;
            while (offset < data.length) {
                int n = in.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            if (offset != data.length) {
                throw new IOException("Lectura incompleta: " + file.getName());
            }
        }
        return data;
    }

    /**
     * Escritura atómica con respaldo. Mismo patrón que el {@code .sav} nativo:
     * si el proceso muere, el archivo anterior sigue en {@code .bak}.
     */
    private static void writeAtomic(File dest, byte[] data) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("No se pudo crear " + parent);
        }
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        File bak = new File(dest.getAbsolutePath() + ".bak");

        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }

        if (bak.exists() && !bak.delete()) {
            tmp.delete();
            throw new IOException("No se pudo limpiar el respaldo");
        }
        if (dest.exists() && !dest.renameTo(bak)) {
            tmp.delete();
            throw new IOException("No se pudo respaldar el archivo anterior");
        }
        if (!tmp.renameTo(dest)) {
            if (bak.exists()) bak.renameTo(dest);
            tmp.delete();
            throw new IOException("No se pudo promover el archivo nuevo");
        }
    }

    /** {@code <rom>.gbc} → {@code <rom>.sav}, misma convención que el core. */
    private static String savPathFor(String romPath) {
        int slash = Math.max(romPath.lastIndexOf('/'), romPath.lastIndexOf('\\'));
        int dot = romPath.lastIndexOf('.');
        if (dot > slash) {
            return romPath.substring(0, dot) + ".sav";
        }
        return romPath + ".sav";
    }

    private File geoSaveFile() {
        return new File(new File(context.getFilesDir(), "saves"), "gpsquest96.sav");
    }

    /**
     * Formato de {@code MainActivity.saveGameState()}: MAGIC, VERSION, storyFlag,
     * timestamp (20 B). Se valida antes de pisar el guardado geográfico actual.
     */
    private static boolean isValidGeo(byte[] geo) {
        if (geo == null || geo.length < 20) return false;
        int magic = ((geo[0] & 0xFF) << 24)
                | ((geo[1] & 0xFF) << 16)
                | ((geo[2] & 0xFF) << 8)
                | (geo[3] & 0xFF);
        int version = ((geo[4] & 0xFF) << 24)
                | ((geo[5] & 0xFF) << 16)
                | ((geo[6] & 0xFF) << 8)
                | (geo[7] & 0xFF);
        return magic == 0x47513936 && version == 1;
    }
}
