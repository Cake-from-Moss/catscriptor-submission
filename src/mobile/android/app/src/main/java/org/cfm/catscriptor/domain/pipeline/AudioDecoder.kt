package org.cfm.catscriptor.domain.pipeline

/** Декодирует локальный документ в PCM с ограниченным расходом памяти; источником владеет вызывающий код. */
interface AudioDecoder {
    suspend fun decode(input: AudioInput): PcmAudioSource
}
