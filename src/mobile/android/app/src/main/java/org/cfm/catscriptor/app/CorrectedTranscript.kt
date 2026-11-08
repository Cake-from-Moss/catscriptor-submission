package org.cfm.catscriptor.app

import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.pipeline.Utterance

/** Повторно правим видимый текст, включая осознанно выбранные пользователем ручные изменения. */
internal fun PipelineEntry.correctionSource(): List<Utterance> =
    editedTranscript ?: processingState?.transcript ?: processingState?.speakerDraft.orEmpty()

/** Атомарный коммит не затирает параллельную ручную правку, переименование или пометки. */
internal fun PipelineEntry.withCorrectedText(source: List<Utterance>, result: List<Utterance>): PipelineEntry {
    check(!hidden) { "Запись удалена" }
    check(correctionSource() == source) { "Текст изменился во время обработки. Повторите пунктуацию и правку" }
    check(source.size == result.size && source.zip(result).all { (before, after) ->
        before.copy(text = after.text) == after
    }) { "Коррекция изменила интервалы или спикеров" }
    return copy(
        status = RecordingStatus.Ready,
        textCorrected = true,
        processingState = (processingState ?: AudioProcessingState()).copy(
            phase = AudioProcessingPhase.COMPLETED, transcript = result,
        ),
        editedTranscript = if (editedTranscript == null) null else result,
    )
}
