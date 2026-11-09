// ÉPICO ARM (Fase GBA) — slice 5: fetch real + Thumb básico.
// Formatos: 1 (MOV#), 2 (ADD/SUB), 3 (MOV/CMP/ADD/SUB#8), 4 (ALU),
// 5 (Hi/BX), 6 (LDR literal), 7/8 (LDR/STR reg), 9 (LDR/STR/B/LDRH/STRH
// imm), 10 (LDRH reg), 11 (SP-rel), 12 (PUSH/POP), 13 (ADD SP/PC),
// 16 (B cond), 18 (B), 19 (BL largo). Pendiente: modo ARM (avanza),
// LDM/STM, SWI, IRQ. PC lecturable = ins+4 como el hardware real.

#include "arm7.h"

namespace {
constexpr uint32_t CPSR_I = 1u << 7; // IRQ deshabilitado
constexpr uint32_t CPSR_F = 1u << 6; // FIQ deshabilitado
} // namespace

Arm7::Arm7() {
    reset(0x08000000u); // punto de entrada de la ROM GBA (tras BIOS-HLE)
}

void Arm7::reset(uint32_t entryPoint) {
    for (int i = 0; i < 16; i++) r_[i] = 0;
    for (int i = 0; i < 6; i++) spsr_[i] = 0;
    for (int i = 0; i < 7; i++) bank_fiq_[i] = 0;
    bank_irq_[0] = bank_irq_[1] = 0;
    bank_svc_[0] = bank_svc_[1] = 0;
    bank_abt_[0] = bank_abt_[1] = 0;
    bank_und_[0] = bank_und_[1] = 0;
    // SVC + ARM + interrupciones cerradas: estado de encendido GBA.
    cpsr_ = static_cast<uint32_t>(SVC) | CPSR_I | CPSR_F;
    r_[15] = entryPoint;
}

void Arm7::aluFlagsAdd(uint32_t a, uint32_t b, uint32_t r) {
    cpsr_ &= ~(CPSR_N | CPSR_Z | CPSR_C | CPSR_V);
    if (r & 0x80000000u) cpsr_ |= CPSR_N;
    if (r == 0) cpsr_ |= CPSR_Z;
    if ((static_cast<uint64_t>(a) + b) >> 32) cpsr_ |= CPSR_C;
    int32_t sa = static_cast<int32_t>(a), sb = static_cast<int32_t>(b),
             sr = static_cast<int32_t>(r);
    if (((sa > 0 && sb > 0 && sr < 0) || (sa < 0 && sb < 0 && sr >= 0))) {
        cpsr_ |= CPSR_V;
    }
}

void Arm7::aluFlagsSub(uint32_t a, uint32_t b, uint32_t r) {
    cpsr_ &= ~(CPSR_N | CPSR_Z | CPSR_C | CPSR_V);
    if (r & 0x80000000u) cpsr_ |= CPSR_N;
    if (r == 0) cpsr_ |= CPSR_Z;
    if (a >= b) cpsr_ |= CPSR_C; // sin préstamo
    if ((((a ^ b) & (a ^ r)) >> 31) & 1u) cpsr_ |= CPSR_V;
}

int Arm7::step(Mmu32& bus) {
    uint32_t pc = r_[15];
    if (!thumb()) {
        // Modo ARM: decode pendiente (slice futuro). Avanza sin romper.
        r_[15] = pc + 4;
        return 1;
    }
    uint16_t ins = bus.read16(pc);
    r_[15] = pc + 2;
    execThumb(ins, bus);
    return 1;
}

