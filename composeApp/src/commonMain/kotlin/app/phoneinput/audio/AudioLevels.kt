package app.phoneinput.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object AudioLevels {
    /** Lowest level the meters show; anything quieter reads as silence. */
    const val FLOOR_DB = -60f

    /** Meter values above this (≈ −11 dBFS RMS) are drawn in the warning color. */
    const val HOT_METER = 0.82f

    fun rmsDbfs(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return FLOOR_DB
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        return toDb(sqrt(sum / count))
    }

    fun peakDbfs(samples: ShortArray, count: Int = samples.size): Float {
        var peak = 0
        for (i in 0 until count) peak = max(peak, abs(samples[i].toInt()))
        return toDb(peak / 32768.0)
    }

    fun toMeter(db: Float): Float = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)

    fun gainFactor(gainDb: Float): Float = 10f.pow(gainDb / 20f)

    /** Applies [factor] in place with clipping. */
    fun applyGain(samples: ShortArray, count: Int, factor: Float) {
        if (factor == 1f) return
        for (i in 0 until count) {
            val v = (samples[i] * factor).roundToInt()
            samples[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun toDb(linear: Double): Float {
        if (linear <= 0.0) return FLOOR_DB
        return (20 * log10(linear)).toFloat().coerceIn(FLOOR_DB, 0f)
    }
}

/** Rolling window of meter values (0..1), newest last. */
class LevelHistory(private val capacity: Int) {
    private val values = ArrayDeque<Float>(capacity)

    fun push(value: Float) {
        values.addLast(value)
        while (values.size > capacity) values.removeFirst()
    }

    fun clear() = values.clear()

    fun snapshot(): List<Float> = values.toList()
}

object Formats {
    fun dbfs(db: Float): String =
        if (db <= AudioLevels.FLOOR_DB) "— dBFS" else "−${(-db).roundToInt()} dBFS"

    fun db(db: Float): String = if (db <= AudioLevels.FLOOR_DB) "—" else "−${(-db).roundToInt()}"

    fun signedDb(db: Float): String {
        val v = db.roundToInt()
        return when {
            v > 0 -> "+$v dB"
            v < 0 -> "−${-v} dB"
            else -> "0 dB"
        }
    }

    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }
}
