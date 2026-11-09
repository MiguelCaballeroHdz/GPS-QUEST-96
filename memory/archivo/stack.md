# stack.md - Stack Tecnológico Oficial de GPS Quest Urbano

Para mantener el proyecto extremadamente ligero y libre de dependencias pesadas comerciales, el desarrollo se restringe estrictamente a las siguientes tecnologías oficiales (API 26+):

## ☕ Capa de Aplicación e Interfaz (Java Nativo)
*   **Lenguaje Principal:** Java Nativo (Android SDK compatible con API 26+).
*   **Servicios en Primer Plano:** `Foreground Services` dedicados para la persistencia en segundo plano del rastreo GPS y la conexión Bluetooth GATT.
*   **Persistencia Local:** `SharedPreferences` para configuraciones rápidas de usuario (idioma base, preferencia de interfaz, caché de banderas de estado) y SQLite nativo si se requiere indexación masiva.
*   **Interfaz Gráfica:** Componentes nativos de Android (`SurfaceView`) y contextos optimizados mediante **OpenGL ES 2.0** para el dibujado eficiente de texturas y mapas pixel art a baja tasa de consumo de batería.

## 🔩 Capa de Motor y Bajo Nivel (Core Engine C++)
*   **Lenguaje NDK:** C/C++ nativo de bajo nivel (`cpu`, `mmu`, `ppu`, `apu`) compilado a través del Android NDK utilizando CMake.
*   **Procesamiento de Archivos:** Carga de datos completa en RAM al abrir (máx. 16 MB) y `memcpy` por bloque para el acceso del procesador. El acceso a memoria se optimiza para eficiencia de batería y frametime estable.
*   **Algoritmia:** Descompresión en memoria mediante rutinas compatibles con **LZ77** y mapeo dinámico de tablas de color indexadas de 15/16 bits.
*   **Norte de generación y lectura (`PLAN.md`):** Tiles, diálogos, objetos y eventos se leen o generan proceduralmente desde los datos de juego aportados por el usuario.

## 🗺️ Geolocalización, Sensores y Periféricos
*   **Mapas y Clima:** Estructuras de datos planas basadas en llamadas REST asíncronas hacia la API pública de **OpenStreetMap** (datos de geofencing y polígonos) y **Open-Meteo** (sincronización meteorológica).
*   **Sensores del Dispositivo:** Uso de `LocationManager` nativo para el GPS y el listener `Sensor.TYPE_STEP_COUNTER` acoplado al hardware interno del teléfono.
*   **Conectividad Periférica:** API nativa `android.bluetooth` (Bluetooth Low Energy GATT) para la sincronización con el accesorio periférico opcional.

## ❌ Propuestas de Frameworks Híbridos/Pesados: RECHAZADAS

Evaluadas y rechazadas: merman la compatibilidad y rendimiento de Android, descartan el motor C++ verificado en dispositivo por una reescritura innecesaria y chocan con las metas de rendimiento y representación del mundo estilo retro 8 bits.
