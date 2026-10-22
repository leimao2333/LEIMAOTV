package com.leimao.tv.feature.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
) {
    // 「最近观看」作为虚拟分组插在最前（LIVE-15）。它和真实分组并列出现在左侧分组栏，
    // 只是数据来源不同：真实分组来自全集切片，它是 history 表的一份倒序查询。
    val allGroups =
        remember(groups, recentChannels) {
            val recent =
                if (recentChannels.isEmpty()) {
                    emptyList()
                } else {
                    listOf(ChannelGroup(RECENT_GROUP_NAME, recentChannels))
                }
            recent + groups
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
            modifier = Modifier.width(200.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            state = rememberLazyListState(),
        ) {
            items(items = allGroups, key = { it.name }) { group ->
                val isActive = group.name == activeGroup
                FocusableCard(
                    onClick = { activeGroup = group.name },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        )
                        Spacer(modifier = Modifier.weight(1f))
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
            state = listState,
        ) {
            items(items = activeChannels, key = { it.id }) { channel ->
                val isCurrent = channel.id == selectedChannelId
                FocusableCard(
                    onClick = { onChannelClick(channel) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // 交给调用方指定「进来先落在哪个台上」：抽屉打开时如果不给焦点，
                            // 焦点会留在被移除的控制条上并回落到根容器，方向键就再也进不了列表
                            .then(
                                if (channel == activeChannels.first() && initialFocusRequester != null) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                            ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = channel.number.toString().padStart(3, '0'),
                            style = TvText.current.hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp),
                        )
                        ChannelLogo(logoUrl = channel.logoUrl)
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
                        )
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

/** 抽屉里「最近观看」虚拟分组的名字（LIVE-15）。 */
private const val RECENT_GROUP_NAME = "最近观看"
