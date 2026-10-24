package com.leimao.tv.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.vod.VodEpisode
import com.leimao.tv.core.model.vod.VodProgress

/**
 * 影视详情页（VOD-04）：简介、线路、选集、倒序，以及「继续观看」（VOD-06）。
 *
 * 版面用一个 [LazyVerticalGrid] 兜住整页，而不是「外层滚动 + 内层网格」——
 * 后者在电视上有个致命问题：焦点走到网格边界时，方向键会被内层吃掉，页面滚不动，
 * 用户会以为卡住了。整页一个网格，上下键天然贯通。
 *
 * 集数用 8 列小格而不是海报：一部剧 40 集，海报要滚六七屏才能看到最后，
 * 而用户在第 3 屏就已经想换成「直接给我第 20 集」了。
 *
 * 看过一半的片子，进页面第一眼该看到的是**继续观看**而不是第 1 集 —— 所以有进度时
 * 初始焦点落在续播按钮上，没有时才落到第一集（见下面的 LaunchedEffect）。
 */
@Composable
fun VodDetailScreen(
    state: VodDetailState,
    onRetry: () -> Unit,
    onSelectLine: (Int) -> Unit,
    onToggleReverse: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onResume: (VodProgress) -> Unit,
    onOpenSourceManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstEpisode = remember { FocusRequester() }
    val resumeButton = remember { FocusRequester() }

    // 切线路后选集整批换掉，焦点会掉到无处可落。等一帧再要，与首页 chip 同一套做法。
    // 依赖 lineIndex / ready / 有无进度：三者任一变化都意味着「该停的地方变了」。
    // 不依赖 reversed：切倒序时用户正停在倒序按钮上，这时抢焦点是打断而不是帮忙。
    LaunchedEffect(state.lineIndex, state.ready, state.progress) {
        if (!state.ready) return@LaunchedEffect
        withFrameNanos { }
        val target = if (state.progress != null) resumeButton else firstEpisode
        runCatching { target.requestFocus() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(EPISODE_COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            DetailHeader(state = state)
        }

        when (state.status) {
            VodDetailStatus.LOADING ->
                fullSpan {
                    DetailHint(
                        title = "正在读取详情…",
                        detail = null,
                        actionText = null,
                        onAction = null,
                    )
                }

            VodDetailStatus.NO_SOURCE ->
                fullSpan {
                    DetailHint(
                        title = "这个影视源已经不可用了",
                        detail = "源被删除或停用了，这一页没法自己恢复。去源管理里看看，或者返回换一个源的内容。",
                        actionText = "去源管理",
                        onAction = onOpenSourceManager,
                    )
                }

            VodDetailStatus.GONE ->
                fullSpan {
                    DetailHint(
                        title = "源里已经找不到这部片子",
                        detail = "可能是下架了，也可能换了编号。回首页看看同类型的其他内容。",
                        actionText = null,
                        onAction = null,
                    )
                }

            VodDetailStatus.FAILED ->
                fullSpan {
                    DetailHint(
                        title = "详情没能读出来",
                        detail = state.note ?: "网络或对方接口的问题，稍后重试。",
                        actionText = "重试",
                        onAction = onRetry,
                    )
                }

            VodDetailStatus.READY -> {
                state.progress?.let { progress ->
                    fullSpan {
                        ResumeRow(
                            progress = progress,
                            onResume = { onResume(progress) },
                            focusRequester = resumeButton,
                        )
                    }
                }

                if (state.lines.size > 1) {
                    fullSpan {
                        Column {
                            SectionLabel("线路")
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.lines.forEachIndexed { index, line ->
                                    LineChip(
                                        // 源没给线路名时兜底成「线路 N」，不能让用户点一个空格子
                                        text = line.name.ifBlank { "线路 ${index + 1}" },
                                        selected = index == state.lineIndex,
                                        onClick = { onSelectLine(index) },
                                    )
                                }
                            }
                        }
                    }
                }

                fullSpan {
                    EpisodeToolbar(
                        count = state.episodes.size,
                        reversed = state.reversed,
                        onToggleReverse = onToggleReverse,
                    )
                }

                if (state.episodes.isEmpty()) {
                    fullSpan {
                        DetailHint(
                            title = "这条线路没有播放地址",
                            detail = "换一条线路试试，或者过一会儿再来。",
                            actionText = null,
                            onAction = null,
                        )
                    }
                } else {
                    itemsIndexed(
                        items = state.episodes,
                        // key 用**原始下标**而不是展示下标：切倒序时同一个 key 还在，
                        // 焦点就会留在同一集上，而不是跳到第一格
                        key = { index, _ -> "${state.lineIndex}-${state.originalIndexAt(index)}" },
                    ) { index, episode ->
                        EpisodeChip(
                            episode = episode,
                            selected = index == state.shownEpisodeIndex,
                            onClick = { onSelectEpisode(index) },
                            focusRequester = if (index == 0) firstEpisode else null,
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- //
// 组件
// --------------------------------------------------------------------------- //

/** 头部：海报 + 标题 / 副信息 / 演职员 / 简介 */
@Composable
private fun DetailHeader(state: VodDetailState) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Poster(state = state)
        Spacer(modifier = Modifier.width(26.dp))
        Column(modifier = Modifier.widthIn(max = 720.dp)) {
            Text(
                text = state.title.ifBlank { "载入中…" },
                style = TvText.current.display,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val meta = state.metaLine
            if (meta.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = meta,
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val remarks = state.detail?.summary?.remarks ?: state.summary?.remarks
            if (!remarks.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = remarks,
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            state.detail?.director?.takeIf { it.isNotBlank() }?.let { director ->
                Spacer(modifier = Modifier.height(10.dp))
                PeopleLine(label = "导演", value = director)
            }
            state.detail?.actor?.takeIf { it.isNotBlank() }?.let { actor ->
                Spacer(modifier = Modifier.height(4.dp))
                PeopleLine(label = "主演", value = actor)
            }

            state.detail?.content?.takeIf { it.isNotBlank() }?.let { content ->
                Spacer(modifier = Modifier.height(14.dp))
                DescriptionBlock(content = content)
            }
        }
    }
}

@Composable
private fun Poster(state: VodDetailState) {
    Box(
        modifier =
            Modifier
                .width(POSTER_WIDTH)
                .height(POSTER_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val poster = state.poster
        if (poster.isNullOrBlank()) {
            Text(
                text = state.title.take(1).ifBlank { "影" },
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
}

@Composable
private fun PeopleLine(
    label: String,
    value: String,
) {
    Row {
        Text(
            text = label,
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Text(
            text = value,
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 简介块。默认三行，点一下展开全文、再点收起。
 *
 * 为什么不做成「滚动看全文」：简介在电视上没人会逐字读完，三行够判断「要不要看」；
 * 真想看全的再点开。做成一整块可滚动的文本反而会让方向键在这里失去边界感。
 */
@Composable
private fun DescriptionBlock(content: String) {
    var expanded by remember(content) { mutableStateOf(false) }
    FocusableCard(
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = content,
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (!expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "按 OK 展开简介",
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 工具行：共几集 + 正序/倒序切换 */
@Composable
private fun EpisodeToolbar(
    count: Int,
    reversed: Boolean,
    onToggleReverse: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("选集")
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "共 $count 集",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(18.dp))
        LineChip(text = if (reversed) "倒序" else "正序", selected = false, onClick = onToggleReverse)
    }
}

/**
 * 继续观看（VOD-06）。
 *
 * 只说「第几集 + 看到第几分钟」，不写「播放」两个字：整个按钮就是「从这儿接着看」，
 * 加了动词反而像要跳去另一个页面。右那句小字是给「我就想从头看」的人一个出口 ——
 * 否则他唯一能试的动作是点这个按钮，然后又得自己拖进度条回 0。
 */
@Composable
private fun ResumeRow(
    progress: VodProgress,
    onResume: () -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        FocusableCard(
            onClick = onResume,
            focusRequester = focusRequester,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "继续观看",
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${progress.episodeLabel} · ${formatTime(progress.positionMs)}",
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "想从头看，就在下面的选集里随便点一集",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = TvText.current.title,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/** 线路 / 倒序这类「选了就生效」的小块，与首页筛选 chip 同一套视觉语言 */
@Composable
private fun LineChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    FocusableCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = TvText.current.body,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 一集。
 *
 * 与首页 chip 的区别：这里的宽度由网格决定（不许按文字撑开，否则 40 集排不满整行），
 * 所以文本必须限一行、居中、超长省略。
 */
@Composable
private fun EpisodeChip(
    episode: VodEpisode,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    FocusableCard(
        onClick = onClick,
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = episode.name.ifBlank { "正片" },
            style = TvText.current.hint,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 占满整行的一条提示，避免在 8 列网格里被挤成一列 */
@Composable
private fun DetailHint(
    title: String,
    detail: String?,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
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
                modifier = Modifier.widthIn(max = 620.dp),
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

// 网格的「占满整行」写法要拿到 maxLineSpan，只有 LazyGridScope 里才有，所以包一层
private fun LazyGridScope.fullSpan(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

private const val EPISODE_COLUMNS = 8

/** 三行：够看清「讲的是什么片」，又不至于把选集挤出首屏 */
private const val DESCRIPTION_COLLAPSED_LINES = 3

private val POSTER_WIDTH = 232.dp

/** 2:3 海报比例 */
private val POSTER_HEIGHT = 348.dp
