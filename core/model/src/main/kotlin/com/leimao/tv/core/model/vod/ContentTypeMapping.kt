package com.leimao.tv.core.model.vod

import com.leimao.tv.core.model.ContentType

/**
 * 源侧分类名 → 本项目九类内容的**唯一映射表**（VOD-02）。
 *
 * 为什么必须只有一张表：SEARCH-02 的白名单是拿 [ContentType] 做差集起作用的 ——
 * 如果影视源解析时用一份映射、搜索过滤时又用另一份字符串判断，两边只要有一点不一致，
 * 白名单就会被绕过（比如这里把「微短剧」认成 [ContentType.TV_SERIES]，搜索按类型过滤就拦不住了）。
 * 所以映射一旦确定，全项目只认这一份结果。
 *
 * 匹配是**按顺序**的关键词包含，顺序本身就是规则：
 * 1. 「短剧」含「剧」，「纪录片」「预告片」「综艺片」「动画片」都含「片」——
 *    这些必须排在做泛匹配的「剧」「片」之前，否则会被后者的兜底分支抢走；
 * 2. 「动漫电影」按 [ContentType.ANIME] 处理：在这类源里它出现在动漫栏，
 *    用户在动漫分类里找它比在电影里找更符合习惯。
 */
fun contentTypeOfSourceName(raw: String?): ContentType? {
    val name = normalizeCategoryName(raw)
    if (name.isEmpty()) return null
    return when {
        name.containsAny(SHORT_PLAY_WORDS) -> ContentType.SHORT_PLAY
        name.containsAny(TRAILER_WORDS) -> ContentType.TRAILER
        name.containsAny(DOCUMENTARY_WORDS) -> ContentType.DOCUMENTARY
        name.containsAny(NEWS_WORDS) -> ContentType.NEWS
        name.containsAny(ANIME_WORDS) -> ContentType.ANIME
        name.containsAny(OPERA_WORDS) -> ContentType.OPERA
        name.containsAny(VARIETY_WORDS) -> ContentType.VARIETY
        name.containsAny(SPORTS_WORDS) -> ContentType.SPORTS
        name.containsAny(MOVIE_WORDS) -> ContentType.MOVIE
        name.containsAny(SERIES_WORDS) -> ContentType.TV_SERIES
        else -> null
    }
}

/**
 * 归一化：去首尾空白、全角转半角（源里「电视剧」常混着全角字符）、去掉中间的空白与分隔符。
 *
 * 不做小写化 —— 中文没有大小写，而分类名里出现的英文（如 `4K`）大小写不影响关键词命中。
 */
internal fun normalizeCategoryName(raw: String?): String {
    if (raw == null) return ""
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        val half =
            when {
                ch == '\u3000' -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
        if (!half.isWhitespace()) sb.append(half)
    }
    return sb.toString()
}

private fun String.containsAny(words: List<String>): Boolean = words.any { contains(it) }

// 顺序敏感的判定在 [contentTypeOfSourceName] 的 when 里，这里只维护各类型的词根。
// 加词根的判断标准：它是这类内容的**专有词**，不会出现在别的类型的常见分类名里。

private val SHORT_PLAY_WORDS = listOf("短剧", "微剧", "竖屏剧")

/** 「片花」「预告」都指向宣传物料，不属于正片 */
private val TRAILER_WORDS = listOf("预告", "花絮", "片花")

private val DOCUMENTARY_WORDS = listOf("纪录")

private val NEWS_WORDS = listOf("资讯", "新闻", "快讯")

private val ANIME_WORDS = listOf("动漫", "动画", "番剧", "国漫", "日漫", "欧美漫")

private val VARIETY_WORDS = listOf("综艺", "真人秀", "脱口秀", "晚会")

/** 戏曲 / 曲艺：传统舞台艺术。排在综艺之前 —— 「戏曲」不能落进综艺的兜底 */
private val OPERA_WORDS = listOf("戏曲", "曲艺", "京剧", "越剧", "黄梅戏", "豫剧", "相声", "评书")

private val SPORTS_WORDS = listOf("体育", "赛事", "足球", "篮球", "网球", "奥运", "NBA", "CBA")

/**
 * 电影：只放「专有词」与「片」兜底。
 *
 * 之所以敢用「片」兜底：「纪录片」「预告片」「综艺片」「动画片」在前面的分支已被拦走，
 * 剩下含「片」的（动作片 / 喜剧片 / 伦理片 / 剧情片…）在这类源里都是电影分类。
 */
private val MOVIE_WORDS = listOf("电影", "影院", "院线", "片")

/** 电视剧的兜底：前面所有具体类型都排除后，含「剧」的就是剧集 */
private val SERIES_WORDS = listOf("剧")
