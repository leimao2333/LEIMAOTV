package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.dao.ChannelLineQualityDao
import com.leimao.tv.core.database.entity.ChannelLineQualityEntity

/** 一条线路的质量（LIVE-13）。计数语义见 [ChannelLineQualityEntity]。 */
data class ChannelLineQuality(
    val channelId: Long,
    val url: String,
    val successCount: Int,
    val failCount: Int,
    val lastSeenAt: Long,
)

interface ChannelLineQualityRepository {
    /** 某频道的全部线路质量，按「最近播过」倒序 */
    suspend fun byChannel(channelId: Long): List<ChannelLineQuality>

    suspend fun find(
        channelId: Long,
        url: String,
    ): ChannelLineQuality?

    /** 记一次成功（次数 +1，重置失败？不 —— 失败历史保留，见实现注释） */
    suspend fun recordSuccess(
        channelId: Long,
        url: String,
        now: Long,
    )

    /** 记一次失败 / 卡顿 */
    suspend fun recordFailure(
        channelId: Long,
        url: String,
        now: Long,
    )

    suspend fun deleteChannel(channelId: Long)

    suspend fun deleteOlderThan(before: Long)
}

class RoomChannelLineQualityRepository(
    private val dao: ChannelLineQualityDao,
) : ChannelLineQualityRepository {
    override suspend fun byChannel(channelId: Long): List<ChannelLineQuality> =
        dao.byChannel(channelId).map { it.toModel() }

    override suspend fun find(
        channelId: Long,
        url: String,
    ): ChannelLineQuality? = dao.find(channelId, url)?.toModel()

    override suspend fun recordSuccess(
        channelId: Long,
        url: String,
        now: Long,
    ) {
        val existing = dao.find(channelId, url)
        dao.upsert(
            ChannelLineQualityEntity(
                channelId = channelId,
                url = url,
                // 成功不抹掉失败历史：一条线路「成功 3 次但也失败过 2 次」说明它不稳定，
                // 排序器需要完整的两个计数才能判断，不能因为最近一次成功就假装它从没失败过
                successCount = (existing?.successCount ?: 0) + 1,
                failCount = existing?.failCount ?: 0,
                lastSeenAt = now,
            ),
        )
    }

    override suspend fun recordFailure(
        channelId: Long,
        url: String,
        now: Long,
    ) {
        val existing = dao.find(channelId, url)
        dao.upsert(
            ChannelLineQualityEntity(
                channelId = channelId,
                url = url,
                successCount = existing?.successCount ?: 0,
                failCount = (existing?.failCount ?: 0) + 1,
                lastSeenAt = now,
            ),
        )
    }

    override suspend fun deleteChannel(channelId: Long) = dao.deleteChannel(channelId)

    override suspend fun deleteOlderThan(before: Long) = dao.deleteOlderThan(before)
}

private fun ChannelLineQualityEntity.toModel(): ChannelLineQuality =
    ChannelLineQuality(
        channelId = channelId,
        url = url,
        successCount = successCount,
        failCount = failCount,
        lastSeenAt = lastSeenAt,
    )
