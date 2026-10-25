package com.leimao.tv.core.model.catchup

import com.leimao.tv.core.model.epg.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 回看（catchup）能力（LIVE-24）。
 *
 * 只描述「怎么把过去某一档拼成一个可播地址」，不描述「哪些档可以回看」——
 * 后者由节目单或时间窗口决定，属于调用方的事。
 *
 * 设计上的硬约束：**拼不出地址就不该有这个对象**。功能清单写的是「不支持时不显示，
 * 而不是点了报错」，所以「源声明了 catchup 但我们不认识它的拼法」这种情况，
 * 在解析阶段就要落成 null，让入口根本不出现。
 */
data class Catchup(
    val mode: CatchupMode,
    /** [CatchupMode.TEMPLATE] 用的地址模板，形如 `...?start=${start}&end=${end}` */
    val template: String? = null,
    /** 这个源能回看几天（`catchup-days` / `timeshift`），用于限定入口里的可选范围 */
    val days: Int = DEFAULT_DAYS,
) {
    /** 能力本身是否可用于拼地址 —— 模板模式必须有模板，其余模式靠基地址 */
    val isUsable: Boolean
        get() = mode != CatchupMode.TEMPLATE || !template.isNullOrBlank()

    companion object {
        /** 源没写天数时的默认值：三天覆盖「错过两晚」这个最常见的场景 */
        const val DEFAULT_DAYS: Int = 3

        /** 上限七天：再长的回看窗口在电视上翻起来只会让人放弃 */
        const val MAX_DAYS: Int = 7

        fun daysOr(raw: String?): Int =
            raw?.trim()?.toIntOrNull()?.coerceIn(1, MAX_DAYS) ?: DEFAULT_DAYS
    }
}

/**
 * 回看地址的拼法。
 *
 * - [TEMPLATE]：`catchup-source` 给了带占位符的模板，这是中文源里最常见的一种；
 * - [FLUSSONIC]：Flussonic 服务端，`?start=<epoch 秒>&end=<epoch 秒>`；
 * - [XTREAM]：Xtream Codes 风格，`?start=yyyy-MM-dd:HH-mm&end=...`。
 */
enum class CatchupMode {
    TEMPLATE,
    FLUSSONIC,
    XTREAM,
}

/**
 * 回看面板里的一行。
 *
 * [title] 为 null 表示「没有节目单，只有时间段」——这时界面只显示起止时刻，
 * 不要编一个「节目 1」出来（那比空着更让人怀疑）。
 */
data class CatchupEntry(
    val title: String?,
    val startAt: Long,
    val endAt: Long,
)

/**
 * 按源声明的拼法，把 [baseUrl] 与一段时间拼成回看地址。
 *
 * 时间一律按 **UTC** 格式化：模板里的 `${start}` 在业界约定就是 UTC，
 * 按本地时区拼会让东八区的用户拿到早八个小时的节目。
 *
 * 返回 null 表示「这段拼不出来」：模板里一个认识的占位符都没有、时间区间非法，
 * 或者拼完还留着没替换掉的占位符。宁可让调用方退化成「不可回看」，
 * 也不要送一个带 `${` 的地址给播放内核。
 */
fun buildCatchupUrl(
    baseUrl: String,
    catchup: Catchup,
    startAt: Long,
    endAt: Long,
): String? {
    if (baseUrl.isBlank() || endAt <= startAt) return null
    val durationMinutes = ((endAt - startAt) / MINUTE_MILLIS).toInt().coerceAtLeast(1)

    return when (catchup.mode) {
        CatchupMode.TEMPLATE -> fillTemplate(catchup.template, startAt, endAt, durationMinutes)

        CatchupMode.FLUSSONIC ->
            baseUrl.withQuery("start=${startAt / 1000}&end=${endAt / 1000}")

        CatchupMode.XTREAM ->
            baseUrl.withQuery(
                "start=${utcFormat(startAt, XTREAM_PATTERN)}&end=${utcFormat(endAt, XTREAM_PATTERN)}",
            )
    }
}

