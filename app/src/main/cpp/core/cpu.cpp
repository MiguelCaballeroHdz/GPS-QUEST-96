#include "core/cpu.h"
#include "core/mmu.h"
#include "core/ppu.h"

#include <android/log.h>

#define LOG_TAG "GPSQuest96"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gpsquest96 {

Cpu::Cpu()
    : a_(0), b_(0), c_(0), d_(0), e_(0), f_(0), h_(0), l_(0),
      sp_(0), pc_(0), halted_(false), ime_(false), haltBug_(false),
      mmu_(nullptr), ppu_(nullptr) {
}

Cpu::~Cpu() {
}

void Cpu::initialize() {
    // Valores iniciales segÃºn especificaciÃ³n de hardware de 8 bits.
    // En CGB el arranque deja A=0x11; en DMG A=0x01. Algunos cartuchos
    // consultan ese valor ademÃ¡s del flag 0x143.
    a_ = (mmu_ != nullptr && mmu_->isCgbMode()) ? 0x11 : 0x01;
    b_ = 0x00;
    c_ = 0x13;
    d_ = 0x00;
    e_ = 0xD8;
    f_ = 0xB0;
    h_ = 0x01;
    l_ = 0x4D;
    sp_ = 0xFFFE;
    pc_ = 0x0100; // El juego comienza en 0x0100
    halted_ = false;
    ime_ = false;
    haltBug_ = false;

    LOGI("CPU LR35902 inicializado (%s)",
         (mmu_ != nullptr && mmu_->isCgbMode()) ? "CGB" : "DMG");
}

int Cpu::step() {
    if (mmu_ == nullptr) {
        return 4; // Sin MMU: ciclos muertos sin tocar memoria ni colgar
    }
    if (halted_) {
        // HALT sale con interrupciÃ³n pendiente (IME activado o no).
        if (!hasPendingInterrupt()) {
            // DIV/TIMA van enganchados al SYSCLK (4.194304 MHz): a doble
            // velocidad NO se aceleran, solo consume la mitad de ticks por
            // M-cycle (spec 013 RF-02). toPpuCycles() = ticks de SYSCLK.
            mmu_->updateTimers(toPpuCycles(4));
            return 4;
        }
        halted_ = false;
        // Hardware: si IME=1 la IRQ se sirve ANTES de ejecutar otra
        // instrucciÃ³n (no despuÃ©s, como hacÃ­a el orden anterior).
        if (ime_) {
            int irq = checkInterrupts();
            if (irq > 0) return irq;
        }
    }

    const uint16_t pcBefore = pc_;
    uint8_t opcode = fetchByte();
    // El prefijo CB lleva un segundo byte: se mira sin consumirlo para
    // poder devolver los ciclos exactos (8/12/16).
    uint8_t cbOpcode = (opcode == 0xCB) ? readByte(pc_) : 0x00;

    // Opcode invÃ¡lido: executeOpcode lo ignora con LOGE (rutina de escape:
    // PC ya avanzÃ³, se devuelven 4 ciclos y el hilo jamÃ¡s se cuelga).
    executeOpcode(opcode);

    // Condicionales: no cuestan lo mismo tomadas que no tomadas.
    bool taken = false;
    switch (opcode) {
        case 0x20: case 0x28: case 0x30: case 0x38: // JR cc
            taken = (pc_ != static_cast<uint16_t>(pcBefore + 2));
            break;
        case 0xC2: case 0xCA: case 0xD2: case 0xDA: // JP cc
        case 0xC4: case 0xCC: case 0xD4: case 0xDC: // CALL cc
            taken = (pc_ != static_cast<uint16_t>(pcBefore + 3));
            break;
        case 0xC0: case 0xC8: case 0xD0: case 0xD8: // RET cc
            taken = (pc_ != static_cast<uint16_t>(pcBefore + 1));
            break;
        default:
            break;
    }

    int cycles = getOpcodeCycles(opcode, cbOpcode, taken);
    // Timers con TICKS DE SYSCLK (spec 013 RF-02): el DIV/TIMA del hardware
    // no se aceleran con la doble velocidad (siempre van al 4.194304 MHz),
    // asÃ­ que cada M-cycle aporta la mitad de ticks del SYSCLK en 2Ã—.
    // toPpuCycles() es exactamente esa cuenta (y la PPU va a la misma
    // cadencia SYSCLK que los timers: una sola aguja de reloj).
    mmu_->updateTimers(toPpuCycles(cycles));
    cycles += checkInterrupts(); // +20 si atiende IRQ (PPU en sync vÃ­a retorno)
    return cycles;
}

