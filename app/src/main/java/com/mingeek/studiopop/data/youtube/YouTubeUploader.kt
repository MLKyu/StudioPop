package com.mingeek.studiopop.data.youtube

import android.content.ContentResolver
import android.net.Uri
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class YouTubeUploader(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val contentResolver: ContentResolver,
) {

    /**
     * 단일 PUT 방식으로 YouTube 에 영상을 업로드한다.
     * 1) POST /upload/youtube/v3/videos?uploadType=resumable — 세션 URL 획득
     * 2) PUT <session url> — 바이너리 전송
     *
     * 반환: 생성된 videoId
     */
    suspend fun upload(
        videoUri: Uri,
        metadata: VideoMetadata,
        accessToken: String,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fileSize = contentResolver.openAssetFileDescriptor(videoUri, "r")
                ?.use { it.length }
                ?: error("Cannot determine file size for $videoUri")
            val mimeType = contentResolver.getType(videoUri) ?: "video/*"

            val sessionUrl = startResumableSession(metadata, fileSize, mimeType, accessToken)
            uploadBinary(sessionUrl, videoUri, fileSize, mimeType, onProgress)
        }
    }

    private fun startResumableSession(
        metadata: VideoMetadata,
        fileSize: Long,
        mimeType: String,
        accessToken: String,
    ): String {
        val json = moshi.adapter(VideoInsertBody::class.java).toJson(
            VideoInsertBody(
                snippet = Snippet(
                    title = metadata.title,
                    description = metadata.description,
                    tags = metadata.tags,
                    categoryId = metadata.categoryId,
                ),
                status = Status(privacyStatus = metadata.privacy.apiValue),
            )
        )

        val request = Request.Builder()
            .url("$UPLOAD_ENDPOINT?uploadType=resumable&part=snippet,status")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("X-Upload-Content-Length", fileSize.toString())
            .addHeader("X-Upload-Content-Type", mimeType)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Failed to start resumable session: ${response.code} ${response.body?.string()}")
            }
            return response.header("Location")
                ?: error("No Location header in resumable session response")
        }
    }

    private fun uploadBinary(
        sessionUrl: String,
        uri: Uri,
        fileSize: Long,
        mimeType: String,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val body = ProgressRequestBody(
            contentType = mimeType.toMediaType(),
            contentLength = fileSize,
            openStream = {
                contentResolver.openInputStream(uri)
                    ?: error("Cannot open input stream for $uri")
            },
            onProgress = onProgress,
        )

        val request = Request.Builder()
            .url(sessionUrl)
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Upload failed: ${response.code} $responseBody")
            }
            val result = moshi.adapter(VideoInsertResponse::class.java).fromJson(responseBody)
                ?: error("Empty upload response")
            return result.id
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class VideoInsertBody(val snippet: Snippet, val status: Status)

    @JsonClass(generateAdapter = true)
    internal data class Snippet(
        val title: String,
        val description: String,
        val tags: List<String>,
        val categoryId: String,
    )

    @JsonClass(generateAdapter = true)
    internal data class Status(val privacyStatus: String)

    @JsonClass(generateAdapter = true)
    internal data class VideoInsertResponse(val id: String)

    /**
     * 업로드된 영상의 썸네일을 PNG/JPEG 로 교체.
     * thumbnails.set 엔드포인트 — 리쥼어블 업로드 필요 없음, 단일 POST.
     */
    suspend fun setThumbnail(
        videoId: String,
        imageFile: File,
        accessToken: String,
    ): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val mimeType = when {
                imageFile.name.endsWith(".jpg", ignoreCase = true) ||
                        imageFile.name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                else -> "image/png"
            }
            val body = imageFile.asRequestBody(mimeType.toMediaType())
            val request = Request.Builder()
                .url("$THUMBNAIL_ENDPOINT?videoId=$videoId&uploadType=media")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Thumbnail set failed: ${resp.code} ${resp.body?.string()}")
                }
            }
        }
    }

    companion object {
        private const val UPLOAD_ENDPOINT =
            "https://www.googleapis.com/upload/youtube/v3/videos"
        private const val THUMBNAIL_ENDPOINT =
            "https://www.googleapis.com/upload/youtube/v3/thumbnails/set"
    }
}
