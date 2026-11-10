package com.gpsquest96.render;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.gpsquest96.gps.TileMath;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Capa base de tiles raster OSM (spec 017 slice 2).
 * <p>
 * La lección del mapa viejo (oct 2026, "dos mapas cruzados"): la capa de
 * tiles y el raster/checker dibujaban A LA VEZ con proyecciones y anclas
 * distintas. Aquí la regla es estructural, no de disciplina: esta capa
 * dibuja UN marco completo 3x3 o no dibuja nada (`draw()` devuelve false y
 * el llamador cae al raster de respaldo). Un marco solo se publica cuando
 * sus 9 teselas están subidas a textura; jamás se dibuja un marco parcial
 * ni se mezclan niveles de zoom. La proyección es la misma que la del
 * raster (metros → píxeles con la celda de 8 m de `GBMapRenderer`), así que
 * al caer al respaldo la geografía no salta.
 * <p>
 * Solo hilo GL: `setTile` sube la textura en el momento (el `Bitmap` lo
 * conserva la caché RAM del manager; aquí no se recicla). Las texturas del
 * marco anterior se borran al publicar el nuevo (máx 9 + 9 texturas vivas).
 */
public class OsmTileLayer {

    private static final String TAG = "OsmTileLayer";

    private static final String VS =
            "#version 100\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texCoord;\n" +
            "varying vec2 v_texCoord;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_texCoord = a_texCoord;\n" +
            "}\n";
    private static final String FS =
            "#version 100\n" +
            "precision mediump float;\n" +
            "uniform sampler2D u_texture;\n" +
            "varying vec2 v_texCoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(u_texture, v_texCoord);\n" +
            "}\n";

    /** Una tesela subida: textura GL + bordes geográficos. */
    private static final class TileTex {
        final int texId;
        final double north;
        final double south;
        final double west;
        final double east;

        TileTex(int texId, double north, double south, double west, double east) {
            this.texId = texId;
            this.north = north;
            this.south = south;
            this.west = west;
            this.east = east;
        }
    }

    /** Marco completo e inmutable: 9 teselas de un mismo (z, cx, cy). */
    private static final class Frame {
        final int z;
        final int cx;
        final int cy;
        final Map<String, TileTex> tiles;
        final double north;
        final double south;
        final double west;
        final double east;

        Frame(int z, int cx, int cy, Map<String, TileTex> tiles) {
            this.z = z;
            this.cx = cx;
            this.cy = cy;
            this.tiles = tiles;
            int r = TileMath.GRID_RADIUS;
            this.north = TileMath.latNorth(cy - r, z);
            this.south = TileMath.latNorth(cy + r + 1, z);
            this.west = TileMath.lonWest(cx - r, z);
            this.east = TileMath.lonWest(cx + r + 1, z);
        }
    }

    private int program = 0;
    private int posLoc = -1;
    private int texLoc = -1;
    private int texUniform = -1;
    private boolean ready = false;
    private int surfW = 0;
    private int surfH = 0;

    /** Marco publicado (volatile: lo lee el hilo GL, lo escribe `setTile`). */
    private volatile Frame current = null;

