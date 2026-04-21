package com.mingeek.studiopop.ui.editor

import android.app.Application
import android.graphics.Bitmap
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
import com.mingeek.studiopop.data.caption.Srt
import com.mingeek.studiopop.data.editor.AudioTrack
import com.mingeek.studiopop.data.editor.CaptionStyle
import com.mingeek.studiopop.data.editor.CutRange
import com.mingeek.studiopop.data.editor.FrameStripGenerator
import com.mingeek.studiopop.data.editor.TextLayer
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.editor.TimelineCaption
import com.mingeek.studiopop.data.editor.VideoEditor
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import com.mingeek.studiopop.ui.editor.components.EditableTextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportPhase {
    data object Idle : ExportPhase
    data class Running(val progress: Float) : ExportPhase
    data class Success(val outputPath: String) : ExportPhase
    data class Error(val message: String) : ExportPhase
}

enum class EditKind { CAPTION, TEXT_LAYER }

data class EditorUiState(
    val timeline: Timeline = Timeline(segments = emptyList()),
    /** 영상 Uri → (총 길이, 프레임 스트립). 세그먼트가 sourceUri 별로 조회. */
    val frameStrips: Map<Uri, Pair<Long, List<Bitmap>>> = emptyMap(),
    val playheadOutputMs: Long = 0L,
    val isPlaying: Boolean = false,
    val seekRequest: Long? = null,
    val editingItem: EditableTextItem? = null,
    val editingKind: EditKind? = null,
    val phase: ExportPhase = ExportPhase.Idle,
) {
    val hasVideo: Boolean get() = timeline.segments.isNotEmpty()
    val canExport: Boolean
        get() = hasVideo && phase !is ExportPhase.Running
    val canDelete: Boolean get() = timeline.segments.size > 1
}

