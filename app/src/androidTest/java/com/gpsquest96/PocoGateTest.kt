package com.gpsquest96

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsquest96.emulator.EmulatorBridge
import com.gpsquest96.emulator.core.EmulationPipeline
import com.gpsquest96.render.TileAtlas
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Gate de verificación POCO X7 Pro para Slice 1 de specs/001-nucleo-gbc/
 */
@RunWith(AndroidJUnit4::class)
class PocoGateTest {

    @Test
    fun testPocoGateBoot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val romsDir = File(context.filesDir, "roms")
        if (!romsDir.exists()) {
            romsDir.mkdirs()
        }

        var romFile = File(romsDir, "Crystal.gbc")
        if (!romFile.exists()) {
            val list = romsDir.listFiles()
            if (list != null) {
                for (f in list) {
                    if (f.name.endsWith(".gbc", ignoreCase = true) || f.name.endsWith(".gb", ignoreCase = true)) {
                        romFile = f
                        break
                    }
                }
            }
        }

        Log.i("POCOBoot", "Iniciando PocoGateTest con ROM: ${romFile.absolutePath}")

        val pipeline = EmulationPipeline()
        pipeline.initialize()

        val cpu = pipeline.cpu
        val mmu = pipeline.mmu
        val ppu = pipeline.ppu

        // Enlazar componentes (el enlace cpu->ppu es obligatorio:
        // sin él stepAndSync() no avanza la PPU, LY se queda en 0,
        // no hay VBlank y el boot gira en espera válida para siempre)
        cpu.setMmu(mmu)
        cpu.setPpu(ppu)
        ppu.setMmu(mmu)
        Log.i("POCOBoot", "HARNESS setppu+forense+s5k+t30s")

        val bridge = EmulatorBridge()
        if (romFile.exists()) {
            bridge.loadRom(romFile.absolutePath)
        }

        val startTimeMs = System.currentTimeMillis()
        // Ventana larga y muestreo espaciado: cada snapshot (6144 lecturas
        // JNI) cuesta ~ms; muestrear cada 100 ciclos dejaba al juego en
        // ~37 frames/10 s y el trace nativo (cada 2M steps) jamás disparaba.
        val timeoutMs = 30000L // 30 segundos max
        val sampleEveryCycles = 5000L

        var totalCycles = 0L
        var stepCount = 0L
        var lastLogMarker = 0L

        val traceFile = File(context.filesDir, "poco_trace.txt")
        val traceFos = FileOutputStream(traceFile)

        while (System.currentTimeMillis() - startTimeMs < timeoutMs) {
            val cycles = cpu.stepAndSync()
            totalCycles += cycles
            stepCount++

            if (totalCycles - lastLogMarker >= sampleEveryCycles) {
                lastLogMarker = totalCycles
                val tileCount = TileAtlas.snapshot(mmu).tileCount
                val logMsg = String.format(
                    Locale.US,
                    "GBCore cycle=%d step=%d | POCOBoot | Tiles:M=%d",
                    totalCycles, stepCount, tileCount
                )
                Log.i("GBCore", logMsg)
                Log.i("POCOBoot", logMsg)
                Log.i("Tiles:M", "tileCount=$tileCount")

                val entry = "$logMsg\n"
                traceFos.write(entry.toByteArray(Charsets.UTF_8))
            }

            if (cycles <= 0) break
        }

        traceFos.flush()
        traceFos.close()

        // Foto forense del hang: LCDC/IE/IF + bytes del bucle 0x60-0x80.
        // Sin esto no se distingue "HALT sin IE" de "JR a sí mismo" ni de
        // "vectores ejecutados como código". Solo lectura, cero conducta.
        try {
            val sb = StringBuilder()
            sb.append(String.format(Locale.US, "LCDC=0x%02X IE=0x%02X IF=0x%02X\n",
                mmu.read(0xFF40) and 0xFF, mmu.read(0xFFFF) and 0xFF,
                mmu.read(0xFF0F) and 0xFF))
            sb.append("ROM[0x60-0x80]:")
            for (a in 0x60..0x80) {
                sb.append(String.format(Locale.US, " %02X", mmu.read(a) and 0xFF))
            }
            sb.append("\n")
            val forense = sb.toString()
            Log.i("POCOBoot", forense.trim())
            File(context.filesDir, "forense.txt").writeText(forense, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w("POCOBoot", "forense omitido: ${t.message}")
        }

        // Dumpear VRAM (0x8000 a 0x9FFF = 8192 B) a /data/data/com.gpsquest96/files/tiles_dump.bin
        val tilesDumpFile = File(context.filesDir, "tiles_dump.bin")
        val vramData = ByteArray(0x2000)
        for (i in 0 until 0x2000) {
            vramData[i] = (mmu.read(0x8000 + i) and 0xFF).toByte()
        }
        FileOutputStream(tilesDumpFile).use { fos ->
            fos.write(vramData)
        }

        // Dumpear SRAM actual a crystal.sav
        bridge.flushSram()
        val sramSize = bridge.nativeSramSize()
        val savFile = File(context.filesDir, "crystal.sav")
        if (sramSize > 0) {
            val sramBytes = ByteArray(sramSize)
            for (i in 0 until sramSize) {
                sramBytes[i] = (bridge.readSramRaw(i) and 0xFF).toByte()
            }
            FileOutputStream(savFile).use { fos ->
                fos.write(sramBytes)
            }
        } else {
            val crystalInRoms = File(romsDir, "Crystal.sav")
            if (crystalInRoms.exists()) {
                crystalInRoms.copyTo(savFile, overwrite = true)
            } else {
                savFile.writeBytes(ByteArray(32768))
            }
        }

        Log.i("POCOBoot", "GATE POCO: listo - sube poco_trace.txt, tiles_dump.bin y crystal.sav")
        Log.i("GBCore", "GATE POCO: listo - sube poco_trace.txt, tiles_dump.bin y crystal.sav")
    }
}
