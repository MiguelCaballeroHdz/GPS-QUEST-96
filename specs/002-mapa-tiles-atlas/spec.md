# spec.md — 002 Composición de Mapa con Tiles del Atlas VRAM (B1-2)

Estado: CERRADO 2026-10-19. Slices 1, 2 y 3 ACEPTADOS de punta a punta en dispositivo de prueba.

## 1. Visión y Propósito
Reemplazar el renderizado de celdas del mapa (`GBMapRenderer`) por la composición del raster de OpenStreetMap (OSM) utilizando exclusivamente los tiles de 8×8 píxeles extraídos en tiempo real de la memoria de video de 8 bits (`TileAtlas`).

Fidelidad al Estándar (RULES / AGENTS.md):
- **Lectura 100% fiel de datos del usuario:** Sin texturas propietarias ni no autorizadas. Cada celda del mapa se dibuja con un tile del atlas de 384 tiles (`TileAtlas`) activo en la memoria del procesador gráfico.
- **VRAM Viva:** El atlas se refresca periódicamente desde `TileAtlas.snapshot(mmu)` en worker optimizado.
- **Sin afectación al core:** El pipeline del motor (CPU/PPU/MMU) permanece intacto. Solo cambia la composición visual en modo Mapa.
- **Modo Juego intacto:** La vista Juego (PPU opaca a pantalla completa) no se toca. Esta spec solo afecta la composición en modo Mapa.

## 2. Requisitos Funcionales

### RF-01: Mapeo Determinista de Atributos OSM a Tiles VRAM
- Cada celda del raster OSM se clasifica por tipo de terreno: **calle**, **edificio**, **pasto**, **agua**.
- El tipo de terreno se mapea a un **índice de tile del atlas** mediante una tabla determinista fija (cromo UI, no contenido del juego):
  - Calle → tile índice T_CALLE
  - Edificio → tile índice T_EDIFICIO
  - Pasto → tile índice T_PASTO
  - Agua → tile índice T_AGUA
- Los índices T_* se definen en código como constantes y se ajustan tras verificación visual en el dispositivo de prueba. **No se crean gráficos propios** — solo se selecciona qué tile existente del atlas se usa para cada tipo.
- **Fallback:** Si la VRAM aún no contiene suficiente variedad (inicio del arranque), se usa un tile del atlas ya cargado (determinista: tile 0 o el más frecuente en el snapshot). **Nunca inventado.**

### RF-02: Renderizado eficiente OpenGL ES 2.0
- La textura del atlas (384 tiles × 8×8 píxeles, formato RGBA) se sube a GPU como textura 2D en `GameRenderer`.
- La subida es thread-safe: se realiza en el hilo GL (`onDrawFrame`) desde un `volatile` actualizado por el worker de `TileAtlas`.
- Cero asignación de memoria dinámica en `onDrawFrame` (reutilizar buffers).
- `GBMapRenderer` muestrea la textura del atlas por celda según el mapeo RF-01.

### RF-03: Conmutación y Persistencia de Vista
- El toggle "Vista: Juego / Mapa" mantiene su funcionamiento actual (persiste en prefs `view_ppu_full`).
- En modo Mapa, `GBMapRenderer` compone celdas con tiles del atlas en lugar de colores planos provisionales.
- `GBTileRenderer` (fallback de pasto) se mantiene como contingencia si el atlas no está disponible (p. ej. sin datos de juego cargados).

## 3. Requisitos No Funcionales
- **Rendimiento:** Mantener 30+ fps sin micro-tirones ni asignaciones de memoria excesivas en `onDrawFrame`.
- **Compatibilidad:** Verificado en Android 8.0+ / dispositivo de prueba (8 GB RAM).

## 4. Criterios de Aceptación y Puerta de Verificación
- **Compilación:** `./gradlew assembleDebug` verde (`EXIT=0`).
- **Evidencia en Dispositivo:** La vista Mapa muestra celdas compuestas por tiles reales del atlas VRAM extraído en vivo (`tiles:M > 0`), sin cierres ni caídas de frames.
- **Foto:** Mapa con celdas visibles compuestas por tiles del atlas (no colores planos).
