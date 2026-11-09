package com.gpsquest96.render;

import com.gpsquest96.emulator.core.EmulationPipeline;

/**
 * Atlas de tiles 2bpp de la hardware de 8 bits, extraído de la VRAM EN VIVO.
 * <p>
 * VISION.md B1: nada procedural como contenido — estos 8x8 salen de lo que
 * el juego cargó en VRAM (0x8000-0x9FFF = 384 tiles x 16 B). Formato 2bpp
 * planar + registro BGP (0xFF47) para los 4 tonos de verde GB.
 * <p>
 * Puro Java, sin JNI nuevo (usa {@code Mmu.read}), sin hilos propios:
 * el llamador lo invoca en worker throttled (p. ej. tras cada fetch OSM).
 * Sin datos (VRAM vacía o MMU ausente) informa 0 tiles, sin excepciones.
 */
public final class TileAtlas {

    public static final int TILE_PX = 8;
    public static final int TILE_COUNT = 384;
    public static final int VRAM_BASE = 0x8000;
    public static final int VRAM_SIZE = 0x2000;
    public static final int BGP_ADDR = 0xFF47;

    // 4 tonos GB (identidad: valor 0 = más claro, como Gen 2).
    // Públicos: SpriteExtractor compone sprites con la misma paleta.
    public static final int[] SHADES = {
            0xFF9BBC0F, 0xFF8BAC0F, 0xFF306230, 0xFF0F380F
    };

    private final int[] atlas = new int[TILE_COUNT * TILE_PX * TILE_PX];
    private int tilesRead = 0;
    private int bgp = 0xE4;

    private TileAtlas() {
    }

    public static TileAtlas snapshot(EmulationPipeline.Mmu mmu) {
        if (mmu == null) return new TileAtlas();
        try {
            byte[] vram = new byte[VRAM_SIZE];
            mmu.readBlock(VRAM_BASE, vram, VRAM_SIZE);
            return fromVram(vram, mmu.read(BGP_ADDR) & 0xFF);
        } catch (Throwable ignored) {
            return new TileAtlas();
        }
    }

    /**
     * Construye el atlas desde un buffer de VRAM YA leído (un solo cruce JNI
     * por frame cuando el llamador reutiliza el buffer).
     *
     * @param vram 8192 B de VRAM (0x8000-0x9FFF)
     * @param bgp  registro BGP (0xFF47)
     */
    public static TileAtlas fromVram(byte[] vram, int bgp) {
        TileAtlas a = new TileAtlas();
        a.bgp = bgp & 0xFF;
        if (vram == null || vram.length < VRAM_SIZE) return a;
        try {
            int n = 0;
            for (int t = 0; t < TILE_COUNT; t++) {
                boolean blank = true;
                for (int row = 0; row < TILE_PX; row++) {
                    int off = t * 16 + row * 2;
                    int lo = vram[off] & 0xFF;
                    int hi = vram[off + 1] & 0xFF;
                    if ((lo | hi) != 0) blank = false;
                    for (int col = 0; col < TILE_PX; col++) {
                        int bit = 7 - col;
                        int v = ((hi >> bit) & 1) << 1 | ((lo >> bit) & 1);
                        int shade = (a.bgp >> (v * 2)) & 0x03;
                        // Layout de la TEXTURA: 3072x8 (x = tile*8+col, y = row).
                        // Antes se escribía tile-mayor (t*64+...) y la subida
                        // lineal lo interpretaba como 3072x8 => atlas scramble.
                        a.atlas[row * (TILE_COUNT * TILE_PX) + t * TILE_PX + col] =
                                SHADES[shade & 0x03];
                    }
                }
                if (!blank) n++;
            }
            a.tilesRead = n;
        } catch (Throwable ignored) {
            // Buffer inválido: atlas vacío, sin excepción al llamador.
        }
        return a;
    }

    /** Tiles no vacíos en la foto (0 = sin datos). */
    public int getTileCount() {
        return tilesRead;
    }

    public int getBgp() {
        return bgp & 0xFF;
    }

    /** Atlas 8x(384*8) ARGB, fila por tile. Solo lectura. */
    public int[] getAtlasPixels() {
        return atlas;
    }
}