bool Cpu::hasPendingInterrupt() const {
    if (mmu_ == nullptr) return false;
    return (mmu_->getInterruptFlags() & mmu_->getInterruptEnable() & 0x1F) != 0;
}

int Cpu::checkInterrupts() {
    if (!ime_ || !hasPendingInterrupt()) return 0;
    uint8_t fired = mmu_->getInterruptFlags() & mmu_->getInterruptEnable() & 0x1F;
    for (int bit = 0; bit < 5; bit++) {
        if (fired & (1 << bit)) {
            static const uint16_t vectors[5] = {0x40, 0x48, 0x50, 0x58, 0x60};
            ime_ = false;
            mmu_->setInterruptFlags(mmu_->getInterruptFlags() & ~(1 << bit));
            push_r16(pc_);
            pc_ = vectors[bit];
            // La IRQ cuesta 20 ciclos de CPU; DIV/TIMA avanzan en TICKS de
            // SYSCLK (spec 013 RF-02: la mitad a doble velocidad).
            mmu_->updateTimers(toPpuCycles(20));
            return 20;
        }
    }
    return 0;
}

int Cpu::stepAndSync() {
    int cycles = step();
    if (ppu_ != nullptr) {
        ppu_->step(toPpuCycles(cycles));
    }
    return cycles;
}

int Cpu::toPpuCycles(int cycles) const {
    if (mmu_ != nullptr && mmu_->isDoubleSpeed()) {
        return cycles >> 1; // CPU a 2x: la PPU avanza la mitad por M-cycle
    }
    return cycles;
}

uint8_t Cpu::fetchByte() {
    if (haltBug_) {
        // HALT bug: el primer byte tras HALT se lee sin incrementar PC.
        haltBug_ = false;
        return readByte(pc_);
    }
    return readByte(pc_++);
}

uint16_t Cpu::fetchWord() {
    uint8_t lo = fetchByte();
    uint8_t hi = fetchByte();
    return (static_cast<uint16_t>(hi) << 8) | lo;
}

uint8_t Cpu::getReg8(uint8_t idx) {
    switch (idx & 0x07) {
        case 0: return b_;
        case 1: return c_;
        case 2: return d_;
        case 3: return e_;
        case 4: return h_;
        case 5: return l_;
        case 6: return readByte(getHL());
        case 7: return a_;
        default: return 0;
    }
}

void Cpu::setReg8(uint8_t idx, uint8_t val) {
    switch (idx & 0x07) {
        case 0: b_ = val; break;
        case 1: c_ = val; break;
        case 2: d_ = val; break;
        case 3: e_ = val; break;
        case 4: h_ = val; break;
        case 5: l_ = val; break;
        case 6: writeByte(getHL(), val); break;
        case 7: a_ = val; break;
    }
}

