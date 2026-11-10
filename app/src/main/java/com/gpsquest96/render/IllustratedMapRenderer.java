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
 * Mapa ilustrado (spec 018): la escena vectorial de {@link IllustratedMap}
 * dibujada con un shader propio de triángulos de color (sin texturas).
 * <p>
 * La escena vive en metros relativos a su centro; el shader aplica por
 * frame la escala (viewport + zoom) y el desplazamiento (slide continuo
 * por lat/lon en vivo), igual que hacía el raster: el jugador se mueve y
 * la geografía no salta. El fondo pergamino y la brújula son geometría
 * fija en pantalla (NDC) y se dibujan con el mismo programa, sin offset.
 * <p>
 * Solo hilo GL para el dibujo; la escena se construye en el hilo worker
 * del fetcher (`setElements`/`setRouteNodes`). Sin datos de vector la
 * escena es solo el pergamino (D5): `draw()` sigue devolviendo true y el
 * avatar y los nodos se dibujan encima.
 */
public class IllustratedMapRenderer {

    private static final String TAG = "IllustratedMapRenderer";

    private static final String VS =
            "#version 100\n" +
            "attribute vec2 a_position;\n" +
            "attribute vec4 a_color;\n" +
            "uniform vec2 u_scale;\n" +
            "uniform vec2 u_offset;\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(a_position * u_scale + u_offset, 0.0, 1.0);\n" +
            "    v_color = a_color;\n" +
            "}\n";
    private static final String FS =
            "#version 100\n" +
            "precision mediump float;\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "    gl_FragColor = v_color;\n" +
            "}\n";

    private static final int FLOATS_PER_VERTEX = 6; // x, y, r, g, b, a

    private int program = 0;
    private int posLoc = -1;
    private int colorLoc = -1;
    private int scaleLoc = -1;
    private int offsetLoc = -1;
    private FloatBuffer sceneBuffer;
    private FloatBuffer screenBuffer;
    private boolean ready = false;

    private volatile List<OsmElement> lastElements = null;
    private volatile List<RouteNode> routeNodes = new java.util.ArrayList<RouteNode>();
    private volatile double sceneLat = 0.0;
    private volatile double sceneLon = 0.0;
    private volatile IllustratedMap.Scene scene = null;
    private volatile float zoom = 1.0f; // zoom UI (cromo, no contenido)
    private volatile int surfW = 0;
    private volatile int surfH = 0;

