package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.leimao.tv.core.database.entity.SourceEntity

@Dao
interface SourceDao {
    @Query("SELECT COUNT(*) FROM source WHERE enabled = 1")
    suspend fun countEnabled(): Int

    /**
     * 启用的**直播**源数量。
     *
     * 启动落点问的是「有没有台可播」，影视源不算 —— 只加了影视源的用户
     * 不该被丢进播放页等十秒的载入提示（VOD-03 引入 type 后必须区分）。
     */
    @Query("SELECT COUNT(*) FROM source WHERE enabled = 1 AND type = 'LIVE'")
    suspend fun countEnabledLive(): Int

    @Query("SELECT COUNT(*) FROM source")
    suspend fun countAll(): Int

    @Query("SELECT * FROM source ORDER BY sortOrder ASC, id ASC")
    suspend fun listAll(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun listEnabled(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun findById(id: Long): SourceEntity?

    /** 同 ChannelDao：REPLACE 会换主键，连带 cascade 删掉该源下所有频道与收藏 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("UPDATE source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 两个源的 sortOrder 对调，用于「上移/下移」（SET-03）。 */
    @Query("UPDATE source SET sortOrder = CASE id WHEN :a THEN :orderB WHEN :b THEN :orderA END WHERE id IN (:a, :b)")
    suspend fun swapSortOrder(a: Long, orderA: Int, b: Long, orderB: Int)

    /** 已占用的最大 sortOrder；新源追加到末尾用它 +1。 */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM source")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE source SET updatedAt = :updatedAt, lastRefreshNote = :note WHERE id = :id")
    suspend fun markRefreshed(id: Long, updatedAt: Long, note: String?)

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun deleteById(id: Long)
}
