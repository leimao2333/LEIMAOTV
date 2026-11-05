package com.leimao.tv.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.theme.TvText
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
 * 5. **进度条只在知道分母时才画百分比**（见 [progress]）。直播没有「总共多久」，
 *    硬凑一个百分比就是编数据；那种情况退一步画扫光条 + 已等待秒数，
 *    同样能回答「它还在动吗」，但不假装知道进度。
 * 6. **只管起播，不管播放中途的卡顿**（2026-11-05 用户反馈后收敛）。
 *    这一层是一张 0xCC 的大黑卡片，压在画面正中 —— 黑屏时它是最好的东西，
 *    画面**已经在播**时它是最坏的东西：卡一秒弹一张黑卡，用户连刚才演到哪都看不见了。
 *    中途卡顿改用 [BufferingPill]（角落一行小字）。判据见 [rememberPlayedOnce]。
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
    /**
     * 起播进度 0f..1f。**只有知道总时长时才传**（影视点播：已缓冲到的位置 / 全片时长）；
     * 直播没有分母，传 null —— 那时画扫光条，不画百分比。
     */
    progress: Float? = null,
) {
    if (!visible) return

    // 已等待秒数：直播那种「不知道自己排到哪」的场景里，唯一能持续变化的真实数字。
    // 它随浮层的组合生命周期走 —— 浮层一消失就从头开始，不会把两次加载累加在一起
    val waitedSeconds = rememberWaitSeconds()

    // 0% 一律当作「还不知道」。填一条空的轨道 + 写「已加载 0%」，读起来就是卡死，
    // 而真相往往只是「刚发出请求，第一个字节还没回来」—— 那时该画扫光条
    val shown = progress?.takeIf { it > 0f }

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
        LoadingBar(progress = shown, color = MaterialTheme.colorScheme.primary)
        LoadingProgressText(progress = shown, waitedSeconds = waitedSeconds)
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
 * 浮层的进度条。
 *
 * 两种情况分开画：
 * - 有 [progress]：实心条按比例填，用户能直接看出「还差多少」；
 * - 没有：一段 28% 宽的亮条从左扫到右，循环。它不承诺任何比例，
 *   只承诺「还在动」—— 这与转圈是一个意思，但配合下面那行秒数更让人安心。
 */
@Composable
private fun LoadingBar(
    progress: Float?,
    color: Color,
) {
    val track = Color.White.copy(alpha = 0.16f)
    BoxWithConstraints(
        modifier =
            Modifier
                .width(BAR_WIDTH)
                .height(4.dp)
                .clip(CircleShape)
                .background(track),
    ) {
        val barWidth = maxWidth
        if (progress != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        // 给下限：0% 时也要留出一点，否则看起来像条灰色空槽，
                        // 用户会以为进度条本身坏了
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(color),
            )
        } else {
            val transition = rememberInfiniteTransition(label = "buffering-sweep")
            val shift by
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 1150, easing = LinearEasing)),
                    label = "buffering-shift",
                )
            val segmentWidth = barWidth * SWEEP_FRACTION
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset(x = ((barWidth - segmentWidth) * shift).roundToPx(), y = 0) }
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .background(color),
            )
        }
    }
}

/**
 * 进度条下面那行：知道进度就报百分比，不知道就报「已等待 N 秒」。
 *
 * 两个都显示会挤成两条互不相干的信息；而它们回答的是同一个问题（「还要等多久」），
 * 只是精度不同，所以二选一。
 */
@Composable
private fun LoadingProgressText(
    progress: Float?,
    waitedSeconds: Int,
) {
    val text =
        if (progress != null) {
            "已加载 ${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%"
        } else {
            formatWait(waitedSeconds)
        }
    Text(
        text = text,
        style = TvText.current.hint,
        color = Color.White.copy(alpha = 0.9f),
        textAlign = TextAlign.Center,
    )
}

/**
 * 已等待时长。60 秒以内报秒（「3 秒」和「31 秒」是两种心情），超过就折算成分，
 * 免得出现「已等待 187 秒」这种要用户自己换算的写法。
 */
private fun formatWait(seconds: Int): String =
    if (seconds < 60) {
        "已等待 $seconds 秒"
    } else {
        "已等待 ${seconds / 60} 分 ${(seconds % 60).toString().padStart(2, '0')} 秒"
    }

/** 从浮层出现开始每秒 +1。浮层不组合时协程随之取消，下次打开从 0 起算 */
@Composable
private fun rememberWaitSeconds(): Int {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds++
        }
    }
    return seconds
}

/** 进度条宽度：比标题窄一点，让视觉重心留在文字上 */
private val BAR_WIDTH = 220.dp

/** 扫光条占轨道的比例。太宽像进度、太窄像故障灯，28% 刚好读作「一段在跑」 */
private const val SWEEP_FRACTION = 0.28f

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
 * 播放中途卡顿时的轻量提示：角落一行「⟳ 缓冲中 · 已等待 2 秒」。
 *
 * ## 为什么不能复用 [BufferingOverlay]
 *
 * 卡顿与黑屏是两种处境。黑屏时用户没得看，把「在等什么、下了多少」摆到画面正中
 * 是对的；可画面**已经在播**时，同一张卡片会盖住整块画面 —— 2026-11-05 用户截图：
 * 背景画面清清楚楚，正中却压着一张「正在缓冲…已加载 63%」的黑卡，
 * 每次网络抖一下就来一次。那几百毫秒里用户既看不见剧情，也看不见刚才演到哪。
 *
 * 所以这里只保留「它还在动」这一个信息：小转圈 + 一句读得清的话，
 * 贴在画面**顶缘**（贴底会撞上控制条），**不压暗、不遮挡中心**。数据行（字节 / 分辨率）
 * 留给了起播浮层 —— 中途卡顿时它是排查信息，不是用户此刻需要的东西。
 *
 * 不接焦点：遥控器的一切操作照常落到播放页。
 */
