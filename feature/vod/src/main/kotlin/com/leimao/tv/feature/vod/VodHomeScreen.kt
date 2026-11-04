package com.leimao.tv.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
 * 影视首页（VOD-03 / VOD-14）。
 *
 * ## 版式：只有上下滚
 *
 * 整页是**一条竖向流**：通栏 Hero → 继续观看 → 区块标题 → 海报网格（一直往下翻）。
 * 没有横向滑动的行 —— 电视上没有触屏，横滑要按十几次右键才看到第 6 张，
 * 而竖着滚一次就是一整屏新内容。
 *
 * ## 推荐是独立栏目
 *
 * 顶部第一个栏目是「推荐」，与电影/电视剧/动漫…并列，**不再混进各类型里**：
 * 以前「为你推荐」是每个类型下面都挂一行，用户切到电影想看片，第一眼却是一堆跨类型的内容 ——
 * 等于把一个栏目塞进了另一个栏目。
 *
 * ## 翻页
 *
 * 类型栏目滚到底会通过 `onLoadMore` 继续要下一页（见 [VodPager]），一直翻到源里没内容为止；
 * 推荐栏目是编好的短列表，不翻页。
 */
@Composable
fun VodHomeScreen(
    state: VodHomeState,
    hero: List<HeroItem>,
    onHeroOpen: (HeroItem) -> Unit,
    onSelectRecommend: () -> Unit,
    onSelectType: (ContentType) -> Unit,
    onSelectYear: (Int?) -> Unit,
    onSelectArea: (String?) -> Unit,
    onSelectTag: (String?) -> Unit,
    onSelectMedia: (VodSummary) -> Unit,
    onResume: (VodContinueItem) -> Unit,
    onLoadMore: () -> Unit,
    onOpenSourceManager: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    /**
     * 内容区入口焦点：由外层的首页交给这里，落到**当前选中的那个栏目**上。
     *
     * 不传的话外层只能把焦点扔进整个内容区，落点是「组内第一个可聚焦项」——
     * 也就是固定在「推荐」上：用户点导航「电影」进来，焦点却停在推荐，
     * 顺手按一下 OK 内容又切回推荐，等于导航点了个寂寞（2026-11-02 模拟器走查）。
     * 焦点落在哪个 tab，就是「你刚才选的是哪个」最直接的提示。
     */
    entryFocus: FocusRequester? = null,
    /**
     * 入口焦点的「第几次请求」。外层每点一次导航项就 +1。
     *
     * 单靠 requester 不够：点「电影」时 section 先变、影视类型后变（中间隔一次
     * LaunchedEffect），外层请求焦点那一刻新的 tab 可能还没组合出来，请求会落在旧的 tab 上。
     * 带上 token 并以 state 为 key，就能在「所选栏目真正落定」的那一帧再校正一次 ——
     * 点几次导航都不会留下「焦点停在推荐」的尾巴。
     */
    entryToken: Int = 0,
) {
    // 没人指定入口时自己攒一个：这个 requester 只挂在「当前选中的栏目」上，
    // 所以请求它就等于「把焦点放到选中的栏目」
    val ownTabFocus = remember { FocusRequester() }
    val firstTab = entryFocus ?: ownTabFocus
    val listState = rememberLazyListState()

    // 「换一换」只是把本地这批往前转一页，不重新请求
    var shuffle by rememberSaveable { mutableStateOf(0) }
    // 换栏目就回到第一页：转了 5 次之后切到电影，第一屏不该是「第 6 页的电影」
    LaunchedEffect(state.selectedType, state.recommend) { shuffle = 0 }

    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstTab.requestFocus() }
    }

    // 外层（首页）要求把焦点交给内容区时，落点由这一层定：当前选中的栏目。
    // key 里带 state 是有意的 —— 见 [entryToken] 的说明，栏目晚一拍落定时要再校正一次。
    LaunchedEffect(entryToken, state.recommend, state.selectedType) {
        if (entryToken == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstTab.requestFocus() }
    }

    val filters =
        remember(state.years, state.areas, state.tags, state.selectedYear, state.selectedArea, state.selectedTag) {
            buildList {
                if (state.years.isNotEmpty()) {
                    add(VodFilter(FILTER_YEAR, "年份", state.years.map { it.toString() }, state.selectedYear?.toString()))
                }
                if (state.areas.isNotEmpty()) {
                    add(VodFilter(FILTER_AREA, "地区", state.areas, state.selectedArea))
                }
                if (state.tags.isNotEmpty()) {
                    add(VodFilter(FILTER_TAG, "标签", state.tags, state.selectedTag))
                }
            }
        }

    var openFilterKey by rememberSaveable { mutableStateOf<String?>(null) }
    val openFilter = filters.firstOrNull { it.key == openFilterKey }

    // 面板打开期间底层不组合（焦点锁），关掉后要把焦点送回刚点的那颗 chip
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

            // 面板打开时不组合底层：树内弹层没有窗口边界，
            // 靠「后面没有可聚焦的东西」才是唯一可靠的焦点锁
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
                        recommend = state.recommend,
                        selectedType = state.selectedType,
                        onSelectRecommend = onSelectRecommend,
                        onSelectType = onSelectType,
                        onShuffle = { shuffle++ },
                        // 推荐是编好的短列表，没有年份/地区/标签可筛
                        filters = if (state.recommend) emptyList() else filters,
                        onOpenFilter = { openFilterKey = it.key },
                        firstTabFocus = firstTab,
                        restoreKey = restoreFilterKey,
                        restoreFocus = filterFocus,
                    )

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val columns = columnsFor(maxWidth)
                        val cardWidth = (maxWidth - POSTER_SPACING * (columns - 1)) / columns

                        // 两个栏目共用同一套「换一换」：推荐换的是候选的排列顺序，
                        // 类型栏目换的是同一批内容的分页 —— 按下去都必须看得见变化。
                        // 之前推荐栏目直接返回 recommendItems，按「换一换」毫无反应
                        //
                        // 先按 state 记住 source：`recommendItems` / `allItems` 都是 getter，
                        // 每读一次就要遍历两遍列表、建两个 Set 做去重。直接写进 remember 的
                        // key 里，等于每次重组都白算一遍（2GB 的 A55 盒子经不起这么花）
                        val source =
                            remember(state) {
                                if (state.recommend) state.recommendItems else state.allItems
                            }
                        val feed =
                            remember(source, shuffle, columns) {
                                // 一页 = 一屏能看到的行数。转一页比转一张像「换了一批」
                                rotateBy(source, shuffle * columns * GRID_ROWS_PER_PAGE)
                            }
                        // 分块结果同样要记住：`chunked` 每次调用都新建 N 个 List，
                        // 而 LazyColumn 只要重新组合就会读它 —— 滚到底部追加一页时，
                        // 前面几百行的分块全被白白重建一遍
                        val feedRows = remember(feed, columns) { feed.chunked(columns) }
                        val continueRows =
                            remember(state.continueWatching, columns) {
                                state.continueWatching.chunked(columns)
                            }

                        LazyColumn(
                            state = listState,
                            // focusGroup：从顶部的栏目条按「下」进内容区时，焦点落在**组内第一个**
                            // 可聚焦项（Hero），而不是按几何距离随便挑一个。没有这个组，上面的固定条
                            // 与这里的海报是两个互不相干的焦点岛，落差一大就会「按下往左跑」
                            modifier = Modifier.fillMaxSize().focusGroup(),
                            contentPadding = PaddingValues(bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            if (hero.isNotEmpty()) {
                                item("hero") { HomeHero(items = hero, onOpen = onHeroOpen) }
                            }

                            // 继续观看只在「推荐」里出现：它是「我的东西」，不属于某个类型的榜单。
                            // 用海报卡 + 底部进度条，不另做一套 16:9 横卡 —— 一页里只该有一种卡
                            if (state.recommend && state.continueWatching.isNotEmpty()) {
                                item("continue-title") { SectionTitle(title = "继续观看") }
                                items(
                                    items = continueRows,
                                    key = { row -> "continue-${row.first().progress.sourceKey}" },
                                    contentType = { "continue-row" },
                                ) { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(POSTER_SPACING)) {
                                        row.forEach { item ->
                                            PosterCard(
                                                item = item.asSummary(),
                                                cardWidth = cardWidth,
                                                progress = item.fraction,
                                                badge = item.episodeLabel,
                                                subtitle = "已看到 ${formatTime(item.progress.positionMs)}",
                                                onClick = { onResume(item) },
                                            )
                                        }
                                    }
                                }
                            }

                            item("feed-title") {
                                SectionTitle(
                                    title = if (state.recommend) "为你推荐" else state.selectedType.displayName,
                                    hint = if (state.loading) "正在更新…" else state.note,
                                )
                            }

                            if (feed.isEmpty()) {
                                item("blank") {
                                    HintBlock(
                                        title = if (state.loading) "正在载入影视内容…" else "这个栏目暂时没有内容",
                                        detail =
                                            if (state.loading) {
                                                null
                                            } else {
                                                state.note ?: "换一个栏目看看，或者到源管理里检查影视源是否可用。"
                                            },
                                        // 空态也要留一颗按钮：这不仅是「给个出口」，还是内容区**唯一
                                        // 可聚焦的东西**。早先这里是纯文字，栏目一空整块内容区就没有
                                        // 焦点目标，从顶部栏目条按「下」焦点只能往外跑（2026-11-02
                                        // 模拟器实测：跑到左侧导航去了）
                                        actionText = if (state.loading) null else "去源管理",
                                        onAction = onOpenSourceManager.takeIf { !state.loading },
                                    )
                                }
                            }

                            items(
                                items = feedRows,
                                key = { row -> "row-${row.first().rowKey()}" },
                                contentType = { "feed-row" },
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

                            // 翻页哨兵：滚到它就说明用户看到底了，顺手要下一页。
                            // 推荐栏目同样要有 —— 它曾经是「编好的短列表」，翻两屏就空，
                            // 用户的原话是「推荐根本不能一直往下翻」（2026-11-02）
                            if (!state.filtering) {
                                item("load-more") {
                                    LoadMoreFooter(
                                        loading = state.loadingMore,
                                        noMore = state.noMore,
                                        failed = state.loadMoreFailed,
                                        // 内容变了就重新判一次「还要不要下一页」：
                                        // 只靠「组合一次」的话，追加的一页没把脚挤出屏幕时会卡住不翻
                                        itemCount = feed.size,
                                        onRetry = onLoadMore,
                                        onVisible = onLoadMore,
                                    )
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
 * 页面顶部的固定条：栏目（推荐 + 八个类型）+ 换一换 + 筛选触发器。
 *
 * 「推荐」与类型**同构**（同一个 [TypeTab]），因为它就是一个栏目，
 * 不是某种「全局开关」—— 长得不一样会让人以为它和别的栏目不是一回事。
 */
@Composable
private fun VodTopBar(
    recommend: Boolean,
    selectedType: ContentType,
    onSelectRecommend: () -> Unit,
    onSelectType: (ContentType) -> Unit,
    onShuffle: () -> Unit,
    filters: List<VodFilter>,
    onOpenFilter: (VodFilter) -> Unit,
    firstTabFocus: FocusRequester,
    restoreKey: String?,
    restoreFocus: FocusRequester,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item("recommend") {
                    TypeTab(
                        text = "推荐",
                        selected = recommend,
                        onClick = onSelectRecommend,
                        focusRequester = if (recommend) firstTabFocus else null,
                    )
                }
                items(VOD_HOME_TYPES, key = { it.name }) { type ->
                    TypeTab(
                        text = type.displayName,
                        selected = !recommend && type == selectedType,
                        onClick = { onSelectType(type) },
                        focusRequester = if (!recommend && type == selectedType) firstTabFocus else null,
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            // 换一换只对类型栏目有意义：推荐是编好的短列表，转它没有意义
            if (!recommend) {
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
 * 选中态用**底色 + 主色文字**，未选中透明：选中的那一项就是「亮起来的一块」。
 * 内边距压到 10dp：九个栏目 + 换一换要放得下（用 14dp 时「纪录片」会被裁成「纪录」）。
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
 * 为什么是整页遮罩而不是行内下拉：面板画在筛选行自己的 Box 里时，
 * 下面后声明的滚动列表会盖在它上面 —— 打开筛选看到的是一排海报压着半截菜单。
 * 放在页面根节点的最后，永远在最上层，也不受滚动视口裁切。
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
// 滚动层：标题 / 海报 / 翻页脚
// --------------------------------------------------------------------------- //

/** 一个区块标题，右边可以挂一句提示（加载中 / 源抓取失败的原因） */
@Composable
private fun SectionTitle(
    title: String,
    hint: String? = null,
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
 * 网格里的一个海报：
 *
 * ```
 * ┌──────────────┐
 * │ 第3集    更新中│  ← 左上角集号 / 右上角状态角标
 * │              │
 * │ 更新至104集   │  ← 左下角更新备注（压一层渐变，保证可读）
 * ├──────────────┤  ← 看了一半的片子在这里画进度条
 * └──────────────┘
 * 片名
 * 2024 · 中国大陆
 * ```
 *
 * 角标与备注压在图上而不是排在图下：图下那块只够一行标题 + 一行副信息，
 * 再塞「更新至第 104 集」会把片名挤成省略号 —— 而片名才是认片的第一依据。
 */
@Composable
private fun PosterCard(
    item: VodSummary,
    cardWidth: Dp,
    onClick: () -> Unit,
    /** 观看进度（0~1）；null = 不画。继续观看的卡片用 */
    progress: Float? = null,
    /** 左上角额外角标（如「第 3 集」）；null = 不画 */
    badge: String? = null,
    /** 覆盖副信息（继续观看要写「已看到 12:34」而不是年份/地区） */
    subtitle: String? = null,
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

                // 底部渐变压暗：源里的封面经常是浅底，不压一层的话白字直接看不见
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))),
                            ),
                )

                cornerTagOf(item.remarks)?.let { tag ->
                    CornerBadge(
                        text = tag.text,
                        color = tag.color,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }

                if (badge != null) {
                    CornerBadge(
                        text = badge,
                        color = Color(0xCC000000),
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
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

                if (progress != null) {
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
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
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
            Text(
                text = subtitle ?: subtitleOf(item),
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 角标（「更新中」/「全集」/集号） */
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
 * 翻页脚（VOD-14）。
 *
 * 它同时是**触发器**：被组合出来就说明用户已经滚到底了，直接要下一页 ——
 * 电视上没有浏览器那套滚动监听，用「滚到底部时它被组合出来」当信号最可靠。
 *
 * 三种状态都要说清楚，否则用户不知道是「在加载」「到底了」还是「出错了」。
 */
@Composable
private fun LoadMoreFooter(
    loading: Boolean,
    noMore: Boolean,
    failed: Boolean,
    /** 当前已有多少条：它变了说明新的一页已经并进来，可以再判一次要不要继续翻 */
    itemCount: Int,
    onRetry: () -> Unit,
    onVisible: () -> Unit,
) {
    LaunchedEffect(itemCount) {
        // 组合即触发。加载中/已到底时调用方自己会忽略，这里不用再判断
        if (!noMore && !failed) onVisible()
    }

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed ->
                FocusableCard(onClick = onRetry) {
                    Text(
                        text = "没加载出来，按 OK 重试",
                        style = TvText.current.body,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

            noMore ->
                Text(
                    text = "没有更多了",
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            loading ->
                Text(
                    text = "正在加载更多…",
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            // 还没轮到它时也给一句：列表末尾不会因为状态切换而跳动
            else ->
                Text(
                    text = "继续往下翻…",
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * 继续观看 → 海报卡。
 *
 * `type` 只能填一个占位值：进度表里没存类型，而卡片的副信息由调用方用
 * `subtitle` 覆盖（「已看到 12:34」），所以这个字段不会显示出来。
 */
private fun VodContinueItem.asSummary(): VodSummary =
    VodSummary(
        sourceId = progress.sourceId,
        sourceKey = progress.sourceKey,
        name = title,
        type = ContentType.MOVIE,
        pic = poster,
    )

/**
 * 按可用宽度算一行放几张海报。
 *
 * 结果不小于 [MIN_COLUMNS]：再窄也别退化成两张 —— 一屏只能看到两张时，逛起来像在翻相册。
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

/** 跨源唯一标识：不同源的 `sourceKey` 会撞，必须带上 sourceId */
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

/** 取值面板宽度 */
private val SHEET_WIDTH = 720.dp

/**
 * 角标配色。不跟主题走：它们压在用户源里的图片上，
 * 红橙=正在更新、蓝=已完结，与主流平台的约定一致。
 */
private val BADGE_UPDATE = Color(0xFFFF6A00)

private val BADGE_COMPLETE = Color(0xFF3A8DDE)
