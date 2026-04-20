package com.mingeek.studiopop.ui.project

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import com.mingeek.studiopop.StudioPopApp
import com.mingeek.studiopop.data.project.AssetEntity
import com.mingeek.studiopop.data.project.ProjectEntity
import com.mingeek.studiopop.data.project.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
) : AndroidViewModel(application) {

    val projectId: Long = savedStateHandle.get<Long>("projectId") ?: 0L

    private val _project = MutableStateFlow<ProjectEntity?>(null)
    val project: StateFlow<ProjectEntity?> = _project.asStateFlow()

    val assets: StateFlow<List<AssetEntity>> = repository.observeAssets(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _project.value = repository.getProject(projectId)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StudioPopApp
                val ssh = createSavedStateHandle()
                ProjectDetailViewModel(app, ssh, app.container.projectRepository)
            }
        }
    }
}
