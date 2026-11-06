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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.components.BufferingOverlay
import com.leimao.tv.core.designsystem.components.BufferingPill
import com.leimao.tv.core.designsystem.components.BufferingPresentation
import com.leimao.tv.core.designsystem.components.REBUFFER_DELAY_MS
import com.leimao.tv.core.designsystem.components.rememberBufferingVisible
import com.leimao.tv.core.designsystem.components.rememberPlayedOnce
import com.leimao.tv.core.designsystem.components.resolveBufferingPresentation
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.player.PlaybackSpeed
import com.leimao.tv.core.player.PlaybackState
import com.leimao.tv.core.player.PlaybackTrack
import com.leimao.tv.core.player.VideoSurfaceHost
import kotlinx.coroutines.delay

/** 播放页的全部动作。抽成一个类而不是七个回调参数：调用点清爽，加动作也不改签名。 */
data class VodPlayerActions(
    val onTogglePause: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSelectEpisode: (Int) -> Unit,
    /** 换到第 N 条线路，按集号对齐、保留当前位置（VOD-10） */
    val onSelectLine: (Int) -> Unit,
    /** 收藏 / 取消收藏（VOD-07） */
    val onToggleFavorite: () -> Unit,
    /** 选中某条音轨 / 字幕轨（PLAY-07）。trackId 原样来自 state.tracks */
    val onSelectTrack: (String) -> Unit,
    /** 关掉字幕（PLAY-07） */
    val onDisableText: () -> Unit,
    val onRetry: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * 播放页当前开着哪个覆盖面板。
 *
 * 用一个枚举而不是两个布尔：两个布尔能出现「选集和线路同时打开」这种画不出来的状态，
 * 而返回键的优先级也得跟着分成四支。
 */
private enum class VodPanel { None, Episodes, Lines, Audio, Text, Speed }

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
    surfaceHost: VideoSurfaceHost?,
    seekStepMs: Long,
    /** 当前生效的播放速度（PLAY-05）：来自设置里的记忆值，随设置变化重组 */
    speed: Float,
    /** 临时改速度（VOD-11 长按快进用）：只改内核，不写设置 —— 松手就恢复，不该被记住 */
    onApplySpeed: (Float) -> Unit,
    /** 从倍速面板选速度（PLAY-05）：写入设置记忆，下次起播还是这个速度 */
    onRememberSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(VodPanel.None) }
    var tick by remember { mutableIntStateOf(0) }

    // 这一集这条线路有没有真的出过画面。决定卡顿时弹「大浮层」还是「小角标」：
    // 起播时黑着屏、需要完整信息；已经看过一段再卡，遮住画面只会更糟。
    // 判定本身在 resolveBufferingPresentation 里，纯函数、有单测钉着
    val preparing = state.playback is PlaybackState.Preparing
    // 「暂停了」不是一个独立状态：内核 READY 之后暂停只是时间不走（见 PlaybackState.Playing.paused）
    val paused = (state.playback as? PlaybackState.Playing)?.paused == true
    val playedOnce =
        rememberPlayedOnce(
            sessionKey = state.episodeIndex to state.lineIndex,
            playing = state.playback is PlaybackState.Playing,
            buffering = state.loadStats.buffering,
        )
    val presentation = resolveBufferingPresentation(state.showLoading, preparing, playedOnce)
    // 可见性在这里算，不放进 if 里 —— 包进 if 会连消失延迟一起撤掉（见 rememberBufferingVisible）
    val startupVisible = rememberBufferingVisible(presentation == BufferingPresentation.Full)
    val pillVisible =
        rememberBufferingVisible(
            presentation == BufferingPresentation.Pill,
            showDelayMs = REBUFFER_DELAY_MS,
            keepMs = 300L,
        )

    val rootFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // 5s 无操作自动收起（与直播页同一个窗口，PLAY-04 的约定）。
    // 面板开着时不计时：用户正在挑集，把控制条收掉是帮倒忙。
    // 暂停时也不收：暂停之后要做的几乎都是「选集 / 换线 / 拖进度」，
    // 控制条自己消失等于让人再按一次键把它叫回来
    LaunchedEffect(controlsVisible, tick, panel, paused) {
        if (!controlsVisible || panel != VodPanel.None || paused) return@LaunchedEffect
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

    LaunchedEffect(panel) {
        if (panel == VodPanel.None) return@LaunchedEffect
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
            panel != VodPanel.None -> panel = VodPanel.None
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
                    if (panel != VodPanel.None) return@onPreviewKeyEvent false
                    // 遥控器上的播放/暂停实体键（KEYCODE_MEDIA_PLAY_PAUSE / PLAY / PAUSE）。
                    // 不少电视盒子只有这一个键，用户按它暂停之后**再按它必然要继续**——
                    // 这条路径原来完全没接，按下去毫无反应（2026-11-06 反馈「暂停不能继续」）
                    if (event.key == Key.MediaPlayPause ||
                        event.key == Key.MediaPlay ||
                        event.key == Key.MediaPause
                    ) {
                        actions.onTogglePause()
                        controlsVisible = true
                        tick++
                        return@onPreviewKeyEvent true
                    }
                    // 控制条收起时，任意键只负责「叫出来」，不执行动作 ——
                    // 否则用户想看一眼进度，结果顺手切走了一集。
                    // **BACK 必须放行**：它是「退出」语义而不是「操作」，吞掉它会让用户
                    // 按返回键永远退不出播放页（收起态按 BACK 被这里吃掉 → 亮控制条 →
                    // 再按 BACK 只是收起控制条 → 死循环，2026-10-25 遥控器实测抓到）
                    if (!controlsVisible) {
                        if (event.key == Key.Back) return@onPreviewKeyEvent false
                        controlsVisible = true
                        tick++
                        return@onPreviewKeyEvent true
                    }
                    // 失败 / 播完时焦点在提示里的按钮上，根节点不插手
                    if (failed != null || state.finished) return@onPreviewKeyEvent false
                    // 控制条开着时的按键要续命，否则用户还在挑「上一集/下一集」，
                    // 5 秒一到整条自己收起来（与直播页同一个坑）
                    tick++
                    false
                },
    ) {
        VideoSurface(surfaceHost = surfaceHost)

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

        // 起播浮层（PLAY-12）：准备 / 连接 / 缓冲三种黑屏，各自说清楚在等什么，
        // 并带上「已加载多少、多快、什么分辨率」—— 一个字节都没来时那行字就是排查入口。
        // **只在这一集还没出过画面时出现**：已经播起来了再盖这张黑卡，等于把剧情挡掉
        BufferingOverlay(
            visible = startupVisible,
            title = state.loadingTitle,
            detail = state.note ?: state.loadingDetail,
            // 点播知道全片时长，能给真实百分比；时长还没解析出来时是 null（扫光条）
            progress = state.bufferFraction,
            modifier = Modifier.align(Alignment.Center),
        )

        // 播放中途卡顿：顶部中央一行「缓冲中 · 已等待 N 秒」，不压暗、不遮中心。
        // 放顶部而不是底部：底部是控制条的地盘，刚卡顿时控制条往往还开着（起播后 5 秒内），
        // 叠在一起既难看又可能挡住「下一集」
        BufferingPill(
            visible = pillVisible,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = PILL_TOP_PADDING),
        )

        // 暂停标记。暂停之后画面就定在那一帧，和「卡死」在屏幕上长得一模一样，
        // 而这个页面上又没有播放/暂停按钮可以看图标 —— 所以必须给一句话。
        // 放正中而不是顶缘：这一刻画面是静止的，正中的字不会挡掉任何正在发生的内容
        if (paused) {
            Text(
                text = "已暂停 · 按 OK 继续",
                style = TvText.current.hint,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }

        if (failed != null) {
            // 「换线路」只在真有第二条线路时出现：内核已经把**同一集在所有线路上的地址**
            // 依次试过了（episodeUrls 组成的降级链），所以走到这里通常是真的都不行 ——
            // 但线路本身可能刚好在这一集上出了差异，给用户一个一键换过去的口子，
            // 比让他在失败页上反复按「重试」有用
            val reason =
                if (state.canSwitchLine) {
                    "${failed.reason}（这一集在各条线路上都没播起来，可以换一条试试）"
                } else {
                    failed.reason
                }
            CenterHint(
                text = reason,
                buttons =
                    buildList {
                        add("重试" to actions.onRetry)
                        if (state.canSwitchLine) add("换线路" to { panel = VodPanel.Lines })
                        add("返回" to actions.onBack)
                    },
            )
        }

        if (state.finished) {
            CenterHint(
                text = "已经播到最后一集了",
                buttons =
                    listOf(
                        "选集" to { panel = VodPanel.Episodes },
                        "返回" to actions.onBack,
                    ),
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
                    // 长按快进（VOD-11）：按住 ←/→ 一段后切 2×，松手回记忆速度。
                    // 只临时生效，不写设置 —— 「按住了两秒」不是用户想改记忆速度的意思
                    rememberedSpeed = speed,
                    onApplySpeed = onApplySpeed,
                )
                Spacer(modifier = Modifier.height(14.dp))
                ControlButtons(
                    state = state,
                    actions = actions,
                    speed = speed,
                    onOpenPanel = { panel = VodPanel.Episodes },
                    onOpenLines = { panel = VodPanel.Lines },
                    onOpenAudio = { panel = VodPanel.Audio },
                    onOpenText = { panel = VodPanel.Text },
                    onOpenSpeed = { panel = VodPanel.Speed },
                    onInteract = { tick++ },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    // 这套控制条的「← → 是快进」不可能自己看出来，必须写在屏幕上
                    text = "进度条上 ← → 快进快退 · 长按 2× 快放 · OK 暂停/继续 · 返回 收起控制条",
                    style = TvText.current.hint,
                    color = Color(0xFF8A9AA6),
                )
            }
        }

        when (panel) {
            VodPanel.Episodes ->
                EpisodePanel(
                    state = state,
                    firstFocus = panelFocus,
                    onSelect = { index ->
                        panel = VodPanel.None
                        actions.onSelectEpisode(index)
                    },
                    onDismiss = { panel = VodPanel.None },
                )

            VodPanel.Lines ->
                LinePanel(
                    state = state,
                    firstFocus = panelFocus,
                    onSelect = { index ->
                        panel = VodPanel.None
                        actions.onSelectLine(index)
                    },
                    onDismiss = { panel = VodPanel.None },
                )

            VodPanel.Audio ->
                TrackPanel(
                    title = "音轨",
                    hint = "选了立刻生效，不用重新起播",
                    tracks = state.audioTracks,
                    // 音轨没有「关闭」这一项：关掉音轨只剩画面，不是一个有人会要的选项
                    offLabel = null,
                    offSelected = false,
                    firstFocus = panelFocus,
                    onSelect = { id ->
                        panel = VodPanel.None
                        actions.onSelectTrack(id)
                    },
                    onOff = null,
                    onDismiss = { panel = VodPanel.None },
                )

            VodPanel.Text ->
                TrackPanel(
                    title = "字幕",
                    hint = "内嵌字幕，来自片源本身",
                    tracks = state.textTracks,
                    offLabel = "关闭字幕",
                    offSelected = state.textDisabled,
                    firstFocus = panelFocus,
                    onSelect = { id ->
                        panel = VodPanel.None
                        actions.onSelectTrack(id)
                    },
                    onOff = {
                        panel = VodPanel.None
                        actions.onDisableText()
                    },
                    onDismiss = { panel = VodPanel.None },
                )

            VodPanel.Speed ->
                SpeedPanel(
                    current = speed,
                    firstFocus = panelFocus,
                    onSelect = { value ->
                        panel = VodPanel.None
                        onRememberSpeed(value)
                    },
                    onDismiss = { panel = VodPanel.None },
                )

            VodPanel.None -> Unit
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
private fun VideoSurface(surfaceHost: VideoSurfaceHost?) {
    if (surfaceHost == null) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF101820), Color(0xFF05080A)))),
        )
        return
    }

    // 与直播页同构：换内核必须重建 View（factory 只跑一次），离场必须解绑（防泄漏/原生崩溃）
    key(surfaceHost.surfaceToken) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> surfaceHost.createSurfaceView(context) },
            onRelease = { view -> surfaceHost.releaseSurfaceView(view) },
        )
    }
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
    rememberedSpeed: Float,
    onApplySpeed: (Float) -> Unit,
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
                        when {
                            // 长按快进（VOD-11）：方向键按住不放（系统开始连发 KeyDown）时
                            // 切到 2×，松手（KeyUp）回记忆速度。按一下的 seek 照旧 ——
                            // 「点按是挪，按住是跑」和打字时的退格是同一种手感
                            event.type == KeyEventType.KeyDown &&
                                (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) &&
                                event.nativeKeyEvent.repeatCount >= LONG_PRESS_REPEAT_THRESHOLD -> {
                                onApplySpeed(FAST_SPEED)
                                true
                            }

                            event.type == KeyEventType.KeyUp &&
                                (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) -> {
                                onApplySpeed(rememberedSpeed)
                                false
                            }

                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                                onSeekBy(-seekStepMs)
                                true
                            }

                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
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
    speed: Float,
    onOpenPanel: () -> Unit,
    onOpenLines: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenText: () -> Unit,
    onOpenSpeed: () -> Unit,
    onInteract: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // 按钮行是按键的直系祖先：焦点进了按钮之后，根节点那份 preview 收不到事件
        // （KeyInput 节点挂在 `focusable()` 的内侧，按钮子树不在它的下游）。
        // 在这里补一刀，保证「正在挑按钮」时控制条不会自己消失
        modifier =
            Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) onInteract()
                false
            },
    ) {
        ControlButton(text = "上一集", enabled = state.hasPrevious, onClick = actions.onPrevious)
        ControlButton(text = "下一集", enabled = state.hasNext, onClick = actions.onNext)
        ControlButton(text = "选集", enabled = state.episodeCount > 0, onClick = onOpenPanel)
        // 只有一条线路时按钮直接**不出现**（不是变灰）：大量片子本来就一条线路，
        // 一个永远点不动的按钮会被当成 bug
        if (state.canSwitchLine) {
            ControlButton(text = "线路", enabled = true, onClick = onOpenLines)
        }
        // 音轨 / 字幕同样「没有就不显示」：绝大多数直播流与不少片子根本没有这两样，
        // 常驻两个点进去空空的菜单等于教用户别信控制条
        if (state.canPickAudio) {
            ControlButton(text = "音轨", enabled = true, onClick = onOpenAudio)
        }
        if (state.hasTextTracks) {
            ControlButton(
                text = if (state.textDisabled) "字幕·关" else "字幕",
                enabled = true,
                onClick = onOpenText,
            )
        }
        // 倍速（PLAY-05）常驻：点播看囤剧时 2× 是刚需，没有「没有倍速」的片子
        ControlButton(
            text = if (speed == 1f) "倍速" else "倍速 $speed×",
            enabled = true,
            onClick = onOpenSpeed,
        )
        ControlButton(
            text = if (state.favorited) "已收藏" else "收藏",
            enabled = true,
            onClick = actions.onToggleFavorite,
        )
        ControlButton(text = "返回", enabled = true, onClick = actions.onBack)
    }
}

