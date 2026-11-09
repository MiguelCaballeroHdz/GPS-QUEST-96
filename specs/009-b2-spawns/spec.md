# spec.md — 009 B2 Generación de eventos desde datos del usuario

Estado: **APROBADO** 2026-10-27. Validado contra `PLAN.md`.

## 1. Visión y propósito

`PLAN.md` establece el bucle: *caminas en el mundo real → se activa el encuentro del tramo actual*, sin contenido inventado.

Esta spec define el comportamiento de generación de eventos B2:

1. Leer de los datos del usuario las tablas de encuentro del mapa actual.
2. Exponerlas al motor Java/nativo sin offsets adivinados.
3. Disparar el encuentro utilizando las rutinas del propio motor, no un inyector que escriba datos arbitrarios en memoria protegida.
4. No adelantar zonas: si el mapa actual no tiene tabla, no hay evento.

**Fidelidad:** La entidad (ID de especie), el nivel, la tasa de aparición y el reparto por período horarios salen de la tabla de los datos del usuario. No se inventan listas, niveles ni probabilidades.

### 1.1 Veredicto contra PLAN.md

| PLAN.md | Cómo lo cumple 009 |
|---|---|
| Lectura fiel; todo sale de los datos del usuario | Las tablas se localizan y parsean dinámicamente; no hay listas propias. |
| Inyección en desplazamientos reales del tramo actual | El disparo nace del paso real en `GPS_REAL` y usa el mapa actual como tramo. |
| Coherencia de tramo: sin tabla no hay evento | La tabla se elige por identificadores de mapa; un mapa sin tabla no produce eventos. |
| Gating original | Usar las rutinas y tablas normales de los datos del usuario. |
| Fidelidad absoluta | Se respetan los IDs y parámetros originales de los datos del usuario. |

## 2. Evidencia medida

| Hecho | Dato verificado |
|---|---|
| Datos analizados | Archivo de datos de 8 bits a color aportado por el usuario. |
| Mapa actual en WRAM | Identificadores de mapa de grupo, número y coordenadas locales. |
| Tabla Sector A terreno | Tablas de entradas con terminador de estructura. |
| Tramo 1 | Tasas por horario (mañana, día, noche) e IDs de entidades correspondientes. |
| Tabla Sector A agua | Entradas de agua estructuradas. |
| Rutinas del motor | Módulos de consulta y disparo del propio juego. |

Los parámetros anteriores son prueba de la estructura de datos, no una lista estática. El parser localiza las tablas por estructura y valida; si no las encuentra, devuelve vacío y lo registra. Nunca usa un offset a ciegas.

## 3. Requisitos funcionales

### RF-01 — Mapa actual
- Leer los identificadores de mapa de la WRAM en cada consulta.
- Si el mapa no tiene tabla de encuentro, el resultado es **sin evento**.

### RF-02 — Localizar tablas de los datos del usuario
- Soportar las tablas de terreno y agua del mapa actual.
- Terreno: entrada estructurada con tasas e IDs de entidades por período de tiempo.
- Agua: entrada estructurada con tasas e IDs correspondientes.
- Validar rangos de identificadores, mapas, niveles y tasas.
- Elegir las tablas con suficientes entradas válidas y descartar candidatos solapados.

### RF-03 — Exponer la tabla
- API nativa de solo lectura: `nativeGetWildTable(group, map, kind)` devuelve tasas y entidades de los tres momentos, o vacío.
- No modifica los datos del usuario, la memoria de guardado ni el estado del motor.

### RF-04 — Disparo fiel por desplazamiento real
- Solo en `GPS_REAL`, con paso humano validado (misma puerta del podómetro).
- En `SOFA` el usuario utiliza la exploración tradicional.
- Al completar un paso real, consultar la tabla del mapa actual.
- Si hay tabla, ejecutar la rutina del motor (`nativeTryWildEncounter()`).
- Respetar la tregua temporal de 180 s tras cada evento.
- Si el motor no está en el mapa de exploración (menú, batalla, texto, transición), no disparar.

### RF-05 — Gating por tramo
- El mapa actual es la única fuente de verdad del tramo. No se leen tablas de mapas futuros.

### RF-06 — Cero contenido propio
- No se añaden entidades, niveles, nombres, sprites ni tasas propias.
- Los IDs internos se usan tal cual; la UI no inventa nombres.

## 4. Requisitos no funcionales

- **Rendimiento:** La tabla se localiza una vez y se cachea; la consulta por paso es de complejidad constante $O(1)$.
- **Memoria:** Tablas compactas en RAM; sin copias innecesarias.
- **Aislamiento:** Lectura y parseo en C++ (`core/`), JNI fino; Java solo coordina.
- **Legalidad:** Los datos del usuario se leen localmente y no se distribuyen en el repositorio.

## 5. Criterios de aceptación

1. Pruebas de unidad nativas incluyen `test_wildtable` y verifican la localización de tablas.
2. `./gradlew assembleDebug --offline` → `EXIT=0`.
3. Dispositivo de prueba:
   - Caminar en un tramo con tabla dispara un encuentro perteneciente a la misma.
   - En interiores o zonas sin tabla no aparece ningún encuentro.
   - La partida de guardado continúa funcionando tras el evento.
4. No hay escrituras no autorizadas en memoria RAM protegida.

## 6. Fuera de alcance

- Gating por banderas de estado (005).
- Mapeo global dinámico (004).
- Eventos secundarios congelados (§8).
- Arquitecturas de 32 bits.
