#include "core/apu.h"
#include <algorithm>
#include <cmath>

namespace gpsquest96 {

static const uint8_t DUTY_PATTERNS[4][8] = {
    {0, 0, 0, 0, 0, 0, 0, 1}, // 12.5%
    {1, 0, 0, 0, 0, 0, 0, 1}, // 25%
    {1, 0, 0, 0, 0, 1, 1, 1}, // 50%
    {0, 1, 1, 1, 1, 1, 1, 0}  // 75%
};

Apu::Apu() {
    initialize();
}

Apu::~Apu() {
}

void Apu::initialize() {
    std::memset(regs_, 0, sizeof(regs_));
    std::memset(waveRam_, 0, sizeof(waveRam_));
    std::memset(sampleBuffer_, 0, sizeof(sampleBuffer_));

    apuEnabled_ = true;
    nr50_ = 0x77;
    nr51_ = 0xFF;

    ch1Enabled_ = false; ch1Timer_ = 0; ch1DutyPos_ = 0; ch1LengthTimer_ = 0;
    ch1Volume_ = 0; ch1EnvelopeTimer_ = 0; ch1Frequency_ = 0;
    ch1SweepTimer_ = 0; ch1SweepFreq_ = 0; ch1SweepEnabled_ = false;

    ch2Enabled_ = false; ch2Timer_ = 0; ch2DutyPos_ = 0; ch2LengthTimer_ = 0;
    ch2Volume_ = 0; ch2EnvelopeTimer_ = 0; ch2Frequency_ = 0;

    ch3Enabled_ = false; ch3Timer_ = 0; ch3SampleIdx_ = 0; ch3LengthTimer_ = 0;
    ch3VolumeShift_ = 0; ch3Frequency_ = 0;

    ch4Enabled_ = false; ch4Timer_ = 0; ch4Lfsr_ = 0x7FFF; ch4LengthTimer_ = 0;
    ch4Volume_ = 0; ch4EnvelopeTimer_ = 0;

    frameSequencerCycle_ = 0;
    frameSequencerStep_ = 0;
    sampleCycleAccumulator_ = 0.0;
    sampleReadIdx_ = 0;
    sampleWriteIdx_ = 0;
}

void Apu::reset() {
    initialize();
}

void Apu::step(int cycles) {
    if (!apuEnabled_) return;

    // Frame Sequencer (512 Hz -> 8192 ciclos CPU por paso)
    frameSequencerCycle_ += cycles;
    if (frameSequencerCycle_ >= 8192) {
        frameSequencerCycle_ -= 8192;
        clockFrameSequencer();
    }

    // Temporizador Canal 1
    if (ch1Enabled_) {
        ch1Timer_ -= cycles;
        if (ch1Timer_ <= 0) {
            ch1Timer_ += (2048 - ch1Frequency_) * 4;
            ch1DutyPos_ = (ch1DutyPos_ + 1) & 7;
        }
    }

    // Temporizador Canal 2
    if (ch2Enabled_) {
        ch2Timer_ -= cycles;
        if (ch2Timer_ <= 0) {
            ch2Timer_ += (2048 - ch2Frequency_) * 4;
            ch2DutyPos_ = (ch2DutyPos_ + 1) & 7;
        }
    }

    // Temporizador Canal 3
    if (ch3Enabled_) {
        ch3Timer_ -= cycles;
        if (ch3Timer_ <= 0) {
            ch3Timer_ += (2048 - ch3Frequency_) * 2;
            ch3SampleIdx_ = (ch3SampleIdx_ + 1) & 31;
        }
    }

    // Temporizador Canal 4
    if (ch4Enabled_) {
        ch4Timer_ -= cycles;
        if (ch4Timer_ <= 0) {
            int shift = (regs_[0x22] >> 4) & 0x0F;
            int divisorCode = regs_[0x22] & 0x07;
            static const int DIVISORS[8] = {8, 16, 32, 48, 64, 80, 96, 112};
            ch4Timer_ += (DIVISORS[divisorCode] << shift);

            int xorResult = (ch4Lfsr_ & 1) ^ ((ch4Lfsr_ >> 1) & 1);
            ch4Lfsr_ = (ch4Lfsr_ >> 1) | (xorResult << 14);
            if (regs_[0x22] & 0x08) { // 7-bit LFSR mode
                ch4Lfsr_ = (ch4Lfsr_ & ~0x40) | (xorResult << 6);
            }
        }
    }

    // Acumulador de muestras PCM @ 44100 Hz (4194304 / 44100 ≈ 95.109 ciclos CPU por muestra)
    sampleCycleAccumulator_ += cycles;
    while (sampleCycleAccumulator_ >= 95.109) {
        sampleCycleAccumulator_ -= 95.109;
        generateSample();
    }
}

void Apu::clockFrameSequencer() {
    frameSequencerStep_ = (frameSequencerStep_ + 1) & 7;
    // Step 0, 2, 4, 6: Length Counter
    if ((frameSequencerStep_ & 1) == 0) {
        clockLength();
    }
    // Step 2, 6: Sweep
    if (frameSequencerStep_ == 2 || frameSequencerStep_ == 6) {
        clockSweep();
    }
    // Step 7: Envelope
    if (frameSequencerStep_ == 7) {
        clockEnvelope();
    }
}

void Apu::clockLength() {
    // Ch 1
    if ((regs_[0x14] & 0x40) && ch1LengthTimer_ > 0) {
        if (--ch1LengthTimer_ == 0) ch1Enabled_ = false;
    }
    // Ch 2
    if ((regs_[0x19] & 0x40) && ch2LengthTimer_ > 0) {
        if (--ch2LengthTimer_ == 0) ch2Enabled_ = false;
    }
    // Ch 3
    if ((regs_[0x1E] & 0x40) && ch3LengthTimer_ > 0) {
        if (--ch3LengthTimer_ == 0) ch3Enabled_ = false;
    }
    // Ch 4
    if ((regs_[0x24] & 0x40) && ch4LengthTimer_ > 0) {
        if (--ch4LengthTimer_ == 0) ch4Enabled_ = false;
    }
}

void Apu::clockEnvelope() {
    // Ch 1
    if (ch1Enabled_ && (regs_[0x12] & 0x07)) {
        if (++ch1EnvelopeTimer_ >= (regs_[0x12] & 0x07)) {
            ch1EnvelopeTimer_ = 0;
            if (regs_[0x12] & 0x08) {
                if (ch1Volume_ < 15) ch1Volume_++;
            } else {
                if (ch1Volume_ > 0) ch1Volume_--;
            }
        }
    }
    // Ch 2
    if (ch2Enabled_ && (regs_[0x17] & 0x07)) {
        if (++ch2EnvelopeTimer_ >= (regs_[0x17] & 0x07)) {
            ch2EnvelopeTimer_ = 0;
            if (regs_[0x17] & 0x08) {
                if (ch2Volume_ < 15) ch2Volume_++;
            } else {
                if (ch2Volume_ > 0) ch2Volume_--;
            }
        }
    }
    // Ch 4
    if (ch4Enabled_ && (regs_[0x21] & 0x07)) {
        if (++ch4EnvelopeTimer_ >= (regs_[0x21] & 0x07)) {
            ch4EnvelopeTimer_ = 0;
            if (regs_[0x21] & 0x08) {
                if (ch4Volume_ < 15) ch4Volume_++;
            } else {
                if (ch4Volume_ > 0) ch4Volume_--;
            }
        }
    }
}

void Apu::clockSweep() {
    if (!ch1SweepEnabled_) return;
    int sweepPace = (regs_[0x10] >> 4) & 0x07;
    if (sweepPace == 0) return;

    if (++ch1SweepTimer_ >= sweepPace) {
        ch1SweepTimer_ = 0;
        int shift = regs_[0x10] & 0x07;
        if (shift > 0) {
            int delta = ch1SweepFreq_ >> shift;
            if (regs_[0x10] & 0x08) { // restar
                ch1SweepFreq_ -= delta;
            } else { // sumar
                ch1SweepFreq_ += delta;
            }
            if (ch1SweepFreq_ > 2047) {
                ch1Enabled_ = false;
            } else {
                ch1Frequency_ = ch1SweepFreq_;
            }
        }
    }
}

void Apu::generateSample() {
    double sampleL = 0.0;
    double sampleR = 0.0;

    // Ch 1
    if (ch1Enabled_) {
        int duty = (regs_[0x11] >> 6) & 0x03;
        int bit = DUTY_PATTERNS[duty][ch1DutyPos_];
        double sig = bit ? (ch1Volume_ / 15.0) : 0.0;
        if (nr51_ & 0x01) sampleR += sig;
        if (nr51_ & 0x10) sampleL += sig;
    }

    // Ch 2
    if (ch2Enabled_) {
        int duty = (regs_[0x16] >> 6) & 0x03;
        int bit = DUTY_PATTERNS[duty][ch2DutyPos_];
        double sig = bit ? (ch2Volume_ / 15.0) : 0.0;
        if (nr51_ & 0x02) sampleR += sig;
        if (nr51_ & 0x20) sampleL += sig;
    }

    // Ch 3
    if (ch3Enabled_ && (regs_[0x1A] & 0x80)) {
        uint8_t byte = waveRam_[ch3SampleIdx_ / 2];
        uint8_t nibble = (ch3SampleIdx_ & 1) ? (byte & 0x0F) : ((byte >> 4) & 0x0F);
        if (ch3VolumeShift_ > 0) {
            int val = nibble >> (ch3VolumeShift_ - 1);
            double sig = val / 15.0;
            if (nr51_ & 0x04) sampleR += sig;
            if (nr51_ & 0x40) sampleL += sig;
        }
    }

    // Ch 4
    if (ch4Enabled_) {
        int bit = (~ch4Lfsr_) & 1;
        double sig = bit ? (ch4Volume_ / 15.0) : 0.0;
        if (nr51_ & 0x08) sampleR += sig;
        if (nr51_ & 0x80) sampleL += sig;
    }

    // Normalizar y escalar a int16 (rango -32768..32767)
    double masterVolL = ((nr50_ >> 4) & 0x07) / 7.0;
    double masterVolR = (nr50_ & 0x07) / 7.0;

    int16_t outL = static_cast<int16_t>(std::clamp(sampleL * 0.25 * masterVolL * 32767.0, -32768.0, 32767.0));
    int16_t outR = static_cast<int16_t>(std::clamp(sampleR * 0.25 * masterVolR * 32767.0, -32768.0, 32767.0));

    int nextWrite = (sampleWriteIdx_ + 2) % (BUFFER_SIZE * 2);
    if (nextWrite != sampleReadIdx_) {
        sampleBuffer_[sampleWriteIdx_] = outL;
        sampleBuffer_[sampleWriteIdx_ + 1] = outR;
        sampleWriteIdx_ = nextWrite;
    }
}

int Apu::readSamples(int16_t* dst, int maxSamples) {
    if (dst == nullptr || maxSamples <= 0) return 0;
    int count = 0;
    while (sampleReadIdx_ != sampleWriteIdx_ && count < maxSamples) {
        dst[count++] = sampleBuffer_[sampleReadIdx_];
        sampleReadIdx_ = (sampleReadIdx_ + 1) % (BUFFER_SIZE * 2);
    }
    return count;
}

uint8_t Apu::read(uint16_t address) {
    if (address >= 0xFF30 && address <= 0xFF3F) {
        return waveRam_[address - 0xFF30];
    }
    if (address == 0xFF26) {
        uint8_t status = apuEnabled_ ? 0x80 : 0x00;
        if (ch1Enabled_) status |= 0x01;
        if (ch2Enabled_) status |= 0x02;
        if (ch3Enabled_) status |= 0x04;
        if (ch4Enabled_) status |= 0x08;
        return static_cast<uint8_t>(status | 0x70);
    }
    if (address >= 0xFF10 && address <= 0xFF3F) {
        return regs_[address - 0xFF10];
    }
    return 0xFF;
}

void Apu::write(uint16_t address, uint8_t value) {
    if (address >= 0xFF30 && address <= 0xFF3F) {
        waveRam_[address - 0xFF30] = value;
        return;
    }
    if (address < 0xFF10 || address > 0xFF3F) return;

    if (!apuEnabled_ && address != 0xFF26) {
        // Con APU apagada (NR52 bit 7 = 0), solo se permite escribir en NR52 (0xFF26)
        return;
    }

    regs_[address - 0xFF10] = value;

    switch (address) {
        // Ch 1 Trigger
        case 0xFF14:
            if (value & 0x80) {
                ch1Enabled_ = true;
                ch1LengthTimer_ = 64 - (regs_[0x11] & 0x3F);
                ch1Volume_ = (regs_[0x12] >> 4) & 0x0F;
                ch1EnvelopeTimer_ = 0;
                ch1Frequency_ = ((value & 0x07) << 8) | regs_[0x13];
                ch1SweepFreq_ = ch1Frequency_;
                ch1SweepTimer_ = 0;
                ch1SweepEnabled_ = ((regs_[0x10] >> 4) & 0x07) > 0 || (regs_[0x10] & 0x07) > 0;
            }
            break;
        // Ch 2 Trigger
        case 0xFF19:
            if (value & 0x80) {
                ch2Enabled_ = true;
                ch2LengthTimer_ = 64 - (regs_[0x16] & 0x3F);
                ch2Volume_ = (regs_[0x17] >> 4) & 0x0F;
                ch2EnvelopeTimer_ = 0;
                ch2Frequency_ = ((value & 0x07) << 8) | regs_[0x18];
            }
            break;
        // Ch 3 Trigger
        case 0xFF1E:
            if (value & 0x80) {
                ch3Enabled_ = true;
                ch3LengthTimer_ = 256 - regs_[0x1B];
                static const int SHIFTS[4] = {0, 1, 2, 3}; // 0%, 100%, 50%, 25%
                ch3VolumeShift_ = SHIFTS[(regs_[0x1C] >> 5) & 0x03];
                ch3Frequency_ = ((value & 0x07) << 8) | regs_[0x1D];
                ch3SampleIdx_ = 0;
            }
            break;
        // Ch 4 Trigger
        case 0xFF23:
            if (value & 0x80) {
                ch4Enabled_ = true;
                ch4LengthTimer_ = 64 - (regs_[0x20] & 0x3F);
                ch4Volume_ = (regs_[0x21] >> 4) & 0x0F;
                ch4EnvelopeTimer_ = 0;
                ch4Lfsr_ = 0x7FFF;
            }
            break;
        case 0xFF24: nr50_ = value; break;
        case 0xFF25: nr51_ = value; break;
        case 0xFF26:
            apuEnabled_ = (value & 0x80) != 0;
            if (!apuEnabled_) {
                std::memset(regs_, 0, sizeof(regs_));
                ch1Enabled_ = false;
                ch2Enabled_ = false;
                ch3Enabled_ = false;
                ch4Enabled_ = false;
            }
            break;
    }
}

} // namespace gpsquest96
