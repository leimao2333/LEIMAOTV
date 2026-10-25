package com.leimao.tv.core.database.entity

import androidx.room.Entity

/**
 * 频道线路的质量记录（LIVE-13 智能调度）。
 *
 * 一条「频道 + 线路 URL」一行。复合主键 [channelId] + [url]：换源后同一频道的线路 URL 会变，
 * 所以不能用自增 id，而要让「这条线路」能被稳定地记住。
 *
 * 质量用两个计数表达，不存打分：
 * - [successCount]：这条线路成功起播过的次数 —— 越多越可信；
 * - [failCount]：这条线路失败 / 卡顿过的次数 —— 越多越该靠后、甚至跳过。
 *
 * 两个数分开存而不是合成一个分数，是因为「成功过 10 次、失败过 1 次」和「成功 1 次、失败 1 次」
 * 是完全不同的可信度，排序器需要原始计数，不能在落库时就把信息量丢掉。
 *
 * [lastSeenAt] 是最近一次**任何**记录（成功或失败）的时间，用来给「很久没播过」的线路降权：
 * 源是会变的，三个月前的「这条线路很快」不等于现在还能用。
 *
 * 注意：复合主键必须写在 `@Entity(primaryKeys = ...)` 里。字段上的 `@PrimaryKey` 只支持单列，
 * 而且单列注解会让 Room 的 schema 校验按「另一个主键」去比 —— 迁移 SQL 与实体对不上时
 * 启动即崩（`Migration didn't properly handle`，实测）。
 */
@Entity(
    tableName = "channel_line_quality",
    primaryKeys = ["channelId", "url"],
)
data class ChannelLineQualityEntity(
    val channelId: Long,
    val url: String,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastSeenAt: Long = 0,
)
