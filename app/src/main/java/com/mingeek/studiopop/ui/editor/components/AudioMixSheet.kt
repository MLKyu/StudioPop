package com.mingeek.studiopop.ui.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mingeek.studiopop.data.editor.Timeline
import com.mingeek.studiopop.data.vocal.UvrModelManager

/**
 * 오디오 믹싱 시트 — 원본 영상 오디오 / BGM / 각 SFX 의 볼륨 배율 조절 + 센터 채널 추출 토글.
 * 0% = 음소거, 100% = 원본, 200% = 2배 증폭 (하드 클리핑 주의).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMixSheet(
    timeline: Timeline,
    vocalExtractProgress: Float?,
    vocalModelState: UvrModelManager.InstallState,
    onOriginalVolume: (Float) -> Unit,
    onBgmVolume: (Float) -> Unit,
    onSfxVolume: (id: String, v: Float) -> Unit,
    onExtractCenterChannel: (Boolean) -> Unit,
    onBgmReplaceOriginal: (Boolean) -> Unit,
    onExtractVocals: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "오디오 믹싱",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "각 소리의 음량을 개별로 조절. 0% 는 음소거. " +
                    "미리보기는 원본 볼륨(≤100%) · BGM · 추출된 보컬 재생까지 반영. " +
                    "100% 초과 증폭과 배경 음악 줄이기(센터 추출) 는 내보내기에서만 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 보컬 추출 카드 (UVR MDX-Net, 원본에서 사람 소리만 분리)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "🎤 사람 소리만 추출 (AI)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    val singleSegment = timeline.segments.size == 1
                    Text(
                        if (singleSegment)
                            "원본 영상에서 배경 음악을 AI 로 분리해 사람 목소리만 남김. " +
                                "첫 사용 시 모델 다운로드(~30MB) 필요."
                        else
                            "현재 여러 영상을 이어붙인 상태라 적용 불가. 단일 영상에서만 사용할 수 있어요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        vocalExtractProgress != null -> {
                            Text(
                                when {
                                    vocalModelState == UvrModelManager.InstallState.DOWNLOADING ->
                                        "모델 다운로드 중..."
                                    vocalExtractProgress < 0.08f -> "오디오 준비 중..."
                                    vocalExtractProgress < 0.92f ->
                                        "분리 중... ${(vocalExtractProgress * 100).toInt()}%"
                                    else -> "저장 중..."
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                            LinearProgressIndicator(
                                progress = { vocalExtractProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            OutlinedButton(
                                onClick = onExtractVocals,
                                enabled = singleSegment,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (vocalModelState == UvrModelManager.InstallState.READY)
                                        "사람 소리만 추출"
                                    else
                                        "모델 받고 사람 소리만 추출"
                                )
                            }
                        }
                    }
                }
            }

            // 원본 영상 오디오. replaceOriginal=true 면 원본이 대체되므로 슬라이더 의미 없음 — disable.
            val originalReplaced = timeline.audioTrack?.replaceOriginal == true
            VolumeCard(
                label = "원본 영상 소리",
                subLabel = if (originalReplaced) "BGM·추출된 소리로 대체됨 (비활성)"
                           else "영상에 녹음된 말소리·배경음",
                value = timeline.originalVolume,
                onChange = onOriginalVolume,
                enabled = !originalReplaced,
            )

            // 배경음악 줄이기 (센터 채널 추출)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "배경 음악 줄이기 (실험)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "스테레오 원본에서 중앙 채널만 남겨 배경 반주를 일부 제거.\n" +
                                "모노 영상·중앙 배치 악기엔 효과 없음. 결과는 케이스별.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = timeline.extractCenterChannel,
                        onCheckedChange = onExtractCenterChannel,
                    )
                }
            }

            // BGM / 추출된 보컬 WAV — 둘 다 audioTrack 슬롯에 저장되지만 의미가 달라 라벨 분기.
            // AudioTrack.isVocalExtraction 명시 플래그로 판단 (경로 휴리스틱은 BGM 파일명에 "vocals"
            // 가 포함되면 오판할 수 있어 위험).
            timeline.audioTrack?.let { track ->
                val isVocalExtract = track.isVocalExtraction
                VolumeCard(
                    label = if (isVocalExtract) "🎤 추출된 사람 소리" else "BGM",
                    subLabel = when {
                        isVocalExtract -> "이 슬라이더를 0 으로 내리면 영상이 무음이 됩니다"
                        else -> track.uri.lastPathSegment ?: "배경 음악"
                    },
                    value = track.volume,
                    onChange = onBgmVolume,
                    warning = isVocalExtract && track.volume < 0.01f,
                )
                if (!isVocalExtract) {
                    // "원본 대신 BGM 만 쓰기" 토글은 BGM 용도. 추출된 보컬 모드에서는 항상 replaceOriginal
                    // 이 참이므로 토글 숨김 (사용자 혼란 방지).
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "원본 대신 BGM 만 쓰기",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "체크 해제하면 원본 소리 + BGM 믹스",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = track.replaceOriginal,
                                onCheckedChange = onBgmReplaceOriginal,
                            )
                        }
                    }
                }
            }

            // SFX 목록
            if (timeline.sfxClips.isNotEmpty()) {
                Text(
                    "효과음 ${timeline.sfxClips.size}",
                    style = MaterialTheme.typography.labelLarge,
                )
                timeline.sfxClips.forEach { clip ->
                    VolumeCard(
                        label = "🔔 ${clip.label.ifBlank { "SFX" }}",
                        subLabel = "${clip.sourceStartMs / 1000.0}s 시점",
                        value = clip.volume,
                        onChange = { onSfxVolume(clip.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(
    label: String,
    subLabel: String,
    value: Float,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
    warning: Boolean = false,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        subLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (warning) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${(value * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (warning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = 0f..2f,
                steps = 19,
                enabled = enabled,
            )
            if (value > 1.01f && enabled) {
                // 100% 초과는 export 에서 soft-clip 되지만 여전히 음질 변형 가능. 안내.
                Text(
                    "⚠ 100% 초과는 음질 왜곡 가능 (soft-clip 처리)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
