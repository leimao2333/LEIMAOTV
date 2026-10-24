package com.leimao.tv.feature.vod

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.player.PlaybackState
import kotlinx.coroutines.delay

/** 播放页的全部动作。抽成一个类而不是七个回调参数：调用点清爽，加动作也不改签名。 */
data class VodPlayerActions(
    val onTogglePause: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSelectEpisode: (Int) -> Unit,
    val onRetry: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * 影视播放页（VOD-05）。
 *
 * 与直播播放页分开是一件**有意的重复**：两边控制条几乎没有交集（那边是换台、分组、
 * 收藏、节目单，这边是上一集、下一集、进度、选集）。合成一个页面之后，每个按钮都要
 * 问一句「这个模式该不该出现」，最后得到的是一张到处是 if 的图。
 *
 * 焦点策略：控制条**有焦点**，但第一条是进度条 —— 点播最常用的动作是快进快退，
 * 所以默认焦点落在进度条上，← → 在它上面被拦下来当 seek 用（RULES §6.1：
 * 功能要能用遥控器完成，且要一眼看得出按了会发生什么）。按钮行的方向键照常走焦点。
 */
@Composable
fun VodPlayerScreen(
    state: VodPlayerUiState,
    actions: VodPlayerActions,
    videoPlayer: Player?,
    seekStepMs: Long,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    val rootFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // 5s 无操作自动收起（与直播页同一个窗口，PLAY-04 的约定）。
    // 面板开着时不计时：用户正在挑集，把控制条收掉是帮倒忙。
    LaunchedEffect(controlsVisible, tick, panelOpen) {
        if (!controlsVisible || panelOpen) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    // 控制条一出现就把焦点送到进度条。等一帧：刚组合出来的节点还没 attach，
    // 直接 requestFocus 会抛异常（与直播页同一个坑）
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        withFrameNanos { }
        runCatching { progressFocus.requestFocus() }
    }

    LaunchedEffect(panelOpen) {
        if (!panelOpen) return@LaunchedEffect
        withFrameNanos { }
        runCatching { panelFocus.requestFocus() }
    }

    // 播完最后一集把控制条叫回来：不然画面停住，没有任何东西告诉用户「演完了」
    LaunchedEffect(state.finished) {
        if (state.finished) controlsVisible = true
    }

    // 任何一次操作都重新计时，否则用户按到一半控制条自己消失了
    LaunchedEffect(tick) { if (tick > 0) controlsVisible = true }

    // 返回键分层：选集面板 → 控制条 → 回详情页。
    // 必须**无条件接管**：Compose 的 effect 先子后父，外层路由的 BackHandler 注册得更晚、
    // 优先级更高，有条件让出根本轮不到本页（直播页踩过同一个坑）
    BackHandler {
        when {
            panelOpen -> panelOpen = false
            controlsVisible -> controlsVisible = false
            else -> actions.onBack()
        }
    }

    val failed = state.playback as? PlaybackState.Failed

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // 面板开着时按键全交给面板的焦点系统（放行，不消费）
                    if (panelOpen) return@onPreviewKeyEvent false
                    // 控制条收起时，任意键只负责「叫出来」，不执行动作 ——
                    // 否则用户想看一眼进度，结果顺手切走了一集
                    if (!controlsVisible) {
                        controlsVisible = true
                        tick++
                        return@onPreviewKeyEvent true
                    }
                    // 失败 / 播完时焦点在提示里的按钮上，根节点不插手
                    if (failed != null || state.finished) return@onPreviewKeyEvent false
                    false
                },
    ) {
        VideoSurface(player = videoPlayer)

        // ---- 顶部：片名与集数 ----
        Hud(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Text(
                text = state.title.ifBlank { "影视" },
                style = TvText.current.title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitleOf(state),
                style = TvText.current.hint,
                color = Color(0xFFB0BEC7),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.preparing) {
            CenterHint(text = state.note ?: "正在准备这一集…")
        }

        if (failed != null) {
            CenterHint(
                text = failed.reason,
                primary = "重试" to actions.onRetry,
                secondary = "返回详情" to actions.onBack,
            )
        }

        if (state.finished) {
            CenterHint(
                text = "已经播到最后一集了",
                primary = "选集" to { panelOpen = true },
                secondary = "返回详情" to actions.onBack,
            )
        }

        // ---- 底部控制条 ----
        if (controlsVisible) {
            val bottomAlpha by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(HUD_FADE_MS),
            )
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))),
                        )
                        .alpha(bottomAlpha)
                        .padding(horizontal = 40.dp, vertical = 26.dp),
            ) {
                ProgressRow(
                    state = state,
                    focus = progressFocus,
                    seekStepMs = seekStepMs,
                    onSeekBy = actions.onSeekBy,
                    onTogglePause = actions.onTogglePause,
                )
                Spacer(modifier = Modifier.height(14.dp))
                ControlButtons(state = state, actions = actions, onOpenPanel = { panelOpen = true })
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    // 这套控制条的「← → 是快进」不可能自己看出来，必须写在屏幕上
                    text = "进度条上 ← → 快进快退 · OK 暂停/继续 · 返回 收起控制条",
                    style = TvText.current.hint,
                    color = Color(0xFF8A9AA6),
                )
            }
        }

        if (panelOpen) {
            EpisodePanel(
                state = state,
                firstFocus = panelFocus,
                onSelect = { index ->
                    panelOpen = false
                    actions.onSelectEpisode(index)
                },
                onDismiss = { panelOpen = false },
            )
        }
    }
}