    /** Buffer de pantalla (fondo + brújula, NDC fijo): se construye una vez. */
    private float[] screenVerts = null;

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
            scaleLoc = GLES20.glGetUniformLocation(program, "u_scale");
            offsetLoc = GLES20.glGetUniformLocation(program, "u_offset");
            if (posLoc < 0 || colorLoc < 0 || scaleLoc < 0 || offsetLoc < 0) {
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
     * Publica los elementos OSM (hilo worker del fetcher). La escena se
     * reconstruye aquí (Java puro); el hilo GL solo sube el buffer.
     * Lista vacía o nula = sin datos: el mapa es pergamino (D5), nunca vacío.
     */
    public synchronized void setElements(List<OsmElement> elements,
            double centerLat, double centerLon) {
        lastElements = elements;
        sceneLat = centerLat;
        sceneLon = centerLon;
        rebuildScene();
    }

    /**
     * Publica los nodos de la Ruta Maestra (hilo worker). Se dibujan sobre
     * la base con la prioridad más alta, como hacía el raster.
     */
    public synchronized void setRouteNodes(List<RouteNode> nodes) {
        routeNodes = (nodes != null) ? nodes : new java.util.ArrayList<RouteNode>();
        rebuildScene();
    }

    private void rebuildScene() {
        try {
            scene = IllustratedMap.build(lastElements, routeNodes, sceneLat, sceneLon);
            sceneBuffer = null; // fuerza la subida en el hilo GL
            Log.i(TAG, "Mapa ilustrado: " + scene.triangles + " triángulos / "
                    + scene.fills + " rellenos / " + scene.streets + " calles / "
                    + scene.icons + " iconos / " + scene.nodes + " nodos");
        } catch (Throwable t) {
            Log.w(TAG, "Escena omitida", t);
        }
    }

    /** Tamaño real de la superficie (hilo GL, onSurfaceChanged). */
    public void setViewport(int width, int height) {
        if (width <= 0 || height <= 0) return;
        surfW = width;
        surfH = height;
    }

    /** Zoom centrado (0.5x–4x). Solo cambia la escala metros→píxeles. */
    public void setZoom(float z) {
        if (z < 0.5f) z = 0.5f;
        if (z > 4.0f) z = 4.0f;
        zoom = z;
    }

    public float getZoom() {
        return zoom;
    }

    /** Lado de la unidad de escala (8 m) en píxeles (misma fórmula que el raster). */
    public float cellPixels() {
        if (surfW <= 0 || surfH <= 0) return 0f;
        return IllustratedMap.cellPixels(surfW, surfH, zoom);
    }

    /**
     * Dibuja la base ilustrada con slide continuo (hilo GL).
     *
     * @return false = renderer sin inicializar o sin superficie; true = base
     *         dibujada (pergamino siempre, con o sin datos de vector).
     */
    public boolean draw(double lat, double lon) {
        if (!ready) return false;
        try {
            int w = surfW;
            int h = surfH;
            if (w <= 0 || h <= 0) return false;
            IllustratedMap.Scene sc = scene;
            if (sc == null) {
                // Sin datos todavía: pergamino + nodos (D5).
                rebuildScene();
                sc = scene;
                if (sc == null) return false;
            }
            if (sceneBuffer == null) {
                uploadScene(sc);
                if (sceneBuffer == null) return false;
            }
            if (screenVerts == null) buildScreen();
            float cellPx = IllustratedMap.cellPixels(w, h, zoom);
            if (cellPx <= 0f) return false;
            double metersPerPx = IllustratedMap.SCALE_METERS / cellPx;
            float scaleX = (float) (2.0 / (w * metersPerPx));
            float scaleY = (float) (2.0 / (h * metersPerPx));
            double cosLat = Math.cos(Math.toRadians(sc.centerLat));
            double dxM = (lon - sc.centerLon) * IllustratedMap.METERS_PER_DEG * cosLat;
            double dyM = (lat - sc.centerLat) * IllustratedMap.METERS_PER_DEG;

            GLES20.glUseProgram(program);
            GLES20.glEnableVertexAttribArray(posLoc);
            GLES20.glEnableVertexAttribArray(colorLoc);
            final int stride = FLOATS_PER_VERTEX * 4;
            // Fondo pergamino + brújula (NDC fijo, sin escala ni offset).
            screenBuffer.position(0);
            GLES20.glUniform2f(scaleLoc, 1f, 1f);
            GLES20.glUniform2f(offsetLoc, 0f, 0f);
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT,
                    false, stride, screenBuffer);
            screenBuffer.position(2);
            GLES20.glVertexAttribPointer(colorLoc, 4, GLES20.GL_FLOAT,
                    false, stride, screenBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0,
                    screenVerts.length / FLOATS_PER_VERTEX);
            // Escena del mundo (metros → NDC + slide).
            sceneBuffer.position(0);
            GLES20.glUniform2f(scaleLoc, scaleX, scaleY);
            GLES20.glUniform2f(offsetLoc, (float) (-dxM * scaleX), (float) (dyM * scaleY));
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT,
                    false, stride, sceneBuffer);
            sceneBuffer.position(2);
            GLES20.glVertexAttribPointer(colorLoc, 4, GLES20.GL_FLOAT,
                    false, stride, sceneBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sc.triangles * 3);
            GLES20.glDisableVertexAttribArray(posLoc);
            GLES20.glDisableVertexAttribArray(colorLoc);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Draw de mapa omitido", t);
            return false;
        }
    }

    /** Sube la escena al buffer GL (hilo GL). */
    private void uploadScene(IllustratedMap.Scene sc) {
        float[] verts = sc.verts;
        if (verts == null || verts.length == 0) {
            sceneBuffer = ByteBuffer.allocateDirect(4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            return;
        }
        if (sceneBuffer == null || sceneBuffer.capacity() < verts.length) {
            sceneBuffer = ByteBuffer.allocateDirect(verts.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        sceneBuffer.clear();
        sceneBuffer.put(verts);
        sceneBuffer.position(0);
    }

    /**
     * Fondo pergamino a pantalla completa + brújula (flecha al norte, sin
     * texto) en la esquina inferior derecha, por encima de la atribución
     * (abajo centrada). Geometría NDC fija: no se desplaza con el mapa.
     */
    private void buildScreen() {
        float[] c = IllustratedMap.C_PARCHMENT;
        float[] ink = IllustratedMap.C_INK;
        // Fondo: quad NDC (-1,-1)-(1,1) = 2 triángulos.
        // Brújula: flecha (triángulo + asta) en (0.88, -0.88), ~0.05 NDC.
        float bx = 0.88f;
        float by = -0.88f;
        float s = 0.045f;
        screenVerts = new float[]{
                // fondo
                -1f, -1f, c[0], c[1], c[2], c[3],
                1f, -1f, c[0], c[1], c[2], c[3],
                -1f, 1f, c[0], c[1], c[2], c[3],
                1f, -1f, c[0], c[1], c[2], c[3],
                1f, 1f, c[0], c[1], c[2], c[3],
                -1f, 1f, c[0], c[1], c[2], c[3],
                // flecha (punta al norte = arriba)
                bx - s * 0.45f, by - s * 0.30f, ink[0], ink[1], ink[2], ink[3],
                bx + s * 0.45f, by - s * 0.30f, ink[0], ink[1], ink[2], ink[3],
                bx, by + s * 0.70f, ink[0], ink[1], ink[2], ink[3],
                // asta
                bx - s * 0.12f, by - s * 0.70f, ink[0], ink[1], ink[2], ink[3],
                bx + s * 0.12f, by - s * 0.70f, ink[0], ink[1], ink[2], ink[3],
                bx + s * 0.12f, by - s * 0.30f, ink[0], ink[1], ink[2], ink[3],
                bx - s * 0.12f, by - s * 0.70f, ink[0], ink[1], ink[2], ink[3],
                bx + s * 0.12f, by - s * 0.30f, ink[0], ink[1], ink[2], ink[3],
                bx - s * 0.12f, by - s * 0.30f, ink[0], ink[1], ink[2], ink[3],
        };
        if (screenBuffer == null
                || screenBuffer.capacity() < screenVerts.length) {
            screenBuffer = ByteBuffer.allocateDirect(screenVerts.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        screenBuffer.clear();
        screenBuffer.put(screenVerts);
        screenBuffer.position(0);
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
