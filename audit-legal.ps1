# audit-legal.ps1 - Chequeo rápido antes de compilar/compartir.
# Ejecutar desde la raíz del proyecto.
#
# Notas de implementación:
# - PowerShell 5.1 no expande `**` en -Path, así que se usa Get-ChildItem -Recurse.
# - Se excluye el valor obligatorio del filtro BLE
#   ACCESSORY_NAME = "Pokemon GO Plus" (interoperabilidad, Lote 2).
# - El chequeo de tools/ solo mira archivos de texto para evitar falsos positivos
#   sobre PNG/ZIP.

$ErrorActionPreference = 'SilentlyContinue'
$critical = $false

Write-Host "=== AUDITORIA LEGAL GPS Quest ===" -ForegroundColor Cyan

# 1. Binarios fuera de build/
$bin = Get-ChildItem -Recurse -File |
    Where-Object {
        $_.Extension -in '.gb', '.gbc', '.gba', '.sav', '.srm' -and
        $_.FullName -notmatch "\\build\\" -and
        $_.FullName -notmatch "\\.git\\"
    }
if ($bin) {
    Write-Host "CRITICO: Binarios encontrados:" -ForegroundColor Red
    $bin | ForEach-Object { Write-Host " - $($_.FullName)" }
    $critical = $true
} else {
    Write-Host "OK: Sin binarios fuera de build/" -ForegroundColor Green
}

# 2. Marcas en código de producción
$prodFiles = @()
$prodFiles += Get-ChildItem 'app/src/main/java' -Recurse -File -Filter '*.java'
$prodFiles += Get-ChildItem 'app/src/main/cpp' -Recurse -File |
    Where-Object { $_.Extension -in '.cpp', '.h' }
$prodFiles += Get-ChildItem 'app/src/main/res' -Recurse -File -Filter 'strings.xml'

$marcas = $prodFiles |
    Select-String -Pattern 'pokémon|pokemon|nintendo|GO_PLUS|GoPlusService' -CaseSensitive:$false |
    Where-Object { $_.Line -notmatch 'ACCESSORY_NAME\s*=\s*"Pokemon GO Plus"' }

if ($marcas) {
    Write-Host "CRITICO: Marcas en código:" -ForegroundColor Red
    $marcas | ForEach-Object { Write-Host " - $($_.Path):$($_.LineNumber) $($_.Line.Trim())" }
    $critical = $true
} else {
    Write-Host "OK: Sin marcas en código (excluido el valor BLE requerido)" -ForegroundColor Green
}

# 3. tools/ limpio
$tools = Get-ChildItem 'tools' -Recurse -File |
    Where-Object {
        $_.Extension -in '.txt', '.md', '.json', '.xml', '.log', '.csv' -and
        $_.FullName -notmatch "\\build\\" -and
        $_.FullName -notmatch "\\.git\\"
    } |
    Select-String -Pattern 'PM_CRYSTAL|BYTS|Pokemon - Edicion'

if ($tools) {
    Write-Host "CRITICO: Metadatos ROM en tools/:" -ForegroundColor Red
    $tools | ForEach-Object { Write-Host " - $($_.Path):$($_.LineNumber)" }
    $critical = $true
} else {
    Write-Host "OK: tools/ limpio" -ForegroundColor Green
}

Write-Host "=== FIN AUDITORIA ===" -ForegroundColor Cyan
if ($critical) { exit 1 }
exit 0
