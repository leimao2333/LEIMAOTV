package com.leimao.tv.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.components.RemoteImage
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.LocalTvFocusVisuals
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodContinueItem
import com.leimao.tv.core.model.vod.VodSummary

/**
 * 影视首页（VOD-03）。
 *
 * 版式对齐主流长视频首页（腾讯视频那一套）：**通栏大图 → 我的内容 → 榜单标题 → 海报网格**。
 * 相比老版「一行行横向滑动」，改成网格是因为首页要回答的是「有什么可看」，
 * 而一屏只能放一行半的时候，横向滑动一次只换来 4 张新海报，网格滚一次是 8~10 张。
 *
 * 页面分两层，不是随手分的：
 *
 * - **固定层**（不滚动）：栏目切换 + 换一换 + 年份/地区/标签。这三个都是「换一整套内容」的
 *   开关，滚到第三屏还想切类型时不该先滚回顶部；
 * - **滚动层**：Hero 轮播 + 继续观看 + 收藏 + 看过的 + 网格 + 每源一行。
 *
 * 筛选值**不用行内下拉**（老实现是 `FilterBar`）：下拉面板画在筛选行自己的 Box 里，
 * 而它下面的滚动列表是**后声明的兄弟节点**，会盖在面板上面 —— 打开筛选看到的是一排海报压着半截菜单。
 * 这里改成「整页遮罩 + 居中取值面板」（见 [VodFilterSheet]），面板是页面根节点的最后一个孩子，
 * 永远在最上层，也不再受滚动视口裁切。
 *
 * 筛选生效时**隐藏「为你推荐」与「每源一行」**：那两块是不分年份/地区的全景，
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
    val firstTab = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // 「换一换」只是把本地这批往前转一页，不重新请求：首页的数据是半小时缓存，
    // 真去拉一次远端要等好几秒，而用户按这个键的期待是「立刻换一批看看」
    var shuffle by rememberSaveable { mutableStateOf(0) }
    // 换类型就回到第一页：转了 5 次之后切到电影，第一屏不该是「第 6 页的电影」
    LaunchedEffect(state.selectedType) { shuffle = 0 }

    // 首帧还没布局完时 requestFocus 会打空，等一帧再要焦点（与全局菜单同一套做法）
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstTab.requestFocus() }
    }

    val filters =
        remember(state.years, state.areas, state.tags, state.selectedYear, state.selectedArea, state.selectedTag) {
            buildList {
                if (state.years.isNotEmpty()) {
                    add(
                        VodFilter(
                            key = FILTER_YEAR,
                            label = "年份",
                            values = state.years.map { it.toString() },
                            selected = state.selectedYear?.toString(),
                        ),
                    )
                }
                if (state.areas.isNotEmpty()) {
                    add(VodFilter(FILTER_AREA, "地区", state.areas, state.selectedArea))
                }
                // 标签维度仅在标签池非空时出现：空池放个「标签」触发器点进去是空的，
                // 比不放更让人怀疑坏了
                if (state.tags.isNotEmpty()) {
                    add(VodFilter(FILTER_TAG, "标签", state.tags, state.selectedTag))
                }
            }
        }

    var openFilterKey by rememberSaveable { mutableStateOf<String?>(null) }
    val openFilter = filters.firstOrNull { it.key == openFilterKey }

    // 面板关掉之后要把焦点送回**刚点的那颗 chip**。
    //
    // 面板打开期间底层是不组合的（焦点锁的要求），整棵内容树被销毁又重建 ——
    // 不主动接回来，焦点会退到左侧导航的第一项，用户每次筛完都得再按一次右键才能回内容区
    // （模拟器实测）。这里记住「刚才是哪个维度」，重建后把焦点放回它身上。
    val filterFocus = remember { FocusRequester() }
    var restoreFilterKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openFilterKey, restoreFilterKey) {
        if (openFilterKey == null && restoreFilterKey != null) {
            withFrameNanos { }
            runCatching { filterFocus.requestFocus() }
            restoreFilterKey = null
        }
    }
    val closeSheet: () -> Unit = {
        restoreFilterKey = openFilterKey
        openFilterKey = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            !state.hasSource ->
                HintBlock(
                    title = "还没有影视源",
                    detail = "影视源和直播源是分开添加的。去源管理里加一个影视接口，回来就能看到内容。",
                    actionText = "去源管理",
                    onAction = onOpenSourceManager,
                )

            // 面板打开时不组合底层：树内弹层没有窗口边界，靠「后面没有可聚焦的东西」
            // 才是唯一可靠的焦点锁（见 TvModal 的注释）
            openFilter != null ->
                VodFilterSheet(
                    filter = openFilter,
                    onPick = { value ->
                        when (openFilter.key) {
                            FILTER_YEAR -> onSelectYear(value?.toIntOrNull())
                            FILTER_AREA -> onSelectArea(value)
                            else -> onSelectTag(value)
                        }
                        closeSheet()
                    },
                    onDismiss = closeSheet,
                )

            else ->
                Column(modifier = Modifier.fillMaxSize()) {
                    VodTopBar(
                        selectedType = state.selectedType,
                        onSelectType = onSelectType,
                        onShuffle = { shuffle++ },
                        filters = filters,
                        onOpenFilter = { openFilterKey = it.key },
                        firstTabFocus = firstTab,
                        restoreKey = restoreFilterKey,
                        restoreFocus = filterFocus,
                    )

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val columns = columnsFor(maxWidth)
                        val cardWidth = (maxWidth - POSTER_SPACING * (columns - 1)) / columns
                        // 一页 = 一屏能看到的行数。转一页比转一张像「换了一批」，
                        // 转一张只会让人以为列表没动
                        val ordered =
                            remember(state.items, shuffle, columns) {
                                rotateBy(state.items, shuffle * columns * GRID_ROWS_PER_PAGE)
                            }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            if (state.blank) {
                                item("blank") {
                                    HintBlock(
                                        title = if (state.loading) "正在载入影视内容…" else "这个栏目暂时没有内容",
                                        detail =
                                            if (state.loading) {
                                                null
                                            } else {
                                                state.note ?: "换一个类型看看，或者到源管理里检查影视源是否可用。"
                                            },
                                        actionText = null,
                                        onAction = null,
                                    )
                                }
                            } else {
                                if (hero.isNotEmpty()) {
                                    item("hero") { HomeHero(items = hero, onOpen = onHeroOpen) }
                                }
                                // 继续观看不受筛选影响：切年份/地区是想找新片，
                                // 与「我昨天看到第 12 集」无关
                                if (state.continueWatching.isNotEmpty()) {
                                    item("continue") {
                                        WatchedRow(
                                            title = "继续观看",
                                            items = state.continueWatching,
                                            finished = false,
                                            onOpen = onResume,
                                        )
                                    }
                                }
                                if (state.favorites.isNotEmpty()) {
                                    item("favorites") {
                                        MediaRow(
                                            title = "我的收藏",
                                            items = state.favorites.map { it.toSummary() },
                                            onSelect = onSelectMedia,
                                        )
                                    }
                                }
                                if (state.history.isNotEmpty()) {
                                    item("history") {
                                        WatchedRow(
                                            title = "看过的",
                                            items = state.history,
                                            finished = true,
                                            onOpen = onOpenHistory,
                                        )
                                    }
                                }

                                item("grid-title") {
                                    SectionTitle(
                                        title = "重磅热播",
                                        hint = if (state.loading) "正在更新…" else state.note,
                                    )
                                }

                                items(
                                    items = ordered.chunked(columns),
                                    key = { row -> "row-${row.first().rowKey()}" },
                                ) { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(POSTER_SPACING)) {
                                        row.forEach { media ->
                                            PosterCard(
                                                item = media,
                                                cardWidth = cardWidth,
                                                onClick = { onSelectMedia(media) },
                                            )
                                        }
                                    }
                                }

                                if (!state.filtering) {
                                    if (state.recent.isNotEmpty()) {
                                        item("recent") {
                                            MediaRow(title = "为你推荐", items = state.recent, onSelect = onSelectMedia)
                                        }
                                    }
                                    state.sourceRows.forEach { row ->
                                        item("source-${row.sourceId}") {
                                            MediaRow(
                                                title = row.sourceName,
                                                items = row.items,
                                                onSelect = onSelectMedia,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}

// --------------------------------------------------------------------------- //
// 固定层：栏目条 + 筛选
// --------------------------------------------------------------------------- //

/**
 * 页面顶部的固定条：栏目切换 + 换一换 + 三个筛选触发器。
 *
 * 栏目用横向 LazyRow 而不是等分：八个栏目在 600dp 宽的内容区里放得下，
 * 但用户装了自定义字体/放大了字号就放不下 —— 能左右滑比被裁掉一个「纪录片」好。
 */
