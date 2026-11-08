package org.cfm.catscriptor.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.ProcessingStage

/** Длительность без ведущего нуля: «0:04», «12:41», «1:18:40». */
internal fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

internal fun percentOf(fraction: Float): Int = (fraction * 100).roundToInt().coerceIn(0, 100)

/** Остаток округляется вверх: «около 1 мин» честнее, чем «около 0 мин». */
internal fun remainingMinutes(ms: Long): Int = ceil(ms / 60_000.0).toInt().coerceAtLeast(1)

/** Номер N из speaker_N; у ID в другом формате считаем первого спикера. */
internal fun speakerNumber(speakerId: String): Int = speakerId.substringAfterLast('_').toIntOrNull() ?: 1

@StringRes
internal fun ProcessingStage.titleRes(): Int = when (this) {
    ProcessingStage.PREPARE_AUDIO -> R.string.home_stage_prepare_audio
    ProcessingStage.DETECT_SPEECH -> R.string.home_stage_detect_speech
    ProcessingStage.TRANSCRIBE -> R.string.home_stage_transcribe
    ProcessingStage.DIARIZE -> R.string.home_stage_diarize
    ProcessingStage.CORRECT_TEXT -> R.string.home_stage_correct_text
}

@StringRes
internal fun ProcessingStage.hintRes(): Int = when (this) {
    ProcessingStage.PREPARE_AUDIO -> R.string.home_stage_prepare_audio_hint
    ProcessingStage.DETECT_SPEECH -> R.string.home_stage_detect_speech_hint
    ProcessingStage.TRANSCRIBE -> R.string.home_stage_transcribe_hint
    ProcessingStage.DIARIZE -> R.string.home_stage_diarize_hint
    ProcessingStage.CORRECT_TEXT -> R.string.home_stage_correct_text_hint
}

@Composable
internal fun speakerLabel(speakerId: String): String =
    stringResource(R.string.home_speaker_label, speakerNumber(speakerId))

@Composable
internal fun remainingLabel(job: ProcessingJob): String? =
    job.remainingMs?.let { stringResource(R.string.home_remaining_about, remainingMinutes(it)) }

/** Текст для TalkBack: «Идёт запись: 4 минуты 10 секунд». */
@Composable
internal fun recordingAnnouncement(elapsedMs: Long, paused: Boolean): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0) / 1000).toInt()
    val minutes = pluralStringResource(R.plurals.home_minutes, totalSeconds / 60, totalSeconds / 60)
    val seconds = pluralStringResource(R.plurals.home_seconds, totalSeconds % 60, totalSeconds % 60)
    val res = if (paused) R.string.home_recording_announcement_paused else R.string.home_recording_announcement
    return stringResource(res, minutes, seconds)
}

/** Подписи ведутся через [joinMeta], чтобы разделитель « · » был один на весь экран. */
@Composable
internal fun joinMeta(parts: List<String?>): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(stringResource(R.string.home_meta_separator))
