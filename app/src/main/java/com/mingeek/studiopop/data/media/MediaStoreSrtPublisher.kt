package com.mingeek.studiopop.data.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 앱이 만든 SRT 파일을 `Download/StudioPop/` 공용 경로에 MediaStore.Downloads 로 복사 등록.
 *
 * 왜 필요한가:
 * - 앱은 SRT 를 `getExternalFilesDir` (앱 private) 에 쓰고 DB asset 에 경로만 기록.
 * - 그래서 `ACTION_GET_CONTENT` / DocumentPicker 에서 **이 파일이 안 보임**.
 * - MediaStore.Downloads 에 등록하면 Files 앱 > Download > StudioPop 에서 찾을 수 있음.
 *
 * 제약:
 * - API 29 (Android 10) 미만: MediaStore.Downloads 가 없음 → no-op. Pre-Q 는 DocumentPicker 에서
 *   앱 private 폴더 직접 탐색이 유일한 방법 — 대부분 유저는 최신 Android 라 무시.
 */
class MediaStoreSrtPublisher(private val context: Context) {

    /**
     * [file] 을 MediaStore.Downloads 에 복사. 성공 시 Uri 반환, 실패/하위 API 면 null.
     */
    suspend fun publish(file: File, displayName: String): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        if (!file.exists() || file.length() == 0L) return@withContext null

        val resolver: ContentResolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sanitize(displayName))
            // SRT 공식 MIME 은 application/x-subrip 이지만 많은 파일 피커가 text/plain 으로만
            // 필터링해서 .srt 를 놓침. text/plain 을 선언하되 확장자 .srt 는 DISPLAY_NAME 이 보장.
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$RELATIVE_SUBDIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(collection, values) ?: return@withContext null

        val success = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: error("openOutputStream 실패")
        }.isSuccess

        if (!success) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }

        uri
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .ifBlank { "StudioPop_captions" }
        return if (cleaned.endsWith(".srt", ignoreCase = true)) cleaned else "$cleaned.srt"
    }

    companion object {
        /** Download 밑의 앱 이름 서브디렉터리. */
        const val RELATIVE_SUBDIR = "StudioPop"
    }
}
