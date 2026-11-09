#ifndef GPSQUEST96_NATIVE_BRIDGE_H
#define GPSQUEST96_NATIVE_BRIDGE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Abre una ROM en modo streaming y lee su cabecera.
 * Retorna un cÃ³digo de estado (0 = Ã©xito, negativo = error).
 */
JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeOpenRom(
        JNIEnv* env,
        jobject thiz,
        jstring romPath);

/**
 * Obtiene el Game Code (identificador de 4 bytes) de la ROM cargada.
 * Retorna un string con el cÃ³digo o nullptr si no hay ROM cargada.
 */
JNIEXPORT jstring JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetGameCode(
        JNIEnv* env,
        jobject thiz);

/**
 * Obtiene el tÃ­tulo del juego (16 bytes desde 0x134).
 */
JNIEXPORT jstring JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetTitle(
        JNIEnv* env,
        jobject thiz);

/**
 * Obtiene el tipo de cartucho (byte en 0x147).
 */
JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetCartridgeType(
        JNIEnv* env,
        jobject thiz);

/**
 * Obtiene el tamaÃ±o de la ROM (byte en 0x148).
 */
JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetRomSize(
        JNIEnv* env,
        jobject thiz);

/**
 * Tabla de encuentro de Gen 2 para un mapa (spec 009, solo lectura).
 * kind: 0 auto, 1 grass, 2 water. Devuelve int[] o null.
 */
JNIEXPORT jintArray JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetWildTable(
        JNIEnv* env,
        jobject thiz,
        jint group,
        jint map,
        jint kind);

/**
 * Estado actual del emulador (spec 016, slice 3).
 * Devuelve el valor de EmulatorState (BOOT=0, INTRO=1, MAP=2, BATTLE=3,
 * MENU=4, PAUSE=5) o -1 si el PPU aun no existe.
 */
JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeGetEmulatorState(
        JNIEnv* env,
        jobject thiz);

/**
 * Cierra la ROM y libera recursos.
 */
JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_EmulatorBridge_nativeCloseRom(
        JNIEnv* env,
        jobject thiz);

/**
 * Inyecta el PPU global en la CPU (pipeline CPU -> PPU).
 */
JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_setPpu(
        JNIEnv* env,
        jobject thiz,
        jobject ppu);

/**
 * Ejecuta un ciclo CPU sincronizado con la PPU.
 * @return ciclos consumidos
 */
JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_stepAndSync(
        JNIEnv* env,
        jobject thiz);

/**
 * Ejecuta un frame completo GB (70224 ciclos) en un solo salto JNI.
 * @return ciclos consumidos
 */
JNIEXPORT jint JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Cpu_stepFrame(
        JNIEnv* env,
        jobject thiz);

/**
 * Establece el estado de botones del joypad (0 = presionado).
 * Bits: 0=A, 1=B, 2=Select, 3=Start, 4=Right, 5=Left, 6=Up, 7=Down.
 */
JNIEXPORT void JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Mmu_setJoypad(
        JNIEnv* env,
        jobject thiz,
        jint buttons);

/**
 * Devuelve el frameBuffer_ de la PPU como ByteBuffer directo (160x144 RGBA,
 * 92.160 B, cero copias). VÃ¡lido mientras viva la instancia nativa.
 */
JNIEXPORT jobject JNICALL
Java_com_gpsquest96_emulator_core_EmulationPipeline_00024Ppu_getFrameBufferDirect(
        JNIEnv* env,
        jobject thiz);

#ifdef __cplusplus
}
#endif

#endif // GPSQUEST96_NATIVE_BRIDGE_H

