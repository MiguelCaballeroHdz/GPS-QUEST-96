package com.gpsquest96.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifica y compone el sprite OW del jugador desde estado vivo de la ROM.
 * <p>
 * Dos fuentes, ambas 100% ROM (canon B4, sin offsets ni assets propios):
 * <ol>
 *   <li><b>OAM centrada</b> (preferente): el jugador está centrado en
 *       pantalla; el objeto 16x16 cuyo tile superior-izquierdo cae en
 *       (72,64) de 160x144 es el jugador. Es estable porque no depende de
 *       lo que animen los tiles de fondo.</li>
 *   <li><b>Diff de VRAM</b> (apoyo): tiles que cambian con el movimiento.
 *       Se usa solo cuando la OAM no da candidato.</li>
 * </ol>
 * Histéresis (spec 014 RF-06): un candidato solo se publica tras
 * {@link #CONFIRM_HITS} ticks consecutivos con el MISMO índice base; si el
 * índice cambia, la racha vuelve a 1. El sprite publicado no se reemplaza
 * hasta que otro candidato se confirme, así que el mapa no muestra saltos
 * (base=133/137 alternando cada frame en la auditoría del 2026-10-29).
 * <p>
 * El sprite publicado lleva banco, paleta y flips: los bits FlipX (0x20) y
 * FlipY (0x40) se leen del atributo OAM de la entrada superior-izquierda
 * del bloque 2x2 y se publican junto con el base. Sin ellos, al caminar el
 * sprite se compone sin voltear y se ve dividido (el juego voltea el bloque
 * al girar y en cuadros de la animación de paso).
 * <p>
 * Fallback honesto: sin identificación no se compone nada (punto blanco).
 */
public final class SpriteExtractor {

    public static final int OAM_BASE = 0xFE00;
    public static final int OAM_SIZE = 160;
    public static final int OBP0_ADDR = 0xFF48;
    public static final int SPRITE_PX = 16;

    /** Bit 3 del atributo OAM: en CGB selecciona el banco de VRAM del OBJ. */
    private static final int OAM_ATTR_BANK = 0x08;
    /** Bits 0-2 del atributo OAM: paleta OBJ CGB (8 paletas). */
    private static final int OAM_ATTR_PALETTE = 0x07;

    private static final long MOVE_WINDOW_MS = 2000;
    private static final int MAX_CHANGED_FOR_SPRITE = 64;
    // TL del jugador centrado en 160x144 (Gen 2).
    private static final int CENTER_X = 72;
    private static final int CENTER_Y = 64;
    // spec 015 D-25: en Gen 2 el bloque del jugador no cae exactamente en el
    // centro; medido en POCO la entrada más cercana queda a ~22 px. Con 16 px
    // se rechazaba el jugador real y solo pasaba el OBJ del título.
    private static final int CENTER_TOLERANCE = 28;
    // Histéresis: 6 ticks con el mismo base (~200 ms a la frecuencia del
    // snapshot) antes de publicar; soltar tras 1.5 s sin evidencia.
    private static final int CONFIRM_HITS = 6;
    private static final int RELEASE_MISSES = 45;

    private byte[] prevVram = null;
    private long lastPlayerMoveMs = 0;

    private int candidateBase = -1;
    private int candidateBank = 0;
    private int candidateStreak = 0;
    private int pendingBank = 0;
    private int pendingPalette = 0;
    private boolean pendingFlipX = false;
    private boolean pendingFlipY = false;
    private int missStreak = 0;
    private long lastLogMs = 0;
    // spec 015 D-25: el bloque de OAM centrado puede ser cualquier OBJ de la
    // pantalla (en el título de Gen 2 hay un 2x2 en (72,64) que salía como
    // blob púrpura sobre el mapa). Solo se publica tras ver ANIMACIÓN: el
    // índice base del bloque cambia entre ticks, que es lo que hace el
    // jugador al caminar. Un OBJ estático nunca se publica.
    private int lastOamBase = -1;
    private boolean animationSeen = false;
    private long lastRejectLogMs = 0;
    private volatile int[] confirmedTiles = null;
    private volatile int spriteBank = 0;
    private volatile int spritePalette = 0;
    private volatile boolean spriteFlipX = false;
    private volatile boolean spriteFlipY = false;

    /** Banco de VRAM del bloque publicado (0 = DMG/banco 0, 1 = CGB). */
    public int getSpriteBank() {
        return spriteBank;
    }

    /** Paleta OBJ CGB del bloque publicado (0-7). */
    public int getSpritePalette() {
        return spritePalette;
    }

    /** FlipX del bloque publicado (bit 0x20 del atributo OAM de la entrada TL). */
    public boolean getSpriteFlipX() {
        return spriteFlipX;
    }

    /** FlipY del bloque publicado (bit 0x40 del atributo OAM de la entrada TL). */
    public boolean getSpriteFlipY() {
        return spriteFlipY;
    }

    /** Registra que el jugador se movió (UI thread). */
    public void notePlayerMove() {
        lastPlayerMoveMs = System.currentTimeMillis();
    }

    /**
     * Compara VRAM actual con el snapshot previo y retorna índices de tiles
     * que cambiaron mientras el jugador se movía.
     *
     * @param vram 8192 bytes (0x8000-0x9FFF, 384 tiles x 16 B)
     */
    public List<Integer> findChangedTiles(byte[] vram) {
        List<Integer> changed = new ArrayList<>();
        if (vram == null || vram.length != 8192) return changed;

        boolean playerMoving = (System.currentTimeMillis() - lastPlayerMoveMs) < MOVE_WINDOW_MS;
        if (!playerMoving || prevVram == null) {
            prevVram = vram.clone();
            return changed;
        }
        for (int t = 0; t < 384; t++) {
            int off = t * 16;
            for (int j = 0; j < 16; j++) {
                if (vram[off + j] != prevVram[off + j]) {
                    changed.add(t);
                    break;
                }
            }
        }
        prevVram = vram.clone();
        return changed;
    }

    /**
     * Identifica el bloque de 4 tiles (2x2) del sprite OW.
     *
     * @param vram0   8192 B del banco 0 de VRAM (fondo/ventana)
     * @param vram1   8192 B del banco 1 de VRAM (OBJ en CGB); null/inválido en DMG
     * @param oam     160 B de OAM cruda (0xFE00-0xFE9F)
     * @param changed candidatos del diff de VRAM del banco 0 (puede ser vacío)
     * @return índices [TL..BR] publicados (histéresis superada) o null si aún
     *         no hay evidencia suficiente o el sprite se perdió
     */
    public int[] identifySprite(byte[] vram0, byte[] vram1, byte[] oam, List<Integer> changed) {
        int base = baseFromCenteredOam(vram0, vram1, oam);
        boolean viaOam = base >= 0;
        if (base < 0) {
            base = baseFromVramDiff(changed, vram0);
            if (base >= 0) {
                // El diff solo mira el banco 0 (DMG); en CGB el jugador vive
                // en el banco 1, así que este camino queda como respaldo.
                // Sin OAM no hay evidencia de flips: se publican sin voltear,
                // igual que hacía el overload de 7 args de composeSprite.
                pendingBank = 0;
                pendingPalette = 0;
                pendingFlipX = false;
                pendingFlipY = false;
            }
        }
        if (base < 0) {
            // Sin evidencia: contar la racha y soltar el sprite si se
            // mantiene demasiado tiempo (menú, combate, cambio de mapa).
            missStreak++;
            candidateBase = -1;
            candidateStreak = 0;
            if (missStreak >= RELEASE_MISSES) {
                if (confirmedTiles != null) {
                    android.util.Log.i("SpriteExtractor", "sprite OW oculto tras "
                            + missStreak + " ticks sin evidencia");
                }
                confirmedTiles = null;
                animationSeen = false; // D-25: nueva evidencia obligatoria
                lastOamBase = -1;
            }
            return confirmedTiles;
        }

        missStreak = 0;
        if (viaOam) {
            // D-25: evidencia de que el bloque centrado es el jugador. Vale
            // cualquiera de estas dos, ambas verificables desde la app:
            //  1) el índice base del bloque cambia (animación de paso);
            //  2) la app acaba de registrar movimiento del jugador (joystick
            //     o paso real), y ese bloque centrado aparece en pantalla.
            // Un OBJ decorativo del título (el blob púrpura original) no
            // cumple ninguna de las dos: no se mueve ni hay paso registrado.
            long nowMs = System.currentTimeMillis();
            if (lastOamBase >= 0 && base != lastOamBase) animationSeen = true;
            if (lastPlayerMoveMs > 0 && nowMs - lastPlayerMoveMs < MOVE_WINDOW_MS) {
                animationSeen = true;
            }
            lastOamBase = base;
            if (!animationSeen) {
                candidateBase = -1;
                candidateStreak = 0;
                return confirmedTiles;
            }
        } else {
            lastOamBase = -1;
        }
        // Flip inmediato: si el candidato es el sprite ya publicado (mismo
        // base y banco), el giro se aplica en este tick sin esperar otra
        // confirmación de 6 ticks. Al caminar los bases alternan (133/137)
        // y la racha no llega a 6, así que sin esto el sprite quedaba
        // ~200 ms mirando al lado viejo tras cada giro. Los tiles no
        // cambian, así que no hay riesgo de troceado: tiles y flips
        // describen el mismo bloque.
        if (confirmedTiles != null && base == confirmedTiles[0]
                && pendingBank == spriteBank) {
            spriteFlipX = pendingFlipX;
            spriteFlipY = pendingFlipY;
        }
        if (base == candidateBase && pendingBank == candidateBank) {
            candidateStreak++;
        } else {
            // Candidato distinto: la racha NO se hereda (antes un base nuevo
            // cada tick se confirmaba con el contador de otro).
            candidateBase = base;
            candidateBank = pendingBank;
            candidateStreak = 1;
        }
        if (candidateStreak < CONFIRM_HITS) {
            return confirmedTiles;
        }
        int[] previous = confirmedTiles;
        int[] tiles = new int[]{base, base + 1, base + 2, base + 3};
        confirmedTiles = tiles;
        spriteBank = pendingBank;
        spritePalette = pendingPalette;
        spriteFlipX = pendingFlipX;
        spriteFlipY = pendingFlipY;
        long now = System.currentTimeMillis();
        if ((previous == null || previous[0] != base) && now - lastLogMs > 1000) {
            lastLogMs = now;
            android.util.Log.i("SpriteExtractor", "sprite OW base=" + base
                    + " via=" + (viaOam ? "OAM" : "VRAM")
                    + " bank=" + spriteBank + " pal=" + spritePalette
                    + " flipX=" + spriteFlipX + " flipY=" + spriteFlipY
                    + " tiles=" + tiles[0] + "," + tiles[1] + ","
                    + tiles[2] + "," + tiles[3]
                    + " hits=" + candidateStreak);
        }
        return tiles;
    }

    /** Apoyo: 4 tiles consecutivos cambiados durante el movimiento. */
    private static int baseFromVramDiff(List<Integer> changed, byte[] vram) {
        if (changed == null || changed.isEmpty()) return -1;
        if (changed.size() > MAX_CHANGED_FOR_SPRITE) return -1; // cambio de tileset, no sprite
        boolean[] hit = new boolean[384];
        for (int t : changed) {
            if (t >= 0 && t < 384) hit[t] = true;
        }
        for (int t = 0; t <= 380; t++) {
            if (hit[t] && hit[t + 1] && hit[t + 2] && hit[t + 3] && tilesPresent(vram, t)) {
                return t;
            }
        }
        return -1;
    }

    /**
     * El jugador está centrado (160x144). Se localiza la entrada de OAM más
     * cercana al centro, se agrupan las entradas del mismo bloque 16x16 y se
     * toma la de ARRIBA-IZQUIERDA como base.
     * <p>
     * Evidencia POCO 2026-10-30: el jugador se dibuja con 4 entradas
     * (64,60)=tile 0 / (72,60)=1 / (64,68)=2 / (72,68)=3, todas con
     * atributo 0x08/0x88 → <b>banco 1 de VRAM</b> (bit 3 del atributo OAM).
     * Leer el banco 0 (o el banco que dejara VBK) devolvía tiles de fuente y
     * el mapa pintaba glifos en vez del jugador.
     */
    private int baseFromCenteredOam(byte[] vram0, byte[] vram1, byte[] oam) {
        if (oam == null || oam.length < OAM_SIZE || vram0 == null || vram0.length != 8192) {
            return -1;
        }
        int anchorX = 0;
        int anchorY = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < 40; i++) {
            int o = i * 4;
            int y = oam[o] & 0xFF;
            int x = oam[o + 1] & 0xFF;
            if (x == 0 || y == 0) continue; // entrada oculta
            int sx = x - 8;
            int sy = y - 16;
            int tile = oam[o + 2] & 0xFF;
            int bank = ((oam[o + 3] & OAM_ATTR_BANK) != 0) ? 1 : 0;
            int dist = Math.abs(sx - CENTER_X) + Math.abs(sy - CENTER_Y);
            if (dist < bestDist && tileNonBlank(vramFor(vram0, vram1, bank), tile)) {
                bestDist = dist;
                anchorX = sx;
                anchorY = sy;
            }
        }
        if (bestDist > CENTER_TOLERANCE) {
            long now = System.currentTimeMillis();
            if (now - lastRejectLogMs > 10000) {
                lastRejectLogMs = now;
                android.util.Log.i("SpriteExtractor", "sin bloque OAM centrado (dist="
                        + bestDist + ", tol=" + CENTER_TOLERANCE + ")");
            }
            return -1;
        }

        // Bloque 16x16: la celda superior-izquierda manda (y fija su banco).
        // Los flips salen del atributo de esa misma entrada TL: el juego
        // maneja el bloque 2x2 como un solo sprite y en la práctica las 4
        // entradas comparten el byte de atributo (evidencia POCO: las 4 con
        // 0x08/0x88, mismo bit de banco). Si alguna entrada discrepara,
        // manda la TL, igual que ya mandaba para base, banco y paleta, y
        // composeSprite aplica ese único par de flips a todo el bloque.
        int tlTile = -1;
        int tlBank = 0;
        int tlPalette = 0;
        boolean tlFlipX = false;
        boolean tlFlipY = false;
        int minSx = Integer.MAX_VALUE;
        int minSy = Integer.MAX_VALUE;
        for (int i = 0; i < 40; i++) {
            int o = i * 4;
            int y = oam[o] & 0xFF;
            int x = oam[o + 1] & 0xFF;
            if (x == 0 || y == 0) continue;
            int sx = x - 8;
            int sy = y - 16;
            if (Math.abs(sx - anchorX) > 8 || Math.abs(sy - anchorY) > 8) continue;
            if (sy < minSy || (sy == minSy && sx < minSx)) {
                minSy = sy;
                minSx = sx;
                tlTile = oam[o + 2] & 0xFF;
                tlBank = ((oam[o + 3] & OAM_ATTR_BANK) != 0) ? 1 : 0;
                tlPalette = oam[o + 3] & OAM_ATTR_PALETTE;
                tlFlipX = (oam[o + 3] & OAM_ATTR_FLIP_X) != 0;
                tlFlipY = (oam[o + 3] & OAM_ATTR_FLIP_Y) != 0;
            }
        }
        pendingBank = tlBank;
        pendingPalette = tlPalette;
        pendingFlipX = tlFlipX;
        pendingFlipY = tlFlipY;
        return (tlTile >= 0 && tlTile <= 380
                && tilesPresent(vramFor(vram0, vram1, tlBank), tlTile)) ? tlTile : -1;
    }

    /** VRAM del banco pedido (banco 0 si el banco 1 no está disponible). */
    private static byte[] vramFor(byte[] vram0, byte[] vram1, int bank) {
        if (bank == 1 && vram1 != null && vram1.length == 8192) {
            return vram1;
        }
        return vram0;
    }

    /** El tile existe y tiene al menos un byte distinto de cero. */
    private static boolean tileNonBlank(byte[] vram, int tile) {
        if (vram == null || tile < 0 || tile > 383) return false;
        int off = tile * 16;
        for (int j = 0; j < 16; j++) {
            if (vram[off + j] != 0) return true;
        }
        return false;
    }

    /** Los 4 tiles del bloque deben existir y no estar en blanco. */
    private static boolean tilesPresent(byte[] vram, int base) {
        if (base < 0 || base + 3 >= 384) return false;
        for (int t = base; t < base + 4; t++) {
            int off = t * 16;
            boolean any = false;
            for (int j = 0; j < 16; j++) {
                if (vram[off + j] != 0) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }
        return true;
    }

    private static final int OAM_ATTR_FLIP_X = 0x20;
    private static final int OAM_ATTR_FLIP_Y = 0x40;

    public int[] composeSprite(byte[] vram0, byte[] vram1, int base, int bank,
                               byte[] objPal, int palette, int obp0) {
        return composeSprite(vram0, vram1, base, bank, objPal, palette, obp0, false, false);
    }

    /**
     * Compone el sprite 2x2 (16x16) a partir de los tiles de VRAM del banco
     * indicado. Respeta atributos OAM FlipX/FlipY para evitar fragmentación.
     */
    public int[] composeSprite(byte[] vram0, byte[] vram1, int base, int bank,
                               byte[] objPal, int palette, int obp0, boolean flipX, boolean flipY) {
        byte[] vram = vramFor(vram0, vram1, bank);
        if (vram == null || vram.length != 8192) return null;
        if (base < 0 || base + 3 >= 384) return null;
        boolean cgb = objPal != null && objPal.length >= 64;
        int[] out = new int[SPRITE_PX * SPRITE_PX];
        for (int sub = 0; sub < 4; sub++) {
            int t = base + sub;
            int rBlock = sub / 2;
            int cBlock = sub % 2;
            if (flipX) cBlock = 1 - cBlock;
            if (flipY) rBlock = 1 - rBlock;
            int rowOff = rBlock * 8;
            int colOff = cBlock * 8;
            for (int row = 0; row < 8; row++) {
                int drawRow = flipY ? (7 - row) : row;
                int off = t * 16 + drawRow * 2;
                int lo = vram[off] & 0xFF;
                int hi = vram[off + 1] & 0xFF;
                for (int col = 0; col < 8; col++) {
                    int drawCol = flipX ? col : (7 - col);
                    int v = (((hi >> drawCol) & 1) << 1) | ((lo >> drawCol) & 1);
                    int px;
                    if (v == 0) {
                        px = 0x00000000; // transparente
                    } else if (cgb) {
                        px = cgbColor(objPal, palette, v);
                    } else {
                        int shade = (obp0 >> (v * 2)) & 0x03;
                        px = TileAtlas.SHADES[shade & 0x03];
                    }
                    out[(rowOff + row) * SPRITE_PX + colOff + col] = px;
                }
            }
        }
        return out;
    }

    /** Color RGB555 de la paleta OBJ CGB a ARGB opaco. */
    private static int cgbColor(byte[] objPal, int palette, int colorIndex) {
        int off = (palette & 0x07) * 8 + (colorIndex & 0x03) * 2;
        int v = (objPal[off] & 0xFF) | ((objPal[off + 1] & 0xFF) << 8);
        int r = v & 0x1F;
        int g = (v >> 5) & 0x1F;
        int b = (v >> 10) & 0x1F;
        return 0xFF000000
                | ((r * 255 / 31) << 16)
                | ((g * 255 / 31) << 8)
                | (b * 255 / 31);
    }

    /** Último bloque publicado (copia defensiva), o null. */
    public int[] getSpriteTiles() {
        int[] t = confirmedTiles;
        return t == null ? null : t.clone();
    }

    public void reset() {
        prevVram = null;
        confirmedTiles = null;
        candidateBase = -1;
        candidateBank = 0;
        candidateStreak = 0;
        pendingBank = 0;
        pendingPalette = 0;
        pendingFlipX = false;
        pendingFlipY = false;
        spriteBank = 0;
        spritePalette = 0;
        spriteFlipX = false;
        spriteFlipY = false;
        missStreak = 0;
        lastLogMs = 0;
        lastOamBase = -1;
        animationSeen = false;
    }
}
