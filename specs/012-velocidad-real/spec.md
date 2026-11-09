# spec.md — 012 Velocidad real (bombeo a tiempo real + GL 60)

Estado: **APROBADO**.
Ejecución: Slices 1 y 2 HECHOS y verificado a 59.7 FPS en dispositivo de prueba.

## 1. Visión y propósito

Garantizar que el motor avance a **velocidad real de 60 Hz (59.7 FPS)**. El objetivo es entregar un rendimiento fluido y continuo en el dispositivo de prueba objetivo sin aceleraciones o desaceleraciones artificiales.

Anti-objetivos (explícitos): no modificar el motor de audio APU, no alterar la persistencia `.sav` ni modificar el contenido del usuario.

## 2. Evidencia que motiva la spec

| Síntoma | Medición/lectura | Dato |
|---|---|---|
| Rendimiento deseado | Tasa de fotogramas completa de 60 Hz (59.7 FPS) | **59.7 FPS** con ciclos por frame completos (~70224 ciclos/frame) |
| Sincronización GL | Ajuste del temporizador de refresco en `MainActivity` | GLThread sincronizado a 60 Hz |

## 3. Requisitos funcionales

### RF-01: EmuLoop con acumulador de tiempo a ciclos
- El hilo de emulación utiliza un **acumulador de tiempo**: en cada intervalo mide el tiempo real transcurrido (`dt`), calcula los ciclos correspondientes (`ciclos = dt × 4194304`), y ejecuta los fotogramas completos equivalentes vía `stepFrame()`, con un tope de seguridad de fotogramas por intervalo.
- La deriva del temporizador de Android es absorbida por el acumulador para mantener el promedio exacto de 59.7 FPS.
- **Criterio:** Log reporta ≥ **55-59.7 FPS sostenidos** continuos en el dispositivo de prueba.

### RF-02: GLRenderer a 60 fps
- `TARGET_FPS` configurado a **60** (16.67 ms de intervalo de refresco en `onDrawFrame`).
- `onDrawFrame` no renderiza más de una vez por intervalo; utiliza el frame `present` atómico del anillo de doble búfer.

### RF-03: Coherencia con el anillo de doble búfer
- El hilo de renderizado GL siempre muestrea el último fotograma completo publicado por el motor nativo sin carreras de memoria.

### RF-04: Guardas de seguridad y pausa
- Al pausar la aplicación o minimizarla, el acumulador de tiempo se reinicia inmediatamente para evitar saltos o aceleraciones acumuladas tras una pausa prolongada.
- El hilo de ejecución permanece blindado ante excepciones.

### RF-05: Preservación de guardado
- Ningún cambio en los archivos de guardado `.sav` o perfiles `.pueblo`.

## 4. Requisitos no funcionales

- **Rendimiento:** Mancomunar la tasa de 59.7 FPS sostenida con baja tasa de consumo de procesador.
- **Estabilidad:** Compilación verde (`EXIT=0`) tras cada cambio.
- **Alcance:** Restringido a los temporizadores de ejecución en Java y la tasa de refresco GL.

## 5. Criterios de aceptación

1. `./gradlew assembleDebug --offline` → `EXIT=0`.
2. ≥ 55-59.7 FPS sostenidos en el dispositivo de prueba.
3. Desplazamiento y movimiento fluidos sin tirones.
4. Menús y batallas funcionando correctamente sin aceleración indebida.
5. Persistencia `.sav` e importación `.pueblo` funcionando normalmente.
