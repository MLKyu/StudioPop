package com.mingeek.studiopop.ui.thumbnail

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import com.mingeek.studiopop.data.thumbnail.ClaudeCopywriter
import com.mingeek.studiopop.data.thumbnail.FrameExtractor
import com.mingeek.studiopop.data.thumbnail.ThumbnailComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ThumbnailPhase {
    data object Idle : ThumbnailPhase
    data object ExtractingFrame : ThumbnailPhase
    data object Composing : ThumbnailPhase
    data object SuggestingCopies : ThumbnailPhase
    data class Saved(val path: String) : ThumbnailPhase
    data class Error(val message: String) : ThumbnailPhase
}

data class ThumbnailUiState(
    val videoUri: Uri? = null,
    val durationMs: Long = 0L,
    val framePositionMs: Long = 0L,
    val frameBitmap: Bitmap? = null,
    val composedBitmap: Bitmap? = null,
    val mainText: String = "",
    val subText: String = "",
    val topic: String = "",
    val copyCandidates: List<String> = emptyList(),
    val phase: ThumbnailPhase = ThumbnailPhase.Idle,
)

class ThumbnailViewModel(
    application: Application,
    private val frameExtractor: FrameExtractor,
    private val composer: ThumbnailComposer,
    private val copywriter: ClaudeCopywriter,
    private val projectRepository: ProjectRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ThumbnailUiState())
    val uiState: StateFlow<ThumbnailUiState> = _uiState.asStateFlow()

    private var projectId: Long? = null

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            onVideoSelected(project.sourceVideoUri.toUri())
        }
    }

    fun onVideoSelected(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val info = frameExtractor.readInfo(uri)
            _uiState.update {
                it.copy(
                    videoUri = uri,
                    durationMs = info.durationMs,
                    framePositionMs = info.durationMs / 2,
                    frameBitmap = null,
                    composedBitmap = null,
                    phase = ThumbnailPhase.Idle,
                )
            }
            extractFrame()
        }
    }

    fun onPositionChange(ms: Long) {
        _uiState.update { it.copy(framePositionMs = ms.coerceIn(0L, it.durationMs)) }
    }

    fun extractFrame() {
        val uri = _uiState.value.videoUri ?: return
        val pos = _uiState.value.framePositionMs
        viewModelScope.launch {
            _uiState.update { it.copy(phase = ThumbnailPhase.ExtractingFrame) }
            val bmp = frameExtractor.extractFrame(uri, pos)
            _uiState.update {
                it.copy(
                    frameBitmap = bmp,
                    phase = if (bmp == null) ThumbnailPhase.Error("프레임 추출 실패") else ThumbnailPhase.Idle,
                    composedBitmap = null,
                )
            }
        }
    }

    fun onMainTextChange(value: String) = _uiState.update { it.copy(mainText = value) }
    fun onSubTextChange(value: String) = _uiState.update { it.copy(subText = value) }
    fun onTopicChange(value: String) = _uiState.update { it.copy(topic = value) }

    fun composePreview() {
        val base = _uiState.value.frameBitmap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(phase = ThumbnailPhase.Composing) }
            val composed = runCatching {
                composer.compose(
                    base,
                    ThumbnailComposer.Spec(
                        mainText = _uiState.value.mainText,
                        subText = _uiState.value.subText,
                    )
                )
            }
            composed.fold(
                onSuccess = { bmp ->
                    _uiState.update { it.copy(composedBitmap = bmp, phase = ThumbnailPhase.Idle) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(phase = ThumbnailPhase.Error("합성 실패: ${e.message}")) }
                },
            )
        }
    }

    fun suggestCopies() {
        val topic = _uiState.value.topic.ifBlank { _uiState.value.mainText }.ifBlank { "유튜브 뽑기 영상" }
        viewModelScope.launch {
            _uiState.update { it.copy(phase = ThumbnailPhase.SuggestingCopies) }
            copywriter.suggestThumbnailCopies(topic = topic).fold(
                onSuccess = { list ->
                    _uiState.update { it.copy(copyCandidates = list, phase = ThumbnailPhase.Idle) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(phase = ThumbnailPhase.Error(e.message ?: "제안 실패")) }
                },
            )
        }
    }

    fun adoptCopy(text: String) {
        _uiState.update { it.copy(mainText = text) }
    }

    fun savePng() {
        val bmp = _uiState.value.composedBitmap ?: return
        viewModelScope.launch {
            val outDir = getApplication<Application>().getExternalFilesDir(null)
                ?: getApplication<Application>().filesDir
            val outFile = File(outDir, "thumbnail_${System.currentTimeMillis()}.png")
            runCatching {
                withContext(Dispatchers.IO) { composer.saveAsPng(bmp, outFile) }
            }.fold(
                onSuccess = {
                    _uiState.update { it.copy(phase = ThumbnailPhase.Saved(outFile.absolutePath)) }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.THUMBNAIL,
                            value = outFile.absolutePath,
                            label = _uiState.value.mainText.take(30),
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(phase = ThumbnailPhase.Error("저장 실패: ${e.message}")) }
                },
            )
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(phase = ThumbnailPhase.Idle) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                val c = app.container
                ThumbnailViewModel(
                    application = app,
                    frameExtractor = c.frameExtractor,
                    composer = c.thumbnailComposer,
                    copywriter = c.claudeCopywriter,
                    projectRepository = c.projectRepository,
                )
            }
        }
    }
}
