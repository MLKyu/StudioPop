package com.mingeek.studiopop.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mingeek.studiopop.data.library.LibraryAssetEntity
import com.mingeek.studiopop.data.library.LibraryAssetKind
import java.io.File

/**
 * 설정 하위 "짤 / 효과음 라이브러리" 화면.
 * - 각 타입별로 등록 버튼 → 파일 선택 → 이름 지정 다이얼로그 → 저장
 * - 등록된 에셋은 편집기에서 Picker 로 바로 선택 가능
 * - 삭제는 길게 누르기 없이 ✕ 버튼으로 즉시 확인 다이얼로그
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetLibraryScreen(
    onNavigateBack: () -> Unit,
    viewModel: AssetLibraryViewModel = viewModel(factory = AssetLibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    var pendingStickerUri by remember { mutableStateOf<Uri?>(null) }
    var pendingSfxUri by remember { mutableStateOf<Uri?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryAssetEntity?>(null) }

    val pickSticker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) pendingStickerUri = uri }

    val pickSfx = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) pendingSfxUri = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("짤 / 효과음 라이브러리") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "여기 등록한 에셋은 편집기의 '짤 추가' / '효과음 추가' 목록에서 바로 선택할 수 있어요. " +
                        "파일은 앱 내부로 복사되므로 원본을 지워도 유지됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LibrarySection(
                title = "🖼 짤 라이브러리",
                subtitle = "PNG / JPG / WebP — 영상 위에 띄울 반응/밈/로고 이미지",
                items = state.stickers,
                kind = LibraryAssetKind.STICKER,
                onAdd = { pickSticker.launch("image/*") },
                onDelete = { deleteTarget = it },
            )

            LibrarySection(
                title = "🔔 효과음 라이브러리",
                subtitle = "MP3 / WAV / M4A — 띠용, 두둥 같은 짧은 SFX",
                items = state.sfx,
                kind = LibraryAssetKind.SFX,
                onAdd = { pickSfx.launch("audio/*") },
                onDelete = { deleteTarget = it },
            )
        }
    }

    pendingStickerUri?.let { uri ->
        NameDialog(
            title = "짤 이름",
            suggestion = "새 짤",
            onCancel = { pendingStickerUri = null },
            onConfirm = { label ->
                viewModel.register(LibraryAssetKind.STICKER, label, uri)
                pendingStickerUri = null
            },
        )
    }

    pendingSfxUri?.let { uri ->
        NameDialog(
            title = "효과음 이름",
            suggestion = "새 효과음",
            onCancel = { pendingSfxUri = null },
            onConfirm = { label ->
                viewModel.register(LibraryAssetKind.SFX, label, uri)
                pendingSfxUri = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("삭제하시겠어요?") },
            text = { Text("'${target.label}' 을(를) 라이브러리에서 제거합니다. 이미 적용된 영상은 영향받지 않아요.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    deleteTarget = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun LibrarySection(
    title: String,
    subtitle: String,
    items: List<LibraryAssetEntity>,
    kind: LibraryAssetKind,
    onAdd: () -> Unit,
    onDelete: (LibraryAssetEntity) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "아직 등록된 항목이 없어요",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items.forEach { item ->
                    LibraryRow(item = item, kind = kind, onDelete = { onDelete(item) })
                }
            }

            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (kind == LibraryAssetKind.STICKER) "+ 짤 추가" else "+ 효과음 추가")
            }
        }
    }
}

@Composable
private fun LibraryRow(
    item: LibraryAssetEntity,
    kind: LibraryAssetKind,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when (kind) {
                LibraryAssetKind.STICKER -> AsyncImage(
                    model = File(item.filePath),
                    contentDescription = item.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                LibraryAssetKind.SFX -> Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val meta = if (kind == LibraryAssetKind.SFX && item.durationMs > 0) {
                "${item.durationMs / 1000.0}초"
            } else {
                File(item.filePath).name
            }
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "삭제")
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    suggestion: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(suggestion) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("이름을 입력하세요") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifBlank { suggestion }) }) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("취소") }
        },
    )
}
