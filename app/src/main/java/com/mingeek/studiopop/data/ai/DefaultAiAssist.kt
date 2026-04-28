package com.mingeek.studiopop.data.ai

import android.net.Uri
import com.mingeek.studiopop.data.caption.Cue
import com.mingeek.studiopop.data.design.LutAsset
import com.mingeek.studiopop.data.design.StudioPopDefaultTheme
import com.mingeek.studiopop.data.design.ThemePack
import com.mingeek.studiopop.data.shorts.GeminiHighlightPicker
import com.mingeek.studiopop.data.thumbnail.FaceDetector
import com.mingeek.studiopop.data.thumbnail.FrameExtractor
import com.mingeek.studiopop.data.thumbnail.GeminiCopywriter
import com.mingeek.studiopop.data.thumbnail.VariantGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * [AiAssist] 의 기본 구현 (R5a). 기존 단일 목적 Gemini 컴포넌트 + ML Kit + 새 [ToneEstimator]
 * 를 조합한다. 실패 정책은 부분 흡수 — 한 분석 단계가 실패해도 결과 객체의 해당 필드만 비고
 * 나머지는 채워진 채로 반환된다.
 *
 * R5a 단계의 스코프:
 *  - analyzeVideo: SRT 큐(있으면) + 프레임 N장 → 톤·얼굴·하이라이트 합쳐서 반환
 *  - suggestEdits: 하이라이트·얼굴 트랙·LUT 휴리스틱 기반 편집 제안
 *  - generatePackage: Copywriter 로 제목, ThumbnailAdvisor 로 썸네일, HighlightPicker 로 숏츠
 *    하이라이트. 챕터·태그는 R5b 에서 별도 Gemini 호출.
 *  - suggestLut: ToneEstimate → BuiltinLuts 매핑 ([LutMatcher])
 */
