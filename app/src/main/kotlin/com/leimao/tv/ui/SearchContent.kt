package com.leimao.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leimao.tv.AppContainer
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.search.SearchOutcome
import com.leimao.tv.feature.search.SearchScreen
import com.leimao.tv.feature.search.SearchUiState
import com.leimao.tv.feature.search.opensVodDetail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 搜索页的接线（SEARCH-01 → 界面）。
 *
 * 只做三件事：把 DataStore 里的模式与历史读成状态、把关键词喂给 `SearchRepository`、
 * 把「点了哪一条」翻成一个导航动作。没有业务判断 —— 白名单在仓库出口、口径在 core:model，
 * 这里连一次类型判断都不该有（除了「点频道还是点影视」这件事，它由 `opensVodDetail` 回答）。
 *
 * [autoFocus] 与首页其它分区一致：主页里的内容区由导航管焦点，独立页面自己抢。
 */
@Composable
internal fun SearchContent(
    container: AppContainer,
    onOpenChannel: (Long) -> Unit,
    onOpenVod: (Long, String) -> Unit,
    autoFocus: Boolean = true,
) {
    val mode by container.settings.mode.collectAsStateWithLifecycle(initialValue = AppMode.LIVE)
    // 板块决定搜哪些库（SEARCH-02/04）：直播模式走白名单，影视模式放开九类
    val scope = if (mode == AppMode.LIVE) SearchScope.IPTV else SearchScope.VOD
    val history by container.settings.searchHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val scopeLauncher = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }

    // 打字防抖：遥控器输入法每敲一个字都会触发一次 onValueChange，不去抖等于「一个词打八个字发八轮请求」
    LaunchedEffect(query, scope) {
        if (query.isBlank()) {
            outcome = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        container.searchRepository.search(query, scope).collect { outcome = it }
    }

    SearchScreen(
        state =
            SearchUiState(
                query = query,
                scope = scope,
                outcome = outcome,
                history = history,
            ),
        onQueryChange = { query = it },
        // 输入法上按「搜索」：把词记进历史，但不改结果（结果早就在跟着输入走了）
        onSubmit = { scopeLauncher.launch { container.settings.rememberSearchQuery(query) } },
        onSelectHistory = { query = it },
        onRemoveHistory = { scopeLauncher.launch { container.settings.forgetSearchQuery(it) } },
        onClearHistory = { scopeLauncher.launch { container.settings.clearSearchHistory() } },
        onSelectHit = { hit ->
            // 点了结果才算「这个搜索有用」，这时记历史：只记打了一半的半截词没有意义
            scopeLauncher.launch { container.settings.rememberSearchQuery(query) }
            if (hit.opensVodDetail()) {
                hit.sourceKey?.let { onOpenVod(hit.sourceId, it) }
            } else {
                hit.channelId?.let(onOpenChannel)
            }
        },
        // SEARCH-08 的出口：切到影视板块接着搜同一个词。切板块会落到 DataStore，
        // 主页的导航也跟着变成影视 —— 这是同一个事实的两处显示，不该有两个真相
        onSearchInVodScope = { scopeLauncher.launch { container.settings.setMode(AppMode.VOD) } },
        autoFocus = autoFocus,
    )
}

/** 防抖窗口：短到「松手就出结果」，长到能吞掉连续输入 */
private const val SEARCH_DEBOUNCE_MS = 250L
