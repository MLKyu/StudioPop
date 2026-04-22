package com.mingeek.studiopop.ui.shorts

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.caption.Srt
import com.mingeek.studiopop.data.editor.CaptionPreset
import com.mingeek.studiopop.data.editor.CaptionStyle
import com.mingeek.studiopop.data.editor.TextLayer
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineCaption
import com.mingeek.studiopop.data.editor.TimelineSegment
import com.mingeek.studiopop.data.editor.VideoEditor
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import com.mingeek.studiopop.data.shorts.GeminiHighlightPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ShortsPhase {
    data object Idle : ShortsPhase
    data class Running(val progress: Float) : ShortsPhase
    data class Success(val outputPath: String) : ShortsPhase
    data class Error(val message: String) : ShortsPhase
}

data class ShortsClip(val uri: Uri, val durationMs: Long)

enum class ShortsMode(val label: String) {
    MANUAL(label = "수동"),
    AI_HIGHLIGHT(label = "AI 하이라이트"),
    TEMPLATE(label = "템플릿"),
}

enum class ShortsTemplate(val label: String, val description: String) {
    HOOK_INTRO(
        label = "훅 인트로",
        description = "첫 3초에 큰 훅 텍스트를 띄워 시선을 붙잡음",
    ),
    BIG_CAPTION(
        label = "큰 자막",
        description = "SRT 자막을 특대 스타일로 번인 (쇼츠 프리셋)",
    ),
    JUMP_CUT(
        label = "점프컷",
        description = "각 클립 앞 N초씩만 잘라 빠르게 이어붙임",
    ),
    BEST_CUTS(
        label = "베스트 컷 자동 선별",
        description = "각 클립의 중간 구간을 자동 추출해 합침",
    ),
    TYPING_CAPTION(
        label = "자막 타이핑 애니메이션",
        description = "SRT 자막을 단어 단위로 순차 노출 (타이핑 효과)",
    ),
}

data class ShortsUiState(
    val mode: ShortsMode = ShortsMode.MANUAL,

    val clips: List<ShortsClip> = emptyList(),
    val srtUri: Uri? = null,
    val cues: List<Cue> = emptyList(),

    // MANUAL
    val burnCaptions: Boolean = true,

    // AI_HIGHLIGHT
    val aiTopic: String = "",
    val aiAnalyzing: Boolean = false,
    val aiSuggestion: GeminiHighlightPicker.HighlightSuggestion? = null,
    val aiError: String? = null,

    // TEMPLATE
    val template: ShortsTemplate = ShortsTemplate.HOOK_INTRO,
    val hookIntroText: String = "",
    val jumpCutDurationMs: Long = JUMP_CUT_DEFAULT_MS,

    val phase: ShortsPhase = ShortsPhase.Idle,

    val latestExportVideoPath: String? = null,
    val latestSrtPath: String? = null,
    val hasProject: Boolean = false,
) {
    val totalDurationMs: Long get() = clips.sumOf { it.durationMs }
    val willTruncate: Boolean get() = totalDurationMs > SHORTS_MAX_MS
    val finalDurationMs: Long get() = totalDurationMs.coerceAtMost(SHORTS_MAX_MS)

    val hasCues: Boolean get() = cues.isNotEmpty()
    val aiSingleClip: ShortsClip? get() = clips.singleOrNull()

    val canExport: Boolean
        get() = phase !is ShortsPhase.Running && when (mode) {
            ShortsMode.MANUAL -> clips.isNotEmpty() && totalDurationMs > 0
            ShortsMode.AI_HIGHLIGHT ->
                aiSingleClip != null && hasCues && aiSuggestion != null
            ShortsMode.TEMPLATE -> clips.isNotEmpty() && totalDurationMs > 0 && templateReady
        }

    val templateReady: Boolean
        get() = when (template) {
            ShortsTemplate.HOOK_INTRO -> hookIntroText.isNotBlank()
            ShortsTemplate.BIG_CAPTION,
            ShortsTemplate.TYPING_CAPTION -> hasCues
            ShortsTemplate.JUMP_CUT,
            ShortsTemplate.BEST_CUTS -> true
        }

    val canAnalyzeAi: Boolean
        get() = phase !is ShortsPhase.Running &&
                !aiAnalyzing &&
                aiSingleClip != null &&
                hasCues &&
                aiTopic.isNotBlank()

    companion object {
        const val SHORTS_MAX_MS = 60_000L
        const val JUMP_CUT_DEFAULT_MS = 1_500L
        const val JUMP_CUT_MIN_MS = 500L
        const val JUMP_CUT_MAX_MS = 5_000L
        const val HOOK_INTRO_DURATION_MS = 3_000L
    }
}

