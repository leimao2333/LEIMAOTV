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

    /** 总记录数（裁剪用：超过上限才动刀，避免每次回写都扫一遍） */
    @Query("SELECT COUNT(*) FROM channel_line_quality")
    suspend fun count(): Int

    /**
     * 只保留最近 [keep] 条（按 lastSeenAt 倒序），其余删掉。
     *
     * 用子查询挑出要删的行：`lastSeenAt` 排不进前 [keep] 的都在删除集里。
     * 内存里做「读全表 → 排序 → 删」也行，但那是把几万行拉进内存，
     * SQL 一条子查询让数据库自己算，才是盒子上的正解。
     */
    @Query(
        """
        DELETE FROM channel_line_quality
        WHERE (channelId, url) IN (
            SELECT channelId, url FROM channel_line_quality
            ORDER BY lastSeenAt DESC
            LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimToKeep(keep: Int)
}
