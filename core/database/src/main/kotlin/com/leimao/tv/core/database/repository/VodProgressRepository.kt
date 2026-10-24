package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.VodProgressEntity
import com.leimao.tv.core.model.vod.VodContinueItem
import com.leimao.tv.core.model.vod.VodProgress

/**
 * 看片进度的读写（VOD-06）。
 *
 * 写入口只有 [save] 一个，取舍规则（多短不值得记、多长算看完）放在
 * `VodProgressRules` 里 —— 那是纯函数，能单测；这里只管落库。
 */
interface VodProgressRepository {
    /** 记一次进度。[title] / [poster] 是快照，见 `VodProgressEntity` 的注释 */
    suspend fun save(
        progress: VodProgress,
        title: String,
        poster: String?,
    )

    suspend fun find(
        sourceId: Long,
        sourceKey: String,
    ): VodProgress?

    /** 继续观看（最近看过的在前） */
    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<VodContinueItem>

    suspend fun delete(
        sourceId: Long,
        sourceKey: String,
    )

    suspend fun clearSource(sourceId: Long)

    companion object {
        /**
         * 最多留多少条。
         *
         * 继续观看那一行实际只显示得下七八张卡，但用户可能想「往回翻」找上周追的那部，
         * 所以库里多留一些。50 条已经足够覆盖「同时在追的剧 + 最近看过的电影」。
         */
        const val DEFAULT_LIMIT = 50
    }
}

class RoomVodProgressRepository(
    private val database: LeimaoDatabase,
) : VodProgressRepository {
    private val dao get() = database.vodProgressDao()

    override suspend fun save(
        progress: VodProgress,
        title: String,
        poster: String?,
    ) {
        dao.upsert(
            VodProgressEntity(
                sourceId = progress.sourceId,
                sourceKey = progress.sourceKey,
                lineIndex = progress.lineIndex,
                episodeIndex = progress.episodeIndex,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                updatedAt = progress.updatedAt,
                title = title,
                poster = poster,
            ),
        )
        // 每次写顺手裁一次：50 条的表上做一次排序删除，代价远小于「无限增长后才发现」
        dao.prune(VodProgressRepository.DEFAULT_LIMIT)
    }

    override suspend fun find(
        sourceId: Long,
        sourceKey: String,
    ): VodProgress? = dao.find(sourceId, sourceKey)?.toProgress()

    override suspend fun recent(limit: Int): List<VodContinueItem> =
        dao.recent(limit).mapNotNull { entity ->
            val progress = entity.toProgress() ?: return@mapNotNull null
            VodContinueItem(progress = progress, title = entity.title, poster = entity.poster)
        }

    override suspend fun delete(
        sourceId: Long,
        sourceKey: String,
    ) = dao.delete(sourceId, sourceKey)

    override suspend fun clearSource(sourceId: Long) = dao.clearSource(sourceId)
}

private fun VodProgressEntity.toProgress(): VodProgress? {
    // 源 id 不该是 0；真出现说明这行是脏数据（早期版本 / 手工改库），丢掉比带着它跑好
    if (sourceId <= 0L) return null
    return VodProgress(
        sourceId = sourceId,
        sourceKey = sourceKey,
        lineIndex = lineIndex,
        episodeIndex = episodeIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
    )
}
