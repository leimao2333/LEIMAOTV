package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.database.entity.SourceEntity

@Dao
interface SourceDao {
    @Query("SELECT COUNT(*) FROM source WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceEntity): Long

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT COUNT(*) FROM channel")
    suspend fun count(): Int

    /**
     * 只查「这条频道还在不在」，不查它的内容。
     *
     * 这是启动关键路径上的查询（RULES §6.6 约束 2）：上次看的台可能因为换源 / 改名已经没了，
     * 用一条主键查询就能判断要不要降级，不必把整个频道表读进来。
     * 返回 null 即「已不存在」，调用方据此降级到收藏第一个。
     */
    @Query("SELECT id FROM channel WHERE id = :id")
    suspend fun existingIdOrNull(id: Long): Long?

    @Query("SELECT id FROM channel ORDER BY number ASC, id ASC LIMIT 1")
    suspend fun firstChannelId(): Long?

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun findById(id: Long): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: ChannelEntity): Long
}
