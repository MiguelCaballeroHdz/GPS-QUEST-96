package hosttest;

import com.gpsquest96.avatar.AvatarData;
import com.gpsquest96.avatar.AvatarSprites;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Host test del avatar propio de Miguel (spec 017 Slice 1).
 * <p>
 * Sin Android, sin red y sin POCO: compila la clase real
 * {@code com.gpsquest96.avatar.AvatarSprites} (arte de la hoja enviada por
 * Miguel el 2026-11-10, retocado: paleta cuantizada, fondo transparente,
 * cuadros 32x56) contra stubs de Android.
 * <p>
 * Cubre:
 *  1. Decodificacion: los 12 cuadros miden FRAME_W x FRAME_H y solo usan
 *     caracteres del alfabeto con indice dentro de la paleta.
 *  2. Paleta: indice 0 transparente, resto opacos.
 *  3. Contenido: cada cuadro tiene pixeles opacos y transparentes.
 *  4. Espejo: mirror(mirror(x)) == x; el lado espejado difiere del original.
 *  5. Direccion por rumbo: sectores de 90 grados centrados en cardinales,
 *     con normalizacion (negativos, 360+).
 *  6. Espejo por rumbo: solo al oeste (225-315).
 *  7. Fase: parado tras IDLE_MS sin movimiento; alterna 0/1 cada STEP_MS.
 *  8. Caminata: los cuadros A y B difieren en las 3 direcciones y generos.
 *  9. Pelo: el chico no tiene rosa vivo (16/18/20) en ningun cuadro; la
 *     chica si, y en el lado (recolor) el rosa domina al castano.
 * 10. Decodificacion rechaza datos corruptos (null, longitud, caracter).
 */
public class AvatarSpritesHostTest {

    private static int checks = 0;

    private static void check(boolean cond, String name) {
        checks++;
        if (!cond) {
            throw new AssertionError("FALLO: " + name);
        }
        System.out.println("  OK     " + name);
    }

    private static void checkEq(int expected, int actual, String name) {
        check(expected == actual, name + " (fue " + actual + ", esperado " + expected + ")");
    }

