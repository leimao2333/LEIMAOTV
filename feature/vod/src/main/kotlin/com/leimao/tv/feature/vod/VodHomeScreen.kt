package com.leimao.tv.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.components.FilterBar
import com.leimao.tv.core.designsystem.components.FilterSpec
import com.leimao.tv.core.designsystem.components.RemoteImage
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodContinueItem
import com.leimao.tv.core.model.vod.VodSummary

/**
 * 影视首页（VOD-03）：类型分栏 + 继续观看 + 最近更新 + 每源一行 + 年份/地区筛选。
 *
 * 三层信息结构，从上到下由「宽」到「窄」：
 * 1. **类型**：我要看什么（电影 / 剧集 / 动漫…）；
 * 2. **筛选**：这一栏里更细的范围（哪一年 / 哪个地区）；
 * 3. **内容**：Hero 轮播（HOME-04）+ 继续观看（VOD-06，跨类型）+ 最近更新（全库）
 *    + 当前类型 + 每源一行。
 *
 * 关于「筛选为下拉还是横排」：电视上没有「点击展开、方向键选择」的舒适区，
 * 一个弹出的下拉层反而要多两次按键。这里改成**横排 chip**，左右键走到就选中，
 * 与 RULES §6.1 的输入假设一致。
 *
 * 筛选生效时**隐藏「最近更新」与「每源一行」**：那两块是不分年份/地区的全景，
 * 混在筛选结果里会让人以为筛选没生效。
 */
