package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 频道。`(sourceId, name, tvgId)` 唯一：同一个源里「同名且同标识」才是重复数据，
 * 多线路走 [ChannelLineEntity]，不是在这里存两行。
 *
 * [url] 恒等于该频道第一条线路的地址 —— 保留这一列是为了「只想起播、不想查线路表」的场景
 * （启动路径上少一次查询，§6.6 约束 2）。入库时由 Repository 统一维护这个不变式，
 * 不允许别处直接改它。
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
        // v2 把唯一键从 (sourceId, name) 放宽到 (sourceId, name, tvgId)：
        // 有些源里两个不同的台真的同名（源作者自己标了不同的 tvg-id），
        // 硬按名字唯一会让第二个台插不进去。SQLite 的唯一索引里 NULL 互不冲突，
        // 而「同名且都没有 tvg-id」的条目在合并阶段已经被并成一条，不会因此产生重复行。
        Index(value = ["sourceId", "name", "tvgId"], unique = true),
        Index("sourceId"),
        Index("groupName"),
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
