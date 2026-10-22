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
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.layout.SafeAreaContainer
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.epg.EpgProgramme
import com.leimao.tv.core.model.epg.buildEpgTimeline
import com.leimao.tv.core.player.PlaybackState
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
     * 当前频道的节目单（时间窗内的，已排序）。节目单网格（LIVE-22）直接用这一份，
     * 打开时不再查一次库 —— 顶部「正在播 / 下一档」用的也是它，两处同一个数据源就不会打架。
     */
    val epgProgrammes: List<EpgProgramme> = emptyList(),
)

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
 * | 菜单 | 收藏 / 取消收藏 | 收藏 / 取消收藏 |
 * | 返回 | 回主页 | 先收起控制条 |
 *
 * 为什么方向键要分两套含义：既要「按一下就换台」（§6.5 的直播语义），
 * 又要「控制条里能选按钮」。折中点是**控制条是用户的显式动作** ——
 * 它一旦出现，就说明用户正在操作 UI 而不是在看节目。
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    videoPlayer: Player?,
    onSwitchChannel: (Int) -> Unit,
    onSelectChannel: (Long) -> Unit,
    onSwitchLine: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onRetry: () -> Unit,
    onOpenSourceManager: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var epgOpen by remember { mutableStateOf(false) }
    var numberBuffer by remember { mutableStateOf("") }
    var interactionTick by remember { mutableIntStateOf(0) }

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
            epgOpen -> epgOpen = false
            drawerOpen -> drawerOpen = false
            controlsVisible -> controlsVisible = false
            else -> onBack()
        }
    }

    val playback = state.playback
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
                        onToggleFavorite = onToggleFavorite,
                        onDigit = { digit -> numberBuffer = (numberBuffer + digit).take(MAX_NUMBER_DIGITS) },
                    )
                },
    ) {
        VideoSurface(player = videoPlayer)

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
                Spacer(modifier = Modifier.weight(1f))

                if (isFailed) {
                    FailurePanel(
                        state = playback,
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
                        onOpenSourceManager = onOpenSourceManager,
                    )
                } else if (hudVisible && !isFailed && !state.loadingChannels && !drawerOpen) {
                    Text(
                        // 按键提示只在控制条收起、HUD 还亮着的时候露出：
                        // 控制条里已经有按钮了，再并排一行说明只会抢注意力；
                        // HUD 退掉后它就是纯噪音，必须跟着一起消失
                        text = "↑↓ 换台　数字键 输入频道号　←→ 切线路　OK 控制条　菜单 收藏",
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
                // 直播不裁画面：宁可留黑边，也不要把台标和字幕切掉
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { view -> view.player = player },
    )
}

@Composable
private fun PlayerTopBar(state: PlayerUiState, numberBuffer: String) {
    val playback = state.playback
    val lineIndex =
        when (playback) {
            is PlaybackState.Playing -> playback.lineIndex
            is PlaybackState.Preparing -> playback.lineIndex
            is PlaybackState.Failed -> playback.lineIndex
            PlaybackState.Idle -> 0
        }
    val lineCount =
        when (playback) {
            is PlaybackState.Playing -> playback.lineCount
            is PlaybackState.Preparing -> playback.lineCount
            is PlaybackState.Failed -> playback.lineCount
            PlaybackState.Idle -> 1
        }

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
        FocusableCard(onClick = onOpenSourceManager) {
            Text(text = "源管理", style = TvText.current.label)
        }
    }
}

@Composable
private fun FailurePanel(
    state: PlaybackState.Failed,
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
            text = "这个台暂时播不了",
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

@Composable
private fun ChannelDrawer(
    state: PlayerUiState,
    onSelect: (Channel) -> Unit,
) {
    val firstChannelFocus = remember { FocusRequester() }

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
                ChannelBrowser(
                    groups = state.groups,
                    selectedChannelId = state.channel?.id,
                    onChannelClick = onSelect,
                    favoriteIds = state.favoriteIds,
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
    onToggleFavorite: () -> Unit,
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

        Key.Menu -> {
            onToggleFavorite()
            true
        }

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
