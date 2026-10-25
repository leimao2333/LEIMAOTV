package com.leimao.tv.feature.vod

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodContinueItem
import com.leimao.tv.core.model.vod.VodFavorite
import com.leimao.tv.core.model.vod.VodSummary

/**
 * 首页栏目顺序（VOD-03）。
 *
 * 只放八个「用户会主动逛」的类型。**资讯与预告片不进首页**：这两类内容密度低、
 * 更新碎，塞进首屏只会把电影/剧集挤下去，需要它们时走搜索。
 *
 * 戏曲（VOD-13）排在综艺与体育之间：老年观众把它当「固定栏目」找，靠搜索太深。
 */
val VOD_HOME_TYPES: List<ContentType> =
    listOf(
        ContentType.MOVIE,
        ContentType.TV_SERIES,
        ContentType.ANIME,
        ContentType.VARIETY,
        ContentType.OPERA,
        ContentType.SPORTS,
        ContentType.SHORT_PLAY,
        ContentType.DOCUMENTARY,
    )

/** 「每源一行」的一行。源可能这次没抓到内容，[items] 为空时这一行不渲染。 */
data class VodSourceRow(
    val sourceId: Long,
    val sourceName: String,
    val items: List<VodSummary>,
)

/**
 * 影视首页的全部展示状态。
 *
 * 由 app 层从 `media` 表查出后整块传进来 —— feature 模块不碰数据层，
 * 这样首页的可预览性、可测性都不受数据库影响（与直播板块同一套做法）。
 */
data class VodHomeState(
    val selectedType: ContentType = ContentType.MOVIE,
    /** 当前类型下出现过的年份，倒序；为空表示没数据，不显示年份筛选 */
    val years: List<Int> = emptyList(),
    val areas: List<String> = emptyList(),
    val selectedYear: Int? = null,
    val selectedArea: String? = null,
    /** 当前类型下出现过的题材标签（VOD-08），按出现频次排 */
    val tags: List<String> = emptyList(),
    val selectedTag: String? = null,
    /** 当前类型 + 当前筛选的结果 */
    val items: List<VodSummary> = emptyList(),
    /** 全库最近更新 */
    val recent: List<VodSummary> = emptyList(),
    /**
     * 继续观看（VOD-06）。
     *
     * 放在筛选之上、且**筛选时不隐藏**：用户切到「2024 / 美国」是想找新片，
     * 但「我昨天看到第 12 集」这件事跟筛选条件无关 —— 藏起来只会让他以为进度丢了。
     */
    val continueWatching: List<VodContinueItem> = emptyList(),
    /**
     * 看过的（VOD-07）：与 [continueWatching] **互补**，没有交集。
     *
     * 一部片要么「还没看完」进去继续看，要么「看完了」进去是想再看一遍 ——
     * 两个动作不同，所以是两行而不是一行带标签。全集只有一份数据（`vod_progress`），
     * 靠 `finished` 分流。
     */
    val history: List<VodContinueItem> = emptyList(),
    /** 收藏的片子（VOD-07），最近收藏的在前 */
    val favorites: List<VodFavorite> = emptyList(),
    /** 每源一行 */
    val sourceRows: List<VodSourceRow> = emptyList(),
    val loading: Boolean = false,
    /** 是否已配置影视源。false 时是「去加源」，而不是「源里没内容」 */
    val hasSource: Boolean = true,
    /** 需要直接告诉用户的一句话（抓取失败的原因等） */
    val note: String? = null,
) {
    val filtering: Boolean
        get() = selectedYear != null || selectedArea != null || selectedTag != null

    /** 所有内容块都空 —— 此时渲染空态，而不是一堆空行 */
    val blank: Boolean
        get() =
            items.isEmpty() &&
                recent.isEmpty() &&
                sourceRows.isEmpty() &&
                continueWatching.isEmpty() &&
                history.isEmpty() &&
                favorites.isEmpty()
}
