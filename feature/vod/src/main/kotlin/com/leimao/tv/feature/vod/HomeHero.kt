package com.leimao.tv.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.components.RemoteImage
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import kotlinx.coroutines.delay

/**
 * 首页 Hero 区（HOME-04）：一张大图 + 一句话，遥控器停着不动就自己往下轮播。
 *
 * 几个刻意的取舍：
 *
 * 1. **整块只有一个焦点目标**。轮播时如果内容区里各有一堆可点元素，焦点会在换图的一瞬间
 *    落到别处（TV 上最常见的事故）。这里「换哪一张」是纯状态切换，不移动焦点 ——
 *    用户盯着第一张按 OK，按下去就是他正在看的那张。
 * 2. **有焦点就不轮播**。用户把焦点停在 Hero 上说明他正在做决定，
 *    这时候换内容等于「你刚要按，我把它换了」。一旦离开，3 秒后接着转。
 * 3. **不自动播放视频**。首页预播一个流会把带宽和首帧全都吃掉，
 *    而冷启动本来就有一条直播流在播（§6.6）。这里只放静态图。
 * 4. 空列表直接不渲染 —— 没有内容的 Hero 区就是一块黑，不如让下面的内容顶上来。
 *
 * [rotateMillis] 只给测试和调试包留的口子，正式界面用 [HERO_ROTATE_MS]。
 */
@Composable
fun HomeHero(
    items: List<HeroItem>,
    onOpen: (HeroItem) -> Unit,
    modifier: Modifier = Modifier,
    rotateMillis: Long = HERO_ROTATE_MS,
) {
    if (items.isEmpty()) return

    // 列表换了内容就从头开始：换了片子还停在第 4 位，用户看到的是「随机一张」
    var index by remember(items) { mutableStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val current = items[index.coerceIn(0, items.lastIndex)]

    LaunchedEffect(items.size, focused) {
        // 停在 Hero 上不转；只有一张时也不用转（转了也是它自己）
        if (focused || items.size < 2) return@LaunchedEffect
        while (true) {
            delay(rotateMillis)
            index = nextHeroIndex(index, items.size)
        }
    }

    // 用外层 Box 观察 hasFocus 而不是给 FocusableCard 加参数：
    // 焦点态是通用视觉，不该为了「Hero 需要知道自己有没有焦点」再往设计系统里加一个入参
    Box(modifier = modifier.onFocusChanged { focused = it.hasFocus }) {
        FocusableCard(
            onClick = { onOpen(current) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.height(HERO_HEIGHT)) {
                HeroPoster(item = current)
                Column(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 16.dp),
                ) {
                    Text(
                        text = current.kicker,
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = current.title,
                        style = TvText.current.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = current.meta,
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 把「按下去会发生什么」写在图上：电视上没光标，不给提示就得靠猜
                        Text(
                            text = "按 OK ${current.actionLabel}",
                            style = TvText.current.label,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        HeroDots(count = items.size, selected = index.coerceIn(0, items.lastIndex))
                    }
                }
            }
        }
    }
}

/**
 * Hero 左侧的 16:9 图。
 *
 * 台标是方的、海报是 2:3 的，统一按 16:9 裁切：Hero 的作用是「一眼认出来」，
 * 不是让人端详构图。没有图时用首字占位，比一块灰色空盒好认。
 */
@Composable
private fun HeroPoster(item: HeroItem) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .padding(10.dp)
                .clip(POSTER_CORNER)
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        RemoteImage(
            url = item.poster,
            fallbackText = item.title,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 轮播进度点。选中点用主题色，其余压暗 —— 不做数字页码，客厅距离下看不清小字。 */
@Composable
private fun HeroDots(
    count: Int,
    selected: Int,
) {
    if (count < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count.coerceAtMost(MAX_DOTS)) { i ->
            Box(
                modifier =
                    Modifier
                        .size(if (i == selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        ),
            )
        }
    }
}

/** 轮播间隔：3 秒（HOME-04 的规定值），够读完标题，又不至于等得以为卡住了 */
const val HERO_ROTATE_MS: Long = 3_000L

/**
 * Hero 高度。
 *
 * 168dp 是为了「一行海报的高度」与「整块别把下面挤没」之间取的中间值：
 * 再高就把「最近更新」整行推出首屏，首页就变成只有一个轮播了。
 */
private val HERO_HEIGHT = 168.dp

private val POSTER_CORNER = RoundedCornerShape(8.dp)

/** 点数上限：超过 6 个点就成了一条虚线，读不出「还有几张」 */
private const val MAX_DOTS = 6
