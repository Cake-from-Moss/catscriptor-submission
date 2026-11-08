package org.cfm.catscriptor.domain.pipeline

import org.cfm.catscriptor.native.models.ModelResource

interface    DiarizationBackend : ModelResource {
    /**
     * Автоматически определить число спикеров; их идентификаторы стабильны в пределах записи.
     * Читать аудио ограниченными порциями; вернуть интервалы по времени начала, перекрытия допустимы.
     * Использовать готовые интервалы VAD без повторной загрузки его модели. Не закрывать источ ник аудио.
     */
    suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>): List<SpeakerSegment>
}
