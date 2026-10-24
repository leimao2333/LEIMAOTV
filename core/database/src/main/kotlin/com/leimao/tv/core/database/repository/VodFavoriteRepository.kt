package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.VodFavoriteEntity
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodFavorite

/**
 * 影视收藏的读写（VOD-07）。
 *
 * 只有「加 / 取消 / 查」三个动作，没有自己的业务规则 —— 收藏不需要「多短算收藏」
 * 这种阈值，用户点一下就是他的意思。界面上的乐观更新由调用方负责。
 */
interface VodFavoriteRepository {
    /** 收藏。重复收藏同一条只会刷新 [VodFavorite.addedAt]（进列表尾部），不会变成两条 */
    suspend fun add(favorite: VodFavorite)

    suspend fun remove(
        sourceId: Long,
        sourceKey: String,
    )

    suspend fun isFavorite(
        sourceId: Long,
        sourceKey: String,
    ): Boolean

    /** 收藏列表，最近收藏的在前 */
    suspend fun list(limit: Int = DEFAULT_LIMIT): List<VodFavorite>

    suspend fun clearSource(sourceId: Long)

    companion object {
        /**
         * 最多取多少条给界面。
         *
         * 与进度表的 50 条不同：收藏是**用户主动挑的**，不会自己膨胀，
         * 所以库里不裁、只限制一次取多少。取 100 条已经够翻很久，
         * 再多的收藏靠搜索找更快。
         */
        const val DEFAULT_LIMIT = 100
    }
}

class RoomVodFavoriteRepository(
    private val database: LeimaoDatabase,
) : VodFavoriteRepository {
    private val dao get() = database.vodFavoriteDao()

    override suspend fun add(favorite: VodFavorite) {
        dao.upsert(
            VodFavoriteEntity(
                sourceId = favorite.sourceId,
                sourceKey = favorite.sourceKey,
                title = favorite.title,
                poster = favorite.poster,
                type = favorite.type.name,
                year = favorite.year,
                area = favorite.area,
                remarks = favorite.remarks,
                addedAt = favorite.addedAt,
            ),
        )
    }

    override suspend fun remove(
        sourceId: Long,
        sourceKey: String,
    ) = dao.delete(sourceId, sourceKey)

    override suspend fun isFavorite(
        sourceId: Long,
        sourceKey: String,
    ): Boolean = dao.find(sourceId, sourceKey) != null

    override suspend fun list(limit: Int): List<VodFavorite> = dao.list(limit).mapNotNull { it.toFavorite() }

    override suspend fun clearSource(sourceId: Long) = dao.clearSource(sourceId)
}

private fun VodFavoriteEntity.toFavorite(): VodFavorite? {
    if (sourceId <= 0L) return null
    return VodFavorite(
        sourceId = sourceId,
        sourceKey = sourceKey,
        title = title,
        poster = poster,
        // 类型是快照的字符串：以后枚举改名或删类时，旧行读回来不能整行作废，
        // 兜底成电影至少还能显示、还能点进去
        type = runCatching { ContentType.valueOf(type) }.getOrDefault(ContentType.MOVIE),
        year = year,
        area = area,
        remarks = remarks,
        addedAt = addedAt,
    )
}
