# Captura de diagnóstico para el gate POCO (GPS Quest '96).
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File tools\poco-test.ps1 -Phase all
#   powershell -ExecutionPolicy Bypass -File tools\poco-test.ps1 -Phase install
#   powershell -ExecutionPolicy Bypass -File tools\poco-test.ps1 -Phase capture
#
# `all`: instala el APK, lanza la app, espera a que hagas las pruebas manuales
# y captura logcat + dumps de filesDir en tools\poco-capture\<fecha>.
# `install`: solo instala y lanza. `capture`: solo captura el estado actual.
#
# No requiere root: usa `run-as` porque el APK es debug. No descarga la ROM.
param(
    [ValidateSet("all", "install", "capture")]
    [string]$Phase = "all",
    [string]$Adb = "",
    [string]$Apk = "",
    [string]$Package = "com.gpsquest96"
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
if (-not $Apk) {
    $Apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
}

function Find-Adb {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:ADB) { $candidates += $env:ADB }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    $candidates += "C:\Users\Miguel Caballero\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    throw "No encontré adb.exe. Pasa -Adb 'C:\ruta\adb.exe'."
}

$Adb = Find-Adb $Adb
Write-Host "adb: $Adb"

$devices = & $Adb devices
Write-Host ($devices -join "`n")
$online = @($devices | Where-Object { $_ -match '^\S+\s+device' })
if ($online.Count -eq 0) {
    throw "No hay dispositivo autorizado. Conecta el POCO, activa Depuración USB y acepta la huella."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$capture = Join-Path $PSScriptRoot "poco-capture\$stamp"
New-Item -ItemType Directory -Force -Path $capture | Out-Null
Write-Host "Salida: $capture"

function Adb-Text {
    param([string[]]$AdbArgs)
    & $Adb @AdbArgs 2>&1 | Out-String
}

function Pull-RunAs {
    param([string]$Remote, [string]$Local)
    # Redirección con cmd para no corromper binarios con la codificación de PS.
    $line = '"' + $Adb + '" exec-out run-as ' + $Package + ' cat "' + $Remote + '" > "' + $Local + '"'
    cmd /c $line
    if (-not (Test-Path $Local)) {
        Write-Warning "No se pudo extraer $Remote"
    }
}

function Convert-FrameToPng {
    param([string]$RgbaPath, [string]$PngPath)
    if (-not (Test-Path $RgbaPath)) { return }
    $bytes = [System.IO.File]::ReadAllBytes($RgbaPath)
    if ($bytes.Length -ne (160 * 144 * 4)) {
        Write-Warning "ppu_frame.rgba tiene $($bytes.Length) B, se esperaban 92160."
        return
    }
    try {
        Add-Type -AssemblyName System.Drawing
    } catch {
        Write-Warning "System.Drawing no disponible; se conserva el .rgba crudo."
        return
    }
    $bmp = New-Object System.Drawing.Bitmap 160, 144
    try {
        for ($y = 0; $y -lt 144; $y++) {
            for ($x = 0; $x -lt 160; $x++) {
                $i = ($y * 160 + $x) * 4
                # GL_RGBA + UNSIGNED_BYTE: bytes R,G,B,A.
                $c = [System.Drawing.Color]::FromArgb(
                    [int]$bytes[$i + 3], [int]$bytes[$i],
                    [int]$bytes[$i + 1], [int]$bytes[$i + 2])
                $bmp.SetPixel($x, $y, $c)
            }
        }
        $bmp.Save($PngPath, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Host "Frame -> $PngPath"
    } finally {
        $bmp.Dispose()
    }
}

if ($Phase -eq "install" -or $Phase -eq "all") {
    if (-not (Test-Path $Apk)) {
        throw "No existe el APK: $Apk. Ejecuta .\gradlew assembleDebug --offline."
    }
    Write-Host "Instalando $Apk ..."
    & $Adb install -r $Apk
    if ($LASTEXITCODE -ne 0) {
        throw "adb install falló. Si es INSTALL_FAILED_UPDATE_INCOMPATIBLE, desinstala a mano (perderás saves) o firma igual."
    }
    & $Adb logcat -c
    & $Adb shell am force-stop $Package
    & $Adb shell am start -n "$Package/.ui.MainActivity"
    Write-Host "App lanzada."
}

if ($Phase -eq "all") {
    Write-Host ""
    Write-Host "Haz ahora las pruebas manuales en el POCO:" -ForegroundColor Cyan
    Write-Host "  1. Abrir Gen 2 (.gbc) y comprobar color real (no verde DMG)."
    Write-Host "  2. Mover/joystick, entrar a menú, abrir engrane."
    Write-Host "  3. Guardar en el juego, matar la app, reabrir -> CONTINUE."
    Write-Host "  4. Exportar perfil (.pueblo), importarlo y reabrir."
    Write-Host "  5. Long-press en 'Partidas Guardadas' (dump SRAM)."
    Write-Host "  6. Long-press en 'Vista' (dump frame+OAM+VRAM+paletas)."
    Write-Host ""
    Read-Host "Pulsa ENTER cuando termines para capturar"
}

if ($Phase -eq "capture" -or $Phase -eq "all") {
    Write-Host "Capturando logcat ..."
    & $Adb logcat -d -v threadtime -s GPSQuest96:V GameEngine:V Pueblo:V AndroidRuntime:E "*:S" |
        Out-File -Encoding utf8 (Join-Path $capture "logcat.txt")

    $device = Adb-Text @("shell", "getprop", "ro.product.model")
    $android = Adb-Text @("shell", "getprop", "ro.build.version.release")
    $sdk = Adb-Text @("shell", "getprop", "ro.build.version.sdk")
    @(
        "model=$($device.Trim())",
        "android=$($android.Trim())",
        "sdk=$($sdk.Trim())",
        "package=$Package",
        "apk=$Apk",
        "captured=$(Get-Date -Format o)"
    ) | Out-File -Encoding utf8 (Join-Path $capture "device.txt")

    Adb-Text @("shell", "run-as", $Package, "ls", "-lR", "files") |
        Out-File -Encoding utf8 (Join-Path $capture "files-list.txt")

    $known = @(
        "sram_dump.txt",
        "ppu_regs.txt",
        "ppu_frame.rgba",
        "ppu_oam.bin",
        "ppu_vram.bin",
        "ppu_vram1.bin",
        "ppu_wram.bin",
        "ppu_hram.bin",
        "ppu_bg_palette.bin",
        "ppu_obj_palette.bin"
    )
    foreach ($name in $known) {
        Pull-RunAs "files/$name" (Join-Path $capture $name)
    }
    Convert-FrameToPng (Join-Path $capture "ppu_frame.rgba") (Join-Path $capture "ppu_frame.png")

    # Saves y respaldos de la ROM copiada en files/roms (no la ROM).
    $romList = & $Adb shell run-as $Package ls -1 files/roms 2>$null
    $saveDir = Join-Path $capture "saves"
    New-Item -ItemType Directory -Force -Path $saveDir | Out-Null
    foreach ($raw in $romList) {
        $name = ($raw -replace "`r", "").Trim()
        if ($name -match "\.sav($|\.bak$)") {
            Pull-RunAs "files/roms/$name" (Join-Path $saveDir $name)
        }
    }

    # Perfiles .pueblo exportados a Descargas/Documentos (si el usuario los dejó ahí).
    $profiles = & $Adb shell 'ls /sdcard/Download/*.pueblo /sdcard/Documents/*.pueblo 2>/dev/null'
    foreach ($raw in $profiles) {
        $remote = ($raw -replace "`r", "").Trim()
        if (-not $remote -or $remote -match "No such file") { continue }
        $localName = "profile-" + [System.IO.Path]::GetFileName($remote)
        & $Adb pull $remote (Join-Path $capture $localName) 2>&1 | Out-Null
        if (Test-Path (Join-Path $capture $localName)) {
            Write-Host "Perfil -> $localName"
        }
    }

    # Estado de la actividad y memoria: útil para cuelgues/ticks.
    Adb-Text @("shell", "dumpsys", "activity", "top") |
        Out-File -Encoding utf8 (Join-Path $capture "activity-top.txt")
    Adb-Text @("shell", "dumpsys", "meminfo", $Package) |
        Out-File -Encoding utf8 (Join-Path $capture "meminfo.txt")

    Write-Host ""
    Write-Host "Captura completa: $capture" -ForegroundColor Green
    Get-ChildItem $capture | Select-Object Name, Length | Format-Table
}
