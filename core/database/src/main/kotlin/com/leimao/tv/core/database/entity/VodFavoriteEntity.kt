package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 收藏的影视条目（VOD-07）。
 *
 * 主键是 `(sourceId, sourceKey)`，与 `vod_progress` 同一套口径 —— 收藏的是「这个源里的
 * 这部片」，不是「叫这个名字的片」：两个源都上架的同一部片，收藏其中一个，
 * 在那个源失效时不该跟着消失。
 *
 * **同样不建指向 `media` 的外键**：`media` 每次刷新整类删了重插，CASCADE 会让
 * 「升级后第一次刷新」把收藏表清空。清理只在源被删除时显式做
 * （见 `SourceRepository.delete`）。
 */
@Entity(
    tableName = "vod_favorite",
    primaryKeys = ["sourceId", "sourceKey"],
    indices = [Index("addedAt")],
)
data class VodFavoriteEntity(
    val sourceId: Long,
    val sourceKey: String,
    /** 快照，见 `VodFavorite` 的注释 */
    val title: String,
    val poster: String? = null,
    /** `ContentType.name`；认不出的旧值读回时兜底成电影 */
    val type: String,
    val year: Int? = null,
    val area: String? = null,
    val remarks: String? = null,
    val addedAt: Long,
)