@Composable
fun VodHomeScreen(
    state: VodHomeState,
    hero: List<HeroItem>,
    onHeroOpen: (HeroItem) -> Unit,
    onSelectType: (ContentType) -> Unit,
    onSelectYear: (Int?) -> Unit,
    onSelectArea: (String?) -> Unit,
    onSelectTag: (String?) -> Unit,
    onSelectMedia: (VodSummary) -> Unit,
    onResume: (VodContinueItem) -> Unit,
    onOpenHistory: (VodContinueItem) -> Unit,
    onOpenSourceManager: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val firstChip = remember { FocusRequester() }

    // 首帧还没布局完时 requestFocus 会打空，等一帧再要焦点（与全局菜单同一套做法）
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstChip.requestFocus() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "影视",
                style = TvText.current.display,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = if (state.loading) "正在更新…" else state.note.orEmpty(),
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(VOD_HOME_TYPES, key = { it.name }) { type ->
                SelectableChip(
                    text = type.displayName,
                    selected = type == state.selectedType,
                    onClick = { onSelectType(type) },
                    focusRequester = if (type == state.selectedType) firstChip else null,
                )
            }
        }

        if (state.hasSource && (state.years.isNotEmpty() || state.areas.isNotEmpty() || state.tags.isNotEmpty())) {
            Spacer(modifier = Modifier.height(10.dp))
            // 常驻筛选行（VOD-12）：年份 / 地区 / 标签各自独立下拉，不再「一次展开全部」。
            // 聚合源下几十个值在弹层里滚，不会把影片网格顶出屏幕；触发器上直接显示所选值。
            FilterBar(
                filters =
                    buildList {
                        if (state.years.isNotEmpty()) {
                            add(
                                FilterSpec(
                                    label = "年份",
                                    values = state.years.map { it.toString() },
                                    selected = state.selectedYear?.toString(),
                                    onSelect = { onSelectYear(it?.toIntOrNull()) },
                                ),
                            )
                        }
                        if (state.areas.isNotEmpty()) {
                            add(
                                FilterSpec(
                                    label = "地区",
                                    values = state.areas,
                                    selected = state.selectedArea,
                                    onSelect = onSelectArea,
                                ),
                            )
                        }
                        // 标签维度仅在标签池非空时出现：空池放个「标签」触发器点进去是空的，
                        // 比不放更让人怀疑坏了
                        if (state.tags.isNotEmpty()) {
                            add(
                                FilterSpec(
                                    label = "标签",
                                    values = state.tags,
                                    selected = state.selectedTag,
                                    onSelect = onSelectTag,
                                ),
                            )
                        }
                    },
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            when {
                !state.hasSource ->
                    HintBlock(
                        title = "还没有影视源",
                        detail = "影视源和直播源是分开添加的。去源管理里加一个影视接口，回来就能看到内容。",
                        actionText = "去源管理",
                        onAction = onOpenSourceManager,
                    )

                state.loading && state.blank ->
                    HintBlock(
                        title = "正在载入影视内容…",
                        detail = null,
                        actionText = null,
                        onAction = null,
                    )

                state.blank ->
                    HintBlock(
                        title = "这个栏目暂时没有内容",
                        detail = state.note ?: "换一个类型看看，或者到源管理里检查影视源是否可用。",
                        actionText = null,
                        onAction = null,
                    )

                else -> {
                    // Hero 放最前面（HOME-04）：它是「现在最该看的那一个」，
                    // 而下面几行是「还有哪些」。没有可轮播的内容时它自己不渲染，不留空档
                    HomeHero(items = hero, onOpen = onHeroOpen)
                    // 继续观看放在最上面，且**不受筛选影响**：切年份/地区是想找新片，
                    // 与「我昨天看到第 12 集」无关（见 VodHomeState.continueWatching 的注释）
                    if (state.continueWatching.isNotEmpty()) {
                        WatchedRow(
                            title = "继续观看",
                            items = state.continueWatching,
                            finished = false,
                            onOpen = onResume,
                        )
                    }
                    // 收藏与看过的也放在筛选之上：它们是「我的东西」，
                    // 与「我这次想看哪个年份的片」是两件事（同 continueWatching 的理由）
                    if (state.favorites.isNotEmpty()) {
                        MediaRow(
                            title = "我的收藏",
                            items = state.favorites.map { it.toSummary() },
                            onSelect = onSelectMedia,
                        )
                    }
                    if (state.history.isNotEmpty()) {
                        WatchedRow(
                            title = "看过的",
                            items = state.history,
                            finished = true,
                            onOpen = onOpenHistory,
                        )
                    }
                    if (!state.filtering) {
                        MediaRow(title = "最近更新", items = state.recent, onSelect = onSelectMedia)
                    }
                    MediaRow(
                        title = state.selectedType.displayName,
                        items = state.items,
                        onSelect = onSelectMedia,
                    )
                    if (!state.filtering) {
                        state.sourceRows.forEach { row ->
                            MediaRow(title = row.sourceName, items = row.items, onSelect = onSelectMedia)
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- //
// 组件
// --------------------------------------------------------------------------- //

/**
 * 可选中 chip。
 *
 * 选中态靠**文字颜色 + 底部短横线**表达，而不是换背景：焦点态已经用了描边+缩放，
 * 两种状态同时出现时再叠一层背景色会分不清「我选中了」还是「我停在这」。
 * 短横线占位恒定（未选中时透明），选中时不会把整行撑高。
 */
@Composable
private fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    FocusableCard(
        onClick = onClick,
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                style = TvText.current.body,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier =
                    Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .background(if (selected) accent else Color.Transparent),
            )
        }
    }
}

/**
 * 一排海报。空数据直接不渲染标题 —— 免得出现「最近更新」四个字下面一片空白，
 * 让人以为加载失败。
 */
@Composable
private fun MediaRow(
    title: String,
    items: List<VodSummary>,
    onSelect: (VodSummary) -> Unit,
) {
    if (items.isEmpty()) return
    Column {
        Text(
            text = title,
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(10.dp))
        // 行尾不露半张卡（用户实测反馈）：按可用宽度算出整数张数，
        // 每张宽度均摊。少半张卡难看，比「固定宽度 + 滚动暗示」重要。
        BoxWithConstraints {
            val spacing = 14.dp
            val count =
                maxOf(
                    MIN_POSTERS_PER_ROW,
                    ((maxWidth + spacing) / (POSTER_WIDTH + spacing)).toInt(),
                )
            val cardWidth = (maxWidth - spacing * (count - 1)) / count
            LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                // key 必须稳定：刷新时焦点才不会跳到第一张（RULES §7）
                items(items, key = { "${it.sourceId}-${it.sourceKey}" }) { item ->
                    PosterCard(
                        item = item,
                        posterWidth = cardWidth,
                        posterHeight = cardWidth * POSTER_ASPECT,
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

/**
 * 「继续观看」/「看过的」一行（VOD-06 / VOD-07）。
 *
 * 两个列表用同一个组件：卡片长得一样（16:9 + 集号 + 标题），差别只在「点进去干什么」
 * 和右下角那句说明。给看完的内容再写一套卡片，只会让两行的圆角与字号慢慢长歪。
 *
 * 卡片用横向 16:9 而不是海报的 2:3：这一行的主角是「看到哪儿了」，进度条要有足够长度
 * 才读得出来；而且横版卡片下面的标题区更宽，长片名不至于全被省略号吃掉。
 *
 * 海报本身是 2:3 的，裁成 16:9 会切掉上下 —— 可以接受：这里用户已经知道自己要哪部，
 * 需要的是一眼认出「哦是这部」，而不是在海报上端详构图。
 */
@Composable
private fun WatchedRow(
    title: String,
    items: List<VodContinueItem>,
    finished: Boolean,
    onOpen: (VodContinueItem) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { "${it.progress.sourceId}-${it.progress.sourceKey}" }) { item ->
                WatchedCard(item = item, finished = finished, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun WatchedCard(
    item: VodContinueItem,
    finished: Boolean,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(CONTINUE_WIDTH),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(CONTINUE_HEIGHT)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = item.poster,
                    fallbackText = item.title,
                    modifier = Modifier.fillMaxSize(),
                )

                // 集号压在左上角：用户扫这一行时先确认「是不是我看到那一集的地方」。
                // 看完的片子写「已看完」而不是集号 —— 集号只对「接着看」有意义
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (finished) "已看完" else item.episodeLabel,
                        style = TvText.current.hint,
                        color = Color.White,
                        maxLines = 1,
                    )
                }

                // 进度条贴底：不占额外高度，也不与集号抢位置。
                // 看完的不画：一条永远满格的进度条只会让人以为「还能接着看」
                if (!finished) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(Color(0x66FFFFFF)),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth(item.fraction)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.title,
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 已看到 12:34 比「已看 27%」有用：前者能对上进度条，后者还得自己算
            Text(
                text =
                    if (finished) {
                        "已看完 · ${item.episodeLabel}"
                    } else {
                        "${item.episodeLabel} · 已看到 ${formatTime(item.progress.positionMs)}"
                    },
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PosterCard(
    item: VodSummary,
    posterWidth: Dp = POSTER_WIDTH,
    posterHeight: Dp = POSTER_HEIGHT,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(posterWidth),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(posterHeight)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = item.pic,
                    fallbackText = item.name,
                    modifier = Modifier.fillMaxSize(),
                )

                val remarks = item.remarks
                if (!remarks.isNullOrBlank()) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xCC000000))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = remarks,
                            style = TvText.current.hint,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.name,
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = listOfNotNull(item.year?.toString(), item.area).joinToString(" · ")
            Text(
                text = secondary.ifBlank { item.type.displayName },
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 空态 / 提示块。给不给按钮由 [actionText] 决定（没动作时不留一个假的按钮）。 */
@Composable
private fun HintBlock(
    title: String,
    detail: String?,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(4.dp))
            FocusableCard(onClick = onAction) {
                Text(
                    text = actionText,
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 期望的单张海报宽度：实际宽度按可用宽整除均摊（见 [MediaRow]），这是下限参考值 */
private val POSTER_WIDTH = 150.dp

/** 海报 2:3 通行比例 */
private const val POSTER_ASPECT = 1.5f

/** 一行最少放几张：太宽的屏幕上海报也不能无限放大到失真 */
private const val MIN_POSTERS_PER_ROW = 4

private val POSTER_HEIGHT = POSTER_WIDTH * POSTER_ASPECT

/** 继续观看卡：16:9 */
private val CONTINUE_WIDTH = 244.dp

private val CONTINUE_HEIGHT = 138.dp
