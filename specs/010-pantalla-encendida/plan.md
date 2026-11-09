# plan.md — 010 Pantalla encendida durante la partida

Spec: `specs/010-pantalla-encendida/spec.md` (APROBADA 2026-10-26).
Regla: un slice verificable por turno; build verde; si rompe, se repara ese archivo.

## Slice 1 — flag de ventana (ÚNICO)

**Objetivo:** que el POCO no se apague por inactividad mientras el juego está en primer
plano, sin tocar ajustes globales.

Archivo:
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`
  - En `onCreate()`, después de `setContentView(R.layout.activity_main)`:
    `getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);`
  - Comentario corto explicando que el flag vive con la ventana y no afecta al background.

Verificación:
- `.\gradlew assembleDebug --offline` → `BUILD SUCCESSFUL`.
- Instalar el APK en el POCO.
- Guardar el timeout actual: `adb shell settings get system screen_off_timeout`.
- Ponerlo a 15 s para la prueba: `adb shell settings put system screen_off_timeout 15000`.
- Abrir GPS Quest '96 en primer plano y esperar >30 s sin tocar la pantalla.
- Comprobar que sigue encendida (`adb shell dumpsys power` o captura).
- Pulsar Home, esperar >15 s y comprobar que el sistema apaga la pantalla.
- Restaurar el timeout original.

Criterio de aceptación:
- La pantalla sigue encendida con la app visible.
- Al salir a Home, el sistema vuelve a apagar por timeout.
- El botón de encendido sigue bloqueando manualmente.
- `assembleDebug` verde.

Riesgo residual:
- Consumo de batería algo mayor solo con la app visible. No hay efecto en segundo plano.

## Resultado (2026-10-26) — HECHO

- Build `assembleDebug --offline` verde e instalación `-r` en el POCO.
- `dumpsys window windows`: `fl=KEEP_SCREEN_ON` en `com.gpsquest96/.ui.MainActivity`.
- Primer plano: timeout 15 s, `stay_on_while_plugged_in=0`, 35 s sin tocar →
  pantalla encendida (`mScreenOn=true`, `Awake`); captura `screen-35s.png`.
- Home: a los 24 s el sistema apaga (`mScreenOn=false`, `Dozing`); captura
  `screen-home-24s.png`.
- Ajustes restaurados a `300000` ms y `15`.
- Evidencia: `tools/poco-capture/20261026-keepon/evidence.md`.
- La primera vuelta con keyguard seguro no contó: el flag no puede desbloquear; se
  repitió con el POCO desbloqueado y la app visible.
