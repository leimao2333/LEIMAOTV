package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.FavoriteEntity
import com.leimao.tv.core.database.entity.HistoryEntity
import com.leimao.tv.core.model.Channel

/**
 * 收藏（LIVE-14）。启动落点优先级 #2 也读它，所以「第一个收藏」必须是一条能走索引的查询，
 * 不能取全表再排序（§6.6 约束 2）。
 */
interface FavoriteRepository {
    suspend fun isFavorite(channelId: Long): Boolean

    /** 切换收藏，返回切换后的状态（true = 已收藏） */
    suspend fun toggle(channelId: Long, at: Long): Boolean

    suspend fun firstFavoriteChannelId(): Long?

    suspend fun favoriteChannelIds(): List<Long>
}

class RoomFavoriteRepository(
    private val database: LeimaoDatabase,
) : FavoriteRepository {
    private val dao get() = database.favoriteDao()

    override suspend fun isFavorite(channelId: Long): Boolean = dao.isFavorite(channelId)

    override suspend fun toggle(channelId: Long, at: Long): Boolean {
        val already = dao.isFavorite(channelId)
        if (already) {
            dao.delete(channelId)
        } else {
            dao.insert(FavoriteEntity(channelId = channelId, addedAt = at))
        }
        return !already
    }

    override suspend fun firstFavoriteChannelId(): Long? = dao.firstFavoriteChannelId()

    override suspend fun favoriteChannelIds(): List<Long> = dao.favoriteChannelIds()
}

/**
 * 观看历史（LIVE-15）。一个频道只留一行，每次观看只更新时间戳 ——
 * 启动只要「最近一条」，存全量流水既没必要又会让表无限增长。
 */
interface HistoryRepository {
    suspend fun latestChannelId(): Long?

    suspend fun touch(channelId: Long, at: Long)

    suspend fun recent(limit: Int = RECENT_LIMIT): List<Channel>

    suspend fun clear()

    companion object {
        /** 主页「最近观看」一行放得下的数量 */
        const val RECENT_LIMIT = 12
    }
}

class RoomHistoryRepository(
    private val database: LeimaoDatabase,
) : HistoryRepository {
    private val dao get() = database.historyDao()

    override suspend fun latestChannelId(): Long? = dao.latestChannelId()

    override suspend fun touch(channelId: Long, at: Long) =
        dao.touch(HistoryEntity(channelId = channelId, watchedAt = at))

    override suspend fun recent(limit: Int): List<Channel> =
        dao.recentChannels(limit).map { it.toModel() }

    override suspend fun clear() = dao.clear()
}