class DefaultAiAssist(
    private val variantGenerator: VariantGenerator,
    private val copywriter: GeminiCopywriter,
    private val highlightPicker: GeminiHighlightPicker,
    private val chapterPicker: GeminiChapterPicker,
    private val tagPicker: GeminiTagPicker,
    private val frameExtractor: FrameExtractor,
    private val faceDetector: FaceDetector,
    /**
     * R6: Gemini 멀티모달 톤 분석. null 이면 기존 ToneEstimator 휴리스틱만으로 동작 (기존 동작
     * 유지). 일반적으론 AppContainer 가 wire 해 줌.
     */
    private val toneAnalyzer: GeminiToneAnalyzer? = null,
) : AiAssist {

    /**
     * 분석에 사용하는 키프레임 시점들. 영상 길이 대비 균등 간격으로 N개. 짧은 영상은 N 줄어듦.
     */
    private fun pickKeyframeTimes(durationMs: Long, count: Int = 8): LongArray {
        if (durationMs <= 0) return LongArray(0)
        val n = count.coerceAtMost((durationMs / 500L).toInt().coerceAtLeast(1))
        return LongArray(n) { i -> ((durationMs.toDouble() * (i + 1) / (n + 1))).toLong() }
    }

    override suspend fun analyzeVideo(uri: Uri): Result<VideoAnalysis> = runCatching {
        analyzeVideoInternal(uri, srtCues = emptyList(), topic = null)
    }

    /**
     * SRT 큐와 주제가 있으면 더 풍부한 분석. 호출 측이 가지고 있을 때 사용.
     */
    suspend fun analyzeVideoWithContext(
        uri: Uri,
        srtCues: List<Cue>,
        topic: String?,
    ): Result<VideoAnalysis> = runCatching {
        analyzeVideoInternal(uri, srtCues, topic)
    }

    private suspend fun analyzeVideoInternal(
        uri: Uri,
        srtCues: List<Cue>,
        topic: String?,
    ): VideoAnalysis = withContext(Dispatchers.IO) {
        val info = runCatching { frameExtractor.readInfo(uri) }.getOrNull()
        val durationMs = info?.durationMs ?: 0L
        val times = pickKeyframeTimes(durationMs)

        // 1) 키프레임 추출 (병렬은 MediaMetadataRetriever 가 thread-safe 하지 않을 수 있어 직렬).
        // mapNotNull 람다는 suspend 컨텍스트가 아니므로 for 루프로 명시.
        // try/finally 를 추출 단계까지 감싸 — extractFrame 은 suspend cancellation point 이므로
        // 일부 추출 후 코루틴이 취소되면 이미 잡힌 비트맵들도 회수돼야 한다.
        val frames = mutableListOf<Pair<Long, android.graphics.Bitmap>>()
        try {
        for (t in times) {
            val bmp = frameExtractor.extractFrame(uri, t) ?: continue
            frames += t to bmp
        }
        // 2) 얼굴 감지(직렬 — ML Kit detector 가 thread-safe 하지 않음) + 톤 추정 + (선택)
        //    Gemini 멀티모달 톤 분석. 후자는 네트워크 — 실패해도 분석 전체는 통과.
        return@withContext coroutineScope {
            val faceJob = async {
                val out = mutableListOf<FaceKeyframe>()
                for ((timeMs, bmp) in frames) {
                    val rects = faceDetector.detect(bmp)
                    val largest = rects.firstOrNull() ?: continue
                    out += FaceKeyframe(timeMs = timeMs, sourceRect = largest)
                }
                out
            }
            val aiToneJob = async {
                val analyzer = toneAnalyzer ?: return@async null
                if (frames.isEmpty()) null
                else analyzer.analyze(
                    keyframes = frames.map { it.second },
                    topic = topic,
                ).getOrNull()
            }
            val tone = ToneEstimator.estimate(frames.map { pair -> pair.second })
            val faces = faceJob.await()
            val aiTone = aiToneJob.await()

            // 3) 하이라이트 — 큐가 있고 주제가 주어지면 호출. 단일 추천이라 최대 1개 항목.
            val highlights: List<HighlightSpan> = if (srtCues.isNotEmpty() && !topic.isNullOrBlank() && durationMs > 0L) {
                highlightPicker.pickHighlight(
                    cues = srtCues,
                    topic = topic,
                    videoDurationMs = durationMs,
                ).fold(
                    onSuccess = { s ->
                        listOf(
                            HighlightSpan(
                                startMs = s.startMs,
                                endMs = s.endMs,
                                score = 1f,
                                reason = s.reason ?: s.hookText.orEmpty(),
                            )
                        )
                    },
                    onFailure = { emptyList() },
                )
            } else emptyList()

            // 4) 키워드: SRT 큐에서 빈도 상위 단어를 단순 추출 (3자 이상, 한글/영문)
            val keywords = extractKeywords(srtCues)

            VideoAnalysis(
                sourceUri = uri,
                durationMs = durationMs,
                scenes = emptyList(), // R5b 에서 별도 Gemini 호출
                highlights = highlights,
                keywords = keywords,
                tone = tone,
                aiTone = aiTone,
                faces = if (faces.isEmpty()) emptyList() else listOf(
                    FaceTrack(trackId = 0, keyframes = faces)
                ),
                srtCues = srtCues,
            )
        }
        } finally {
            for ((_, bmp) in frames) {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun extractKeywords(cues: List<Cue>): List<String> {
        if (cues.isEmpty()) return emptyList()
        val text = cues.joinToString(" ") { it.text }
        // 단순 토큰: 공백/구두점 분리, 3자 이상 단어 빈도 상위 10개
        val tokens = text.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length >= 3 }
        val freq = HashMap<String, Int>()
        for (t in tokens) freq[t] = (freq[t] ?: 0) + 1
        return freq.entries.sortedByDescending { it.value }.take(10).map { it.key }
    }

    override suspend fun suggestEdits(analysis: VideoAnalysis): Result<List<EditSuggestion>> = runCatching {
        val out = mutableListOf<EditSuggestion>()

        // 하이라이트 → ZOOM_PUNCH 효과
        for (h in analysis.highlights) {
            out += EditSuggestion.AddEffect(
                effectDefinitionId = com.mingeek.studiopop.data.effects.builtins.VideoFxPresets.ZOOM_PUNCH,
                sourceStartMs = h.startMs,
                sourceEndMs = h.endMs,
                rationale = "하이라이트 구간(${h.reason})에 줌 펀치로 임팩트 강조",
            )
        }

        // R6: Gemini 의 의미적 톤 추천이 있으면 우선, 없으면 LutMatcher 휴리스틱 fallback.
        //     LUT id 만 동일해도 rationale 의 출처를 사용자가 알 수 있게 분기 표시.
        val aiLutId = analysis.aiTone?.recommendedLutId
        val (lutId, lutRationale) = when {
            aiLutId != null -> aiLutId to (
                analysis.aiTone?.reasoning?.takeIf { it.isNotBlank() }?.let { "AI 톤 분석: $it" }
                    ?: "AI 톤 분석으로 $aiLutId 추천"
                )
            else -> {
                val lut = LutMatcher.match(analysis.tone)
                if (lut != null) lut.id to "영상 톤 분석으로 ${lut.displayName} LUT 추천"
                else null to ""
            }
        }
        if (lutId != null) {
            out += EditSuggestion.AddEffect(
                effectDefinitionId = "lut.${lutId.removePrefix("lut.")}",
                sourceStartMs = 0L,
                sourceEndMs = analysis.durationMs,
                params = mapOf("lutId" to lutId),
                rationale = lutRationale,
            )
        }

        // 인물이 있고 SRT 큐가 있으면 큐 별로 자동 위치 회피 자막 제안
        if (analysis.faces.isNotEmpty() && analysis.srtCues.isNotEmpty()) {
            for (cue in analysis.srtCues.take(20)) {
                out += EditSuggestion.AddCaption(
                    sourceStartMs = cue.startMs,
                    sourceEndMs = cue.endMs,
                    text = cue.text,
                    rationale = "인물이 있어 자동 위치 회피 적용 권장",
                )
            }
        }

        // 인트로 — 영상 첫 2초에 후킹 타이틀 제안
        if (analysis.durationMs >= 5_000L) {
            out += EditSuggestion.AddEffect(
                effectDefinitionId = com.mingeek.studiopop.data.effects.builtins.IntroOutroPresets.HOOK_TITLE,
                sourceStartMs = 0L,
                sourceEndMs = 2_000L,
                rationale = "첫 2초에 후킹 타이틀로 시청자 멈추기",
            )
        }

        // 아웃트로 — 영상 마지막 3초에 구독 유도
        if (analysis.durationMs >= 10_000L) {
            out += EditSuggestion.AddEffect(
                effectDefinitionId = com.mingeek.studiopop.data.effects.builtins.IntroOutroPresets.SUBSCRIBE_PROMPT,
                sourceStartMs = (analysis.durationMs - 3_000L).coerceAtLeast(0L),
                sourceEndMs = analysis.durationMs,
                rationale = "마지막 3초 구독 카드로 채널 성장 유도",
            )
        }

        out
    }

    override suspend fun generatePackage(
        analysis: VideoAnalysis,
        topic: String?,
    ): Result<YoutubePackage> = runCatching {
        coroutineScope {
            val resolvedTopic = topic ?: analysis.keywords.firstOrNull() ?: "영상"

            val titlesJob = async {
                copywriter.suggestThumbnailCopies(
                    topic = resolvedTopic,
                    count = 5,
                ).getOrElse { emptyList() }
            }

            val chaptersJob = async {
                if (analysis.srtCues.isEmpty() || analysis.durationMs <= 0L) emptyList()
                else chapterPicker.pickChapters(
                    cues = analysis.srtCues,
                    videoDurationMs = analysis.durationMs,
                ).getOrElse { emptyList() }
            }

            val tagsJob = async {
                tagPicker.pickTags(
                    topic = resolvedTopic,
                    keywordsHint = analysis.keywords,
                ).getOrElse { analysis.keywords.take(10) }
            }

            val highlightJob = async {
                if (analysis.srtCues.isEmpty() || analysis.durationMs <= 0L) {
                    emptyList()
                } else {
                    highlightPicker.pickHighlight(
                        cues = analysis.srtCues,
                        topic = resolvedTopic,
                        videoDurationMs = analysis.durationMs,
                    ).fold(
                        onSuccess = { s ->
                            listOf(
                                HighlightSpan(
                                    startMs = s.startMs,
                                    endMs = s.endMs,
                                    score = 1f,
                                    reason = s.reason ?: s.hookText.orEmpty(),
                                )
                            )
                        },
                        onFailure = { emptyList() },
                    )
                }
            }

            // 썸네일 변형 — VariantGenerator(advisor+faceDetector) 가 ThumbnailVariant 직접 반환.
            // 첫 키프레임을 frame 으로 사용 — 더 정교한 키프레임 선택은 R5b 에서.
            val variantsJob = async {
                val firstKey = pickKeyframeTimes(analysis.durationMs, count = 3).firstOrNull()
                val frame = if (firstKey != null) {
                    frameExtractor.extractFrame(analysis.sourceUri, firstKey)
                } else null
                if (frame == null) emptyList()
                else variantGenerator.generate(
                    frame = frame,
                    topic = resolvedTopic,
                    baseCount = 4,
                ).getOrElse { emptyList() }
            }

            val titles = titlesJob.await().filter { it.isNotBlank() }
            val variants = variantsJob.await()
            val highlights = highlightJob.await()
            val chapters = chaptersJob.await()
            val tags = tagsJob.await()

            YoutubePackage(
                titles = titles.take(5),
                description = buildDescription(resolvedTopic, analysis, chapters),
                tags = tags.take(15),
                chapters = chapters,
                hashtags = tags.take(5).map { if (it.startsWith("#")) it else "#$it" },
                thumbnailVariants = variants,
                shortsHighlights = highlights,
            )
        }
    }

    private fun buildDescription(
        topic: String,
        analysis: VideoAnalysis,
        chapters: List<Chapter>,
    ): String = buildString {
        appendLine("📹 $topic")
        analysis.aiTone?.let { t ->
            val parts = mutableListOf<String>()
            if (t.mood.isNotBlank()) parts += t.mood
            if (t.descriptors.isNotEmpty()) parts += t.descriptors.joinToString(" · ")
            if (parts.isNotEmpty()) {
                appendLine()
                appendLine("💫 분위기: ${parts.joinToString(" / ")}")
            }
        }
        if (chapters.isNotEmpty()) {
            appendLine()
            appendLine("⏱️ 챕터")
            for (c in chapters) {
                appendLine("${formatMs(c.startMs)} ${c.title}")
            }
        }
        if (analysis.keywords.isNotEmpty()) {
            appendLine()
            appendLine("키워드: ${analysis.keywords.take(8).joinToString(" · ")}")
        }
        if (analysis.highlights.isNotEmpty()) {
            appendLine()
            appendLine("하이라이트:")
            for (h in analysis.highlights) {
                appendLine("- ${formatMs(h.startMs)}~${formatMs(h.endMs)}  ${h.reason}")
            }
        }
        appendLine()
        appendLine("🤖 이 설명은 AI 가 생성한 초안 — 업로드 전 직접 다듬어주세요.")
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    override suspend fun suggestThemeFromHistory(channelId: String): Result<ThemePack> =
        Result.success(StudioPopDefaultTheme)

    override suspend fun suggestLut(analysis: VideoAnalysis): Result<LutAsset?> =
        Result.success(LutMatcher.match(analysis.tone))
}
