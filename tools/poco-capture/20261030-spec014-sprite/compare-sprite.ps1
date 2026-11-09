param(
  [string]$Dir = "tools\poco-capture\20261030-spec014-sprite\ow3",
  [int]$Base = 0,
  [int]$Palette = 0,
  [int]$Scale = 12,
  [int]$FrameX = 64,
  [int]$FrameY = 60
)
# Compara el sprite compuesto desde VRAM banco 1 contra el frame que
# dibuja el PPU en las mismas coordenadas. Si coinciden pixel a pixel, la
# composicion es fiel a la ROM.
Add-Type -AssemblyName System.Drawing
$vram = [System.IO.File]::ReadAllBytes((Resolve-Path (Join-Path $Dir "ppu_vram1.bin")))
$pal = [System.IO.File]::ReadAllBytes((Resolve-Path (Join-Path $Dir "ppu_obj_palette.bin")))
$frame = [System.IO.File]::ReadAllBytes((Resolve-Path (Join-Path $Dir "ppu_frame.rgba")))
$oam = [System.IO.File]::ReadAllBytes((Resolve-Path (Join-Path $Dir "ppu_oam.bin")))

function Cgb([int]$p, [int]$c) {
  $o = ($p * 8) + ($c * 2)
  $v = $pal[$o] -bor ($pal[$o + 1] -shl 8)
  $r = ($v -band 0x1F) * 255 / 31
  $g = (($v -shr 5) -band 0x1F) * 255 / 31
  $b = (($v -shr 10) -band 0x1F) * 255 / 31
  return [System.Drawing.Color]::FromArgb(255, [int]$r, [int]$g, [int]$b)
}

$W = 16 * $Scale
$bmp = New-Object System.Drawing.Bitmap ($W * 2 + 30), ($W + 40)
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.Clear([System.Drawing.Color]::FromArgb(255, 40, 40, 40))

# --- izquierda: composicion desde VRAM ---
for ($sub = 0; $sub -lt 4; $sub++) {
  $t = $Base + $sub
  $rowOff = [math]::Floor($sub / 2) * 8
  $colOff = ($sub % 2) * 8
  for ($row = 0; $row -lt 8; $row++) {
    $off = $t * 16 + $row * 2
    $lo = $vram[$off]; $hi = $vram[$off + 1]
    for ($col = 0; $col -lt 8; $col++) {
      $bit = 7 - $col
      $v = ((($hi -shr $bit) -band 1) -shl 1) -bor (($lo -shr $bit) -band 1)
      if ($v -eq 0) { continue }
      $brush = New-Object System.Drawing.SolidBrush (Cgb $Palette $v)
      $gfx.FillRectangle($brush, ($colOff + $col) * $Scale, 20 + ($rowOff + $row) * $Scale, $Scale, $Scale)
      $brush.Dispose()
    }
  }
}

# --- derecha: frame RGBA del PPU ---
$fbmp = New-Object System.Drawing.Bitmap 16, 16
for ($y = 0; $y -lt 16; $y++) {
  for ($x = 0; $x -lt 16; $x++) {
    $i = ((($FrameY + $y) * 160) + ($FrameX + $x)) * 4
    $fbmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $frame[$i], $frame[$i + 1], $frame[$i + 2]))
  }
}
$gfx.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$gfx.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$gfx.DrawImage($fbmp, ($W + 30), 20, $W, $W)

$font = New-Object System.Drawing.Font "Arial", 11
$gfx.DrawString("compose(base=$Base)", $font, [System.Drawing.Brushes]::White, 4, 0)
$gfx.DrawString("PPU frame ($FrameX,$FrameY)", $font, [System.Drawing.Brushes]::White, ($W + 34), 0)
$gfx.Dispose()

# --- diff numerico ---
$diff = 0
for ($y = 0; $y -lt 16; $y++) {
  for ($x = 0; $x -lt 16; $x++) {
    $sub = [math]::Floor($y / 8) * 2 + [math]::Floor($x / 8)
    $t = $Base + $sub
    $off = $t * 16 + ($y % 8) * 2
    $lo = $vram[$off]; $hi = $vram[$off + 1]
    $bit = 7 - ($x % 8)
    $v = ((($hi -shr $bit) -band 1) -shl 1) -bor (($lo -shr $bit) -band 1)
    $i = ((($FrameY + $y) * 160) + ($FrameX + $x)) * 4
    if ($v -ne 0) {
      $c = Cgb $Palette $v
      if ([math]::Abs($c.R - $frame[$i]) -gt 8 -or [math]::Abs($c.G - $frame[$i + 1]) -gt 8 -or [math]::Abs($c.B - $frame[$i + 2]) -gt 8) {
        $diff++
      }
    }
  }
}
$out = Join-Path (Resolve-Path $Dir) ("compare-base" + $Base + ".png")
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"pixeles opacos distintos de la ROM: $diff  -> $out"
