package com.leimao.tv.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 1080p 基准 + 全局字号缩放（RULES §6.3 / 架构设计 §7）。
 *
 * 下限不是拍的：电视观看距离 2~3m，正文 14sp 是「坐着能看清」的最低线，
 * 所以 [label] 卡在 14sp、[hint] 卡在 12sp。缩小字号的需求一律通过 [fontScale] 整体乘，
 * **禁止**在页面里单独调小某个 Text 的 fontSize —— 那样就没法保证任意页面都不低于底线。
 */
@Immutable
data class TvTextStyles(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val hint: TextStyle,
)

fun tvTextStyles(fontScale: Float = 1f): TvTextStyles {
    require(fontScale > 0f) { "fontScale 必须为正数：$fontScale" }
    return TvTextStyles(
        display =
            TextStyle(
                fontSize = (40 * fontScale).sp,
                lineHeight = (48 * fontScale).sp,
                fontWeight = FontWeight.SemiBold,
            ),
        title =
            TextStyle(
                fontSize = (24 * fontScale).sp,
                lineHeight = (32 * fontScale).sp,
                fontWeight = FontWeight.Medium,
            ),
        body =
            TextStyle(
                fontSize = (16 * fontScale).sp,
                lineHeight = (24 * fontScale).sp,
            ),
        label =
            TextStyle(
                fontSize = (16 * fontScale).sp,
                lineHeight = (22 * fontScale).sp,
            ),
        hint =
            TextStyle(
                fontSize = (14 * fontScale).sp,
                lineHeight = (20 * fontScale).sp,
            ),
    )
}
