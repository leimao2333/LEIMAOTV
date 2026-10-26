package com.leimao.tv.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.leimao.tv.core.designsystem.components.TvKeypad
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.focus.focusRing
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.search.SearchHit
import com.leimao.tv.core.model.search.SearchKind

/**
 * 搜索页（SEARCH-03 / 05 / 07 / 08）。
 *
 * 一屏三块，从上到下：
 * 1. **输入框**：遥控器按 OK 弹输入法（TV 上获得焦点就该弹，没有「点一下」这个动作）；
 * 2. **结果区**：直播频道 / 影视两段，同一张网格，段头占满一行；
 * 3. **状态行**：正在搜什么、哪条源没查完 —— 不转圈，也不静默。
 *
 * 三个刻意的决定：
 *
 * 1. **初始焦点在输入框，不在第一条结果**。功能清单里 SEARCH-03 写的是「焦点默认落频道区第一条」，
 *    但结果随输入逐帧补入：一边打字一边被抢焦点，等于每敲一个字都要重新找光标。所以这里
 *    进页面先给输入框，按 ↓ 即进结果区（第一条就在最前面）。
 * 2. **空态要等 `searching == false`**。结果是一帧帧来的，收到第一帧空就报「没搜到」，
 *    慢源回来时又突然冒出结果 —— 用户只会觉得这功能在抽风。
 * 3. **两段之间不画分割线，只用一个段头**。1080p 下本来就挤，一条线换不来任何信息。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelectHistory: (String) -> Unit,
    onSelectCandidate: (String) -> Unit = {},
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSelectHit: (SearchHit) -> Unit,
    onSearchInVodScope: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val inputFocus = remember { FocusRequester() }

    // 首帧还没布局完时 requestFocus 会打空，等一帧再要焦点（与影视首页同一套做法）
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { inputFocus.requestFocus() }
    }

    // 自绘九宫格默认展开（SEARCH-06）：TV 上「能打字」是可用性的底线，
    // 收起只是为了给结果腾地方，所以给一个显式开关而不是藏起来
    var keypadVisible by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "搜索",
                style = TvText.current.display,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.width(16.dp))
            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                onSubmit = onSubmit,
                focusRequester = inputFocus,
                showIme = !keypadVisible,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            FocusableCard(
                onClick = { keypadVisible = !keypadVisible },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (keypadVisible) "收起键盘" else "键盘",
                    style = TvText.current.label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = scopeHintFor(state.scope),
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // 候选词紧贴输入框、在键盘**上面**：短屏（411dp）上放在键盘下面会被挤出可视区，
        // 用户打完字什么提示都看不到 —— 这正是「写半天没有提示词」的直接原因。
        //
        // 高度**固定**：候选来了又走时，键盘不能跟着上下跳 —— 一跳，手指/方向键就落在别的键上
        // （实测：打 `bh` 有候选、再打一个字没候选，整块键盘弹起一行，后面全按错）。
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(CANDIDATE_ROW_HEIGHT)) {
            if (state.candidates.isNotEmpty()) {
                CandidateRow(candidates = state.candidates, onSelect = onSelectCandidate)
            }
        }

        if (keypadVisible) {
            Spacer(modifier = Modifier.height(10.dp))
            TvKeypad(query = state.query, onQueryChange = onQueryChange)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.showHistory ->
                    HistoryGrid(
                        history = state.history,
                        onSelect = onSelectHistory,
                        onRemove = onRemoveHistory,
                        onClear = onClearHistory,
                    )

                !state.hasQuery ->
                    Placeholder(text = "输入片名或频道名开始搜索。上次搜过的词会留在这里，按 OK 再搜一次。")

                // 手里这一帧不是当前关键词的（防抖中 / 旧帧还在路上），别摆一个空结果区冒充「没搜到」
                state.current == null -> Placeholder(text = "正在搜索…")

                state.noResult -> EmptyResult(state = state, onSearchInVodScope = onSearchInVodScope)

                else -> ResultGrid(state = state, onSelectHit = onSelectHit)
            }
        }

        state.statusText?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 中文候选词（SEARCH-06）。
 *
 * 为什么必须有：九宫格打出来的是字母，用户看不到自己打了什么字 ——
 * 打完 `hnws` 屏幕上一个中文都没有，那这个键盘就等于在盲打。
 * 这里把命中的中文名横排出来，按 OK 直接换成中文词去搜。
 */

/** 候选词行高：固定占位，避免候选出现/消失时整块键盘跳动 */
private val CANDIDATE_ROW_HEIGHT = 44.dp

