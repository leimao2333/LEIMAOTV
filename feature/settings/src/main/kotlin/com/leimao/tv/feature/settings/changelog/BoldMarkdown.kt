package com.leimao.tv.feature.settings.changelog

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private const val BOLD_MARKER = "**"

/**
 * 极简 `**加粗**` 渲染（SYS-03：应用内更新日志要支持加粗）。
 *
 * 为什么不用完整 Markdown 库：更新日志是我们自己写的纯文本，只需要一种行内样式。
 * 引一个解析器（含 HTML 渲染路径）会让 APK 多几百 KB，而收益为零 —— 体积也是硬指标（RULES §7）。
 *
 * 未闭合的 `**` 会原样输出，不吞字符：宁可显示得难看，也不能把用户看到的文案吃掉半句。
 */
fun renderBoldMarkdown(text: String): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val open = text.indexOf(BOLD_MARKER, cursor)
            if (open < 0) {
                append(text.substring(cursor))
                return@buildAnnotatedString
            }
            val close = text.indexOf(BOLD_MARKER, open + BOLD_MARKER.length)
            if (close < 0) {
                append(text.substring(cursor))
                return@buildAnnotatedString
            }
            append(text.substring(cursor, open))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(open + BOLD_MARKER.length, close))
            }
            cursor = close + BOLD_MARKER.length
        }
    }
