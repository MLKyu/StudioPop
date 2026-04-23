package com.mingeek.studiopop.data.library

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 라이브러리 에셋 관리.
 * 사용자가 고른 파일을 앱 내부(`filesDir/library/`)에 복사 저장해 권한 없이도 안정적으로 참조.
 */
class LibraryAssetRepository(
    private val context: Context,
    private val dao: LibraryAssetDao,
) {

    private val baseDir: File by lazy {
        File(context.filesDir, "library").apply { mkdirs() }
    }

    fun observe(kind: LibraryAssetKind): Flow<List<LibraryAssetEntity>> =
        dao.observeByKind(kind)

    suspend fun list(kind: LibraryAssetKind): List<LibraryAssetEntity> =
        dao.listByKind(kind)

    suspend fun get(id: Long): LibraryAssetEntity? = dao.getById(id)

    suspend fun getByPath(path: String): LibraryAssetEntity? =
        dao.listByKind(LibraryAssetKind.STICKER).firstOrNull { it.filePath == path }
            ?: dao.listByKind(LibraryAssetKind.SFX).firstOrNull { it.filePath == path }

    /**
     * 사용자가 Picker 로 고른 Uri 의 파일을 라이브러리 디렉터리로 복사해 등록.
     * 기존에 등록됐는지 여부는 URI 만으로 판정 불가(content://…/123 는 세션마다 달라질 수 있음)
     * 이라 매번 새 엔트리 생성. 중복이 불편하면 사용자가 삭제.
     */
    suspend fun register(
        kind: LibraryAssetKind,
        label: String,
        sourceUri: Uri,
        sourceExtension: String? = null,
    ): Result<LibraryAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = sourceExtension
                ?: guessExtension(context.contentResolver, sourceUri, kind)
            val fileName = "${UUID.randomUUID()}.$ext"
            val outFile = File(baseDir, fileName)
            context.contentResolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "파일을 열 수 없습니다" }
                outFile.outputStream().use { out -> input.copyTo(out) }
            }
            val duration = if (kind == LibraryAssetKind.SFX) readDurationMs(outFile) else 0L
            val entity = LibraryAssetEntity(
                kind = kind,
                label = label.ifBlank { outFile.nameWithoutExtension },
                filePath = outFile.absolutePath,
                durationMs = duration,
            )
            val id = dao.insert(entity)
            entity.copy(id = id)
        }
    }

    suspend fun rename(asset: LibraryAssetEntity, newLabel: String) {
        if (newLabel.isBlank()) return
        dao.insert(asset.copy(label = newLabel))
    }

    suspend fun delete(asset: LibraryAssetEntity) {
        dao.delete(asset)
        runCatching { File(asset.filePath).delete() }
    }

    private fun guessExtension(
        resolver: ContentResolver,
        uri: Uri,
        kind: LibraryAssetKind,
    ): String {
        val mime = resolver.getType(uri)
        return when {
            mime == "image/png" -> "png"
            mime == "image/jpeg" -> "jpg"
            mime == "image/webp" -> "webp"
            mime == "image/gif" -> "gif"
            mime == "audio/mpeg" -> "mp3"
            mime == "audio/wav" || mime == "audio/x-wav" -> "wav"
            mime == "audio/mp4" || mime == "audio/aac" -> "m4a"
            mime == "audio/ogg" -> "ogg"
            kind == LibraryAssetKind.STICKER -> "png"
            else -> "mp3"
        }
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
