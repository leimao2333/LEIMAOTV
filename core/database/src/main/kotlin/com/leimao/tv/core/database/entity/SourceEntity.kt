package com.leimao.tv.core.database.entity

import androidx.room.Entity
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
    /** Basic 认证（LIVE-06）。只存在本机，不上传；空表示不需要认证 */
    val basicUser: String? = null,
    val basicPass: String? = null,
    val enabled: Boolean = true,
    val updatedAt: Long = 0,
    /** 上一次刷新的结果，空串表示从未成功；用于源管理页显示「上次更新」与失败原因 */
    val lastRefreshNote: String? = null,
)