void Cpu::executeOpcode(uint8_t opcode) {
    // 0x40 - 0x7F: LD r, r' (except 0x76 = HALT)
    if (opcode >= 0x40 && opcode <= 0x7F) {
        if (opcode == 0x76) {
            // HALT bug: con IME=0 y una IRQ ya pendiente, el hardware no
            // se detiene y relee el siguiente byte (PC no avanza).
            if (!ime_ && mmu_ != nullptr &&
                (mmu_->getInterruptFlags() & mmu_->getInterruptEnable() & 0x1F) != 0) {
                haltBug_ = true;
            } else {
                halted_ = true;
            }
        } else {
            setReg8((opcode >> 3) & 0x07, getReg8(opcode & 0x07));
        }
        return;
    }

    // 0x80 - 0xBF: ALU A, r
    if (opcode >= 0x80 && opcode <= 0xBF) {
        uint8_t val = getReg8(opcode & 0x07);
        switch ((opcode >> 3) & 0x07) {
            case 0: add_a(val); break;
            case 1: adc_a(val); break;
            case 2: sub_a(val); break;
            case 3: sbc_a(val); break;
            case 4: and_a(val); break;
            case 5: xor_a(val); break;
            case 6: or_a(val); break;
            case 7: cp_a(val); break;
        }
        return;
    }

    switch (opcode) {
        case 0x00: break; // NOP
        case 0x01: setBC(fetchWord()); break; // LD BC, n16
        case 0x02: writeByte(getBC(), a_); break; // LD (BC), A
        case 0x03: setBC(getBC() + 1); break; // INC BC
        case 0x04: inc_r8(b_); break; // INC B
        case 0x05: dec_r8(b_); break; // DEC B
        case 0x06: b_ = fetchByte(); break; // LD B, n8
        case 0x07: { // RLCA
            bool c = (a_ & 0x80) != 0;
            a_ = (a_ << 1) | (c ? 1 : 0);
            setFlagZ(false); setFlagN(false); setFlagH(false); setFlagC(c);
            break;
        }
        case 0x08: writeWord(fetchWord(), sp_); break; // LD (a16), SP
        case 0x09: { // ADD HL, BC
            uint32_t hl = getHL(); uint32_t bc = getBC(); uint32_t res = hl + bc;
            setFlagN(false); setFlagH((hl & 0x0FFF) + (bc & 0x0FFF) > 0x0FFF); setFlagC(res > 0xFFFF);
            setHL(res & 0xFFFF);
            break;
        }
        case 0x0A: a_ = readByte(getBC()); break; // LD A, (BC)
        case 0x0B: setBC(getBC() - 1); break; // DEC BC
        case 0x0C: inc_r8(c_); break; // INC C
        case 0x0D: dec_r8(c_); break; // DEC C
        case 0x0E: c_ = fetchByte(); break; // LD C, n8
        case 0x0F: { // RRCA
            bool c = (a_ & 0x01) != 0;
            a_ = (a_ >> 1) | (c ? 0x80 : 0);
            setFlagZ(false); setFlagN(false); setFlagH(false); setFlagC(c);
            break;
        }

        case 0x10: // STOP
        fetchByte(); // byte de relleno
        if (mmu_ != nullptr) {
            // En CGB, STOP completa el cambio de velocidad armado por KEY1.
            // En DMG se conserva el comportamiento anterior (no se cuelga).
            mmu_->trySpeedSwitch();
            // Hardware: STOP resetea DIV (y con Ã©l el prescaler visible) en
            // ambos modos. Sin esto, los juegos que usan STOP para temporizar
            // ven un desfase de hasta 255 ciclos tras el cambio de velocidad.
            mmu_->write(0xFF04, 0);
        }
        break;
        case 0x11: setDE(fetchWord()); break; // LD DE, n16
        case 0x12: writeByte(getDE(), a_); break; // LD (DE), A
        case 0x13: setDE(getDE() + 1); break; // INC DE
        case 0x14: inc_r8(d_); break; // INC D
        case 0x15: dec_r8(d_); break; // DEC D
        case 0x16: d_ = fetchByte(); break; // LD D, n8
        case 0x17: { // RLA
            bool oldC = getFlagC(); bool newC = (a_ & 0x80) != 0;
            a_ = (a_ << 1) | (oldC ? 1 : 0);
            setFlagZ(false); setFlagN(false); setFlagH(false); setFlagC(newC);
            break;
        }
        case 0x18: jr_n8(); break; // JR n8
        case 0x19: { // ADD HL, DE
            uint32_t hl = getHL(); uint32_t de = getDE(); uint32_t res = hl + de;
            setFlagN(false); setFlagH((hl & 0x0FFF) + (de & 0x0FFF) > 0x0FFF); setFlagC(res > 0xFFFF);
            setHL(res & 0xFFFF);
            break;
        }
        case 0x1A: a_ = readByte(getDE()); break; // LD A, (DE)
        case 0x1B: setDE(getDE() - 1); break; // DEC DE
        case 0x1C: inc_r8(e_); break; // INC E
        case 0x1D: dec_r8(e_); break; // DEC E
        case 0x1E: e_ = fetchByte(); break; // LD E, n8
        case 0x1F: { // RRA
            bool oldC = getFlagC(); bool newC = (a_ & 0x01) != 0;
            a_ = (a_ >> 1) | (oldC ? 0x80 : 0);
            setFlagZ(false); setFlagN(false); setFlagH(false); setFlagC(newC);
            break;
        }

        case 0x20: { int8_t off = static_cast<int8_t>(fetchByte()); if (!getFlagZ()) pc_ += off; break; } // JR NZ, n8
        case 0x21: setHL(fetchWord()); break; // LD HL, n16
        case 0x22: writeByte(getHL(), a_); setHL(getHL() + 1); break; // LD (HL+), A
        case 0x23: setHL(getHL() + 1); break; // INC HL
        case 0x24: inc_r8(h_); break; // INC H
        case 0x25: dec_r8(h_); break; // DEC H
        case 0x26: h_ = fetchByte(); break; // LD H, n8
        case 0x27: { // DAA
            uint16_t val = a_;
            if (!getFlagN()) {
                if (getFlagH() || (val & 0x0F) > 9) val += 0x06;
                if (getFlagC() || val > 0x9F) { val += 0x60; setFlagC(true); }
            } else {
                if (getFlagH()) val = (val - 6) & 0xFF;
                if (getFlagC()) val -= 0x60;
            }
            a_ = val & 0xFF; setFlagZ(a_ == 0); setFlagH(false);
            break;
        }
        case 0x28: { int8_t off = static_cast<int8_t>(fetchByte()); if (getFlagZ()) pc_ += off; break; } // JR Z, n8
        case 0x29: { // ADD HL, HL
            uint32_t hl = getHL(); uint32_t res = hl + hl;
            setFlagN(false); setFlagH((hl & 0x0FFF) + (hl & 0x0FFF) > 0x0FFF); setFlagC(res > 0xFFFF);
            setHL(res & 0xFFFF);
            break;
        }
        case 0x2A: a_ = readByte(getHL()); setHL(getHL() + 1); break; // LD A, (HL+)
        case 0x2B: setHL(getHL() - 1); break; // DEC HL
        case 0x2C: inc_r8(l_); break; // INC L
        case 0x2D: dec_r8(l_); break; // DEC L
        case 0x2E: l_ = fetchByte(); break; // LD L, n8
        case 0x2F: a_ = ~a_; setFlagN(true); setFlagH(true); break; // CPL

        case 0x30: { int8_t off = static_cast<int8_t>(fetchByte()); if (!getFlagC()) pc_ += off; break; } // JR NC, n8
        case 0x31: sp_ = fetchWord(); break; // LD SP, n16
        case 0x32: writeByte(getHL(), a_); setHL(getHL() - 1); break; // LD (HL-), A
        case 0x33: sp_++; break; // INC SP
        case 0x34: { uint8_t val = readByte(getHL()); inc_r8(val); writeByte(getHL(), val); break; } // INC (HL)
        case 0x35: { uint8_t val = readByte(getHL()); dec_r8(val); writeByte(getHL(), val); break; } // DEC (HL)
        case 0x36: writeByte(getHL(), fetchByte()); break; // LD (HL), n8
        case 0x37: setFlagN(false); setFlagH(false); setFlagC(true); break; // SCF
        case 0x38: { int8_t off = static_cast<int8_t>(fetchByte()); if (getFlagC()) pc_ += off; break; } // JR C, n8
        case 0x39: { // ADD HL, SP
            uint32_t hl = getHL(); uint32_t sp = sp_; uint32_t res = hl + sp;
            setFlagN(false); setFlagH((hl & 0x0FFF) + (sp & 0x0FFF) > 0x0FFF); setFlagC(res > 0xFFFF);
            setHL(res & 0xFFFF);
            break;
        }
        case 0x3A: a_ = readByte(getHL()); setHL(getHL() - 1); break; // LD A, (HL-)
        case 0x3B: sp_--; break; // DEC SP
        case 0x3C: inc_r8(a_); break; // INC A
        case 0x3D: dec_r8(a_); break; // DEC A
        case 0x3E: a_ = fetchByte(); break; // LD A, n8
        case 0x3F: setFlagN(false); setFlagH(false); setFlagC(!getFlagC()); break; // CCF

        // 0xCB: Prefijo de 256 instrucciones extendidas
        case 0xCB: {
            uint8_t cb = fetchByte();
            uint8_t regIdx = cb & 0x07;
            uint8_t bit = (cb >> 3) & 0x07;
            uint8_t val = getReg8(regIdx);

            if (cb <= 0x07) { // RLC
                bool c = (val & 0x80) != 0; val = (val << 1) | (c ? 1 : 0);
                setFlagZ(val == 0); setFlagN(false); setFlagH(false); setFlagC(c); setReg8(regIdx, val);
            } else if (cb <= 0x0F) { // RRC
                bool c = (val & 0x01) != 0; val = (val >> 1) | (c ? 0x80 : 0);
                setFlagZ(val == 0); setFlagN(false); setFlagH(false); setFlagC(c); setReg8(regIdx, val);
            } else if (cb <= 0x17) { // RL
                bool oldC = getFlagC(); bool newC = (val & 0x80) != 0; val = (val << 1) | (oldC ? 1 : 0);
                setFlagZ(val == 0); setFlagN(false); setFlagH(false); setFlagC(newC); setReg8(regIdx, val);
            } else if (cb <= 0x1F) { // RR
                bool oldC = getFlagC(); bool newC = (val & 0x01) != 0; val = (val >> 1) | (oldC ? 0x80 : 0);
                setFlagZ(val == 0); setFlagN(false); setFlagH(false); setFlagC(newC); setReg8(regIdx, val);
            } else if (cb <= 0x27) { // SLA
                bool c = (val & 0x80) != 0; val = val << 1;
                setFlagZ(val == 0); setFlagN(false); setFlagH(false); setFlagC(c); setReg8(regIdx, val);
            } else if (cb <= 0x2F) { // SRA
                bool c = (val & 0x01) != 0; val = (val >> 1) | (val & 0x80);
                setFlagZ(val == 0); setFlagN(false); setFlagH(false); setFlagC(c); setReg8(regIdx, val);
            } else if (cb <= 0x37) { // SWAP
                val = ((val & 0x0F) << 4) | ((val & 0xF0) >> 4);
                setFlagZ(val == 0); setFlagN(false); setFlagH(false); setFlagC(false); setReg8(regIdx, val);
            } else if (cb <= 0x3F) { // SRL
                bool c = (val & 0x01) != 0; val = val >> 1;
                setFlagZ(val == 0); setFlagN(false); setFlagH(false); setFlagC(c); setReg8(regIdx, val);
            } else if (cb <= 0x7F) { // BIT
                setFlagZ((val & (1 << bit)) == 0); setFlagN(false); setFlagH(true);
            } else if (cb <= 0xBF) { // RES
                setReg8(regIdx, val & ~(1 << bit));
            } else { // SET
                setReg8(regIdx, val | (1 << bit));
            }
            break;
        }

        case 0xC0: if (!getFlagZ()) ret(); break; // RET NZ
        case 0xC1: { uint16_t val; pop_r16(val); setBC(val); break; } // POP BC
        case 0xC2: { uint16_t addr = fetchWord(); if (!getFlagZ()) pc_ = addr; break; } // JP NZ, a16
        case 0xC3: jp_n16(); break; // JP a16
        case 0xC4: { uint16_t addr = fetchWord(); if (!getFlagZ()) { push_r16(pc_); pc_ = addr; } break; } // CALL NZ, a16
        case 0xC5: push_r16(getBC()); break; // PUSH BC
        case 0xC6: add_a(fetchByte()); break; // ADD A, n8
        case 0xC7: push_r16(pc_); pc_ = 0x00; break; // RST 00H
        case 0xC8: if (getFlagZ()) ret(); break; // RET Z
        case 0xC9: ret(); break; // RET
        case 0xCA: { uint16_t addr = fetchWord(); if (getFlagZ()) pc_ = addr; break; } // JP Z, a16
        case 0xCC: { uint16_t addr = fetchWord(); if (getFlagZ()) { push_r16(pc_); pc_ = addr; } break; } // CALL Z, a16
        case 0xCD: call_n16(); break; // CALL a16
        case 0xCE: adc_a(fetchByte()); break; // ADC A, n8
        case 0xCF: push_r16(pc_); pc_ = 0x08; break; // RST 08H

        case 0xD0: if (!getFlagC()) ret(); break; // RET NC
        case 0xD1: { uint16_t val; pop_r16(val); setDE(val); break; } // POP DE
        case 0xD2: { uint16_t addr = fetchWord(); if (!getFlagC()) pc_ = addr; break; } // JP NC, a16
        case 0xD4: { uint16_t addr = fetchWord(); if (!getFlagC()) { push_r16(pc_); pc_ = addr; } break; } // CALL NC, a16
        case 0xD5: push_r16(getDE()); break; // PUSH DE
        case 0xD6: sub_a(fetchByte()); break; // SUB n8
        case 0xD7: push_r16(pc_); pc_ = 0x10; break; // RST 10H
        case 0xD8: if (getFlagC()) ret(); break; // RET C
        case 0xD9: ret(); ime_ = true; break; // RETI
        case 0xDA: { uint16_t addr = fetchWord(); if (getFlagC()) pc_ = addr; break; } // JP C, a16
        case 0xDC: { uint16_t addr = fetchWord(); if (getFlagC()) { push_r16(pc_); pc_ = addr; } break; } // CALL C, a16
        case 0xDE: sbc_a(fetchByte()); break; // SBC n8
        case 0xDF: push_r16(pc_); pc_ = 0x18; break; // RST 18H

        case 0xE0: writeByte(0xFF00 | fetchByte(), a_); break; // LDH (n8), A
        case 0xE1: { uint16_t val; pop_r16(val); setHL(val); break; } // POP HL
        case 0xE2: writeByte(0xFF00 | c_, a_); break; // LD (FF00+C), A
        case 0xE5: push_r16(getHL()); break; // PUSH HL
        case 0xE6: and_a(fetchByte()); break; // AND n8
        case 0xE7: push_r16(pc_); pc_ = 0x20; break; // RST 20H
        case 0xE8: { int8_t off = static_cast<int8_t>(fetchByte()); sp_ += off; setFlagZ(false); setFlagN(false); break; } // ADD SP, e8
        case 0xE9: pc_ = getHL(); break; // JP (HL)
        case 0xEA: writeByte(fetchWord(), a_); break; // LD (a16), A
        case 0xEE: xor_a(fetchByte()); break; // XOR n8
        case 0xEF: push_r16(pc_); pc_ = 0x28; break; // RST 28H

        // Traza quirÃºrgica B4: vigila LDH a 0xFF40-0xFF45.
        case 0xF0: a_ = readByte(0xFF00 | fetchByte()); break; // LDH A, (n8)

        case 0xF1: { uint16_t val; pop_r16(val); setAF(val); break; } // POP AF
        case 0xF2: a_ = readByte(0xFF00 | c_); break; // LD A, (FF00+C)
        case 0xF3: ime_ = false; break; // DI
        case 0xF5: push_r16(getAF()); break; // PUSH AF
        case 0xF6: or_a(fetchByte()); break; // OR n8
        case 0xF7: push_r16(pc_); pc_ = 0x30; break; // RST 30H
        case 0xF8: { int8_t off = static_cast<int8_t>(fetchByte()); setHL(sp_ + off); setFlagZ(false); setFlagN(false); break; } // LD HL, SP+e8
        case 0xF9: sp_ = getHL(); break; // LD SP, HL
        case 0xFA: a_ = readByte(fetchWord()); break; // LD A, (a16)
        case 0xFB: ime_ = true; break; // EI
        case 0xFE: cp_a(fetchByte()); break; // CP n8
        case 0xFF: push_r16(pc_); pc_ = 0x38; break; // RST 38H

        default: {
            // Opcodes ilegales del LR35902: NOP documentados, sin crash.
            switch (opcode) {
                case 0xD3: case 0xDB: case 0xDD: case 0xE3:
                case 0xE4: case 0xEB: case 0xEC: case 0xED:
                case 0xF4: case 0xFC: case 0xFD:
                    break;
                default: {
                    static int unkOpcodeCount = 0;
                    if (unkOpcodeCount++ % 1000 == 0) {
                        LOGE("Opcode no implementado: 0x%02X en PC=0x%04X (total %d)",
                             opcode, static_cast<unsigned>(pc_ - 1), unkOpcodeCount);
                    }
                    break;
                }
            }
        }
    }
}