@Composable
private fun VodTopBar(
    selectedType: ContentType,
    onSelectType: (ContentType) -> Unit,
    onShuffle: () -> Unit,
    filters: List<VodFilter>,
    onOpenFilter: (VodFilter) -> Unit,
    firstTabFocus: FocusRequester,
    /** 刚关掉面板的那个维度：重建后焦点要回到它身上 */
    restoreKey: String?,
    restoreFocus: FocusRequester,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(VOD_HOME_TYPES, key = { it.name }) { type ->
                    TypeTab(
                        text = type.displayName,
                        selected = type == selectedType,
                        onClick = { onSelectType(type) },
                        focusRequester = if (type == selectedType) firstTabFocus else null,
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            FocusableCard(
                onClick = onShuffle,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "换一换",
                    style = TvText.current.label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (filters.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { filter ->
                    FilterChip(
                        text = filter.selected?.takeIf { it.isNotBlank() } ?: filter.label,
                        selected = filter.selected != null,
                        onClick = { onOpenFilter(filter) },
                        focusRequester = if (filter.key == restoreKey) restoreFocus else null,
                    )
                }
            }
        }
    }
}

/**
 * 栏目 tab。
 *
 * 选中态用**底色 + 主色文字**，未选中透明：参考图上选中的那一项就是「亮起来的一块」。
 * 焦点态仍由 [FocusableCard] 统一画描边 —— 两者叠加时不会互相盖住，因为底色是容器画的、
 * 描边是外层画的（这正是 `containerColor` 参数存在的理由）。
 *
 * 内边距压到 10dp：八个栏目 + 换一换在 600dp 宽的内容区里要放得下，
 * 用 14dp 时「纪录片」会被裁成「纪录」（模拟器实测）。
 */
@Composable
private fun TypeTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FocusableCard(
        onClick = onClick,
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        containerColor =
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                Color.Transparent
            },
    ) {
        Text(
            text = text,
            style = TvText.current.label,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/** 筛选触发器：没选时显示维度名（「地区」），选了显示所选值（「美国」） */
@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FocusableCard(
        onClick = onClick,
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = TvText.current.hint,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "▾",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 筛选取值面板。
 *
 * 为什么是整页遮罩而不是行内下拉：见 [VodHomeScreen] 的注释（面板会被下面的列表盖住）。
 * 值用 [FlowRow] 平铺：年份、地区这类选项都是短词，平铺后一眼能看全，
 * 比竖着滚一个二十项的长列表少按很多次方向键。
 *
 * BACK 在这一层用 `onPreviewKeyEvent` 收掉：面板没有独立窗口，不拦的话 BACK 会一路
 * 传到主页的「退出确认」，变成「关个筛选弹出退出对话框」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VodFilterSheet(
    filter: VodFilter,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstValue = remember(filter.key) { FocusRequester() }
    LaunchedEffect(filter.key) {
        withFrameNanos { }
        runCatching { firstValue.requestFocus() }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(SHEET_WIDTH)
                    .fillMaxHeight(0.86f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
        ) {
            Text(
                text = "按${filter.label}筛选",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 选项多的维度（年份能有二十年）要能滚：固定高度 + 内部滚动，
            // 不让它把面板撑破后被静默裁掉
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ValueChip(
                        text = "全部",
                        selected = filter.selected == null,
                        onClick = { onPick(null) },
                        focusRequester = firstValue,
                    )
                    filter.values.forEach { value ->
                        ValueChip(
                            text = value,
                            selected = value == filter.selected,
                            onClick = { onPick(value) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "按返回键收起",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ValueChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FocusableCard(
        onClick = onClick,
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
        containerColor =
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        Text(
            text = text,
            style = TvText.current.body,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            maxLines = 1,
        )
    }
}

// --------------------------------------------------------------------------- //
// 滚动层：标题 / 海报网格 / 横排
// --------------------------------------------------------------------------- //

/** 一个区块标题，右边可以挂一句提示（加载中 / 源抓取失败的原因） */
@Composable
private fun SectionTitle(
    title: String,
    hint: String?,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = title,
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (!hint.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = hint,
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

/**
 * 网格里的一个海报（参考图的卡片结构，从上到下）：
 *
 * ```
 * ┌──────────────┐
 * │         更新中│  ← 右上角状态角标
 * │              │
 * │ 更新至104集   │  ← 左下角更新备注（压一层渐变，保证可读）
 * └──────────────┘
 * 片名
 * 2024 · 中国大陆
 * ```
 *
 * 角标和备注都压在图上而不是排在图下：图下那块地方只够一行标题 + 一行副信息，
 * 再塞「更新至第 104 集」会把片名挤成省略号 —— 而片名才是用户认片的第一依据。
 *
 * 卡片底色传 `Transparent`：参考图里海报是直接浮在页面底色上的，
 * 每张卡再垫一块 `surfaceVariant` 会变成一排灰砖。
 */
@Composable
private fun PosterCard(
    item: VodSummary,
    cardWidth: Dp,
    onClick: () -> Unit,
) {
    val shape = LocalTvFocusVisuals.current.shape
    FocusableCard(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        containerColor = Color.Transparent,
        modifier = Modifier.width(cardWidth),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(cardWidth * POSTER_ASPECT)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                RemoteImage(
                    url = item.pic,
                    fallbackText = item.name,
                    modifier = Modifier.fillMaxSize(),
                )

                // 底部渐变压暗：源里的封面经常是浅底（雪景、白墙），
                // 不压一层的话白字备注直接看不见
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0xCC000000)),
                                ),
                            ),
                )

                cornerTagOf(item.remarks)?.let { tag ->
                    CornerBadge(
                        text = tag.text,
                        color = tag.color,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }

                item.remarks?.takeIf { it.isNotBlank() }?.let { remarks ->
                    Text(
                        text = remarks,
                        style = TvText.current.hint,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                    )
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
            Text(
                text = subtitleOf(item),
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 右上角的小角标（「更新中」/「全集」） */
@Composable
private fun CornerBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color)
                .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = TvText.current.hint,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * 一排海报（继续观看/收藏/最近更新这类横向区块）。
 *
 * 空数据直接不渲染标题 —— 免得出现「最近更新」四个字下面一片空白，让人以为加载失败。
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
        // 行尾不露半张卡（用户实测反馈）：按可用宽度算出整数张数，每张宽度均摊。
        // 少半张卡难看，比「固定宽度 + 滚动暗示」重要。
        BoxWithConstraints {
            val spacing = POSTER_SPACING
            val count = columnsFor(maxWidth)
            val cardWidth = (maxWidth - spacing * (count - 1)) / count
            LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                // key 必须稳定：刷新时焦点才不会跳到第一张（RULES §7）
                items(items, key = { it.rowKey() }) { item ->
                    PosterCard(item = item, cardWidth = cardWidth, onClick = { onSelect(item) })
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
        containerColor = Color.Transparent,
        modifier = Modifier.width(CONTINUE_WIDTH),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(CONTINUE_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
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

/** 空态 / 提示块。给不给按钮由 [actionText] 决定（没动作时不留一个假的按钮）。 */
@Composable
private fun HintBlock(
    title: String,
    detail: String?,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (!detail.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = detail,
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
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

// --------------------------------------------------------------------------- //
// 纯逻辑（可在 JVM 上直接测）
// --------------------------------------------------------------------------- //

/** 一个筛选维度：维度名 + 可选值 + 当前选中值。选值回调在页面层按 [key] 分发 */
private data class VodFilter(
    val key: String,
    val label: String,
    val values: List<String>,
    val selected: String?,
)

/**
 * 按可用宽度算一行放几张海报。
 *
 * 为什么是「算」而不是写死：电视盒子从 720p 到 4K、导航栏还会自动收起，
 * 内容区宽度能在 600~780dp 之间变。写死 4 张的话，收起导航后右边会空出一条。
 *
 * 结果不小于 [MIN_COLUMNS]：再窄也别退化成两张 —— 一张海报 250dp 宽时，
 * 一屏只能看到两张，逛起来像在翻相册。
 */
internal fun columnsFor(
    availableWidth: Dp,
    minCardWidth: Dp = MIN_POSTER_WIDTH,
    spacing: Dp = POSTER_SPACING,
): Int {
    if (availableWidth <= minCardWidth) return MIN_COLUMNS
    val fit = ((availableWidth + spacing) / (minCardWidth + spacing)).toInt()
    return fit.coerceAtLeast(MIN_COLUMNS)
}

/**
 * 把列表整体左移 [shift] 位（尾部补到前面），用于「换一换」。
 *
 * 负数与超过一圈的 shift 都要能正确处理：`shuffle` 是累加的，转上十几次就会超过长度，
 * 直接 `drop(shift)` 会抛 `IndexOutOfBounds`。
 */
internal fun <T> rotateBy(
    items: List<T>,
    shift: Int,
): List<T> {
    if (items.size <= 1 || shift == 0) return items
    val size = items.size
    val normalized = ((shift % size) + size) % size
    if (normalized == 0) return items
    return items.drop(normalized) + items.take(normalized)
}

/** 跨源唯一标识：不同源的 `sourceKey` 会撞，必须带上 sourceId（见 VodSummary 的注释） */
private fun VodSummary.rowKey(): String = "$sourceId-$sourceKey"

/** 卡片副信息：年份 · 地区，都没有时退回类型名（不留一行空白） */
private fun subtitleOf(item: VodSummary): String {
    val parts =
        listOfNotNull(
            item.year?.toString(),
            item.area?.takeIf { it.isNotBlank() },
        )
    return parts.joinToString(" · ").ifBlank { item.type.displayName }
}

/** 右上角角标：从更新备注里认出「更新中 / 全集」，认不出就不放（宁缺勿滥） */
private fun cornerTagOf(remarks: String?): CornerTag? {
    val text = remarks?.trim().orEmpty()
    if (text.isEmpty()) return null
    return when {
        text.contains("更新") || text.contains("连载") -> CornerTag("更新中", BADGE_UPDATE)
        text.startsWith("全") || text.contains("完结") -> CornerTag("全集", BADGE_COMPLETE)
        else -> null
    }
}

private data class CornerTag(
    val text: String,
    val color: Color,
)

private const val FILTER_YEAR = "year"

private const val FILTER_AREA = "area"

private const val FILTER_TAG = "tag"

/** 海报最小宽度：小于这个值，客厅 2.5m 外的片名就看不清了 */
private val MIN_POSTER_WIDTH = 132.dp

/** 网格间距 */
private val POSTER_SPACING = 12.dp

/** 一行最少几张 */
private const val MIN_COLUMNS = 3

/** 海报 2:3 通行比例 */
private const val POSTER_ASPECT = 1.5f

/** 「换一换」一次跳过几行：一屏能看到的行数 */
private const val GRID_ROWS_PER_PAGE = 2

/** 继续观看卡：16:9 */
private val CONTINUE_WIDTH = 244.dp

private val CONTINUE_HEIGHT = 138.dp

/** 取值面板宽度 */
private val SHEET_WIDTH = 720.dp

/**
 * 角标配色。
 *
 * 这两个颜色不跟主题走：它们压在**用户源里的图片**上，主题色在浅色模式下是深蓝，
 * 放在海报角上会跟「已下架」的观感混起来。红橙=正在更新、蓝=已完结，
 * 与主流平台的约定一致（用户不用学）。
 */
private val BADGE_UPDATE = Color(0xFFFF6A00)

private val BADGE_COMPLETE = Color(0xFF3A8DDE)
