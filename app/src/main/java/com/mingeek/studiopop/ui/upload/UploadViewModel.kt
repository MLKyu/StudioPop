package com.mingeek.studiopop.ui.upload

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.auth.AuthOutcome
import com.mingeek.studiopop.data.auth.AuthTokenStore
import com.mingeek.studiopop.data.auth.GoogleAuthManager
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import com.mingeek.studiopop.data.youtube.PrivacyStatus
import com.mingeek.studiopop.data.youtube.VideoMetadata
import com.mingeek.studiopop.data.youtube.YouTubeUploader
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UploadPhase {
    data object Idle : UploadPhase
    data object Authorizing : UploadPhase
    data class Uploading(val progress: Float) : UploadPhase
    data class Success(val videoId: String) : UploadPhase
    data class Error(val message: String) : UploadPhase
}

data class UploadUiState(
    val videoUri: Uri? = null,
    val title: String = "",
    val description: String = "",
    val tagsRaw: String = "",
    val privacy: PrivacyStatus = PrivacyStatus.PRIVATE,
    val hasAccessToken: Boolean = false,
    val attachThumbnail: Boolean = true,
    val thumbnailPath: String? = null,
    val phase: UploadPhase = UploadPhase.Idle,
) {
    val canUpload: Boolean
        get() = videoUri != null &&
                title.isNotBlank() &&
                hasAccessToken &&
                phase !is UploadPhase.Uploading
}

class UploadViewModel(
    application: Application,
    private val authManager: GoogleAuthManager,
    private val tokenStore: AuthTokenStore,
    private val uploader: YouTubeUploader,
    private val projectRepository: ProjectRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    private val _consentRequest = MutableStateFlow<PendingIntent?>(null)
    val consentRequest: StateFlow<PendingIntent?> = _consentRequest.asStateFlow()

    private var projectId: Long? = null

    init {
        viewModelScope.launch {
            val cached = tokenStore.accessToken.firstOrNull()
            if (!cached.isNullOrBlank()) {
                _uiState.update { it.copy(hasAccessToken = true) }
            }
        }
    }

    fun bindProject(id: Long?) {
        if (id == null || id <= 0 || id == projectId) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getProject(id) ?: return@launch
            // 편집본이 있으면 편집본 사용, 없으면 원본
            val export = projectRepository.latestAsset(id, AssetType.EXPORT_VIDEO)
            val uri = export?.value?.let { File(it).toUri() }
                ?: project.sourceVideoUri.toUri()
            val thumbnail = projectRepository.latestAsset(id, AssetType.THUMBNAIL)
            _uiState.update {
                it.copy(
                    videoUri = uri,
                    title = if (it.title.isBlank()) project.title else it.title,
                    thumbnailPath = thumbnail?.value,
                )
            }
        }
    }

    fun onAttachThumbnailToggle(v: Boolean) =
        _uiState.update { it.copy(attachThumbnail = v) }

    fun onVideoSelected(uri: Uri?) {
        _uiState.update { it.copy(videoUri = uri) }
    }

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value) }
    fun onDescriptionChange(value: String) = _uiState.update { it.copy(description = value) }
    fun onTagsChange(value: String) = _uiState.update { it.copy(tagsRaw = value) }
    fun onPrivacyChange(value: PrivacyStatus) = _uiState.update { it.copy(privacy = value) }

    fun signIn() {
        _uiState.update { it.copy(phase = UploadPhase.Authorizing) }
        viewModelScope.launch {
            when (val outcome = authManager.requestYouTubeUploadAuthorization()) {
                is AuthOutcome.Success -> {
                    tokenStore.save(outcome.accessToken)
                    _uiState.update { it.copy(hasAccessToken = true, phase = UploadPhase.Idle) }
                }
                is AuthOutcome.NeedsUserConsent -> {
                    _consentRequest.value = outcome.pendingIntent
                    _uiState.update { it.copy(phase = UploadPhase.Idle) }
                }
                is AuthOutcome.Failure -> {
                    _uiState.update {
                        it.copy(phase = UploadPhase.Error(outcome.error.message ?: "인증 실패"))
                    }
                }
            }
        }
    }

    fun onConsentLaunched() {
        _consentRequest.value = null
    }

    fun onConsentResult(data: Intent?) {
        val token = authManager.extractAccessTokenFromConsent(data)
        if (token != null) {
            viewModelScope.launch {
                tokenStore.save(token)
                _uiState.update { it.copy(hasAccessToken = true, phase = UploadPhase.Idle) }
            }
        } else {
            _uiState.update { it.copy(phase = UploadPhase.Error("사용자 동의 실패")) }
        }
    }

    fun startUpload() {
        val state = _uiState.value
        val uri = state.videoUri ?: return
        viewModelScope.launch {
            val token = tokenStore.accessToken.firstOrNull()
            if (token.isNullOrBlank()) {
                _uiState.update { it.copy(phase = UploadPhase.Error("먼저 로그인이 필요합니다")) }
                return@launch
            }
            _uiState.update { it.copy(phase = UploadPhase.Uploading(0f)) }

            val metadata = VideoMetadata(
                title = state.title,
                description = state.description,
                tags = state.tagsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                privacy = state.privacy,
            )

            uploader.upload(
                videoUri = uri,
                metadata = metadata,
                accessToken = token,
                onProgress = { written, total ->
                    if (total > 0) {
                        val ratio = (written.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
                        _uiState.update { it.copy(phase = UploadPhase.Uploading(ratio)) }
                    }
                },
            ).fold(
                onSuccess = { videoId ->
                    // 썸네일 자동 첨부
                    val thumbPath = state.thumbnailPath
                    if (state.attachThumbnail && !thumbPath.isNullOrBlank()) {
                        val f = File(thumbPath)
                        if (f.exists()) {
                            uploader.setThumbnail(videoId, f, token)
                        }
                    }
                    projectId?.let { pid ->
                        projectRepository.addAsset(
                            projectId = pid,
                            type = AssetType.UPLOADED_VIDEO_ID,
                            value = videoId,
                            label = "youtube://$videoId",
                        )
                    }
                    _uiState.update { it.copy(phase = UploadPhase.Success(videoId)) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(phase = UploadPhase.Error(e.message ?: "업로드 실패"))
                    }
                },
            )
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(phase = UploadPhase.Idle) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                val container = app.container
                UploadViewModel(
                    application = app,
                    authManager = container.googleAuthManager,
                    tokenStore = container.authTokenStore,
                    uploader = container.youTubeUploader,
                    projectRepository = container.projectRepository,
                )
            }
        }
    }
}
