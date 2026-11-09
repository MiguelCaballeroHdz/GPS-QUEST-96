# Compila y ejecuta los host tests del core (Windows + VS Build Tools).
#   test_mmu: bancos/registros CGB y color RGB15.
#   test_sav: guardado real .sav (MBC3 + batería, respaldo y reapertura).
#   test_cgb_ppu: render CGB (paletas, bancos, flips, prioridad, OPRI, ventana).
#   test_lr35902: CPU mínimo + STOP + DIV/TIMA en doble velocidad.
#   test_wildtable: tablas de encuentro de Gen 2 (sintéticas + ROM real opcional).
$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$src  = Join-Path $root "app\src\main\cpp"
$out  = Join-Path $PSScriptRoot "build"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) {
    throw "No se encontró vswhere.exe. Instala Visual Studio Build Tools con C++."
}

$vs = & $vswhere -latest -products * -property installationPath
if (-not $vs) {
    throw "No se encontró Visual Studio Build Tools."
}

$devcmd = Join-Path $vs "Common7\Tools\VsDevCmd.bat"
if (-not (Test-Path $devcmd)) {
    throw "No se encontró VsDevCmd.bat en $vs"
}

$common = "`"$src\core\mmu.cpp`" `"$src\core\mbc.cpp`" `"$src\core\rom_loader.cpp`" `"$src\core\apu.cpp`""
$commonPpu = "$common `"$src\core\ppu.cpp`""
$commonCpu = "$commonPpu `"$src\core\cpu.cpp`""
$commonWild = "$common `"$src\core\wild_table.cpp`""

$compileMmu = "`"$devcmd`" -arch=x64 -no_logo && cd /d `"$out`" && " +
              "cl /nologo /std:c++17 /EHsc " +
              "/I`"$PSScriptRoot\stub`" /I`"$src`" " +
              "`"$PSScriptRoot\test_mmu.cpp`" $common /Fe:test_mmu.exe"

$compileSav = "`"$devcmd`" -arch=x64 -no_logo && cd /d `"$out`" && " +
              "cl /nologo /std:c++17 /EHsc " +
              "/I`"$PSScriptRoot\stub`" /I`"$src`" " +
              "`"$PSScriptRoot\test_sav.cpp`" $common /Fe:test_sav.exe"

$compilePpu = "`"$devcmd`" -arch=x64 -no_logo && cd /d `"$out`" && " +
              "cl /nologo /std:c++17 /EHsc " +
              "/I`"$PSScriptRoot\stub`" /I`"$src`" " +
              "`"$PSScriptRoot\test_cgb_ppu.cpp`" $commonPpu /Fe:test_cgb_ppu.exe"

$compileCpu = "`"$devcmd`" -arch=x64 -no_logo && cd /d `"$out`" && " +
              "cl /nologo /std:c++17 /EHsc " +
              "/I`"$PSScriptRoot\stub`" /I`"$src`" " +
              "`"$PSScriptRoot\test_lr35902.cpp`" $commonCpu /Fe:test_lr35902.exe"

$compileWild = "`"$devcmd`" -arch=x64 -no_logo && cd /d `"$out`" && " +
               "cl /nologo /std:c++17 /EHsc " +
               "/I`"$PSScriptRoot\stub`" /I`"$src`" " +
               "`"$PSScriptRoot\test_wildtable.cpp`" $commonWild /Fe:test_wildtable.exe"

# cl escribe avisos por stderr; con Stop, PowerShell los trataría como excepción.
$ErrorActionPreference = "Continue"

cmd /c $compileMmu
if ($LASTEXITCODE -ne 0) {
    throw "La compilación de test_mmu falló."
}

cmd /c $compileSav
if ($LASTEXITCODE -ne 0) {
    throw "La compilación de test_sav falló."
}

cmd /c $compilePpu
if ($LASTEXITCODE -ne 0) {
    throw "La compilación de test_cgb_ppu falló."
}

cmd /c $compileCpu
if ($LASTEXITCODE -ne 0) {
    throw "La compilación de test_lr35902 falló."
}

cmd /c $compileWild
if ($LASTEXITCODE -ne 0) {
    throw "La compilación de test_wildtable falló."
}

& (Join-Path $out "test_mmu.exe")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& (Join-Path $out "test_sav.exe") $out
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& (Join-Path $out "test_cgb_ppu.exe")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& (Join-Path $out "test_lr35902.exe") $out
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& (Join-Path $out "test_wildtable.exe") $out
exit $LASTEXITCODE
