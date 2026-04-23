package com.mingeek.studiopop.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.library.LibraryAssetEntity
import com.mingeek.studiopop.data.library.LibraryAssetKind
import com.mingeek.studiopop.data.library.LibraryAssetRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class AssetLibraryUiState(
    val stickers: List<LibraryAssetEntity> = emptyList(),
    val sfx: List<LibraryAssetEntity> = emptyList(),
    val errorMessage: String? = null,
    val savingLabel: String? = null,
)

/**
 * 짤/효과음 라이브러리 관리 ViewModel.
 * 상태는 Room Flow 를 구독해 자동 갱신.
 */
class AssetLibraryViewModel(
    application: Application,
    private val repository: LibraryAssetRepository,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(AssetLibraryUiState())
    val uiState: StateFlow<AssetLibraryUiState> = _ui.asStateFlow()

    init {
        combine(
            repository.observe(LibraryAssetKind.STICKER),
            repository.observe(LibraryAssetKind.SFX),
        ) { stickers, sfx ->
            stickers to sfx
        }
            .onEach { (stickers, sfx) ->
                _ui.value = _ui.value.copy(stickers = stickers, sfx = sfx)
            }
            .launchIn(viewModelScope)
    }

    fun register(kind: LibraryAssetKind, label: String, uri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(savingLabel = label, errorMessage = null)
            val result = repository.register(kind, label, uri)
            _ui.value = _ui.value.copy(
                savingLabel = null,
                errorMessage = result.exceptionOrNull()?.message,
            )
        }
    }

    fun rename(asset: LibraryAssetEntity, newLabel: String) {
        viewModelScope.launch { repository.rename(asset, newLabel) }
    }

    fun delete(asset: LibraryAssetEntity) {
        viewModelScope.launch { repository.delete(asset) }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(errorMessage = null)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                AssetLibraryViewModel(app, app.container.libraryAssetRepository)
            }
        }
    }
}
