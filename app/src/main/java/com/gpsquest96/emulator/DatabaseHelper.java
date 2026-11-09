package com.gpsquest96.emulator;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistencia relacional nativa (STACK.md: SQLite sin ORMs).
 * <p>
 * Purga VISION.md (Task 8): las tablas inventadas ({@code creatures},
 * {@code dojo_medals}) se eliminaron. El spawn y las medallas saldrán de
 * la ROM en vivo (Fase B); este archivo queda como mecanismo (apertura
 * async + pool + cierre) para el futuro índice de la ROM.
 * <p>
 * v2 suelta las tablas viejas en instalaciones existentes.
 * Todo I/O en pool propio: jamás bloquea el hilo GL ni la red OSM.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "DatabaseHelper";
    private static final String DB_NAME = "gpsquest96_data.db";
    private static final int DB_VERSION = 2;

    public interface WriteCallback {
        void onSuccess(long rowId);
        void onError(String message);
    }

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    public DatabaseHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // minSdk 19 ≥ 16: onConfigure disponible.
        db.execSQL("PRAGMA foreign_keys=ON;");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Sin tablas: el schema fiel a ROM llega con la Fase B.
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Purga del prototipo: nada inventado persiste.
            db.execSQL("DROP TABLE IF EXISTS dojo_medals;");
            db.execSQL("DROP TABLE IF EXISTS creatures;");
        }
    }

    /**
     * Abre/crea el .db en background (dispara onCreate/onUpgrade async).
     */
    public void openAsync(final WriteCallback callback) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    getWritableDatabase();
                    if (callback != null) callback.onSuccess(-1);
                } catch (Exception e) {
                    Log.e(TAG, "Error abriendo DB", e);
                    if (callback != null) callback.onError(e.getMessage());
                }
            }
        });
    }

    public void shutdown() {
        pool.shutdownNow();
        try {
            close();
        } catch (Exception e) {
            Log.w(TAG, "Error cerrando DB", e);
        }
    }
}
