# spec.md — 010 Pantalla encendida durante la partida

Estado: **APROBADO** 2026-10-26.
Ejecución: **HECHO y verificado**.

## 1. Visión y propósito

Las sesiones de uso requieren mantener la pantalla activa mientras el usuario explora. El timeout de pantalla del sistema operativo no debe interrumpir la sesión de uso. Se requiere que, **mientras la ventana principal de GPS Quest Urbano esté en primer plano**, la pantalla no se apague por inactividad. Al salir de la app, el dispositivo recupera su timeout normal.

No se cambia el ajuste global del sistema, no se pide ningún permiso nuevo y no se impide que el usuario bloquee manualmente con el botón de encendido.

## 2. Evidencia que motiva la spec

| Hecho | Dato |
|---|---|
| Pruebas y uso | Sesiones continuas de exploración en el dispositivo de prueba. |
| Riesgo | El dispositivo se bloquea por timeout de inactividad durante la navegación. |
| Alcance | Solo la pantalla principal (juego), no servicios en segundo plano. |

## 3. Requisitos funcionales

### RF-01: Pantalla encendida en primer plano
- Con `MainActivity` visible y sin interacción, la pantalla permanece encendida.
- **Criterio:** Con un timeout de 15 s, tras 30 s la pantalla sigue encendida.

### RF-02: Sin efecto fuera de la app
- Si la app pasa a segundo plano, se abre otra app o se cierra, el flag deja de actuar y el sistema vuelve a su timeout normal.
- **Criterio:** Tras pulsar Home y esperar el timeout, la pantalla se apaga.

### RF-03: Sin cambios globales ni permisos
- La implementación **no** modifica la configuración del sistema.
- No se añade `WAKE_LOCK` ni permisos nuevos.
- El botón de encendido sigue bloqueando el dispositivo manualmente.

### RF-04: Sin tocar el producto
- No se modifica la lógica del motor, guardado, GPS ni renderizado.
- Único cambio: la ventana de `MainActivity`.

## 4. Implementación propuesta

En `MainActivity.onCreate()`, después de `setContentView(...)`:

```java
getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
```

El flag está asociado a la ventana de la actividad: vive mientras `MainActivity` está visible y desaparece al salir. No requiere `WakeLock` ni permiso.

## 5. Fuera de alcance

- Mantener la pantalla encendida en segundo plano o con la app cerrada.
- Cambiar el timeout global del dispositivo de forma permanente.
- Desbloquear el dispositivo o saltarse la pantalla de bloqueo.

## 6. Riesgo residual

Consumo de batería algo mayor **solo mientras la app está visible**. Es el comportamiento esperado y no afecta al resto del sistema.

## 7. Criterios de Aceptación y Verificación

- **Código:** `MainActivity.onCreate()` añade `FLAG_KEEP_SCREEN_ON` a la ventana. `assembleDebug --offline` → **BUILD SUCCESSFUL**.
- **Dispositivo de prueba:** `dumpsys window windows` muestra `fl=KEEP_SCREEN_ON` en la ventana de la aplicación.
- **Primer plano:** Pantalla activa de forma continua en primer plano.
- **Segundo plano:** Tras minimizar, el dispositivo retoma su comportamiento normal de ahorro de pantalla.
