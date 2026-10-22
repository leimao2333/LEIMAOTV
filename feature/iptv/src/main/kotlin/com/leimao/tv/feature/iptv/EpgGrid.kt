package com.leimao.tv.feature.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.epg.EpgBlock
import com.leimao.tv.core.model.epg.EpgTimeline
import com.leimao.tv.core.model.epg.SLOT_MINUTES
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一分钟的宽度。4dp/min → 一小时 240dp，六小时窗口约两屏半，滑两下就能看完一晚 */
private val MINUTE_WIDTH = 4.dp

private val RULER_HEIGHT = 26.dp
private val BLOCK_HEIGHT = 76.dp

/** 竖线每 30s 挪一次：分钟粒度的时间轴，秒级刷新换不来任何可见精度 */
private const val NOW_TICK_MS = 30_000L

/**
 * 时间轴节目单网格（LIVE-22）。
 *
 * 布局算法全在 [com.leimao.tv.core.model.epg.buildEpgTimeline] 里，这里只做三件事：
 * 把分钟乘成 dp、把焦点摆到当前节目上、把「现在」画成一条竖线。
 *
 * 为什么不用 `LazyRow`：一个六小时窗口里通常只有十几档节目，
 * 而 `LazyRow` 会让「竖线跟着内容一起滚」变得很难写 —— 竖线得知道自己该画在
 * 第几个 item 的什么位置。用一个 `horizontalScroll` 包住整条内容，
 * 竖线就只是一个按分钟偏移的普通 Box，跟着滚，永远对得上刻度。
 */
