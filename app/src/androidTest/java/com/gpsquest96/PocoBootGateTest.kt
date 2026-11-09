package com.gpsquest96

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Alias de PocoGateTest para soportar la ejecución mediante la clase PocoBootGateTest.
 */
@RunWith(AndroidJUnit4::class)
class PocoBootGateTest {

    @Test
    fun testPocoGateBoot() {
        PocoGateTest().testPocoGateBoot()
    }
}