/**
 * 从节目单里挑出「已经播完、还在回看窗口内」的节目，按时间**倒序**（最近的在前）。
 *
 * 正在播的那一档不进列表：它就在直播里，列出来只会让用户以为「回看比直播慢一档」。
 */
fun catchupEntriesFrom(
    programmes: List<EpgProgramme>,
    nowMillis: Long,
    days: Int,
    limit: Int = CATCHUP_ENTRY_LIMIT,
): List<CatchupEntry> {
    val earliest = nowMillis - days.coerceIn(1, Catchup.MAX_DAYS) * DAY_MILLIS
    return programmes
        .asSequence()
        .filter { it.endAt <= nowMillis && it.endAt > earliest }
        .sortedByDescending { it.startAt }
        .take(limit)
        .map { CatchupEntry(title = it.title, startAt = it.startAt, endAt = it.endAt) }
        .toList()
}

/**
 * 没有节目单时的兜底：按整点切出最近若干个一小时档。
 *
 * 为什么要有这个兜底：很多聚合源声明了 catchup 却不提供节目单。只给节目单的话，
 * 这类源上「回看」永远是个空面板 —— 用户会认为按钮坏了。按时间段回看本来就成立
 * （回看地址只需要起止时刻），只是没法告诉你那一档叫什么。
 *
 * 从「上一个整点」往回数：正在走的这个小时还没结束，回看它等于看直播。
 */
fun catchupSlots(
    nowMillis: Long,
    days: Int,
    limit: Int = CATCHUP_ENTRY_LIMIT,
): List<CatchupEntry> {
    val hourStart = nowMillis - nowMillis % HOUR_MILLIS
    val maxSlots = (days.coerceIn(1, Catchup.MAX_DAYS) * 24).coerceAtMost(limit)
    return (0 until maxSlots).map { index ->
        val end = hourStart - index * HOUR_MILLIS
        CatchupEntry(title = null, startAt = end - HOUR_MILLIS, endAt = end)
    }
}

private fun fillTemplate(
    template: String?,
    startAt: Long,
    endAt: Long,
    durationMinutes: Int,
): String? {
    val source = template?.takeIf { it.isNotBlank() } ?: return null

    val values =
        mapOf(
            "\${start}" to utcFormat(startAt, COMPACT_PATTERN),
            "\${end}" to utcFormat(endAt, COMPACT_PATTERN),
            "\${utc}" to (startAt / 1000).toString(),
            // `${lutc}` 在模板里表示「本地时间的 unix 秒」。epoch 秒本身不带时区，
            // 与 `${utc}` 同值 —— 真正的差别在服务端怎么解读，客户端无从影响
            "\${lutc}" to (startAt / 1000).toString(),
            "\${duration}" to durationMinutes.toString(),
        )

    var result = source
    var replaced = false
    values.forEach { (token, value) ->
        if (result.contains(token)) {
            result = result.replace(token, value)
            replaced = true
        }
    }

    // 还剩 `${` 说明模板里有我们不认识的占位符（如 `${offset}`），拼出来是个坏地址
    if (!replaced || result.contains("\${")) return null
    return result
}

private fun utcFormat(millis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))

private fun String.withQuery(query: String): String =
    if (contains('?')) {
        if (endsWith('?') || endsWith('&')) "$this$query" else "$this&$query"
    } else {
        "$this?$query"
    }

/** 回看面板最多列多少档：再多就得上下翻半天，翻到的那一档还不如去搜索 */
const val CATCHUP_ENTRY_LIMIT: Int = 24

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 86_400_000L

/** `yyyyMMddHHmmss`：模板占位符 `${start}` / `${end}` 的约定格式 */
private const val COMPACT_PATTERN = "yyyyMMddHHmmss"

/** Xtream Codes 的时间写法：`2026-10-25:20-00` */
private const val XTREAM_PATTERN = "yyyy-MM-dd:HH-mm"
