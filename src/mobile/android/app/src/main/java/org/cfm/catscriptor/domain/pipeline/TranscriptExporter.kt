package org.cfm.catscriptor.domain.pipeline

import java.io.OutputStream

enum class TranscriptFormat { JSON, TEXT }

interface TranscriptExporter {
    /**
     * Записать UTF-8, не закрывая поток вызывающего кода. JSON должен иметь строго такую структуру:
     * {"transcript":[{"speaker_id":string,"start_ms":integer,"end_ms":integer,"text":string}]}.
     * Внутренние слова не сериализуются. Реализация SAF и сериализации относится к задаче #2.
     */
    suspend fun export(transcript: Transcript, format: TranscriptFormat, output: OutputStream)
}
