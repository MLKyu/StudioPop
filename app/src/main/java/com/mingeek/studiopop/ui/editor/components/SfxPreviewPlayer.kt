package com.mingeek.studiopop.ui.editor.components

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.mingeek.studiopop.data.editor.SfxClip
import com.mingeek.studiopop.data.editor.Timeline

/**
 * 프리뷰 재생 중 playhead 가 SFX 시작점(output ms)을 지나가는 순간 SoundPool 로 재생.
 * - Media3 export 와 별개. 프리뷰용 근사.
 * - 정확한 singleton 관리 대신 Composable 라이프사이클에 귀속 (편집기 떠나면 해제).
 */
@Composable
fun SfxPreviewPlayer(
    timeline: Timeline,
    playheadOutputMs: Long,
    isPlaying: Boolean,
) {
    // SoundPool.load 는 async — 로딩 완료 콜백으로 들어온 sampleId 만 재생 가능 집합에 추가.
    // 이 집합 밖의 id 로 play() 하면 조용히 실패(return 0) 하므로 첫 재생이 묵음이 되는 문제를 방지.
    // 콜백은 non-main 스레드에서 올 수 있어 read-modify-write 경쟁이 발생 — lock 으로 직렬화.
    val loadedIds = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val loadedIdsLock = remember { Any() }
    val pool = remember {
        SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) {
                        synchronized(loadedIdsLock) {
                            loadedIds.value = loadedIds.value + sampleId
                        }
                    }
                }
            }
    }

    // SfxClip.id → SoundPool soundId 매핑. lazy 로 필요할 때 load.
    val soundIds = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val lastPlayhead = remember { mutableStateOf(-1L) }
    val firedThisPass = remember { mutableStateOf<Set<String>>(emptySet()) }

    // SFX 리스트가 바뀌면 필요한 것만 추가 로드.
    LaunchedEffect(timeline.sfxClips) {
        val current = soundIds.value.toMutableMap()
        for (clip in timeline.sfxClips) {
            if (!current.containsKey(clip.id)) {
                val path = clip.audioUri.path ?: continue
                val id = runCatching { pool.load(path, 1) }.getOrNull() ?: continue
                current[clip.id] = id
            }
        }
        // 제거된 clip 의 soundId 도 unload + loadedIds 정리
        val valid = timeline.sfxClips.map { it.id }.toSet()
        val toRemove = (current.keys - valid).toList()
        val unloadedSampleIds = mutableSetOf<Int>()
        for (key in toRemove) {
            current[key]?.let { sid ->
                runCatching { pool.unload(sid) }
                unloadedSampleIds += sid
            }
            current.remove(key)
        }
        soundIds.value = current
        if (unloadedSampleIds.isNotEmpty()) {
            synchronized(loadedIdsLock) {
                loadedIds.value = loadedIds.value - unloadedSampleIds
            }
        }
    }

    // playhead 가 역행(= seek 또는 재감기)하면 firedThisPass 리셋.
    if (playheadOutputMs < lastPlayhead.value) {
        firedThisPass.value = emptySet()
    }
    lastPlayhead.value = playheadOutputMs

    // 재생 중일 때만 트리거 — seek 만으로는 SFX 재생되지 않음.
    LaunchedEffect(playheadOutputMs, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        val fired = firedThisPass.value.toMutableSet()
        for (clip in timeline.sfxClips) {
            if (clip.id in fired) continue
            val windows = rangeToOutputWindows(timeline, clip.sourceStartMs, clip.sourceEndMs)
            val triggerMs = windows.firstOrNull()?.first ?: continue
            if (playheadOutputMs in triggerMs..(triggerMs + 120L)) {
                val sid = soundIds.value[clip.id]
                // 로드 완료된 id 만 재생 — 아직 로딩 중이면 이번 패스는 스킵해 "무음 실패" 로 기록되지 않도록
                // fired 에도 넣지 않음(다음 패스에서 재시도).
                if (sid != null && sid in loadedIds.value) {
                    pool.play(sid, clip.volume, clip.volume, 1, 0, 1f)
                    fired += clip.id
                }
            }
        }
        firedThisPass.value = fired
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { pool.release() } }
    }
}

/**
 * Timeline.rangeToOutputWindows 의 UI 레이어 복제 — 순환 의존 피하기 위해 로컬.
 * PreviewPlayer 도 effective 세그먼트로 재생하므로 여기도 effective 기준.
 */
private fun rangeToOutputWindows(
    timeline: Timeline,
    sourceStartMs: Long,
    sourceEndMs: Long,
): List<LongRange> {
    val result = mutableListOf<LongRange>()
    var accumulated = 0L
    for (seg in timeline.effectiveSegments()) {
        val overlapStart = maxOf(sourceStartMs, seg.sourceStartMs)
        val overlapEnd = minOf(sourceEndMs, seg.sourceEndMs)
        if (overlapEnd > overlapStart) {
            val outStart = accumulated + (overlapStart - seg.sourceStartMs)
            val outEnd = accumulated + (overlapEnd - seg.sourceStartMs)
            result += outStart..outEnd
        }
        accumulated += seg.durationMs
    }
    return result
}

