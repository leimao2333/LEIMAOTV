package com.leimao.tv.core.network.playlist

import com.leimao.tv.core.model.playlist.ParsedChannel
import com.leimao.tv.core.model.playlist.ParsedPlaylist
import com.leimao.tv.core.model.playlist.PlaylistFormat
import com.leimao.tv.core.model.playlist.UNGROUPED

/**
 * M3U / M3U8 解析（LIVE-01）。
 *
 * 只解析**频道清单**，不解 HLS 媒体播放列表：用户给的是播放列表，不是分片文件。
 * 两者的结构有交集（都以 `#EXT` 开头），但目的完全不同，混在一起会让这里的字段判断变糊。
 *
 * 写法上刻意用「待定项」状态机而不是一次性正则：`#EXTINF` 与地址是两行，
 * 中间还可能插 `#EXTGRP` / `#EXTVLCOPT` / `#KODIPROP`，只有顺序扫描才说得清谁属于谁。
 */
internal object M3uParser {
    fun parse(text: String): ParsedPlaylist {
        val channels = mutableListOf<ParsedChannel>()
        var skipped = 0
        var pending: PendingChannel? = null

        text.stripBom().lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith(TAG_EXTINF, ignoreCase = true) -> {
                    // 上一条 `#EXTINF` 还没等到地址就又来一条：上一行作废并计数，
                    // 否则会把两行拼成一个不存在的频道
                    if (pending != null) skipped++
                    pending =
                        if (line.looksTooLong()) {
                            skipped++
                            null
                        } else {
                            parseExtInf(line)
                        }
                }

                line.startsWith(TAG_EXTGRP, ignoreCase = true) -> {
                    // `#EXTGRP` 是「接下来这条频道属于哪个分组」，只在有紧邻的 EXTINF 时有意义
                    pending?.let { current ->
                        current.groupName =
                            line.substringAfter(':').trim().ifBlank { UNGROUPED }
                    }
                }

                // `#EXTM3U` / `#EXTVLCOPT` / `#KODIPROP` / 普通注释：对频道清单没有信息量
                line.startsWith("#") -> Unit

                // 没有 EXTINF 兜着的裸地址：拿不到频道名，收进来只会变成「未命名频道」噪声
                pending == null -> skipped++

                else -> {
                    if (line.looksTooLong()) {
                        skipped++
                    } else {
                        channels += pending!!.toChannel(url = line)
                    }
                    pending = null
                }
            }
        }

        // 文件结束还挂着一条没有地址的 EXTINF
        if (pending != null) skipped++

        return ParsedPlaylist(channels = channels, skippedLines = skipped, format = PlaylistFormat.M3U)
    }

    private fun parseExtInf(line: String): PendingChannel {
        val body = line.substringAfter(':', "").trim()
        val comma = firstUnquotedComma(body)
        val attributes = parseAttributes(if (comma >= 0) body.substring(0, comma) else body)
        val title = if (comma >= 0) body.substring(comma + 1).trim() else ""

        // 名字的三级回退：逗号后的标题 → tvg-name → 占位名。
        // 「未命名频道」不丢弃这条，是因为它的地址可能仍然可播，用户能在列表里认出序号
        val name =
            title
                .ifBlank { attributes["tvg-name"].orEmpty() }
                .ifBlank { FALLBACK_NAME }

        return PendingChannel(
            name = name,
            groupName = attributes["group-title"].valueOr(UNGROUPED),
            tvgId = attributes["tvg-id"],
            logoUrl = attributes["tvg-logo"],
            numberInPlaylist = attributes["tvg-chno"]?.toIntOrNull(),
        )
    }

    /** 找第一个**不在引号里**的逗号：`group-title="新闻,财经"` 里的逗号不是标题分隔符。 */
    private fun firstUnquotedComma(body: String): Int {
        var inQuotes = false
        body.forEachIndexed { index, char ->
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> return index
            }
        }
        return -1
    }

    private fun parseAttributes(part: String): Map<String, String> =
        buildMap {
            ATTR_PATTERN.findAll(part).forEach { match ->
                val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
                put(match.groupValues[1].lowercase(), value)
            }
        }

    private fun String?.valueOr(fallback: String): String =
        this?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    private class PendingChannel(
        val name: String,
        var groupName: String,
        val tvgId: String?,
        val logoUrl: String?,
        val numberInPlaylist: Int?,
    ) {
        fun toChannel(url: String): ParsedChannel =
            ParsedChannel(
                name = name,
                groupName = groupName,
                url = url,
                tvgId = tvgId?.takeIf { it.isNotBlank() },
                logoUrl = logoUrl?.takeIf { it.isNotBlank() },
                numberInPlaylist = numberInPlaylist,
            )
    }

    private const val TAG_EXTINF = "#EXTINF"
    private const val TAG_EXTGRP = "#EXTGRP"
    private const val FALLBACK_NAME = "未命名频道"

    /**
     * 属性形如 `tvg-id="a" group-title='b' tvg-chno=12`。
     * 三种写法都见过（双引号 / 单引号 / 裸值），所以三个分支都要认。
     */
    private val ATTR_PATTERN =
        Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
}
