package com.leimao.tv.feature.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.key.KeyEvent
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
import com.leimao.tv.core.designsystem.components.TvTextField
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.layout.SafeAreaContainer
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.epg.EpgProgramme
import com.leimao.tv.core.model.epg.buildEpgTimeline
import com.leimao.tv.core.player.PlaybackState
import com.leimao.tv.core.player.PlaybackTrack
import com.leimao.tv.core.player.TrackKind
import com.leimao.tv.core.player.VideoSurfaceHost
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 顶部「正在播 / 下一档」（LIVE-20 的展示部分）。
 *
 * 只带名字与时间点，不带节目简介：播放页是「一眼看到」，简介要等用户主动打开详情才给。
 * 时间用 epoch 毫秒而不是格式化好的字符串，是为了让 UI 层决定时区与格式。
 */
data class ProgrammeLine(
    val current: String? = null,
    val currentEndsAt: Long? = null,
    val next: String? = null,
    val nextStartsAt: Long? = null,
) {
    /** 节目单没覆盖这个台时整行不显示 —— 宁可空着，也不要编一条假节目出来 */
    val isEmpty: Boolean get() = current == null && next == null
}

/**
 * 把 [ProgrammeLine] 拼成一行字。
 *
 * 时间格式化器由调用方注入，测试里才能固定时区（否则在 CI 上跑出来的断言会随机器时区飘）。
 */
internal fun ProgrammeLine?.toCaption(
    formatter: SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault()),
): String? {
    if (this == null || isEmpty) return null

    val parts = mutableListOf<String>()
    current?.let { title ->
        parts += currentEndsAt?.let { "正在播 $title ${formatter.format(Date(it))} 结束" } ?: "正在播 $title"
    }
    next?.let { title ->
        parts += nextStartsAt?.let { "下一档 $title ${formatter.format(Date(it))}" } ?: "下一档 $title"
    }
    return parts.joinToString("　·　")
}

/** 播放页要展示的全部状态。UI 不自己算业务，只把它画出来。 */
data class PlayerUiState(
    val playback: PlaybackState = PlaybackState.Idle,
    val channel: Channel? = null,
    val groups: List<ChannelGroup> = emptyList(),
    val favoriteIds: Set<Long> = emptySet(),
    val loadingChannels: Boolean = false,
    val programme: ProgrammeLine? = null,
    /**
     * 最近观看（LIVE-15）：按观看时间倒序的频道，抽屉里作为「最近观看」分组展示。
     * 独立于 groups，因为它不是按分组切片的全集，而是 history 表的一份查询。
     */
    val recentChannels: List<Channel> = emptyList(),
    /**
     * 当前频道的节目单（时间窗内的，已排序）。节目单网格（LIVE-22）直接用这一份，
     * 打开时不再查一次库 —— 顶部「正在播 / 下一档」用的也是它，两处同一个数据源就不会打架。
     */
    val epgProgrammes: List<EpgProgramme> = emptyList(),
    /**
     * 当前流的可选轨道（PLAY-07）。
     *
     * 直播里大多数台没有可选轨道，所以两个按钮都是「有才显示」。
     * 真正有的是这些：体育赛事的多语解说（粤语 / 英语）、部分亚洲台的
     * 双语伴音、以及少数带内嵌字幕的源。
     */
    val tracks: List<PlaybackTrack> = emptyList(),
) {
    val audioTracks: List<PlaybackTrack> get() = tracks.filter { it.kind == TrackKind.AUDIO }

    val textTracks: List<PlaybackTrack> get() = tracks.filter { it.kind == TrackKind.TEXT }

    /** 只有一条音轨时不显示按钮 —— 与「换线路」同一个口径 */
    val canPickAudio: Boolean get() = audioTracks.size > 1

    val hasTextTracks: Boolean get() = textTracks.isNotEmpty()

    val textDisabled: Boolean get() = textTracks.isNotEmpty() && textTracks.none { it.selected }
}

