package com.gpsquest96.emulator.core;

import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Pipeline de emulación completo (CPU -> MMU -> PPU -> Renderer).
 * <p>
 * Conecta todos los componentes de emulación en un solo flujo:
 * 1. CPU LR35902 ejecuta instrucciones
 * 2. MMU gestiona el acceso a memoria
 * 3. PPU renderiza tiles y scanlines
 * 4. Renderer OpenGL ES 2.0 muestra el frame
 * <p>
 * Esta clase actúa como puente entre Java y C++ (JNI) para
 * el pipeline de emulación completo.
 */
public class EmulationPipeline {

    private static final String TAG = "EmulationPipeline";

    // Componentes del pipeline
    private Cpu cpu;
    private Mmu mmu;
    private Ppu ppu;
    private Renderer renderer;

    // Estado
    private boolean isInitialized = false;

    public EmulationPipeline() {
        Log.i(TAG, "EmulationPipeline creado");
    }

    /**
     * Inicializa el pipeline de emulación.
     * Conecta CPU -> MMU -> PPU -> Renderer.
     */
    public void initialize() {
        Log.i(TAG, "Inicializando pipeline de emulación...");

        // Crear componentes
        cpu = new Cpu();
        mmu = new Mmu();
        ppu = new Ppu();
        renderer = new Renderer();

        // Inicializar componentes ANTES de enlazar: los setMmu/setPpu JNI
        // operan sobre los singletons nativos (g_cpu/g_mmu/g_ppu), que solo
        // existen tras initialize(). Enlazar antes se descarta en silencio
        // y cpu.step() gira en vacío (mmu_==nullptr → 4 ciclos sin PC).
        cpu.initialize();
        mmu.initialize();
        ppu.initialize();

        // Conectar componentes (con singletons ya creados el enlace pega).
        cpu.setMmu(mmu);
        ppu.setMmu(mmu);
        ppu.setRenderer(renderer);

        isInitialized = true;
        Log.i(TAG, "Pipeline de emulación inicializado");
    }

    /**
     * Obtiene el CPU.
     */
    public Cpu getCpu() {
        return cpu;
    }

    /**
     * Obtiene la MMU.
     */
    public Mmu getMmu() {
        return mmu;
    }

    /**
     * Obtiene el PPU.
     */
    public Ppu getPpu() {
        return ppu;
    }

    /**
     * Obtiene el Renderer.
     */
    public Renderer getRenderer() {
        return renderer;
    }

    /**
     * Verifica si el pipeline está inicializado.
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Reinicia el pipeline.
     */
    public void reset() {
        if (cpu != null) cpu.reset();
        if (mmu != null) mmu.reset();
        if (ppu != null) ppu.reset();
        Log.i(TAG, "Pipeline reiniciado");
    }

    /**
     * Libera recursos.
     */
    public void destroy() {
        if (renderer != null) {
            renderer.destroy();
        }
        isInitialized = false;
        Log.i(TAG, "Pipeline destruido");
    }

    // ═══════════════════════════════════════════════════════
    // CLASES INTERNAS (wrappers de C++ via JNI)
    // ═══════════════════════════════════════════════════════

    /**
     * Wrapper del CPU LR35902.
     */
    public static class Cpu {
        public native void initialize();
        public native int step();
        public native void reset();
        public native void setMmu(Mmu mmu);
        public native void setPpu(Ppu ppu);
        public native int stepAndSync();
    /**
     * Ejecuta un frame completo (70224 ciclos) en un solo salto JNI.
     * Antes el loop Java hacía ~17.5k llamadas JNI por frame y la bomba
     * corría a 4 fps; con esto un frame = un JNI y 30 fps es viable.
     */
    public native int stepFrame();

        static {
            System.loadLibrary("gpsquest96");
        }
    }

    /**
     * Wrapper de la MMU.
     */
    public static class Mmu {
        public native void initialize();
        public native void reset();
        public native int read(int address);
        /**
         * Copia {@code length} bytes desde {@code address} a {@code dst}.
         * Un solo cruce JNI; solo para rangos sin efectos de lectura
         * (VRAM 0x8000-0x9FFF, OAM 0xFE00-0xFE9F).
         */
        public native void readBlock(int address, byte[] dst, int length);
        /**
         * Copia VRAM de un banco EXPLICITO (CGB). {@code bank} 0 = tiles del
         * fondo/ventana; banco 1 = tiles y atributos de los OBJ (bit 3 del
         * atributo OAM). No toca VBK: no altera el estado que ve el juego.
         */
        public native void readVramBlock(int bank, int address, byte[] dst, int length);
        /** Copia los 64 B de la paleta OBJ CGB (8 paletas x 4 colores RGB555). */
        public native void readObjPaletteBlock(byte[] dst);
        /**
         * Copia los 160 B de OAM con los que se pintó el último frame
         * (spec 015 D-25). Análogo a un snapshot de PPU: leer 0xFE00 desde
         * fuera del frame devuelve a menudo la shadow OAM ya limpiada.
         */
        public native void readOamSnapshot(byte[] dst);
        public native void write(int address, int value);
        public native int readWord(int address);
        public native void writeWord(int address, int value);

        /**
         * Estado de botones del joypad (0 = presionado).
         * Bits: 0=A, 1=B, 2=Select, 3=Start, 4=Right, 5=Left, 6=Up, 7=Down.
         */
        public native void setJoypad(int buttons);

        static {
            System.loadLibrary("gpsquest96");
        }
    }

    /**
     * Wrapper del PPU.
     */
    public static class Ppu {
        public native void initialize();
        public native void reset();
        public native boolean step(int cycles);
        public native int[] getFrameBuffer();
        public native ByteBuffer getFrameBufferDirect();
        public native void setMmu(Mmu mmu);
        public native void setRenderer(Renderer renderer);

        static {
            System.loadLibrary("gpsquest96");
        }
    }

    /**
     * Wrapper del Renderer OpenGL ES 2.0.
     */
    public static class Renderer {
        public native void initialize(int width, int height);
        public native void renderFrame(int[] frameBuffer);
        public native void destroy();

        static {
            System.loadLibrary("gpsquest96");
        }
    }
}
