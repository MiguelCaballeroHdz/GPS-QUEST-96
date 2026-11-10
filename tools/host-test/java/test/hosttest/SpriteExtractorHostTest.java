package hosttest;

import com.gpsquest96.render.SpriteExtractor;
import com.gpsquest96.render.TileAtlas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host test del fix de flips del sprite OW (spec 015, fila SpriteExtractor).
 * <p>
 * Sin Android, sin red y sin POCO: compila la clase real
 * {@code com.gpsquest96.render.SpriteExtractor} (con {@code TileAtlas} y
 * {@code EmulationPipeline} por la referencia a {@code SHADES}) contra
 * stubs de Android.
 * <p>
 * Cubre:
 *  1. Composición 2x2 sin flips: layout absoluto de los 4 tiles.
 *  2. Composición con FlipX / FlipY / ambos: espejo exacto del sprite.
 *  3. Camino DMG (sin paleta CGB): tonos GB vía OBP0.
 *  4. identifySprite publica los flips de la entrada TL del bloque OAM.
 *  5. Flips mezclados: manda la entrada TL.
 *  6. Giro inmediato: mismo base, flip distinto, se aplica sin esperar
 *     otra confirmación de 6 ticks (bases 133/137 alternando al caminar).
 *  7. Camino diff de VRAM (sin OAM): se publica sin voltear.
 */
public class SpriteExtractorHostTest {

    private static int checks = 0;

    private static final int BASE = 133;

    public static void main(String[] args) throws Exception {
        composeNoFlip();
        composeFlips();
        composeDmg();
        identifyPublishesFlips();
        identifyTlWinsOnMixedFlips();
        identifyImmediateFlipOnTurn();
        identifyVramDiffHasNoFlips();
        System.out.println("SPRITE EXTRACTOR HOST TEST OK (" + checks + " comprobaciones)");
    }

    private static void check(String name, boolean ok) {
        checks++;
        System.out.println((ok ? "  OK   " : "  FALLO") + "  " + name);
        if (!ok) throw new AssertionError("Fallo: " + name);
    }

    /** Pone un píxel (row, col) de un tile 8x8 con valor 2bpp v (0-3). */
    private static void setTilePixel(byte[] vram, int tile, int row, int col, int v) {
        int off = tile * 16 + row * 2;
        int bit = 0x80 >> col;
        if ((v & 1) != 0) vram[off] = (byte) (vram[off] | bit);
        if ((v & 2) != 0) vram[off + 1] = (byte) (vram[off + 1] | bit);
    }

    /** VRAM banco 1 con 4 tiles (BASE..BASE+3), un píxel v=1 cada uno.
     * Patrón ASIMÉTRICO a propósito: con píxeles en las 4 esquinas el
     * fixture sería simétrico y un flip no se distinguiría del original. */
    private static byte[] cornerVram1() {
        byte[] vram1 = new byte[8192];
        setTilePixel(vram1, BASE, 0, 0, 1);     // TL: (0,0)   -> salida (0,0)
        setTilePixel(vram1, BASE + 1, 1, 7, 1); // TR: (1,7)   -> salida (1,15)
        setTilePixel(vram1, BASE + 2, 2, 0, 1); // BL: (2,0)   -> salida (10,0)
        setTilePixel(vram1, BASE + 3, 3, 7, 1); // BR: (3,7)   -> salida (11,15)
        return vram1;
    }

    /** Paleta OBJ CGB de 64 B: paleta 0 con color1=blanco, color2=rojo, color3=verde. */
    private static byte[] objPal() {
        byte[] pal = new byte[64];
        pal[2] = (byte) 0xFF; pal[3] = (byte) 0x7F; // color1 = 0x7FFF blanco
        pal[4] = (byte) 0x1F; pal[5] = (byte) 0x00; // color2 = 0x001F rojo
        pal[6] = (byte) 0xE0; pal[7] = (byte) 0x03; // color3 = 0x03E0 verde
        return pal;
    }

    private static void composeNoFlip() {
        SpriteExtractor ex = new SpriteExtractor();
        byte[] vram1 = cornerVram1();
        int[] out = ex.composeSprite(new byte[8192], vram1, BASE, 1,
                objPal(), 0, 0, false, false);
        check("sin flip: compone 16x16", out != null && out.length == 256);
        check("sin flip: TL en (0,0)", out[0] == 0xFFFFFFFF);
        check("sin flip: TR en (1,15)", out[1 * 16 + 15] == 0xFFFFFFFF);
        check("sin flip: BL en (10,0)", out[10 * 16] == 0xFFFFFFFF);
        check("sin flip: BR en (11,15)", out[11 * 16 + 15] == 0xFFFFFFFF);
        int lit = 0;
        for (int px : out) if (px != 0) lit++;
        check("sin flip: solo 4 píxeles opacos", lit == 4);
    }

