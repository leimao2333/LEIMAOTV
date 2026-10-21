package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.database.entity.FavoriteEntity
import com.leimao.tv.core.database.entity.HistoryEntity

@Dao
interface FavoriteDao {
    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE channelId = :channelId)")
    suspend fun isFavorite(channelId: Long): Boolean

    /** 启动落点优先级 #2：收藏列表的第一个（按收藏时间，先藏的优先） */
    @Query(
        """
        SELECT f.channelId FROM favorite f
        INNER JOIN channel c ON c.id = f.channelId
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
        ORDER BY f.addedAt ASC
        LIMIT 1
        """,
    )
    suspend fun firstFavoriteChannelId(): Long?

    @Query(
        """
        SELECT f.channelId FROM favorite f
        INNER JOIN channel c ON c.id = f.channelId
        WHERE c.sourceId IN (SELECT id FROM source WHERE enabled = 1)
        ORDER BY f.addedAt ASC
        """,
    )
    suspend fun favoriteChannelIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE channelId = :channelId")
    suspend fun delete(channelId: Long)
}

@Dao
interface HistoryDao {
    /**
     * 启动落点优先级 #1：最近一条观看记录。
     *
     * 一个频道只留一行（主键 channelId），所以这条查询命中索引后只回一行 ——
     * 正是 RULES §6.6 约束 2 要求的「启动只读一条记录」。
     */
    @Query(
        """
        SELECT h.channelId FROM history h
        INNER JOIN channel c ON c.id = h.channelId
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
        ORDER BY h.watchedAt DESC
        LIMIT 1
        """,
    )
    suspend fun latestChannelId(): Long?

    @Query(
        """
        SELECT c.* FROM history h
        INNER JOIN channel c ON c.id = h.channelId
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
        ORDER BY h.watchedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun recentChannels(limit: Int): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun touch(entry: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()
}
