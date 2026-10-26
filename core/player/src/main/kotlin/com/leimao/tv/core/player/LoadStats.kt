package com.leimao.tv.core.player

/**
 * 起播 / 缓冲期间「正在发生什么」（PLAY-12）。
 *
 * ## 为什么需要它
 *
 * 换台或点进一集之后，画面可能黑上好几秒 —— 期间界面上什么都没有。用户无法区分三种情况：
 * ①正在连（还没收到数据）；②收到了但在缓冲（源太慢）；③内核已经挂了但 UI 没反应。
 * 真机上这三种都表现为「一块黑屏」，只能靠猜。这组数字就是把它们分开的东西。
 *
 * ## 每个字段解决哪个问题
 *
 * - [bytesLoaded] **为 0** → 数据根本没来（网络 / 地址 / 被墙）。等下去没有意义，该换线路；
 * - 有字节但 [bufferedMs] 不涨、[bytesPerSecond] 很低 → 源在慢慢吐，属于「等」还是「换」的判断；
 * - [width]/[height] 有值说明**解码链路已经通了**，此时黑屏是渲染/画面问题，不是加载问题；
 * - [decoder] 顺带回答「硬解起来了没有」（与 [PlaybackDiagnostics] 的日志同源）。
 *
 * 全部字段都有「未知」的合法取值（0 / null）：libVLC 内核拿不到字节数，
 * UI 必须能在「只有一部分数据」时照常显示，而不是因为缺字段就整块不显示。
 */
data class LoadStats(
    /** 播放器处于 BUFFERING。注意它与「黑屏」不是一回事：起播瞬间也会 buffering */
    val buffering: Boolean = false,
    /** 已缓冲到的位置（相对当前位置的时长，毫秒） */
    val bufferedMs: Long = 0L,
    /** 本次起播累计收到的字节数。**0 是最有价值的信号**：说明一个字节都没来 */
    val bytesLoaded: Long = 0L,
    /** 最近一段时间的平均下载速率（字节/秒），由调用方按窗口算 */
    val bytesPerSecond: Long = 0L,
    /** 视频宽高；还没解析到流信息时为 0 */
    val width: Int = 0,
    val height: Int = 0,
    /** 解码器名。软解/硬解的判据见 [PlaybackDiagnostics.isSoftware] */
    val decoder: String? = null,
) {
    /** 「1920×1080」；没解析到流信息时为 null（UI 不显示这一段） */
    val resolution: String?
        get() = if (width > 0 && height > 0) "$width×$height" else null

    /**
     * 给用户看的一行数据。
     *
     * 顺序按「排查优先级」而不是「好看」：先看有没有收到数据，再看快不快，最后才是分辨率。
     * 最多三段 —— 电视上一行超过三段就变成一串看不清的字符。
     */
    fun summary(): String {
        // 一个字节都没有：这是唯一需要用户**动手**的状态，必须说清楚
        if (bytesLoaded <= 0L) return "还没有收到数据"
        val parts =
            buildList {
                add("已加载 ${formatBytes(bytesLoaded)}")
                if (bytesPerSecond > 0L) add(formatSpeed(bytesPerSecond))
                if (bufferedMs > 0L) add("已缓冲 ${formatSeconds(bufferedMs)}")
                resolution?.let { add(it) }
            }
        return parts.take(3).joinToString(" · ")
    }

    companion object {
        /** 什么都没有：stub 内核与「还没起播」时的取值 */
        val None = LoadStats()
    }
}

/** 字节数 → 人话。[base] 用 1024（电视上没人用 1000 进制算流量） */
internal fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        else -> "${oneDecimal(bytes / (1024.0 * 1024.0))} MB"
    }

/** 速率：与 [formatBytes] 同一套单位，后面加 /s */
internal fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

/** 缓冲时长：小于 10 秒保留一位小数（0.3 秒和 3 秒是两种心情），再长就取整 */
internal fun formatSeconds(ms: Long): String =
    if (ms < 10_000L) "${oneDecimal(ms / 1000.0)} 秒" else "${ms / 1000} 秒"

/** 一位小数，且去掉 ".0"：`1.0 MB` 读起来比 `1 MB` 啰嗦 */
private fun oneDecimal(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
}
