package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 订阅源。直播源与影视源同表不同 [type]（架构设计 §5）。
 *
 * 刻意**不内置任何默认行** —— 表一开始就是空的，用户自己添加（RULES §2.1）。
 */
@Entity(
    tableName = "source",
    indices = [Index("enabled")],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** LIVE / VOD，与 :core:network 的适配器分发对应 */
    val type: String,
    val name: String,
    val url: String,
    /** 每源可配的请求头（LIVE-06）：有些源不带 UA / Referer 直接 403 */
    val userAgent: String? = null,
    val referer: String? = null,
    val enabled: Boolean = true,
    val updatedAt: Long = 0,
)

/**
 * 频道。`(sourceId, name)` 唯一（架构设计 §5）：同一个源里同名频道就是重复数据，
 * 多线路走 channel_line（M1 的 LIVE-13），不是在这里存两行。
 */
@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId", "name"], unique = true),
        Index("sourceId"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val tvgId: String? = null,
    val name: String,
    /** 无分组的频道统一归到「未分组」（LIVE-01） */
    val groupName: String,
    val logo: String? = null,
    val url: String,
    /** 频道号，数字键换台与「列表第一个」都按它排序（LIVE-11） */
    val number: Int = 0,
)