// Ciclos reales del LR35902 en T-cycles (4 por M-cycle).
// `taken` solo aplica a JR/JP/CALL/RET condicionales.
int Cpu::getOpcodeCycles(uint8_t opcode, uint8_t cbOpcode, bool taken) {
    // Prefijo CB: 8 ciclos para registros; 12 para BIT (HL); 16 para
    // el resto de operaciones sobre (HL).
    if (opcode == 0xCB) {
        if ((cbOpcode & 0x07) == 0x06) {
            return (cbOpcode >= 0x40 && cbOpcode <= 0x7F) ? 12 : 16;
        }
        return 8;
    }

    switch (opcode) {
        case 0x00: return 4;                       // NOP
        case 0x76: return 4;                       // HALT

        // LD r16, n16
        case 0x01: case 0x11: case 0x21: case 0x31: return 12;
        case 0x08: return 20;                      // LD (a16), SP

        // INC/DEC r16
        case 0x03: case 0x13: case 0x23: case 0x33:
        case 0x0B: case 0x1B: case 0x2B: case 0x3B: return 8;

        // LD (BC/DE),A y LD A,(BC/DE)
        case 0x02: case 0x12: case 0x0A: case 0x1A: return 8;
        // LD (HL+/-),A y LD A,(HL+/-)
        case 0x22: case 0x32: case 0x2A: case 0x3A: return 8;

        // LD r8, n8
        case 0x06: case 0x0E: case 0x16: case 0x1E:
        case 0x26: case 0x2E: case 0x3E: return 8;
        case 0x36: return 12;                      // LD (HL), n8

        // INC/DEC r8
        case 0x04: case 0x0C: case 0x14: case 0x1C:
        case 0x24: case 0x2C: case 0x3C:
        case 0x05: case 0x0D: case 0x15: case 0x1D:
        case 0x25: case 0x2D: case 0x3D: return 4;
        case 0x34: case 0x35: return 12;           // INC/DEC (HL)

        // Rotaciones A y miscelÃ¡neos
        case 0x07: case 0x0F: case 0x17: case 0x1F: return 4;
        case 0x10: return 4;                       // STOP
        case 0x27: case 0x2F: case 0x37: case 0x3F: return 4;
        case 0x18: return 12;                      // JR n8
        case 0x20: case 0x28: case 0x30: case 0x38: return taken ? 12 : 8;

        // ADD HL, r16
        case 0x09: case 0x19: case 0x29: case 0x39: return 8;

        // LD r,r' (4)
        case 0x40: case 0x41: case 0x42: case 0x43:
        case 0x44: case 0x45: case 0x47:
        case 0x48: case 0x49: case 0x4A: case 0x4B:
        case 0x4C: case 0x4D: case 0x4F:
        case 0x50: case 0x51: case 0x52: case 0x53:
        case 0x54: case 0x55: case 0x57:
        case 0x58: case 0x59: case 0x5A: case 0x5B:
        case 0x5C: case 0x5D: case 0x5F:
        case 0x60: case 0x61: case 0x62: case 0x63:
        case 0x64: case 0x65: case 0x67:
        case 0x68: case 0x69: case 0x6A: case 0x6B:
        case 0x6C: case 0x6D: case 0x6F:
        case 0x78: case 0x79: case 0x7A: case 0x7B:
        case 0x7C: case 0x7D: case 0x7F: return 4;
        // LD r,(HL) (8)
        case 0x46: case 0x4E: case 0x56: case 0x5E:
        case 0x66: case 0x6E: case 0x7E: return 8;
        // LD (HL),r (8)
        case 0x70: case 0x71: case 0x72: case 0x73:
        case 0x74: case 0x75: case 0x77: return 8;

        // ALU A,r (4)
        case 0x80: case 0x81: case 0x82: case 0x83:
        case 0x84: case 0x85: case 0x87:
        case 0x88: case 0x89: case 0x8A: case 0x8B:
        case 0x8C: case 0x8D: case 0x8F:
        case 0x90: case 0x91: case 0x92: case 0x93:
        case 0x94: case 0x95: case 0x97:
        case 0x98: case 0x99: case 0x9A: case 0x9B:
        case 0x9C: case 0x9D: case 0x9F:
        case 0xA0: case 0xA1: case 0xA2: case 0xA3:
        case 0xA4: case 0xA5: case 0xA7:
        case 0xA8: case 0xA9: case 0xAA: case 0xAB:
        case 0xAC: case 0xAD: case 0xAF:
        case 0xB0: case 0xB1: case 0xB2: case 0xB3:
        case 0xB4: case 0xB5: case 0xB7:
        case 0xB8: case 0xB9: case 0xBA: case 0xBB:
        case 0xBC: case 0xBD: case 0xBF: return 4;
        // ALU A,(HL) (8)
        case 0x86: case 0x8E: case 0x96: case 0x9E:
        case 0xA6: case 0xAE: case 0xB6: case 0xBE: return 8;

        // Pila
        case 0xC1: case 0xD1: case 0xE1: case 0xF1: return 12; // POP
        case 0xC5: case 0xD5: case 0xE5: case 0xF5: return 16; // PUSH

        // Saltos y llamadas
        case 0xC3: return 16;                                 // JP a16
        case 0xC2: case 0xCA: case 0xD2: case 0xDA:
            return taken ? 16 : 12;                           // JP cc
        case 0xCD: return 24;                                 // CALL a16
        case 0xC4: case 0xCC: case 0xD4: case 0xDC:
            return taken ? 24 : 12;                           // CALL cc
        case 0xC9: case 0xD9: return 16;                      // RET / RETI
        case 0xC0: case 0xC8: case 0xD0: case 0xD8:
            return taken ? 20 : 8;                            // RET cc
        case 0xE9: return 4;                                  // JP (HL)
        case 0xF9: return 8;                                  // LD SP,HL
        case 0xE8: return 16;                                 // ADD SP,e8
        case 0xF8: return 12;                                 // LD HL,SP+e8

        // RST
        case 0xC7: case 0xCF: case 0xD7: case 0xDF:
        case 0xE7: case 0xEF: case 0xF7: case 0xFF: return 16;

        // ALU A, n8
        case 0xC6: case 0xCE: case 0xD6: case 0xDE:
        case 0xE6: case 0xEE: case 0xF6: case 0xFE: return 8;

        // LDH / LD (C) / LD (a16)
        case 0xE0: case 0xF0: return 12;                      // LDH
        case 0xE2: case 0xF2: return 8;                       // LD (C)
        case 0xEA: case 0xFA: return 16;                      // LD (a16)

        // Control de interrupciones
        case 0xF3: case 0xFB: return 4;                       // DI / EI

        // Ilegales del LR35902: NOP documentado
        default: return 4;
    }
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// INSTRUCCIONES LD (LOAD)
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

void Cpu::ld_r8_r8(uint8_t& dst, uint8_t src) {
    dst = src;
}

void Cpu::ld_r8_n8(uint8_t& dst) {
    dst = fetchByte();
}

void Cpu::ld_r16_n16(uint16_t& dst) {
    dst = fetchWord();
}

void Cpu::ld_a16_a() {
    uint16_t addr = fetchWord();
    writeByte(addr, a_);
}

void Cpu::ld_a_a16() {
    uint16_t addr = fetchWord();
    a_ = readByte(addr);
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// INSTRUCCIONES ALU
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

void Cpu::add_a(uint8_t value) {
    uint16_t result = a_ + value;
    setFlagZ((result & 0xFF) == 0);
    setFlagN(false);
    setFlagH((a_ & 0x0F) + (value & 0x0F) > 0x0F);
    setFlagC(result > 0xFF);
    a_ = result & 0xFF;
}

void Cpu::adc_a(uint8_t value) {
    uint8_t carry = getFlagC() ? 1 : 0;
    uint16_t result = a_ + value + carry;
    setFlagZ((result & 0xFF) == 0);
    setFlagN(false);
    setFlagH((a_ & 0x0F) + (value & 0x0F) + carry > 0x0F);
    setFlagC(result > 0xFF);
    a_ = result & 0xFF;
}

void Cpu::sub_a(uint8_t value) {
    uint16_t result = a_ - value;
    setFlagZ((result & 0xFF) == 0);
    setFlagN(true);
    setFlagH((a_ & 0x0F) < (value & 0x0F));
    setFlagC(a_ < value);
    a_ = result & 0xFF;
}

void Cpu::sbc_a(uint8_t value) {
    uint8_t carry = getFlagC() ? 1 : 0;
    int result = a_ - value - carry;
    setFlagZ((result & 0xFF) == 0);
    setFlagN(true);
    setFlagH(((a_ & 0x0F) - (value & 0x0F) - carry) < 0);
    setFlagC(result < 0);
    a_ = result & 0xFF;
}

void Cpu::and_a(uint8_t value) {
    a_ &= value;
    setFlagZ(a_ == 0);
    setFlagN(false);
    setFlagH(true);
    setFlagC(false);
}

void Cpu::or_a(uint8_t value) {
    a_ |= value;
    setFlagZ(a_ == 0);
    setFlagN(false);
    setFlagH(false);
    setFlagC(false);
}

void Cpu::xor_a(uint8_t value) {
    a_ ^= value;
    setFlagZ(a_ == 0);
    setFlagN(false);
    setFlagH(false);
    setFlagC(false);
}

void Cpu::cp_a(uint8_t value) {
    uint16_t result = a_ - value;
    setFlagZ((result & 0xFF) == 0);
    setFlagN(true);
    setFlagH((a_ & 0x0F) < (value & 0x0F));
    setFlagC(a_ < value);
}

void Cpu::inc_r8(uint8_t& reg) {
    reg++;
    setFlagZ(reg == 0);
    setFlagN(false);
    setFlagH((reg & 0x0F) == 0);
}

void Cpu::dec_r8(uint8_t& reg) {
    reg--;
    setFlagZ(reg == 0);
    setFlagN(true);
    setFlagH((reg & 0x0F) == 0x0F);
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// INSTRUCCIONES DE SALTO
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

void Cpu::jp_n16() {
    pc_ = fetchWord();
}

void Cpu::jr_n8() {
    int8_t offset = static_cast<int8_t>(fetchByte());
    pc_ += offset;
}

void Cpu::call_n16() {
    uint16_t addr = fetchWord();
    push_r16(pc_);
    pc_ = addr;
}

void Cpu::ret() {
    pop_r16(pc_);
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// INSTRUCCIONES DE PILA
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

void Cpu::push_r16(uint16_t value) {
    sp_--;
    writeByte(sp_, (value >> 8) & 0xFF);
    sp_--;
    writeByte(sp_, value & 0xFF);
}

void Cpu::pop_r16(uint16_t& reg) {
    reg = readByte(sp_);
    sp_++;
    reg |= static_cast<uint16_t>(readByte(sp_)) << 8;
    sp_++;
}

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
// UTILIDADES DE MEMORIA
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

uint8_t Cpu::readByte(uint16_t address) {
    if (mmu_ != nullptr) {
        return mmu_->read(address);
    }
    return 0;
}

void Cpu::writeByte(uint16_t address, uint8_t value) {
    if (mmu_ != nullptr) {
        mmu_->write(address, value);
    }
}

uint16_t Cpu::readWord(uint16_t address) {
    uint8_t lo = readByte(address);
    uint8_t hi = readByte(address + 1);
    return (static_cast<uint16_t>(hi) << 8) | lo;
}

void Cpu::writeWord(uint16_t address, uint16_t value) {
    writeByte(address, value & 0xFF);
    writeByte(address + 1, (value >> 8) & 0xFF);
}

} // namespace gpsquest96



