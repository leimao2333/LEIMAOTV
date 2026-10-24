package com.leimao.tv.core.model.search

/**
 * 搜索历史（SEARCH-07）。
 *
 * 只存**最近 10 条**：历史的作用是「刚才那几个词再点一次」，不是「我的搜索记录」——
 * 电视上是方向键一条条走，20 条之后没人会按到，反而把最常用的挤到看不见的地方。
 *
 * 三条纯函数（编码 / 解码 / 入栈）放在 core:model 而不是 DataStore 那一层：
 * 它们是规则（去重口径、上限、空白词不记），规则要有单测，而 DataStore 的读写不好测。
 */
const val SEARCH_HISTORY_LIMIT = 10

/**
 * 一条关键词入栈：去重（按 [normalizeSearchQuery] 的口径）、新的在前、截到上限。
 *
 * 去重**不按原文字面**：「CCTV 1」与「cctv1」搜出来是同一批台，留两条等于占两个坑。
 * 但存的是用户**原样的写法**（这里保留 `trim` 后的原文），历史要看起来像自己敲的。
 * 空白词不记：那多半是清空输入框时顺手提交的。
 */
fun pushSearchHistory(
    history: List<String>,
    query: String,
): List<String> {
    val text = query.trim()
    val key = normalizeSearchQuery(text)
    if (key.isEmpty()) return history.take(SEARCH_HISTORY_LIMIT)
    val rest = history.filter { normalizeSearchQuery(it) != key }
    return (listOf(text) + rest).take(SEARCH_HISTORY_LIMIT)
}

fun removeSearchHistory(
    history: List<String>,
    query: String,
): List<String> {
    val key = normalizeSearchQuery(query)
    return history.filter { normalizeSearchQuery(it) != key }
}

/**
 * 编码成一行存 DataStore。
 *
 * 为什么用换行分隔而不是 JSON：搜索框是单行的，关键词里不可能出现换行 ——
 * 这等于「不需要转义的分隔符」，比引一个 JSON 库或处理转义便宜得多。
 */
fun encodeSearchHistory(history: List<String>): String =
    history
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(SEARCH_HISTORY_LIMIT)
        .joinToString(SEPARATOR)

/** 解码。存档被手工改坏（出现空行 / 超长）时只会少几条，不会抛异常影响启动 */
fun decodeSearchHistory(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split(SEPARATOR)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(SEARCH_HISTORY_LIMIT)
        .toList()
}

private const val SEPARATOR = "\n"
