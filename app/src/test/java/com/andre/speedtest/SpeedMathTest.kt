package com.andre.speedtest

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMathTest {
    @Test
    fun convertsBytesToMegabitsPerSecond() {
        assertEquals(8.0, SpeedMath.mbps(bytes = 1_000_000, millis = 1_000), 0.0001)
    }

    @Test
    fun handlesEmptyMeasurement() {
        assertEquals(0.0, SpeedMath.mbps(bytes = 0, millis = 1_000), 0.0001)
        assertEquals(0.0, SpeedMath.mbps(bytes = 1_000, millis = 0), 0.0001)
    }

    @Test
    fun classifiesQuality() {
        assertEquals("Excellent", SpeedMath.quality(150.0, 40.0, 20))
        assertEquals("Strong", SpeedMath.quality(60.0, 15.0, 60))
        assertEquals("Good", SpeedMath.quality(30.0, 8.0, 90))
        assertEquals("Usable", SpeedMath.quality(12.0, 3.0, 200))
        assertEquals("Limited", SpeedMath.quality(3.0, 1.0, 200))
    }
}

