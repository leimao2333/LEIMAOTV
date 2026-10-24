package com.leimao.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 看片进度（VOD-06）。
 *
 * 主键是 `(sourceId, sourceKey)` —— 一部片一条，看到哪儿就覆盖到哪儿。
 * 不存流水（每集一行）：用户要的是「上次看到哪」，而不是一份可以回放的轨迹；
 * 流水还会让表随观看次数无限长，而查询永远只需要最新那条。
 *
 * **刻意不建指向 `media` 的外键**。`media` 是首页快照，刷新时整类删了重插
 * （`MediaDao.replaceTypes`），外键 CASCADE 会顺手把用户看了三十集的进度一起删掉 ——
 * 而这两件事半点关系都没有。进度只跟着「源」的生死走（源被删时由
 * [com.leimao.tv.core.database.repository.SourceRepository.delete] 显式清理）。
 *
 * [title] / [poster] 是入库时的快照，理由同 `VodContinueItem` 的注释：
 * 老剧会被挤出首页快照，那时再 JOIN `media` 就只能得到一张没名字的卡。
 */
@Entity(
    tableName = "vod_progress",
    primaryKeys = ["sourceId", "sourceKey"],
    indices = [Index("updatedAt")],
)
data class VodProgressEntity(
    val sourceId: Long,
    val sourceKey: String,
    val lineIndex: Int,
    val episodeIndex: Int,
    val positionMs: Long,
    /** 0 = 内核还没报时长，不是「零长度」 */
    val durationMs: Long,
    val updatedAt: Long,
    val title: String,
    val poster: String? = null,
    /**
     * 看完了没有（VOD-07）。
     *
     * 看完的**不删**：这一行同时是「继续观看」（`finished = 0`）与「看过的」
     * （`finished = 1`）两个列表的数据源。VOD-06 当初用删除表达「看完了」，
     * 代价是「我看过这部片」这件事在应用里彻底消失。
     */
    val finished: Boolean = false,
)
