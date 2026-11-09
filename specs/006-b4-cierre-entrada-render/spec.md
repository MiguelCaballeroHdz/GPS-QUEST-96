# spec.md — 006 Cierre de B4: entrada, instrumentación y render fiel

Estado: **APROBADO** 2026-10-23.
Ejecución: Slices 1 y 2 HECHOS, Slice 3 PENDIENTE de verificación en dispositivo de prueba.

## 1. Visión y propósito

Garantizar que la ejecución de los datos del juego del usuario llegue limpiamente al mapa de exploración inicial sin parches propios ni degradaciones visuales en el renderizado.

Esta spec consolida la estabilidad del motor en tres frentes:

1. **Eliminación de instrumentación temporal** — remover trazas del core que reduzcan la tasa de fotogramas o inyecten escrituras no autorizadas en memoria.
2. **Entrada real** — asegurar que las pulsaciones de botones A/B/START lleguen al motor en cualquier pantalla o aviso de texto.
3. **Render fiel** — renderizar sprites OAM (cursor de menú, avatar) sin rayas verticales ni texto fuera del cuadro.

Fidelidad al Estándar:
- **Nada inventado:** eliminar cualquier inyección arbitraria de datos en memoria por condición de dirección de programa en la CPU.
- **Sin offsets adivinados:** las soluciones provienen de comportamiento observable en registros estándar del motor (`0xFF00`, `LY`, `OAM`).
- **Core separado del producto:** mantener intacta la emulación sin mezclar mecánicas secundarias.

## 2. Evidencia que motiva la spec

| Hecho medido | Dato |
|---|---|
| Rendimiento inicial con instrumentación | Ciclos diferidos por escaneo masivo de direcciones en el bucle principal de la CPU. |
| Bloqueo en aviso de texto inicial | Espera de lectura en puerto de entrada sin respuesta. |
| Renderizado | Cursor de menú omitido, artefactos en el búfer de pantalla. |
| Input de control | Pulsaciones ultracortas perdidas por desincronización de fotogramas. |

## 3. Requisitos funcionales

### RF-01: Retirar instrumentación temporal del core
- Quitar del módulo de la CPU cualquier histograma temporal de trazado de direcciones y parches de memoria por condición de banco o programa.
- Limpiar `LOGI` excesivos por instrucción o fotograma.
- **Criterio:** El código de la CPU no contiene arreglos específicos por título ni inyecciones de prueba en memoria.

### RF-02: Entrada fiel en prompts (sin parches por título)
- Los botones A/B/START/SELECT y la cruceta direccional deben registrarse en cualquier pantalla: título, menú, avisos de texto o mapa de exploración.
- El refresco de los controles debe ocurrir de acuerdo al estándar de hardware (registro `0xFF00` + interrupción de control si está habilitada).
- **Criterio:** En todos los archivos de datos de prueba del usuario, las secuencias de texto iniciales avanzan con pulsaciones normales y se llega al mapa sin parches.

### RF-03: Render fiel de sprites y ventana
- El cursor del menú (OAM) debe verse correctamente.
- Sin rayas verticales ni contenido repetido en el búfer de pantalla.
- Texto dentro de los límites de su cuadro.

### RF-04: Verificación reproducible en dispositivo de prueba
- Llegar al mapa de exploración, mover el joystick y confirmar la detección de tiles del avatar con la silueta correspondiente.

## 4. Requisitos no funcionales

- **Rendimiento:** ≥ 50 fps equivalentes de emulación en el dispositivo de prueba con la instrumentación temporal retirada.
- **Estabilidad:** Compilación verde (`EXIT=0`) tras cada cambio.
- **Alcance:** Restringido a módulos del motor nativo en C++ y puentes JNI.

## 5. Criterios de aceptación

1. `./gradlew assembleDebug --offline` → `EXIT=0`.
2. Código de la CPU limpio de parches o inyecciones por título.
3. Rendimiento en dispositivo de prueba ≥ 50 fps continuos.
4. Secuencias de inicio del usuario: intro → menú → avisos → **mapa de exploración**, con foto.
5. Extractor de sprites detecta el avatar tras mover el joystick en el mapa.
6. Menú con cursor visible y texto enmarcado en pantalla.
7. Cero escrituras en memoria RAM protegida; inyecciones obsoletas eliminadas.

## 6. Riesgos y supuestos

- **Supuesto:** Archivos de datos del usuario válidos y legales alojados de forma privada en el almacenamiento local.
- **Riesgo:** Prioridades de renderizado de sprites en pantalla. Mitigación: respetar el estándar de atributos OAM.

## 7. Dependencias

- Módulos nativos PPU, MMU y CPU.
- Extractor de sprites y componente UI de la aplicación.
