package com.leimao.tv.core.model.search

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.SearchScope

/**
 * 一条搜索结果来自哪一侧（SEARCH-03 的两区就是按它分的）。
 *
 * 直播频道与影视条目在 UI 上长得像、点进去却是两条完全不同的路（换台 vs 进详情），
 * 所以不能只靠 `type` 区分：`type` 说的是「这是什么内容」，[SearchKind] 说的是「点了会发生什么」。
 */
enum class SearchKind {
    CHANNEL,
    VOD,
}

/**
 * 搜索结果（SEARCH-01）。
 *
 * [key] 是**稳定唯一键**，用 [channelSearchKey] / [vodSearchKey] 生成：
 * 频道用 `ch:<channelId>`，影视用 `vod:<sourceId>:<sourceKey>` —— 与收藏、进度用的是同一套
 * 跨源唯一口径，点击后能直接定位到那条记录，不必再回查一次。
 *
 * 两个字段决定点击行为：[channelId] 非空就换台，[sourceKey] 非空就进详情。
 * 两个都为空是构造错误（见 [isActionable]），UI 侧据此禁用点击而不是猜。
 */
data class SearchHit(
    val kind: SearchKind,
    val key: String,
    val type: ContentType,
    val title: String,
    /** 副标题：频道是「分组 · 源名」，影视是「电影 · 2024 · 内地」。可为空 */
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val sourceId: Long,
    val sourceName: String? = null,
    val channelId: Long? = null,
    val sourceKey: String? = null,
) {
    val isActionable: Boolean
        get() =
            when (kind) {
                SearchKind.CHANNEL -> channelId != null
                SearchKind.VOD -> sourceKey != null
            }
}

/**
 * 一次搜索的**当前快照**（SEARCH-05 渐进式）。
 *
 * 为什么要快照而不是「结果列表 + 完成事件」：搜索是流式的 —— 本地频道几十毫秒就出，
 * 远端单源最多 8 秒。UI 每收到一帧就把整块重画一次，比「追加事件 + 自己拼列表」简单得多，
 * 也不会出现「追加了但没刷新」这种时序 bug。
 *
 * [pendingSources] 是**还在跑的远端源数量**，>0 表示结果还会变 ——
 * 空态必须等它归零才能说「什么都没搜到」（SEARCH-08），否则用户会看到「没结果」闪一下就出结果。
 */
data class SearchOutcome(
    val query: String,
    val scope: SearchScope,
    val hits: List<SearchHit> = emptyList(),
    val pendingSources: Int = 0,
    /** 这次没搜成功的源名（超时 / 报错）。不静默：用户该知道「有一部分源没查」 */
    val failedSources: List<String> = emptyList(),
) {
    val channels: List<SearchHit> get() = hits.filter { it.kind == SearchKind.CHANNEL }

    val vod: List<SearchHit> get() = hits.filter { it.kind == SearchKind.VOD }

    /** 还在搜 —— UI 显示进度条，且**不下空态结论** */
    val searching: Boolean get() = pendingSources > 0

    /** 搜完了，且真的一条都没有 */
    val isEmpty: Boolean get() = hits.isEmpty() && !searching
}

/** 频道结果的唯一键 */
fun channelSearchKey(channelId: Long): String = "ch:$channelId"

/** 影视结果的唯一键，口径与收藏 / 进度一致：跨源只有 `(sourceId, sourceKey)` 唯一 */
fun vodSearchKey(
    sourceId: Long,
    sourceKey: String,
): String = "vod:$sourceId:$sourceKey"

/**
 * 关键词归一化（SEARCH-06）。
 *
 * 做三件事：全角转半角（电视遥控器与手机分享来的关键词常带全角字符）、
 * 去掉首尾与内部所有空白、ASCII 转小写。
 *
 * **去内部空白而不只是 trim**：频道名里的空格是最不可靠的东西 ——
 * 「CCTV 1」「CCTV-1」「CCTV1」在源里三种写法都见过，用户不会记得自己搜的是哪种。
 * 本地匹配（[matchesSearchQuery]）用同一套归一化，所以「cctv 1」也能命中「CCTV-1」。
 */
fun normalizeSearchQuery(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        val half =
            when {
                ch == '\u3000' -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
        if (!half.isWhitespace()) sb.append(half.lowercaseChar())
    }
    return sb.toString()
}

/**
 * 本地包含匹配：把两边都按 [normalizeSearchQuery] 归一化后比 `contains`。
 *
 * 只在**本地**用（频道表、影视快照）。远端搜索交给源自己匹配 ——
 * 源端的 `wd` 参数有自己的分词与权重，本地再过滤一遍会把它认出来的近义词全砍掉。
 */
fun matchesSearchQuery(
    text: String?,
    query: String,
): Boolean {
    if (text.isNullOrEmpty() || query.isEmpty()) return false
    // 关键词也归一化一次：调用方可能传的是用户刚敲的原文（带全角字母、带空格），
    // 让「归一化」只在两处生效比要求每个调用点自己记得先转一次可靠
    val needle = normalizeSearchQuery(query)
    return needle.isNotEmpty() && normalizeSearchQuery(text).contains(needle)
}
