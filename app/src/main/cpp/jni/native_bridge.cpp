#include "jni/native_bridge.h"
#include "core/rom_loader.h"
#include "core/cpu.h"
#include "core/mmu.h"
#include "core/ppu.h"
#include "core/wild_table.h"
#include "renderer/renderer.h"

#include <android/log.h>
#include <cstdio>
#include <unistd.h>
#include <cstring>

#define LOG_TAG "GPSQuest96"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Huella del Ã¡rbol compilado: se imprime en cada nativeOpenRom para saber
// en POCO EXACTAMENTE quÃ© cÃ³digo corre (fin de los "APK rancio" ambiguos).
// Formato: fecha + slices incluidos. Actualizar al tocar el core.
#define BUILD_TAG "2026-11-08+spec004-mapa"

// Instancia global del cargador (simplificado para esta fase)
static gpsquest96::RomLoader g_romLoader;

// Cabeceras GBA/GBC pueden traer bytes crudos no UTF-8: nunca pasarlos
// directos a NewStringUTF (crashea ART). Solo ASCII imprimible, resto '?'.
static jstring newSanitizedString(JNIEnv* env, const char* data, size_t maxLen) {
    char clean[32];
    size_t n = 0;
    for (size_t i = 0; i < maxLen && n < sizeof(clean) - 1; i++) {
        unsigned char c = static_cast<unsigned char>(data[i]);
        if (c == 0) break;
        clean[n++] = (c >= 0x20 && c <= 0x7E) ? static_cast<char>(c) : '?';
    }
    clean[n] = '\0';
    return env->NewStringUTF(clean);
}

// Instancias globales del pipeline de emulaciÃ³n
static gpsquest96::Cpu* g_cpu = nullptr;
static gpsquest96::Mmu* g_mmu = nullptr;
static gpsquest96::Ppu* g_ppu = nullptr;
static gpsquest96::Renderer* g_renderer = nullptr;
static gpsquest96::WildTableReader g_wildTable;

