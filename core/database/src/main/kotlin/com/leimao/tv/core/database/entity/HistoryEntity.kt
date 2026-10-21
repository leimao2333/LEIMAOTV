package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 观看历史（LIVE-15）。
 *
 * 一个频道只留一行、每次观看更新时间戳 —— 启动时只要「最近一条」，
 * 存全量流水既没必要又会让表无限增长。落点判定就是这张表上的一条索引查询（§6.6 约束 2）。
 */
@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("watchedAt")],
)
data class HistoryEntity(
    @PrimaryKey val channelId: Long,
    val watchedAt: Long,
)
