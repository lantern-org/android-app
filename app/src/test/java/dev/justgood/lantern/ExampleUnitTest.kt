package dev.justgood.lantern

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun packet_v1() {
        /* todo
            refactor LocationTracker.transmitPacket() to pull out packet construction
            into separate function. then unit-test that function
        */
    }
}