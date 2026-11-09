package hosttest;

import android.content.Context;

import com.gpsquest96.emulator.PuebloProfile;
import com.gpsquest96.emulator.SaveManager;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Host test de `.pueblo` (spec 008 slice 2). Compila las clases reales
 * `PuebloProfile` y `SaveManager` contra stubs de Android y ejecuta una ida y
 * vuelta completa: exportar, importar en "otro equipo", comprobar `.sav`, geo,
 * prefs, respaldo `.bak` y rechazo por SHA-256.
 */
public final class PuebloHostTest {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("pueblo-host").toFile();
        File deviceA = new File(root, "deviceA");
        File deviceB = new File(root, "deviceB");
        try {
            run(deviceA, deviceB);
        } finally {
            deleteTree(root);
        }

        if (failures == 0) {
            System.out.println("OK: " + checks + " checks");
        } else {
            System.out.println("FAILED: " + failures + " de " + checks + " checks");
            System.exit(1);
        }
    }

    private static void run(File deviceA, File deviceB) throws Exception {
        // ── Equipo A: ROM + .sav + geo + prefs ─────────────────────────────
        File romsA = new File(deviceA, "roms");
        File romA = new File(romsA, "crystal.gbc");
        File savA = new File(romsA, "crystal.sav");
        File geoA = new File(new File(deviceA, "saves"), "gpsquest96.sav");
        byte[] romBytes = bytes(4096, 31, 7);
        byte[] savBytes = bytes(32768, 13, 5);
        byte[] geoBytes = geo(42, 123456789L);
        write(romA, romBytes);
        write(savA, savBytes);
        write(geoA, geoBytes);

        Context ctxA = new Context(deviceA);
        SaveManager prefsA = new SaveManager(ctxA);
        prefsA.savePlayer("Ash", 12, "BOY");
        int[] flagsA = new int[256];
        flagsA[3] = 1;
        flagsA[7] = 1;
        prefsA.saveState(flagsA);
        prefsA.saveLastLocation(19.4326, -99.1332);
        prefsA.startTruce();

        PuebloProfile profileA = new PuebloProfile(ctxA);
        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        PuebloProfile.Result export = profileA.export(
                zipOut, romA.getAbsolutePath(), "TEST_ROM_1", "BYTE");
        check(export.ok, "export ok");
        check(export.battery, "export incluye battery");
        check(export.geo, "export incluye geo");
        check(export.prefs, "export incluye prefs");

        byte[] zip = zipOut.toByteArray();
        Map<String, byte[]> entries = unzip(zip);
        check(entries.containsKey("manifest.json"), "zip tiene manifest");
        check(entries.containsKey("battery.sav"), "zip tiene battery.sav");
        check(entries.containsKey("geo.sav"), "zip tiene geo.sav");
        check(entries.containsKey("prefs.json"), "zip tiene prefs.json");

        JSONObject manifest = new JSONObject(
                new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        check("gpsquest96.pueblo".equals(manifest.getString("format")), "formato manifest");
        check(manifest.getInt("version") == 1, "versión manifest");
        check(manifest.getString("sha256").equals(PuebloProfile.sha256(romA)),
                "sha256 manifest = ROM");
        check(manifest.getLong("batterySize") == savBytes.length, "batterySize manifest");
        check(Arrays.equals(entries.get("battery.sav"), savBytes), "battery.sav intacto");
        check(Arrays.equals(entries.get("geo.sav"), geoBytes), "geo.sav intacto");

        JSONObject prefsJson = new JSONObject(
                new String(entries.get("prefs.json"), StandardCharsets.UTF_8));
        check("Ash".equals(prefsJson.getString("player_name")), "prefs nombre");
        check("3,7".equals(prefsJson.getString("event_flags")), "prefs flags");

        // ── Equipo B: misma ROM, sin partida; importar el perfil ───────────
        File romsB = new File(deviceB, "roms");
        File romB = new File(romsB, "crystal.gbc");
        write(romB, romBytes);
        Context ctxB = new Context(deviceB);
        PuebloProfile profileB = new PuebloProfile(ctxB);

        PuebloProfile.Result importB = profileB.importFrom(
                new ByteArrayInputStream(zip), romB.getAbsolutePath());
        check(importB.ok, "import ok");
        check(importB.battery, "import aplicó battery");
        check(importB.geo, "import aplicó geo");
        check(importB.prefs, "import aplicó prefs");

        File savB = new File(romsB, "crystal.sav");
        File geoB = new File(new File(deviceB, "saves"), "gpsquest96.sav");
        check(Arrays.equals(read(savB), savBytes), "battery importada");
        check(Arrays.equals(read(geoB), geoBytes), "geo importado");

        SaveManager prefsB = new SaveManager(ctxB);
        check("Ash".equals(prefsB.getPlayerName()), "prefs nombre importado");
        check(prefsB.getPlayerAge() == 12, "prefs edad importada");
        check("BOY".equals(prefsB.getPlayerGender()), "prefs género importado");
        int[] flagsB = new int[256];
        prefsB.loadState(flagsB);
        check(flagsB[3] == 1 && flagsB[7] == 1 && flagsB[0] == 0, "flags importados");
        check(Double.compare(prefsB.getLastLocation()[0], 19.4326) == 0,
                "latitud importada");
        check(Double.compare(prefsB.getLastLocation()[1], -99.1332) == 0,
                "longitud importada");
        check(prefsB.isInTruce(), "tregua importada");

        // ── Respaldo: una segunda importación deja el .sav anterior en .bak ─
        byte[] oldSav = bytes(32768, 99, 17);
        write(savB, oldSav);
        PuebloProfile.Result importAgain = profileB.importFrom(
                new ByteArrayInputStream(zip), romB.getAbsolutePath());
        check(importAgain.ok, "segunda importación ok");
        File bakB = new File(romsB, "crystal.sav.bak");
        check(bakB.isFile(), "se creó .bak");
        check(Arrays.equals(read(bakB), oldSav), ".bak conserva la partida anterior");
        check(Arrays.equals(read(savB), savBytes), ".sav quedó con la importada");

        // ── Rechazo por ROM distinta: no debe tocar la partida actual ──────
        byte[] romOtherBytes = romBytes.clone();
        romOtherBytes[100] ^= 0x01;
        File romOther = new File(romsB, "crystal.gbc");
        write(romOther, romOtherBytes);
        PuebloProfile.Result wrongRom = profileB.importFrom(
                new ByteArrayInputStream(zip), romOther.getAbsolutePath());
        check(!wrongRom.ok, "ROM distinta se rechaza");
        check(wrongRom.message.contains("no corresponde"), "mensaje de hash");
        check(Arrays.equals(read(savB), savBytes), "rechazo no toca el .sav");

        // ── geo.sav inválido: se rechaza antes de escribir ─────────────────
        Map<String, byte[]> badGeoEntries = new LinkedHashMap<>(entries);
        badGeoEntries.put("geo.sav", new byte[]{0x00, 0x01, 0x02, 0x03});
        byte[] badGeoZip = zip(badGeoEntries);
        File romGood = new File(romsB, "crystal.gbc");
        write(romGood, romBytes);
        PuebloProfile.Result badGeo = profileB.importFrom(
                new ByteArrayInputStream(badGeoZip), romGood.getAbsolutePath());
        check(!badGeo.ok, "geo inválido se rechaza");
        check(badGeo.message.contains("geo.sav inválido"), "mensaje de geo");
        check(Arrays.equals(read(savB), savBytes), "geo inválido no toca el .sav");
    }

    private static byte[] geo(int storyFlag, long timestamp) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(0x47513936);
        data.writeInt(1);
        data.writeInt(storyFlag);
        data.writeLong(timestamp);
        data.flush();
        return out.toByteArray();
    }

    private static byte[] bytes(int size, int mul, int add) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * mul + add) & 0xFF);
        }
        return data;
    }

    private static void write(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        Files.write(file.toPath(), data);
    }

    private static byte[] read(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                entries.put(entry.getName(), out.toByteArray());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
