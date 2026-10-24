package com.leimao.tv.feature.search

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.IPTV_ALLOWED_TYPES
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.search.SearchHit
import com.leimao.tv.core.model.search.SearchOutcome
import com.leimao.tv.core.model.search.normalizeSearchQuery

/**
 * 搜索页的全部输入（SEARCH-03 / 05 / 07 / 08）。
 *
 * 只装「渲染需要什么」，不含任何仓库引用：路由层把 `Flow<SearchOutcome>` 收成一帧帧快照塞进来，
 * 这个类只负责把它翻译成「该显示哪一块」。所以这些派生属性全都能在 JVM 上直接测。
 */
data class SearchUiState(
    val query: String = "",
    val scope: SearchScope = SearchScope.IPTV,
    /** null = 还没搜（空关键词 / 防抖窗口内），与「搜完了但没结果」是两件事 */
    val outcome: SearchOutcome? = null,
    val history: List<String> = emptyList(),
) {
    /**
     * 只认「与当前关键词同一条」的结果。
     *
     * 结果是一帧帧回来的，用户改词之后上一轮的帧还可能在路上：不过滤的话，
     * 搜「流浪地球」的第一帧会显示在「三体」的结果区里 —— 而它明明没搜错，只是慢了一拍。
     * 判定用归一化后的词，和仓库出口的口径一致（`SearchOutcome.query` 存的就是归一化后的值）。
     */
    val current: SearchOutcome? get() = outcome?.takeIf { it.query == normalizeSearchQuery(query) }

    val channelHits: List<SearchHit> get() = current?.channels.orEmpty()

    val vodHits: List<SearchHit> get() = current?.vod.orEmpty()

    val hasQuery: Boolean get() = query.isNotBlank()

    /** 还在搜某几条源 —— 底部显示进度，但**不下「没搜到」的结论** */
    val searching: Boolean get() = current?.searching == true

    /** 搜完了，一条都没有（SEARCH-08）。搜索中不算，否则空态会先闪一下 */
    val noResult: Boolean get() = hasQuery && current?.isEmpty == true

    val showHistory: Boolean get() = !hasQuery && history.isNotEmpty()

    val statusText: String? get() = describeSearchStatus(current, hasQuery)

    val emptyHint: SearchEmptyHint get() = emptyHintFor(scope)
}

/**
 * 底部状态行。null 表示不用说话。
 *
 * 「正在搜」与「哪条源没查完」要能同时出现：用户看到结果的同时也该知道下面还可能补东西，
 * 否则他会以为「就这些了」，然后怪源太烂。
 */
fun describeSearchStatus(
    outcome: SearchOutcome?,
    hasQuery: Boolean,
): String? {
    if (!hasQuery || outcome == null) return null
    val missing = outcome.failedSources
    return when {
        outcome.searching && missing.isEmpty() -> "正在搜索…"
        outcome.searching -> "正在搜索…（${missing.joinToString("、")} 还没回来）"
        missing.isNotEmpty() -> "${missing.joinToString("、")} 没查完，结果可能不全"
        else -> null
    }
}

/** 无结果时说什么、给什么出口（SEARCH-08）。 */
data class SearchEmptyHint(
    val title: String,
    val detail: String,
    /** 可点的出口文案；null 表示这一屏没有可点的动作 */
    val action: String? = null,
)

/**
 * 无结果引导的文案。
 *
 * 直播板块必须**说清是口径问题而不是没有这部片**：用户搜「某某纪录片」搜不到，
 * 十有八九会以为源里没有，实际上是白名单挡住了。所以这里既写清楚六类是什么，
 * 又给一个「去影视板块搜全部类型」的出口 —— 只解释不给出路，等于让用户自己去猜怎么切。
 */
fun emptyHintFor(scope: SearchScope): SearchEmptyHint =
    when (scope) {
        SearchScope.IPTV ->
            SearchEmptyHint(
                title = "直播板块没有搜到",
                detail =
                    "这个板块只搜这六类：" +
                        IPTV_ALLOWED_TYPES.joinToString(" / ") { it.displayName } +
                        "。短剧、纪录片、资讯、预告片要去影视板块搜。",
                action = "去影视板块搜全部类型",
            )

        SearchScope.VOD ->
            SearchEmptyHint(
                title = "影视板块没有搜到",
                detail =
                    "影视板块不限制类型，九类都会返回。试试更短的词，" +
                        "或者检查影视源是否正常（源管理里看上次刷新结果）。",
            )
    }

/** 输入框上方的口径说明：用户还没搜的时候就该知道这个板块能搜到什么 */
fun scopeHintFor(scope: SearchScope): String =
    when (scope) {
        SearchScope.IPTV ->
            "搜频道与影视；${BLOCKED_IN_IPTV.joinToString(" / ") { it.displayName }} 不在这里出现"

        SearchScope.VOD -> "搜全部九类影视内容"
    }

/** 影视板块里被白名单挡住的四类，界面上用一句话点出来（避免用户以为「源里没有」） */
val BLOCKED_IN_IPTV: Set<ContentType> = ContentType.VOD_TYPES - IPTV_ALLOWED_TYPES
