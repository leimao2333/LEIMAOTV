package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 节目单频道（LIVE-20）。
 *
 * 存的是**归一化之后**的名字：查询「这个台现在播什么」时用的是播放列表的频道名，
 * 两边只有在同一个归一化结果上才对得上。[normalizedName] 由写入方算好，
 * 不在查询时现算 —— SQLite 里没有 [com.leimao.tv.core.model.epg.normalizeEpgName]。
 */
@Entity(
    tableName = "epg_channel",
    indices = [Index("normalizedName")],
)
data class EpgChannelEntity(
    /** XMLTV 里 `<channel id="...">` 的值 */
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
)

/**
 * 一条节目。
 *
 * [epgChannelId] 不做外键：EPG 文件经常是「先给节目、后给频道」或者干脆少几个频道定义，
 * 用外键会在导入时大批失败，而少一个频道的节目单比导入失败好得多。
 */
@Entity(
    tableName = "epg_programme",
    indices = [Index("epgChannelId"), Index("startAt")],
)
data class EpgProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgChannelId: String,
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val description: String? = null,
)
