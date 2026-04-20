package com.mingeek.studiopop.ui.project

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.project.ProjectEntity
import com.mingeek.studiopop.data.project.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewProjectDraft(
    val title: String = "",
    val videoUri: Uri? = null,
)

class ProjectListViewModel(
    application: Application,
    private val repository: ProjectRepository,
) : AndroidViewModel(application) {

    val projects: StateFlow<List<ProjectEntity>> =
        repository.observeProjects()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow(NewProjectDraft())
    val draft: StateFlow<NewProjectDraft> = _draft.asStateFlow()

    private val _lastCreatedId = MutableStateFlow<Long?>(null)
    val lastCreatedId: StateFlow<Long?> = _lastCreatedId.asStateFlow()

    fun onTitleChange(v: String) = _draft.update { it.copy(title = v) }
    fun onVideoPicked(uri: Uri?) = _draft.update { it.copy(videoUri = uri) }
    fun resetDraft() {
        _draft.value = NewProjectDraft()
        _lastCreatedId.value = null
    }

    fun createProject() {
        val d = _draft.value
        val uri = d.videoUri ?: return
        val title = d.title.ifBlank { "제목 없음" }
        viewModelScope.launch {
            val id = repository.createProject(title, uri.toString())
            _lastCreatedId.value = id
            _draft.value = NewProjectDraft()
        }
    }

    fun deleteProject(p: ProjectEntity) {
        viewModelScope.launch { repository.deleteProject(p) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                ProjectListViewModel(app, app.container.projectRepository)
            }
        }
    }
}
