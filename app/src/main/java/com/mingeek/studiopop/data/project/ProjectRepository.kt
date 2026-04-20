package com.mingeek.studiopop.data.project

import kotlinx.coroutines.flow.Flow

/**
 * 프로젝트 + 에셋 DB 접근을 한 곳으로 모아주는 Repository.
 */
class ProjectRepository(
    private val projectDao: ProjectDao,
    private val assetDao: AssetDao,
) {

    fun observeProjects(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    fun observeAssets(projectId: Long): Flow<List<AssetEntity>> =
        assetDao.observeForProject(projectId)

    suspend fun createProject(title: String, sourceVideoUri: String, description: String = ""): Long =
        projectDao.insertProject(
            ProjectEntity(
                title = title,
                description = description,
                sourceVideoUri = sourceVideoUri,
            )
        )

    suspend fun getProject(id: Long): ProjectEntity? = projectDao.getById(id)

    suspend fun deleteProject(project: ProjectEntity) = projectDao.delete(project)

    suspend fun addAsset(projectId: Long, type: AssetType, value: String, label: String = ""): Long =
        assetDao.insertAsset(
            AssetEntity(projectId = projectId, type = type, value = value, label = label)
        )

    suspend fun latestAsset(projectId: Long, type: AssetType): AssetEntity? =
        assetDao.latestOfType(projectId, type)

    suspend fun deleteAsset(asset: AssetEntity) = assetDao.delete(asset)
}
