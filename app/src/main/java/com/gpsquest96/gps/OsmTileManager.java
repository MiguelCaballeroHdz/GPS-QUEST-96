package com.gpsquest96.gps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiles raster OSM (spec 017 slice 2): descarga y caché en disco + RAM.
 * <p>
 * Reimplementación desde cero del viejo `TileQueueManager` (oct 2026, borrado
 * el 9 oct 2026 en "adiós OSM"): se reutilizan sus parámetros que sí
 * funcionaban (pool fijo de 2 hilos, RAM `LruCache` ~6 MB, disco
 * `filesDir/maps/z_x_y.png`, dedupe en vuelo, endpoint
 * `tile.openstreetmap.org` + User-Agent OSM). Lo que NO se reutiliza es su
 * integración: entonces las tiles iban bajo un checker translúcido (alfa
 * 0.45) y el raster encima, "dos mapas cruzados"; ahora la capa de tiles es
 * la base única o no se dibuja (la decisión vive en `OsmTileLayer` y en
 * `GameRenderer`, nunca ambas a la vez).
 * <p>
 * Solo `HttpURLConnection` + pool propio. Sin osmdroid/okhttp/gson/Glide:
 * Miguel compila con `--offline` y el STACK lo prohíbe. Patrón de caché y
 * de descarga tomado de `OsmDataFetcher` (TTL 7 días, mismo User-Agent).
 * Todo en workers, jamás en UI/GL: el hilo GL solo recibe `Bitmap` listos
 * por el listener y los sube a textura.
 */
public class OsmTileManager {

    private static final String TAG = "OsmTileManager";

    /** Endpoint de tiles OSM (el viejo fallaba con 404 antes de la corrección). */
    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String CONTACT_URL = "https://github.com/MiguelCaballeroHdz";
    /** Tiles de 256 px: 15 s sobran; un tile no es una query Overpass de 40 s. */
    private static final int TIMEOUT_MS = 15000;
    /** Caché de disco: 7 días, igual que `OsmDataFetcher`. */
    private static final long DISK_TTL_MS = TileMath.DISK_TTL_MS;
    /** RAM: ~6 MB, igual que el viejo TileQueueManager. */
    private static final int RAM_MAX_BYTES = 6 * 1024 * 1024;
    /** Pool fijo de 2 hilos, igual que el viejo TileQueueManager. */
    private static final int POOL_THREADS = 2;
    /** Throttle de petición de área: 2 s, igual que el viejo `requestMapTiles`. */
    private static final long REQUEST_THROTTLE_MS = 2000;

    /** Entrega de un tile listo (hilo worker → el llamador lo encola a GL). */
    public interface TileListener {
        void onTile(TileDelivery delivery);
    }

    /** Una tesela lista con el centro de su rejilla 3x3 (identidad del marco). */
    public static final class TileDelivery {
        public final int z;
        public final int cx;
        public final int cy;
        public final int x;
        public final int y;
        public final Bitmap bitmap;

        public TileDelivery(int z, int cx, int cy, int x, int y, Bitmap bitmap) {
            this.z = z;
            this.cx = cx;
            this.cy = cy;
            this.x = x;
            this.y = y;
            this.bitmap = bitmap;
        }
    }

