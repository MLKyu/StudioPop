package com.mingeek.studiopop.ui.exports

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ExportsViewModel = viewModel(factory = ExportsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ExportFile?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("내보낸 파일") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (state.totalCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "아직 내보낸 파일이 없습니다.\n" +
                            "편집기·숏츠·썸네일·자막 화면에서 작업한 결과가 여기 모입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValuesAll(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "${state.totalCount}개 · ${formatBytes(state.totalSizeBytes)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.groupedByKind.forEach { (kind, files) ->
                    item {
                        Text(
                            "${kind.label}  ·  ${files.size}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(files, key = { it.file.absolutePath }) { exp ->
                        ExportRow(
                            exp = exp,
                            onOpen = { openFile(context, exp) },
                            onShare = { shareFile(context, exp) },
                            onDelete = { pendingDelete = exp },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("삭제 확인") },
            text = { Text("'${target.file.name}' 을 삭제할까요? 되돌릴 수 없습니다.") },
            confirmButton = {
                OutlinedButton(onClick = {
                    viewModel.deleteFile(target)
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun ExportRow(
    exp: ExportFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(exp.file.name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "${formatBytes(exp.sizeBytes)}  ·  ${formatDate(exp.lastModifiedMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text("열기", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("공유", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "삭제")
                }
            }
        }
    }
}

private fun openFile(context: Context, exp: ExportFile) {
    val uri = providerUri(context, exp)
    val mime = mimeFor(exp.file.name)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "열기"))
}

private fun shareFile(context: Context, exp: ExportFile) {
    val uri = providerUri(context, exp)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeFor(exp.file.name)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "공유"))
}

private fun providerUri(context: Context, exp: ExportFile): Uri =
    FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        exp.file,
    )

private fun mimeFor(name: String): String = when {
    name.endsWith(".mp4", true) -> "video/mp4"
    name.endsWith(".png", true) -> "image/png"
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
    name.endsWith(".srt", true) -> "text/plain"
    else -> "*/*"
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "${b}B"
    b < 1024 * 1024 -> "%.1fKB".format(b / 1024.0)
    b < 1024 * 1024 * 1024 -> "%.1fMB".format(b / 1024.0 / 1024.0)
    else -> "%.2fGB".format(b / 1024.0 / 1024.0 / 1024.0)
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun PaddingValuesAll(value: androidx.compose.ui.unit.Dp) =
    androidx.compose.foundation.layout.PaddingValues(value)