    private static void checkThrows(Runnable r, String name) {
        checks++;
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            System.out.println("  OK     " + name);
            return;
        }
        throw new AssertionError("FALLO (no lanzo): " + name);
    }

    private static Set<Integer> paletteValues(int[] px) {
        Set<Integer> s = new HashSet<>();
        for (int v : px) s.add(v);
        return s;
    }

    private static int countPalette(int[] px, int[] indices) {
        Set<Integer> want = new HashSet<>();
        for (int i : indices) want.add(AvatarSprites.PALETTE[i]);
        int n = 0;
        for (int v : px) if (want.contains(v)) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        decodeAll();
        palette();
        content();
        mirror();
        direction();
        mirrorFor();
        phase();
        walkDiffers();
        hair();
        decodeRejects();
        System.out.println("AVATAR SPRITES HOST TEST OK (" + checks + " comprobaciones)");
    }

    private static AvatarData.Gender[] genders() {
        return new AvatarData.Gender[]{AvatarData.Gender.BOY, AvatarData.Gender.GIRL};
    }

    private static AvatarSprites.Direction[] dirs() {
        return new AvatarSprites.Direction[]{
            AvatarSprites.Direction.FRONT,
            AvatarSprites.Direction.BACK,
            AvatarSprites.Direction.SIDE};
    }

    private static void decodeAll() {
        for (AvatarData.Gender g : genders()) {
            for (AvatarSprites.Direction d : dirs()) {
                for (int p = 0; p < 2; p++) {
                    String data = AvatarSprites.frameData(g, d, p);
                    check(data.length() == AvatarSprites.FRAME_W * AvatarSprites.FRAME_H,
                            "decodifica " + g + " " + d + " fase " + p + " (1792 chars)");
                    int[] px = AvatarSprites.decode(data);
                    checkEq(AvatarSprites.FRAME_W * AvatarSprites.FRAME_H, px.length,
                            "pixeles " + g + " " + d + " fase " + p);
                    int[] viaPixels = AvatarSprites.pixels(g, d, p);
                    check(Arrays.equals(px, viaPixels),
                            "pixels() == decode() en " + g + " " + d + " fase " + p);
                }
            }
        }
    }

    private static void palette() {
        checkEq(0x00000000, AvatarSprites.PALETTE[0], "paleta[0] transparente");
        for (int i = 1; i < AvatarSprites.PALETTE.length; i++) {
            check((AvatarSprites.PALETTE[i] & 0xFF000000) == 0xFF000000,
                    "paleta[" + i + "] opaca");
        }
        check(AvatarSprites.PALETTE.length <= AvatarSprites.ALPHABET.length(),
                "paleta cabe en el alfabeto (21 <= 64)");
    }

    private static void content() {
        for (AvatarData.Gender g : genders()) {
            for (AvatarSprites.Direction d : dirs()) {
                for (int p = 0; p < 2; p++) {
                    int[] px = AvatarSprites.pixels(g, d, p);
                    int opaque = 0;
                    for (int v : px) if ((v & 0xFF000000) != 0) opaque++;
                    check(opaque > 500, "cuadro " + g + " " + d + " fase " + p
                            + " con contenido (opacos=" + opaque + ")");
                    check(opaque < px.length, "cuadro " + g + " " + d + " fase " + p
                            + " con transparencia");
                }
            }
        }
    }

    private static void mirror() {
        for (AvatarData.Gender g : genders()) {
            for (AvatarSprites.Direction d : dirs()) {
                int[] px = AvatarSprites.pixels(g, d, 0);
                int[] m = AvatarSprites.mirror(px);
                checkEq(px.length, m.length, "espejo conserva longitud " + g + " " + d);
                check(Arrays.equals(px, AvatarSprites.mirror(m)),
                        "espejo del espejo == original " + g + " " + d);
                check(!Arrays.equals(px, m),
                        "espejo difiere del original " + g + " " + d);
                check(Arrays.equals(m, AvatarSprites.pixelsMirrored(g, d, 0)),
                        "pixelsMirrored == mirror(pixels) " + g + " " + d);
            }
        }
    }

    private static void direction() {
        check(AvatarSprites.directionFor(0f) == AvatarSprites.Direction.BACK, "rumbo 0 -> BACK");
        check(AvatarSprites.directionFor(44f) == AvatarSprites.Direction.BACK, "rumbo 44 -> BACK");
        check(AvatarSprites.directionFor(45f) == AvatarSprites.Direction.SIDE, "rumbo 45 -> SIDE");
        check(AvatarSprites.directionFor(90f) == AvatarSprites.Direction.SIDE, "rumbo 90 -> SIDE");
        check(AvatarSprites.directionFor(134f) == AvatarSprites.Direction.SIDE, "rumbo 134 -> SIDE");
        check(AvatarSprites.directionFor(135f) == AvatarSprites.Direction.FRONT, "rumbo 135 -> FRONT");
        check(AvatarSprites.directionFor(180f) == AvatarSprites.Direction.FRONT, "rumbo 180 -> FRONT");
        check(AvatarSprites.directionFor(224f) == AvatarSprites.Direction.FRONT, "rumbo 224 -> FRONT");
        check(AvatarSprites.directionFor(225f) == AvatarSprites.Direction.SIDE, "rumbo 225 -> SIDE");
        check(AvatarSprites.directionFor(270f) == AvatarSprites.Direction.SIDE, "rumbo 270 -> SIDE");
        check(AvatarSprites.directionFor(314f) == AvatarSprites.Direction.SIDE, "rumbo 314 -> SIDE");
        check(AvatarSprites.directionFor(315f) == AvatarSprites.Direction.BACK, "rumbo 315 -> BACK");
        check(AvatarSprites.directionFor(359f) == AvatarSprites.Direction.BACK, "rumbo 359 -> BACK");
        check(AvatarSprites.directionFor(-90f) == AvatarSprites.Direction.SIDE, "rumbo -90 -> SIDE");
        check(AvatarSprites.directionFor(360f) == AvatarSprites.Direction.BACK, "rumbo 360 -> BACK");
        check(AvatarSprites.directionFor(405f) == AvatarSprites.Direction.SIDE, "rumbo 405 -> SIDE");
    }

    private static void mirrorFor() {
        check(!AvatarSprites.mirrorFor(0f), "rumbo 0 no espeja");
        check(!AvatarSprites.mirrorFor(90f), "rumbo 90 no espeja");
        check(!AvatarSprites.mirrorFor(180f), "rumbo 180 no espeja");
        check(AvatarSprites.mirrorFor(225f), "rumbo 225 espeja");
        check(AvatarSprites.mirrorFor(270f), "rumbo 270 espeja");
        check(!AvatarSprites.mirrorFor(315f), "rumbo 315 no espeja");
    }

    private static void phase() {
        checkEq(0, AvatarSprites.phaseFor(1000L, 100L), "parado tras 900 ms sin moverse");
        checkEq(0, AvatarSprites.phaseFor(1000L, 499L), "parado tras 501 ms sin moverse");
        checkEq(1, AvatarSprites.phaseFor(1000L, 900L), "moviendose en t=1000 -> fase 1");
        checkEq(0, AvatarSprites.phaseFor(1080L, 900L), "moviendose en t=1080 -> fase 0");
        checkEq(1, AvatarSprites.phaseFor(900L, 900L), "moviendose en t=900 -> fase 1");
        checkEq(0, AvatarSprites.phaseFor(1440L, 1200L), "moviendose en t=1440 -> fase 0");
    }

    private static void walkDiffers() {
        for (AvatarData.Gender g : genders()) {
            for (AvatarSprites.Direction d : dirs()) {
                int[] a = AvatarSprites.pixels(g, d, 0);
                int[] b = AvatarSprites.pixels(g, d, 1);
                check(!Arrays.equals(a, b), "paso A != paso B en " + g + " " + d);
            }
        }
    }

    private static void hair() {
        // Chico: cero rosa vivo (16/18/20) en ningun cuadro; chica: rosa en todos.
        for (AvatarSprites.Direction d : dirs()) {
            for (int p = 0; p < 2; p++) {
                int[] boy = AvatarSprites.pixels(AvatarData.Gender.BOY, d, p);
                checkEq(0, countPalette(boy, AvatarSprites.HAIR_PINK),
                        "chico sin rosa vivo " + d + " fase " + p);
                check(countPalette(boy, AvatarSprites.HAIR_BROWN) > 0,
                        "chico con pelo castano " + d + " fase " + p);
                int[] girl = AvatarSprites.pixels(AvatarData.Gender.GIRL, d, p);
                check(countPalette(girl, AvatarSprites.HAIR_PINK) > 0,
                        "chica con rosa vivo " + d + " fase " + p);
            }
        }
        // Lado chica (recolor): en el tercio superior el rosa domina al castano.
        for (int p = 0; p < 2; p++) {
            int[] px = AvatarSprites.pixels(AvatarData.Gender.GIRL,
                    AvatarSprites.Direction.SIDE, p);
            int topRows = (int) (AvatarSprites.FRAME_H * 0.40);
            int brown = 0;
            int pink = 0;
            for (int y = 0; y < topRows; y++) {
                for (int x = 0; x < AvatarSprites.FRAME_W; x++) {
                    int v = px[y * AvatarSprites.FRAME_W + x];
                    for (int i : AvatarSprites.HAIR_BROWN) {
                        if (v == AvatarSprites.PALETTE[i]) brown++;
                    }
                    for (int i : AvatarSprites.HAIR_PINK) {
                        if (v == AvatarSprites.PALETTE[i]) pink++;
                    }
                }
            }
            check(pink > 50, "lado chica con pelo rosa arriba (fase " + p + ", rosa=" + pink + ")");
            check(pink > 10 * brown, "lado chica rosa domina castano arriba (fase " + p
                    + ", rosa=" + pink + " castano=" + brown + ")");
        }
    }

    private static void decodeRejects() {
        checkThrows(new Runnable() {
            @Override
            public void run() {
                AvatarSprites.decode(null);
            }
        }, "decode(null) rechaza");
        checkThrows(new Runnable() {
            @Override
            public void run() {
                AvatarSprites.decode("AAA");
            }
        }, "decode corto rechaza");
        checkThrows(new Runnable() {
            @Override
            public void run() {
                char[] bad = AvatarSprites.frameData(
                        AvatarData.Gender.BOY, AvatarSprites.Direction.FRONT, 0).toCharArray();
                bad[0] = '!';
                AvatarSprites.decode(new String(bad));
            }
        }, "decode con caracter fuera de paleta rechaza");
        checkThrows(new Runnable() {
            @Override
            public void run() {
                AvatarSprites.mirror(null);
            }
        }, "mirror(null) rechaza");
        checkThrows(new Runnable() {
            @Override
            public void run() {
                AvatarSprites.mirror(new int[10]);
            }
        }, "mirror corto rechaza");
    }
}
