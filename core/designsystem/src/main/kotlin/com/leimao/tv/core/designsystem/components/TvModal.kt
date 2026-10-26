package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/**
 * 树内弹层（SET-12）。
 *
 * **为什么不用 `Dialog`**：Dialog 是独立窗口，窗口拿到焦点的过程会**吃掉打开后的第一次按键** ——
 * 用户按 OK 没反应，得再按一次才进输入框。这是平台行为，在 Compose 里改不掉。
 * 树内弹层没有自己的窗口，按键直接进当前窗口的焦点树，第一次就有效。
 *
 * 代价是**焦点不会自动被锁住**：树内节点没有「窗口」边界，方向键能走到后面的内容上。
 * 所以调用方必须做到两件事，缺一不可：
 *
 * 1. **弹层打开时不组合底层内容**（见 `SourceManagerScreen` 的 `modalOpen`）——
 *    锁焦点最可靠的办法就是「后面根本没有可聚焦的东西」；
 * 2. **自己处理返回键**（BACK 先关弹层，再退页面）。
 *
 * 只提供「遮罩 + 卡片」这层壳，标题与正文交给调用方：三个弹层的字段差别很大，
 * 强行抽象成一个带标题/说明/按钮的模板，最后只会多出一堆可选参数。
 */
@Composable
fun TvModal(
    modifier: Modifier = Modifier,
    width: Dp = 720.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                // 0.72 的遮罩：足够把底层压下去，又不至于让人以为「切到别的页面了」
                .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(width)
                    // 高度给上限：电视可用高度差别很大，内容撑破时 Compose 会**直接裁掉**
                    // 而不是自动滚动 —— 表现就是「按钮不见了，看起来像没做这个按钮」
                    .fillMaxHeight(0.92f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
            content = content,
        )
    }
}
