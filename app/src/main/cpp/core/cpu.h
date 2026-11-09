#ifndef GPSQUEST96_CPU_H
#define GPSQUEST96_CPU_H

#include <cstdint>

namespace gpsquest96 {

class Mmu;
class Ppu;

/**
 * CPU LR35902 â€” Procesador principal de hardware de 8 bits.
 * <p>
 * El LR35902 es un procesador de 8 bits basado en el Z80 con modificaciones.
 * Registros:
 * - 8 registros de 8 bits: A, B, C, D, E, F, H, L
 * - 2 registros de 16 bits: SP (Stack Pointer), PC (Program Counter)
 * - Flags: Z (Zero), N (Subtract), H (Half Carry), C (Carry)
 */
class Cpu {
public:
    Cpu();
    ~Cpu();

    /**
     * Inicializa el CPU.
     */
    void initialize();

    /**
     * Ejecuta un ciclo de CPU.
     * @return NÃºmero de ciclos de reloj consumidos
     */
    int step();

    /**
     * Obtiene el valor del registro A.
     */
    uint8_t getA() const { return a_; }

    /**
     * Obtiene el valor del registro B.
     */
    uint8_t getB() const { return b_; }

    /**
     * Obtiene el valor del registro C.
     */
    uint8_t getC() const { return c_; }

    /**
     * Obtiene el valor del registro D.
     */
    uint8_t getD() const { return d_; }

    /**
     * Obtiene el valor del registro E.
     */
    uint8_t getE() const { return e_; }

    /**
     * Obtiene el valor del registro F (flags).
     */
    uint8_t getF() const { return f_; }

    /**
     * Obtiene el valor del registro H.
     */
    uint8_t getH() const { return h_; }

    /**
     * Obtiene el valor del registro L.
     */
    uint8_t getL() const { return l_; }

    /**
     * Obtiene el valor del registro PC.
     */
    uint16_t getPC() const { return pc_; }

    /**
     * Obtiene el valor del registro SP.
     */
    uint16_t getSP() const { return sp_; }

    /**
     * Establece el valor del registro PC.
     */
    void setPC(uint16_t pc) { pc_ = pc; }

    /**
     * Establece el valor del registro SP.
     */
    void setSP(uint16_t sp) { sp_ = sp; }

    /**
     * Verifica si el CPU estÃ¡ detenido (halt).
     */
    bool isHalted() const { return halted_; }

    /**
     * Establece el puntero a la MMU.
     */
    void setMmu(class Mmu* mmu) { mmu_ = mmu; }

    /**
     * Establece el puntero a la PPU para sincronizaciÃ³n de reloj.
     */
    void setPpu(Ppu* ppu) { ppu_ = ppu; }

    /**
     * Ejecuta un ciclo de CPU y reenvÃ­a los ciclos a la PPU.
     * @return NÃºmero de ciclos de reloj consumidos
     */
    int stepAndSync();

    uint16_t getHL() const { return (static_cast<uint16_t>(h_) << 8) | l_; }
    void setHL(uint16_t hl) { h_ = (hl >> 8) & 0xFF; l_ = hl & 0xFF; }

    uint16_t getBC() const { return (static_cast<uint16_t>(b_) << 8) | c_; }
    void setBC(uint16_t bc) { b_ = (bc >> 8) & 0xFF; c_ = bc & 0xFF; }

    uint16_t getDE() const { return (static_cast<uint16_t>(d_) << 8) | e_; }
    void setDE(uint16_t de) { d_ = (de >> 8) & 0xFF; e_ = de & 0xFF; }

    uint16_t getAF() const { return (static_cast<uint16_t>(a_) << 8) | (f_ & 0xF0); }
    void setAF(uint16_t af) { a_ = (af >> 8) & 0xFF; f_ = af & 0xF0; }

    // Flags
    bool getFlagZ() const { return (f_ & 0x80) != 0; }
    bool getFlagN() const { return (f_ & 0x40) != 0; }
    bool getFlagH() const { return (f_ & 0x20) != 0; }
    bool getFlagC() const { return (f_ & 0x10) != 0; }

    void setFlagZ(bool value) { if (value) f_ |= 0x80; else f_ &= ~0x80; }
    void setFlagN(bool value) { if (value) f_ |= 0x40; else f_ &= ~0x40; }
    void setFlagH(bool value) { if (value) f_ |= 0x20; else f_ &= ~0x20; }
    void setFlagC(bool value) { if (value) f_ |= 0x10; else f_ &= ~0x10; }

private:
    // Registros de 8 bits
    uint8_t a_, b_, c_, d_, e_, f_, h_, l_;

    // Registros de 16 bits
    uint16_t sp_, pc_;

    // Estado
    bool halted_;
    bool ime_; // Interrupt Master Enable
    // HALT bug del LR35902: con IME=0 e IRQ pendiente al ejecutar HALT, el
    // CPU no se detiene y relee el siguiente byte sin avanzar PC.
    bool haltBug_;

    // Puntero a la MMU
    class Mmu* mmu_;

    // Puntero a la PPU (sincronizaciÃ³n de reloj CPU -> PPU)
    Ppu* ppu_;

    // DecodificaciÃ³n de opcodes
    void executeOpcode(uint8_t opcode);
    int getOpcodeCycles(uint8_t opcode, uint8_t cbOpcode, bool taken);
    bool hasPendingInterrupt() const;
    int checkInterrupts(); // 0 o 20 ciclos; despierta HALT
    /**
     * Convierte ciclos de CPU a ciclos de PPU/timers. En doble velocidad
     * (CGB) la CPU va a 8.38 MHz y la PPU sigue a 4.19 MHz: cada M-cycle
     * de CPU avanza medio ciclo de PPU.
     */
    int toPpuCycles(int cycles) const;

    // MÃ©todos de instrucciones
    uint8_t fetchByte();
    uint16_t fetchWord();
    uint8_t getReg8(uint8_t idx);
    void setReg8(uint8_t idx, uint8_t val);

    // Instrucciones LD (Load)
    void ld_r8_r8(uint8_t& dst, uint8_t src);
    void ld_r8_n8(uint8_t& dst);
    void ld_r16_n16(uint16_t& dst);
    void ld_a16_a();
    void ld_a_a16();

    // Instrucciones ALU
    void add_a(uint8_t value);
    void adc_a(uint8_t value);
    void sub_a(uint8_t value);
    void sbc_a(uint8_t value);
    void and_a(uint8_t value);
    void or_a(uint8_t value);
    void xor_a(uint8_t value);
    void cp_a(uint8_t value);
    void inc_r8(uint8_t& reg);
    void dec_r8(uint8_t& reg);

    // Instrucciones de salto
    void jp_n16();
    void jr_n8();
    void call_n16();
    void ret();

    // Instrucciones de pila
    void push_r16(uint16_t value);
    void pop_r16(uint16_t& reg);

    // Utilidades
    uint8_t readByte(uint16_t address);
    void writeByte(uint16_t address, uint8_t value);
    uint16_t readWord(uint16_t address);
    void writeWord(uint16_t address, uint16_t value);
};

} // namespace gpsquest96

#endif // GPSQUEST96_CPU_H

