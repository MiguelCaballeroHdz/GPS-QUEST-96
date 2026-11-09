param([string]$Vram = "tools\poco-capture\20261030-spec014-sprite\ow\ppu_vram.bin", [int]$Base = 136)
$v = [System.IO.File]::ReadAllBytes((Resolve-Path $Vram))
$orders = @{
  "rowmajor (0,1 / 2,3)" = @(0,1,2,3)
  "colmajor (0,2 / 1,3)" = @(0,2,1,3)
  "flip-a   (0,1 / 3,2)" = @(0,1,3,2)
  "flip-b   (0,2 / 3,1)" = @(0,2,3,1)
}
$chars = @(' ', '.', '+', '#')
foreach ($k in $orders.Keys) {
  "--- $k ---"
  $ord = $orders[$k]
  for ($row = 0; $row -lt 16; $row++) {
    $quadRow = [math]::Floor($row / 8)
    $line = ""
    for ($col = 0; $col -lt 16; $col++) {
      $quadCol = [math]::Floor($col / 8)
      $q = $quadRow * 2 + $quadCol
      $t = $Base + $ord[$q]
      $off = $t * 16 + ($row % 8) * 2
      $lo = $v[$off]; $hi = $v[$off + 1]
      $bit = 7 - ($col % 8)
      $val = (((($hi -shr $bit) -band 1) -shl 1) -bor (($lo -shr $bit) -band 1))
      $line += $chars[$val]
    }
    $line
  }
}
