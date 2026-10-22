package com.leimao.tv.core.designsystem.focus

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import com.leimao.tv.core.designsystem.theme.LocalTvFocusVisuals

/**
 * 统一焦点视觉。**禁止**页面自绘描边（RULES §6.2 / 架构设计 §7）——
 * 各画各的结果就是有的页面描边 2dp、有的 4dp，用户一换页就觉得「这个 App 有点飘」。
 */
@Composable
fun Modifier.focusRing(focused: Boolean): Modifier {
    val visuals = LocalTvFocusVisuals.current
    val color = if (focused) MaterialTheme.colorScheme.primary else visuals.idleBorderColor
    return border(visuals.borderWidth, color, visuals.shape)
}

/**
 * 可聚焦卡片：电视端一切「可点的块」都该用它。
 *
 * 三件事它替调用方兜住：
 * ① 焦点态可见（描边 + 1.05 缩放，§6.2 第 1 条）；
 * ② 遥控器 OK 键等同于点击 —— `clickable` 会接管 DPAD_CENTER / Enter，
 *    这也是「功能必须能用遥控器完成」（§6.1）在组件层的落点；
 * ③ 动画时长统一 100~150ms（§6.3），不允许各页面自定义过渡。
 *
 * 还有一件容易被忘的事：**内容颜色由这里兜住**。卡片的底是深色，
 * 如果让里面的 [androidx.compose.material3.Text] 用默认内容色（黑色），
 * 就会出现「深底黑字」—— 在客厅距离下等于看不见。所以这里显式提供 onSurface，
 * 调用方再想覆盖（比如收藏态改成金色）仍可自己传 color。
 */
@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val visuals = LocalTvFocusVisuals.current
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (focused) visuals.focusedScale else 1f,
        animationSpec = tween(visuals.animationMillis),
        label = "tv-focus-scale",
    )

    // 调用方没传 FocusRequester 时给一个「自己能记住」的，避免 null 导致初始焦点无处可落
    val resolvedRequester = focusRequester ?: remember { FocusRequester() }

    // 未聚焦时用 surfaceVariant 而不是 surface：surface 跟页面底色几乎一个值，
    // 一整排按钮会糊成一片，看不出「这里有几个可点的东西」
    val backgroundColor =
        if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

    Box(
        modifier =
            modifier
                .focusRequester(resolvedRequester)
                // onFocusChanged 必须在 clickable 之前：它观察的是链上后出现的那个焦点目标
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(backgroundColor, visuals.shape)
                .focusRing(focused)
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}
