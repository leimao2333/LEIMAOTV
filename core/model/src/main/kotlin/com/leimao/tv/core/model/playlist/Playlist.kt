package com.leimao.tv.core.model.playlist

import com.leimao.tv.core.model.catchup.Catchup

/**
 * 候选分组名。没有 `group-title` / 没有 `#genre#` 的频道统一落到这里（LIVE-01）。
 *
 * 为什么用常量而不是各处写字符串：分组名会出现在「按分组切列表」的比对里，
 * 一处写成「未分组」另一处写成「其它」，列表就会多出一个只有一条内容的分组。
 */
const val UNGROUPED: String = "未分组"

/** 源文件格式。解析器与导入报告都要说清「这份文件被当成什么解析的」。 */
enum class PlaylistFormat {
    M3U,
    TXT,
}

/**
 * 解析出来的单条频道 —— **还没有 id，也没有入库**。
 *
 * 与 `:core:database` 的 `ChannelEntity` 分开的原因：解析器不该知道数据库长什么样，
 * 入库时再决定 id / 频道号 / 线路归属；这样解析器可以在 JVM 上用字符串直接测。
 */
data class ParsedChannel(
    val name: String,
    val groupName: String,
    val url: String,
    val tvgId: String? = null,
    val logoUrl: String? = null,
    /** 源文件里显式写的频道号（`tvg-chno` / TXT 的 `序号`），没有就为 null，入库时再补 */
    val numberInPlaylist: Int? = null,
    /**
     * 这个台声明的回看能力（LIVE-24）。null 表示**不支持回看**，或声明了但我们不会拼它的地址 ——
     * 两种情况下播放页都不出现「回看」入口。
     */
    val catchup: Catchup? = null,
)

/**
 * 一次解析的结果。
 *
 * [skippedLines] 不是「错误数」，而是「看起来像频道但没能成为频道」的行数：
 * 导入报告里显示它，用户才能判断是文件本来如此，还是自己的源被截断了。
 */
data class ParsedPlaylist(
    val channels: List<ParsedChannel>,
    val skippedLines: Int,
    val format: PlaylistFormat,
) {
    val isEmpty: Boolean get() = channels.isEmpty()
}
