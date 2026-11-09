# Evidencia — 010 pantalla encendida (POCO, 2026-10-26)

Dispositivo: POCO X7 Pro `2412DPC0AG` / `XWWSAY4LAAPZHIA6`.
Build: `.\gradlew.bat assembleDebug --offline` → `BUILD SUCCESSFUL`.
APK instalado con `adb install -r app-debug.apk` (datos conservados).

## 1. El flag está en la ventana correcta

`adb shell dumpsys window windows` sobre `com.gpsquest96/.ui.MainActivity`:

```text
mAttrs={(0,0)(fillxfill) ... ty=BASE_APPLICATION fmt=TRANSLUCENT
  fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS LAYOUT_INSET_DECOR ...
```

Solo afecta a la ventana de `MainActivity`; no hay `WAKE_LOCK` ni permisos nuevos.

## 2. Prueba en primer plano

Ajustes temporales para que el sistema apague por inactividad (el POCO estaba
configurado con "mantener encendido mientras está conectado"):

| Ajuste | Antes | Prueba | Después |
|---|---:|---:|---:|
| `system screen_off_timeout` | 300000 ms | 15000 ms | 300000 ms |
| `global stay_on_while_plugged_in` | 15 | 0 | 15 |

Con `MainActivity` enfocada (`mCurrentFocus=...MainActivity`) y 35 s sin tocar
la pantalla:

```text
mScreenOn = true
mAwake=true mScreenOnEarly=true mScreenOnFully=true
mWakefulness=Awake
mHoldingDisplaySuspendBlocker=true
```

Captura: `screen-35s.png` (título de Azul visible, HUD y controles).
El timeout de 15 s no apagó la pantalla → **RF-01 cumple**.

## 3. Prueba al pasar a segundo plano

`adb shell input keyevent KEYCODE_HOME`; a los 2 s el foco era el launcher
(`com.mi.android.globallauncher`) y la pantalla seguía encendida. A los 24 s:

```text
mScreenOn = false
mAwake=false mScreenOnEarly=false mScreenOnFully=false
mWakefulness=Dozing
mHoldingDisplaySuspendBlocker=false
mCurrentFocus=Window{... NotificationShade}
```

Captura: `screen-home-24s.png` (pantalla apagada).
El sistema volvió a aplicar su timeout al salir de la app → **RF-02 cumple**.

## 4. Nota de la primera vuelta

El primer intento se hizo con el POCO bloqueado por huella/PIN. `am start` no
enciende la pantalla y `FLAG_KEEP_SCREEN_ON` no puede saltarse el keyguard; es
el comportamiento esperado. Tras desbloquearlo, la prueba se repitió con la app
visible y pasó. El flag **no** desbloquea ni enciende un dispositivo apagado.

## 5. Archivos

- `screen-unlocked.png` — app enfocada tras desbloquear.
- `screen-35s.png` — 35 s en primer plano con timeout de 15 s; pantalla encendida.
- `screen-home.png` — recién pulsado Home; launcher visible.
- `screen-home-24s.png` — 24 s después; pantalla apagada.
- `screen-menu.png`, `screen-swipe.png`, `screen-wake*.png` — intentos con el
  keyguard seguro (histórico, no fallo del código).