/**
 * 倍速面板（PLAY-05）。
 *
 * 选中即写入记忆：用户选 1.5× 说明他想**接下来也用 1.5×**，否则他每次起播都得
 * 再按一遍 —— 「记住」就是这个功能与一个临时调速按钮的全部区别。
 */
@Composable
private fun SpeedPanel(
    current: Float,
    firstFocus: FocusRequester,
    onSelect: (Float) -> Unit,
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
                    text = "播放速度",
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "选了会记住，下次起播还是这个速度",
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(items = PlaybackSpeed.entries.toList()) { option ->
                    val selected = option.value == current
                    FocusableCard(
                        onClick = { onSelect(option.value) },
                        focusRequester = if (selected) firstFocus else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = option.label,
                                style = TvText.current.body,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (selected) {
                                Text(
                                    text = "正在用",
                                    style = TvText.current.hint,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
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

/**
 * 线路面板（VOD-10）。
 *
 * 详情页也能切线路，但**播到一半发现这条线路模糊/卡顿**时再退回详情页、再点进来，
 * 进度就断了 —— 这个面板解决的正是那一步。所以它必须：
 * ① 按集号对齐（不是回到第 1 集）；② 保留当前位置（由调用方起播时带 startPositionMs）。
 *
 * 竖排一列而不是网格：线路通常 2~5 条，一列读下来比找格子快，也容得下「线路名 + 集数」。
 */
@Composable
private fun LinePanel(
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
                    text = "线路",
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "换线后仍停在第 ${state.episodeIndex + 1} 集，进度不丢",
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(items = state.lines, key = { "line-${it.index}" }) { line ->
                    val selected = line.index == state.lineIndex
                    FocusableCard(
                        onClick = { onSelect(line.index) },
                        focusRequester = if (selected) firstFocus else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = line.label,
                                style = TvText.current.body,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "${line.episodeCount} 集",
                                style = TvText.current.hint,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (selected) {
                                Text(
                                    text = "正在用",
                                    style = TvText.current.hint,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 音轨 / 字幕面板（PLAY-07）。
 *
 * 与选集、线路共用同一个覆盖层外壳，但内容是「平铺一列」而不是网格：轨道最多五六条，
 * 用网格反而要横着找。字幕多一项「关闭字幕」——它不是一条轨道（见
 * `PlayerController.disableTracks`），所以单独作为列表首项。
 *
 * 初始焦点落在**当前选中**的那一条上，而不是第一行：用户打开面板十次有九次是
 * 「看看现在用的是哪条」，落点在哪，说明就在哪。
 */
@Composable
private fun TrackPanel(
    title: String,
    hint: String,
    tracks: List<PlaybackTrack>,
    offLabel: String?,
    offSelected: Boolean,
    firstFocus: FocusRequester,
    onSelect: (String) -> Unit,
    onOff: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    // 焦点落点：选中项优先，都没有就第一条。落在「关闭字幕」上只有一种情况 ——
    // 字幕本来就是关的
    val focusOff = offLabel != null && offSelected
    val focusTrackId = tracks.firstOrNull { it.selected }?.id ?: tracks.firstOrNull()?.id

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
                    text = title,
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = hint,
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (offLabel != null && onOff != null) {
                    item(key = "track-off") {
                        TrackRow(
                            label = offLabel,
                            selected = offSelected,
                            onClick = onOff,
                            focusRequester = if (focusOff) firstFocus else null,
                        )
                    }
                }
                items(items = tracks, key = { it.id }) { track ->
                    TrackRow(
                        label = track.label,
                        selected = track.selected,
                        onClick = { onSelect(track.id) },
                        focusRequester =
                            if (!focusOff && track.id == focusTrackId) firstFocus else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    FocusableCard(
        onClick = onClick,
        focusRequester = focusRequester,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = TvText.current.body,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (selected) {
                Text(
                    text = "正在用",
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 居中的一句话 + 若干出口。给不给按钮由调用方决定，不留假按钮 */
@Composable
private fun CenterHint(
    text: String,
    buttons: List<Pair<String, () -> Unit>> = emptyList(),
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
        // 字幕状态（PLAY-07）：切过字幕之后顶部得能看出「现在用的是哪条」。
        // 关掉字幕是用户主动干的，也要写出来 —— 否则下一次他会以为字幕自己没了
        state.textStatus?.let { add(it) }
        // 换线时集号被钳制（甲 40 集 → 乙 39 集）这类事必须说出来：
        // 用户只看到数字变了，会以为自己按错了集
        state.note?.takeIf { it.isNotBlank() }?.let { add(it) }
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

/** 长按判定（VOD-11）：系统连发第 2 个 KeyDown（repeatCount ≥ 1）就算按住，再快点按不到这里 */
private const val LONG_PRESS_REPEAT_THRESHOLD = 1

/** 长按快进的临时速度 */
private const val FAST_SPEED = 2f

private const val HUD_FADE_MS = 150

private const val PANEL_COLUMNS = 8

/** 卡顿角标离画面顶边的距离：压得太靠上会被电视的 overscan 切掉一截 */
private val PILL_TOP_PADDING = 24.dp