// Todas las definiciones JNI con linkage C: sin esto el compilador C++
// mangled los sÃ­mbolos y Dalvik/ART no los encuentra (UnsatisfiedLinkError).
extern "C" {

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeOpenRom(
        JNIEnv* env,
        jobject /* thiz */,
        jstring romPath) {

    if (romPath == nullptr) {
        LOGE("Ruta de ROM nula");
        return -1;
    }

    const char* path = env->GetStringUTFChars(romPath, nullptr);
    if (path == nullptr) {
        LOGE("Error obteniendo ruta de ROM");
        return -2;
    }

    bool success = g_romLoader.open(path);
    env->ReleaseStringUTFChars(romPath, path);

    if (success) {
        // La ROM cambiÃ³: invalidar la cachÃ© de tablas de encuentro.
        g_wildTable.clear();
    }

    LOGI("BUILD %s", BUILD_TAG);

    if (success && g_mmu != nullptr) {
        g_mmu->initialize();
        g_mmu->setRomLoader(&g_romLoader);
        // Modo CGB segÃºn cabecera 0x143. Debe fijarse ANTES de initialize()
        // de la CPU: el arranque CGB deja A=0x11 y el DMG A=0x01.
        const bool cgb = g_romLoader.isCgbCapable();
        g_mmu->setCgbMode(cgb);
        LOGI("CGB %s (flag 0x%02X%s)", cgb ? "ON" : "OFF",
             g_romLoader.cgbFlag(), g_romLoader.isCgbOnly() ? ", only" : "");
        // Boot limpio al cambiar de ROM: sin esto la CPU seguÃ­a con el PC
        // del juego anterior y la PPU con sus frames (pantalla sin cambio).
        if (g_cpu != nullptr) {
            // Enlazar antes de initialize(): initialize() decide A=0x11/0x01
            // segÃºn el modo CGB ya fijado en la MMU.
            g_cpu->setMmu(g_mmu);
            if (g_ppu != nullptr) {
                g_cpu->setPpu(g_ppu);
            }
            g_cpu->initialize(); // PC=0x0100, registros/flags a cero
        }
        if (g_ppu != nullptr) {
            g_ppu->initialize(); // framebuffer limpio para el juego nuevo
        }
        if (cgb && g_mmu != nullptr) {
            // Estado de handoff del boot ROM de CGB: el LCD queda encendido.
            // Crystal (y otros CGB-only) esperan a que LY llegue a 0x91 antes
            // de apagar el LCD para el cambio de velocidad; si arrancamos con
            // LCDC=0, LY nunca avanza y el boot se queda en 0x01A2-0x01A6.
            g_mmu->write(0xFF40, 0x91); // LCDC: LCD on, BG on, sprites 8x8
            g_mmu->write(0xFF47, 0xFC); // BGP
            g_mmu->write(0xFF48, 0xFF); // OBP0
            g_mmu->write(0xFF49, 0xFF); // OBP1
        }
        gpsquest96::RomHeader header;
        if (g_romLoader.readHeader(header)) {
            uint8_t b0100 = g_mmu->read(0x0100);
            uint8_t b0104 = g_mmu->read(0x0104);
            uint8_t b4000 = g_mmu->read(0x4000);
            LOGI("ROM Header OK: %s | MMU Read [0x0104]=0x%02X (b0100=0x%02X) [0x4000]=0x%02X",
                 header.title, b0104, b0100, b4000);
        }
    }

    return success ? 0 : -3;
}

JNIEXPORT jstring JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetGameCode(
        JNIEnv* env,
        jobject /* thiz */) {

    if (!g_romLoader.isOpen()) {
        return nullptr;
    }

    gpsquest96::RomHeader header;
    if (!g_romLoader.readHeader(header)) {
        return nullptr;
    }

    return newSanitizedString(env, header.gameCode, 4);
}

JNIEXPORT jstring JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetTitle(
        JNIEnv* env,
        jobject /* thiz */) {

    if (!g_romLoader.isOpen()) {
        return nullptr;
    }

    gpsquest96::RomHeader header;
    if (!g_romLoader.readHeader(header)) {
        return nullptr;
    }

    return newSanitizedString(env, header.title, 16);
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetCartridgeType(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (!g_romLoader.isOpen()) {
        return -1;
    }

    gpsquest96::RomHeader header;
    if (!g_romLoader.readHeader(header)) {
        return -1;
    }

    return static_cast<jint>(header.cartridgeType);
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetRomSize(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (!g_romLoader.isOpen()) {
        return -1;
    }

    gpsquest96::RomHeader header;
    if (!g_romLoader.readHeader(header)) {
        return -1;
    }

    return static_cast<jint>(header.romSize);
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeFlushSram(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_mmu != nullptr) {
        g_mmu->flushCartridge();
    }
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeSramSize(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_mmu == nullptr) {
        return 0;
    }
    return static_cast<jint>(g_mmu->sramSize());
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeReadBgPalette(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint index) {

    if (g_mmu == nullptr || index < 0 || index >= 64) {
        return 0;
    }
    return static_cast<jint>(g_mmu->readBgPalette(static_cast<uint8_t>(index)));
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeReadObjPalette(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint index) {

    if (g_mmu == nullptr || index < 0 || index >= 64) {
        return 0;
    }
    return static_cast<jint>(g_mmu->readObjPalette(static_cast<uint8_t>(index)));
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeReadVramBank(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint bank,
        jint address) {

    if (g_mmu == nullptr || address < 0x8000 || address > 0x9FFF) {
        return 0;
    }
    return static_cast<jint>(g_mmu->readVramBank(static_cast<uint8_t>(bank), static_cast<uint16_t>(address)));
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeReadAudioSamples(
        JNIEnv* env,
        jobject /* thiz */,
        jshortArray dst) {

    if (g_mmu == nullptr || dst == nullptr) {
        return 0;
    }
    jsize cap = env->GetArrayLength(dst);
    if (cap <= 0) return 0;

    jshort* p = env->GetShortArrayElements(dst, nullptr);
    if (p == nullptr) return 0;

    int count = g_mmu->getApu().readSamples(reinterpret_cast<int16_t*>(p), cap);
    env->ReleaseShortArrayElements(dst, p, 0);

    return count;
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeReadSramRaw(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint offset) {

    if (g_mmu == nullptr || offset < 0) {
        return 0xFF;
    }
    return static_cast<jint>(g_mmu->readSramRaw(static_cast<uint32_t>(offset)));
}

/**
 * Tabla de encuentro del mapa actual (spec 009, slice 1: solo lectura).
 *
 * Formato del int[] devuelto:
 *   [0] kind (1 grass, 2 water)
 *   [1] group
 *   [2] map
 *   [3] ratesCount (3 grass, 1 water)
 *   [4] totalPairs (21 grass, 3 water)
 *   [5] offset de la tabla en la ROM
 *   [6] offset de la entrada de este mapa
 *   [7] entradas de la tabla que la contiene
 *   [8..] tasas, luego pares nivel/especie por momento
 *
 * kind: 0 = grass y, si no hay, water; 1 = solo grass; 2 = solo water.
 * Devuelve null si la ROM no tiene tabla para ese mapa.
 */
JNIEXPORT jintArray JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetWildTable(
        JNIEnv* env,
        jobject /* thiz */,
        jint group,
        jint map,
        jint kind) {

    if (group < 1 || group > 26 || map < 1 || map > 255) {
        return nullptr;
    }
    if (!g_romLoader.isOpen() || !g_wildTable.load(g_romLoader)) {
        return nullptr;
    }

    gpsquest96::WildTableKind preferred = gpsquest96::WildTableKind::NONE;
    if (kind == 1) {
        preferred = gpsquest96::WildTableKind::GRASS;
    } else if (kind == 2) {
        preferred = gpsquest96::WildTableKind::WATER;
    } else if (kind != 0) {
        return nullptr;
    }

    gpsquest96::WildTable table;
    if (!g_wildTable.lookup(static_cast<uint8_t>(group),
                            static_cast<uint8_t>(map), preferred, table)) {
        LOGI("B2 mapa %d/%d: sin tabla", group, map);
        return nullptr;
    }

    const bool grass = (table.kind == gpsquest96::WildTableKind::GRASS);
    const int periods = grass ? 3 : 1;
    const int ratesCount = periods;
    const int slots = table.slotsPerPeriod;
    const int totalPairs = periods * slots;
    const int header = 8;

    std::vector<jint> values;
    values.reserve(static_cast<size_t>(header + ratesCount + totalPairs * 2));
    values.push_back(grass ? 1 : 2);
    values.push_back(table.group);
    values.push_back(table.map);
    values.push_back(ratesCount);
    values.push_back(totalPairs);
    values.push_back(static_cast<jint>(table.romOffset));
    values.push_back(static_cast<jint>(table.entryOffset));
    values.push_back(static_cast<jint>(table.tableEntryCount));
    for (int i = 0; i < ratesCount; ++i) {
        values.push_back(table.rates[i]);
    }
    for (int period = 0; period < periods; ++period) {
        for (int slot = 0; slot < slots; ++slot) {
            values.push_back(table.levels[period][slot]);
            values.push_back(table.species[period][slot]);
        }
    }

    LOGI("B2 mapa %d/%d: %s tabla=0x%X entrada=0x%X n=%u tasas=%u/%u/%u",
         group, map, grass ? "grass" : "water", table.romOffset,
         table.entryOffset, table.tableEntryCount, table.rates[0],
         table.rates[1], table.rates[2]);

    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()),
                           values.data());
    return result;
}

/**
 * Mapa actual del jugador (spec 009, slice 2: solo lectura).
 *
 * Formato del int[] devuelto:
 *   [0] wMapGroup, [1] wMapNumber, [2] wYCoord, [3] wXCoord
 *
 * Las coordenadas son solo diagnï¿½ï¿½stico: el spawn depende del mapa, no del
 * tile. Si no hay ROM/MMU o el grupo/mapa no es un tramo vï¿½ï¿½lido, devuelve
 * null y no se consulta ninguna tabla.
 */
JNIEXPORT jintArray JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetCurrentMap(
        JNIEnv* env,
        jobject /* thiz */) {

    if (g_mmu == nullptr || !g_romLoader.isOpen()) {
        return nullptr;
    }

    const int group = g_mmu->read(0xDCB5);
    const int map = g_mmu->read(0xDCB6);
    const int y = g_mmu->read(0xDCB7);
    const int x = g_mmu->read(0xDCB8);

    // Mismos lï¿½ï¿½mites que nativeGetWildTable: grupo 1..26, mapa 1..255.
    if (group < 1 || group > 26 || map < 1 || map > 255) {
        LOGI("B2 mapa actual invalido: %d/%d (y=%d x=%d)", group, map, y, x);
        return nullptr;
    }

    jint values[4] = {group, map, y, x};
    jintArray result = env->NewIntArray(4);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 4, values);
    LOGI("B2 mapa actual %d/%d (y=%d x=%d)", group, map, y, x);
    return result;
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeCloseRom(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_mmu != nullptr) {
        g_mmu->flushCartridge();
        g_mmu->setRomLoader(nullptr);
        g_mmu->setCgbMode(false);
    }
    g_wildTable.clear();
    g_romLoader.close();
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// PIPELINE DE EMULACIÃ“N (CPU -> MMU -> PPU -> Renderer)
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// CPU LR35902
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_initialize(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_cpu == nullptr) {
        g_cpu = new gpsquest96::Cpu();
    }
    g_cpu->initialize();
    LOGI("CPU inicializado");
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_step(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_cpu == nullptr) {
        return 0;
    }
    return g_cpu->step();
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_reset(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_cpu != nullptr) {
        g_cpu->initialize();
    }
    LOGI("CPU reiniciado");
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_setMmu(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jobject /* mmu */) {

    // La conexiÃ³n CPU-MMU se maneja internamente en C++
    if (g_cpu != nullptr && g_mmu != nullptr) {
        g_cpu->setMmu(g_mmu);
    }
}

static void ensureCpuPipelineLinked() {
    if (g_cpu != nullptr && g_mmu != nullptr) g_cpu->setMmu(g_mmu);
    if (g_cpu != nullptr && g_ppu != nullptr) g_cpu->setPpu(g_ppu);
    if (g_ppu != nullptr && g_mmu != nullptr) g_ppu->setMmu(g_mmu);
    if (g_ppu != nullptr && g_renderer != nullptr) g_ppu->setRenderer(g_renderer);
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_setPpu(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jobject /* ppu */) {

    if (g_cpu != nullptr && g_ppu != nullptr) {
        g_cpu->setPpu(g_ppu);
    }
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_stepAndSync(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_cpu == nullptr) {
        return 0;
    }
    ensureCpuPipelineLinked();
    return g_cpu->stepAndSync();
}

// DIAGNÃ“STICO TEMPORAL (spec 006, slice 3): vuelca frame RGBA, VRAM, OAM y
// registros de la PPU a filesDir para analizarlos fuera del POCO.
// Se retira al cerrar el slice.
static void dumpPpuState(int index) {
    if (g_ppu == nullptr || g_mmu == nullptr) return;
    char path[256];

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_frame.rgba", index);
    if (FILE* f = fopen(path, "wb")) {
        fwrite(g_ppu->getFrameBuffer(), 4, 160 * 144, f);
        fclose(f);
    }

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_vram.bin", index);
    if (FILE* f = fopen(path, "wb")) {
        for (int i = 0; i < 0x2000; i++) {
            fputc(g_mmu->readVramBank(0, static_cast<uint16_t>(0x8000 + i)), f);
        }
        fclose(f);
    }

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_vram1.bin", index);
    if (FILE* f = fopen(path, "wb")) {
        for (int i = 0; i < 0x2000; i++) {
            fputc(g_mmu->readVramBank(1, static_cast<uint16_t>(0x8000 + i)), f);
        }
        fclose(f);
    }

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_bgpal.bin", index);
    if (FILE* f = fopen(path, "wb")) {
        for (int i = 0; i < 64; i++) fputc(g_mmu->readBgPalette(static_cast<uint8_t>(i)), f);
        fclose(f);
    }

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_objpal.bin", index);
    if (FILE* f = fopen(path, "wb")) {
        for (int i = 0; i < 64; i++) fputc(g_mmu->readObjPalette(static_cast<uint8_t>(i)), f);
        fclose(f);
    }

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_oam.bin", index);
    if (FILE* f = fopen(path, "wb")) {
        for (int i = 0; i < 160; i++) {
            uint8_t b = g_mmu->read(static_cast<uint16_t>(0xFE00 + i));
            fwrite(&b, 1, 1, f);
        }
        fclose(f);
    }

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_wram.bin", index);
    if (FILE* f = fopen(path, "wb")) {
        for (int i = 0; i < 0x2000; i++) {
            uint8_t b = g_mmu->read(static_cast<uint16_t>(0xC000 + i));
            fwrite(&b, 1, 1, f);
        }
        fclose(f);
    }

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_hram.bin", index);
    if (FILE* f = fopen(path, "wb")) {
        for (int i = 0; i < 0x7F; i++) {
            uint8_t b = g_mmu->read(static_cast<uint16_t>(0xFF80 + i));
            fwrite(&b, 1, 1, f);
        }
        fclose(f);
    }

    snprintf(path, sizeof(path), "/data/user/0/com.gpsquest96/files/dbg_%02d_regs.txt", index);
    if (FILE* f = fopen(path, "wb")) {
        fprintf(f,
                "LCDC=0x%02X STAT=0x%02X SCY=0x%02X SCX=0x%02X LY=0x%02X LYC=0x%02X\n"
                "BGP=0x%02X OBP0=0x%02X OBP1=0x%02X WY=0x%02X WX=0x%02X\n"
                "KEY1=0x%02X VBK=0x%02X SVBK=0x%02X IF=0x%02X IE=0x%02X\n",
                g_mmu->read(0xFF40), g_mmu->read(0xFF41), g_mmu->read(0xFF42),
                g_mmu->read(0xFF43), g_mmu->read(0xFF44), g_mmu->read(0xFF45),
                g_mmu->read(0xFF47), g_mmu->read(0xFF48), g_mmu->read(0xFF49),
                g_mmu->read(0xFF4A), g_mmu->read(0xFF4B),
                g_mmu->read(0xFF4D), g_mmu->read(0xFF4F), g_mmu->read(0xFF70),
                g_mmu->read(0xFF0F), g_mmu->read(0xFFFF));
        if (g_cpu != nullptr) {
            fprintf(f,
                    "PC=0x%04X SP=0x%04X AF=0x%04X BC=0x%04X DE=0x%04X HL=0x%04X halted=%d\n",
                    g_cpu->getPC(), g_cpu->getSP(), g_cpu->getAF(),
                    g_cpu->getBC(), g_cpu->getDE(), g_cpu->getHL(),
                    g_cpu->isHalted() ? 1 : 0);
        }
        fclose(f);
    }
    LOGI("DUMP PPU %d escrito", index);
}

// Un frame completo GB (70224 ciclos) en un solo salto JNI: el loop Java
// hacÃ­a ~17.5k llamadas por frame y la bomba corrÃ­a a 4 fps; con esto un
// frame = un JNI y 30 fps es viable (adiÃ³s al titileo PPU vs refresco GL).
JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_stepFrame(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_cpu == nullptr) {
        return 0;
    }
    ensureCpuPipelineLinked();
    int cycles = 0;
    // Un frame de PPU son 70224 ciclos de CPU; en doble velocidad la CPU
    // necesita el doble de M-cycles para que la PPU complete el mismo frame.
    const int maxCycles = (g_mmu != nullptr && g_mmu->isDoubleSpeed())
                          ? 140448 : 70224;
    while (cycles < maxCycles) {
        int c = g_cpu->stepAndSync();
        if (c <= 0) break;
        cycles += c;
    }

    // MÃ©trica honesta de velocidad (spec 006, RF-01): una lÃ­nea cada 600 frames
    // con el acumulado. Comparando dos lÃ­neas con la hora de logcat sale
    // ciclos/s reales sin instrumentar el core ni frenar la emulaciÃ³n.
    {
        static long long emuFrames = 0;
        static long long emuCycles = 0;
        emuFrames++;
        emuCycles += cycles;
        if (emuFrames % 600 == 0) {
            LOGI("EMU %lld frames, %lld cycles", emuFrames, emuCycles);
        }
        // DIAGNÃ“STICO TEMPORAL (spec 006, slice 3): volcado a demanda. Se
        // dispara creando files/dump_now y captura frame+VRAM+OAM+registros
        // del mismo instante. Se retira al cerrar el slice.
        if (emuFrames % 30 == 0) {
            const char* flag = "/data/user/0/com.gpsquest96/files/dump_now";
            if (access(flag, F_OK) == 0) {
                unlink(flag);
                dumpPpuState(99);
            }
        }
    }

    return cycles;
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// MMU
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_initialize(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_mmu == nullptr) {
        g_mmu = new gpsquest96::Mmu();
    }
    g_mmu->initialize();
    // Si la ROM del usuario ya estaba abierta, enlazarla (orden inverso posible)
    // y recuperar el modo CGB: initialize() lo deja en DMG por diseÃ±o.
    if (g_romLoader.isOpen()) {
        g_mmu->setRomLoader(&g_romLoader);
        g_mmu->setCgbMode(g_romLoader.isCgbCapable());
    }
    LOGI("MMU inicializada");
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_reset(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_mmu != nullptr) {
        g_mmu->initialize();
    }
    LOGI("MMU reiniciada");
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_read(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint address) {

    if (g_mmu == nullptr) {
        return 0;
    }
    return g_mmu->read(static_cast<uint16_t>(address));
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_readBlock(
        JNIEnv* env,
        jobject /* thiz */,
        jint address,
        jbyteArray dst,
        jint length) {

    if (g_mmu == nullptr || dst == nullptr || length <= 0) {
        return;
    }
    jsize cap = env->GetArrayLength(dst);
    if (length > cap) {
        length = cap;
    }
    // Escritura directa en el array: 1 cruce JNI en vez de N.
    // Solo para rangos sin efectos de lectura (VRAM 0x8000-0x9FFF, OAM 0xFE00-0xFE9F).
    jbyte* p = env->GetByteArrayElements(dst, nullptr);
    if (p == nullptr) {
        return;
    }
    for (jint i = 0; i < length; i++) {
        p[i] = static_cast<jbyte>(
                g_mmu->read(static_cast<uint16_t>((address + i) & 0xFFFF)));
    }
    env->ReleaseByteArrayElements(dst, p, 0);
}

/*
 * Lectura de VRAM de un banco EXPLICITO (CGB: 0 = tiles, 1 = tiles/atributos).
 * g_mmu->read() depende de VBK (lo cambia el juego), asÃ­ que copiar VRAM con
 * readBlock() devolvÃ­a un banco impredecible: el extractor de sprites leÃ­a el
 * banco 0 (fondo/fuentes) cuando el OBJ estaba en el banco 1 y componÃ­a un
 * bloque de glifos en vez del jugador (auditorÃ­a POCO 2026-10-30).
 */
JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_readVramBlock(
        JNIEnv* env,
        jobject /* thiz */,
        jint bank,
        jint address,
        jbyteArray dst,
        jint length) {

    if (g_mmu == nullptr || dst == nullptr || length <= 0) {
        return;
    }
    jsize cap = env->GetArrayLength(dst);
    if (length > cap) {
        length = cap;
    }
    const uint8_t b = static_cast<uint8_t>(bank & 0x01);
    jbyte* p = env->GetByteArrayElements(dst, nullptr);
    if (p == nullptr) {
        return;
    }
    for (jint i = 0; i < length; i++) {
        p[i] = static_cast<jbyte>(
                g_mmu->readVramBank(b, static_cast<uint16_t>((address + i) & 0xFFFF)));
    }
    env->ReleaseByteArrayElements(dst, p, 0);
}

/* Paleta OBJ CGB (8 x 4 colores RGB555, 64 bytes) en un solo cruce JNI. */
JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_readObjPaletteBlock(
        JNIEnv* env,
        jobject /* thiz */,
        jbyteArray dst) {

    if (g_mmu == nullptr || dst == nullptr) {
        return;
    }
    jsize len = env->GetArrayLength(dst);
    if (len > 64) {
        len = 64;
    }
    jbyte* p = env->GetByteArrayElements(dst, nullptr);
    if (p == nullptr) {
        return;
    }
    for (jsize i = 0; i < len; i++) {
        p[i] = static_cast<jbyte>(g_mmu->readObjPalette(static_cast<uint8_t>(i)));
    }
    env->ReleaseByteArrayElements(dst, p, 0);
}

/* spec 015 D-25: OAM del Ãºltimo frame completo (160 B).
 * Leer 0xFE00 desde el hilo GL cae en la ventana en que la ROM ya limpiÃ³ su
 * shadow OAM; el snapshot del PPU se toma al entrar en VBlank. */
JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_readOamSnapshot(
        JNIEnv* env,
        jobject /* thiz */,
        jbyteArray dst) {

    if (g_ppu == nullptr || dst == nullptr) {
        return;
    }
    jsize len = env->GetArrayLength(dst);
    if (len > 160) {
        len = 160;
    }
    jbyte* p = env->GetByteArrayElements(dst, nullptr);
    if (p == nullptr) {
        return;
    }
    const uint8_t* oam = g_ppu->getOamSnapshot();
    for (jsize i = 0; i < len; i++) {
        p[i] = static_cast<jbyte>(oam[i]);
    }
    env->ReleaseByteArrayElements(dst, p, 0);
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_write(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint address,
        jint value) {

    if (g_mmu != nullptr) {
        g_mmu->write(static_cast<uint16_t>(address), static_cast<uint8_t>(value));
    }
}

JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_readWord(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint address) {

    if (g_mmu == nullptr) {
        return 0;
    }
    return g_mmu->readWord(static_cast<uint16_t>(address));
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_writeWord(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint address,
        jint value) {

    if (g_mmu != nullptr) {
        g_mmu->writeWord(static_cast<uint16_t>(address), static_cast<uint16_t>(value));
    }
}

// Estado de botones del joypad (0 = presionado). Alimenta el protocolo P1
// de 0xFF00 para que la ROM lea la cruceta/botones que pulsamos desde UI.
JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_setJoypad(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint buttons) {

    if (g_mmu != nullptr) {
        g_mmu->setJoypad(static_cast<uint8_t>(buttons));
    }
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// PPU
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Ppu_initialize(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_ppu == nullptr) {
        g_ppu = new gpsquest96::Ppu();
    }
    g_ppu->initialize();
    LOGI("PPU inicializado");
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Ppu_reset(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_ppu != nullptr) {
        g_ppu->initialize();
    }
    LOGI("PPU reiniciado");
}

JNIEXPORT jboolean JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Ppu_step(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint cycles) {

    if (g_ppu == nullptr) {
        return JNI_FALSE;
    }
    return g_ppu->step(cycles) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Ppu_getFrameBuffer(
        JNIEnv* env,
        jobject /* thiz */) {

    if (g_ppu == nullptr) {
        return nullptr;
    }

    const uint32_t* frameBuffer = g_ppu->getFrameBuffer();
    jintArray result = env->NewIntArray(160 * 144);
    env->SetIntArrayRegion(result, 0, 160 * 144, reinterpret_cast<const jint*>(frameBuffer));
    return result;
}

JNIEXPORT jobject JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Ppu_getFrameBufferDirect(
        JNIEnv* env,
        jobject /* thiz */) {

    if (g_ppu == nullptr) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(const_cast<uint32_t*>(g_ppu->getFrameBuffer()),
            160 * 144 * 4);
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Ppu_setMmu(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jobject /* mmu */) {

    // La conexiÃ³n PPU-MMU se maneja internamente en C++
    if (g_ppu != nullptr && g_mmu != nullptr) {
        g_ppu->setMmu(g_mmu);
    }
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Ppu_setRenderer(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jobject /* renderer */) {

    // La conexiÃ³n PPU-Renderer se maneja internamente en C++
    if (g_ppu != nullptr && g_renderer != nullptr) {
        g_ppu->setRenderer(g_renderer);
    }
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// RENDERER
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Renderer_initialize(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint width,
        jint height) {

    if (g_renderer == nullptr) {
        g_renderer = new gpsquest96::Renderer();
    }
    g_renderer->initialize(width, height);
    LOGI("Renderer inicializado: %dx%d", width, height);
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Renderer_renderFrame(
        JNIEnv* env,
        jobject /* thiz */,
        jintArray frameBuffer) {

    if (g_renderer == nullptr) {
        return;
    }

    jint* pixels = env->GetIntArrayElements(frameBuffer, nullptr);
    g_renderer->renderFrame(reinterpret_cast<uint32_t*>(pixels));
    env->ReleaseIntArrayElements(frameBuffer, pixels, 0);
}

JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Renderer_destroy(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    if (g_renderer != nullptr) {
        g_renderer->destroy();
        delete g_renderer;
        g_renderer = nullptr;
    }
    LOGI("Renderer destruido");
}

} // extern "C"



