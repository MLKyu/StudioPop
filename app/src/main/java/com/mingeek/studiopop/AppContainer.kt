package com.mingeek.studiopop

import android.content.Context
import com.mingeek.studiopop.data.auth.AuthTokenStore
import com.mingeek.studiopop.data.auth.GoogleAuthManager
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.data.caption.AudioChunker
import com.mingeek.studiopop.data.caption.AudioExtractor
import com.mingeek.studiopop.data.caption.CaptionWordStore
import com.mingeek.studiopop.data.caption.ChunkedTranscriber
import com.mingeek.studiopop.data.caption.PcmDecoder
import com.mingeek.studiopop.data.caption.SpeechToText
import com.mingeek.studiopop.data.caption.SttEngine
import com.mingeek.studiopop.data.caption.SttRegistry
import com.mingeek.studiopop.data.caption.VoskModelManager
import com.mingeek.studiopop.data.caption.VoskTranscriber
import com.mingeek.studiopop.data.caption.WhisperApiEngine
import com.mingeek.studiopop.data.caption.WhisperClient
import com.mingeek.studiopop.data.caption.WhisperCppEngine
import com.mingeek.studiopop.data.caption.WhisperCppModelManager
import com.mingeek.studiopop.data.editor.FaceTracker
import com.mingeek.studiopop.data.editor.FrameStripGenerator
import com.mingeek.studiopop.data.editor.VideoEditor
import com.mingeek.studiopop.data.library.FixedTemplatePresetRepository
import com.mingeek.studiopop.data.library.LibraryAssetRepository
import com.mingeek.studiopop.data.library.TimelineSnapshotRepository
import com.mingeek.studiopop.data.vocal.StereoPcmDecoder
import com.mingeek.studiopop.data.vocal.UvrModelManager
import com.mingeek.studiopop.data.vocal.VocalSeparator
import com.mingeek.studiopop.data.media.AssetBackfillPublisher
import com.mingeek.studiopop.data.media.MediaStoreSrtPublisher
import com.mingeek.studiopop.data.media.MediaStoreVideoPublisher
import com.mingeek.studiopop.data.project.AppDatabase
import com.mingeek.studiopop.data.project.ProjectRepository
import com.mingeek.studiopop.data.settings.ApiKeyStore
import com.mingeek.studiopop.data.shorts.GeminiHighlightPicker
import com.mingeek.studiopop.data.thumbnail.FaceDetector
import com.mingeek.studiopop.data.thumbnail.FrameExtractor
import com.mingeek.studiopop.data.thumbnail.GeminiCopywriter
import com.mingeek.studiopop.data.thumbnail.GeminiThumbnailAdvisor
import com.mingeek.studiopop.data.thumbnail.ThumbnailComposer
import com.mingeek.studiopop.data.thumbnail.VariantGenerator
import com.mingeek.studiopop.data.youtube.YouTubeUploader
import com.mingeek.studiopop.data.ai.AiAssist
import com.mingeek.studiopop.data.ai.DefaultAiAssist
import com.mingeek.studiopop.data.ai.GeminiChapterPicker
import com.mingeek.studiopop.data.ai.GeminiTagPicker
import com.mingeek.studiopop.data.ai.GeminiToneAnalyzer
import com.mingeek.studiopop.data.audio.AnalysisCache
import com.mingeek.studiopop.data.audio.AudioAnalysisService
import com.mingeek.studiopop.data.audio.BeatBus
import com.mingeek.studiopop.data.audio.BeatDetector
import com.mingeek.studiopop.data.audio.BeatDetectorImpl
import com.mingeek.studiopop.data.audio.LoudnessAnalyzer
import com.mingeek.studiopop.data.audio.LoudnessAnalyzerImpl
import com.mingeek.studiopop.data.audio.WaveformSampler
import com.mingeek.studiopop.data.audio.WaveformSamplerImpl
import com.mingeek.studiopop.data.design.DesignTokens
import com.mingeek.studiopop.data.design.TypefaceLoader
import com.mingeek.studiopop.data.design.registerBuiltinFontPacks
import com.mingeek.studiopop.data.design.registerBuiltinLuts
import com.mingeek.studiopop.data.design.registerBuiltinThemes
import com.mingeek.studiopop.data.effects.EffectRegistry
import com.mingeek.studiopop.data.effects.builtins.registerCaptionStylePresets
import com.mingeek.studiopop.data.effects.builtins.registerIntroOutroPresets
import com.mingeek.studiopop.data.effects.builtins.registerTransitionPresets
import com.mingeek.studiopop.data.effects.builtins.registerVideoFxPresets
import com.mingeek.studiopop.data.effects.registerBuiltins
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * 간단한 수동 DI 컨테이너. 의존성 관리가 커지면 Hilt 로 이관.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                }
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.MINUTES)
            .build()
    }

    val authTokenStore: AuthTokenStore by lazy { AuthTokenStore(appContext) }

    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(appContext) }

    val googleAuthManager: GoogleAuthManager by lazy { GoogleAuthManager(appContext) }

    val youTubeUploader: YouTubeUploader by lazy {
        YouTubeUploader(
            client = okHttpClient,
            moshi = moshi,
            contentResolver = appContext.contentResolver,
        )
    }

    val audioExtractor: AudioExtractor by lazy {
        AudioExtractor(
            contentResolver = appContext.contentResolver,
            cacheDir = appContext.cacheDir,
        )
    }

    val whisperClient: WhisperClient by lazy {
        WhisperClient(
            client = okHttpClient,
            apiKeyProvider = { apiKeyStore.getOpenAi() },
        )
    }

    val audioChunker: AudioChunker by lazy {
        AudioChunker(
            contentResolver = appContext.contentResolver,
            cacheDir = appContext.cacheDir,
        )
    }

    val chunkedTranscriber: ChunkedTranscriber by lazy {
        ChunkedTranscriber(audioChunker, whisperClient)
    }

    // --- STT 엔진 어셈블리 (Phase 8) ---
    val pcmDecoder: PcmDecoder by lazy { PcmDecoder(appContext) }

    val voskModelManager: VoskModelManager by lazy {
        VoskModelManager(appContext, okHttpClient)
    }

    private val whisperApiEngine: WhisperApiEngine by lazy {
        WhisperApiEngine(
            transcriber = chunkedTranscriber,
            apiKeyProvider = { apiKeyStore.getOpenAi() },
        )
    }

    private val voskEngine: VoskTranscriber by lazy {
        VoskTranscriber(pcmDecoder, voskModelManager, moshi)
    }

    val whisperCppModelManager: WhisperCppModelManager by lazy {
        WhisperCppModelManager(appContext, okHttpClient)
    }

    /**
     * 사용자가 선택한 whisper.cpp variant. CaptionViewModel 이 setter 로 갱신.
     * lazy 컨테이너 환경에서 단일 진실 소스를 두기 위해 컨테이너에 보관.
     */
    @Volatile
    var whisperCppVariant: WhisperCppModelManager.Variant =
        WhisperCppModelManager.Variant.BASE_Q5

    private val whisperCppEngine: WhisperCppEngine by lazy {
        WhisperCppEngine(
            pcmDecoder = pcmDecoder,
            modelManager = whisperCppModelManager,
            moshi = moshi,
            variantProvider = { whisperCppVariant },
        )
    }

    val sttRegistry: SttRegistry by lazy {
        SttRegistry(
            mapOf<SttEngine, SpeechToText>(
                SttEngine.WHISPER_API to whisperApiEngine,
                SttEngine.VOSK_LOCAL to voskEngine,
                SttEngine.WHISPER_CPP to whisperCppEngine,
            )
        )
    }

    val mediaStoreVideoPublisher: MediaStoreVideoPublisher by lazy {
        MediaStoreVideoPublisher(appContext)
    }

    val mediaStoreSrtPublisher: MediaStoreSrtPublisher by lazy {
        MediaStoreSrtPublisher(appContext)
    }

    val assetBackfillPublisher: AssetBackfillPublisher by lazy {
        AssetBackfillPublisher(
            context = appContext,
            projectRepository = projectRepository,
            videoPublisher = mediaStoreVideoPublisher,
            srtPublisher = mediaStoreSrtPublisher,
        )
    }

    @get:UnstableApi
    val videoEditor: VideoEditor by lazy {
        VideoEditor(
            context = appContext,
            outputDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            mediaStorePublisher = mediaStoreVideoPublisher,
        )
    }

    val frameStripGenerator: FrameStripGenerator by lazy { FrameStripGenerator(appContext) }

    val frameExtractor: FrameExtractor by lazy { FrameExtractor(appContext) }

    val thumbnailComposer: ThumbnailComposer by lazy { ThumbnailComposer() }

    val geminiCopywriter: GeminiCopywriter by lazy {
        GeminiCopywriter(
            client = okHttpClient,
            moshi = moshi,
            apiKeyProvider = { apiKeyStore.getGemini() },
        )
    }

    val geminiThumbnailAdvisor: GeminiThumbnailAdvisor by lazy {
        GeminiThumbnailAdvisor(
            client = okHttpClient,
            moshi = moshi,
            apiKeyProvider = { apiKeyStore.getGemini() },
        )
    }

    val geminiHighlightPicker: GeminiHighlightPicker by lazy {
        GeminiHighlightPicker(
            client = okHttpClient,
            moshi = moshi,
            apiKeyProvider = { apiKeyStore.getGemini() },
        )
    }

    val geminiChapterPicker: GeminiChapterPicker by lazy {
        GeminiChapterPicker(
            client = okHttpClient,
            moshi = moshi,
            apiKeyProvider = { apiKeyStore.getGemini() },
        )
    }

    val geminiTagPicker: GeminiTagPicker by lazy {
        GeminiTagPicker(
            client = okHttpClient,
            moshi = moshi,
            apiKeyProvider = { apiKeyStore.getGemini() },
        )
    }

    val geminiToneAnalyzer: GeminiToneAnalyzer by lazy {
        GeminiToneAnalyzer(
            client = okHttpClient,
            moshi = moshi,
            apiKeyProvider = { apiKeyStore.getGemini() },
        )
    }

    val faceDetector: FaceDetector by lazy { FaceDetector() }

    val variantGenerator: VariantGenerator by lazy {
        VariantGenerator(
            advisor = geminiThumbnailAdvisor,
            faceDetector = faceDetector,
        )
    }

    private val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(database.projectDao(), database.assetDao())
    }

    val libraryAssetRepository: LibraryAssetRepository by lazy {
        LibraryAssetRepository(appContext, database.libraryAssetDao())
    }

    val timelineSnapshotRepository: TimelineSnapshotRepository by lazy {
        TimelineSnapshotRepository(database.timelineSnapshotDao())
    }

    val fixedTemplatePresetRepository: FixedTemplatePresetRepository by lazy {
        FixedTemplatePresetRepository(database.fixedTemplatePresetDao())
    }

    val faceTracker: FaceTracker by lazy { FaceTracker(appContext, faceDetector) }

    // --- Vocal separation (UVR MDX-Net) ---
    val uvrModelManager: UvrModelManager by lazy { UvrModelManager(appContext, okHttpClient) }
    val stereoPcmDecoder: StereoPcmDecoder by lazy { StereoPcmDecoder(appContext) }
    val vocalSeparator: VocalSeparator by lazy {
        VocalSeparator(appContext, uvrModelManager, stereoPcmDecoder)
    }

    // --- 확장 골격 (R1) -------------------------------------------------------
    // 새 효과·디자인·AI·오디오 분석·렌더 시스템의 단일 진입점들. 골격 단계라 효과 0개·자산 기본만
    // 등록된 상태. R2 부터 effect/asset 등록이 일괄 추가됨.

    /**
     * R5c3a 후속: STT word-level 결과를 SRT 파일에 저장될 때 손실되지 않도록 세션 메모리에 보관.
     * CaptionViewModel 이 채우고 EditorViewModel 이 자막 시간 범위로 매칭해 카라오케에 사용.
     */
    val captionWordStore: CaptionWordStore by lazy { CaptionWordStore() }

    val effectRegistry: EffectRegistry by lazy {
        EffectRegistry().apply {
            registerBuiltins()
            // R2 builtin packs
            registerCaptionStylePresets()
            registerTransitionPresets()
            // R3 builtin packs
            registerVideoFxPresets()
            // R5b builtin packs
            registerIntroOutroPresets()
        }
    }

    val designTokens: DesignTokens by lazy {
        DesignTokens().apply {
            // R2: placeholder 폰트 팩 8종 등록 (실제 ttf 는 자산 큐레이션 시)
            registerBuiltinFontPacks()
            // R3: LUT 5종 placeholder + 테마 5팩
            registerBuiltinLuts()
            registerBuiltinThemes()
        }
    }

    /** R6: fontPackId → Typeface 매핑. ttf 자산이 있으면 그걸, 없으면 시스템 변형 fallback. */
    val typefaceLoader: TypefaceLoader by lazy { TypefaceLoader(appContext) }

    val analysisCache: AnalysisCache by lazy { AnalysisCache() }

    val beatBus: BeatBus by lazy { BeatBus() }

    /** R4: jTransforms FFT + RMS 기반 실구현. */
    val beatDetector: BeatDetector by lazy { BeatDetectorImpl(pcmDecoder) }
    val loudnessAnalyzer: LoudnessAnalyzer by lazy { LoudnessAnalyzerImpl(pcmDecoder) }
    val waveformSampler: WaveformSampler by lazy { WaveformSamplerImpl(pcmDecoder) }

    val audioAnalysisService: AudioAnalysisService by lazy {
        AudioAnalysisService(
            beatDetector = beatDetector,
            loudnessAnalyzer = loudnessAnalyzer,
            waveformSampler = waveformSampler,
            cache = analysisCache,
        )
    }

    val aiAssist: AiAssist by lazy {
        DefaultAiAssist(
            variantGenerator = variantGenerator,
            copywriter = geminiCopywriter,
            highlightPicker = geminiHighlightPicker,
            chapterPicker = geminiChapterPicker,
            tagPicker = geminiTagPicker,
            frameExtractor = frameExtractor,
            faceDetector = faceDetector,
            toneAnalyzer = geminiToneAnalyzer,
        )
    }
}

class StudioPopApp : android.app.Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
