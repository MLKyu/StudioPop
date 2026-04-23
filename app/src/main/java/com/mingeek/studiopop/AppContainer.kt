package com.mingeek.studiopop

import android.content.Context
import com.mingeek.studiopop.data.auth.AuthTokenStore
import com.mingeek.studiopop.data.auth.GoogleAuthManager
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.data.caption.AudioChunker
import com.mingeek.studiopop.data.caption.AudioExtractor
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
import com.mingeek.studiopop.data.library.LibraryAssetRepository
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

    val faceTracker: FaceTracker by lazy { FaceTracker(appContext, faceDetector) }
}

class StudioPopApp : android.app.Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
