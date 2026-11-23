// Host test de la APU (diagnóstico del núcleo, spec 020).
// Mide el comportamiento real de `core/apu.cpp` con la API pública
// (step/write/read/readSamples), sin Android ni POCO.
//
// Lo que pincha este test:
//  - Tasa de producción de muestras: 1 frame GB (70224 ciclos) = 738
//    muestras estéreo @ 44100 Hz (70224 / 95,109).
//  - Cadencia de drenaje: si Java drena 1 frame por frame no se pierde nada.
//  - Capacidad del anillo: 4095 frames estéreo (~93 ms); sin drenar, las
//    muestras que sobran se TIRAN en silencio (generateSample descarta).
//  - HALLAZGO (diag 020): la lógica de la APU lee regs_[] con índices
//    +0x10 (p. ej. regs_[0x14] en vez de regs_[0x04]): write()/read()
//    guardan en address-0xFF10, pero length/envelope/sweep/duty/volumen
//    leen el registro de 0x10 más arriba. Consecuencia medida: ch3 y ch4
//    MUDOS siempre, ch1/ch2 con tono/volumen/envelope del canal vecino.
//    Cada bloque pincha el bug y lo confirma con una SONDA que escribe el
//    registro que el código lee de verdad; el slice de corrección cambia
//    los índices y estos checks pasarán a su valor hardware.
//  - Ruido (ch4): además del +0x10, el periodo del LFSR es divisor <<
//    shift, 16x más rápido que el hardware (divisor * 2^(shift+4));
//    no es medible por muestras mientras el canal es mudo, se medirá
//    tras el fix (objetivo ~128 transiciones/s con shift=7, divisor 8).
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
// pasa 4-24 ciclos por llamada). OBLIGATORIO para el sequencer: el frame
// sequencer y los temporizadores de canal usan `if` (no `while`): un solo
// step(N) grande solo avanza UN paso de sequencer y UN paso de duty por
// llamada, y las cadencias medidas saldrían mal. La producción de muestras
// sí usa `while` y es independiente del tamaño del paso.
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
    // BUG (diag 020): clockLength() lee el length-enable del ch1 en
    // regs_[0x14] (slot de NR50, 0xFF24) en vez de regs_[0x04] (NR14).
    // Sin escribir NR50, el length NUNCA corta el canal. Con NR50=0xC0
    // (bit 6), sí corta: eso prueba que el enable se lee del registro
    // equivocado (+0x10). En juegos reales NR50 suele ser 0x77 (bit 6 = 1),
    // así que el length del ch1 "funciona por accidente".
    {
        Apu apu;
        apu.write(0xFF11, 0xBF); // duty 50%, length load 63 (lo ignora: lee NR42)
        apu.write(0xFF12, 0xF0); // vol 15, envelope off
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0xC0); // trigger + length enable, freq 0
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        stepCycles(apu, 16384); // paso 2 -> tick de length (si el enable se leyera)
        CHECK((apu.read(0xFF26) & 0x01) != 0); // HOY: sigue on (bug)
        std::printf("length ch1: tras 16384 ciclos sigue on (enable leido "
                    "de NR50, no de NR14)\n");
    }
    {
        Apu apu;
        apu.write(0xFF24, 0xC0); // SONDA: NR50 con bit 6 (lo que lee el bug)
        apu.write(0xFF21, 0x3F); // SONDA: NR42 como length load (timer 1)
        apu.write(0xFF11, 0xBF);
        apu.write(0xFF12, 0xF0);
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0xC0); // trigger + length enable, freq 0
        stepCycles(apu, 16383);
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        stepCycles(apu, 1); // 16384: paso 2 -> tick -> timer 1->0 -> off
        CHECK((apu.read(0xFF26) & 0x01) == 0); // la sonda confirma el +0x10
    }

    // ── Frame sequencer: envelope del ch1 en el paso 7 (57344 ciclos) ──
    // BUG (diag 020): clockEnvelope() y el trigger del ch1 leen el
    // envelope en regs_[0x12] (slot de NR43, 0xFF22) en vez de regs_[0x02]
    // (NR12). Sin escribir NR43, el periodo es 0 y el volumen no se mueve.
    // La sonda escribe NR43=0x09 (subir, periodo 1) y el tick sí llega.
    {
        Apu apu;
        apu.write(0xFF11, 0x80); // duty 50%, length 64 (sin length enable)
        apu.write(0xFF12, 0x09); // vol 0, subir, periodo 1 (no lo lee nadie)
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0x80); // trigger, freq 0
        stepCycles(apu, 57343);
        int n0 = drain(apu, buf, 20000);
        CHECK(maxL(buf, n0) == 0); // vol 0: silencio
        stepCycles(apu, 200); // cruza 57344: paso 7 -> tick de envelope
        int n1 = drain(apu, buf, 20000);
        CHECK(maxL(buf, n1) == 0); // HOY: sigue 0 (envelope leído de NR43)
        std::printf("envelope ch1: maxL antes=%d, despues=%d "
                    "(esperado 0 y 546; hoy 0 y 0)\n",
                    maxL(buf, n0), maxL(buf, n1));
    }
    {
        Apu apu;
        apu.write(0xFF22, 0x09); // SONDA: NR43 con subir + periodo 1
        apu.write(0xFF21, 0x80); // SONDA: NR42 como duty (50%: bit 1 en pos 0)
        apu.write(0xFF11, 0x80);
        apu.write(0xFF12, 0x09);
        apu.write(0xFF13, 0x00);
        apu.write(0xFF14, 0x80);
        stepCycles(apu, 57343);
        CHECK(maxL(buf, drain(apu, buf, 20000)) == 0);
        stepCycles(apu, 200); // paso 7 -> tick -> vol 0->1
        int n1 = drain(apu, buf, 20000);
        // vol 1/15 * 0,25 * 32767 = 546. El temporizador de duty arranca
        // en 0 (primer paso inmediato): a 57344 ciclos dutyPos vuelve a 0
        // y el patrón 50% ahí es 1.
        CHECK(maxL(buf, n1) == 546); // la sonda confirma el +0x10
    }

    // ── Sweep: overflow en el primer tick apaga el ch1 ─────────────────
    // BUG (diag 020): clockSweep() lee pace/shift en regs_[0x10] (slot de
    // NR41, 0xFF20) en vez de regs_[0x00] (NR10). Sin escribir NR41, el
    // sweep está apagado y el ch1 no se apaga nunca. La sonda escribe
    // NR41=0x11 (pace 1, sumar, shift 1) y el overflow sí lo apaga.
    {
        Apu apu;
        apu.write(0xFF10, 0x11); // pace 1, sumar, shift 1 (no lo lee nadie)
        apu.write(0xFF11, 0x80);
        apu.write(0xFF12, 0xF0);
        apu.write(0xFF13, 0xFF); // byte bajo de freq (tampoco lo lee: usa NR44)
        apu.write(0xFF14, 0x87); // trigger, freq alta 7
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        stepCycles(apu, 16384); // paso 2 -> tick de sweep (si pace > 0)
        CHECK((apu.read(0xFF26) & 0x01) != 0); // HOY: sigue on (bug)
        std::printf("sweep ch1: tras 16384 ciclos sigue on (pace/shift "
                    "leidos de NR41, no de NR10)\n");
    }
    {
        Apu apu;
        apu.write(0xFF20, 0x11); // SONDA: NR41 con pace 1, sumar, shift 1
        apu.write(0xFF23, 0xFF); // SONDA: NR44 como byte bajo de freq (2047)
        apu.write(0xFF10, 0x11);
        apu.write(0xFF11, 0x80);
        apu.write(0xFF12, 0xF0);
        apu.write(0xFF13, 0xFF);
        apu.write(0xFF14, 0x87); // trigger, freq 2047
        stepCycles(apu, 16383);
        CHECK((apu.read(0xFF26) & 0x01) != 0);
        stepCycles(apu, 1); // 16384: paso 2 -> sweep: 2047+1023 > 2047 -> off
        CHECK((apu.read(0xFF26) & 0x01) == 0); // la sonda confirma el +0x10
    }

    // ── Canal 3 (wave): nibble de la Wave RAM con volumen 100% ─────────
    // BUG (diag 020): generateSample() lee el DAC en regs_[0x1A] (slot de
    // 0xFF2A, sin uso) en vez de regs_[0x0A] (NR30), y el trigger lee el
    // volumen en regs_[0x1C] (slot de 0xFF2C, sin uso) en vez de regs_[0x0C]
    // (NR32). Sin sondas, el ch3 NUNCA suena. Las sondas escriben 0xFF2A y
    // 0xFF2C y el canal suena: eso prueba el +0x10.
    {
        Apu apu;
        apu.write(0xFF30, 0x10); // byte 0: nibble alto 1, bajo 0
        apu.write(0xFF31, 0x00);
        apu.write(0xFF1A, 0x80); // DAC on (no lo lee nadie)
        apu.write(0xFF1B, 0x00); // length 256
        apu.write(0xFF1C, 0x20); // vol 100% (tampoco lo lee)
        apu.write(0xFF1D, 0x00);
        apu.write(0xFF1E, 0x80); // trigger, freq 0
        stepCycles(apu, 8192);
        int n = drain(apu, buf, 20000);
        CHECK(maxL(buf, n) == 0); // HOY: ch3 mudo (DAC leído de 0xFF2A)
        std::printf("wave ch3: maxL=%d (esperado 546; hoy 0)\n",
                    maxL(buf, n));
    }
    {
        Apu apu;
        apu.write(0xFF2A, 0x80); // SONDA: slot que lee el DAC
        apu.write(0xFF2C, 0x20); // SONDA: slot que lee el volumen (100%)
        apu.write(0xFF30, 0x11); // byte 0: nibbles 1,1 (idx 0 y 1 -> 546)
        apu.write(0xFF31, 0x00); // byte 1: nibbles 0,0 (idx 2 y 3 -> 0)
        apu.write(0xFF1A, 0x80);
        apu.write(0xFF1B, 0x00);
        apu.write(0xFF1C, 0x20);
        apu.write(0xFF1D, 0x00);
        apu.write(0xFF1E, 0x80);
        // El temporizador de wave arranca en 0 (primer paso inmediato):
        // idx 0 dura 4 ciclos (< 1 muestra), idx 1 dura hasta 4100.
        stepCycles(apu, 8192); // idx 1 (546) y luego idx 2 (0)
        int n = drain(apu, buf, 20000);
        CHECK(maxL(buf, n) == 546); // 1/15 * 0,25 * 32767
        CHECK(countTransitions(buf, n) >= 1); // hay cambio 546 -> 0
    }

    // ── Ruido (ch4): volumen siempre 0 -> canal mudo ───────────────────
    // BUG (diag 020): el trigger del ch4 lee el volumen en regs_[0x21]
    // (slot de 0xFF31, Wave RAM) en vez de regs_[0x11] (NR42). Los slots
    // 0x20-0x2F de regs_ NO se pueden escribir jamás (0xFF30-0xFF3F van a
    // waveRam_), así que ch4Volume_ es 0 siempre: el ruido NO suena nunca,
    // escriba lo que escriba el juego. No hay sonda que lo despierte; solo
    // el reindexado del fix lo arregla.
    // BUG 2 (solo lectura de código, no medible mientras el canal es mudo):
    // el periodo del LFSR es divisor << shift, 16x más rápido que el
    // hardware (divisor * 2^(shift+4)); se medirá tras el fix.
    {
        Apu apu;
        apu.write(0xFF20, 0x00); // length 64
        apu.write(0xFF21, 0xF0); // vol 15, envelope off (no lo lee nadie)
        apu.write(0xFF22, 0x70); // shift 7, divisor 8 (tampoco lo lee)
        apu.write(0xFF23, 0x80); // trigger
        int nonzero = 0;
        for (int k = 0; k < 10; k++) {
            stepCycles(apu, 419430); // ~0,1 s por pasada
            int n = drain(apu, buf, 20000);
            for (int i = 0; i < n; i += 2) {
                if (buf[i] != 0) ++nonzero;
            }
        }
        CHECK(nonzero == 0); // HOY: ch4 mudo (vol leído de Wave RAM)
        std::printf("ruido ch4: %d muestras distintas de cero en 1 s "
                    "(esperado >0; hoy 0)\n", nonzero);
    }

    std::printf("test_apu: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
