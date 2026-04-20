package com.mingeek.studiopop.ui.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mingeek.studiopop.data.project.ProjectEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onNavigateBack: () -> Unit,
    onOpenProject: (Long) -> Unit,
    viewModel: ProjectListViewModel = viewModel(factory = ProjectListViewModel.Factory),
) {
    val projects by viewModel.projects.collectAsState()
    val lastCreated by viewModel.lastCreatedId.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(lastCreated) {
        lastCreated?.let {
            dialogOpen = false
            viewModel.resetDraft()
            onOpenProject(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("프로젝트") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialogOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "새 프로젝트")
            }
        },
    ) { innerPadding ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "아직 프로젝트가 없습니다.\n+ 버튼으로 새 프로젝트를 만들어 보세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projects, key = { it.id }) { p ->
                    ProjectRow(
                        project = p,
                        onClick = { onOpenProject(p.id) },
                        onDelete = { viewModel.deleteProject(p) },
                    )
                }
            }
        }
    }

    if (dialogOpen) {
        NewProjectDialog(
            viewModel = viewModel,
            onDismiss = { dialogOpen = false; viewModel.resetDraft() },
        )
    }
}

@Composable
private fun ProjectRow(project: ProjectEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(project.title, fontWeight = FontWeight.Bold)
                Text(
                    formatDate(project.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "삭제")
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
    viewModel: ProjectListViewModel,
    onDismiss: () -> Unit,
) {
    val draft by viewModel.draft.collectAsState()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.onVideoPicked(uri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 프로젝트") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text("프로젝트 제목") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (draft.videoUri != null) "영상 변경" else "원본 영상 선택")
                }
                if (draft.videoUri != null) {
                    Text(
                        draft.videoUri.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = viewModel::createProject,
                enabled = draft.videoUri != null,
            ) { Text("만들기") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
