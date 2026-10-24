package com.leimao.tv.core.model.vod

import com.leimao.tv.core.model.ContentType

/**
 * 收藏的一部片子（VOD-07）。
 *
 * 与直播的收藏（`FavoriteEntity`，主键是 channelId）**不复用同一张表**：
 * 频道有本地自增 id、可以随外键级联清理；影视条目是「源 + 源内编号」的组合，
 * 而且 `media` 表每次刷新整类重插 —— 给它建外键等于让收藏跟着缓存一起消失。
 *
 * 标题 / 海报 / 类型等字段都是**入库时的快照**，理由与 `VodContinueItem` 相同：
 * 收藏一部老片，几个月后它早被挤出首页快照了，那时再回 `media` 查只会得到一张
 * 没有名字的空卡 —— 而收藏页要回答的恰恰是「我收藏了哪些」。
 *
 * [type] 决定卡片上那行副标题（没有年份/地区时兜底显示类型），所以它也一起快照。
 */
data class VodFavorite(
    val sourceId: Long,
    val sourceKey: String,
    val title: String,
    val poster: String? = null,
    val type: ContentType = ContentType.MOVIE,
    val year: Int? = null,
    val area: String? = null,
    val remarks: String? = null,
    val addedAt: Long = 0L,
) {
    /** 渲染用的稳定 key（片名可能重复，`(sourceId, sourceKey)` 不会） */
    val key: String get() = "$sourceId:$sourceKey"

    /**
     * 还原成海报卡吃的摘要。
     *
     * 不做「收藏页专用卡片」：收藏页要画的东西与首页一行海报完全一样，
     * 复制一份组件只会让两处的圆角、字号、省略行数慢慢长歪。
     */
    fun toSummary(): VodSummary =
        VodSummary(
            sourceId = sourceId,
            sourceKey = sourceKey,
            name = title,
            type = type,
            pic = poster,
            remarks = remarks,
            year = year,
            area = area,
        )
}
