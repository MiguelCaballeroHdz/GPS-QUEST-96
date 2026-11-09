# Artefactos movidos (cumplimiento legal)

Los **318 artefactos** derivados del juego que había en esta carpeta y sus subcarpetas se
retiraron del repositorio el **2026-11-08**:

| Tipo | Cantidad | Contenido |
|---|---|---|
| `.png` | 254 | Capturas de pantalla del juego |
| `.bin` | 46 | Volcados de VRAM/OAM/WRAM/paletas (gráficos del juego) |
| `.rgba` / `.raw` | 13 | Fotogramas crudos del framebuffer |
| `.mp4` | 3 | Vídeo de la caminata (40 MB) |
| `.zip` | 2 | Perfiles `.pueblo` con `battery.sav` |

- **Destino:** `C:\Dev\gps-quest-96-private\artefactos-legales-20261107\tools\poco-capture\`
- **Estructura preservada:** cada `evidence.md` conserva su ruta relativa; sus artefactos
  están en la misma ruta bajo el destino privado.
- **Qué queda aquí:** solo texto (`.md`, `.txt`, `.json`, `.ps1`) con conclusiones y
  trazabilidad. Los `evidence.md` no se editaron.
- **Artefactos futuros:** los que genere `tools/poco-test.ps1` deben revisarse y moverse
  antes de compartir el repositorio. `audit-legal.ps1` no inspecciona imágenes ni dumps.

**Motivo:** son material derivado de la obra del usuario. El repositorio debe contener
solo código, documentación y datos sintéticos.
