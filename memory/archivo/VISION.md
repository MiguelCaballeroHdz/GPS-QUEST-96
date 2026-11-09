# VISION.md — Definición final de GPS Quest Urbano

Fuente de verdad de producto. Cualquier mecánica nueva se mide contra este documento antes que contra prototipos o código congelado de post-game.

## Qué es

Un motor de exploración urbana y aventuras retro de 8/16 bits jugado en el mundo real. No utiliza tiendas integradas ni compras in-app.

Cargas los datos del juego original del usuario, te presenta la secuencia de introducción intacta, y cuando tu avatar inicia su aventura en el juego, tú exploras tu ciudad real — estés en Xalapa, Tokio, Berlín o Buenos Aires.

## Cómo funciona

**Generación procedural y lectura de datos.** Todo el contenido se genera a partir de los datos del juego original del usuario en tiempo real. No hay elementos propietarios ni datos no autorizados. Tiles, diálogos, objetos y eventos se procesan directamente desde los datos del usuario en el momento preciso de la aventura.

**Mapeo global dinámico y Navegación Pixel Art.** 
- **Mapa de la Ciudad (Navegación):** Muestra el mapa de tu ciudad real dibujado de forma **100% OPACA en estilo Pixel Art de 8 bits** utilizando los patrones gráficos de los datos del usuario (calles, áreas verdes, agua, estructuras). En este mapa se destacan los **Puntos de Interés (POIs), Personajes (PNJs) y Objetos** a los que debes trasladarte caminando.
- **Activación al llegar:** Al llegar físicamente a un punto marcado o activarse un evento de historia, la app conmuta a **Primer Plano Opaco a Pantalla Completa** para ejecutar la escena, enfrentamiento, puzle o diálogo original de los datos del usuario. Al concluir, regresa al mapa Pixel Art de la ciudad.
- **Modo Sofá / Sillón:** Pulsar el botón **CAMBIAR MODO** abre la aventura de forma 100% tradicional a pantalla completa con el Joystick virtual para jugar sin depender de la caminata GPS.
- **Seguridad en Entornos de Riesgo:** Geovallas automáticas a <20m de cuerpos de agua (fuerzan el Modo Sofá para evitar peligros) y pausa automática de pasos y eventos si la velocidad supera los 7 km/h (vehículos).

**Spawn progresivo y fiel.** Solo genera en tus desplazamientos reales los eventos y encuentros correspondientes al tramo exacto de tu avance en los datos del usuario. ¿Vas por el Tramo 1 en tu aventura? Aunque camines 10 km reales, solo verás encuentros del Tramo 1. Nada adelantado.

**Gating original.** Si físicamente te desplazas a una zona que en tu avance aún no está desbloqueada, no puedes interactuar con su contenido principal. Solo encuentros de ruta normales. Respeta la continuidad del avance original al pie de la letra.

## Propósito

Darle una razón real para volver a disfrutar juegos de aventura retro, obligándote a explorar tu ciudad real con un motor adaptativo de geolocalización.

## Dirigido a

Entusiastas de la exploración urbana, nostálgicos y jugadores que buscan experiencias de aventura libres de microtransacciones.

## Fase de ingeniería

- **Ahora:** Motor de 8 bits a color — paletas de color, doble velocidad, bancos de memoria visual/trabajo, transferencias por línea y paletas de sprites. Los datos de juego los aporta el usuario. Las características visuales avanzadas son parte de la fidelidad del motor.
- **Después:** Extensión de motor para arquitecturas de 32 bits. La visión las incluye; el núcleo actual no las ejecuta.

## Fuera de fase (post-game diferido)

Eventos especiales de nivel alto, modificaciones de encuentros raros por override, modos de dificultad escalados, trofeos adicionales, jefes ocultos y elementos narrativos secundarios **no forman parte del MVP**. Se retoman en actualizaciones futuras.

Ver `memory/archivo/POSTGAME.md` (inventario y gate) y `future.md` (ruta).