void Arm7::execThumb(uint16_t ins, Mmu32& bus) {
    // Formatos 1-3: MOV/ADD/SUB inmediatos sobre r0-r7.
    if ((ins & 0xE000u) == 0x2000u) {
        uint32_t op = (ins >> 11) & 3u, rd = (ins >> 8) & 7u;
        uint32_t imm = ins & 0xFFu;
        if (op == 0) { // MOV Rd, #imm
            r_[rd] = imm;
            cpsr_ &= ~(CPSR_N | CPSR_Z);
            if (imm == 0) cpsr_ |= CPSR_Z;
        } else if (op == 1) { // CMP Rd, #imm
            aluFlagsSub(r_[rd], imm, r_[rd] - imm);
        } else if (op == 2) { // ADD Rd, #imm
            uint32_t r = r_[rd] + imm;
            aluFlagsAdd(r_[rd], imm, r);
            r_[rd] = r;
        } else { // SUB Rd, #imm
            uint32_t r = r_[rd] - imm;
            aluFlagsSub(r_[rd], imm, r);
            r_[rd] = r;
        }
        return;
    }
    if ((ins & 0xF800u) == 0x1800u) { // Formato 2: ADD/SUB reg/imm3
        uint32_t sub = (ins >> 9) & 1u, imm = (ins >> 6) & 7u;
        uint32_t rs = (ins >> 3) & 7u, rd = ins & 7u;
        uint32_t b = sub ? r_[rs] : imm;
        uint32_t r;
        if ((ins >> 10) & 1u) { // SUB
            r = r_[rs] - b;
            aluFlagsSub(r_[rs], b, r);
        } else { // ADD
            r = r_[rs] + b;
            aluFlagsAdd(r_[rs], b, r);
        }
        r_[rd] = r;
        return;
    }
    if ((ins & 0xE000u) == 0x0000u && ((ins >> 11) & 3u) == 1u) { // Fmt 1: MOV Rd,# (alias 00100)
        uint32_t rd = (ins >> 8) & 7u, imm = ins & 0xFFu;
        r_[rd] = imm;
        cpsr_ &= ~(CPSR_N | CPSR_Z);
        if (imm == 0) cpsr_ |= CPSR_Z;
        return;
    }
    if ((ins & 0xFC00u) == 0x4000u) { // Formato 4: ALU
        uint32_t op = (ins >> 6) & 0xFu;
        uint32_t rs = (ins >> 3) & 7u, rd = ins & 7u;
        uint32_t a = r_[rd], b = r_[rs], r = a;
        switch (op) {
            case 0x0: r = a & b; cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; // AND
            case 0x1: r = a ^ b; cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; // EOR
            case 0x2: { uint32_t n = b & 31u; cpsr_ = (cpsr_ & ~CPSR_C) | ((n && (a >> (32 - n)) & 1u) ? CPSR_C : 0); r = (n >= 32) ? 0 : a << n; cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; } // LSL
            case 0x3: { uint32_t n = b & 31u; if (n == 0) { r = a; } else { if ((a >> (n - 1)) & 1u) cpsr_ |= CPSR_C; else cpsr_ &= ~CPSR_C; r = a >> n; } cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; break; } // LSR
            case 0x4: { uint32_t n = b & 31u; int32_t sa = static_cast<int32_t>(a); if (n == 0) { r = a; } else { if (((a >> (n - 1)) & 1u)) cpsr_ |= CPSR_C; else cpsr_ &= ~CPSR_C; r = static_cast<uint32_t>(sa >> n); } cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; } // ASR
            case 0x5: { uint64_t full = static_cast<uint64_t>(a) + b + ((cpsr_ & CPSR_C) ? 1u : 0u); r = static_cast<uint32_t>(full); aluFlagsAdd(a, b + ((cpsr_ & CPSR_C) ? 1u : 0u), r); break; } // ADC
            case 0x6: { uint32_t cb = (cpsr_ & CPSR_C) ? 0u : 1u; r = a - b - cb; aluFlagsSub(a, b + cb, r); break; } // SBC
            case 0x7: { uint32_t n = b & 31u; if (n) { uint32_t bit = (a >> ((n - 1) & 31u)) & 1u; cpsr_ = (cpsr_ & ~CPSR_C) | (bit ? CPSR_C : 0); n &= 31u; r = (a >> n) | (a << ((32 - n) & 31u)); } cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; } // ROR
            case 0x8: { uint32_t t = a & b; cpsr_ &= ~(CPSR_N | CPSR_Z); if (t == 0) cpsr_ |= CPSR_Z; if (t >> 31) cpsr_ |= CPSR_N; break; } // TST
            case 0x9: r = 0u - b; aluFlagsSub(0u, b, r); r_[rd] = r; return; // NEG
            case 0xA: aluFlagsSub(a, b, a - b); break; // CMP
            case 0xB: { uint32_t t = a + b; aluFlagsAdd(a, b, t); break; } // CMN
            case 0xC: r = a | b; cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; // ORR
            case 0xD: r = a * b; cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; // MUL
            case 0xE: r = a & ~b; cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; // BIC
            default: r = ~b; cpsr_ &= ~(CPSR_N | CPSR_Z); if (r == 0) cpsr_ |= CPSR_Z; if (r >> 31) cpsr_ |= CPSR_N; break; // MVN
        }
        r_[rd] = r;
        return;
    }
    if ((ins & 0xFC00u) == 0x4400u) { // Formato 5: Hi-reg / BX
        uint32_t op = (ins >> 6) & 3u;
        uint32_t hs = (ins >> 3) & 0xFu, hd = ((ins & 0x80u) >> 4) | (ins & 7u);
        uint32_t a = (hd == 15) ? thumbPC() : r_[hd];
        uint32_t b = (hs == 15) ? thumbPC() : r_[hs];
        if (op == 3) { // BX Rs
            cpsr_ = (cpsr_ & ~CPSR_T) | ((b & 1u) ? CPSR_T : 0);
            r_[15] = b & ~1u;
            return;
        }
        uint32_t r = (op == 0) ? a + b : ((op == 1) ? a - b : a); // ADD/CMP/MOV
        if (op == 1) {
            aluFlagsSub(a, b, r);
            return;
        }
        r_[hd] = (hd == 15) ? r & ~1u : r;
        return;
    }
    if ((ins & 0xF800u) == 0x4800u) { // Formato 6: LDR literal
        uint32_t rd = (ins >> 8) & 7u;
        uint32_t addr = thumbPC() + ((ins & 0xFFu) << 2);
        r_[rd] = bus.read32(addr);
        return;
    }
    if ((ins & 0xF000u) == 0x5000u) { // Formatos 7/8/10: acceso con registro
        uint32_t op = (ins >> 9) & 7u;
        uint32_t ro = (ins >> 6) & 7u, rb = (ins >> 3) & 7u, rd = ins & 7u;
        uint32_t addr = r_[rb] + r_[ro];
        switch (op) {
            case 0: bus.write32(addr, r_[rd]); break; // STR
            case 1: bus.write16(addr, static_cast<uint16_t>(r_[rd])); break; // STRH
            case 2: bus.write8(addr, static_cast<uint8_t>(r_[rd])); break; // STRB
            case 3: { int8_t v = static_cast<int8_t>(bus.read8(addr)); r_[rd] = static_cast<uint32_t>(v); break; } // LDSB
            case 4: r_[rd] = bus.read32(addr); break; // LDR
            case 5: r_[rd] = bus.read16(addr); break; // LDRH
            case 6: r_[rd] = bus.read8(addr); break; // LDRB
            default: { int16_t v = static_cast<int16_t>(bus.read16(addr)); r_[rd] = static_cast<uint32_t>(v); break; } // LDSH
        }
        return;
    }
    if ((ins & 0xE000u) == 0x6000u) { // Formato 9: LDR/STR imm5
        bool load = (ins >> 11) & 1u;
        uint32_t off = (ins >> 6) & 0x1Fu, rb = (ins >> 3) & 7u, rd = ins & 7u;
        if (((ins >> 12) & 1u) == 0) { // word
            uint32_t addr = r_[rb] + (off << 2);
            if (load) r_[rd] = bus.read32(addr); else bus.write32(addr, r_[rd]);
        } else { // byte
            uint32_t addr = r_[rb] + off;
            if (load) r_[rd] = bus.read8(addr); else bus.write8(addr, static_cast<uint8_t>(r_[rd]));
        }
        return;
    }
    if ((ins & 0xF000u) == 0x8000u) { // Formato 9b: LDRH/STRH imm5
        bool load = (ins >> 11) & 1u;
        uint32_t off = ((ins >> 6) & 0x1Fu) << 1, rb = (ins >> 3) & 7u, rd = ins & 7u;
        uint32_t addr = r_[rb] + off;
        if (load) r_[rd] = bus.read16(addr); else bus.write16(addr, static_cast<uint16_t>(r_[rd]));
        return;
    }
    if ((ins & 0xF000u) == 0x9000u) { // Formato 11: SP relativo
        uint32_t rd = (ins >> 8) & 7u;
        uint32_t addr = r_[13] + ((ins & 0xFFu) << 2);
        if ((ins >> 11) & 1u) r_[rd] = bus.read32(addr); else bus.write32(addr, r_[rd]);
        return;
    }
    if ((ins & 0xF600u) == 0xB400u) { // Formato 12: PUSH/POP
        bool pop = (ins >> 11) & 1u, keepLrPc = (ins >> 8) & 1u;
        if (!pop) {
            if (keepLrPc) { r_[13] -= 4; bus.write32(r_[13], r_[14]); }
            for (int i = 7; i >= 0; i--) {
                if ((ins >> i) & 1u) { r_[13] -= 4; bus.write32(r_[13], r_[i]); }
            }
        } else {
            for (int i = 0; i < 8; i++) {
                if ((ins >> i) & 1u) { r_[i] = bus.read32(r_[13]); r_[13] += 4; }
            }
            if (keepLrPc) { r_[15] = bus.read32(r_[13]) & ~1u; r_[13] += 4; }
        }
        return;
    }
    if ((ins & 0xFF00u) == 0xB000u) { // Formato 13: ADD SP, #imm
        int32_t off = static_cast<int32_t>((ins & 0x7Fu) << 2);
        r_[13] = (((ins >> 7) & 1u)) ? r_[13] - static_cast<uint32_t>(off)
                                     : r_[13] + static_cast<uint32_t>(off);
        return;
    }
    if ((ins & 0xF800u) == 0xA000u) { // ADD Rd, PC/SP
        uint32_t rd = (ins >> 8) & 7u;
        uint32_t base = ((ins >> 11) & 1u) ? r_[13] : (thumbPC() & ~2u);
        r_[rd] = base + ((ins & 0xFFu) << 2);
        return;
    }
    if ((ins & 0xF000u) == 0xD000u) { // Formato 16: B condicional
        uint32_t cond = (ins >> 8) & 0xFu;
        bool n = cpsr_ & CPSR_N, z = cpsr_ & CPSR_Z;
        bool c = cpsr_ & CPSR_C, v = cpsr_ & CPSR_V;
        bool take = false;
        switch (cond) {
            case 0x0: take = z; break;
            case 0x1: take = !z; break;
            case 0x2: take = c; break;
            case 0x3: take = !c; break;
            case 0x4: take = n; break;
            case 0x5: take = !n; break;
            case 0x6: take = v; break;
            case 0x7: take = !v; break;
            case 0x8: take = c && !z; break;
            case 0x9: take = !c || z; break;
            case 0xA: take = (n == v); break;
            case 0xB: take = (n != v); break;
            case 0xC: take = !z && (n == v); break;
            case 0xD: take = z || (n != v); break;
            default: break; // 0xE = indefinido, 0xF = SWI (ignorado aquí)
        }
        if (take) {
            int32_t off = static_cast<int32_t>(static_cast<int8_t>(ins & 0xFFu)) << 1;
            r_[15] = static_cast<uint32_t>(static_cast<int32_t>(thumbPC()) + off);
        }
        return;
    }
    if ((ins & 0xF800u) == 0xE000u) { // Formato 18: B incondicional
        int32_t off = (static_cast<int32_t>(ins << 21) >> 21) << 1;
        r_[15] = static_cast<uint32_t>(static_cast<int32_t>(thumbPC()) + off);
        return;
    }
    if ((ins & 0xF000u) == 0xF000u) { // Formato 19: BL largo (dos mitades)
        uint32_t off = ins & 0x7FFu;
        if (((ins >> 11) & 1u) == 0) {
            r_[14] = r_[15] + 2u + (off << 12); // r15 ya apunta a la 2a mitad
        } else {
            uint32_t target = r_[14] + (off << 1);
            r_[14] = (r_[15] - 2u) | 1u;
            r_[15] = target & ~1u;
        }
        return;
    }
    // No implementado en este slice: LDM/STM, SWI, resto. Avanza sin romper.
}
