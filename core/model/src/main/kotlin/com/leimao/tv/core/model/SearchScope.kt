package com.leimao.tv.core.model

/** 搜索从哪个板块发起。没有默认值 —— 新增入口时必须显式选一个（架构设计 §3）。 */
enum class SearchScope {
    IPTV,
    VOD,
}

/**
 * IPTV 板块允许出现的搜索结果类型 —— **本项目的核心规则，单一事实来源**。
 *
 * 改动这张表等于改产品规则，必须：① 先改 RULES §3.1；② 同步 docs/功能清单.md；
 * ③ 在 CHANGELOG 的「规则变更」小节单独列出（RULES §3.3）。
 *
 * 三处强制缺一不可：这里定义一次、[filterForScope] 在 Repository 出口过滤一次、
 * 单测断言「输出恰好等于六类」。**禁止**在 UI / ViewModel 里再写一份 `if (type == ...)`。
 */
val IPTV_ALLOWED_TYPES: Set<ContentType> =
    setOf(
        ContentType.LIVE_CHANNEL,
        ContentType.MOVIE,
        ContentType.ANIME,
        ContentType.TV_SERIES,
        ContentType.SPORTS,
        ContentType.VARIETY,
    )

/** 按发起板块给出允许的结果类型：IPTV 走白名单，VOD 不受限（RULES §3.4）。 */
fun allowedTypesFor(scope: SearchScope): Set<ContentType> =
    when (scope) {
        SearchScope.IPTV -> IPTV_ALLOWED_TYPES
        SearchScope.VOD -> ContentType.entries.toSet()
    }

/**
 * 结果过滤的唯一入口。放在 core:model 里是为了能被 Repository 与单测共用同一份实现，
 * 避免「Repository 一套、测试里再抄一套」导致测试永远绿而线上漏类型。
 */
fun <T> List<T>.filterForScope(scope: SearchScope, typeOf: (T) -> ContentType): List<T> =
    filter { typeOf(it) in allowedTypesFor(scope) }
