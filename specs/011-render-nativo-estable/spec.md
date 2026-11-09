# spec.md — 011 Render nativo estable (doble búfer + HDMA HBlank + ventana)

Estado: **APROBADO**.
Ejecución: Slices 1, 2 y 3 HECHOS y verificados.

## 1. Visión y propósito

El bucle central del producto exige desplazarse en el mundo real y ver la pantalla estable.
Esta spec garantiza un renderizado **coherente y fiel al hardware del motor retro de 8 bits**, sin tocar el producto ni el contenido del usuario.

Anti-objetivos (explícitos): no modificar el audio APU ni cambiar permisos del sistema operativo.

## 2. Evidencia que motiva la spec

| Fallo reportado | Causa en código | Archivo |
|---|---|---|
| Titileo al caminar | **Un solo `frameBuffer_[160*144]` compartido.** El hilo de emulación escribe scanlines mientras el hilo GL sube ese mismo array. Sin doble búfer ni sincronía: fotogramas a medio pintar | `ppu.h`, `MainActivity.java` |
| Distorsión en batallas/menús a color | **HDMA síncrono instantáneo:** `startHdma()` copia todo el bloque en un solo golpe (hardware: 16 B por HBlank). La lógica del juego no ve la transferencia real | `mmu.cpp` |
| Menús con líneas desfasadas | **`windowLine_` se reiniciaba solo al fin de frame** (`LY>=154`). Hardware: se reinicia cuando `LY==WY` con ventana activa. Con `WY/WX` cambiados a mitad de frame, los cuadros leían tiles desalineados | `ppu.cpp` |
| RAM nativa sin liberar | Instancias nativas globales sin método explícito de destrucción. | `native_bridge.cpp` |

## 3. Requisitos funcionales

### RF-01: Doble búfer del framebuffer PPU
- La PPU pinta cada scanline en un **búfer de trabajo** (`back buffer`) y realiza el **intercambio atómico de punteros** (swap) al entrar en VBlank (frame completo). El hilo de emulación jamás pisa el búfer que sube el hilo GL.
- `getFrameBuffer()` / `getFrameBufferDirect()` devuelven SIEMPRE el búfer `present` vigente.
- **Criterio:** Desplazamiento sin fotogramas rasgados ni parpadeo visible.
- Restricción de memoria: dos búferes fijos de la instancia (`2 × 92 160 B`); cero asignaciones por fotograma.

### RF-02: HDMA troceado por HBlank
- Modo HDMA con LCD encendido: arranca transferencia de bloques de **16 B**, copiando **un bloque por HBlank** (modo 0, scanlines 0..143).
- `0xFF55` reporta los bloques restantes; escribir durante una transferencia activa la cancela según el estándar.
- **Criterio:** Menús, inventarios y pantallas de batalla se presentan sin distorsión visual.

### RF-03: Contador de ventana fiel
- `windowLine_` se **reinicia a 0 cuando `LY==WY`** en la scanline donde la ventana está activa.
- Se incrementa **solo** en scanlines donde la ventana efectivamente se dibuja.
- **Criterio:** En diálogos y cuadros de texto, los bordes de la ventana permanecen nítidos y alineados.

### RF-04: Liberación explícita del pipeline nativo
- Función `nativeDestroyPipeline()` que detiene la emulación, guarda los datos en memoria y libera las instancias nativas C++. Se invoca al destruir el motor de emulación.

### RF-05: Contraindicaciones de guardado
- Ningún cambio en los archivos de guardado `.sav`. La persistencia de partida continúa funcionando sin interrupciones.

## 4. Requisitos no funcionales

- **Rendimiento:** Mantener ≥ 50 fps equivalentes de emulación.
- **Estabilidad:** Compilación verde (`EXIT=0`) tras cada cambio.
- **Alcance:** Restringido a módulos nativos C++ y puente JNI.

## 5. Criterios de aceptación

1. `./gradlew assembleDebug --offline` → `EXIT=0`.
2. Tests de unidad de la PPU y MMU en verde.
3. Desplazamiento continuo en el mapa sin rasgado ni parpadeo.
4. Menús y batallas presentados sin distorsión de líneas.
5. Cuadros de texto estables con bordes alineados.
6. Persistencia `.sav` funcionando correctamente tras pausar o cerrar la aplicación.
