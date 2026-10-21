package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.leimao.tv.core.database.entity.ChannelEntity

@Dao
interface ChannelDao {
    @Query("SELECT COUNT(*) FROM channel")
    suspend fun count(): Int

    /**
     * 只查「这条频道还在不在」，不查它的内容。
     *
     * 这是启动关键路径上的查询（RULES §6.6 约束 2）：上次看的台可能因为换源 / 改名已经没了，
     * 用一条主键查询就能判断要不要降级，不必把整个频道表读进来。
     */
    @Query("SELECT id FROM channel WHERE id = :id")
    suspend fun existingIdOrNull(id: Long): Long?

    @Query("SELECT id FROM channel ORDER BY number ASC, id ASC LIMIT 1")
    suspend fun firstChannelId(): Long?

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun findById(id: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE sourceId = :sourceId AND name = :name")
    suspend fun findBySourceAndName(sourceId: Long, name: String): ChannelEntity?

    /**
     * 列表页用的全量查询：只从**启用的源**取频道。
     *
     * 为什么在 SQL 里 join 而不是取出来再在 Kotlin 里过滤：5000 条频道的列表要按 §7 不丢帧，
     * 让数据库多做一次 join、少给内存一批行，比在 UI 线程外多一轮过滤更省。
     */
    @Query(
        """
        SELECT c.* FROM channel c
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
        ORDER BY c.number ASC, c.id ASC
        """,
    )
    suspend fun listFromEnabledSources(): List<ChannelEntity>

    @Query(
        """
        SELECT DISTINCT c.groupName FROM channel c
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
        ORDER BY c.groupName ASC
        """,
    )
    suspend fun listGroups(): List<String>

    @Query("SELECT * FROM channel WHERE sourceId = :sourceId ORDER BY number ASC, id ASC")
    suspend fun listBySource(sourceId: Long): List<ChannelEntity>

    /**
     * 刻意用 ABORT 而不是 REPLACE。
     *
     * REPLACE 的语义是「删掉冲突行再插入」，主键会变；而 favorite / history 靠外键级联挂在
     * 频道上 —— 一次刷新就会把用户的收藏与观看历史全删掉。刷新必须走「查到就 UPDATE、
     * 查不到才 INSERT」，id 才能稳定（见 SourceRefresh 的实现）。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(channel: ChannelEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(channels: List<ChannelEntity>): List<Long>

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("DELETE FROM channel WHERE id = :id")
    suspend fun deleteById(id: Long)
}
