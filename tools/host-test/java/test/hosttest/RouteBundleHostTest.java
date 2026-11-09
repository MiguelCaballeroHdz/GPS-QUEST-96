package hosttest;

import com.gpsquest96.route.Route;
import com.gpsquest96.route.RouteBundle;
import com.gpsquest96.route.RouteNode;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Host test de spec 004 slice 6: paquete portátil de ruta.
 *
 * <p>Comprueba que el ZIP se valida antes de tocar nada: formato, versión,
 * checksum opcional, {@code rom_key} coincidente, nombres sin {@code ..},
 * entradas desconocidas y topes de tamaño. También el guardado atómico.
 */
public class RouteBundleHostTest {

    private static final String KEY = "TEST_ROM_1-v0";
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        roundTrip();
        rejectsForeignRom();
        rejectsBadManifest();
        rejectsBadRoute();
        rejectsUnsafeNames();
        rejectsOversize();
        checksumOptional();
        atomicSave();
        System.out.println("ROUTE BUNDLE HOST TEST OK (" + checks + " comprobaciones)");
    }

    private static void roundTrip() throws Exception {
        Route route = sample(KEY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RouteBundle.Result exported = RouteBundle.export(route, out);
        check("export ok", exported.ok);
        check("ZIP no vacío", out.size() > 0);

        RouteBundle.Result imported = RouteBundle.importFrom(
                new ByteArrayInputStream(out.toByteArray()), KEY);
        check("import ok", imported.ok);
        check("mismo rom_key", KEY.equals(imported.route.romKey));
        check("mismos nodos", route.nodes.size() == imported.route.nodes.size());
        check("JSON idéntico", route.toJson().equals(imported.route.toJson()));
    }

    private static void rejectsForeignRom() throws Exception {
        byte[] zip = bundle(KEY, "", sample(KEY).toJson());
        RouteBundle.Result result = RouteBundle.importFrom(
                new ByteArrayInputStream(zip), "PM_AZUL-v0");
        check("otro rom_key rechazado", !result.ok);
        check("motivo menciona la partida", result.message.contains("partida"));
    }

    private static void rejectsBadManifest() throws Exception {
        String routeJson = sample(KEY).toJson();
        byte[] wrongFormat = zip(new Object[][]{
                {"manifest.json", "{\"format\":\"otro\",\"version\":1}".getBytes(StandardCharsets.UTF_8)},
                {"route.json", routeJson.getBytes(StandardCharsets.UTF_8)}
        });
        check("formato desconocido rechazado", !RouteBundle.importFrom(
                new ByteArrayInputStream(wrongFormat), KEY).ok);

        byte[] wrongVersion = zip(new Object[][]{
                {"manifest.json", "{\"format\":\"gpsquest96.route\",\"version\":9}".getBytes(StandardCharsets.UTF_8)},
                {"route.json", routeJson.getBytes(StandardCharsets.UTF_8)}
        });
        check("versión no soportada rechazada", !RouteBundle.importFrom(
                new ByteArrayInputStream(wrongVersion), KEY).ok);

        byte[] manifestOnly = zip(new Object[][]{
                {"manifest.json", manifest(KEY, "").getBytes(StandardCharsets.UTF_8)}
        });
        check("sin route.json rechazado", !RouteBundle.importFrom(
                new ByteArrayInputStream(manifestOnly), KEY).ok);

        byte[] keyMismatch = bundle("PM_AZUL-v0", "", routeJson);
        check("manifiesto de otro juego rechazado", !RouteBundle.importFrom(
                new ByteArrayInputStream(keyMismatch), KEY).ok);
    }

    private static void rejectsBadRoute() throws Exception {
        String broken = sample(KEY).toJson().replace("\"schema_version\":1",
                "\"schema_version\":2");
        byte[] zip = bundle(KEY, "", broken);
        RouteBundle.Result result = RouteBundle.importFrom(
                new ByteArrayInputStream(zip), KEY);
        check("ruta con esquema inválido rechazada", !result.ok);
        check("motivo conserva el detalle", result.message.contains("Ruta inválida"));

        byte[] garbage = "esto no es un zip".getBytes(StandardCharsets.UTF_8);
        check("ZIP corrupto rechazado", !RouteBundle.importFrom(
                new ByteArrayInputStream(garbage), KEY).ok);

        byte[] wrongChecksum = bundle(KEY, "deadbeef", sample(KEY).toJson());
        check("checksum incorrecto rechazado", !RouteBundle.importFrom(
                new ByteArrayInputStream(wrongChecksum), KEY).ok);
    }

    private static void rejectsUnsafeNames() throws Exception {
        String routeJson = sample(KEY).toJson();
        byte[] traversal = zip(new Object[][]{
                {"manifest.json", manifest(KEY, "").getBytes(StandardCharsets.UTF_8)},
                {"../route.json", routeJson.getBytes(StandardCharsets.UTF_8)}
        });
        check("entrada con .. rechazada", !RouteBundle.importFrom(
                new ByteArrayInputStream(traversal), KEY).ok);

        byte[] absolute = zip(new Object[][]{
                {"manifest.json", manifest(KEY, "").getBytes(StandardCharsets.UTF_8)},
                {"/route.json", routeJson.getBytes(StandardCharsets.UTF_8)}
        });
        check("entrada absoluta rechazada", !RouteBundle.importFrom(
                new ByteArrayInputStream(absolute), KEY).ok);

        byte[] unknown = zip(new Object[][]{
                {"manifest.json", manifest(KEY, "").getBytes(StandardCharsets.UTF_8)},
                {"route.json", routeJson.getBytes(StandardCharsets.UTF_8)},
                {"extra.bin", new byte[]{1, 2, 3}}
        });
        check("entrada desconocida rechazada", !RouteBundle.importFrom(
                new ByteArrayInputStream(unknown), KEY).ok);
    }

    private static void rejectsOversize() throws Exception {
        byte[] huge = new byte[2 * 1024 * 1024 + 1];
        byte[] zip = zip(new Object[][]{
                {"manifest.json", manifest(KEY, "").getBytes(StandardCharsets.UTF_8)},
                {"route.json", huge}
        });
        RouteBundle.Result result = RouteBundle.importFrom(
                new ByteArrayInputStream(zip), KEY);
        check("ruta por encima del tope rechazada", !result.ok);
        check("motivo de tamaño", result.message.contains("grande")
                || result.message.contains("tamaño"));
    }

    private static void checksumOptional() throws Exception {
        byte[] zip = bundle(KEY, "", sample(KEY).toJson());
        check("sin checksum se acepta", RouteBundle.importFrom(
                new ByteArrayInputStream(zip), KEY).ok);
    }

    private static void atomicSave() throws Exception {
        File dir = Files.createTempDirectory("route-bundle").toFile();
        Route route = sample(KEY);
        File saved = RouteBundle.save(route, dir);
        check("archivo guardado con nombre esperado",
                ("route_" + KEY + ".json").equals(saved.getName()));
        check("sin .tmp residual",
                !new File(saved.getAbsolutePath() + ".tmp").exists());

        String json = new String(Files.readAllBytes(saved.toPath()),
                StandardCharsets.UTF_8);
        Route back = Route.fromJson(json);
        check("ruta guardada reparseable", back.toJson().equals(route.toJson()));

        File[] leftovers = dir.listFiles();
        check("solo queda un archivo (y .bak/tmp permitidos)",
                leftovers != null && leftovers.length <= 2);
        if (leftovers != null) {
            for (File f : leftovers) f.delete();
        }
        dir.delete();
    }

    private static Route sample(String key) {
        List<RouteNode> nodes = new ArrayList<>();
        nodes.add(RouteNode.at(0, RouteNode.Action.WALK, "synthetic",
                19.5627876, -96.9180020, 15.0));
        nodes.add(RouteNode.at(1, RouteNode.Action.USE, "SHOP",
                19.5622890, -96.9199260, 15.0));
        nodes.add(RouteNode.at(2, RouteNode.Action.ENTER, "AMENITY",
                19.5608919, -96.9189668, 15.0));
        return new Route(key, 19.5628067, -96.9179283, 3.3, 1794089059697L, nodes);
    }

    private static byte[] bundle(String manifestKey, String checksum, String routeJson)
            throws Exception {
        return zip(new Object[][]{
                {"manifest.json", manifest(manifestKey, checksum)
                        .getBytes(StandardCharsets.UTF_8)},
                {"route.json", routeJson.getBytes(StandardCharsets.UTF_8)}
        });
    }

    private static String manifest(String key, String checksum) throws Exception {
        JSONObject m = new JSONObject();
        m.put("format", "gpsquest96.route");
        m.put("version", 1);
        m.put("rom_key", key);
        m.put("routeSha256", checksum);
        return m.toString();
    }

    private static byte[] zip(Object[][] entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Object[] entry : entries) {
                zip.putNextEntry(new ZipEntry((String) entry[0]));
                zip.write((byte[]) entry[1]);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            throw new AssertionError("FALLO: " + name);
        }
        System.out.println("  OK     " + name);
    }
}
