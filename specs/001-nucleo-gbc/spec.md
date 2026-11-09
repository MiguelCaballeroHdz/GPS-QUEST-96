# spec.md — 001 Núcleo Motor Retro 8 bits: cargar datos de juego del usuario y ejecutar inicio intacto

Estado: BORRADOR — requiere APROBADO antes de plan.md / tasks.md / código.

## 1. Objetivo

Cargar un archivo de datos de juego de 8 bits aportado por el usuario y ejecutar su secuencia de inicio 100% intacta hasta la salida a la aventura inicial, con lectura de tiles en vivo desde la memoria de video (VRAM). Nada propio en pantalla salvo elementos de interfaz (HUD, joystick, marcador neutro).

## 2. Alcance

- Boot válido: cabecera legal de datos, controladores MBC1/3/5 con memoria persistente y guardado en pausa.
- Procesador de 8 bits ejecuta el boot hasta la intro sin instrucciones desconocidas ni bloqueos.
- Tiles 8x8 leídos de VRAM en vivo (`tiles:M>0` en overlay); mapa OSM visible tras ellos.
- Primer arranque ofrece la carga de datos del usuario siempre; cancelar lleva a modo demostración.
- Sin soporte de 32 bits (puerta por extensión, mensaje informativo), sin post-game (§8 congelado), sin eventos inventados, sin sprites no autorizados.

## 3. Fuera de alcance

Núcleo de 32 bits, tablas de eventos de datos (B2), sprite de Overworld del jugador (B4), mapeo monumento→locación (Fase C), gating por banderas de estado reales (5c), tiendas o microtransacciones.

## 4. Criterios de aceptación (todos verificables en dispositivo de prueba)

1. `MBC ready type=MBC5` + cabecera válida con archivo de datos del usuario.
2. 60 s sin tocar: cero `Opcode no implementado`, cero FATAL.
3. Intro visible avanzando (pantalla de inicio/título del juego).
4. Overlay: `Red: OK N elem · tiles:M` con M>0.
5. Home + kill + reabrir: `MBC SRAM load N/N B` y progreso intacto.
6. Sin datos cargados: patrón de prueba + solicitud obligatoria de carga, sin crash.

## 5. Riesgos y supuestos

- **Riesgo abierto:** bloqueo de boot por memoria vacía. Se mantiene trazabilidad del contador de programa (PC).
- **Supuesto:** archivo de datos válido aportado de forma privada por el usuario (RULES §1, repositorio limpio).
- **Supuesto:** red disponible para Overpass/tiles iniciales; sin red vale fallback + juego local.

## 6. Dependencias

Controlador de memoria + persistencia verificados. Build `assembleDebug` verde como puerta de cada cambio.
