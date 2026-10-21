package com.leimao.tv.core.network.playlist

import com.leimao.tv.core.model.playlist.ParsedPlaylist
import com.leimao.tv.core.model.playlist.PlaylistFormat

/** 一个解析器只做一件事：文本进、频道出。不碰网络、不碰数据库，方便直接喂字符串测试。 */
fun interface PlaylistParser {
    fun parse(text: String): ParsedPlaylist
}

/**
 * 解析入口（LIVE-01 / LIVE-02）。
 *
 * 为什么不要求用户先选「这是 M3U 还是 TXT」：他常常只有一个地址，不知道内容长什么样。
 * 这里按内容嗅探 —— 先看有没有 `#EXTM3U` / `#EXTINF`，再退回按行判断 ——
 * 用户只管粘地址，格式判断算我们的。
 */
object Playlists {
    fun detect(text: String): PlaylistFormat? {
        val head = text.stripBom()
        if (head.isBlank()) return null

        // M3U 的标识是 `#EXTM3U`（首行）与 `#EXTINF`（每条频道）。两者任一出现即认定 M3U：
        // 有些源省略 `#EXTM3U` 直接给 `#EXTINF`，也有反过来的，只认一个会误判
        if (head.lineSequence().take(SNIFF_LINES).any { line ->
                val trimmed = line.trim()
                trimmed.startsWith("#EXTM3U", ignoreCase = true) ||
                    trimmed.startsWith("#EXTINF", ignoreCase = true)
            }
        ) {
            return PlaylistFormat.M3U
        }

        if (head.lineSequence().take(SNIFF_LINES).any { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && hasSeparator(trimmed)
            }
        ) {
            return PlaylistFormat.TXT
        }

        return null
    }

    /**
     * 按内容选择解析器。识别不了时返回空结果并标记为 [PlaylistFormat.TXT] ——
     * 调用方据此提示「没解析出频道」，而不是抛异常让导入流程崩在半路。
     */
    fun parse(text: String): ParsedPlaylist =
        when (detect(text)) {
            PlaylistFormat.M3U -> M3uParser.parse(text)
            PlaylistFormat.TXT -> TxtParser.parse(text)
            null -> ParsedPlaylist(channels = emptyList(), skippedLines = 0, format = PlaylistFormat.TXT)
        }

    private const val SNIFF_LINES = 40
}

/** 去掉 UTF-8 BOM：Windows 记事本另存的源文件几乎都带，留着会让首行 `#EXTM3U` 匹配不上。 */
internal fun String.stripBom(): String = removePrefix("\uFEFF")

/**
 * 单行长度上限。
 *
 * 不是「正常行有多长」，而是**防御**：有人会把整个播放列表塞成一行，或给出一个几十 MB 的
 * 畸形文件。超过这个长度的行按无效行处理并计数，避免正则和大字符串操作把内存吃穿。
 */
internal const val MAX_LINE_LENGTH = 8192

internal fun String.looksTooLong(): Boolean = length > MAX_LINE_LENGTH

/** TXT 源里中英文逗号都有人用，取最先出现的那个做分隔。 */
internal fun hasSeparator(line: String): Boolean = line.indexOfFirstSeparator() >= 0

internal fun String.indexOfFirstSeparator(): Int {
    val half = indexOf(',')
    val full = indexOf('，')
    return when {
        half < 0 -> full
        full < 0 -> half
        else -> minOf(half, full)
    }
}
