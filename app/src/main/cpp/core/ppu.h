#ifndef GPSQUEST96_PPU_H
#define GPSQUEST96_PPU_H

#include <atomic>
#include <cstdint>
#include <cstring>

namespace gpsquest96 {

/**
 * Estado del emulador (spec 016, slice 2).
 * Detectado desde registros de hardware de 8 bits genéricos (LCDC, STAT, SCX, SCY, WY, WX),
 * sin direcciones de memoria específicas de una sola ROM.
 */
enum class EmulatorState : uint8_t {
    BOOT = 0,      // Cargando ROM, antes de la intro
    INTRO = 1,     // Secuencia de arranque (logo del fabricante, intro, nombre)
    MAP = 2,       // Overworld caminando
    BATTLE = 3,    // Combate silvestre o entrenador
    MENU = 4,      // Menú START, mochila, criaturas, etc.
    PAUSE = 5      // Emulador pausado por el usuario
};

/**
 * PPU (Picture Processing Unit) â€” Procesador de grÃ¡ficos de hardware de 8 bits.
 * <p>
 * ResoluciÃ³n: 160x144 pÃ­xeles
 * Colores: 4 (2 bits por pÃ­xel)
 * Tiles: 8x8 pÃ­xeles
 * <p>
 * Modos de video:
 * - H-Blank (0): LÃ­nea visible, acceso a VRAM
 * - V-Blank (1): Intervalo vertical, acceso a VRAM
 * - OAM Search (2): BÃºsqueda de sprites
 * - Pixel Transfer (3): Transferencia de pÃ­xeles
 */
class Ppu {
public:
    Ppu();
    ~Ppu();

    /**
     * Inicializa el PPU.
     */
    void initialize();

    /**
     * Ejecuta un ciclo de PPU.
     * @param cycles Ciclos de CPU consumidos
     * @return true si se completÃ³ un frame
     */
    bool step(int cycles);

    /**
     * Obtiene el modo de video actual.
     */
    uint8_t getMode() const { return mode_; }

    /**
     * Obtiene el scanline actual.
     */
    uint8_t getScanline() const { return scanline_; }

    /**
     * Obtiene el estado actual del emulador (spec 016, slice 2).
     * Detectado desde registros de hardware genéricos, sin offsets de ROM específica.
     */
    EmulatorState getEmulatorState() const { return emulatorState_; }

    /**
     * Actualiza el estado del emulador basado en registros de hardware.
     * Se llama desde step() al entrar en VBlank o tras cambios significativos.
     */
    void updateEmulatorState();

    /**
     * ArnÃ©s de host: contador interno de la ventana (WLY). spec 011 slice 2.
     */
    uint8_t getWindowLine() const { return windowLine_; }

    /**
     * Obtiene el buffer de pÃ­xeles del frame de PRESENTACIÃ“N (completo).
     * Mantiene una direcciÃ³n de memoria fija (displayBuffer_) para DirectByteBuffer
     * que solo se actualiza en VBlank de forma atÃ³mica.
     */
    const uint32_t* getFrameBuffer() const {
        return displayBuffer_;
    }

    /**
     * ArnÃ©s de host: buffer de DIBUJO en curso (el Ãºltimo pintado sobre la
     * lÃ­nea). Los checks de PPU leen pÃ­xeles de scanlines reciÃ©n renderizadas,
     * antes del VBlank que las publicarÃ­a: ahÃ­ viven en el draw, no en el
     * present. El hilo GL/JNI NUNCA debe usar esto.
     */
    const uint32_t* getDrawFrameBuffer() const { return drawBuffer_; }

    /**
     * OAM con la que se pintÃ³ el Ãºltimo frame completo (spec 015 D-25).
     * Estable para el extractor de sprites: 160 B, y = 0 significa oculto.
     */
    const uint8_t* getOamSnapshot() const { return oamSnapshot_; }

    /**
     * Establece el puntero a la MMU.
     */
    void setMmu(class Mmu* mmu) { mmu_ = mmu; }

