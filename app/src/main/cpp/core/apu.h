#ifndef GPSQUEST96_APU_H
#define GPSQUEST96_APU_H

#include <cstdint>
#include <cstring>
#include <vector>

namespace gpsquest96 {

/**
 * APU LR35902 / 8 bits a color (Fidelidad de Audio).
 * Emula los 4 canales de sonido de hardware:
 * - Ch 1: Onda Cuadrada con Frecuencia Sweep (NR10-NR14)
 * - Ch 2: Onda Cuadrada (NR21-NR24)
 * - Ch 3: Muestras de Onda de 32 nibbles / Wave RAM (NR30-NR34 + 0xFF30..0xFF3F)
 * - Ch 4: Ruido LFSR (NR41-NR44)
 *
 * Salida: Estéreo 16-bit PCM @ 44100 Hz.
 */
class Apu {
public:
    static constexpr int SAMPLE_RATE = 44100;
    static constexpr int BUFFER_SIZE = 4096;

    Apu();
    ~Apu();

    void initialize();
    void reset();

    /** Avanza la APU N ciclos de CPU (4.194304 MHz / 44100 Hz ≈ 95 ciclos por muestra) */
    void step(int cycles);

    /** Lectura de registros 0xFF10..0xFF3F */
    uint8_t read(uint16_t address);

    /** Escritura de registros 0xFF10..0xFF3F */
    void write(uint16_t address, uint8_t value);

    /** Extrae muestras PCM estéreo comprimidas en int16_t [L, R, L, R...] */
    int readSamples(int16_t* dst, int maxSamples);

private:
    // Registros E/S APU (0xFF10..0xFF3F)
    uint8_t regs_[0x30];
    uint8_t waveRam_[16];

    // Control maestro (0xFF26 NR52)
    bool apuEnabled_;
    uint8_t nr50_; // Volumen Maestro L/R
    uint8_t nr51_; // Asignación de canales L/R

    // Canal 1 (Square + Sweep)
    bool ch1Enabled_;
    int ch1Timer_;
    int ch1DutyPos_;
    int ch1LengthTimer_;
    int ch1Volume_;
    int ch1EnvelopeTimer_;
    int ch1Frequency_;
    int ch1SweepTimer_;
    int ch1SweepFreq_;
    bool ch1SweepEnabled_;

    // Canal 2 (Square)
    bool ch2Enabled_;
    int ch2Timer_;
    int ch2DutyPos_;
    int ch2LengthTimer_;
    int ch2Volume_;
    int ch2EnvelopeTimer_;
    int ch2Frequency_;

    // Canal 3 (Wave)
    bool ch3Enabled_;
    int ch3Timer_;
    int ch3SampleIdx_;
    int ch3LengthTimer_;
    int ch3VolumeShift_;
    int ch3Frequency_;

    // Canal 4 (Noise)
    bool ch4Enabled_;
    int ch4Timer_;
    uint16_t ch4Lfsr_;
    int ch4LengthTimer_;
    int ch4Volume_;
    int ch4EnvelopeTimer_;

    // Frame Sequencer (512 Hz)
    int frameSequencerCycle_;
    int frameSequencerStep_;

    // Generador de muestras PCM
    double sampleCycleAccumulator_;
    int16_t sampleBuffer_[BUFFER_SIZE * 2];
    int sampleReadIdx_;
    int sampleWriteIdx_;

    void clockFrameSequencer();
    void clockLength();
    void clockEnvelope();
    void clockSweep();
    void generateSample();
};

} // namespace gpsquest96

#endif // GPSQUEST96_APU_H
