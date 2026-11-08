package org.cfm.catscriptor.ui.recordings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.SpeakerInfo
import org.cfm.catscriptor.domain.app.speakerNumber
import org.cfm.catscriptor.viewmodel.recordings.RecordingGroupKey

// Подписи, общие для списка записей, расшифровки и экспорта.

@Composable
internal fun speakersCountLabel(count: Int): String =
    pluralStringResource(R.plurals.speakers_count, count, count)

/** Имя спикера или «Спикер N»: цвет никогда не единственный признак спикера. */
@Composable
internal fun speakerName(speaker: SpeakerInfo?, speakerId: String): String =
    speaker?.displayName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.speaker_default_name, speakerNumber(speakerId) ?: 1)

@Composable
internal fun stageLabel(stage: ProcessingStage): String = stringResource(
    when (stage) {
        ProcessingStage.PREPARE_AUDIO -> R.string.stage_prepare_audio
        ProcessingStage.DETECT_SPEECH -> R.string.stage_detect_speech
        ProcessingStage.TRANSCRIBE -> R.string.stage_transcribe
        ProcessingStage.DIARIZE -> R.string.stage_diarize
        ProcessingStage.CORRECT_TEXT -> R.string.stage_correct_text
    },
)

@Composable
internal fun groupLabel(key: RecordingGroupKey): String = when (key) {
    RecordingGroupKey.Today -> stringResource(R.string.recordings_group_today)
    RecordingGroupKey.Yesterday -> stringResource(R.string.recordings_group_yesterday)
    is RecordingGroupKey.Month -> {
        val name = stringArrayResource(R.array.months_nominative)[key.month - 1]
        if (key.isCurrentYear) name else stringResource(R.string.month_with_year, name, key.year)
    }
}

/** Время справа в строке: для сегодня и вчера `ЧЧ:ММ`, для остальных «3 нояб.». */
@Composable
internal fun recordingTimeLabel(epochMs: Long, key: RecordingGroupKey): String {
    val time = zoned(epochMs)
    return when (key) {
        RecordingGroupKey.Today, RecordingGroupKey.Yesterday -> clock(time)
        is RecordingGroupKey.Month -> shortDate(time, withYear = !key.isCurrentYear)
    }
}

/** «7 нояб., 09:15» для шапки расшифровки. */
@Composable
internal fun dateTimeLabel(epochMs: Long): String {
    val time = zoned(epochMs)
    val withYear = time.year != ZonedDateTime.now(time.zone).year
    return stringResource(R.string.date_with_time, shortDate(time, withYear), clock(time))
}

@Composable
private fun shortDate(time: ZonedDateTime, withYear: Boolean): String {
    val month = stringArrayResource(R.array.months_short)[time.monthValue - 1]
    return if (withYear) {
        stringResource(R.string.date_day_month_year, time.dayOfMonth, month, time.year)
    } else {
        stringResource(R.string.date_day_month, time.dayOfMonth, month)
    }
}

private fun zoned(epochMs: Long): ZonedDateTime = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())

private fun clock(time: ZonedDateTime): String =
    String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
