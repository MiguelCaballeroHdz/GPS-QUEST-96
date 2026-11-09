# vram-sheet.ps1 — evidencia visual de la VRAM (GB/GBC), sin inventar nada
#
# Modos:
#   Sheet  → hoja de contactos de los 384 tiles (16x24), con el índice de tilemap
#            real y borde rojo en los que usa la pantalla actual.
#   Screen → reconstruye la ventana visible 20x18 desde la tilemap de fondo
#            (sirve para comprobar que la dirección de tiles es la correcta:
#            debe parecerse a la captura del juego, sin sprites).
#
# Dirección de datos de tile según LCDC bit 4 (GB/GBC):
#   0 → 0x8800 con índices con signo (0..127 = 0x9000+i*16, 128..255 = 0x8800+…)
#   1 → 0x8000 con índices sin signo (0x8000+i*16)
#
# Ejemplo:
#   .\tools\vram-sheet.ps1 -Vram <dump>\ppu_vram.bin -Out <dump>\atlas-sheet.png -Bgp 0xE4 -Lcdc 0xE3
#   .\tools\vram-sheet.ps1 -Vram <dump>\ppu_vram.bin -Out <dump>\vram-screen.png -Mode Screen -Bgp 0xE4 -Lcdc 0xE3

param(
    [Parameter(Mandatory = $true)][string]$Vram,
    [Parameter(Mandatory = $true)][string]$Out,
    [ValidateSet('Sheet', 'Screen')][string]$Mode = 'Sheet',
    [int]$Bgp = 0xE4,
    [int]$Lcdc = 0xE3,
    [int]$Scx = 0,
    [int]$Scy = 0,
    [int]$Scale = 6
)

Add-Type -AssemblyName System.Drawing

$src = @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;

public static class VramSheetTool
{
    static readonly Color[] Shades = {
        Color.FromArgb(unchecked((int)0xFF9BBC0F)), Color.FromArgb(unchecked((int)0xFF8BAC0F)),
        Color.FromArgb(unchecked((int)0xFF306230)), Color.FromArgb(unchecked((int)0xFF0F380F))
    };

    public static string Run(string vramPath, string outPath, bool screen,
                             int bgp, int lcdc, int scx, int scy, int scale)
    {
        byte[] v = File.ReadAllBytes(vramPath);
        if (v.Length < 0x2000) throw new ArgumentException("VRAM corta: " + v.Length + " B");
        bool signed = (lcdc & 0x10) == 0;
        int mapBase = (lcdc & 0x08) != 0 ? 0x1C00 : 0x1800;
        int tileOut = 8 * scale;

        var used = new SortedSet<int>();
        for (int ry = 0; ry < 18; ry++)
            for (int cx = 0; cx < 20; cx++)
            {
                int mx = ((scx / 8) + cx) % 32;
                int my = ((scy / 8) + ry) % 32;
                used.Add(v[mapBase + my * 32 + mx]);
            }

        string report;
        if (screen)
        {
            using (var bmp = new Bitmap(20 * tileOut, 18 * tileOut))
            using (var g = Graphics.FromImage(bmp))
            {
                for (int ry = 0; ry < 18; ry++)
                    for (int cx = 0; cx < 20; cx++)
                    {
                        int mx = ((scx / 8) + cx) % 32;
                        int my = ((scy / 8) + ry) % 32;
                        DrawTile(g, v, signed, bgp, v[mapBase + my * 32 + mx],
                                 cx * tileOut, ry * tileOut, scale);
                    }
                bmp.Save(outPath, ImageFormat.Png);
            }
            report = string.Format(CultureInfo.InvariantCulture,
                "modo=Screen tilemap=0x{0:X4} signed={1} scale={2}",
                0x8000 + mapBase, signed, scale);
        }
        else
        {
            const int cols = 16, rows = 24, labelW = 34, pad = 4;
            int sheetW = labelW + cols * (tileOut + pad) + pad;
            int sheetH = rows * (tileOut + pad) + pad;
            using (var bmp = new Bitmap(sheetW, sheetH))
            using (var g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.FromArgb(unchecked((int)0xFF181818)));
                g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.SingleBitPerPixelGridFit;
                using (var font = new Font("Consolas", 8))
                using (var brush = new SolidBrush(Color.Gainsboro))
                using (var red = new Pen(Color.FromArgb(unchecked((int)0xFFFF4040)), 2))
                {
                    for (int t = 0; t < 384; t++)
                    {
                        int cx = t % cols, cy = t / cols;
                        int ox = labelW + pad + cx * (tileOut + pad);
                        int oy = pad + cy * (tileOut + pad);
                        DrawTile(g, v, signed, bgp, t, ox, oy, scale);
                        if (used.Contains(t)) g.DrawRectangle(red, ox - 1, oy - 1, tileOut + 1, tileOut + 1);
                        g.DrawString(t.ToString("X2"), font, brush, 2, oy + tileOut / 2 - 6);
                    }
                }
                bmp.Save(outPath, ImageFormat.Png);
            }
            var parts = new List<string>();
            foreach (int t in used)
            {
                int off = (signed && t < 128) ? 0x1000 + t * 16 : t * 16;
                parts.Add(string.Format(CultureInfo.InvariantCulture,
                    "0x{0:X2}->0x{1:X4}", t, 0x8000 + off));
            }
            report = string.Format(CultureInfo.InvariantCulture,
                "modo=Sheet tilemap=0x{0:X4} signed={1} tiles_usados={2}\n{3}",
                0x8000 + mapBase, signed, used.Count, string.Join(" ", parts.ToArray()));
        }
        report += "\nhoja: " + outPath;
        return report;
    }

    static void DrawTile(Graphics g, byte[] v, bool signed, int bgp,
                         int tile, int ox, int oy, int scale)
    {
        int off = (signed && tile < 128) ? 0x1000 + tile * 16 : tile * 16;
        for (int row = 0; row < 8; row++)
        {
            int lo = v[off + row * 2];
            int hi = v[off + row * 2 + 1];
            for (int col = 0; col < 8; col++)
            {
                int bit = 7 - col;
                int val = (((hi >> bit) & 1) << 1) | ((lo >> bit) & 1);
                int shade = (bgp >> (val * 2)) & 3;
                using (var b = new SolidBrush(Shades[shade]))
                    g.FillRectangle(b, ox + col * scale, oy + row * scale, scale, scale);
            }
        }
    }
}
'@

Add-Type -TypeDefinition $src -ReferencedAssemblies System.Drawing

$fullOut = Join-Path (Get-Location) $Out
$screenMode = ($Mode -eq 'Screen')
[VramSheetTool]::Run((Resolve-Path $Vram).Path, $fullOut, $screenMode, $Bgp, $Lcdc, $Scx, $Scy, $Scale)
