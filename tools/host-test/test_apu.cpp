// Host test de la APU (diagnóstico del núcleo, spec 020; slice 1: fix).
// Mide el comportamiento real de `core/apu.cpp` con la API pública
// (step/write/read/readSamples), sin Android ni POCO.
//
// Lo que exige este test (valores hardware):
//  - Tasa de producción de muestras: 1 frame GB (70224 ciclos) = 738
//    muestras estéreo @ 44100 Hz (70224 / 95,109).
//  - Cadencia de drenaje: si Java drena 1 frame por frame no se pierde nada.
//  - Capacidad del anillo: 4095 frames estéreo (~93 ms); sin drenar, las
//    muestras que sobran se TIRAN en silencio (generateSample descarta).
//  - Reindexado (D-APU-1, slice 1): length/envelope/sweep/duty/volumen/DAC
//    leen NR10..NR44 con constantes con nombre, no regs_[0x10..0x24].
//    Length del ch1/ch2 corta en el paso 2 (16384 ciclos) solo con el
//    length-enable de NR14/NR19; envelope del ch1 sube a 546 en el paso 7
//    (57344 ciclos); sweep con overflow apaga el ch1 en el paso 2; ch3
//    (wave) suena con su nibble (546 a volumen 100%); ch4 (ruido) suena
//    con volumen de NR42.
//  - Ruido (D-APU-2, slice 1): periodo del LFSR divisor * 2^(shift+4):
//    con shift=7 y divisor 8, 256 pasos/s y 81 transiciones en el primer
//    segundo desde la semilla 0x7FFF (antes 16x más rápido: ~2048/s).
//  - Timers al disparar (D-APU-3, slice 1): el primer paso de duty/wave/
//    LFSR ocurre tras un periodo completo, no en el primer step().
//  - Sequencer y timers con `while` (D-APU-4, slice 1): un solo
//    step(16384) avanza dos pasos de sequencer (length corta igual).
//  - NR52 (D-APU-5, slice 1): al apagar, nr50_/nr51_ se limpian; al
//    reencender el volumen maestro es 0 hasta reescribir NR50.
//  - Frame sequencer: length (paso 2 = 16384 ciclos), envelope (paso 7 =
//    57344 ciclos) y sweep (paso 2) con sus cadencias hardware.
//
// No sustituye el gate POCO: el drenaje real (AudioTrack) se valida en el
// móvil. Si este test está verde y el audio se corta en el POCO, el
// problema está en el camino Java (GameEngine/AudioTrack), no en la APU.
#include "core/apu.h"

#include <cstdint>
#include <cstdio>

using gpsquest96::Apu;

static int failures = 0;
static int checks = 0;

#define CHECK(cond) do { \
    ++checks; \
    if (!(cond)) { \
        std::printf("FAIL line %d: %s\n", __LINE__, #cond); \
        ++failures; \
    } \
} while (0)

// Avanza N ciclos en pasos de 4, como el bucle real (Cpu::stepAndSync
// pasa 4-24 ciclos por llamada). La producción de muestras usa `while`
// y es independiente del tamaño del paso; el sequencer y los timers de
// canal también (D-APU-4), pero el paso de 4 sigue siendo el real.
static void stepCycles(Apu& apu, int cycles) {
    while (cycles > 0) {
        int chunk = cycles < 4 ? cycles : 4;
        apu.step(chunk);
        cycles -= chunk;
    }
}

// Drena todas las muestras pendientes. Devuelve nº de int16 leídos
// (2 por frame estéreo: L, R, L, R...).
static int drain(Apu& apu, int16_t* buf, int cap) {
    return apu.readSamples(buf, cap);
}

static int16_t maxL(const int16_t* buf, int n) {
    int16_t m = 0;
    for (int i = 0; i < n; i += 2) {
        if (buf[i] > m) m = buf[i];
    }
    return m;
}

// Nº de transiciones 0 <-> distinto-de-0 en el canal L.
static int countTransitions(const int16_t* buf, int n) {
    int t = 0;
    int prev = -1;
    for (int i = 0; i < n; i += 2) {
        int cur = buf[i] != 0 ? 1 : 0;
        if (prev >= 0 && cur != prev) ++t;
        prev = cur;
    }
    return t;
}