    private static int[] mirrorH(int[] px) {
        int[] out = new int[256];
        for (int r = 0; r < 16; r++)
            for (int c = 0; c < 16; c++)
                out[r * 16 + c] = px[r * 16 + (15 - c)];
        return out;
    }

    private static int[] mirrorV(int[] px) {
        int[] out = new int[256];
        for (int r = 0; r < 16; r++)
            for (int c = 0; c < 16; c++)
                out[r * 16 + c] = px[(15 - r) * 16 + c];
        return out;
    }

    private static void composeFlips() {
        SpriteExtractor ex = new SpriteExtractor();
        byte[] vram1 = cornerVram1();
        byte[] pal = objPal();
        int[] plain = ex.composeSprite(new byte[8192], vram1, BASE, 1, pal, 0, 0, false, false);
        int[] flipX = ex.composeSprite(new byte[8192], vram1, BASE, 1, pal, 0, 0, true, false);
        int[] flipY = ex.composeSprite(new byte[8192], vram1, BASE, 1, pal, 0, 0, false, true);
        int[] flipXY = ex.composeSprite(new byte[8192], vram1, BASE, 1, pal, 0, 0, true, true);
        check("flipX: espejo horizontal exacto", Arrays.equals(flipX, mirrorH(plain)));
        check("flipY: espejo vertical exacto", Arrays.equals(flipY, mirrorV(plain)));
        check("flipX+flipY: rotación 180 exacta",
                Arrays.equals(flipXY, mirrorV(mirrorH(plain))));
        check("flipX: distinto del sprite sin voltear", !Arrays.equals(flipX, plain));
    }

    private static void composeDmg() {
        SpriteExtractor ex = new SpriteExtractor();
        byte[] vram0 = new byte[8192];
        setTilePixel(vram0, BASE, 0, 0, 1);
        setTilePixel(vram0, BASE + 1, 0, 0, 2);
        setTilePixel(vram0, BASE + 2, 0, 0, 3);
        // OBP0 0xE4 = identidad (v -> v); sin paleta CGB.
        int[] out = ex.composeSprite(vram0, null, BASE, 0, null, 0, 0xE4, false, false);
        check("DMG: v=1 -> SHADES[1]", out[0] == TileAtlas.SHADES[1]);
        check("DMG: v=2 -> SHADES[2]", out[8] == TileAtlas.SHADES[2]);
        check("DMG: v=3 -> SHADES[3]", out[8 * 16] == TileAtlas.SHADES[3]);
        check("DMG: v=0 transparente", out[1] == 0);
    }

    /** OAM con el bloque 2x2 centrado: (64,60)=TL (72,60)=TR (64,68)=BL (72,68)=BR. */
    private static byte[] oamBlock(int base, int tlAttr, int otherAttr) {
        byte[] oam = new byte[160];
        putOam(oam, 0, 76, 72, base, tlAttr);
        putOam(oam, 1, 76, 80, base + 1, otherAttr);
        putOam(oam, 2, 84, 72, base + 2, otherAttr);
        putOam(oam, 3, 84, 80, base + 3, otherAttr);
        return oam;
    }

    private static void putOam(byte[] oam, int i, int y, int x, int tile, int attr) {
        oam[i * 4] = (byte) y;
        oam[i * 4 + 1] = (byte) x;
        oam[i * 4 + 2] = (byte) tile;
        oam[i * 4 + 3] = (byte) attr;
    }

    /** VRAM banco 1 con los 4 tiles del base presentes (no en blanco). */
    private static byte[] presentVram1(int base) {
        byte[] vram1 = new byte[8192];
        for (int t = base; t < base + 4; t++) vram1[t * 16] = 1;
        return vram1;
    }

