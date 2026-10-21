package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 线路：一个频道可以有多条地址（LIVE-13）。
 *
 * 为什么独立成表而不是在 channel 里塞 `url1/url2/url3`：线路数量不固定，
 * 「播放失败自动切下一条」需要按顺序遍历，塞成列之后每次都要写一堆空值判断。
 *
 * 不变式：**每个频道的第一条线路（position 最小）就是 `channel.url`**，
 * 由 Repository 在写入时统一维护；读的时候只有「要换线路」才需要这张表。
 */
@Entity(
    tableName = "channel_line",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["channelId", "url"], unique = true),
        Index("channelId"),
    ],
)
data class ChannelLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val url: String,
    /** 线路标签。源文件里没有线路名，留空；以后支持手填「电信 / 联通」也不用改表 */
    val label: String? = null,
    /** 同频道内的顺序，0 为首选线路 */
    val position: Int = 0,
)
