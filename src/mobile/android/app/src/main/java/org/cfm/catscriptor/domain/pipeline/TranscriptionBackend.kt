package org.cfm.catscriptor.domain.pipeline

import org.cfm.catscriptor.native.models.ModelResource

/** Граница заменяемой реализации ASR; альтернативный движок реализует тот же контракт. */
interface TranscriptionBackend : ModelResource {
    /**
     * Распознать фрагмент VAD или уточнённый ход спикера, читая аудио ограниченными порциями.
     * Вернуть текст и абсолютный интервал обработанного фрагмента, не пословные метки.
     * Для тишины допустим пустой текст. Не закрывать источник аудио вызывающего кода.
     */
    suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment
}
