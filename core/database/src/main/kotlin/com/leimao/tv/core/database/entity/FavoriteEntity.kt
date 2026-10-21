package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 收藏频道（LIVE-14）。
 *
 * 主键直接用 channelId：一个频道只能收藏一次，天然去重；频道被删时随外键级联清掉，
 * 不会留下指向不存在频道的「幽灵收藏」。
 */
@Entity(
    tableName = "favorite",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("addedAt")],
)
data class FavoriteEntity(
    @PrimaryKey val channelId: Long,
    val addedAt: Long,
)
