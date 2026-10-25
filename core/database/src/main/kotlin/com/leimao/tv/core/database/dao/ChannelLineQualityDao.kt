package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.leimao.tv.core.database.entity.ChannelLineQualityEntity

/**
 * 频道线路质量（LIVE-13 智能调度）。
 *
 * 所有操作都按 [channelId] 命中：播放一条频道时只关心它自己的几条线路，
 * 跨频道不聚合 —— 调度粒度是「同一个频道挑最好的线路」，不是「全局挑最好的频道」。
 */
@Dao
interface ChannelLineQualityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChannelLineQualityEntity)

    /** 某频道全部线路的质量记录 */
    @Query("SELECT * FROM channel_line_quality WHERE channelId = :channelId")
    suspend fun byChannel(channelId: Long): List<ChannelLineQualityEntity>

    /** 单条线路的质量记录；没记过返回 null */
    @Query("SELECT * FROM channel_line_quality WHERE channelId = :channelId AND url = :url")
    suspend fun find(
        channelId: Long,
        url: String,
    ): ChannelLineQualityEntity?

    /** 删某频道的全部记录：频道被删 / 源被删时连带清掉（与 channel 外键语义对齐，但这里手动做） */
    @Query("DELETE FROM channel_line_quality WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: Long)

    /** 清掉过期的记录：源换了之后旧 URL 没意义，占着表只会让「好久没播」的降权逻辑误伤新线路 */
    @Query("DELETE FROM channel_line_quality WHERE lastSeenAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
