package com.leimao.tv.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.theme.TvText
import kotlinx.coroutines.delay

/**
 * 起播 / 缓冲浮层（PLAY-12）。
 *
 * ## 为什么必须有
 *
 * 换台或点进一集之后，画面可能黑上好几秒。**一块没有任何信息的黑屏，用户无法判断
 * 是在加载还是已经挂了** —— 2026-10-26 真机实测：某路源 READY/BUFFERING 每 200ms 抖一次，
 * 屏幕黑着不动，只能靠猜。有了这一层，用户至少知道「它在等数据」还是「一个字节都没来」。
 *
 * ## 几个刻意的取舍
 *
 * 1. **不铺满全屏、不压暗画面**。它盖在视频上，但只占中间一小块：缓冲好之后画面直接露出来，
 *    不需要「先黑一下再亮」。全屏遮罩会让每次轻微抖动都闪一下，比黑屏更难受。
 * 2. **底是半透明黑**。白字直接压在亮画面上会糊掉（雪景、公告页），垫一层 0xCC000000
 *    才能保证任意画面上都读得清。
 * 3. **数据行可以为空**。libVLC 内核给不出字节数，这时只显示标题；
 *    编一个假的「0 KB/s」比不显示更误导。
 * 4. **不接焦点**。它只是状态显示，遥控器的一切操作都该照常落到播放页上。
 *
 * [detail] 由调用方从 `LoadStats.summary()` 取 —— 格式化逻辑放在 core:player，
 * 这样直播页和影视页显示的是同一句话，不会各写一套单位。
 */
@Composable
fun BufferingOverlay(
    visible: Boolean,
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(SCRIM)
                .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LoadingSpinner(color = MaterialTheme.colorScheme.primary)
        Text(
            text = title,
            style = TvText.current.title,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = TvText.current.hint,
                color = Color.White.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 自绘转圈。
 *
 * 不用 `CircularProgressIndicator`：**tv-material 1.0.0 里没有这个组件**（编译期就没有），
 * 而为了一个转圈把 compose-material3 也引进来，等于为了一个动效多背一套色板与组件。
 * 一圈轨道 + 一段 90° 的弧旋转，够表达「在转」了。
 */
@Composable
private fun LoadingSpinner(
    color: Color,
    size: Dp = 34.dp,
) {
    val transition = rememberInfiniteTransition(label = "buffering-spin")
    val angle by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
            label = "buffering-angle",
        )

    Canvas(modifier = Modifier.size(size)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = Color.White.copy(alpha = 0.16f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * 给「要不要显示加载浮层」加一层防抖。
 *
 * 真机上弱源会一秒内 READY↔BUFFERING 抖好几次（2026-10-26 实测），直接用 `buffering`
 * 驱动可见性，浮层就会疯狂闪 —— 比没有浮层更烦。
 *
 * 规则：**出现要等 [showDelayMs]，消失也要等 [keepMs]**。
 * 两个方向的等待都会被下一次状态翻转打断（`LaunchedEffect` 的 key 变了就取消），
 * 所以短暂的真缓冲不会被吞掉，短暂的好转也不会让浮层闪一下又回来。
 */
@Composable
fun rememberBufferingVisible(
    loading: Boolean,
    showDelayMs: Long = SHOW_DELAY_MS,
    keepMs: Long = KEEP_MS,
): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(showDelayMs)
            visible = true
        } else {
            delay(keepMs)
            visible = false
        }
    }
    return visible
}

/** 出现延迟：短于这个时长的缓冲，用户根本来不及看见浮层，闪一下反而分心 */
private const val SHOW_DELAY_MS = 400L

/** 消失延迟：缓冲好后多留一会儿，避免「刚看清写的是什么就没了」 */
private const val KEEP_MS = 600L

/** 0xCC 的黑：足够把白字托起来，又能看见底下画面还在动 */
private val SCRIM = Color(0xCC000000)
