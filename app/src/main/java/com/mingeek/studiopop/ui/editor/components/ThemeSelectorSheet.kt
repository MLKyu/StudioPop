package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.design.DesignTokens
import com.mingeek.studiopop.data.design.ThemePack

/**
 * 채널 톤 테마 선택 시트. 등록된 [ThemePack] 들을 카드로 보여주고 사용자가 한 번 누르면
 * 즉시 자막 톤·폰트가 변경된다 (CaptionEffectResolver 가 selectedThemeId 받아 처리).
 *
 * 각 카드에 팔레트의 primary/accent/neon 3색 점을 미리보기로 표시 — 사용자가 톤을 한눈에 비교.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelectorSheet(
    designTokens: DesignTokens,
    selectedThemeId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val themes = designTokens.allThemes()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("🎨 채널 테마", style = MaterialTheme.typography.titleLarge)
            Text(
                "선택하면 자막 색·폰트·전환이 한 번에 바뀝니다.",
                style = MaterialTheme.typography.bodySmall,
            )

            themes.forEach { theme ->
                ThemeCard(
                    theme = theme,
                    isSelected = theme.id == selectedThemeId,
                    onClick = {
                        onSelect(theme.id)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: ThemePack,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) Color(theme.palette.accent) else Color.Transparent
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 팔레트 색 점 3개 미리보기
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorDot(Color(theme.palette.primary))
                ColorDot(Color(theme.palette.accent))
                ColorDot(Color(theme.palette.neon))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    theme.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    theme.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (theme.captionEffectIds.isNotEmpty()) {
                    Text(
                        "추천 자막: ${theme.captionEffectIds.size}종",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (isSelected) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(theme.palette.accent),
                )
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}
