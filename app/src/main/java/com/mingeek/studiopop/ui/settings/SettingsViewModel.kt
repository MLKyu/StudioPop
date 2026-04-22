package com.mingeek.studiopop.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.settings.ApiKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val geminiApiKey: String = "",
    val openAiApiKey: String = "",
    val loading: Boolean = true,
    val savedMessage: String? = null,
)

class SettingsViewModel(
    application: Application,
    private val store: ApiKeyStore,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val gemini = store.getGemini()
            val openAi = store.getOpenAi()
            _uiState.update {
                it.copy(geminiApiKey = gemini, openAiApiKey = openAi, loading = false)
            }
        }
    }

    fun onGeminiChange(value: String) =
        _uiState.update { it.copy(geminiApiKey = value) }

    fun onOpenAiChange(value: String) =
        _uiState.update { it.copy(openAiApiKey = value) }

    fun save() {
        val s = _uiState.value
        viewModelScope.launch {
            store.saveGemini(s.geminiApiKey)
            store.saveOpenAi(s.openAiApiKey)
            _uiState.update { it.copy(savedMessage = "저장되었습니다") }
        }
    }

    fun clearGemini() {
        viewModelScope.launch {
            store.clearGemini()
            _uiState.update { it.copy(geminiApiKey = "", savedMessage = "Gemini 키 삭제됨") }
        }
    }

    fun clearOpenAi() {
        viewModelScope.launch {
            store.clearOpenAi()
            _uiState.update { it.copy(openAiApiKey = "", savedMessage = "OpenAI 키 삭제됨") }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(savedMessage = null) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                SettingsViewModel(
                    application = app,
                    store = app.container.apiKeyStore,
                )
            }
        }
    }
}
