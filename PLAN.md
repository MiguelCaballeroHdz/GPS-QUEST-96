# PLAN - GPS Quest '96

Documento único de producto y ruta. Fusiona la visión, el post-juego y la ruta de los
documentos anteriores (originales en `memory/archivo/`). `RULES.md` manda en ingeniería.

## Visión
Motor de exploración urbana y aventuras retro de 8 bits jugado en el mundo real. Sin
tiendas integradas ni compras in-app.

- **Fidelidad absoluta:** tiles, diálogos, objetos y eventos salen de los datos que aporta
  el usuario, en el momento exacto de la aventura. Nada propio como contenido.
- **Mapeo global dinámico:** el mapa de la ciudad real se pinta opaco en pixel art de
  8 bits con patrones del usuario; POIs, PNJs y objetos se visitan caminando.
- **Activación al llegar:** al pisar un punto o disparar un evento, la app conmuta a
  pantalla completa para la escena original y vuelve al mapa.
- **Modo Sofá:** botón CAMBIAR MODO para jugar de forma tradicional con joystick virtual.
- **Seguridad:** geovallas a <20 m de agua fuerzan Modo Sofá; pausa si la velocidad
  supera 7 km/h.
- **Spawn progresivo:** solo encuentros del tramo de historia actual, aunque camines más.
- **Gating original:** zona no desbloqueada no interactúa; solo combates de ruta.
- **Fase actual:** motor de 8 bits a color (paletas, doble velocidad, bancos, HDMA).
  GBA (32 bits) diferido, con spec y core propios.

## Post-juego (congelado, no MVP)
Gate de apertura: checklist MVP completo + B2 + 004 + 005 cerradas + spec nueva aprobada.
Contenido diferido: desafíos de nivel alto tras el arco principal, encuentros especiales
repetibles en POIs, variantes raras de apariencia, NG+, logros, recompensas por
geolocalización, reciclaje de elementos y cierre narrativo secundario.
Prohibido mientras siga congelado: implementarlo o ampliarlo, cablearlo a la UI, usarlo
como norte, o escribir RAM protegida sin dump. Detalle: `memory/archivo/POSTGAME.md`.

## Futuro
- **016** arquitectura base genérica: slices 1-2 en el árbol; spec en BORRADOR. Llave de
  004 slices 3-5 y 009 slice 3.
- **009 slice 3:** inyección de spawns por paso real, sobre 016.
- **005** gating con flags reales: requiere el dump POCO (slice 5c).
- **004 slices 3-6b:** doble validación, `posicion`/`resueltos[]`, `enter`/`talk`
  automáticos y válvula de escape.
- **B4** cierre con foto del overworld; **APU** audio; timing fino CPU/PPU; RTC MBC3.
- **GBA** ARM7TDMI: spec propia y carpeta de core separada.

## Próximos pasos
1. Cerrar gates pendientes en POCO: cuarto de Azul (007), prueba destructiva de `.pueblo`
   (008) y foto del overworld (B4).
2. Aprobar 016 y ejecutar sus slices restantes.
3. Retomar 004 slice 3 en cuanto 016 esté; después 4, 5 y 6b.
4. Reimplementar 009 slice 3 sobre 016.
5. Triage de campo Task 28: modo inmersivo, sprite del entrenador, `WalkFilter`, caché de
   tiles, lienzo inicial y caminata con log en vivo.