@UnstableApi
class ShortsViewModel(
    application: Application,
    private val videoEditor: VideoEditor,
    private val projectRepository: ProjectRepository,
    private val highlightPicker: GeminiHighlightPicker,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ShortsUiState())
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        _uiState.update { it.copy(hasProject = true) }
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            addClip(project.sourceVideoUri.toUri())
            val srtAsset = projectRepository.latestAsset(id, AssetType.CAPTION_SRT)
            if (srtAsset != null) {
                onSrtSelected(java.io.File(srtAsset.value).toUri())
            }
            refreshQuickLoad()
            backfillOldAssetsSilently(id)
        }
    }

    private suspend fun backfillOldAssetsSilently(projectId: Long) {
        val app = getApplication<Application>() as? StudioPopApp ?: return
        runCatching { app.container.assetBackfillPublisher.backfill(projectId) }
    }

    private suspend fun refreshQuickLoad() {
        val pid = projectId ?: return
        val video = projectRepository.latestAsset(pid, AssetType.EXPORT_VIDEO)?.value
        val srt = projectRepository.latestAsset(pid, AssetType.CAPTION_SRT)?.value
        _uiState.update { it.copy(latestExportVideoPath = video, latestSrtPath = srt) }
    }

    /** 기존 클립을 비우고 최신 편집 결과 영상 1개를 유일 클립으로 세팅. */
    fun loadLatestExportAsClip() {
        val path = _uiState.value.latestExportVideoPath ?: return
        _uiState.update {
            it.copy(
                clips = emptyList(),
                phase = ShortsPhase.Idle,
                aiSuggestion = null,
                aiError = null,
            )
        }
        addClip(java.io.File(path).toUri())
    }

    /** DB 최신 SRT 로 자막 교체 — 숏츠 진입 이후 자막을 새로 만든 경우 수동 refresh. */
    fun loadLatestSrt() {
        val path = _uiState.value.latestSrtPath ?: return
        onSrtSelected(java.io.File(path).toUri())
    }

    fun setMode(mode: ShortsMode) {
        _uiState.update {
            it.copy(
                mode = mode,
                phase = ShortsPhase.Idle,
                aiError = null,
            )
        }
    }

    fun setTemplate(template: ShortsTemplate) {
        _uiState.update { it.copy(template = template) }
    }

    fun setAiTopic(topic: String) {
        _uiState.update { it.copy(aiTopic = topic, aiSuggestion = null, aiError = null) }
    }

    fun setHookIntroText(text: String) {
        _uiState.update { it.copy(hookIntroText = text) }
    }

    fun setJumpCutDurationMs(ms: Long) {
        _uiState.update {
            it.copy(
                jumpCutDurationMs = ms.coerceIn(
                    ShortsUiState.JUMP_CUT_MIN_MS,
                    ShortsUiState.JUMP_CUT_MAX_MS,
                )
            )
        }
    }

    /** 클립 추가. URI 중복 체크 없이 append (같은 영상 두 번 넣기 허용). */
    fun addClip(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
            if (duration <= 0) return@launch
            _uiState.update {
                it.copy(
                    clips = it.clips + ShortsClip(uri, duration),
                    phase = ShortsPhase.Idle,
                    aiSuggestion = null,
                    aiError = null,
                )
            }
        }
    }

    fun removeClip(index: Int) {
        _uiState.update {
            if (index !in it.clips.indices) it
            else it.copy(
                clips = it.clips.toMutableList().apply { removeAt(index) },
                aiSuggestion = null,
                aiError = null,
            )
        }
    }

    fun onSrtSelected(uri: Uri?) {
        viewModelScope.launch {
            if (uri == null) {
                _uiState.update {
                    it.copy(srtUri = null, cues = emptyList(), aiSuggestion = null)
                }
                return@launch
            }
            val cues = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: ""
                }.map(Srt::parse).getOrDefault(emptyList())
            }
            _uiState.update {
                it.copy(srtUri = uri, cues = cues, aiSuggestion = null)
            }
        }
    }

    fun onBurnCaptionsToggle(value: Boolean) {
        _uiState.update { it.copy(burnCaptions = value) }
    }

    // --- AI 하이라이트 ------------------------------------------------------

    fun analyzeAiHighlight() {
        val state = _uiState.value
        val clip = state.aiSingleClip ?: return
        if (!state.canAnalyzeAi) return

        viewModelScope.launch {
            _uiState.update { it.copy(aiAnalyzing = true, aiError = null) }
            val result = highlightPicker.pickHighlight(
                cues = state.cues,
                topic = state.aiTopic,
                videoDurationMs = clip.durationMs,
                maxShortMs = ShortsUiState.SHORTS_MAX_MS,
            )
            result.fold(
                onSuccess = { s ->
                    _uiState.update { it.copy(aiAnalyzing = false, aiSuggestion = s) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            aiAnalyzing = false,
                            aiSuggestion = null,
                            aiError = e.message ?: "분석 실패",
                        )
                    }
                },
            )
        }
    }

    // --- 내보내기 ----------------------------------------------------------

    fun startExport() {
        val state = _uiState.value
        if (!state.canExport) return

        viewModelScope.launch {
            _uiState.update { it.copy(phase = ShortsPhase.Running(0f)) }

            val timeline = when (state.mode) {
                ShortsMode.MANUAL -> buildManualTimeline(state)
                ShortsMode.AI_HIGHLIGHT -> buildAiHighlightTimeline(state)
                ShortsMode.TEMPLATE -> buildTemplateTimeline(state)
            }

            if (timeline == null || timeline.segments.isEmpty()) {
                _uiState.update {
                    it.copy(phase = ShortsPhase.Error("타임라인 구성 실패"))
                }
                return@launch
            }

            videoEditor.exportTimeline(
                timeline = timeline,
                aspectRatio = 9f / 16f,
                onProgress = { p ->
                    _uiState.update {
                        if (it.phase is ShortsPhase.Running) it.copy(phase = ShortsPhase.Running(p))
                        else it
                    }
                },
            ).fold(
                onSuccess = { file ->
                    _uiState.update { it.copy(phase = ShortsPhase.Success(file.absolutePath)) }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.SHORTS,
                            value = file.absolutePath,
                            label = shortsAssetLabel(state),
                        )
                    }
                    refreshQuickLoad()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = ShortsPhase.Error(e.message ?: "내보내기 실패"))
                    }
                },
            )
        }
    }

    private fun shortsAssetLabel(state: ShortsUiState): String = when (state.mode) {
        ShortsMode.MANUAL -> "숏츠 (수동·${state.clips.size}클립)"
        ShortsMode.AI_HIGHLIGHT -> "숏츠 (AI 하이라이트)"
        ShortsMode.TEMPLATE -> "숏츠 (${state.template.label})"
    }

    // --- Timeline 조립 -----------------------------------------------------

    private fun buildManualTimeline(state: ShortsUiState): Timeline {
        val segments = buildSegmentsFromClipStart(state.clips, ShortsUiState.SHORTS_MAX_MS)
        val captions = if (state.burnCaptions) state.cues.toTimelineCaptions() else emptyList()
        return Timeline(segments = segments, captions = captions)
    }

    private fun buildAiHighlightTimeline(state: ShortsUiState): Timeline? {
        val clip = state.aiSingleClip ?: return null
        val s = state.aiSuggestion ?: return null
        val start = s.startMs.coerceIn(0L, clip.durationMs)
        val end = s.endMs.coerceIn(start, clip.durationMs)
        if (end <= start) return null

        val segment = TimelineSegment(
            sourceUri = clip.uri,
            sourceStartMs = start,
            sourceEndMs = end,
        )
        val textLayers = buildList {
            s.hookText?.takeIf { it.isNotBlank() }?.let {
                add(
                    TextLayer(
                        sourceStartMs = start,
                        sourceEndMs = end,
                        text = it,
                        style = CaptionStyle(
                            preset = CaptionPreset.SHORTS,
                            anchorY = 0.7f,
                            sizeScale = 1.3f,
                        ),
                    )
                )
            }
            s.subText?.takeIf { it.isNotBlank() }?.let {
                add(
                    TextLayer(
                        sourceStartMs = start,
                        sourceEndMs = end,
                        text = it,
                        style = CaptionStyle(
                            preset = CaptionPreset.MINIMAL,
                            anchorY = 0.45f,
                            sizeScale = 0.9f,
                        ),
                    )
                )
            }
        }
        return Timeline(segments = listOf(segment), textLayers = textLayers)
    }

    private fun buildTemplateTimeline(state: ShortsUiState): Timeline? = when (state.template) {
        ShortsTemplate.HOOK_INTRO -> buildHookIntroTimeline(state)
        ShortsTemplate.BIG_CAPTION -> buildBigCaptionTimeline(state)
        ShortsTemplate.JUMP_CUT -> buildJumpCutTimeline(state)
        ShortsTemplate.BEST_CUTS -> buildBestCutsTimeline(state)
        ShortsTemplate.TYPING_CAPTION -> buildTypingTimeline(state)
    }

    private fun buildHookIntroTimeline(state: ShortsUiState): Timeline {
        val segments = buildSegmentsFromClipStart(state.clips, ShortsUiState.SHORTS_MAX_MS)
        val firstClip = segments.firstOrNull() ?: return Timeline(segments = emptyList())
        val hookEnd = minOf(firstClip.sourceStartMs + ShortsUiState.HOOK_INTRO_DURATION_MS, firstClip.sourceEndMs)
        val hookLayer = TextLayer(
            sourceStartMs = firstClip.sourceStartMs,
            sourceEndMs = hookEnd,
            text = state.hookIntroText,
            style = CaptionStyle(
                preset = CaptionPreset.SHORTS,
                anchorY = 0.6f,
                sizeScale = 1.4f,
            ),
        )
        return Timeline(segments = segments, textLayers = listOf(hookLayer))
    }

    private fun buildBigCaptionTimeline(state: ShortsUiState): Timeline {
        val segments = buildSegmentsFromClipStart(state.clips, ShortsUiState.SHORTS_MAX_MS)
        val captions = state.cues.map { c ->
            TimelineCaption(
                sourceStartMs = c.startMs,
                sourceEndMs = c.endMs,
                text = c.text,
                style = CaptionStyle(
                    preset = CaptionPreset.SHORTS,
                    anchorY = -0.4f,
                    sizeScale = 1.5f,
                ),
            )
        }
        return Timeline(segments = segments, captions = captions)
    }

    private fun buildJumpCutTimeline(state: ShortsUiState): Timeline {
        val take = state.jumpCutDurationMs
        val segments = mutableListOf<TimelineSegment>()
        var remaining = ShortsUiState.SHORTS_MAX_MS
        for (clip in state.clips) {
            if (remaining <= 0) break
            val actual = minOf(clip.durationMs, take, remaining)
            if (actual <= 0) continue
            segments += TimelineSegment(
                sourceUri = clip.uri,
                sourceStartMs = 0L,
                sourceEndMs = actual,
            )
            remaining -= actual
        }
        return Timeline(segments = segments)
    }

    private fun buildBestCutsTimeline(state: ShortsUiState): Timeline {
        val clips = state.clips
        if (clips.isEmpty()) return Timeline(segments = emptyList())
        val perClipMax = ShortsUiState.SHORTS_MAX_MS / clips.size
        val segments = mutableListOf<TimelineSegment>()
        var remaining = ShortsUiState.SHORTS_MAX_MS
        for (clip in clips) {
            if (remaining <= 0) break
            // 각 클립 길이의 절반, 하지만 perClipMax 와 남은 시간 중 최솟값만큼 중앙에서 추출
            val halfClip = clip.durationMs / 2
            val take = minOf(halfClip.coerceAtLeast(1_000L), perClipMax, remaining)
                .coerceAtMost(clip.durationMs)
            if (take <= 0) continue
            val center = clip.durationMs / 2
            val start = (center - take / 2).coerceAtLeast(0L)
            val end = minOf(start + take, clip.durationMs)
            segments += TimelineSegment(
                sourceUri = clip.uri,
                sourceStartMs = start,
                sourceEndMs = end,
            )
            remaining -= (end - start)
        }
        return Timeline(segments = segments)
    }

    private fun buildTypingTimeline(state: ShortsUiState): Timeline {
        val segments = buildSegmentsFromClipStart(state.clips, ShortsUiState.SHORTS_MAX_MS)
        val layers = buildTypingLayers(state.cues)
        return Timeline(segments = segments, textLayers = layers)
    }

    /**
     * 각 클립을 처음부터 순차로 이어 붙이되, 누적 길이가 [maxMs] 를 넘으면 마지막 세그먼트를 잘라 맞춤.
     * 숏츠 모드 대부분이 "클립 앞부터 쌓기" 패턴이라 재사용.
     */
    private fun buildSegmentsFromClipStart(
        clips: List<ShortsClip>,
        maxMs: Long,
    ): List<TimelineSegment> {
        val result = mutableListOf<TimelineSegment>()
        var remaining = maxMs
        for (clip in clips) {
            if (remaining <= 0) break
            val take = minOf(clip.durationMs, remaining)
            result += TimelineSegment(
                sourceUri = clip.uri,
                sourceStartMs = 0L,
                sourceEndMs = take,
            )
            remaining -= take
        }
        return result
    }

    /**
     * SRT cue 를 단어 단위로 순차 노출하는 TextLayer 세트로 변환. 한 단어의 노출
     * 시간은 cue 길이 / 단어 수. 너무 짧은 간격은 150ms 로 올림.
     */
    private fun buildTypingLayers(cues: List<Cue>): List<TextLayer> {
        val style = CaptionStyle(
            preset = CaptionPreset.SHORTS,
            anchorY = -0.4f,
            sizeScale = 1.3f,
        )
        val layers = mutableListOf<TextLayer>()
        for (cue in cues) {
            val words = cue.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            val total = (cue.endMs - cue.startMs).coerceAtLeast(0L)
            if (total <= 0) continue
            val step = (total / words.size).coerceAtLeast(150L)
            for (i in words.indices) {
                val start = cue.startMs + i * step
                val end = if (i == words.size - 1) {
                    cue.endMs
                } else {
                    minOf(cue.startMs + (i + 1) * step, cue.endMs)
                }
                if (end <= start) continue
                layers += TextLayer(
                    sourceStartMs = start,
                    sourceEndMs = end,
                    text = words.subList(0, i + 1).joinToString(" "),
                    style = style,
                )
            }
        }
        return layers
    }

    private fun List<Cue>.toTimelineCaptions(): List<TimelineCaption> = map {
        TimelineCaption(
            sourceStartMs = it.startMs,
            sourceEndMs = it.endMs,
            text = it.text,
        )
    }

    fun dismissMessage() = _uiState.update { it.copy(phase = ShortsPhase.Idle, aiError = null) }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication<Application>(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                ShortsViewModel(
                    application = app,
                    videoEditor = app.container.videoEditor,
                    projectRepository = app.container.projectRepository,
                    highlightPicker = app.container.geminiHighlightPicker,
                )
            }
        }
    }
}
