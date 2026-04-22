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
 * 앱 private 경로(`getExternalFilesDir`) 에 저장된 영상 파일을 MediaStore.Video 로 **복사 등록**해
 * 갤러리/PhotoPicker 에서 보이게 함.
 *
 * - Android 10 (API 29) 이상: [MediaStore.Video.Media.RELATIVE_PATH] + [MediaStore.Video.Media.IS_PENDING]
 *   플로우로 추가 권한 없이 동작.
 * - Android 9 이하: `WRITE_EXTERNAL_STORAGE` 런타임 권한이 필요해 UX 트레이드오프가 커서 스킵.
 *   사용자는 "최신 결과 불러오기" 퀵 버튼으로 재편집은 가능 (파일 자체는 private 에 남아있음).
 *
 * 실패해도 원본 내보내기 플로우는 깨지지 않도록 항상 `runCatching` 으로 감싸 호출할 것.
 */
class MediaStoreVideoPublisher(private val context: Context) {

    /**
     * [file] 을 MediaStore.Video 로 복사. 성공 시 MediaStore Uri 반환.
     * Android 9 이하에선 null 반환 (no-op).
     */
    suspend fun publish(file: File, displayName: String): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        if (!file.exists() || file.length() == 0L) return@withContext null

        val resolver: ContentResolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, sanitize(displayName))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$RELATIVE_SUBDIR")
            put(MediaStore.Video.Media.IS_PENDING, 1)
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
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }

        uri
    }

    /** MediaStore DISPLAY_NAME 에 들어갈 수 없는 문자를 제거. 확장자 없으면 .mp4 부여. */
    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "StudioPop_output" }
        return if (cleaned.endsWith(".mp4", ignoreCase = true)) cleaned else "$cleaned.mp4"
    }

    companion object {
        /** 갤러리에서 한 폴더로 묶이도록 앱 이름 서브디렉터리. */
        const val RELATIVE_SUBDIR = "StudioPop"
    }
}
