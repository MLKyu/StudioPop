package com.mingeek.studiopop.ui.exports

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mingeek.studiopop.StudioPopApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportKind(val label: String, val matcher: (String) -> Boolean) {
    EXPORT_VIDEO("편집·내보낸 영상", { it.startsWith("edit_") && it.endsWith(".mp4") }),
    SHORTS      ("숏츠",            { it.startsWith("shorts_") || (it.contains("shorts", true) && it.endsWith(".mp4")) }),
    THUMBNAIL   ("썸네일",          { it.startsWith("thumbnail_") && it.endsWith(".png") }),
    CAPTION_SRT ("자막 SRT",        { it.startsWith("captions_") && it.endsWith(".srt") }),
    OTHER       ("기타",            { _ -> true }),
}

data class ExportFile(
    val file: File,
    val kind: ExportKind,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

data class ExportsUiState(
    val files: List<ExportFile> = emptyList(),
    val groupedByKind: Map<ExportKind, List<ExportFile>> = emptyMap(),
    val totalCount: Int = 0,
    val totalSizeBytes: Long = 0L,
    val message: String? = null,
)

class ExportsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ExportsUiState())
    val uiState: StateFlow<ExportsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { scanFiles() }
            val grouped = list.groupBy { it.kind }
                // ExportKind 순서대로 정렬
                .toSortedMap(compareBy { ExportKind.entries.indexOf(it) })
            _uiState.update {
                it.copy(
                    files = list,
                    groupedByKind = grouped,
                    totalCount = list.size,
                    totalSizeBytes = list.sumOf { f -> f.sizeBytes },
                )
            }
        }
    }

    fun deleteFile(target: ExportFile) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { target.file.delete() }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) {
                    refresh()
                    _uiState.update { it.copy(message = "삭제됨: ${target.file.name}") }
                } else {
                    _uiState.update { it.copy(message = "삭제 실패: ${target.file.name}") }
                }
            }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    private fun scanFiles(): List<ExportFile> {
        val dir = getApplication<Application>().getExternalFilesDir(null)
            ?: return emptyList()
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return files.map { f ->
            val name = f.name
            val kind = ExportKind.entries.first { it.matcher(name) }
            ExportFile(
                file = f,
                kind = kind,
                sizeBytes = f.length(),
                lastModifiedMs = f.lastModified(),
            )
        }.sortedByDescending { it.lastModifiedMs }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                ExportsViewModel(app)
            }
        }
    }
}
