package org.cfm.catscriptor.viewmodel.home

import kotlin.math.roundToInt

/** Нижняя граница шкалы уровня: всё тише −60 dBFS показываем как тишину. */
const val LEVEL_FLOOR_DBFS = -60f

/**
 * Переводит уровень в dBFS в долю шкалы 0..1 линейно по децибелам от −60 до 0.
 * Шкала логарифмическая по амплитуде: при линейном RMS обычная речь (−30…−20 dBFS)
 * заняла бы пару процентов высоты, и волна казалась бы плоской.
 */
fun levelFraction(levelDbfs: Float): Float {
    if (levelDbfs.isNaN()) return 0f
    return ((levelDbfs - LEVEL_FLOOR_DBFS) / -LEVEL_FLOOR_DBFS).coerceIn(0f, 1f)
}

/**
 * История столбиков волны в «пилюле». Каждый [push] сдвигает столбики влево и добавляет
 * справа новый, поэтому волна «бежит». Значения — доли высоты 0..1, самый новый последний.
 */
class WaveformHistory(val barCount: Int = DEFAULT_BAR_COUNT) {
    private val bars = FloatArray(barCount)

    init {
        require(barCount > 0) { "barCount must be positive" }
    }

    fun push(levelDbfs: Float) {
        System.arraycopy(bars, 1, bars, 0, barCount - 1)
        bars[barCount - 1] = levelFraction(levelDbfs)
    }

    fun clear() = bars.fill(0f)

    fun snapshot(): List<Float> = bars.toList()

    companion object {
        const val DEFAULT_BAR_COUNT = 20

        /** Период сдвига волны. */
        const val SHIFT_PERIOD_MS = 50L
    }
}

enum class LevelStatus {
    NORMAL, TOO_QUIET, SILENCE, CLIPPING;

    /** Предупреждение: сегменты индикатора окрашиваются цветом ошибки. */
    val isWarning: Boolean get() = this == TOO_QUIET || this == CLIPPING
}

data class LevelReading(val litSegments: Int, val status: LevelStatus)

/**
 * Индикатор уровня для записи без Live-текста.
 *
 * Сглаживание: рост мгновенный, спад не быстрее [decayDbPerSecond], чтобы сегменты не мерцали
 * между буферами. Подпись выбирается по порогам; их стоит уточнить на тестовых записях:
 * - пик выше [clipDbfs] → «слишком громко» и держится [clipHoldMs], иначе подпись прыгает на каждом слоге;
 * - речь есть, но уровень ниже [quietDbfs] дольше [quietAfterMs] → «слишком тихо»;
 * - VAD не слышит речь дольше [silenceAfterMs] → «тишина».
 */
class LevelMeter(
    val segments: Int = DEFAULT_SEGMENTS,
    private val decayDbPerSecond: Float = 20f,
    private val quietDbfs: Float = -45f,
    private val quietAfterMs: Long = 2_000,
    private val silenceAfterMs: Long = 5_000,
    private val clipDbfs: Float = -3f,
    private val clipHoldMs: Long = 1_500,
) {
    private var displayedDbfs = LEVEL_FLOOR_DBFS
    private var lastUpdateMs: Long? = null
    private var quietSinceMs: Long? = null
    private var silenceSinceMs: Long? = null
    private var clipUntilMs = Long.MIN_VALUE

    /** Сбрасывает таймеры подписи, например после паузы: время паузы не должно считаться тишиной. */
    fun reset() {
        displayedDbfs = LEVEL_FLOOR_DBFS
        lastUpdateMs = null
        quietSinceMs = null
        silenceSinceMs = null
        clipUntilMs = Long.MIN_VALUE
    }

    fun update(levelDbfs: Float, peakDbfs: Float, speechDetected: Boolean, nowMs: Long): LevelReading {
        val level = if (levelDbfs.isNaN()) LEVEL_FLOOR_DBFS else levelDbfs.coerceIn(LEVEL_FLOOR_DBFS, 0f)
        val elapsedMs = lastUpdateMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L
        lastUpdateMs = nowMs
        val decayed = displayedDbfs - decayDbPerSecond * elapsedMs / 1000f
        displayedDbfs = maxOf(level, decayed).coerceIn(LEVEL_FLOOR_DBFS, 0f)

        quietSinceMs = if (speechDetected && level < quietDbfs) quietSinceMs ?: nowMs else null
        silenceSinceMs = if (!speechDetected) silenceSinceMs ?: nowMs else null
        if (!peakDbfs.isNaN() && peakDbfs > clipDbfs) clipUntilMs = nowMs + clipHoldMs

        val status = when {
            nowMs < clipUntilMs -> LevelStatus.CLIPPING
            quietSinceMs.let { it != null && nowMs - it >= quietAfterMs } -> LevelStatus.TOO_QUIET
            silenceSinceMs.let { it != null && nowMs - it >= silenceAfterMs } -> LevelStatus.SILENCE
            else -> LevelStatus.NORMAL
        }
        val lit = (levelFraction(displayedDbfs) * segments).roundToInt().coerceIn(0, segments)
        return LevelReading(lit, status)
    }

    companion object {
        const val DEFAULT_SEGMENTS = 16

        /** Индикатор обновляется 10 раз в секунду. */
        const val UPDATE_PERIOD_MS = 100L
    }
}