/**
 * 直播播放页（M1）。
 *
 * 按键映射严格按 RULES §6.5，且**控制条可见与否会改变方向键的含义**：
 *
 * | 键 | 控制条隐藏 | 控制条可见 |
 * |---|---|---|
 * | ↑ / ↓ | 立刻换台（不弹控制条） | 在控制条按钮间移动焦点 |
 * | ← / → | 切线路 | 同上 |
 * | 数字键 | 输入频道号（2s 内无输入自动跳转） | 输入频道号 |
 * | OK | 呼出控制条 | 触发当前焦点按钮 |
 * | 菜单 | 全局菜单（搜索 / 源管理 / 设置 / 退出，HOME-08） | 同左 |
 * | 返回 | 回主页 | 先收起控制条 |
 *
 * 为什么方向键要分两套含义：既要「按一下就换台」（§6.5 的直播语义），
 * 又要「控制条里能选按钮」。折中点是**控制条是用户的显式动作** ——
 * 它一旦出现，就说明用户正在操作 UI 而不是在看节目。
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    surfaceHost: VideoSurfaceHost?,
    onSwitchChannel: (Int) -> Unit,
    onSelectChannel: (Long) -> Unit,
    onSwitchLine: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectTrack: (String) -> Unit,
    onDisableTracks: (TrackKind) -> Unit,
    onRetry: () -> Unit,
    onOpenSourceManager: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var epgOpen by remember { mutableStateOf(false) }

    /** 打开着的轨道面板（PLAY-07）：null = 没开 */
    var trackPanel by remember { mutableStateOf<TrackKind?>(null) }
    var numberBuffer by remember { mutableStateOf("") }
    var interactionTick by remember { mutableIntStateOf(0) }

    // 自动跳台（LIVE-25 第 3 层）：全部线路失败时自动换下一个台，而不是立刻弹失败面板。
    // 计数跨重组保留；成功播放一次清零；连续跳到上限才把失败面板交还给用户。
    var autoSkipCount by remember { mutableIntStateOf(0) }
    // 轻提示「该台不可用，已切到 X」：2s 淡出，不抢焦点、不打断播放
    var skipToast by remember { mutableStateOf<String?>(null) }

    // HUD（台名 / 分组 / 线路 / 按键提示）必须跟控制条一样会自己消失。
    // 常驻等于在画面上钉死两行字：看电视时永远是干扰，亮画面上还会糊成一片。
    // 初始为 true 是让刚进页面的人知道自己在看哪个台，随后自己退掉。
    var hudVisible by remember { mutableStateOf(true) }
    var hudTick by remember { mutableIntStateOf(0) }

    val rootFocus = remember { FocusRequester() }
    val firstControlFocus = remember { FocusRequester() }

    // 每个页面必须显式指定初始焦点（RULES §6.2 第 4 条）
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    // PLAY-04：控制条 5s 无操作自动隐藏
    LaunchedEffect(controlsVisible, interactionTick) {
        if (!controlsVisible) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    // 控制条一出现就把焦点送进第一个按钮。
    //
    // 不做这一步的话焦点仍然停在根节点上，而根节点没有任何可移动的邻居 ——
    // 方向键过去了什么也不发生，OK 也触发不了按钮，用户只会得出「按钮是死的」这个结论。
    // 等一帧再请求：控制条刚组合出来时节点还没 attach，直接 requestFocus 会抛异常。
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstControlFocus.requestFocus() }
    }

    // HUD 用同一个窗口：亮起来之后 5s 没人按键就退掉。
    // 抽屉开着时不计时 —— 那时候屏幕上是频道列表在跟台名抢位置，HUD 本来就不显示。
    LaunchedEffect(hudVisible, hudTick, drawerOpen) {
        if (!hudVisible || drawerOpen) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        hudVisible = false
    }

    // 换台之后必须让 HUD 再亮一次：用户按 ↑↓ 时最想知道的就是「现在这是什么台」，
    // 如果 HUD 已经退掉了，换台就成了全凭记忆的盲操作
    LaunchedEffect(state.channel?.id) {
        hudVisible = true
        hudTick++
    }

    // 数字键 2s 超时（LIVE-11）
    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isEmpty()) return@LaunchedEffect
        delay(NUMBER_INPUT_TIMEOUT_MS)
        val number = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (number != null) {
            val target = state.groups.flatMap { it.channels }.firstOrNull { it.number == number }
            if (target != null) onSelectChannel(target.id)
        }
    }

    // 返回键的第一层含义（PLAY-04 / §6.5）：节目单、抽屉、控制条开着时先收它们。
    //
    // 这里必须**无条件接管**（enabled 恒为 true），把「无可收时回主页」也收进来：
    // Compose 的 effect 按先子后父执行，路由层（外层）的 BackHandler 注册得更晚、
    // 优先级更高 —— 有条件接管（enabled=false 时让出）根本轮不到本页，
    // 实测返回键会直接跳过网格/抽屉回主页。
    BackHandler {
        when {
            trackPanel != null -> trackPanel = null
            epgOpen -> epgOpen = false
            drawerOpen -> drawerOpen = false
            controlsVisible -> controlsVisible = false
            else -> onBack()
        }
    }

    val playback = state.playback
    // 自动跳台（LIVE-25）：失败时先试下一个台，连续失败到上限才落失败面板。
    // 依赖整个 playback 对象而不是 isFailed 布尔 —— 布尔在「失败→换台→再失败」里不变化，
    // 会漏掉第二次失败；整个对象每次状态迁移都是新实例，effect 一定重跑。
    LaunchedEffect(playback) {
        when (playback) {
            is PlaybackState.Playing -> autoSkipCount = 0

            is PlaybackState.Failed -> {
                if (autoSkipCount < AUTO_SKIP_MAX) {
                    autoSkipCount++
                    // 轻提示带上「切到哪个台」：只知道「切了」不知道「切到哪」等于没说
                    skipToast = "该台不可用，已切到下一个台（连续 $autoSkipCount 个）"
                    onSwitchChannel(1)
                }
            }

            else -> Unit
        }
    }

    // 轻提示 2s 淡出：不抢焦点、不打断播放，只挂一句说明
    LaunchedEffect(skipToast) {
        if (skipToast == null) return@LaunchedEffect
        delay(SKIP_TOAST_MS)
        skipToast = null
    }

    val isFailed = playback is PlaybackState.Failed

    // 控制条一出现，HUD 就跟着亮：用户已经在操作 UI 了，这时候藏台名只会让人更迷惑。
    // 反过来说，控制条收起后 HUD 仍由自己的计时器管，两者不共享状态。
    val showHud = hudVisible || controlsVisible

    // 淡入淡出而不是硬切：硬切在黑画面上会闪一下，观感很差
    val hudAlpha by animateFloatAsState(
        targetValue = if (showHud && !drawerOpen) 1f else 0f,
        animationSpec = tween(durationMillis = HUD_FADE_MS),
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // 节目单网格开着时，按键全部交给网格里的焦点系统。
                    //
                    // 这里必须返回 false（放行）而不是 true（消费）：网格里「左右移动」
                    // 靠的就是焦点系统，一旦在预览阶段把方向键吞掉，表现就是「按左右没反应」
                    // —— 第一版就是这么写的，实测确实一动不动。
                    // 放行也不会误触发放映页的动作：下面的 handleKey 不再调用，换台与切线路都不会发生；
                    // 焦点走到最后一个块之后事件自然没人处理，什么也不做才是对的。
                    // 轨道面板与节目单同理：面板里上下选轨道靠焦点系统，方向键必须放行
                    if (trackPanel != null) {
                        if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                            trackPanel = null
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }

                    if (epgOpen) {
                        // BACK 键不会自动进 OnBackPressedDispatcher（焦点在网格卡片上时，
                        // 实测系统返回被 Compose 的按键链吃掉，BackHandler 收不到）——
                        // 所以在这里显式拦下，行为与 ESC（直调 dispatcher）保持一致。
                        if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                            epgOpen = false
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }

                    // 任何一次按键都算「用户在操作」：HUD 立刻亮起来，计时器归零
                    if (event.type == KeyEventType.KeyDown) {
                        hudVisible = true
                        hudTick++
                    }
                    handleKey(
                        event = event,
                        controlsVisible = controlsVisible,
                        drawerOpen = drawerOpen,
                        failurePanelVisible = isFailed,
                        onShowControls = {
                            controlsVisible = true
                            interactionTick++
                        },
                        onHideControls = { controlsVisible = false },
                        onCloseDrawer = { drawerOpen = false },
                        onBack = onBack,
                        onSwitchChannel = onSwitchChannel,
                        onSwitchLine = onSwitchLine,
                        onDigit = { digit -> numberBuffer = (numberBuffer + digit).take(MAX_NUMBER_DIGITS) },
                    )
                },
    ) {
        VideoSurface(surfaceHost = surfaceHost)

        // 文字压在画面上必须有自己的衬底：真实源的画面又亮又白（台标、公告页），
        // 白字直接叠上去等于看不见。上下各压一层渐变，中间完全透明，不挡画面。
        // 衬底跟着 HUD 一起淡出：HUD 都收起来了还压着一层黑边，等于画面被永久截了一块
        if (hudAlpha > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(hudAlpha)
                        .background(
                            Brush.verticalGradient(
                                // 顶边压到 94% 黑、14% 处还在 60%，24% 之后完全透明：
                                // 台名与状态就落在最上面这几行，白字下面必须是深底才读得出来
                                0f to Color(0xF0000000),
                                0.14f to Color(0x99000000),
                                0.24f to Color(0x00000000),
                                0.72f to Color(0x00000000),
                                0.90f to Color(0x99000000),
                                1f to Color(0xF0000000),
                            ),
                        ),
            )
        }

        // 画面之上的信息层：顶部台名 + 状态，底部控制条，右侧频道抽屉
        SafeAreaContainer {
            Column(modifier = Modifier.fillMaxSize()) {
                // 抽屉打开时把台名与提示行收起来：抽屉是半透明的，两层文字叠在一起
                // 会出现「频道列表」压着台名的重影（模拟器上实拍确认过）。
                // HUD 退掉时整块也一起走，只留画面
                if (showHud && !drawerOpen) {
                    Box(modifier = Modifier.alpha(hudAlpha)) {
                        PlayerTopBar(state = state, numberBuffer = numberBuffer)
                    }
                }

                // 轻提示（LIVE-25）：自动跳台时的「已切到 X」，右上角 2s 淡出。
                // 不抢焦点、不打断播放 —— 它不是面板，只是浮在画面角落的一句话
                skipToast?.let { message ->
                    Text(
                        text = message,
                        style = TvText.current.body,
                        color = Color(0xFFFFEB3B),
                        modifier =
                            Modifier
                                .align(Alignment.End)
                                .padding(end = 24.dp, top = 8.dp)
                                .background(Color(0xB3000000), RoundedCornerShape(8.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))

                if (isFailed) {
                    FailurePanel(
                        state = playback,
                        autoSkippedCount = autoSkipCount.takeIf { it >= AUTO_SKIP_MAX },
                        onRetry = onRetry,
                        onSwitchLine = { onSwitchLine(1) },
                        onNextChannel = { onSwitchChannel(1) },
                        onOpenSourceManager = onOpenSourceManager,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (state.loadingChannels) {
                    Text(
                        text = "正在载入频道…（最多等 10 秒，之后会给重试与去源管理的入口）",
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 失败面板自己就带着重试/换线路/下一个台/去源管理，再叠一条控制条只会更乱，
                // 而且两条按钮行会抢焦点
                if (controlsVisible && !isFailed) {
                    ControlBar(
                        state = state,
                        firstFocus = firstControlFocus,
                        onOpenDrawer = {
                            drawerOpen = true
                            controlsVisible = false
                        },
                        onOpenEpg = {
                            epgOpen = true
                            controlsVisible = false
                        },
                        onSwitchLine = onSwitchLine,
                        onToggleFavorite = onToggleFavorite,
                        onOpenAudio = { trackPanel = TrackKind.AUDIO },
                        onOpenText = { trackPanel = TrackKind.TEXT },
                        onOpenSourceManager = onOpenSourceManager,
                    )
                } else if (hudVisible && !isFailed && !state.loadingChannels && !drawerOpen) {
                    Text(
                        // 按键提示只在控制条收起、HUD 还亮着的时候露出：
                        // 控制条里已经有按钮了，再并排一行说明只会抢注意力；
                        // HUD 退掉后它就是纯噪音，必须跟着一起消失
                        text = "↑↓ 换台　数字键 输入频道号　←→ 切线路　OK 控制条　菜单 全局菜单",
                        style = TvText.current.hint,
                        color = Color(0x99FFFFFF),
                        modifier = Modifier.alpha(hudAlpha),
                    )
                }
            }
        }

        if (drawerOpen) {
            ChannelDrawer(
                state = state,
                onSelect = { channel ->
                    drawerOpen = false
                    onSelectChannel(channel.id)
                },
            )
        }

        trackPanel?.let { kind ->
            TrackOverlay(
                kind = kind,
                state = state,
                onSelect = { id ->
                    trackPanel = null
                    onSelectTrack(id)
                },
                onDisable = {
                    trackPanel = null
                    onDisableTracks(kind)
                },
                onDismiss = { trackPanel = null },
            )
        }

        // 节目单网格盖在最上层：它是一块完整的横向时间轴，和抽屉不同时出现
        if (epgOpen) {
            // 窗口起点按「打开这一刻」算：上一轮打开过又关了，再打开要从现在重新铺，
            // 而不是接着上次那个已经过去十分钟的窗口
            val timeline =
                remember(state.epgProgrammes) {
                    buildEpgTimeline(state.epgProgrammes, System.currentTimeMillis())
                }
            EpgGridOverlay(
                channelName = state.channel?.name.orEmpty(),
                timeline = timeline,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }
}

/** 播放画面。没有内核（占位实现）时给一块安静的渐变，而不是「白屏 + 转圈」。 */
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

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context -> surfaceHost.createSurfaceView(context) },
    )
}

@Composable
private fun PlayerTopBar(state: PlayerUiState, numberBuffer: String) {
    val playback = state.playback
    // 线路位置在 PlaybackState 接口上，不必再按状态分支取值
    val lineIndex = playback.lineIndex
    val lineCount = playback.lineCount

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = state.channel?.name ?: if (state.loadingChannels) "正在载入频道" else "暂无可播放的频道",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.channel?.let { channel ->
                    Text(
                        text = channel.groupName,
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (lineCount > 1) {
                    Text(
                        // 多线路时把当前线路露出来，用户才知道 ←→ 按了有没有反应
                        text = "线路 ${lineIndex + 1}/$lineCount",
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 节目单那一行是可选的：没导入节目单、或者这份节目单不覆盖这个台，
            // 就整行不出现，而不是显示「暂无节目信息」占一行位置
            state.programme.toCaption()?.let { caption ->
                Text(
                    text = caption,
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (numberBuffer.isNotEmpty()) {
            Text(
                // 数字输入的即时反馈：用户按了 1 就得看到 1，而不是等 2 秒后才知道跳哪
                text = "跳转：$numberBuffer",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ControlBar(
    state: PlayerUiState,
    firstFocus: FocusRequester,
    onOpenDrawer: () -> Unit,
    onOpenEpg: () -> Unit,
    onSwitchLine: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenText: () -> Unit,
    onOpenSourceManager: () -> Unit,
) {
    val isFavorite = state.channel?.let { state.favoriteIds.contains(it.id) } == true
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xCC1A1A1A))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusableCard(onClick = onOpenDrawer, modifier = Modifier.focusRequester(firstFocus)) {
            Text(text = "频道", style = TvText.current.label)
        }
        FocusableCard(onClick = onOpenEpg) {
            Text(text = "节目单", style = TvText.current.label)
        }
        FocusableCard(onClick = onToggleFavorite) {
            Text(text = if (isFavorite) "★ 已收藏" else "☆ 收藏", style = TvText.current.label)
        }
        FocusableCard(onClick = { onSwitchLine(1) }) {
            Text(text = "换线路", style = TvText.current.label)
        }
        // 音轨 / 字幕：没有就不显示。一个点进去空空的菜单比没有按钮更让人怀疑坏了
        if (state.canPickAudio) {
            FocusableCard(onClick = onOpenAudio) {
                Text(text = "音轨", style = TvText.current.label)
            }
        }
        if (state.hasTextTracks) {
            FocusableCard(onClick = onOpenText) {
                Text(
                    text = if (state.textDisabled) "字幕·关" else "字幕",
                    style = TvText.current.label,
                )
            }
        }
        FocusableCard(onClick = onOpenSourceManager) {
            Text(text = "源管理", style = TvText.current.label)
        }
    }
}

@Composable
private fun FailurePanel(
    state: PlaybackState.Failed,
    /** 自动跳台跳了多少个台（>= AUTO_SKIP_MAX 时文案改成「连续 N 个台不可用」） */
    autoSkippedCount: Int? = null,
    onRetry: () -> Unit,
    onSwitchLine: () -> Unit,
    onNextChannel: () -> Unit,
    onOpenSourceManager: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xE6101010))
                .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                if (autoSkippedCount != null) {
                    "连续 $autoSkippedCount 个台不可用"
                } else {
                    "这个台暂时播不了"
                },
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${state.reason}　·　已尝试 ${state.lineCount} 条线路",
            style = TvText.current.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FocusableCard(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                Text(text = "重试", style = TvText.current.label)
            }
            if (state.canSwitchLine) {
                FocusableCard(onClick = onSwitchLine) {
                    Text(text = "换线路", style = TvText.current.label)
                }
            }
            FocusableCard(onClick = onNextChannel) {
                Text(text = "下一个台", style = TvText.current.label)
            }
            FocusableCard(onClick = onOpenSourceManager) {
                Text(text = "去源管理", style = TvText.current.label)
            }
        }
    }
}

/**
 * 音轨 / 字幕面板（PLAY-07）。
 *
 * 做成右下的浮层而不是整屏：直播是「正在看的东西」，切个音轨不该让画面整个消失 ——
 * 而选集那种整屏面板在点播里是对的（选完就不看了）。这里保持画面可见，
 * 用户能一边听一边换。
 */
@Composable
private fun TrackOverlay(
    kind: TrackKind,
    state: PlayerUiState,
    onSelect: (String) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tracks = if (kind == TrackKind.AUDIO) state.audioTracks else state.textTracks
    val firstFocus = remember { FocusRequester() }

    // 初始焦点落在当前选中那条：打开面板十次有九次是「看看现在用的是哪条」
    val focusId = tracks.firstOrNull { it.selected }?.id ?: tracks.firstOrNull()?.id
    LaunchedEffect(kind, tracks.size) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 48.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier =
                Modifier
                    .width(360.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xF2101418))
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (kind == TrackKind.AUDIO) "音轨" else "字幕",
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                FocusableCard(onClick = onDismiss) {
                    Text(text = "关闭", style = TvText.current.hint)
                }
            }
            if (kind == TrackKind.TEXT) {
                FocusableCard(onClick = onDisable) {
                    Text(
                        text = "关闭字幕",
                        style = TvText.current.body,
                        color =
                            if (state.textDisabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }
            tracks.forEach { track ->
                FocusableCard(
                    onClick = { onSelect(track.id) },
                    focusRequester = if (track.id == focusId) firstFocus else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track.label,
                            style = TvText.current.body,
                            color =
                                if (track.selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (track.selected) {
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

@Composable
private fun ChannelDrawer(
    state: PlayerUiState,
    onSelect: (Channel) -> Unit,
) {
    val firstChannelFocus = remember { FocusRequester() }
    var filterQuery by remember { mutableStateOf("") }
    // 即时过滤（LIVE-16）：输入即筛，空关键词等价于不过滤。过滤后的分组喂给浏览器。
    val visibleGroups = remember(state.groups, filterQuery) { filterGroups(state.groups, filterQuery) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xE6000000)),
    ) {
        SafeAreaContainer {
            Column(modifier = Modifier.fillMaxHeight()) {
                Text(
                    text = "频道列表",
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                // 过滤框放在分组栏上方：TV 上按名称过滤是最快的找台方式，
                // 尤其是几百上千个台的时候，比逐组翻找省得多。
                TvTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    label = "按名称过滤",
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ChannelBrowser(
                    groups = visibleGroups,
                    selectedChannelId = state.channel?.id,
                    onChannelClick = onSelect,
                    favoriteIds = state.favoriteIds,
                    recentChannels = state.recentChannels,
                    modifier = Modifier.weight(1f),
                    initialFocusRequester = firstChannelFocus,
                )
            }
        }
    }
}

/**
 * 播放页按键分发。返回 true 表示事件已被消费。
 *
 * 抽成普通函数是为了能单独读：这里的每一条分支都对应 RULES §6.5 表格里的一格。
 *
 * [failurePanelVisible] 为 true 时，OK 与 ←→ 一律**不消费**，让焦点系统把事件交给面板按钮：
 * 失败面板自带「换线路」，此时 ←→ 再拿去切线路就等于把「重试/下一个台/去源管理」三个出口
 * 全堵死了 —— 遥控器上根本没有别的手段能按到它们。
 */
private fun handleKey(
    event: KeyEvent,
    controlsVisible: Boolean,
    drawerOpen: Boolean,
    failurePanelVisible: Boolean,
    onShowControls: () -> Unit,
    onHideControls: () -> Unit,
    onCloseDrawer: () -> Unit,
    onBack: () -> Unit,
    onSwitchChannel: (Int) -> Unit,
    onSwitchLine: (Int) -> Unit,
    onDigit: (Char) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    // 抽屉打开时只接管返回：方向键留给列表选择
    if (drawerOpen) {
        if (event.key == Key.Back) {
            onCloseDrawer()
            return true
        }
        return false
    }

    return when (event.key) {
        Key.Back -> {
            if (controlsVisible) {
                // 控制条可见时返回键先收起它，不要一路退回主页（PLAY-04）
                onHideControls()
            } else {
                onBack()
            }
            true
        }

        // 菜单键不在这里处理：它在 Activity 层被拦截，统一呼出全局菜单（HOME-08）。
        // 收藏入口保留在控制条按钮里，语义不变、位置更明确。

        Key.DirectionUp, Key.DirectionDown -> {
            if (controlsVisible) {
                false
            } else {
                onSwitchChannel(if (event.key == Key.DirectionUp) -1 else 1)
                true
            }
        }

        Key.DirectionLeft, Key.DirectionRight -> {
            if (controlsVisible) {
                false
            } else {
                onSwitchLine(if (event.key == Key.DirectionLeft) -1 else 1)
                true
            }
        }

        Key.DirectionCenter, Key.Enter -> {
            if (controlsVisible) {
                false
            } else {
                onShowControls()
                true
            }
        }

        else -> {
            val digit = digitOf(event)
            if (digit == null) {
                false
            } else {
                onDigit(digit)
                true
            }
        }
    }
}

/** 只认主键盘与小键盘的 0~9；其余按键一律不拦，免得抢走控件自己的处理。 */
private fun digitOf(event: KeyEvent): Char? =
    when (event.key) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }

/** 控制条自动隐藏时间（PLAY-04）：太短会让人按不到，太长会挡住画面 */
private const val CONTROLS_AUTO_HIDE_MS = 5_000L

/** 数字键换台的等待窗口（LIVE-11）：给用户按完两位数的机会 */
private const val NUMBER_INPUT_TIMEOUT_MS = 2_000L

/** HUD 淡出时长：够慢才看不出是「闪一下」，太快反而更刺眼 */
private const val HUD_FADE_MS = 180

/** 频道号最多四位数，够用且不会让提示条撑破 */
private const val MAX_NUMBER_DIGITS = 4

/**
 * 自动跳台上限（LIVE-25）：连续这么多台都播不起来，就停手、把失败面板交还给用户。
 * 再往下跳没有意义 —— 用户需要知道「是这一片源都坏了」而不是「应用卡住了一直换台」。
 */
private const val AUTO_SKIP_MAX = 3

/** 轻提示「已切到 X」的停留时长：够看清、不碍事 */
private const val SKIP_TOAST_MS = 2_000L
