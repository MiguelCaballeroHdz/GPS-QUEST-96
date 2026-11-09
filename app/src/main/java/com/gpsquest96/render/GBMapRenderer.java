package com.gpsquest96.render;

import android.opengl.GLES20;
import android.util.Log;

import com.gpsquest96.gps.OsmDataFetcher.OsmElement;
import com.gpsquest96.route.RouteNode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Mapa GBC (Bloque C) pintado con datos OSM reales.
 * <p>
 * Rejilla 63x139 de celdas CUADRADAS anclada al mundo: el slide es continuo
 * por lat/lon en vivo y cada celda mide lo mismo en píxeles en X y en Y
 * (spec 014 RF-04), así que no deforma nada. Cada celda son 8 m (1 metatile,
 * spec 004 §5.2): la ventana cubre 504 x 1.112 m para que todo el radio de
 * trabajo de la ruta (250 m) sea visible. El raster vive en
 * {@link OsmRasterGrid}; aquí solo se compone el buffer GL.
 * <p>
 * Regla de oro 1 (nada inventado): este renderer NO fabrica terreno. Si no
 * hay elementos OSM devuelve {@code false} y no pinta ni un quad; la pantalla
 * queda con el {@code glClearColor} neutro del hilo GL. Cada celda pintada
 * corresponde a un elemento OSM recibido en {@link #setElements}.
 * <p>
 * spec 015 B3b (D-02): el pixel art de césped, arbolado y camino son TILES
 * REALES de la VRAM del juego, verificados con el volcado del overworld del
 * 2026-11-03 (ver {@code tools/poco-capture/20261103-015-b3b/}):
 * <pre>
 *   LCDC=0xE3 ⇒ tilemap 0x9800 y datos con signo (0x9000+i*16)
 *   índice 0x05 → VRAM 0x9050 = césped         → atlas 0x105
 *   índice 0x1E → VRAM 0x91E0 = copa de árbol  → atlas 0x11E
 *   índice 0x3D → VRAM 0x93D0 = camino         → atlas 0x13D
 * </pre>
 * Los tipos sin tile verificado en ese volcado (agua, edificio, comercio)
 * siguen pintándose con la tabla de color de cromo de UI: no se inventa un
 * tile que la ROM no haya mostrado.
 */
public class GBMapRenderer {

    private static final String TAG = "GBMapRenderer";

    // spec 015 B2/D-24: la rejilla debe tener el aspecto de la pantalla.
    // 12x27 (0,44) encajaba con un móvil 1220x2712, pero a 4,4 m/celda solo
    // cubría 53x119 m: los POIs a 150-250 m del ancla nunca entraban en el
    // raster. La constante de cobertura vive en OsmRasterGrid (63x139 a 8 m).
    public static final int COLS = OsmRasterGrid.COLS;
    public static final int ROWS = OsmRasterGrid.ROWS;

    // Tipos de celda (prioridad de raster ya resuelta en OsmRasterGrid.stamp()).
    public static final byte EMPTY = OsmRasterGrid.EMPTY;
    public static final byte PARK = OsmRasterGrid.PARK;
    public static final byte ROAD = OsmRasterGrid.ROAD;
    public static final byte BUILDING = OsmRasterGrid.BUILDING;
    public static final byte SHOP = OsmRasterGrid.SHOP;
    public static final byte WATER = OsmRasterGrid.WATER;
    public static final byte AMENITY = OsmRasterGrid.AMENITY;
    public static final byte TOURISM = OsmRasterGrid.TOURISM;
    public static final byte NODE = OsmRasterGrid.NODE;

    // ═══════════════════════════════════════════════════════
    // TABLA ÚNICA TIPO DE CELDA → COLOR (cromo UI, RGBA 0..1)
    // Solo para los tipos SIN tile verificado en VRAM.
    // ═══════════════════════════════════════════════════════
    private static final float[] C_EMPTY    = {0.05f, 0.16f, 0.05f, 1f}; // lienzo neutro
    private static final float[] C_PARK     = {0.20f, 0.45f, 0.16f, 1f}; // zona verde
    private static final float[] C_ROAD     = {0.74f, 0.74f, 0.68f, 1f}; // calle
    private static final float[] C_BUILDING = {0.55f, 0.36f, 0.18f, 1f}; // edificio
    private static final float[] C_SHOP     = {0.68f, 0.47f, 0.22f, 1f}; // comercio
    private static final float[] C_WATER    = {0.16f, 0.35f, 0.72f, 1f}; // agua
    private static final float[] C_AMENITY  = {0.86f, 0.28f, 0.44f, 1f}; // servicio (punto de curación)
    private static final float[] C_TOURISM  = {0.90f, 0.76f, 0.24f, 1f}; // museo/monumento
    private static final float[] C_NODE     = {0.98f, 0.92f, 0.36f, 1f}; // nodo de ruta (cromo UI)

    // Tintes de los tiles de la ROM: el PATRÓN es del juego (dither real de
    // 8x8); el TONO es cromo de UI para que el mapa siga siendo legible
    // (césped verde, arbolado oscuro, calle beige). Si se pinta el verde GB
    // puro, calle y césped se confunden.
    private static final float[] T_GRASS = {0.42f, 0.62f, 0.22f, 1f};
    private static final float[] T_TREE  = {0.16f, 0.36f, 0.14f, 1f};
    private static final float[] T_ROAD  = {0.80f, 0.78f, 0.70f, 1f};

    // ═══════════════════════════════════════════════════════
    // TILES DE LA ROM (índice del atlas = 256 + índice de tilemap)
    // Evidencia: tools/poco-capture/20261103-015-b3b/atlas-sheet.png
    // ═══════════════════════════════════════════════════════
    private static final int TILE_GRASS = 0x105; // tilemap 0x05 · VRAM 0x9050
    private static final int TILE_TREE  = 0x11E; // tilemap 0x1E · VRAM 0x91E0
    private static final int TILE_PATH  = 0x13D; // tilemap 0x3D · VRAM 0x93D0

    /** Ancho del atlas VRAM en tiles (TileAtlas.TILE_COUNT). */
    private static final int ATLAS_TILES = 384;
    /**
     * Lado de tile en pantalla (px) con el que se repite el patrón. Los tiles
     * del juego son 8x8 con dither de 1 px: a 24 px el tramado se lee como
     * rayado; a 12 px (1,5x) se lee como textura, igual que en la GBC.
     */
    private static final float TILE_DISPLAY_PX = 12f;

    private static final String VS =
            "#version 100\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec4 a_color;\n" +
            "attribute vec2 a_tile;\n" +   // base uv del tile (-1 = color plano)
            "attribute vec2 a_rep;\n" +    // 0..R dentro de la celda
            "uniform vec2 u_offset;\n" +
            "varying vec4 v_color;\n" +
            "varying vec2 v_tile;\n" +
            "varying vec2 v_rep;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position + u_offset, 0.0, 1.0);\n" +
            "    v_color = a_color;\n" +
            "    v_tile = a_tile;\n" +
            "    v_rep = a_rep;\n" +
            "}\n";
    private static final String FS =
            "#version 100\n" +
            "precision mediump float;\n" +
            "uniform sampler2D u_atlas;\n" +
            "varying vec4 v_color;\n" +
            "varying vec2 v_tile;\n" +
            "varying vec2 v_rep;\n" +
            "void main() {\n" +
            "    if (v_tile.x < 0.0) { gl_FragColor = v_color; return; }\n" +
            "    vec2 uv = v_tile + clamp(fract(v_rep), 0.0, 0.9999) * vec2(1.0/384.0, 1.0);\n" +
            "    vec3 tex = texture2D(u_atlas, uv).rgb;\n" +
            "    float lum = dot(tex, vec3(0.299, 0.587, 0.114));\n" +
            "    gl_FragColor = vec4(v_color.rgb * (0.35 + 1.10 * lum), 1.0);\n" +
            "}\n";

    private static final int FLOATS_PER_VERTEX = 10; // x, y, r, g, b, a, u, v, repU, repV
    private static final int VERTS_PER_CELL = 6;     // dos triángulos

    private int program = 0;
    private int posLoc = -1;
    private int colorLoc = -1;
    private int tileLoc = -1;
    private int repLoc = -1;
    private int offsetLoc = -1;
    private int atlasLoc = -1;
    private FloatBuffer cellsBuffer;
    /** Array reutilizable para el buffer de celdas (spec 002 RF-02). */
    private float[] scratch;
    private boolean ready = false;

    private volatile byte[] cells;
    private volatile List<OsmElement> lastElements = null;
    private volatile double lastCenterLat = 0.0;
    private volatile double lastCenterLon = 0.0;
    private volatile List<RouteNode> routeNodes = new java.util.ArrayList<RouteNode>();
    private volatile double rasterLat = 0.0;
    private volatile double rasterLon = 0.0;
    private volatile boolean hasData = false;
    private volatile float zoom = 1.0f; // zoom UI (cromo, no contenido)
    private volatile int surfW = 0;
    private volatile int surfH = 0;

    /** Textura del atlas VRAM (0 = todavía sin datos del juego). */
    private volatile int atlasTexId = 0;

    private int builtW = -1;
    private int builtH = -1;
    private float builtZoom = -1f;
    private boolean builtAtlas = false;
    private byte[] builtCells = null;

    public boolean initialize() {
        try {
            int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
            if (vs == 0 || fs == 0) return false;
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                Log.e(TAG, "Link rechazado: " + GLES20.glGetProgramInfoLog(program));
                return false;
            }
            posLoc = GLES20.glGetAttribLocation(program, "a_position");
            colorLoc = GLES20.glGetAttribLocation(program, "a_color");
            tileLoc = GLES20.glGetAttribLocation(program, "a_tile");
            repLoc = GLES20.glGetAttribLocation(program, "a_rep");
            offsetLoc = GLES20.glGetUniformLocation(program, "u_offset");
            atlasLoc = GLES20.glGetUniformLocation(program, "u_atlas");
            if (posLoc < 0 || colorLoc < 0 || tileLoc < 0 || repLoc < 0
                    || offsetLoc < 0 || atlasLoc < 0) {
                Log.e(TAG, "Atributos del mapa incompletos");
                return false;
            }
            ready = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Init fallido", t);
            return false;
        }
    }

    /**
     * Publica la textura del atlas VRAM ya subida (hilo GL). Se llama solo
     * cuando el atlas tiene píxeles reales del juego: antes de eso el mapa
     * pinta color plano, nunca un tile transparente.
     */
    public void setAtlasTexture(int texId) {
        atlasTexId = texId;
    }

    /**
     * Rasteriza elementos OSM a celdas (hilo worker del fetcher).
     * Lista vacía o nula = sin datos: el mapa deja de pintarse entero.
     */
    public synchronized void setElements(List<OsmElement> elements,
            double centerLat, double centerLon) {
        lastElements = elements;
        lastCenterLat = centerLat;
        lastCenterLon = centerLon;
        rasterize(elements, centerLat, centerLon);
    }

    /**
     * Publica los nodos de la Ruta Maestra (hilo worker). Se estampan sobre la
     * rejilla con la prioridad más alta y se repinta si ya había elementos OSM.
     */
    public synchronized void setRouteNodes(List<RouteNode> nodes) {
        routeNodes = (nodes != null) ? nodes : new java.util.ArrayList<RouteNode>();
        List<OsmElement> els = lastElements;
        if (els != null) rasterize(els, lastCenterLat, lastCenterLon);
    }

    private void rasterize(List<OsmElement> elements,
            double centerLat, double centerLon) {
        try {
            // El raster (geometría, prioridad y tipos) vive en OsmRasterGrid:
            // se puede probar en host y aquí solo se publica la rejilla lista.
            OsmRasterGrid.Result r = OsmRasterGrid.rasterize(
                    elements, centerLat, centerLon, routeNodes);
            byte[] grid = r.cells;
            cells = grid;
            builtCells = null; // fuerza reconstrucción en el hilo GL
            rasterLat = centerLat;
            rasterLon = centerLon;
            hasData = r.painted > 0;
            Log.i(TAG, "OSM raster: " + r.painted + " celdas / " + r.osmNodes
                    + " nodos (rejilla " + COLS + "x" + ROWS + " @ "
                    + (int) OsmRasterGrid.METERS_PER_CELL + " m)");
            if (!hasData) {
                Log.i(TAG, "OSM sin elementos: mapa plano, cero terreno inventado");
            } else {
                // spec 015 B3b: constancia de qué tipos llevan tile real de VRAM.
                Log.i(TAG, "tipos: vacío=" + r.count(EMPTY) + " parque=" + r.count(PARK)
                        + " calle=" + r.count(ROAD) + " edificio=" + r.count(BUILDING)
                        + " comercio=" + r.count(SHOP) + " agua=" + r.count(WATER)
                        + " amenity=" + r.count(AMENITY) + " tourism=" + r.count(TOURISM)
                        + " nodo_ruta=" + r.count(NODE) + " (estampados "
                        + r.routeStamped + ") · con tile ROM: vacío/parque/calle");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Raster omitido", t);
        }
    }

    /**
     * Tamaño real de la superficie (hilo GL, onSurfaceChanged).
     */
    public void setViewport(int width, int height) {
        if (width <= 0 || height <= 0) return;
        surfW = width;
        surfH = height;
    }

    /**
     * Zoom centrado (0.5x–4x). Solo cambia el tamaño de celda en pantalla.
     */
    public void setZoom(float z) {
        if (z < 0.5f) z = 0.5f;
        if (z > 4.0f) z = 4.0f;
        zoom = z;
    }

    public float getZoom() {
        return zoom;
    }

    /** Lado de celda en píxeles con el viewport y zoom actuales (spec 015 B2). */
    public float cellPixels() {
        if (surfW <= 0 || surfH <= 0) return 0f;
        return cellPixels(surfW, surfH, zoom);
    }

    /**
     * Dibuja la rejilla con slide continuo (hilo GL).
     *
     * @return false = sin datos OSM; el llamador NO debe pintar terreno.
     */
    public boolean draw(double lat, double lon) {
        if (!ready || !hasData) return false;
        try {
            byte[] grid = cells;
            int w = surfW;
            int h = surfH;
            if (grid == null || w <= 0 || h <= 0) return false;
            float z = zoom;
            int atlas = atlasTexId;
            boolean textured = atlas != 0;

            if (grid != builtCells || w != builtW || h != builtH
                    || z != builtZoom || textured != builtAtlas) {
                rebuildBuffer(grid, w, h, z, textured);
                if (cellsBuffer == null) return false;
            }

            double cellH = OsmRasterGrid.METERS_PER_CELL / 111320.0;
            double cellW = cellH / Math.cos(Math.toRadians(rasterLat));
            double dx = (lon - rasterLon) / cellW;
            double dy = (lat - rasterLat) / cellH;
            float cellPx = cellPixels(w, h, z);
            float cw = 2f * cellPx / w;
            float ch = 2f * cellPx / h;
            float ox = (float) (-dx * cw);
            float oy = (float) (dy * ch);

            GLES20.glUseProgram(program);
            GLES20.glUniform2f(offsetLoc, ox, oy);
            GLES20.glUniform1i(atlasLoc, 0);
            if (textured) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlas);
            }
            GLES20.glEnableVertexAttribArray(posLoc);
            GLES20.glEnableVertexAttribArray(colorLoc);
            GLES20.glEnableVertexAttribArray(tileLoc);
            GLES20.glEnableVertexAttribArray(repLoc);
            cellsBuffer.position(0);
            final int stride = FLOATS_PER_VERTEX * 4;
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, stride, cellsBuffer);
            cellsBuffer.position(2);
            GLES20.glVertexAttribPointer(colorLoc, 4, GLES20.GL_FLOAT, false, stride, cellsBuffer);
            cellsBuffer.position(6);
            GLES20.glVertexAttribPointer(tileLoc, 2, GLES20.GL_FLOAT, false, stride, cellsBuffer);
            cellsBuffer.position(8);
            GLES20.glVertexAttribPointer(repLoc, 2, GLES20.GL_FLOAT, false, stride, cellsBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, COLS * ROWS * VERTS_PER_CELL);
            GLES20.glDisableVertexAttribArray(posLoc);
            GLES20.glDisableVertexAttribArray(colorLoc);
            GLES20.glDisableVertexAttribArray(tileLoc);
            GLES20.glDisableVertexAttribArray(repLoc);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Draw de mapa omitido", t);
            return false;
        }
    }

    /**
     * Lado de celda en píxeles. Igual en X y en Y ⇒ celdas cuadradas.
     * Se toma el mayor de los dos ajustes para que la rejilla cubra la
     * pantalla completa (el sobrante se recorta, centrado).
     */
    private static float cellPixels(int w, int h, float zoom) {
        float byW = (float) w / COLS;
        float byH = (float) h / ROWS;
        return Math.max(byW, byH) * zoom;
    }

    /**
     * Reconstruye posiciones, color y tile de toda la rejilla (hilo GL).
     * Coordenadas relativas al centro de la rejilla; el slide va en u_offset.
     * Reutiliza el array y el buffer: con 8.757 celdas no se asigna memoria
     * nueva en cada actualización de OSM (spec 002 RF-02).
     */
    private void rebuildBuffer(byte[] grid, int w, int h, float z, boolean textured) {
        float cellPx = cellPixels(w, h, z);
        float cw = 2f * cellPx / w;
        float ch = 2f * cellPx / h;
        int repeat = Math.max(1, Math.round(cellPx / TILE_DISPLAY_PX));
        int cellsN = COLS * ROWS;
        int need = cellsN * VERTS_PER_CELL * FLOATS_PER_VERTEX;
        if (scratch == null || scratch.length < need) scratch = new float[need];
        float[] data = scratch;
        int i = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                float x0 = (c - COLS / 2f) * cw;
                float x1 = x0 + cw;
                float y1 = (ROWS / 2f - r) * ch;
                float y0 = y1 - ch;
                byte kind = grid[r * COLS + c];
                int tile = textured ? tileForCell(kind) : -1;
                float[] col = (tile >= 0) ? tintForCell(kind) : colorForCell(kind);
                float tu = (tile >= 0) ? (tile / (float) ATLAS_TILES) : -1f;
                float ru = (float) repeat;
                // triángulo 1: (x0,y0) (x1,y0) (x0,y1)
                i = put(data, i, x0, y0, col, tu, 0f, ru);
                i = put(data, i, x1, y0, col, tu, ru, ru);
                i = put(data, i, x0, y1, col, tu, 0f, 0f);
                // triángulo 2: (x1,y0) (x1,y1) (x0,y1)
                i = put(data, i, x1, y0, col, tu, ru, ru);
                i = put(data, i, x1, y1, col, tu, ru, 0f);
                i = put(data, i, x0, y1, col, tu, 0f, 0f);
            }
        }
        if (cellsBuffer == null || cellsBuffer.capacity() < need) {
            cellsBuffer = ByteBuffer.allocateDirect(need * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        cellsBuffer.clear();
        cellsBuffer.put(data, 0, need);
        cellsBuffer.position(0);
        builtCells = grid;
        builtW = w;
        builtH = h;
        builtZoom = z;
        builtAtlas = textured;
        Log.i(TAG, "rejilla " + COLS + "x" + ROWS + " celdas cuadradas de "
                + cellPx + " px (pantalla " + w + "x" + h + ", zoom " + z
                + ", tile " + (textured ? ("x" + repeat) : "plano") + ")");
    }

    /**
     * Escribe un vértice: posición, color y (base de tile, repetición).
     *
     * @param tu base u del tile en el atlas (-1 = celda de color plano)
     * @param repU repeticiones horizontales dentro de la celda (0 en plano)
     * @param repV repeticiones verticales dentro de la celda (0 en plano)
     */
    private static int put(float[] data, int i, float x, float y, float[] col,
                           float tu, float repU, float repV) {
        data[i++] = x;
        data[i++] = y;
        data[i++] = col[0];
        data[i++] = col[1];
        data[i++] = col[2];
        data[i++] = col[3];
        data[i++] = tu;
        data[i++] = (tu >= 0f) ? 0f : -1f;
        data[i++] = repU;
        data[i++] = repV;
        return i;
    }

    /**
     * Tile real de la ROM para el tipo de celda. -1 = sin evidencia de tile
     * (agua/edificio/comercio): se pinta el color de cromo de UI.
     */
    private static int tileForCell(byte kind) {
        switch (kind) {
            case EMPTY: return TILE_GRASS;
            case PARK: return TILE_TREE;
            case ROAD: return TILE_PATH;
            default: return -1;
        }
    }

    /**
     * Tinte de UI para las celdas que llevan tile de la ROM.
     */
    private static float[] tintForCell(byte kind) {
        switch (kind) {
            case PARK: return T_TREE;
            case ROAD: return T_ROAD;
            case EMPTY:
            default: return T_GRASS;
        }
    }

    /**
     * TABLA ÚNICA tipo de celda → color. Añadir aquí cualquier tipo nuevo.
     */
    private static float[] colorForCell(byte kind) {
        switch (kind) {
            case PARK: return C_PARK;
            case ROAD: return C_ROAD;
            case BUILDING: return C_BUILDING;
            case SHOP: return C_SHOP;
            case WATER: return C_WATER;
            case AMENITY: return C_AMENITY;
            case TOURISM: return C_TOURISM;
            case NODE: return C_NODE;
            case EMPTY:
            default: return C_EMPTY;
        }
    }

    private static int compile(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader rechazado: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
