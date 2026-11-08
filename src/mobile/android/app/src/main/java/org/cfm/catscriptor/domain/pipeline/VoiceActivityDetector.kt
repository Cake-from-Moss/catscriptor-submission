package org.cfm.catscriptor.domain.pipeline

import org.cfm.catscriptor.native.models.ModelResource

interface VoiceActivityDetector : ModelResource {
    /** Читать PCM порциями ограниченного размера; вернуть отсортированные интервалы речи или пустой список для тишины. */
    suspend fun detect(audio: PcmAudioSource): List<SpeechSegment>
}