// --------------------------------------------------------------------------- //
// 组件
// --------------------------------------------------------------------------- //

/** 顶部信息条。淡出而不是硬切：硬切在黑画面上会闪一下 */
@Composable
private fun Hud(
    visible: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val alpha by animateFloatAsState(targetValue = if (visible) 1f else 0f, animationSpec = tween(HUD_FADE_MS))
    if (alpha <= 0f) return
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .alpha(alpha)
                .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        content()
    }
}

/** 播放画面。没有内核（占位实现）时给一块安静的渐变，而不是「白屏 + 转圈」 */
@Composable
private fun VideoSurface(player: Player?) {
    if (player == null) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF101820), Color(0xFF05080A)))),
        )
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                // 影视同样不裁画面：字幕被切掉一条边就看不全了
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { view -> view.player = player },
    )
}

/**
 * 进度条 + 时间。
 *
 * 整条是一个可聚焦块，← → 在它上面被拦成 seek。这是「无焦点控制条」与「焦点控制条」
 * 之间的折中：快进不必先把焦点走到某个按钮上再按，而按钮行仍然保留正常的焦点导航。
 *
 * 用宽度比例画而不是 Slider：电视上没有拖拽手势，Slider 的拖动逻辑只会跟方向键打架。
 */
@Composable
private fun ProgressRow(
    state: VodPlayerUiState,
    focus: FocusRequester,
    seekStepMs: Long,
    onSeekBy: (Long) -> Unit,
    onTogglePause: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = formatTime(state.positionMs), style = TvText.current.hint, color = Color.White)
        Spacer(modifier = Modifier.width(14.dp))
        FocusableCard(
            // 聚焦在进度条上按 OK 就是暂停/继续：这是点播里最顺手的一个位置
            onClick = onTogglePause,
            focusRequester = focus,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            modifier =
                Modifier
                    .weight(1f)
                    // 拦截左右键当 seek 用。onPreviewKeyEvent 只有在这个节点持有焦点时才收到，
                    // 所以不会影响按钮行里方向键的正常导航
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onSeekBy(-seekStepMs)
                                true
                            }

                            Key.DirectionRight -> {
                                onSeekBy(seekStepMs)
                                true
                            }

                            else -> false
                        }
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x66FFFFFF)),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(state.progress)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = formatTime(state.durationMs),
            style = TvText.current.hint,
            color = Color(0xFFB0BEC7),
        )
    }
}

@Composable
private fun ControlButtons(
    state: VodPlayerUiState,
    actions: VodPlayerActions,
    onOpenPanel: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ControlButton(text = "上一集", enabled = state.hasPrevious, onClick = actions.onPrevious)
        ControlButton(text = "下一集", enabled = state.hasNext, onClick = actions.onNext)
        ControlButton(text = "选集", enabled = state.episodeCount > 0, onClick = onOpenPanel)
        ControlButton(text = "返回详情", enabled = true, onClick = actions.onBack)
    }
}

@Composable
private fun ControlButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (!enabled) {
        // 不可用的按钮仍然占位但明显变暗：直接不渲染会让整排按钮左右横跳，
        // 用户按到「下一集」的位置时发现下面变成了「返回详情」
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x14FFFFFF))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text = text, style = TvText.current.body, color = Color(0x40FFFFFF))
        }
        return
    }
    FocusableCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text = text, style = TvText.current.body, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 选集面板：40 集靠「下一集」按 39 次太慢，这里给一次性跳过去的能力 */
@Composable
private fun EpisodePanel(
    state: VodPlayerUiState,
    firstFocus: FocusRequester,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xF20B0F12))
                .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "选集",
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "第 ${state.episodeIndex + 1} / ${state.episodeCount} 集",
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                FocusableCard(onClick = onDismiss) {
                    Text(
                        text = "关闭",
                        style = TvText.current.body,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(PANEL_COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = List(state.episodeCount) { it },
                    key = { index, _ -> "episode-$index" },
                ) { index, _ ->
                    val selected = index == state.episodeIndex
                    FocusableCard(
                        onClick = { onSelect(index) },
                        focusRequester = if (selected) firstFocus else null,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "第 ${index + 1} 集",
                            style = TvText.current.hint,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** 居中的一句话 + 至多两个出口。给不给按钮由调用方决定，不留假按钮 */
@Composable
private fun CenterHint(
    text: String,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            Text(
                text = text,
                style = TvText.current.title,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            val buttons = listOfNotNull(primary, secondary)
            if (buttons.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    buttons.forEach { (label, onClick) ->
                        FocusableCard(onClick = onClick) {
                            Text(
                                text = label,
                                style = TvText.current.body,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- //

private fun subtitleOf(state: VodPlayerUiState): String =
    buildList {
        if (state.episodeName.isNotBlank()) add(state.episodeName)
        if (state.episodeCount > 1) add("第 ${state.episodeIndex + 1}/${state.episodeCount} 集")
        if (state.lineCount > 1 && state.lineName.isNotBlank()) add("线路 ${state.lineName}")
        if (state.playback is PlaybackState.Preparing) add("缓冲中…")
    }.joinToString(" · ")

/** 毫秒 → `mm:ss` 或 `h:mm:ss`。超过一小时还用 mm:ss 的话，两小时的电影会显示成 120:31 */
internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private const val CONTROLS_AUTO_HIDE_MS = 5_000L

private const val HUD_FADE_MS = 150

private const val PANEL_COLUMNS = 8