    private static void identifyPublishesFlips() {
        // FlipX (0x20) en las 4 entradas, banco 1 (0x08).
        SpriteExtractor ex = new SpriteExtractor();
        ex.notePlayerMove();
        byte[] vram1 = presentVram1(BASE);
        byte[] oam = oamBlock(BASE, 0x28, 0x28);
        int[] tiles = null;
        for (int i = 0; i < 6; i++) {
            tiles = ex.identifySprite(new byte[8192], vram1, oam, new ArrayList<Integer>());
        }
        check("OAM flipX: publica base", tiles != null && tiles[0] == BASE);
        check("OAM flipX: getSpriteFlipX true", ex.getSpriteFlipX());
        check("OAM flipX: getSpriteFlipY false", !ex.getSpriteFlipY());

        // FlipY (0x40) en las 4 entradas.
        SpriteExtractor ex2 = new SpriteExtractor();
        ex2.notePlayerMove();
        byte[] oamY = oamBlock(BASE, 0x48, 0x48);
        int[] tiles2 = null;
        for (int i = 0; i < 6; i++) {
            tiles2 = ex2.identifySprite(new byte[8192], vram1, oamY, new ArrayList<Integer>());
        }
        check("OAM flipY: publica base", tiles2 != null && tiles2[0] == BASE);
        check("OAM flipY: getSpriteFlipX false", !ex2.getSpriteFlipX());
        check("OAM flipY: getSpriteFlipY true", ex2.getSpriteFlipY());
    }

    private static void identifyTlWinsOnMixedFlips() {
        // Solo la entrada TL lleva flipX; las otras 3 no.
        SpriteExtractor ex = new SpriteExtractor();
        ex.notePlayerMove();
        byte[] vram1 = presentVram1(BASE);
        byte[] oam = oamBlock(BASE, 0x28, 0x08);
        int[] tiles = null;
        for (int i = 0; i < 6; i++) {
            tiles = ex.identifySprite(new byte[8192], vram1, oam, new ArrayList<Integer>());
        }
        check("flips mezclados: publica base", tiles != null && tiles[0] == BASE);
        check("flips mezclados: manda la TL (flipX true)", ex.getSpriteFlipX());
    }

    private static void identifyImmediateFlipOnTurn() {
        SpriteExtractor ex = new SpriteExtractor();
        ex.notePlayerMove();
        byte[] vram1 = presentVram1(BASE);
        List<Integer> none = new ArrayList<Integer>();
        // Publica base 133 sin flips (6 ticks).
        for (int i = 0; i < 6; i++) {
            ex.identifySprite(new byte[8192], vram1, oamBlock(BASE, 0x08, 0x08), none);
        }
        check("giro: publica 133 sin flips", ex.getSpriteTiles()[0] == BASE);
        check("giro: flipX false al publicar", !ex.getSpriteFlipX());
        // Un tick con el otro cuadro de caminata (137): la racha se reinicia.
        ex.identifySprite(new byte[8192], presentVram1(137),
                oamBlock(137, 0x08, 0x08), none);
        check("giro: tras 137 sin flip sigue false", !ex.getSpriteFlipX());
        // Vuelve 133 CON flipX: sin racha de 6 (racha=1), el giro se aplica ya.
        ex.identifySprite(new byte[8192], vram1, oamBlock(BASE, 0x28, 0x28), none);
        check("giro: flipX true sin esperar confirmación", ex.getSpriteFlipX());
        check("giro: los tiles siguen siendo 133", ex.getSpriteTiles()[0] == BASE);
        // Un tick de 137 SIN flip no pisa el flip del sprite publicado (133).
        ex.identifySprite(new byte[8192], presentVram1(137),
                oamBlock(137, 0x08, 0x08), none);
        check("giro: 137 sin flip no pisa el flip de 133", ex.getSpriteFlipX());
    }

    private static void identifyVramDiffHasNoFlips() {
        SpriteExtractor ex = new SpriteExtractor();
        ex.notePlayerMove();
        byte[] vramA = new byte[8192];
        ex.findChangedTiles(vramA); // siembra el snapshot previo
        byte[] vramB = new byte[8192];
        for (int t = BASE; t < BASE + 4; t++) vramB[t * 16] = (byte) 0xFF;
        List<Integer> changed = ex.findChangedTiles(vramB);
        check("diff VRAM: 4 tiles cambiados", changed.size() == 4);
        int[] tiles = null;
        for (int i = 0; i < 6; i++) {
            tiles = ex.identifySprite(vramB, null, new byte[160], changed);
        }
        check("diff VRAM: publica base", tiles != null && tiles[0] == BASE);
        check("diff VRAM: banco 0", ex.getSpriteBank() == 0);
        check("diff VRAM: sin flipX", !ex.getSpriteFlipX());
        check("diff VRAM: sin flipY", !ex.getSpriteFlipY());
    }
}
