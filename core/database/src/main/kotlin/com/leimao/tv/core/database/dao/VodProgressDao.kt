package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.leimao.tv.core.database.entity.VodProgressEntity

@Dao
interface VodProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VodProgressEntity)

    @Query("SELECT * FROM vod_progress WHERE sourceId = :sourceId AND sourceKey = :sourceKey")
    suspend fun find(
        sourceId: Long,
        sourceKey: String,
    ): VodProgressEntity?

    /**
     * 继续观看列表。
     *
     * 只认**启用中的源**：源被停用后片子点进去也拉不到详情，把它摆在首页第一行
     * 只会让人白点一次 —— 与 `HistoryDao.recentChannels` 同一套口径。
     */
    @Query(
        """
        SELECT p.* FROM vod_progress p
        INNER JOIN source s ON s.id = p.sourceId
        WHERE s.enabled = 1 AND p.finished = 0
        ORDER BY p.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun recent(limit: Int): List<VodProgressEntity>

    /**
     * 看过的（VOD-07）：与「继续观看」正好互补，同一个 `updatedAt` 排序。
     *
     * 两边的筛选条件必须**加起来是全集**，否则一部片会在两个列表里同时出现，
     * 或者两边都不出现（看完后从首页凭空消失）。
     */
    @Query(
        """
        SELECT p.* FROM vod_progress p
        INNER JOIN source s ON s.id = p.sourceId
        WHERE s.enabled = 1 AND p.finished = 1
        ORDER BY p.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun history(limit: Int): List<VodProgressEntity>

    @Query("DELETE FROM vod_progress WHERE sourceId = :sourceId AND sourceKey = :sourceKey")
    suspend fun delete(
        sourceId: Long,
        sourceKey: String,
    )

    @Query("DELETE FROM vod_progress WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: Long)

    /**
     * 只留最近 [keep] 条。
     *
     * 用 `rowid` 而不是 `(sourceId || ':' || sourceKey)` 这种拼接：后者在片名里带冒号时
     * （虽然 sourceKey 通常是数字 id）会拼出两个相同的键，静默删错行。
     */
    @Query(
        """
        DELETE FROM vod_progress WHERE rowid NOT IN (
            SELECT rowid FROM vod_progress ORDER BY updatedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(keep: Int)
}
