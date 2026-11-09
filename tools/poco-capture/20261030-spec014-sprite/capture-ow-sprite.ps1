param(
  [string]$Dir = "tools\poco-capture\20261030-spec014-sprite\fresh",
  [string]$Adb = "C:\Users\Miguel Caballero\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)
# 1) Dispara el dump del PPU desde el overlay y 2) trae frame+OAM+VRAM1+paleta,
# 3) localiza el bloque del jugador (OAM centrada, banco segun atributo) y
# 4) lo compara con el frame que dibuja la PPU.
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
& $Adb shell input swipe 92 203 92 203 250
Start-Sleep -Milliseconds 900
& $Adb shell input swipe 300 1160 300 1160 1300
Start-Sleep -Seconds 3
foreach ($n in @("ppu_frame.rgba", "ppu_oam.bin", "ppu_vram.bin", "ppu_vram1.bin", "ppu_obj_palette.bin", "ppu_regs.txt")) {
  $line = '"' + $Adb + '" exec-out run-as com.gpsquest96 cat "files/' + $n + '" > "' + (Join-Path $Dir $n) + '"'
  cmd /c $line
}
& $Adb shell input swipe 300 1160 300 1160 200  # cierra el overlay

$oam = [System.IO.File]::ReadAllBytes((Resolve-Path (Join-Path $Dir "ppu_oam.bin")))
$cx = 72; $cy = 64
$anchorX = 0; $anchorY = 0; $best = [int]::MaxValue
for ($i = 0; $i -lt 40; $i++) {
  $o = $i * 4
  $y = $oam[$o]; $x = $oam[$o + 1]
  if ($x -eq 0 -or $y -eq 0) { continue }
  $d = [math]::Abs(($x - 8) - $cx) + [math]::Abs(($y - 16) - $cy)
  if ($d -lt $best) { $best = $d; $anchorX = $x - 8; $anchorY = $y - 16; $anchorIndex = $i }
}
$base = -1; $minSy = [int]::MaxValue; $minSx = [int]::MaxValue; $pal = 0
for ($i = 0; $i -lt 40; $i++) {
  $o = $i * 4
  $y = $oam[$o]; $x = $oam[$o + 1]
  if ($x -eq 0 -or $y -eq 0) { continue }
  $sx = $x - 8; $sy = $y - 16
  if ([math]::Abs($sx - $anchorX) -gt 8 -or [math]::Abs($sy - $anchorY) -gt 8) { continue }
  if ($sy -lt $minSy -or ($sy -eq $minSy -and $sx -lt $minSx)) {
    $minSy = $sy; $minSx = $sx; $base = $oam[$o + 2]; $pal = $oam[$o + 3] -band 0x07
  }
}
"bloque jugador: base=$base pal=$pal TL=($minSx,$minSy) ancla=($anchorX,$anchorY)"
powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "compare-sprite.ps1") -Dir $Dir -Base $base -Palette $pal -FrameX $minSx -FrameY $minSy