@UnstableApi
class EditorViewModel(
    application: Application,
    private val videoEditor: VideoEditor,
    private val frameStripGenerator: FrameStripGenerator,
    private val projectRepository: ProjectRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            replaceWithVideo(project.sourceVideoUri.toUri())
            val srtAsset = projectRepository.latestAsset(id, AssetType.CAPTION_SRT)
            srtAsset?.let { importSrtFromPath(it.value) }
        }
    }

    /**
     * 타임라인을 이 영상 단독으로 리셋. 홈 "영상 변경" 에서 사용.
     */
    fun onVideoSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch { replaceWithVideo(uri) }
    }

    /**
     * 현재 타임라인 끝에 이 영상을 통째로 추가. 여러 영상을 이어붙일 때.
     */
    fun addVideoToTimeline(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
            if (duration <= 0) return@launch
            _uiState.update {
                it.copy(
                    timeline = it.timeline.appendVideo(uri, duration),
                    phase = ExportPhase.Idle,
                )
            }
            ensureFrameStrip(uri, duration)
        }
    }

    private suspend fun replaceWithVideo(uri: Uri) {
        val duration = withContext(Dispatchers.IO) { readDurationMs(uri) }
        _uiState.update {
            it.copy(
                timeline = Timeline.single(uri, duration),
                playheadOutputMs = 0L,
                isPlaying = false,
                frameStrips = emptyMap(),
                phase = ExportPhase.Idle,
            )
        }
        ensureFrameStrip(uri, duration)
    }

    private fun ensureFrameStrip(uri: Uri, duration: Long) {
        if (_uiState.value.frameStrips.containsKey(uri)) return
        viewModelScope.launch {
            val strip = frameStripGenerator.generate(uri, duration)
            _uiState.update { s ->
                s.copy(frameStrips = s.frameStrips + (uri to (duration to strip)))
            }
        }
    }

    fun onSrtPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: ""
                }.getOrDefault("")
            }
            if (text.isNotBlank()) importSrtText(text)
        }
    }

    private fun importSrtFromPath(path: String) {
        val text = runCatching { java.io.File(path).readText() }.getOrNull() ?: return
        importSrtText(text)
    }

    private fun importSrtText(text: String) {
        val cues = Srt.parse(text)
        val timelineCaptions = cues.map {
            TimelineCaption(
                sourceStartMs = it.startMs,
                sourceEndMs = it.endMs,
                text = it.text,
            )
        }
        _uiState.update { state ->
            state.copy(timeline = state.timeline.copy(captions = timelineCaptions))
        }
    }

    // --- 재생 제어 ---
    fun togglePlay() = _uiState.update { it.copy(isPlaying = !it.isPlaying) }

    fun onPlayheadChange(outputMs: Long) {
        _uiState.update { it.copy(playheadOutputMs = outputMs) }
    }

    fun onPlayheadDragged(outputMs: Long) {
        _uiState.update {
            it.copy(playheadOutputMs = outputMs, seekRequest = outputMs, isPlaying = false)
        }
    }

    fun consumeSeekRequest() = _uiState.update { it.copy(seekRequest = null) }

    // --- 세그먼트 조작 ---
    fun onDividerDrag(prevSegId: String, nextSegId: String, sourceDeltaMs: Long) {
        _uiState.update {
            val newTimeline = it.timeline.moveBoundary(prevSegId, nextSegId, sourceDeltaMs)
            // 경계 이동으로 출력 길이가 바뀌었을 수 있으므로 플레이헤드 보정
            val newPlayhead = it.playheadOutputMs.coerceAtMost(newTimeline.outputDurationMs)
            it.copy(timeline = newTimeline, playheadOutputMs = newPlayhead)
        }
    }

    // --- 범위 삭제 (CutRange) ---
    /**
     * 현재 플레이헤드 위치에 기본 duration 의 CutRange 생성.
     */
    fun addCutRangeAtPlayhead() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs) ?: return
        val srcEnd = (sourceT + DEFAULT_CUT_DURATION_MS).coerceAtMost(seg.sourceEndMs)
        val cut = CutRange(
            sourceUri = seg.sourceUri,
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
        )
        _uiState.update { it.copy(timeline = it.timeline.addCutRange(cut)) }
    }

    fun onCutRangeResize(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _uiState.update { state ->
            val cut = state.timeline.cutRanges.firstOrNull { it.id == id } ?: return@update state
            val (minStart, maxEnd) = sourceBoundsFor(state.timeline, cut.sourceUri)
                ?: return@update state
            val newStart = (cut.sourceStartMs + startDeltaMs)
                .coerceIn(minStart, maxEnd - MIN_OVERLAY_DURATION_MS)
            val newEnd = (cut.sourceEndMs + endDeltaMs)
                .coerceIn(newStart + MIN_OVERLAY_DURATION_MS, maxEnd)
            state.copy(timeline = state.timeline.updateCutRange(cut.copy(sourceStartMs = newStart, sourceEndMs = newEnd)))
        }
    }

    fun onCutRangeTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val cut = state.timeline.cutRanges.firstOrNull { it.id == id } ?: return@update state
            val (minStart, maxEnd) = sourceBoundsFor(state.timeline, cut.sourceUri)
                ?: return@update state
            val duration = cut.sourceEndMs - cut.sourceStartMs
            val newStart = (cut.sourceStartMs + deltaMs)
                .coerceIn(minStart, maxEnd - duration)
            state.copy(timeline = state.timeline.updateCutRange(
                cut.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    /** 주어진 sourceUri 를 참조하는 segment 들의 source 범위 합집합 [min, max]. */
    private fun sourceBoundsFor(timeline: Timeline, uri: Uri): Pair<Long, Long>? {
        val matching = timeline.segments.filter { it.sourceUri == uri }
        if (matching.isEmpty()) return null
        return matching.minOf { it.sourceStartMs } to matching.maxOf { it.sourceEndMs }
    }

    fun deleteCutRange(id: String) {
        _uiState.update { it.copy(timeline = it.timeline.deleteCutRange(id)) }
    }

    /**
     * 현재 플레이헤드가 위치한 세그먼트를 삭제. 여러 영상 추가한 상태에서 특정 영상 통째로 빼기.
     */
    fun deleteCurrentSegment() {
        val state = _uiState.value
        if (state.timeline.segments.size <= 1) return
        val (seg, _) = state.timeline.mapOutputToSource(state.playheadOutputMs) ?: return
        _uiState.update {
            val newTimeline = it.timeline.deleteSegment(seg.id)
            val newPlayhead = it.playheadOutputMs.coerceAtMost(newTimeline.outputDurationMs)
            it.copy(
                timeline = newTimeline,
                playheadOutputMs = newPlayhead,
                seekRequest = newPlayhead,
            )
        }
    }

    // --- 자막 (CAPTION) ---
    fun openCaptionEditorForNew() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs)
            ?: return
        val srcEnd = (sourceT + DEFAULT_NEW_DURATION_MS).coerceAtMost(seg.sourceEndMs)
        val cap = TimelineCaption(
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
            text = "",
        )
        _uiState.update {
            it.copy(
                editingItem = cap.toEditable(existsInTimeline = false),
                editingKind = EditKind.CAPTION,
            )
        }
    }

    fun openCaptionEditorFor(id: String) {
        val cap = _uiState.value.timeline.captions.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                editingItem = cap.toEditable(existsInTimeline = true),
                editingKind = EditKind.CAPTION,
            )
        }
    }

    // --- 텍스트 레이어 ---
    fun openTextLayerEditorForNew() {
        val state = _uiState.value
        val (seg, sourceT) = state.timeline.mapOutputToSource(state.playheadOutputMs)
            ?: return
        val srcEnd = (sourceT + DEFAULT_NEW_DURATION_MS).coerceAtMost(seg.sourceEndMs)
        val layer = TextLayer(
            sourceStartMs = sourceT,
            sourceEndMs = srcEnd,
            text = "",
        )
        _uiState.update {
            it.copy(
                editingItem = layer.toEditable(existsInTimeline = false),
                editingKind = EditKind.TEXT_LAYER,
            )
        }
    }

    fun openTextLayerEditorFor(id: String) {
        val layer = _uiState.value.timeline.textLayers.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                editingItem = layer.toEditable(existsInTimeline = true),
                editingKind = EditKind.TEXT_LAYER,
            )
        }
    }

    fun closeEditor() = _uiState.update { it.copy(editingItem = null, editingKind = null) }

    fun saveEditingItem(updated: EditableTextItem) {
        val state = _uiState.value
        val kind = state.editingKind ?: return
        val newTimeline = when (kind) {
            EditKind.CAPTION -> {
                val caption = TimelineCaption(
                    id = updated.id,
                    sourceStartMs = updated.sourceStartMs,
                    sourceEndMs = updated.sourceEndMs,
                    text = updated.text,
                    style = updated.style,
                )
                if (updated.existsInTimeline) state.timeline.updateCaption(caption)
                else state.timeline.addCaption(caption)
            }
            EditKind.TEXT_LAYER -> {
                val layer = TextLayer(
                    id = updated.id,
                    sourceStartMs = updated.sourceStartMs,
                    sourceEndMs = updated.sourceEndMs,
                    text = updated.text,
                    style = updated.style,
                )
                if (updated.existsInTimeline) state.timeline.updateTextLayer(layer)
                else state.timeline.addTextLayer(layer)
            }
        }
        _uiState.update {
            it.copy(timeline = newTimeline, editingItem = null, editingKind = null)
        }
    }

    /**
     * 타임라인에서 자막 막대 양끝 드래그 → 시각 범위 조정.
     * @param startDeltaMs sourceStartMs 에 더할 값 (왼쪽 핸들)
     * @param endDeltaMs sourceEndMs 에 더할 값 (오른쪽 핸들)
     */
    fun onCaptionResize(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _uiState.update { state ->
            val cap = state.timeline.captions.firstOrNull { it.id == id } ?: return@update state
            val newStart = (cap.sourceStartMs + startDeltaMs).coerceAtLeast(0L)
            val newEnd = (cap.sourceEndMs + endDeltaMs).coerceAtLeast(newStart + MIN_OVERLAY_DURATION_MS)
            state.copy(timeline = state.timeline.updateCaption(cap.copy(sourceStartMs = newStart, sourceEndMs = newEnd)))
        }
    }

    fun onTextLayerResize(id: String, startDeltaMs: Long, endDeltaMs: Long) {
        _uiState.update { state ->
            val layer = state.timeline.textLayers.firstOrNull { it.id == id } ?: return@update state
            val newStart = (layer.sourceStartMs + startDeltaMs).coerceAtLeast(0L)
            val newEnd = (layer.sourceEndMs + endDeltaMs).coerceAtLeast(newStart + MIN_OVERLAY_DURATION_MS)
            state.copy(timeline = state.timeline.updateTextLayer(layer.copy(sourceStartMs = newStart, sourceEndMs = newEnd)))
        }
    }

    /**
     * 타임라인 막대 롱프레스 후 드래그 → 전체 평행이동 (duration 유지).
     */
    fun onCaptionTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val cap = state.timeline.captions.firstOrNull { it.id == id } ?: return@update state
            val newStart = (cap.sourceStartMs + deltaMs).coerceAtLeast(0L)
            val duration = cap.sourceEndMs - cap.sourceStartMs
            state.copy(timeline = state.timeline.updateCaption(
                cap.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    fun onTextLayerTranslate(id: String, deltaMs: Long) {
        _uiState.update { state ->
            val layer = state.timeline.textLayers.firstOrNull { it.id == id } ?: return@update state
            val newStart = (layer.sourceStartMs + deltaMs).coerceAtLeast(0L)
            val duration = layer.sourceEndMs - layer.sourceStartMs
            state.copy(timeline = state.timeline.updateTextLayer(
                layer.copy(sourceStartMs = newStart, sourceEndMs = newStart + duration)
            ))
        }
    }

    /**
     * 미리보기 세로 드래그 → anchorY 조정 (NDC -1..1).
     */
    fun onCaptionAnchorChange(id: String, newAnchorY: Float) {
        _uiState.update { state ->
            val cap = state.timeline.captions.firstOrNull { it.id == id } ?: return@update state
            val clipped = newAnchorY.coerceIn(-1f, 1f)
            state.copy(timeline = state.timeline.updateCaption(cap.copy(style = cap.style.copy(anchorY = clipped))))
        }
    }

    fun onTextLayerAnchorChange(id: String, newAnchorY: Float) {
        _uiState.update { state ->
            val layer = state.timeline.textLayers.firstOrNull { it.id == id } ?: return@update state
            val clipped = newAnchorY.coerceIn(-1f, 1f)
            state.copy(timeline = state.timeline.updateTextLayer(layer.copy(style = layer.style.copy(anchorY = clipped))))
        }
    }

    fun deleteEditingItem() {
        val state = _uiState.value
        val id = state.editingItem?.id ?: return
        val kind = state.editingKind ?: return
        val newTimeline = when (kind) {
            EditKind.CAPTION -> state.timeline.deleteCaption(id)
            EditKind.TEXT_LAYER -> state.timeline.deleteTextLayer(id)
        }
        _uiState.update {
            it.copy(timeline = newTimeline, editingItem = null, editingKind = null)
        }
    }

    // --- 전환 ---
    fun toggleTransitions() {
        _uiState.update {
            val t = it.timeline.transitions.copy(enabled = !it.timeline.transitions.enabled)
            it.copy(timeline = it.timeline.withTransitions(t))
        }
    }

    // --- BGM ---
    fun onBgmPicked(uri: Uri?) {
        if (uri == null) return
        _uiState.update {
            it.copy(
                timeline = it.timeline.withAudioTrack(
                    AudioTrack(uri = uri, replaceOriginal = true)
                )
            )
        }
    }

    fun removeBgm() {
        _uiState.update { it.copy(timeline = it.timeline.withAudioTrack(null)) }
    }

    // --- Export ---
    fun startExport() {
        val state = _uiState.value
        if (!state.hasVideo) return

        viewModelScope.launch {
            _uiState.update { it.copy(phase = ExportPhase.Running(0f)) }
            videoEditor.exportTimeline(
                timeline = state.timeline,
                onProgress = { p ->
                    _uiState.update { s ->
                        if (s.phase is ExportPhase.Running) s.copy(phase = ExportPhase.Running(p))
                        else s
                    }
                },
            ).fold(
                onSuccess = { file ->
                    _uiState.update { it.copy(phase = ExportPhase.Success(file.absolutePath)) }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.EXPORT_VIDEO,
                            value = file.absolutePath,
                            label = "편집본 (${state.timeline.segments.size}컷)",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = ExportPhase.Error(e.message ?: "내보내기 실패"))
                    }
                },
            )
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(phase = ExportPhase.Idle) }

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

    private fun TimelineCaption.toEditable(existsInTimeline: Boolean) = EditableTextItem(
        id = id,
        text = text,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        style = style,
        existsInTimeline = existsInTimeline,
    )

    private fun TextLayer.toEditable(existsInTimeline: Boolean) = EditableTextItem(
        id = id,
        text = text,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        style = style,
        existsInTimeline = existsInTimeline,
    )

    companion object {
        private const val DEFAULT_NEW_DURATION_MS = 2_000L
        private const val DEFAULT_CUT_DURATION_MS = 2_000L
        private const val MIN_OVERLAY_DURATION_MS = 200L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                EditorViewModel(
                    application = app,
                    videoEditor = app.container.videoEditor,
                    frameStripGenerator = app.container.frameStripGenerator,
                    projectRepository = app.container.projectRepository,
                )
            }
        }
    }
}
