package org.cfm.catscriptor.domain.pipeline

import java.io.Closeable

/** Идентификатор локального документа, например URI из SAF; не сетевой URL. */
data class AudioInput(val documentId: String) {
    init {
        require(documentId.isNotBlank())
    }
}

/**
 * Декодированный mono PCM с частотой 16 кГц, произвольным доступом и нормализованными отсчётами.
 * Может храниться во временном файле; не должен требовать загрузки всей записи в память.
 * Источником владеет вызывающий код: он закрывает его после всех аудиоэтапов, при ошибке и отмене.
 * Вызовы последовательны, если реализация явно не поддерживает параллельное чтение.
 */
interface PcmAudioSource : Closeable {
    val sampleCount: Long

    /**
     * Прочитать не более [maxSamples] отсчётов с абсолютного смещения в отсчётах.
     * Требуется startSample >= 0 и maxSamples > 0. Пустой массив возвращается только в конце;
     * неполное чтение допустимо. Возвращённым массивом владеет вызывающий код.
     * Чтение не закрывает источник.
     */
    suspend fun read(startSample: Long, maxSamples: Int): FloatArray

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}
