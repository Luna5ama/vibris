package dev.luna5ama.vibris.capture

import dev.vibris.api.TextureChannelStats
import kotlin.math.floor
import kotlin.math.sqrt

object TextureStatistics {
    @JvmStatic
    fun compute(channels: Int, values: DoubleArray): List<TextureChannelStats> {
        require(channels in 1..4 && values.size % channels == 0)
        return (0 until channels).map { channel ->
            val finite = DoubleArray(values.size / channels)
            var count = 0
            var nan = 0L; var positiveInf = 0L; var negativeInf = 0L; var zero = 0L
            var mean = 0.0; var m2 = 0.0
            for (index in channel until values.size step channels) {
                val value = values[index]
                when {
                    value.isNaN() -> nan++
                    value == Double.POSITIVE_INFINITY -> positiveInf++
                    value == Double.NEGATIVE_INFINITY -> negativeInf++
                    else -> {
                        finite[count++] = value
                        if (value == 0.0) zero++
                        val delta = value - mean
                        mean += delta / count
                        m2 += delta * (value - mean)
                    }
                }
            }
            java.util.Arrays.sort(finite, 0, count)
            fun quantile(p: Double): Double? {
                if (count == 0) return null
                val position = (count - 1) * p
                val lower = floor(position).toInt()
                return finite[lower] + (finite[minOf(lower + 1, count - 1)] - finite[lower]) * (position - lower)
            }
            TextureChannelStats(channel, count.toLong(), finite.getOrNull(0)?.takeIf { count > 0 },
                finite.getOrNull(count - 1), mean.takeIf { count > 0 },
                sqrt(maxOf(0.0, m2 / count)).takeIf { count > 0 },
                quantile(0.01), quantile(0.5), quantile(0.99), nan, positiveInf, negativeInf, zero)
        }
    }
}
