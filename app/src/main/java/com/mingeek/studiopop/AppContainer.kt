package com.mingeek.studiopop

import android.content.Context
import com.mingeek.studiopop.data.auth.AuthTokenStore
import com.mingeek.studiopop.data.auth.GoogleAuthManager
import androidx.media3.common.util.UnstableApi
import com.mingeek.studiopop.data.caption.AudioChunker
import com.mingeek.studiopop.data.caption.AudioExtractor
import com.mingeek.studiopop.data.caption.ChunkedTranscriber
import com.mingeek.studiopop.data.caption.WhisperClient
import com.mingeek.studiopop.data.editor.VideoEditor
import com.mingeek.studiopop.data.project.AppDatabase
import com.mingeek.studiopop.data.project.ProjectRepository
import com.mingeek.studiopop.data.thumbnail.ClaudeCopywriter
import com.mingeek.studiopop.data.thumbnail.FrameExtractor
import com.mingeek.studiopop.data.thumbnail.ThumbnailComposer
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
            apiKey = BuildConfig.OPENAI_API_KEY,
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

    @get:UnstableApi
    val videoEditor: VideoEditor by lazy {
        VideoEditor(
            context = appContext,
            outputDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        )
    }

    val frameExtractor: FrameExtractor by lazy { FrameExtractor(appContext) }

    val thumbnailComposer: ThumbnailComposer by lazy { ThumbnailComposer() }

    val claudeCopywriter: ClaudeCopywriter by lazy {
        ClaudeCopywriter(
            client = okHttpClient,
            moshi = moshi,
            apiKey = BuildConfig.ANTHROPIC_API_KEY,
        )
    }

    private val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(database.projectDao(), database.assetDao())
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