int main() {
    static int16_t buf[20000];

    // ── NR52 y estado inicial ──────────────────────────────────────────
    {
        Apu apu;
        CHECK(apu.read(0xFF26) == 0xF0); // APU on, ningún canal, | 0x70
        apu.write(0xFF26, 0x00);         // Apagar
        CHECK(apu.read(0xFF26) == 0x70);
        apu.step(70224);
        CHECK(drain(apu, buf, 20000) == 0); // Apagada: cero muestras
        apu.write(0xFF26, 0x80);         // Encender
        CHECK(apu.read(0xFF26) == 0xF0);
    }

    // ── Tasa de producción: 70224 ciclos = 738 frames estéreo ─────────
    {
        Apu apu;
        apu.step(70224);
        int n1 = drain(apu, buf, 20000);
        CHECK(n1 == 738 * 2); // 738 L + 738 R
        apu.step(70224);
        int n2 = drain(apu, buf, 20000);
        CHECK(n2 == 738 * 2); // sin deriva: 1476,79 acumulado -> 738
        std::printf("tasa: frame1=%d int16, frame2=%d int16 (esperado 1476)\n",
                    n1, n2);
    }

    // ── Cadencia: 10 frames drenando 1 por frame, cero pérdidas ────────
    // 738,397 muestras/frame: los drenajes alternan 738/739; el total
    // tras 10 frames es floor(7383,97) = 7383.
    {
        Apu apu;
        int total = 0;
        for (int f = 0; f < 10; f++) {
            apu.step(70224);
            int n = drain(apu, buf, 20000);
            CHECK(n == 738 * 2 || n == 739 * 2);
            total += n;
        }
        CHECK(total == 7383 * 2);
    }

    // ── Anillo: 6 frames sin drenar desbordan y tiran muestras ─────────
    {
        Apu apu;
        for (int f = 0; f < 6; f++) apu.step(70224); // 6*738 = 4428 frames
        int n = drain(apu, buf, 20000);
        // Capacidad real: 4095 frames (un hueco distingue lleno de vacío).
        CHECK(n == 4095 * 2);
        std::printf("anillo: 6 frames sin drenar -> %d int16 "
                    "(4428 producidos, 333 tirados)\n", n);
        // Tras drenar, el anillo vuelve a aceptar un frame entero.
        apu.step(70224);
        CHECK(drain(apu, buf, 20000) == 738 * 2);
    }

    // ── Frame sequencer: length del ch1 en el paso 2 (16384 ciclos) ────
    // Con el fix (D-APU-1), clockLength() lee el length-enable en NR14
    // (regs_[0x04]). Length load 63 -> timer 1: el tick del paso 2 lo
    // apaga. Sin length-enable, el canal sigue sonando.
    {
        Apu apu;
        apu.write(0xFF11, 0xBF); // duty 50%, length load 63
        apu.write(0xFF12, 0xF0); // vol 15, envelope off
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0xC0); // trigger + length enable, freq 0
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        stepCycles(apu, 16383);
        CHECK((apu.read(0xFF26) & 0x01) != 0); // antes del paso 2: on
        stepCycles(apu, 1); // 16384: paso 2 -> tick -> timer 1->0 -> off
        CHECK((apu.read(0xFF26) & 0x01) == 0);
        std::printf("length ch1: off a 16384 ciclos (enable leido de NR14)\n");
    }
    {
        Apu apu;
        apu.write(0xFF11, 0xBF);
        apu.write(0xFF12, 0xF0);
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0x80); // trigger SIN length enable
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        stepCycles(apu, 16384);
        CHECK((apu.read(0xFF26) & 0x01) != 0); // sin enable: sigue on
    }

    // ── Length del ch2 en el paso 2 (mismo reindexado, NR19) ───────────
    {
        Apu apu;
        apu.write(0xFF16, 0xBF); // duty 50%, length load 63
        apu.write(0xFF17, 0xF0);
        apu.write(0xFF18, 0x00);
        apu.write(0xFF19, 0xC0); // trigger + length enable
        CHECK((apu.read(0xFF26) & 0x02) != 0);
        stepCycles(apu, 16383);
        CHECK((apu.read(0xFF26) & 0x02) != 0);
        stepCycles(apu, 1); // 16384: paso 2 -> off
        CHECK((apu.read(0xFF26) & 0x02) == 0);
    }

    // ── Frame sequencer: envelope del ch1 en el paso 7 (57344 ciclos) ──
    // Con el fix, el envelope se lee de NR12: vol 0, subir, periodo 1.
    // El tick del paso 7 lo sube a 1 -> maxL 546 (1/15 * 0,25 * 32767).
    // El temporizador de duty arranca con un periodo completo (D-APU-3):
    // a 57344 ciclos dutyPos es 7 y el patrón 50% ahí es 1.
    {
        Apu apu;
        apu.write(0xFF11, 0x80); // duty 50%, length 64 (sin length enable)
        apu.write(0xFF12, 0x09); // vol 0, subir, periodo 1
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0x80); // trigger, freq 0
        stepCycles(apu, 57343);
        int n0 = drain(apu, buf, 20000);
        int m0 = maxL(buf, n0); // vol 0: silencio (capturar antes del 2º drenaje)
        CHECK(m0 == 0);
        stepCycles(apu, 200); // cruza 57344: paso 7 -> tick de envelope
        int n1 = drain(apu, buf, 20000);
        CHECK(maxL(buf, n1) == 546);
        std::printf("envelope ch1: maxL antes=%d, despues=%d (esperado 0 y 546)\n",
                    m0, maxL(buf, n1));
    }

    // ── Sweep: overflow en el primer tick apaga el ch1 ─────────────────
    // Con el fix, pace/shift se leen de NR10: pace 1, sumar, shift 1,
    // freq 2047 -> 2047 + 1023 > 2047 en el tick del paso 2.
    {
        Apu apu;
        apu.write(0xFF10, 0x11); // pace 1, sumar, shift 1
        apu.write(0xFF11, 0x80);
        apu.write(0xFF12, 0xF0);
        apu.write(0xFF13, 0xFF); // byte bajo de freq
        apu.write(0xFF14, 0x87); // trigger, freq alta 7 -> freq 2047
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        stepCycles(apu, 16383);
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        stepCycles(apu, 1); // 16384: paso 2 -> sweep: overflow -> off
        CHECK((apu.read(0xFF26) & 0x01) == 0);
        std::printf("sweep ch1: off a 16384 ciclos (pace/shift leidos de NR10)\n");
    }

    // ── Canal 3 (wave): nibble de la Wave RAM con volumen 100% ─────────
    // Con el fix, el DAC se lee de NR30 y el volumen de NR32.
    // Byte 0 = 0x10: nibble alto 1 (idx 0 -> 546), nibble bajo 0 (idx 1).
    // El temporizador de wave arranca con un periodo completo (D-APU-3):
    // idx 0 dura 4096 ciclos, no 4.
    {
        Apu apu;
        apu.write(0xFF30, 0x10); // byte 0: nibble alto 1, bajo 0
        apu.write(0xFF31, 0x00);
        apu.write(0xFF1A, 0x80); // DAC on
        apu.write(0xFF1B, 0x00); // length 256
        apu.write(0xFF1C, 0x20); // vol 100%
        apu.write(0xFF1D, 0x00);
        apu.write(0xFF1E, 0x80); // trigger, freq 0
        stepCycles(apu, 8192); // idx 0 (546) y luego idx 1 (0)
        int n = drain(apu, buf, 20000);
        CHECK(maxL(buf, n) == 546); // 1/15 * 0,25 * 32767
        CHECK(countTransitions(buf, n) >= 1); // hay cambio 546 -> 0
        std::printf("wave ch3: maxL=%d (esperado 546)\n", maxL(buf, n));
    }

    // ── Ruido (ch4): volumen de NR42 y periodo hardware del LFSR ───────
    // Con el fix, el trigger lee el volumen de NR42 y el periodo es
    // divisor * 2^(shift+4) (D-APU-2): shift 7, divisor 8 -> periodo
    // 16384 ciclos, 256 pasos/s. En el primer segundo desde la semilla
    // 0x7FFF hay exactamente 81 transiciones (simulado; la media a largo
    // plazo es ~128/s). Antes el canal era mudo (volumen leído de Wave
    // RAM) y el LFSR iba 16x rápido (~2048 transiciones/s).
    {
        Apu apu;
        apu.write(0xFF20, 0x00); // length 64
        apu.write(0xFF21, 0xF0); // vol 15, envelope off
        apu.write(0xFF22, 0x70); // shift 7, divisor 8
        apu.write(0xFF23, 0x80); // trigger
        int nonzero = 0;
        int transitions = 0;
        for (int k = 0; k < 20; k++) {
            stepCycles(apu, 209715); // ~0,05 s por pasada, sin desbordar
            int n = drain(apu, buf, 20000);
            for (int i = 0; i < n; i += 2) {
                if (buf[i] != 0) ++nonzero;
            }
            transitions += countTransitions(buf, n);
        }
        CHECK(nonzero > 0); // el ruido suena
        // 81 transiciones exactas en el primer segundo desde 0x7FFF; el
        // rango 50..210 distingue el periodo hardware del bug 16x
        // (~2048/s) sin ser frágil al muestreo.
        CHECK(transitions >= 50 && transitions <= 210);
        std::printf("ruido ch4: %d muestras distintas de cero, %d transiciones "
                    "en 1 s (esperado >0 y 81)\n", nonzero, transitions);
    }

    // ── D-APU-4: un solo step() grande avanza el sequencer ─────────────
    // Con `while`, step(16384) en UNA llamada hace dos pasos de
    // sequencer (0->1->2) y el length corta igual que con pasos de 4.
    {
        Apu apu;
        apu.write(0xFF11, 0xBF);
        apu.write(0xFF12, 0xF0);
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0xC0); // trigger + length enable, timer 1
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        apu.step(16384); // una sola llamada
        CHECK((apu.read(0xFF26) & 0x01) == 0);
    }

    // ── D-APU-3: el temporizador de duty arranca con un periodo ────────
    // Freq 0 -> periodo 8192 ciclos. Duty 50%: pos 0 = 1 (8191 en la
    // primera ventana), pos 1 = 0 (silencio en la segunda).
    {
        Apu apu;
        apu.write(0xFF11, 0x80); // duty 50%
        apu.write(0xFF12, 0xF0); // vol 15
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0x80); // trigger, freq 0
        stepCycles(apu, 8192);
        CHECK(maxL(buf, drain(apu, buf, 20000)) == 8191); // pos 0
        stepCycles(apu, 8192);
        CHECK(maxL(buf, drain(apu, buf, 20000)) == 0); // pos 1
    }

    // ── D-APU-5: NR52 apaga y limpia nr50_/nr51_ ──────────────────────
    // Tras apagar y reencender, el volumen maestro es 0 hasta reescribir
    // NR50: el ch1 dispara pero no suena. Sin el fix, nr50_ conservaría
    // 0x77 y sonaría a 8191.
    {
        Apu apu;
        apu.write(0xFF11, 0x80);
        apu.write(0xFF12, 0xF0);
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0x80);
        stepCycles(apu, 4096);
        CHECK(maxL(buf, drain(apu, buf, 20000)) > 0); // suena (NR50=0x77)
        apu.write(0xFF26, 0x00); // apagar
        apu.write(0xFF26, 0x80); // encender
        CHECK(apu.read(0xFF26) == 0xF0);
        CHECK(apu.read(0xFF24) == 0x00); // NR50 leído como 0
        apu.write(0xFF11, 0x80);
        apu.write(0xFF12, 0xF0);
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0x80);
        stepCycles(apu, 4096);
        CHECK(maxL(buf, drain(apu, buf, 20000)) == 0); // maestro a 0
    }

    std::printf("test_apu: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