    /**
     * Establece el puntero al Renderer.
     */
    void setRenderer(class Renderer* renderer) { renderer_ = renderer; }

    /**
     * Obtiene el registro LCDC (0xFF40).
     */
    uint8_t getLcdc() const { return lcdc_; }

    /**
     * Establece el registro LCDC (0xFF40).
     */
    void setLcdc(uint8_t value) { lcdc_ = value; }

    /**
     * Obtiene el registro STAT (0xFF41).
     */
    uint8_t getStat() const { return stat_; }

    /**
     * Establece el registro STAT (0xFF41).
     */
    void setStat(uint8_t value) { stat_ = value; }

    /**
     * Obtiene el registro SCY (0xFF42).
     */
    uint8_t getScy() const { return scy_; }

    /**
     * Establece el registro SCY (0xFF42).
     */
    void setScy(uint8_t value) { scy_ = value; }

    /**
     * Obtiene el registro SCX (0xFF43).
     */
    uint8_t getScx() const { return scx_; }

    /**
     * Establece el registro SCX (0xFF43).
     */
    void setScx(uint8_t value) { scx_ = value; }

    /**
     * Obtiene el registro LY (0xFF44).
     */
    uint8_t getLy() const { return ly_; }

    /**
     * Obtiene el registro LYC (0xFF45).
     */
    uint8_t getLyc() const { return lyc_; }

    /**
     * Establece el registro LYC (0xFF45).
     */
    void setLyc(uint8_t value) { lyc_ = value; }

    /**
     * Obtiene la paleta de fondo (0xFF47).
     */
    uint8_t getBgPalette() const { return bgPalette_; }

    /**
     * Establece la paleta de fondo (0xFF47).
     */
    void setBgPalette(uint8_t value) { bgPalette_ = value; }

    /**
     * Obtiene la paleta de objetos 0 (0xFF48).
     */
    uint8_t getObjPalette0() const { return objPalette0_; }

    /**
     * Establece la paleta de objetos 0 (0xFF48).
     */
    void setObjPalette0(uint8_t value) { objPalette0_ = value; }

    /**
     * Obtiene la paleta de objetos 1 (0xFF49).
     */
    uint8_t getObjPalette1() const { return objPalette1_; }

    /**
     * Establece la paleta de objetos 1 (0xFF49).
     */
    void setObjPalette1(uint8_t value) { objPalette1_ = value; }

    /**
     * Obtiene el registro WY (0xFF4A).
     */
    uint8_t getWy() const { return wy_; }

    /**
     * Establece el registro WY (0xFF4A).
     */
    void setWy(uint8_t value) { wy_ = value; }

    /**
     * Obtiene el registro WX (0xFF4B).
     */
    uint8_t getWx() const { return wx_; }

    /**
     * Establece el registro WX (0xFF4B).
     */
    void setWx(uint8_t value) { wx_ = value; }

private:
    // Registros
    uint8_t lcdc_;        // 0xFF40 - LCD Control
    uint8_t stat_;        // 0xFF41 - LCD Status
    uint8_t scy_;         // 0xFF42 - Scroll Y
    uint8_t scx_;         // 0xFF43 - Scroll X
    uint8_t ly_;          // 0xFF44 - Scanline actual
    uint8_t lyc_;         // 0xFF45 - Scanline de comparaciÃ³n
    uint8_t bgPalette_;   // 0xFF47 - Paleta de fondo
    uint8_t objPalette0_; // 0xFF48 - Paleta de objetos 0
    uint8_t objPalette1_; // 0xFF49 - Paleta de objetos 1
    uint8_t wy_;          // 0xFF4A - Window Y
    uint8_t wx_;          // 0xFF4B - Window X

