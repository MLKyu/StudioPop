package com.mingeek.studiopop.data.media

import android.content.Context
import com.mingeek.studiopop.data.project.AssetEntity
import com.mingeek.studiopop.data.project.AssetType
import com.mingeek.studiopop.data.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MediaStore 퍼블리시가 붙기 **이전에** 만들어진 기존 asset (EXPORT_VIDEO / SHORTS / CAPTION_SRT)
 * 을 공개 경로(Movies/StudioPop, Download/StudioPop) 에 소급 등록.
 *
 * - 각 asset 마다 한 번만 퍼블리시 (성공한 id 를 SharedPreferences 에 기록해서 중복 방지).
 * - 이미 퍼블리시된 asset 이나 파일이 사라진 asset 은 스킵.
 * - 네트워크/외부 서비스 호출이 없고 IO 만 있으므로 bindProject 시점에 fire-and-forget 으로 호출.
 */
class AssetBackfillPublisher(
    context: Context,
    private val projectRepository: ProjectRepository,
    private val videoPublisher: MediaStoreVideoPublisher,
    private val srtPublisher: MediaStoreSrtPublisher,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * [projectId] 에 속한 asset 중 아직 퍼블리시되지 않은 것을 모두 공개 폴더에 등록.
     * 결과 수치(영상/자막 등록 성공 건수) 반환 — UI 에 toast 로 노출하고 싶으면 사용.
     */
    suspend fun backfill(projectId: Long): Result = withContext(Dispatchers.IO) {
        val assets: List<AssetEntity> = projectRepository.listAssets(projectId)
        val already: MutableSet<String> = prefs.getStringSet(KEY_PUBLISHED, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

        var videoOk = 0
        var srtOk = 0
        for (asset in assets) {
            val key = asset.id.toString()
            if (key in already) continue

            val file = File(asset.value)
            if (!file.exists() || file.length() == 0L) {
                // 파일이 사라진 asset 은 다시 시도할 필요 없음
                already += key
                continue
            }

            val published = when (asset.type) {
                AssetType.EXPORT_VIDEO, AssetType.SHORTS ->
                    videoPublisher.publish(
                        file = file,
                        displayName = "StudioPop_${file.nameWithoutExtension}",
                    ) != null
                AssetType.CAPTION_SRT ->
                    srtPublisher.publish(
                        file = file,
                        displayName = "StudioPop_${file.nameWithoutExtension}",
                    ) != null
                else -> true // 퍼블리시 대상 아님 — 완료로 마킹해 재시도 방지
            }

            if (published) {
                already += key
                when (asset.type) {
                    AssetType.EXPORT_VIDEO, AssetType.SHORTS -> videoOk++
                    AssetType.CAPTION_SRT -> srtOk++
                    else -> Unit
                }
            }
        }

        prefs.edit().putStringSet(KEY_PUBLISHED, already).apply()
        Result(videoPublished = videoOk, srtPublished = srtOk)
    }

    /**
     * 저장된 "퍼블리시 완료" 기록을 모두 삭제. "다시 등록" 버튼이 누른 시점에 호출 →
     * 다음 backfill 이 전체를 재퍼블리시 (갤러리에서 실수로 삭제한 경우 복구 용도).
     */
    fun clearState() {
        prefs.edit().remove(KEY_PUBLISHED).apply()
    }

    data class Result(val videoPublished: Int, val srtPublished: Int) {
        val total: Int get() = videoPublished + srtPublished
    }

    companion object {
        private const val PREFS_NAME = "media_backfill"
        private const val KEY_PUBLISHED = "published_asset_ids"
    }
}
