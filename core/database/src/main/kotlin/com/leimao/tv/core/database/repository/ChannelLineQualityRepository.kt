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

    /**
     * 落盘裁剪（LIVE-25）：过期删 + 超量删最旧。
     *
     * 读失败必须退化为空表、绝不打断起播 —— 所以这里要么成功裁剪，要么什么都不做，
     * 绝不把裁剪的异常抛给起播链路（§6.6 关键路径）。
     */
    suspend fun prune(now: Long)
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
        prune(now)
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
        prune(now)
    }

    override suspend fun deleteChannel(channelId: Long) = dao.deleteChannel(channelId)

    override suspend fun deleteOlderThan(before: Long) = dao.deleteOlderThan(before)

    override suspend fun prune(now: Long) {
        runCatching {
            // 过期：180 天没被任何记录碰过，源早就换了，旧 URL 留着只会误伤新线路
            dao.deleteOlderThan(now - EXPIRE_AFTER_MS)
            // 超量：只留最近 MAX_ROWS 条，其余删最旧（子查询在数据库里做，不拉全表进内存）
            if (dao.count() > MAX_ROWS) dao.trimToKeep(MAX_ROWS)
        }
    }

    private companion object {
        /** 记忆上限：再多的「这条线路失败过」也没有参考价值，只会拖慢排序与占表 */
        const val MAX_ROWS = 200

        /** 180 天过期：太久远的失败记录对「现在这条源还通不通」没有任何参考意义 */
        const val EXPIRE_AFTER_MS = 180L * 24 * 60 * 60 * 1000
    }
}

private fun ChannelLineQualityEntity.toModel(): ChannelLineQuality =
    ChannelLineQuality(
        channelId = channelId,
        url = url,
        successCount = successCount,
        failCount = failCount,
        lastSeenAt = lastSeenAt,
    )