@Composable
fun EpgGridOverlay(
    channelName: String,
    timeline: EpgTimeline,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    // 竖线自己走时间：进来之后停在这一页看半小时，它就该挪半格
    var now by remember { mutableLongStateOf(nowMillis) }
    var selected by remember { mutableStateOf<EpgBlock?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_TICK_MS)
            now = System.currentTimeMillis()
        }
    }

    val clock = rememberClockFormat()

    // 选中一档就在底部露出它的简介；没选中时那里放按键提示（同一行，不额外占高度）
    val footerHint =
        selected?.let { block ->
            val description = block.programme.description
            if (description.isNullOrBlank()) block.programme.title else "${block.programme.title}　$description"
        } ?: "←→ 看前后节目　OK 看简介　返回 关闭"

    Column(
        modifier =
            modifier
                .fillMaxSize()
                // 完全不透明：这一页是「查节目」，不是「边看边查」。
                // 留一点透明度会让底下播放页的台名、节目单行、上下渐变压边透上来，
                // 跟刻度尺叠成一层重影 —— 实测出来的问题，不是预防性写法。
                .background(Color(0xFF0E0E0E))
                .padding(horizontal = 40.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = channelName, style = TvText.current.title, color = MaterialTheme.colorScheme.onSurface)
            Text(text = "节目单", style = TvText.current.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (timeline.isEmpty) {
            EmptyEpgHint(clock = clock, timeline = timeline)
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            TimelineBody(
                timeline = timeline,
                nowMillis = now,
                clock = clock,
                onSelect = { selected = it },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = footerHint,
            style = TvText.current.hint,
            color = Color(0x99FFFFFF),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 节目单没覆盖这个台时的说明。不写「暂无节目信息」这种占位话，直接说清两种可能 */
@Composable
private fun EmptyEpgHint(
    clock: SimpleDateFormat,
    timeline: EpgTimeline,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "这个台的节目单还没有数据",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    "可能是还没导入节目单，也可能是导入的那份里没有这个台。" +
                        "时间轴已按 ${clock.format(Date(timeline.windowStart))}–" +
                        "${clock.format(Date(timeline.windowEnd))} 铺开。",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "去源管理 → 导入节目单（XMLTV）", style = TvText.current.hint, color = Color(0x99FFFFFF))
        }
    }
}

@Composable
private fun TimelineBody(
    timeline: EpgTimeline,
    nowMillis: Long,
    clock: SimpleDateFormat,
    onSelect: (EpgBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val totalWidth = MINUTE_WIDTH * timeline.windowMinutes
    val requesters =
        remember(timeline.windowStart, timeline.blocks.size) {
            List(timeline.blocks.size) { FocusRequester() }
        }

    // 进场只做一件事：把焦点交给正在播的那块。
    //
    // 不做横向滚动 —— 窗口起点永远是「现在」往前半格，所以「现在」必然落在
    // 视口左侧 30~60 分钟处，天生可见。第一版按 leadIn=20 分钟滚了一下，
    // 结果把第一个刻度「03:30」滚掉了一半，只剩个「:0」挂在左上角。
    LaunchedEffect(timeline.windowStart) {
        val focusIndex = timeline.blocks.indexOfFirst { it.programme.isLiveAt(nowMillis) }
        if (focusIndex >= 0) {
            // 节点要等一帧才 attach，直接 requestFocus 会抛 «not initialized»
            withFrameNanos { }
            runCatching { requesters[focusIndex].requestFocus() }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
    ) {
        // 内容层：刻度尺 + 节目块。宽度由窗口时长决定，高度由常量定死
        Column(modifier = Modifier.width(totalWidth)) {
            RulerRow(timeline = timeline, clock = clock)

            // 用 Box + offset 而不是 Row：两档节目之间常常有空档（节目单没排满，
            // 或者中间那档被丢掉了），Row 会把后面的块整体左移，节目就和刻度对不上。
            // offset 直接按 startMinute 落点，空档自然留白。
            Box(modifier = Modifier.height(BLOCK_HEIGHT).fillMaxWidth()) {
                timeline.blocks.forEachIndexed { index, block ->
                    val isLive = block.programme.isLiveAt(nowMillis)
                    EpgBlockCard(
                        block = block,
                        isLive = isLive,
                        clock = clock,
                        focusRequester = requesters.getOrNull(index),
                        onClick = { onSelect(block) },
                        modifier =
                            Modifier
                                .offset(x = MINUTE_WIDTH * block.startMinute)
                                .width(MINUTE_WIDTH * block.durationMinutes)
                                .fillMaxHeight(),
                    )
                }
            }
        }

        // 「现在」的竖线：放在滚动内容里，跟着节目一起滚，才能永远对准刻度
        val nowOffset = timeline.nowOffsetMinutes(nowMillis)
        if (nowOffset != null) {
            Box(
                modifier =
                    Modifier
                        .offset(x = MINUTE_WIDTH * nowOffset)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** 刻度尺：整点/半点一格，刻度线用左边框代替，免得每格多画一个 Box */
@Composable
private fun RulerRow(
    timeline: EpgTimeline,
    clock: SimpleDateFormat,
) {
    Row(modifier = Modifier.height(RULER_HEIGHT)) {
        // 最后一格只画刻度不画内容：它的右边界就是窗口末尾，再给宽度会撑出内容区
        timeline.tickMinutes.dropLast(1).forEach { minute ->
            Box(
                modifier =
                    Modifier
                        .width(MINUTE_WIDTH * SLOT_MINUTES)
                        .fillMaxHeight(),
            ) {
                // 靠左的 1dp 细线当刻度
                Box(
                    modifier =
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0x40FFFFFF)),
                )
                Text(
                    text = clock.format(Date(timeline.windowStart + minute * 60_000L)),
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun EpgBlockCard(
    block: EpgBlock,
    isLive: Boolean,
    clock: SimpleDateFormat,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier.padding(end = 1.dp),
        focusRequester = focusRequester,
        // 节目块宽度由时长决定，20dp 的水平内边距会把 15 分钟档（60dp 宽）的标题挤成两个字
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = block.programme.title,
                style = TvText.current.label,
                // 正在播的那档用主色标出来：一屏十几块，找「现在是什么节目」不该靠读时间
                color =
                    if (isLive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${clock.format(Date(block.programme.startAt))}–${clock.format(Date(block.programme.endAt))}",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * `HH:mm` 格式化器：24 小时制固定，不跟随系统「12 小时制」设置。
 *
 * 时间轴上零点到中午那段如果变成 `12:00 AM / 12:00 PM`，一屏十几个刻度全是 AM/PM，
 * 扫读成本比 24 小时制高得多。电视节目单用 24 小时制是行业惯例。
 */
@Composable
private fun rememberClockFormat(): SimpleDateFormat =
    remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
