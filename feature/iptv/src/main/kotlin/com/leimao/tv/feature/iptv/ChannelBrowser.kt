package com.leimao.tv.feature.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.Channel

/**
 * 分组频道浏览（LIVE-10）。
 *
 * 左分组、右频道，两栏都用 `LazyColumn` + `key`：5000 条频道在低端盒子上要按 §7
 * 不丢帧，`key` 是让 Compose 能复用而不是重建每一项的前提。
 *
 * 同一份组件被两处复用：主页的「直播」板块与播放页呼出的频道抽屉。
 * 两处各写一遍的话，换台顺序迟早会和列表顺序对不上。
 */
@Composable
fun ChannelBrowser(
    groups: List<ChannelGroup>,
    selectedChannelId: Long?,
    onChannelClick: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    favoriteIds: Set<Long> = emptySet(),
    autoFocus: Boolean = true,
    initialFocusRequester: FocusRequester? = null,
    recentChannels: List<Channel> = emptyList(),
    /** 外部请求「跳转到某个分组」（HOME-06 分组快捷入口）。变化时列表切到该分组。 */
    pendingGroup: String? = null,
) {
    // 「最近观看」作为虚拟分组插在最前（LIVE-15）。它和真实分组并列出现在左侧分组栏，
    // 只是数据来源不同：真实分组来自全集切片，它是 history 表的一份倒序查询。
    val allGroups =
        remember(groups, recentChannels) {
            // 编号以「六个分类里的那一份」为准（LIVE-30）：最近观看 / 收藏的频道来自
            // 历史表与收藏表，带的是入库时的旧编号，不换过来就会出现
            // 「同一个台在最近观看里是 1078、在央视频道里是 021」——按号换台直接失灵
            val canonical = groups.flatMap { it.channels }.associateBy { it.id }
            val recent =
                if (recentChannels.isEmpty()) {
                    emptyList()
                } else {
                    listOf(ChannelGroup(RECENT_GROUP_NAME, recentChannels.map { canonical[it.id] ?: it }))
                }
            (recent + groups).map { group ->
                group.copy(channels = group.channels.map { canonical[it.id] ?: it })
            }
        }

    if (allGroups.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "还没有频道",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val selectedGroupName =
        allGroups.firstOrNull { group -> group.channels.any { it.id == selectedChannelId } }?.name
            ?: allGroups.first().name
    var activeGroup by rememberSaveable(allGroups) { mutableStateOf(selectedGroupName) }

    // 外部请求跳转分组：分组快捷入口点中某个分组时，把列表切过去（HOME-06）。
    // 只在分组真实存在时才切，避免外部传了个已不存在的分组名导致列表错乱。
    LaunchedEffect(pendingGroup) {
        val target = pendingGroup ?: return@LaunchedEffect
        if (allGroups.any { it.name == target }) activeGroup = target
    }

    val activeChannels = allGroups.firstOrNull { it.name == activeGroup }?.channels ?: allGroups.first().channels
    val listState = rememberLazyListState()

    // 播放页呼出抽屉时，焦点应该直接落在「正在看的台」上，而不是列表顶部
    LaunchedEffect(activeGroup) {
        if (!autoFocus) return@LaunchedEffect
        val index = activeChannels.indexOfFirst { it.id == selectedChannelId }
        if (index >= 0) listState.scrollToItem(index)
    }

    // 调用方要「进来就有焦点」时，必须等这一帧布局结束再请求：
    // 节点还没 attach 就 requestFocus 会抛 IllegalStateException，直接崩在打开抽屉那一刻
    LaunchedEffect(initialFocusRequester, activeGroup) {
        if (initialFocusRequester == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { initialFocusRequester.requestFocus() }
    }

    Row(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.width(GROUP_COLUMN_WIDTH).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            state = rememberLazyListState(),
        ) {
            items(items = allGroups, key = { it.name }) { group ->
                val isActive = group.name == activeGroup
                FocusableCard(
                    onClick = { activeGroup = group.name },
                    modifier = Modifier.fillMaxWidth().height(GROUP_ROW_HEIGHT),
                    // 行高固定 + 纵向内边距归零：分组栏要能在 1080p 里放下十来个组，
                    // 每项都按内容撑高的话，长名字那几行会把整栏顶出屏幕
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    // 选中态靠底色区分，不只是换个文字颜色 —— 客厅距离下颜色差一档看不出来（LIVE-26）
                    containerColor =
                        if (isActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_TINT_ALPHA)
                        } else {
                            null
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左侧竖条：焦点描边之外的第二重「当前组」信号，滚动时也能一眼找到
                        Box(
                            modifier =
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(
                                        if (isActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.Transparent
                                        },
                                    ),
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = group.name,
                            style = TvText.current.label,
                            color =
                                if (isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            // 分组里有多少台，一眼可见；比只显示名字有用
                            text = group.channels.size.toString(),
                            style = TvText.current.hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            state = listState,
        ) {
            items(items = activeChannels, key = { it.id }) { channel ->
                val isCurrent = channel.id == selectedChannelId
                FocusableCard(
                    onClick = { onChannelClick(channel) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(CHANNEL_ROW_HEIGHT)
                            // 交给调用方指定「进来先落在哪个台上」：抽屉打开时如果不给焦点，
                            // 焦点会留在被移除的控制条上并回落到根容器，方向键就再也进不了列表
                            .then(
                                if (channel == activeChannels.first() && initialFocusRequester != null) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                            ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    // 正在看的台给一层底色：换台后回到抽屉，用户第一眼要能找回自己刚才那个台
                    containerColor =
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_TINT_ALPHA)
                        } else {
                            null
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = channel.number.toString().padStart(3, '0'),
                            style = TvText.current.hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.width(NUMBER_WIDTH),
                        )
                        ChannelLogo(logoUrl = channel.logoUrl, size = CHANNEL_LOGO_SIZE)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = channel.name,
                            style = TvText.current.label,
                            color =
                                if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Spacer(modifier = Modifier.width(8.dp))
                            NowPlayingBadge()
                        }
                        if (favoriteIds.contains(channel.id)) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "★", style = TvText.current.hint, color = MATERIAL_GOLD)
                        }
                    }
                }
            }
        }
    }
}

/** 「正在播」徽标：当前正在看的那个台（LIVE-26）。文字 + 底色，比一个小圆点更好认 */
@Composable
private fun NowPlayingBadge() {
    Text(
        text = "正在播",
        style = TvText.current.hint,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 台标（LIVE-17）。加载失败或没有台标时显示一个占位方块 ——
 * 列表里高度不齐会让人以为「这个台没了」，占位保证每行视觉高度一致。
 */
@Composable
fun ChannelLogo(
    logoUrl: String?,
    modifier: Modifier = Modifier,
    size: Int = 40,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier =
            modifier
                .size(size.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl.isNullOrBlank()) {
            Text(text = "台", style = TvText.current.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
        }
    }
}

private val MATERIAL_GOLD = Color(0xFFE6B422)

/** 分组栏宽度：够放「XX频道」四个字加计数，再宽就吃内容区 */
private val GROUP_COLUMN_WIDTH = 200.dp

/** 分组行高：44dp 下 1080p 一屏能放下十来个分组 */
private val GROUP_ROW_HEIGHT = 44.dp

/**
 * 频道行高（LIVE-26「行高密度收敛」）。
 *
 * 56dp 是 §6.3「频道列表 ≥ 8 行可见」的下限再留一点余量：原来按内容撑高约 64dp、
 * 加上 6dp 间距只剩 7 行，遥控器一屏一屏翻得手酸。
 */
private val CHANNEL_ROW_HEIGHT = 56.dp

/** 台标尺寸：列表里所有行统一，失败占位与正常态同尺寸（否则行高会跳） */
private const val CHANNEL_LOGO_SIZE = 36

/** 频道号列宽：三位数字 + 留白，右对齐会随字体抖，固定宽度最稳 */
private val NUMBER_WIDTH = 44.dp

/**
 * 选中 / 正在播的底色透明度。
 *
 * 0.16 是「看得出是一块、又不至于把白字压成灰字」的位置：再深一档，浅色主题下
 * primary（深蓝）会把 onSurface 的对比度拉到 4.5:1 以下。
 */
private const val SELECTED_TINT_ALPHA = 0.16f

/** 抽屉里「最近观看」虚拟分组的名字（LIVE-15）。 */
private const val RECENT_GROUP_NAME = "最近观看"
