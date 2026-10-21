package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.leimao.tv.core.database.entity.ChannelLineEntity

@Dao
interface ChannelLineDao {
    @Query("SELECT * FROM channel_line WHERE channelId = :channelId ORDER BY position ASC, id ASC")
    suspend fun linesFor(channelId: Long): List<ChannelLineEntity>

    @Query("SELECT COUNT(*) FROM channel_line WHERE channelId = :channelId")
    suspend fun countFor(channelId: Long): Int

    /**
     * 线路没有下级表，REPLACE 是安全的：同一频道同一地址重复出现时，
     * 换掉的只是一行交通信息，不会牵连收藏与历史。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lines: List<ChannelLineEntity>)

    @Query("DELETE FROM channel_line WHERE channelId = :channelId AND url NOT IN (:keepUrls)")
    suspend fun deleteExcept(channelId: Long, keepUrls: List<String>)

    @Query("DELETE FROM channel_line WHERE channelId = :channelId")
    suspend fun deleteFor(channelId: Long)
}