@Composable
fun BufferingPill(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val waitedSeconds = rememberWaitSeconds()

    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(PILL_SCRIM)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LoadingSpinner(color = MaterialTheme.colorScheme.primary, size = 18.dp)
        Text(
            text = "缓冲中 · 已等待 $waitedSeconds 秒",
            style = TvText.current.hint,
            color = Color.White,
        )
    }
}

/**
 * 缓冲期间给用户看什么。见 [resolveBufferingPresentation]。
 *
 * 为什么要一个枚举而不是两个 bool：两者互斥（要么大卡片要么小角标要么什么都不显示），
 * 两个 bool 能组合出「同时显示」这种画不出来的状态，而调用方还得自己保证互斥。
 */
enum class BufferingPresentation {
    /** 什么都不显示（没在加载） */
    None,

    /** 起播浮层：画面正中一张卡片，带标题、进度与加载数据 */
    Full,

    /** 中途卡顿：画面顶缘一行小字，不遮挡内容 */
    Pill,
}

/**
 * 决定缓冲时该给什么样的提示。
 *
 * 判据只有一条：**画面出过没有**。
 * - 没出过（起播 / 换台 / 换集 / 换线）→ [BufferingPresentation.Full]：
 *   这时用户看的是黑屏，把「在等什么、下了多少」摆到正中是帮他；
 * - 出过之后再卡 → [BufferingPresentation.Pill]：这时画面里是有内容的，
 *   一张 0xCC 的大黑卡片会把它整块盖掉（2026-11-05 用户截图反馈的场景）。
 *
 * [preparing] 单独判一次而不是只看 [playedOnce]：换台 / 换集会回到准备态，
 * 但 `playedOnce` 是按会话记的，某些路径下（同一集重试）它还留着 true ——
 * 那时应该回到完整浮层，而不是给一个「看起来只是卡了一下」的小角标。
 */
fun resolveBufferingPresentation(
    showLoading: Boolean,
    preparing: Boolean,
    playedOnce: Boolean,
): BufferingPresentation =
    when {
        !showLoading -> BufferingPresentation.None
        preparing || !playedOnce -> BufferingPresentation.Full
        else -> BufferingPresentation.Pill
    }

/**
 * 本次播放会话里，画面**是不是真的出过**。
 *
 * 这是「该弹大浮层还是小角标」的判据 —— 起播（一次都没播起来）需要信息，
 * 中途卡顿不需要遮挡。用「播放器进入 PLAYING 且不在 buffering」来认定出过画面：
 * 比按 `positionMs > 0` 可靠（有些源会先给一个非零起始时间），
 * 也不受「缓冲中但已有画面」这种中间态干扰。
 *
 * [sessionKey] 一变就归零：换台 / 换集 / 换线路都是一次新的起播，
 * 那时又该退回大浮层（哪怕上一集刚播完）。
 */
@Composable
fun rememberPlayedOnce(
    sessionKey: Any?,
    playing: Boolean,
    buffering: Boolean,
): Boolean {
    var played by remember(sessionKey) { mutableStateOf(false) }
    // 组合期写状态会让当前帧的重组结果依赖自己，用 SideEffect 推到帧末
    SideEffect {
        if (playing && !buffering) played = true
    }
    return played
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
 *
 * **调用方不要再用 `if (...) { BufferingOverlay(...) }` 把它整个包起来**：
 * 那样 `loading` 变 false 的瞬间组合就被撤掉，下面这段 `keepMs` 的等待根本没机会跑，
 * 消失延迟等于没写（0.5.6.0 之前一直是这个状态）。直接把可见性交给
 * [BufferingOverlay] / [BufferingPill] 的 `visible` 参数，它们内部会自行 return。
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

/** 起播浮层的出现延迟：短于这个时长的缓冲，用户根本来不及看见浮层，闪一下反而分心 */
private const val SHOW_DELAY_MS = 400L

/**
 * 中途卡顿角标的出现延迟。
 *
 * 比起播那条长得多，因为它面对的是另一种抖动：点播在**播放中**只要缓冲掉到
 * `bufferAfterRebufferMs` 以下就会进 BUFFERING，慢源上这种几百毫秒的停顿很常见，
 * 而画面本身还没停（手上还有几帧）。1.2 秒这条线以下的停顿用户根本不会察觉，
 * 让他看见一个「缓冲中」反而提醒了他「刚才卡了一下」。
 */
const val REBUFFER_DELAY_MS = 1_200L

/** 消失延迟：缓冲好后多留一会儿，避免「刚看清写的是什么就没了」 */
private const val KEEP_MS = 600L

/** 0xCC 的黑：足够把白字托起来，又能看见底下画面还在动 */
private val SCRIM = Color(0xCC000000)

/** 角标的底色比大浮层更淡：它只托一行小字，压太黑又会变成一块遮挡 */
private val PILL_SCRIM = Color(0xB3000000)
