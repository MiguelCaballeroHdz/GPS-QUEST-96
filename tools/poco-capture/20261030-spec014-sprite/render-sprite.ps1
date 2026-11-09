param(
  [string]$Dir = "tools\poco-capture\20261030-spec014-sprite\ow3",
  [int]$Base = 0,
  [int]$Palette = 0,
  [int]$Scale = 12
)
Add-Type -AssemblyName System.Drawing
$vram = [System.IO.File]::ReadAllBytes((Resolve-Path (Join-Path $Dir "ppu_vram1.bin")))
$pal = [System.IO.File]::ReadAllBytes((Resolve-Path (Join-Path $Dir "ppu_obj_palette.bin")))

function Cgb([int]$p, [int]$c) {
  $o = ($p * 8) + ($c * 2)
  $v = $pal[$o] -bor ($pal[$o + 1] -shl 8)
  $r = ($v -band 0x1F) * 255 / 31
  $g = (($v -shr 5) -band 0x1F) * 255 / 31
  $b = (($v -shr 10) -band 0x1F) * 255 / 31
  return [System.Drawing.Color]::FromArgb(255, [int]$r, [int]$g, [int]$b)
}

$bmp = New-Object System.Drawing.Bitmap (16 * $Scale), (16 * $Scale)
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.Clear([System.Drawing.Color]::FromArgb(255, 90, 130, 80))
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
      $c = Cgb $Palette $v
      $brush = New-Object System.Drawing.SolidBrush $c
      $gfx.FillRectangle($brush, ($colOff + $col) * $Scale, ($rowOff + $row) * $Scale, $Scale, $Scale)
      $brush.Dispose()
    }
  }
}
$gfx.Dispose()
$out = Join-Path (Resolve-Path $Dir) ("sprite-bank1-base" + $Base + "-pal" + $Palette + ".png")
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"saved $out"