    // Estado
    uint8_t mode_;        // Modo de video actual
    int cycleCounter_;    // Contador de ciclos
    uint8_t scanline_;    // Scanline actual
    // Contador interno de lÃ­neas de la ventana: avanza solo en scanlines
    // donde la ventana es visible (ly >= wy); se reinicia al inicio del frame.
    // La ventana no hace scroll: su fila se toma de este contador, no de ly.
    uint8_t windowLine_;
    // Ãšltimo resultado de LYC==LY. Sirve para disparar la IRQ de STAT solo
    // en el flanco (una vez por lÃ­nea), no en cada sync de modo.
    bool lycMatch_;

    // Estado del emulador (spec 016, slice 2)
    EmulatorState emulatorState_ = EmulatorState::BOOT;

    // Anillo de doble bÃºfer (spec 011 RF-01): la PPU pinta solo en el slot
    // apuntado por drawBuffer_/drawIndex_; el hilo GL (uploadPpuTexture via
    // JNI) lee el slot marcado por presentIndex_. El swap ocurre SOLO en
    // VBlank (frame completo). Tres slots fijos: con dos, el slot reciclado
    // podrÃ­a seguir subiÃ©ndose por el hilo GL en la microventana previa al
    // redibujo (~1 ms); con tres, cada slot queda >=2 frames sin escribir
    // tras presentarse y la carrera desaparece sin locks. Cero allocations
    // por frame (por fuera del keep copy-cero del swap de punteros).
    uint32_t frameBuffers_[3][160 * 144];
    uint32_t displayBuffer_[160 * 144];
    // Ãndice del slot de presentaciÃ³n (leÃ­do por el hilo GL via JNI).
    std::atomic<int> presentIndex_;
    // Ãndice del slot de dibujo (solo hilo de emulaciÃ³n).
    int drawIndex_;
    // Copia de la OAM usada para pintar el frame, tomada al entrar en VBlank
    // (spec 015 D-25). Leer 0xFE00 desde fuera del frame cae en la ventana en
    // que el juego ya limpiÃ³ su shadow OAM: devolvÃ­a 160 ceros y el extractor
    // no encontraba al jugador.
    uint8_t oamSnapshot_[160];
    // Puntero al slot en el que se estÃ¡ pintando (frame en curso).
    uint32_t* drawBuffer_;

    // CGB: Ã­ndice de color y prioridad del BG por pÃ­xel de la scanline actual.
    // Los sprites los consultan para decidir si quedan detrÃ¡s del BG.
    uint8_t bgColorIndex_[160];
    uint8_t bgPriority_[160];

    // Punteros
    class Mmu* mmu_;
    class Renderer* renderer_;

    // MÃ©todos de renderizado
    void fillTestPattern();
    void renderScanline();
    void renderBackground();
    void renderWindow();
    void renderSprites();
    void drawTile(uint16_t tileAddr, int x, int y, bool flipX, bool flipY, uint8_t palette);
    void drawTilePixel(uint16_t tileAddr, int px, int py, int rowInTile,
                       int colInTile, uint8_t palette);
    /**
     * PÃ­xel de BG/ventana en CGB: lee el Ã­ndice del tile del banco 0 y el
     * atributo del banco 1 (paleta, flips, banco de tile, prioridad).
     */
    void drawCgbBgPixel(uint16_t tileMapAddr, int tileOffset, int px, int py,
                        int rowInTile, int colInTile);
    uint32_t getColor(uint8_t colorIndex, uint8_t palette);
    /** RGB15 de las paletas CGB (BG u OBJ) expandido a RGBA. */
    uint32_t getCgbColor(uint8_t colorIndex, uint8_t paletteIndex, bool obj);

    // Utilidades
    uint8_t readVram(uint16_t address);
    uint8_t readVramBank(uint8_t bank, uint16_t address);
    void writeVram(uint16_t address, uint8_t value);
    // Sincroniza LY/STAT hacia la MMU (el juego los lee por LDH).
    void syncRegsToMmu();
    // IRQ de STAT por entrada en modo 0/1/2, segÃºn los bits 3-5 de STAT.
    void requestStatModeIrq();
};

} // namespace gpsquest96

#endif // GPSQUEST96_PPU_H


