package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 节目单订阅地址（LIVE-23）。
 *
 * 与直播源 `source` 表分开：节目单是 XMLTV 格式、走完全不同的解析器与刷新策略，
 * 塞进 `source` 会让 `type` 分发和请求头字段对两种数据都变成「一半用不到」。
 * 节目单源只有一个，多数用户也只配一份，所以不需要 `enabled` 之类的多源开关 ——
 * 定时刷新就是「抓这一个地址，整份替换」。
 */
@Entity(
    tableName = "epg_source",
    indices = [Index("url", unique = true)],
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val updatedAt: Long = 0,
    /** 上一次刷新的结果；空串表示从未成功。与直播源 `lastRefreshNote` 同约定 */
    val lastRefreshNote: String? = null,
)