    /** Acumulación del marco en curso (solo hilo GL). */
    private int pendingZ = -1;
    private int pendingCx = 0;
    private int pendingCy = 0;
    private final Map<String, TileTex> pending = new HashMap<String, TileTex>();

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
            texLoc = GLES20.glGetAttribLocation(program, "a_texCoord");
            texUniform = GLES20.glGetUniformLocation(program, "u_texture");
            if (posLoc < 0 || texLoc < 0 || texUniform < 0) {
                Log.e(TAG, "Atributos de tiles incompletos");
                return false;
            }
            ready = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Init fallido", t);
            return false;
        }
    }

    public void setViewport(int width, int height) {
        if (width <= 0 || height <= 0) return;
        surfW = width;
        surfH = height;
    }

    /** Zoom del marco publicado (-1 = sin marco: toca respaldo raster). */
    public int getActiveZ() {
        Frame f = current;
        return (f != null) ? f.z : -1;
    }

    /**
     * Sube una tesela a textura (SOLO hilo GL). Si pertenece al marco en
     * curso se acumula; al completar las 9 se publica el marco y se borran
     * las texturas del anterior. Si pertenece a un marco viejo (el jugador
     * ya se movió o cambió el zoom), su textura se borra en el acto: nunca
     * se dibuja una tesela suelta ni mezclada.
     *
     * @param cx, cy centro de la rejilla 3x3 a la que pertenece la tesela
     *               (lo entrega el manager con cada `TileDelivery`).
     */
    public void setTileCentered(int z, int cx, int cy, int x, int y, Bitmap bitmap) {
        if (!ready || bitmap == null || bitmap.isRecycled()) return;
        try {
            if (pendingZ != z || pendingCx != cx || pendingCy != cy) {
                // El jugador se movió o cambió el zoom: se descarta el marco
                // en curso (sus texturas se borran) y se empieza otro.
                deleteTextures(pending);
                pending.clear();
                pendingZ = z;
                pendingCx = cx;
                pendingCy = cy;
            }
            String key = TileMath.cacheKey(z, x, y);
            if (pending.containsKey(key)) return; // duplicado (RAM + disco)
            TileTex t = uploadTile(z, x, y, bitmap);
            if (t == null) return;
            pending.put(key, t);
            maybePublish();
        } catch (Throwable t) {
            Log.w(TAG, "Tile omitido", t);
        }
    }

    /** Sube el bitmap a una textura nueva (LINEAR: mapa legible al alejar). */
    private TileTex uploadTile(int z, int x, int y, Bitmap bitmap) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        if (tex[0] == 0) return null;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        return new TileTex(tex[0],
                TileMath.latNorth(y, z), TileMath.latNorth(y + 1, z),
                TileMath.lonWest(x, z), TileMath.lonWest(x + 1, z));
    }

    /** Publica el marco cuando sus 9 teselas están subidas. */
    private void maybePublish() {
        int want = (2 * TileMath.GRID_RADIUS + 1) * (2 * TileMath.GRID_RADIUS + 1);
        if (pending.size() < want) return;
        Frame old = current;
        current = new Frame(pendingZ, pendingCx, pendingCy,
                new HashMap<String, TileTex>(pending));
        pending.clear();
        deleteTextures((old != null) ? old.tiles : null);
        Log.i(TAG, "Marco de tiles publicado z=" + pendingZ
                + " centro=" + pendingCx + "," + pendingCy);
    }

    private void deleteTextures(Map<String, TileTex> tiles) {
        if (tiles == null || tiles.isEmpty()) return;
        try {
            int[] ids = new int[tiles.size()];
            int i = 0;
            for (TileTex t : tiles.values()) ids[i++] = t.texId;
            GLES20.glDeleteTextures(ids.length, ids, 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Dibuja el marco de tiles como base.
     *
     * @return true = marco completo cubriendo el viewport (base tiles);
     *         false = sin marco o sin cobertura (el llamador DEBE dibujar
     *         el raster de respaldo; nunca se dibujan ambas capas).
     */
    public boolean draw(double centerLat, double centerLon, float cellPx,
            int w, int h) {
        Frame f = current;
        if (!ready || f == null || w <= 0 || h <= 0 || cellPx <= 0f) return false;
        try {
            double[] span = TileMath.halfSpanM(w, h, cellPx);
            double cosLat = Math.cos(Math.toRadians(centerLat));
            double vNorth = centerLat + span[1] / 111320.0;
            double vSouth = centerLat - span[1] / 111320.0;
            double vWest = centerLon - span[0] / (111320.0 * cosLat);
            double vEast = centerLon + span[0] / (111320.0 * cosLat);
            if (!TileMath.covers(f.north, f.south, f.west, f.east,
                    vNorth, vSouth, vWest, vEast)) {
                return false;
            }
            GLES20.glUseProgram(program);
            GLES20.glUniform1i(texUniform, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glEnableVertexAttribArray(posLoc);
            GLES20.glEnableVertexAttribArray(texLoc);
            // Misma proyección que el raster: metros → píxeles con la celda
            // de 8 m, así que tiles y respaldo coinciden geográficamente.
            double metersPerPx = 8.0 / cellPx;
            for (TileTex t : f.tiles.values()) {
                float[] quad = quadFor(t, centerLat, centerLon,
                        metersPerPx, w, h);
                FloatBuffer pos = floatBufferOf(new float[]{
                        quad[0], quad[1], quad[2], quad[3],
                        quad[4], quad[5], quad[6], quad[7]});
                // V invertida (igual que el sprite): la fila 0 del PNG
                // (arriba) queda arriba del quad.
                FloatBuffer tex = floatBufferOf(new float[]{
                        0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f});
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.texId);
                GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT,
                        false, 0, pos);
                GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT,
                        false, 0, tex);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
            GLES20.glDisableVertexAttribArray(posLoc);
            GLES20.glDisableVertexAttribArray(texLoc);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Draw de tiles omitido", t);
            return false;
        }
    }

    /** Esquinas del quad en NDC: (sw, se, nw, ne) en x,y. */
    private static float[] quadFor(TileTex t, double centerLat, double centerLon,
            double metersPerPx, int w, int h) {
        double cosLat = Math.cos(Math.toRadians(centerLat));
        double westM = (t.west - centerLon) * 111320.0 * cosLat;
        double eastM = (t.east - centerLon) * 111320.0 * cosLat;
        double southM = (t.south - centerLat) * 111320.0;
        double northM = (t.north - centerLat) * 111320.0;
        float x0 = (float) (westM / metersPerPx * 2.0 / w);
        float x1 = (float) (eastM / metersPerPx * 2.0 / w);
        float y0 = (float) (southM / metersPerPx * 2.0 / h);
        float y1 = (float) (northM / metersPerPx * 2.0 / h);
        return new float[]{x0, y0, x1, y0, x0, y1, x1, y1};
    }

    private static FloatBuffer floatBufferOf(float[] verts) {
        FloatBuffer buf = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(verts).position(0);
        return buf;
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
