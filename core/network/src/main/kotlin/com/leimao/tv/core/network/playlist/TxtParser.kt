package com.leimao.tv.core.network.playlist

import com.leimao.tv.core.model.playlist.ParsedChannel
import com.leimao.tv.core.model.playlist.ParsedPlaylist
import com.leimao.tv.core.model.playlist.PlaylistFormat
import com.leimao.tv.core.model.playlist.UNGROUPED

/**
 * TXT 源解析（LIVE-02）。
 *
 * 格式极简：`名称,地址`，分组用一行 `分组名,#genre#` 切换。正因为简单，
 * 各家用得都很随意 —— 中英文逗号混用、行尾多余空格、注释行、空分组。这里逐条兜住，
 * 兜不住的按「跳过行」计数交给导入报告，而不是静默丢弃或整份失败。
 */
internal object TxtParser {
    fun parse(text: String): ParsedPlaylist {
        val channels = mutableListOf<ParsedChannel>()
        var skipped = 0
        var group = UNGROUPED

        text.stripBom().lineSequence().forEach { raw ->
            val line = raw.trim()

            when {
                line.isEmpty() -> Unit

                line.endsWith(GENRE_MARK) -> {
                    // `央视频道,#genre#` → 之后的频道都归「央视频道」；空分组名回落到未分组
                    val next =
                        line.removeSuffix(GENRE_MARK)
                            .trim()
                            .trimEnd(',', '，')
                            .trim()
                    group = next.ifBlank { UNGROUPED }
                }

                // 注释行（`#` 开头的都不是频道）
                line.startsWith("#") -> Unit

                line.looksTooLong() -> skipped++

                else -> {
                    val separator = line.indexOfFirstSeparator()
                    // 没有分隔符（或第一个字符就是分隔符）→ 拿不到「名称 + 地址」这一对
                    if (separator <= 0) {
                        skipped++
                    } else {
                        val name = line.substring(0, separator).trim()
                        val url = line.substring(separator + 1).trim()
                        if (name.isEmpty() || url.isEmpty()) {
                            skipped++
                        } else {
                            channels += ParsedChannel(name = name, groupName = group, url = url)
                        }
                    }
                }
            }
        }

        return ParsedPlaylist(channels = channels, skippedLines = skipped, format = PlaylistFormat.TXT)
    }

    private const val GENRE_MARK = "#genre#"
}
