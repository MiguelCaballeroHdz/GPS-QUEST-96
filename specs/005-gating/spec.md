# spec.md — 005 Gating original e integridad tras derrotas

Estado: BORRADOR — **BLOQUEADO** por la verificación de memoria de datos en el dispositivo de prueba.
Requiere APROBADO antes de plan.md / tasks.md / código.

## 1. Objetivo

Las restricciones de avance en el mapa las determinan las banderas de progreso reales de los datos de guardado (`.sav`), y la derrota en un combate o evento respeta la posición del jugador sin teletransporte forzado.

## 2. Alcance

- Mapa de banderas de estado poblado con la estructura real de los datos del usuario; migración a banderas de guardado oficiales.
- Zona bloqueada en el avance = sin interacción con eventos principales; solo encuentros de ruta normales del tramo actual.
- Derrota: Coordenadas GPS intactas + tregua temporal + opción de restauración/curación.

## 3. Fuera de alcance

Generación de eventos (B2), mapeo global (004), arquitecturas de 32 bits, post-game congelado, tiendas o microtransacciones.

## 4. Criterios de aceptación (Dispositivo de Prueba)

1. Zona bloqueada en el avance no permite interacción al trasladarse físicamente; mensaje explicativo en pantalla.
2. Derrota: coordenadas GPS del usuario intactas (avatar permanece en la ubicación física actual), tregua activa por 180 s.
3. Cero banderas inventadas en el código fuente.

## 5. Riesgos y supuestos

- **Puerta:** Direcciones de memoria reales verificadas desde los datos del usuario; prohibido adivinar offsets.
- Estructura de datos por archivo del usuario: adaptada por versión o familia de datos.

## 6. Dependencias

Lector de datos guardados + guardado atómico verificados. Necesita la validación de memoria del dispositivo antes de cualquier plan.
