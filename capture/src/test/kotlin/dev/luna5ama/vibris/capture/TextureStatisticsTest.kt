package dev.luna5ama.vibris.capture

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.math.sqrt

class TextureStatisticsTest {
    @Test fun separatesNonfiniteValuesAndUsesNativeUnits() {
        val values = doubleArrayOf(0.0, Double.NaN, 2.0, Double.POSITIVE_INFINITY,
            4.0, Double.NEGATIVE_INFINITY, 6.0, 10.0)
        val result = TextureStatistics.compute(2, values)
        assertEquals(3.0, result[0].mean)
        assertEquals(sqrt(5.0), result[0].std)
        assertEquals(0.06, result[0].p1!!, 1e-12)
        assertEquals(3.0, result[0].p50)
        assertEquals(5.94, result[0].p99!!, 1e-12)
        assertEquals(1L, result[0].zero)
        assertEquals(1L, result[1].nan)
        assertEquals(1L, result[1].positiveInf)
        assertEquals(1L, result[1].negativeInf)
        assertEquals(10.0, result[1].mean)
        assertNull(TextureStatistics.compute(1, doubleArrayOf(Double.NaN))[0].mean)
    }
}
