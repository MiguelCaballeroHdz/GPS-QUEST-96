package com.gpsquest96.route;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
 * Paquete portable de una Ruta Maestra (spec 004 §6, slice 6).
 *
 * <p>Mueve la ruta entre dispositivos sin ejecutar contenido ajeno: dentro del ZIP
 * viajan solo datos.
 * <ul>
 *   <li>{@code manifest.json}: formato, versión, {@code rom_key}, número de nodos,
 *       checksum SHA-256 de la ruta y fecha de creación.</li>
 *   <li>{@code route.json}: el JSON de {@link Route} (esquema v1).</li>
 * </ul>
 *
 * <p>El ZIP se extrae en memoria con topes de tamaño, se rechazan nombres con
 * {@code ..}, rutas absolutas y entradas desconocidas. La importación exige que
 * {@code rom_key} coincida con la partida cargada: una ruta de otro juego no se
 * aplica. El checksum es opcional (si viene, se comprueba). Nada se ejecuta.
 */
public final class RouteBundle {

    /** MIME para el selector SAF. */
    public static final String MIME = "application/zip";
    /** Extensión propia; el contenido es un ZIP estándar. */
    public static final String EXTENSION = ".ruta";

    private static final String FORMAT = "gpsquest96.route";
    private static final int VERSION = 1;

    private static final String ENTRY_MANIFEST = "manifest.json";
    private static final String ENTRY_ROUTE = "route.json";

    // Límites defensivos: una ruta de 500 nodos ronda 100 KB. El archivo importado
    // puede venir de fuera; no se confía en el tamaño declarado por el ZIP.
    private static final long MAX_MANIFEST = 64L * 1024L;
    private static final long MAX_ROUTE = 2L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 8;

    private RouteBundle() {
    }

    /** Resultado de exportar/importar con el motivo exacto si falla. */
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final Route route;

        private Result(boolean ok, String message, Route route) {
            this.ok = ok;
            this.message = message;
            this.route = route;
        }

        static Result fail(String message) {
            return new Result(false, message, null);
        }

