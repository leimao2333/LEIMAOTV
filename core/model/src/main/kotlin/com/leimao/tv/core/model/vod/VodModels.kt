package com.leimao.tv.core.model.vod

import com.leimao.tv.core.model.ContentType

/**
 * 影视源侧的一个分类（VOD-01）。
 *
 * [typeId] 是**源自己的**编号（苹果CMS 里是 `type_id`），跨源不通用 —— 所以它只能和
 * [sourceId] 配对使用，不能当全局 key。[type] 是映射到本项目的九类之后的结果，
 * 映射不出来（未分类 / 源自定义的怪名字）时为 null，由上层决定丢掉还是归入「其它」。
 */
data class VodCategory(
    val typeId: String,
    val name: String,
    val type: ContentType?,
)

/**
 * 影视条目摘要 —— 列表页、搜索结果、海报墙都吃这个。
 *
 * 跨源唯一标识是 `(sourceId, sourceKey)` 而不是单个字段：不同源之间 `vod_id` 会撞，
 * 同一个源换了采集站后 id 也可能变。详情页要拉全量数据时，靠这两个字段回源。
 */
data class VodSummary(
    val sourceId: Long,
    val sourceKey: String,
    val name: String,
    val type: ContentType,
    val pic: String? = null,
    /** 更新备注：源里叫 `vod_remarks`，常见「更新至20集」「HD国语」「全40集」 */
    val remarks: String? = null,
    val year: Int? = null,
    val area: String? = null,
    /**
     * 题材 / 属性标签（VOD-08），来自 `vod_class`（如「动作,科幻,冒险」）。
     *
     * 与 [type] 是两层：`type` 是九类里的一类（决定分栏），标签是这一类里更细的口味。
     * 源给的标签质量参差（有「HD」「国语」这类其实是属性、也有整段是分类名的），
     * 所以只当**筛选项**用，不做内容分类的依据。
     */
    val tags: List<String> = emptyList(),
    /**
     * 源侧更新时间（毫秒），来自 `vod_time`。
     *
     * 首页「最近更新」按它排序；源没给或格式认不出时为 null，
     * 落库时回落到入库时间 —— 排序不能因为缺字段就乱（VOD-03）。
     */
    val updatedAt: Long? = null,
)

/** 一集（或一部电影的唯一一条播放地址）。[name] 可能是「第01集」，也可能为空。 */
data class VodEpisode(val name: String, val url: String)

/**
 * 一条线路（苹果CMS 的「播放源」）。
 *
 * [name] 来自 `vod_play_from`（如「量子」「非凡」），是用户切线路时看到的名字；
 * 空串表示源没给名字，UI 侧兜底成「线路 N」。
 */
data class VodLine(
    val name: String,
    val episodes: List<VodEpisode>,
)

/**
 * 详情（VOD-04 用）。
 *
 * [content] 已经过 HTML 清洗 —— 源里返回的是 `<p>简介<br/></p>` 这种带标签的富文本，
 * 直接塞进 Text 会把标签当正文显示出来。
 */
data class VodDetail(
    val summary: VodSummary,
    val content: String? = null,
    val actor: String? = null,
    val director: String? = null,
    val score: String? = null,
    val lang: String? = null,
    val lines: List<VodLine> = emptyList(),
)

/**
 * 一页列表。
 *
 * [pageCount] / [total] 来自源的分页信息，用于「还有没有下一页」与进度展示；
 * 源没给时按 0 处理（UI 侧退化成「有返回就还能翻」）。
 *
 * [droppedCount] 是「分类名映射不出九类，被丢掉的条目数」。不静默丢：源自定义分类
 * （如「福利」）映射不出时按合规默认丢弃，但要让 UI 能说清「这一页有几条没显示」，
 * 否则用户只会觉得「源少了内容」。
 */
data class VodPage(
    val items: List<VodSummary>,
    val page: Int = 1,
    val pageCount: Int = 0,
    val total: Int = 0,
    val droppedCount: Int = 0,
) {
    val hasMore: Boolean get() = pageCount > page
}
