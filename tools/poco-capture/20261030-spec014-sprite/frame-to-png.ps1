param([string]$Dir = "tools\poco-capture\20261030-spec014-sprite\fresh", [int]$Scale = 4)
Add-Type -AssemblyName System.Drawing
$frame = [System.IO.File]::ReadAllBytes((Resolve-Path (Join-Path $Dir "ppu_frame.rgba")))
$bmp = New-Object System.Drawing.Bitmap (160 * $Scale), (144 * $Scale)
for ($y = 0; $y -lt 144; $y++) {
  for ($x = 0; $x -lt 160; $x++) {
    $i = (($y * 160) + $x) * 4
    $c = [System.Drawing.Color]::FromArgb(255, $frame[$i], $frame[$i + 1], $frame[$i + 2])
    for ($dy = 0; $dy -lt $Scale; $dy++) {
      for ($dx = 0; $dx -lt $Scale; $dx++) {
        $bmp.SetPixel($x * $Scale + $dx, $y * $Scale + $dy, $c)
      }
    }
  }
}
$bmp.Save((Join-Path (Resolve-Path $Dir) "frame.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"ok"