        static Result ok(Route route) {
            return new Result(true, "OK", route);
        }
    }

    /**
     * Exporta una ruta a un stream ya abierto por SAF.
     *
     * @param route  ruta validada (nunca datos crudos sin comprobar)
     * @param output stream de {@code ACTION_CREATE_DOCUMENT}
     */
    public static Result export(Route route, OutputStream output) {
        if (route == null) {
            return Result.fail("Ruta vacía");
        }
        if (output == null) {
            return Result.fail("Sin destino para la ruta");
        }
        try {
            byte[] routeBytes = route.toJson().getBytes(StandardCharsets.UTF_8);
            String checksum = sha256(routeBytes);

            JSONObject manifest = new JSONObject();
            manifest.put("format", FORMAT);
            manifest.put("version", VERSION);
            manifest.put("rom_key", route.romKey);
            manifest.put("nodes", route.nodes.size());
            manifest.put("routeSha256", checksum);
            manifest.put("createdAt", System.currentTimeMillis());

            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(output), StandardCharsets.UTF_8)) {
                putEntry(zip, ENTRY_MANIFEST,
                        manifest.toString().getBytes(StandardCharsets.UTF_8));
                putEntry(zip, ENTRY_ROUTE, routeBytes);
                zip.finish();
                zip.flush();
            }
            return Result.ok(route);
        } catch (IOException | org.json.JSONException e) {
            return Result.fail("No se pudo exportar: " + e.getMessage());
        }
    }

    /**
     * Importa una ruta desde un stream SAF.
     *
     * @param input         stream de {@code ACTION_OPEN_DOCUMENT}
     * @param expectedRomKey {@code rom_key} de la partida cargada; la ruta debe coincidir
     */
    public static Result importFrom(InputStream input, String expectedRomKey) {
        if (input == null) {
            return Result.fail("Sin archivo de ruta");
        }
        if (expectedRomKey == null || expectedRomKey.isEmpty()) {
            return Result.fail("Abre la ROM antes de importar la ruta");
        }
        try {
            Map<String, byte[]> entries = readZip(input);

            byte[] manifestBytes = entries.get(ENTRY_MANIFEST);
            if (manifestBytes == null) {
                return Result.fail("Ruta sin manifest.json");
            }
            JSONObject manifest = new JSONObject(
                    new String(manifestBytes, StandardCharsets.UTF_8));
            if (!FORMAT.equals(manifest.optString("format"))) {
                return Result.fail("Formato de ruta desconocido");
            }
            if (manifest.optInt("version", -1) != VERSION) {
                return Result.fail("Versión de ruta no soportada");
            }

            byte[] routeBytes = entries.get(ENTRY_ROUTE);
            if (routeBytes == null) {
                return Result.fail("Ruta sin route.json");
            }

            String expectedChecksum = manifest.optString("routeSha256", "");
            if (!expectedChecksum.isEmpty()
                    && !expectedChecksum.equalsIgnoreCase(sha256(routeBytes))) {
                return Result.fail("Checksum de la ruta no coincide");
            }

            Route route;
            try {
                route = Route.fromJson(new String(routeBytes, StandardCharsets.UTF_8));
            } catch (RouteFormatException e) {
                return Result.fail("Ruta inválida: " + e.getMessage());
            }

            String manifestKey = manifest.optString("rom_key", "");
            if (!manifestKey.isEmpty() && !manifestKey.equals(route.romKey)) {
                return Result.fail("El manifiesto y la ruta no son del mismo juego");
            }
            if (!expectedRomKey.equals(route.romKey)) {
                return Result.fail("La ruta es de " + route.romKey
                        + " y la partida es de " + expectedRomKey);
            }
            return Result.ok(route);
        } catch (IOException | org.json.JSONException e) {
            return Result.fail("No se pudo importar: " + e.getMessage());
        }
    }

    /**
     * Escribe la ruta de forma atómica en {@code <dir>/route_<rom_key>.json}.
     * Mismo patrón que el guardado nativo: {@code .tmp} y rename al final.
     */
    public static File save(Route route, File dir) throws IOException {
        if (route == null) {
            throw new IOException("Ruta vacía");
        }
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            throw new IOException("No se pudo crear el directorio de rutas");
        }
        String safe = route.romKey.replaceAll("[^A-Za-z0-9._-]", "_");
        File dest = new File(dir, "route_" + safe + ".json");
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        File bak = new File(dest.getAbsolutePath() + ".bak");

        byte[] data = route.toJson().getBytes(StandardCharsets.UTF_8);
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
            throw new IOException("No se pudo respaldar la ruta anterior");
        }
        if (!tmp.renameTo(dest)) {
            if (bak.exists()) bak.renameTo(dest);
            tmp.delete();
            throw new IOException("No se pudo promover la ruta nueva");
        }
        return dest;
    }

    /** SHA-256 en hex minúsculas de los bytes de la ruta. */
    static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0x0F, 16));
                hex.append(Character.forDigit(b & 0x0F, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en cualquier JVM/Android soportado.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
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
                if (++count > MAX_ENTRIES) {
                    throw new IOException("Demasiadas entradas en la ruta");
                }
                final String name = entry.getName();
                rejectUnsafeName(name);
                final long max = maxForEntry(name);
                if (max <= 0) {
                    throw new IOException("Entrada no permitida: " + name);
                }
                if (entry.getSize() > max) {
                    throw new IOException("Entrada demasiado grande: " + name);
                }
                entries.put(name, readEntry(zip, max, name));
                zip.closeEntry();
            }
        }
        return entries;
    }

    /** Rutas sin {@code ..}, sin absolutas y sin separadores de Windows. */
    private static void rejectUnsafeName(String name) throws IOException {
        if (name == null || name.isEmpty()
                || name.indexOf("..") >= 0
                || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0) {
            throw new IOException("Nombre de entrada no permitido: " + name);
        }
    }

    private static long maxForEntry(String name) {
        if (ENTRY_MANIFEST.equals(name)) return MAX_MANIFEST;
        if (ENTRY_ROUTE.equals(name)) return MAX_ROUTE;
        return -1;
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
}
