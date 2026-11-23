#!/bin/sh
# Host tests del core en Linux (equivalente de run.ps1, que exige VS cl.exe).
# Compila con g++ los .cpp REALES del core contra los stubs de
# tools/host-test/stub (android/log.h, renderer/renderer.h) y ejecuta:
#   test_mmu, test_sav, test_cgb_ppu, test_lr35902, test_wildtable,
#   test_apu (diagnóstico APU, spec 020) y test_timing (cadencia PPU).
# Uso: sh tools/host-test/run.sh   (sale con 1 al primer test que falle)
set -e

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$ROOT/app/src/main/cpp"
STUB="$ROOT/tools/host-test/stub"
OUT="$ROOT/tools/host-test/build"
mkdir -p "$OUT"

CXX=${CXX:-g++}
CXXFLAGS="-std=c++17 -O1 -Wall -I$STUB -I$SRC"

COMMON="$SRC/core/mmu.cpp $SRC/core/mbc.cpp $SRC/core/rom_loader.cpp $SRC/core/apu.cpp"
COMMON_PPU="$COMMON $SRC/core/ppu.cpp"
COMMON_CPU="$COMMON_PPU $SRC/core/cpu.cpp"
COMMON_WILD="$COMMON $SRC/core/wild_table.cpp $SRC/core/wild_routine.cpp"

echo "== test_mmu =="
$CXX $CXXFLAGS "$ROOT/tools/host-test/test_mmu.cpp" $COMMON -o "$OUT/test_mmu"
echo "== test_sav =="
$CXX $CXXFLAGS "$ROOT/tools/host-test/test_sav.cpp" $COMMON -o "$OUT/test_sav"
echo "== test_cgb_ppu =="
$CXX $CXXFLAGS "$ROOT/tools/host-test/test_cgb_ppu.cpp" $COMMON_PPU -o "$OUT/test_cgb_ppu"
echo "== test_lr35902 =="
$CXX $CXXFLAGS "$ROOT/tools/host-test/test_lr35902.cpp" $COMMON_CPU -o "$OUT/test_lr35902"
echo "== test_wildtable =="
if [ -f "$SRC/core/wild_routine.cpp" ]; then
  $CXX $CXXFLAGS "$ROOT/tools/host-test/test_wildtable.cpp" $COMMON_WILD -o "$OUT/test_wildtable"
  "$OUT/test_wildtable" "$OUT"
else
  # Base sin 009 slice 3: wild_routine.cpp no existe en este árbol.
  # El test vive en el PR #4 (009-slice-3); aquí se salta sin fallar.
  echo "SKIP test_wildtable: sin core/wild_routine.cpp en esta base"
fi
echo "== test_apu =="
$CXX $CXXFLAGS "$ROOT/tools/host-test/test_apu.cpp" "$SRC/core/apu.cpp" -o "$OUT/test_apu"
echo "== test_timing =="
$CXX $CXXFLAGS "$ROOT/tools/host-test/test_timing.cpp" $COMMON_PPU -o "$OUT/test_timing"

"$OUT/test_mmu"
"$OUT/test_sav" "$OUT"
"$OUT/test_cgb_ppu"
"$OUT/test_lr35902" "$OUT"
if [ -f "$OUT/test_wildtable" ]; then "$OUT/test_wildtable" "$OUT"; fi
"$OUT/test_apu"
"$OUT/test_timing"

echo "HOST TESTS C++ OK"
