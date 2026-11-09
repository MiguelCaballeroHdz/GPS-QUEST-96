#pragma once

// ÉPICO ARM (Fase GBA) — slice 1: tipos y declaración del ARM7TDMI.
// Sin cablear: ningún archivo existente lo incluye. No afecta al core GB.
// Siguiente slice: arm7.cpp (reset + fetch Thumb/ARM) y alta en CMake.

#include <cstdint>

#include "mmu32.h"

class Arm7 {
public:
    // Modos de operación (bits [4:0] del CPSR).
    enum Mode : uint8_t {
        USR = 0x10, FIQ = 0x11, IRQ = 0x12, SVC = 0x13,
        ABT = 0x17, UND = 0x1B, SYS = 0x1F
    };

    // Flags del CPSR.
    static constexpr uint32_t CPSR_N = 1u << 31;
    static constexpr uint32_t CPSR_Z = 1u << 30;
    static constexpr uint32_t CPSR_C = 1u << 29;
    static constexpr uint32_t CPSR_V = 1u << 28;
    static constexpr uint32_t CPSR_T = 1u << 5; // 1 = Thumb, 0 = ARM

    Arm7();
    void reset(uint32_t entryPoint);

    // Estado visible para la futura MMU32 (lectura/escritura por bus).
    uint32_t pc() const { return r_[15]; }
    bool thumb() const { return (cpsr_ & CPSR_T) != 0; }
    Mode mode() const { return static_cast<Mode>(cpsr_ & 0x1F); }

    // Un paso de instrucción con fetch real del bus (slice 5: Thumb;
    // modo ARM avanza como NOP hasta su decodificador).
    int step(Mmu32& bus);

private:
    void execThumb(uint16_t ins, Mmu32& bus);
    void aluFlagsAdd(uint32_t a, uint32_t b, uint32_t r);
    void aluFlagsSub(uint32_t a, uint32_t b, uint32_t r);
    uint32_t thumbPC() const { return (r_[15] & ~2u) + 4u; }

    uint32_t r_[16];      // r0-r12, sp(r13), lr(r14), pc(r15)
    uint32_t cpsr_;       // estado actual
    uint32_t spsr_[6];    // uno por modo con banco (FIQ/IRQ/SVC/ABT/UND/SYSthr)
    uint32_t bank_fiq_[7]; // r8-r14 bancados FIQ
    uint32_t bank_irq_[2]; // r13-r14 IRQ
    uint32_t bank_svc_[2]; // r13-r14 SVC
    uint32_t bank_abt_[2]; // r13-r14 ABT
    uint32_t bank_und_[2]; // r13-r14 UND
};
