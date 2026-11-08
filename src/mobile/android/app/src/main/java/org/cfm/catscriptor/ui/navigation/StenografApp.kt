package org.cfm.catscriptor.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import org.cfm.catscriptor.app.AppGraph
import androidx.compose.ui.res.stringResource
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.ui.components.ProgressBanner
import org.cfm.catscriptor.ui.home.HomeRoute
import org.cfm.catscriptor.ui.recordings.RecordingsRoute
import org.cfm.catscriptor.ui.settings.SettingsRoute
import org.cfm.catscriptor.ui.theme.StenografDimens
import org.cfm.catscriptor.ui.theme.StenografMotion
import org.cfm.catscriptor.ui.transcript.TranscriptRoute

/** Корень UI: вкладки, дочерние экраны и общий Snackbar. Логики здесь нет, только навигация. */
@Composable
fun StenografApp(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(TopTab.HOME) }
    var child by rememberSaveable(stateSaver = ChildScreenSaver) { mutableStateOf<ChildScreen?>(null) }
    // Счётчик, а не флаг: повторный запрос должен снова раскрыть карточку этапов.
    var processingDetailsRequest by rememberSaveable { mutableStateOf(0) }

    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbar = remember(hostState, scope) { AppSnackbar(hostState, scope) }
    val jobs by graph.processing.observeJobs().collectAsState(initial = emptyList())
    val hasJobs = jobs.isNotEmpty()
    var detailsShown by remember { mutableStateOf(false) }
    var hiddenJobId by remember { mutableStateOf<String?>(null) }
    // Первая задача в очереди активна; упавшую показывает карточка на главном экране.
    val activeJob = jobs.firstOrNull()?.takeIf { job -> job.stages.none { it.status is StageStatus.Failed } }
    LaunchedEffect(activeJob?.recordingId) { hiddenJobId = null }

    val correction by graph.processing.correction.collectAsState()
    // Скрытие действует до конца текущей правки; новая правка снова показывает плашку.
    var hiddenCorrectionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(correction == null) { if (correction == null) hiddenCorrectionId = null }
    val showCorrection = correction != null && hiddenCorrectionId != correction?.recordingId
    val showJob = !showCorrection && correction == null && activeJob != null && !detailsShown &&
        hiddenJobId != activeJob.recordingId
    val bannerVisible = showCorrection || showJob

    val navigator = remember {
        AppNavigator(
            openTranscript = { child = ChildScreen.Transcript(it) },
            openSettings = { child = ChildScreen.Settings },
            openTab = { child = null; tab = it },
            showProcessingDetails = {
                child = null
                tab = TopTab.HOME
                processingDetailsRequest++
            },
            back = { child = null },
        )
    }

    // Назад с «Записей» возвращает на главный, с главного закрывает приложение.
    BackHandler(enabled = child != null || tab != TopTab.HOME) {
        if (child != null) child = null else tab = TopTab.HOME
    }

    CompositionLocalProvider(LocalAppSnackbar provides snackbar) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            // Вкладки остаются в композиции под дочерним экраном, чтобы сохранить прокрутку,
            // но TalkBack не должен зачитывать скрытые под ним элементы.
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeThrough() },
                label = "tabs",
                modifier = if (child != null) Modifier.clearAndSetSemantics {} else Modifier,
            ) { current ->
                when (current) {
                    TopTab.HOME -> HomeRoute(graph, navigator, processingDetailsRequest) { detailsShown = it }
                    TopTab.RECORDINGS -> RecordingsRoute(graph, navigator)
                }
            }

            AnimatedVisibility(
                visible = child == null,
                enter = fadeIn(tween(StenografMotion.FADE_IN_MS)),
                exit = fadeOut(tween(StenografMotion.FADE_SHORT_MS)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                FloatingBottomBar(
                    selected = tab,
                    showProcessingBadge = hasJobs,
                    onSelect = { tab = it },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(
                            start = StenografDimens.BottomBarMargin,
                            end = StenografDimens.BottomBarMargin,
                            bottom = StenografDimens.BottomBarMargin,
                        ),
                )
            }

            AnimatedContent(
                targetState = child,
                transitionSpec = {
                    (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.92f))
                        .togetherWith(fadeOut(tween(150)) + scaleOut(tween(300), targetScale = 0.96f))
                },
                label = "child",
            ) { screen ->
                if (screen != null) {
                    // Surface перехватывает касания, чтобы они не уходили во вкладку под экраном.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        ScopedViewModels(key = screen.key) {
                            when (screen) {
                                is ChildScreen.Transcript -> TranscriptRoute(graph, navigator, screen.recordingId)
                                ChildScreen.Settings -> SettingsRoute(graph, navigator)
                            }
                        }
                    }
                }
            }

            // Над навигацией (или над плеером расшифровки) и над Snackbar не мешает друг другу.
            val baseOffset = when (child) {
                null -> StenografDimens.BottomBarHeight + StenografDimens.BottomBarMargin + 8.dp
                is ChildScreen.Transcript -> PLAYER_CLEARANCE
                else -> 8.dp
            }
            AnimatedVisibility(
                visible = bannerVisible,
                enter = fadeIn(tween(StenografMotion.FADE_IN_MS)),
                exit = fadeOut(tween(StenografMotion.FADE_SHORT_MS)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = StenografDimens.ScreenPadding, end = StenografDimens.ScreenPadding, bottom = baseOffset),
            ) {
                // Размеры плашки не прыгают при смене содержимого: слот один на обе задачи.
                val current = correction
                val job = activeJob
                if (current != null) {
                    ProgressBanner(
                        label = stringResource(R.string.correction_progress, (current.fraction * 100).toInt()),
                        title = current.title,
                        fraction = current.fraction,
                        onHide = { hiddenCorrectionId = current.recordingId },
                    )
                } else if (job != null) {
                    ProgressBanner(
                        label = stringResource(R.string.processing_progress, (job.overallFraction.coerceIn(0f, 1f) * 100).toInt()),
                        title = job.title,
                        fraction = job.overallFraction,
                        onHide = { hiddenJobId = job.recordingId },
                        onClick = navigator.showProcessingDetails,
                    )
                }
            }

            // Snackbar поднимается над навигацией и плашкой и не перекрывает их.
            SnackbarHost(
                hostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = baseOffset + if (bannerVisible) BANNER_HEIGHT else 0.dp),
            )
        }
    }
}

/** Запас под плеером расшифровки, чтобы плашка правки не закрывала его. */
private val PLAYER_CLEARANCE = 150.dp

/** Высота плашки правки с зазором: на столько поднимается Snackbar. */
private val BANNER_HEIGHT = 80.dp

/** Fade through из Material motion: старый экран исчезает быстрее, чем появляется новый. */
private fun fadeThrough() =
    fadeIn(tween(210, delayMillis = 90, easing = StenografMotion.Emphasized)) +
        scaleIn(tween(210, delayMillis = 90, easing = StenografMotion.Emphasized), initialScale = 0.92f) togetherWith
        fadeOut(tween(90))