@Composable
private fun CandidateRow(
    candidates: List<String>,
    onSelect: (String) -> Unit,
) {
    // LazyRow 而不是 Row：候选多了要能横向滚（Row 会一路挤出屏幕外，后面的点不到）
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "candidate-label") {
            Text(
                text = "试试",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(candidates, key = { it }) { candidate ->
            FocusableCard(
                onClick = { onSelect(candidate) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = candidate,
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 输入框。
 *
 * 用 [BasicTextField] 而不是自绘一个「显示关键词的卡片」：自绘的拿不到输入法，
 * 而 RULES §6.1 要求功能能用遥控器完成 —— 遥控器上打字只能靠输入法（TV 盒子的输入法
 * 通常自带拼音候选，方向键可选词）。
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    /** 自绘键盘展开时不弹系统输入法：两层键盘叠在一起，焦点会打架 */
    showIme: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        // 自绘键盘展开时设成只读：**可编辑**的 BasicTextField 一获得焦点，系统输入法就会自己弹出来，
        // 和自绘键盘叠在一起（实测：搜索页一进来 Gboard 就盖住右半屏）。
        // 只读不影响自绘键盘写入 —— 那条路走的是 onQueryChange，不经过输入法
        readOnly = !showIme,
        textStyle = TvText.current.title.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions =
            KeyboardActions(
                onSearch = {
                    onSubmit()
                    keyboard?.hide()
                },
            ),
        modifier =
            modifier
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused && showIme) keyboard?.show()
                },
        decorationBox = { inner ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .focusRing(focused)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "片名 / 频道名",
                        style = TvText.current.title,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}

/**
 * 结果区：一张网格装两段（SEARCH-03）。
 *
 * 为什么不拆成「两行 + 两个 LazyRow」：竖向空间只有一屏，两个横向列表各自滚自己的，
 * 用户永远不知道「影视那一段下面还有没有东西」。一张纵向网格只有一个滚动方向，
 * 遥控器上下走到底就是全部结果。
 */
@Composable
private fun ResultGrid(
    state: SearchUiState,
    onSelectHit: (SearchHit) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.channelHits.isNotEmpty()) {
            item(key = "header-channel", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(title = "直播频道", count = state.channelHits.size)
            }
            items(state.channelHits, key = { it.key }) { hit ->
                ChannelHitCard(hit = hit, onClick = { onSelectHit(hit) })
            }
        }

        if (state.vodHits.isNotEmpty()) {
            item(key = "header-vod", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(title = "影视", count = state.vodHits.size)
            }
            items(state.vodHits, key = { it.key }) { hit ->
                VodHitCard(hit = hit, onClick = { onSelectHit(hit) })
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count 条",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 频道卡：横排「台标 + 台名 + 分组·源名」。台标做 44dp 就够了，电视上认台靠的是字 */
@Composable
private fun ChannelHitCard(
    hit: SearchHit,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoThumb(url = hit.posterUrl, fallback = hit.title, size = 40.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = hit.title,
                    style = TvText.current.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.subtitle?.let {
                    Text(
                        text = it,
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 影视卡：2:3 海报 + 片名 + 一行副信息。副信息里带着年份/备注，同名不同版才分得清 */
@Composable
private fun VodHitCard(
    hit: SearchHit,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val poster = hit.posterUrl
                if (poster.isNullOrBlank()) {
                    Text(
                        text = hit.title.take(1),
                        style = TvText.current.display,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = hit.title,
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            hit.subtitle?.let {
                Text(
                    text = it,
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LogoThumb(
    url: String?,
    fallback: String,
    size: Dp,
) {
    Box(
        modifier =
            Modifier
                .width(size)
                .height(size)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            // 没有台标时用首字占位：电视距离下，「央」「湖」比一块灰底好认得多
            Text(
                text = fallback.take(1),
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 搜索历史（SEARCH-07）。
 *
 * 每条是「词 + 删除按钮」两个焦点目标，而不是「长按删除」：
 * 长按在遥控器上不是标准动作，把它当唯一入口等于这条功能只有一部分人能用（RULES §6.1）。
 */
@Composable
private fun HistoryGrid(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "history-header", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "最近搜索",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        items(history, key = { "history-$it" }) { query ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusableCard(
                    onClick = { onSelect(query) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = query,
                        style = TvText.current.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                FocusableCard(
                    onClick = { onRemove(query) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "删",
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "history-clear", span = { GridItemSpan(maxLineSpan) }) {
            FocusableCard(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "清空搜索历史",
                    style = TvText.current.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 无结果引导（SEARCH-08）。
 *
 * 直播板块的空态必须解释「是口径挡住，不是源里没有」，并给一个能按的出口 ——
 * 只解释不给出路，用户只能自己想起来「哦我要先去切模式」。
 */
@Composable
private fun EmptyResult(
    state: SearchUiState,
    onSearchInVodScope: () -> Unit,
) {
    val hint = state.emptyHint
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "${hint.title}「${state.query.trim()}」",
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = hint.detail,
            style = TvText.current.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hint.action?.let { label ->
            Spacer(modifier = Modifier.height(20.dp))
            FocusableCard(
                onClick = onSearchInVodScope,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Text(
                    text = label,
                    style = TvText.current.label,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = text,
            style = TvText.current.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 结果是不是影视条目 —— 点击后走哪条路只由这个判断决定，别在调用处再写一遍 kind 判断 */
fun SearchHit.opensVodDetail(): Boolean = kind == SearchKind.VOD