    private final File mapsDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_THREADS);
    private final LruCache<String, Bitmap> ramCache;
    /** Claves con descarga en vuelo (dedupe: una sola descarga por tesela). */
    private final ConcurrentHashMap<String, Boolean> inFlight =
            new ConcurrentHashMap<String, Boolean>();
    private volatile TileListener listener;
    private volatile long lastRequestMs = 0;
    private volatile String lastRequestKey = "";

    /** Contadores de diagnóstico (para el log y el overlay Red). */
    private final AtomicInteger statRequested = new AtomicInteger();
    private final AtomicInteger statRam = new AtomicInteger();
    private final AtomicInteger statDisk = new AtomicInteger();
    private final AtomicInteger statNet = new AtomicInteger();
    private final AtomicInteger statFailed = new AtomicInteger();

    public OsmTileManager(Context context) {
        File base = context.getFilesDir();
        this.mapsDir = (base != null) ? new File(base, "maps") : null;
        if (mapsDir != null && !mapsDir.exists()) mapsDir.mkdirs();
        this.ramCache = new LruCache<String, Bitmap>(RAM_MAX_BYTES) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                try {
                    return value.getByteCount();
                } catch (Throwable t) {
                    return 256 * 256 * 4;
                }
            }
        };
    }

    public void setListener(TileListener listener) {
        this.listener = listener;
    }

    /**
     * Pide la rejilla 3x3 alrededor del punto en el zoom dado. Nunca bloquea
     * al llamador: RAM → disco → red, todo en el pool. Peticiones repetidas
     * del mismo centro y zoom dentro de 2 s se ignoran (throttle).
     */
    public void requestArea(final double lat, final double lon, final int z) {
        final int cx = TileMath.tileX(lon, z);
        final int cy = TileMath.tileY(lat, z);
        final String areaKey = z + "_" + cx + "_" + cy;
        long now = System.currentTimeMillis();
        if (areaKey.equals(lastRequestKey) && now - lastRequestMs < REQUEST_THROTTLE_MS) {
            return;
        }
        lastRequestMs = now;
        lastRequestKey = areaKey;
        final int[][] tiles = TileMath.tilesForCenter(lat, lon, z, TileMath.GRID_RADIUS);
        for (int[] t : tiles) {
            final int x = t[0];
            final int y = t[1];
            statRequested.incrementAndGet();
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    fetchOne(z, cx, cy, x, y);
                }
            });
        }
    }

    /** RAM → disco → red, con dedupe en vuelo. Nunca lanza excepciones. */
    private void fetchOne(int z, int cx, int cy, int x, int y) {
        final String key = TileMath.cacheKey(z, x, y);
        try {
            Bitmap ram = ramCache.get(key);
            if (ram != null && !ram.isRecycled()) {
                statRam.incrementAndGet();
                deliver(z, cx, cy, x, y, ram);
                return;
            }
            Bitmap disk = loadFromDisk(key);
            if (disk != null) {
                statDisk.incrementAndGet();
                ramCache.put(key, disk);
                deliver(z, cx, cy, x, y, disk);
                return;
            }
            // Dedupe: si otro hilo ya la está bajando, no se duplica la red.
            if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) return;
            try {
                byte[] png = download(z, x, y);
                if (png == null) {
                    statFailed.incrementAndGet();
                    return;
                }
                saveToDisk(key, png);
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp == null) {
                    statFailed.incrementAndGet();
                    Log.w(TAG, "Tile " + key + " no decodifica como PNG");
                    return;
                }
                statNet.incrementAndGet();
                ramCache.put(key, bmp);
                deliver(z, cx, cy, x, y, bmp);
            } finally {
                inFlight.remove(key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Tile " + key + " fallido", t);
            statFailed.incrementAndGet();
        }
    }

    private void deliver(int z, int cx, int cy, int x, int y, Bitmap bitmap) {
        TileListener l = listener;
        if (l != null) l.onTile(new TileDelivery(z, cx, cy, x, y, bitmap));
    }

    private File cacheFile(String key) {
        if (mapsDir == null) return null;
        return new File(mapsDir, key + ".png");
    }

    /** Disco con TTL (misma semántica que `OsmDataFetcher.loadFromDisk`). */
    private Bitmap loadFromDisk(String key) {
        File f = cacheFile(key);
        if (f == null || !f.exists()) return null;
        long modified = f.lastModified();
        if (TileMath.isExpired(modified, System.currentTimeMillis(), DISK_TTL_MS)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "Caché de tile ilegible: " + key, e);
            return null;
        }
    }

    private void saveToDisk(String key, byte[] png) {
        File f = cacheFile(key);
        if (f == null) return;
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(png);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "No se pudo cachear tile: " + key, e);
        }
    }

    /**
     * Una sola descarga GET. Sin reintento aquí: el próximo `requestArea`
     * (movimiento o zoom) la reintenta. Respeta la política de uso de tiles
     * OSM: User-Agent propio con contacto, sin descarga masiva (rejilla 3x3
     * con throttle de 2 s por área).
     */
    private byte[] download(int z, int x, int y) {
        String url = String.format(Locale.ROOT, TILE_URL, z, x, y);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "GPSQuest96/0.1.0 (" + CONTACT_URL + ")");
            conn.setRequestProperty("Referer", CONTACT_URL);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Tile HTTP " + code + ": " + url);
                return null;
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            Log.w(TAG, "Descarga de tile fallida: " + url, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Diagnóstico para el log / overlay: pedidas, RAM, disco, red, fallos. */
    public String stats() {
        return "tiles pedidas " + statRequested.get()
                + " ram " + statRam.get()
                + " disco " + statDisk.get()
                + " red " + statNet.get()
                + " fallos " + statFailed.get();
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
