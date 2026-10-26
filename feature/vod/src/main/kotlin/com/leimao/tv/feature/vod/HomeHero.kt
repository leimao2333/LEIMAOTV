package com.leimao.tv.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.components.RemoteImage
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.LocalTvFocusVisuals
import com.leimao.tv.core.designsystem.theme.TvText
import kotlinx.coroutines.delay

/**
 * 首页 Hero 区（HOME-04）：一张通栏大图 + 标题 + 「▶ 立即播放」。
 *
 * 版式对齐主流长视频首页（腾讯视频/爱奇艺那套）：底图铺满、左侧压一层由深到透的渐变，
 * 文字压在渐变上，右下角是轮播点。这样一屏里「图片」是主角，文字是注释 ——
 * 而不是反过来（老版式是「左边一张小图 + 右边一坨字」，看起来像详情页的头）。
 *
 * 几个刻意的取舍：
 *
 * 1. **整块只有一个焦点目标**。轮播时如果内容区里各有一堆可点元素，焦点会在换图的一瞬间
 *    落到别处（TV 上最常见的事故）。这里「换哪一张」是纯状态切换，不移动焦点 ——
 *    用户盯着第一张按 OK，按下去就是他正在看的那张。
 * 2. **「立即播放」是一枚装饰性按钮**，不单独吃焦点：它画的正是「按 OK 会发生什么」。
 *    真做成第二个焦点目标，用户得先分辨「我该停在图上还是停在按钮上」，多一次按键、
 *    多一次想。按钮文字直接写动作（接着看 / 看详情），不写「播放」——
 *    没看过的新片点进去是详情页，写「播放」会骗人。
 * 3. **有焦点就不轮播**。用户把焦点停在 Hero 上说明他正在做决定，
 *    这时候换内容等于「你刚要按，我把它换了」。一旦离开，3 秒后接着转。
 * 4. **不自动播放视频**。首页预播一个流会把带宽和首帧全都吃掉，
 *    而冷启动本来就有一条直播流在播（§6.6）。这里只放静态图。
 * 5. 空列表直接不渲染 —— 没有内容的 Hero 区就是一块黑，不如让下面的内容顶上来。
 *
 * 压在照片上的文字**固定用白色**，不跟主题走：浅色主题下用 `onBackground`（近黑）
 * 压在深色剧照上会直接看不见。这是全项目唯一允许绕过色板的场景 ——
 * 因为底不是主题色，是用户源里的图片。
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

    // 圆角取设计系统里那个：焦点描边画的是同一个 shape，内层图片若自己写一个 8dp，
    // 聚焦时就会看到「描边在里面、图片缺一角」
    val shape = LocalTvFocusVisuals.current.shape

    // 用外层 Box 观察 hasFocus 而不是给 FocusableCard 加参数：
    // 焦点态是通用视觉，不该为了「Hero 需要知道自己有没有焦点」再往设计系统里加一个入参
    Box(modifier = modifier.onFocusChanged { focused = it.hasFocus }) {
        FocusableCard(
            onClick = { onOpen(current) },
            contentPadding = PaddingValues(0.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(HERO_HEIGHT)
                        .clip(shape),
            ) {
                HeroBackdrop(item = current)

                // 两层渐变：横向那层保证左侧文字有底，纵向那层把下沿压暗、与页面底色接住。
                // 只画横向的话，浅色剧照（雪景、白墙）上的标题会糊掉
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    0f to SCRIM_LEFT,
                                    0.55f to SCRIM_MID,
                                    1f to Color.Transparent,
                                ),
                            ),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.5f to Color.Transparent,
                                    1f to SCRIM_BOTTOM,
                                ),
                            ),
                )

                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                ) {
                    Text(
                        text = current.kicker,
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = current.title,
                        style = TvText.current.display,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current.meta.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = current.meta,
                            style = TvText.current.hint,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    PlayPill(label = current.actionLabel)
                }

                HeroDots(
                    count = items.size,
                    selected = index.coerceIn(0, items.lastIndex),
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 20.dp),
                )
            }
        }
    }
}

/**
 * Hero 的底图。
 *
 * 影视是 2:3 海报，裁成通栏可以接受（剧照裁掉上下仍是剧照）；
 * **直播台标不行**：台标是方图、常常还是透明底，拉满一屏只剩中间一团色块 ——
 * 模拟器上看到一个巨大的「C」（CGTN 的首字占位）铺满整条 banner，就是这么来的。
 * 所以直播条目改成「靠右居中放一枚方形台标」，左边留给文字。
 */
@Composable
private fun BoxScope.HeroBackdrop(item: HeroItem) {
    if (item is HeroItem.Live) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // 台标垫一块底：拉不到的台标会露出「首字占位」，没有底的时候
            // 那个孤零零的「C」看着像渲染坏了；有底就是个「图没加载出来」的方块，一眼能懂
            Box(
                modifier =
                    Modifier
                        .padding(end = 72.dp)
                        .fillMaxHeight(0.62f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                RemoteImage(
                    url = item.poster,
                    fallbackText = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    } else {
        RemoteImage(
            url = item.poster,
            fallbackText = item.title,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 「▶ 立即播放」那枚胶囊。
 *
 * 白底黑字是刻意的：整块图是暗的，白色是唯一能保证在任意剧照上都读得出来的按钮底色。
 * 不接 onClick —— 它只是「按 OK 会发生什么」的说明书，点击由外层 Hero 接（见函数注释第 2 条）。
 */
@Composable
private fun PlayPill(label: String) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "▶",
            style = TvText.current.hint,
            color = PLAY_PILL_TEXT,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = TvText.current.label,
            color = PLAY_PILL_TEXT,
        )
    }
}

/** 轮播进度点。选中点用主题色，其余压暗 —— 不做数字页码，客厅距离下看不清小字。 */
@Composable
private fun HeroDots(
    count: Int,
    selected: Int,
    modifier: Modifier = Modifier,
) {
    if (count < 2) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                                Color.White.copy(alpha = 0.4f)
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
 * 168dp 是「一张通栏图该有的气势」与「别把下面第一排海报挤出首屏」之间的折中：
 * 电视 1080p 在 320dpi 下只有 540dp 高，再高就只能看到 banner、看不到内容了。
 */
private val HERO_HEIGHT = 168.dp

/** 左侧压暗：从几乎全黑渐到透明，保证标题在任意底图上都有 ≥4.5:1 的对比 */
private val SCRIM_LEFT = Color(0xF20B0F12)

private val SCRIM_MID = Color(0x800B0F12)

private val SCRIM_BOTTOM = Color(0xD90B0F12)

/** 胶囊上的字色。不用纯黑：纯黑压在纯白上偏刺眼，0x111 更接近设计稿 */
private val PLAY_PILL_TEXT = Color(0xFF111417)

/** 点数上限：超过 6 个点就成了一条虚线，读不出「还有几张」 */
private const val MAX_DOTS = 6
