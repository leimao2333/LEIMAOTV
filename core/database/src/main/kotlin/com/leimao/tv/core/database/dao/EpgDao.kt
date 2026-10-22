package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.leimao.tv.core.database.entity.EpgChannelEntity
import com.leimao.tv.core.database.entity.EpgProgrammeEntity

/**
 * 节目单读写（LIVE-20）。
 *
 * 整份节目单是「一次导入、整天只读」的数据，所以写入用「先清空再批量插入」，
 * 读只按频道名 + 时间窗查几条 —— 不做增量更新，是因为 EPG 文件本身就是全量快照，
 * 做增量反而要处理「上一份有、这一份没有」的删除逻辑，得不偿失。
 */
@Dao
interface EpgDao {
    @Query("SELECT COUNT(*) FROM epg_programme")
    suspend fun programmeCount(): Int

    @Query("SELECT COUNT(*) FROM epg_channel")
    suspend fun channelCount(): Int

    @Query("SELECT MAX(startAt) FROM epg_programme")
    suspend fun latestStartAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<EpgChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(programmes: List<EpgProgrammeEntity>)

    @Query("DELETE FROM epg_programme")
    suspend fun clearProgrammes()

    @Query("DELETE FROM epg_channel")
    suspend fun clearChannels()

    /**
     * 取若干频道（按归一化名）在 [from]~[to] 时间窗内的节目，按频道 + 开始时间排序。
     *
     * 时间窗而不是「全部」：一份聚合 EPG 有一万多条节目，播放页只关心「现在」和「接下来」，
     * 全取回来再在内存里筛，等于把 DB 的索引白建了。
     */
    @Query(
        """
        SELECT p.* FROM epg_programme p
        INNER JOIN epg_channel c ON c.id = p.epgChannelId
        WHERE c.normalizedName IN (:normalizedNames)
          AND p.endAt > :from AND p.startAt < :to
        ORDER BY c.normalizedName ASC, p.startAt ASC
        """,
    )
    suspend fun programmesInWindow(
        normalizedNames: List<String>,
        from: Long,
        to: Long,
    ): List<EpgProgrammeEntity>

    @Query("SELECT * FROM epg_channel WHERE normalizedName = :normalizedName")
    suspend fun channelsByNormalizedName(normalizedName: String): List<EpgChannelEntity>

    /** 清空 + 批量插入放在一个事务里：中途失败时不能留下半份节目单 */
    @Transaction
    suspend fun replaceAll(
        channels: List<EpgChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
    ) {
        clearProgrammes()
        clearChannels()
        insertChannels(channels)
        // 一万多条一次 insert 会撞 SQLite 的变量上限，分批写
        programmes.chunked(INSERT_BATCH).forEach { insertProgrammes(it) }
    }

    private companion object {
        /** SQLite 单条语句最多 999 个绑定参数；6 列 × 500 = 3000，Room 会拆语句，取 500 稳妥 */
        const val INSERT_BATCH = 500
    }
}
