package com.leimao.tv.core.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Media3 默认内核（PLAY-01 / PLAY-02 / PLAY-03 / PLAY-08）。
 *
 * 降级链在这里落地，顺序是固定的（RULES §6.6 实现约束 3）：
 *
 * ```
 * 同线路重连 N 次 → 换下一条线路（重连次数重置）→ 线路用尽 → Failed（UI 给三个出口）
 * ```
 *
 * 关于「软解兜底」：这一层先用 Media3 的 [DefaultRenderersFactory.setEnableDecoderFallback]
 * —— 硬解初始化失败时自动退到系统软解。系统软解也没有的冷门编码，交给
 * [FallbackPlayerController] 在整份请求失败后唤起 [VlcPlayerController]（D-02 已落地）。
 * 三层各管一段，不要在某一路里重复做别人的事。
 */
class Media3PlayerController(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val retryLimit: Int = PLAYBACK_RETRY_LIMIT,
    private val bufferProfile: BufferProfile = BufferProfile.BALANCED,
) : PlayerController,
    VideoSurfaceHost {
    /**
     * 「这条线路回过响应头没有」的探针。
     *
     * 挂在**播放专用**的 client 上（不是外面那份）：只观测播放请求，
     * 后台刷新订阅源的响应不该被当成「这条线路是活的」。
     */
    private val networkMonitor = PlaybackNetworkMonitor()

    /** 播放实际用的 client：在外部那份基础上只多一层观测，连接池与 Dispatcher 都继承 */
    private val playbackClient: OkHttpClient =
        okHttpClient.newBuilder().addInterceptor(networkMonitor).build()

    /** 当前这条线路的开始时刻（elapsedRealtime），判「有没有响应」的基准 */
    private var lineStartedAt = 0L

    /**
     * 本轮已经「回到直播边缘」重来了几次。
     *
     * 只在直播用：HLS 的滑动窗口会随着我们重连/换线而继续走，理论上可能一直踩到，
     * 所以给个上限；到上限就老老实实按失败处置，别把用户关在无限重选里。
     */
    private var liveRealigns = 0

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow<List<PlaybackTrack>>(emptyList())
    override val tracks: StateFlow<List<PlaybackTrack>> = _tracks.asStateFlow()

    /**
     * 加载统计（PLAY-12）：黑屏那几秒到底在干什么。
     *
     * 数据来自两处：字节数/分辨率/解码器由 [LoadStatsTracker] 在 AnalyticsListener 里累加，
     * 缓冲位置与 BUFFERING 标志由 [statsTicker] 每 500ms 读一次播放器。
     * 不放进 AnalyticsListener 里算，是因为 `bufferedPosition` 不是事件，只能在轮询里读。
     */
    private val _loadStats = MutableStateFlow(LoadStats.None)
    override val loadStats: StateFlow<LoadStats> = _loadStats.asStateFlow()

    private val loadTracker = LoadStatsTracker(_loadStats)

    /**
     * 本次播放里「一个字节都没回」的线路数（2026-11-04 冷启动收敛）。
     *
     * 到 [DEAD_LINE_LIMIT] 就认定不是单条线路的问题，直接落失败面板，不再逐条试完。
     */
    private var deadLines = 0

    // ---- 反复缓冲的自动换线（PLAY-13）----

    /** 这一次起播是否已经出过画面。出画之前的 BUFFERING 是「起播」，不是「卡」 */
    private var renderedOnce = false

    /** 最近几次「卡住」的时刻（elapsedRealtime）。只保留窗口内的 */
    private val rebuffers = ArrayDeque<Long>()

    /** 本轮已经自动换过几次线：几条线都不行时不能无限跳 */
    private var autoSwitchCount = 0

    /** 自动换线后的静默期：这之前的 BUFFERING 是我们自己造成的，不计入统计 */
    private var suppressRebufferUntil = 0L

    /**
     * 连续几个统计周期判「这条线路喂不饱这路流」（2026-11-06）。
     *
     * 判据本身是纯函数 [isLineStarved]，这里只负责计数：**连续**才是证据，
     * 中间只要有一秒不饿就清零（广告/静止镜头掉码率是正常现象）。
     */
    private var starvedTicks = 0

    /**
     * 这次起播里「协议根本解不了」跳过了几条（2026-11-06）。
     *
     * 只用于防死循环：聚合源里 `rtsp://` 的线路往往连续挂一排，全都解不了时
     * 「换下一条 → 又解不了」会在同一帧里空转。到线路总数就认输，把失败面板交出去。
     */
    private var unsupportedSkips = 0

    /**
     * 最近 [REBUFFER_RATIO_MIN_TICKS] 个统计周期的「是否在缓冲」（2026-11-06）。
     *
     * 队列长度固定，所以它天然是「最近 10 秒」的滑动窗口。只在 [startStatsTicker]
     * 里读写，不需要加锁。
     */
    private val bufferingWindow = ArrayDeque<Boolean>()

    /**
     * 出画之后的冷静期计数（2026-11-06）。
     *
     * 起播阶段的 BUFFERING 是**必然**的：首帧还没解码出来，内核当然在缓冲。
     * 把这些拍算进 `bufferingWindow`，后果是**每条线路一出画就被判「供不上」** ——
     * 出画的瞬间窗口里已经攒了十来拍缓冲，占比直接过半，3 秒后自己把自己换掉。
     * 设备实测（2026-11-06）：第 1 条线路已经出画、1080p 软解正常，9 秒后仍被换走，
     * 接着换到 11 条真·不通的兜底线路，最后落到「12 条线路没一条好的」。
     *
     * 所以窗口只在**出画之后**才记账，起播那几拍一笔勾销。
     */
    private var renderedTicks = 0

    /**
     * 本次起播里**出过画面**的那条线路下标（-1 = 还没有）。
     *
     * 它的用途只有一个：走到「全部线路都试完」要落失败面板时，先回到这条再试一次。
     * 一条能出画的线路，永远比后面那些连响应头都没回的兜底线路更值得看 ——
     * 用户的原话是「12 条线路难道没一条好的？」，而实际情况是第 1 条是好的，
     * 只是被换走了就再没回头。
     */
    private var renderedLineIndex = -1

    /** 回退到「曾出画」的线路只允许一次，否则会在「能出画但卡」与「完全不通」之间来回弹 */
    private var returnedToRendered = false

    /**
     * 轨道 id → (track group, 组内第几条)。
     *
     * UI 只认 [PlaybackTrack.id]，真正的定位靠这张表 —— 见 `PlaybackTrack` 的注释。
     * 每次 `onTracksChanged` 重建，因为换集 / 换线路之后 group 对象全变了。
     */
    private val trackIndex = HashMap<String, TrackRef>()

    private data class TrackRef(
        val group: TrackGroup,
        val index: Int,
        val kind: TrackKind,
    )

    /**
     * 事件监听只注册一次：内核重建时把它挂到新实例上即可。
     *
     * 提成字段（原来是 `init` 里的匿名对象）是因为重建内核要重新 `addListener`，
     * 每重建一次就 new 一个匿名对象，等于每次换类型都多一份悬挂引用。
     */
    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val current = request ?: return
                val lines = current.normalizedLines
                when (playbackState) {
                    // 只认 BUFFERING：STATE_IDLE 会在出错后紧跟着回调一次，
                    // 如果把它也当成 Preparing，刚刚设好的 Failed 会被立刻覆盖，
                    // 用户看到的就是「一直转圈、永远不报错」
                    Player.STATE_BUFFERING -> {
                        noteRebuffer()
                        // **播放中重新缓冲不是一次新的起播**：画面出过一次之后，
                        // 状态继续留在 Playing，缓冲这件事交给 loadStats.buffering 表达。
                        //
                        // 原来这里无条件写成 Preparing，于是网速抖一下（哪怕只缓冲 0.2 秒）
                        // 播放页就按 `preparing` 判定成「重新起播」，弹出正中那张大卡片，
                        // 整幅画面被盖住 —— 用户看到的就是「动不动就卡一下」（2026-11-06 截图）。
                        // 只有一次都没出过画面（起播 / 换集 / 换线后的第一次缓冲）才需要 Preparing。
                        if (!renderedOnce) {
                            _state.value =
                                PlaybackState.Preparing(
                                    url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                                    item = current.item,
                                    lineIndex = lineIndex,
                                    lineCount = lines.size,
                                )
                        }
                    }

                    Player.STATE_READY -> {
                        stopWatchdog()
                        // 第一次出画：把这条线路记成「能看的」，并把起播阶段的缓冲拍全部作废
                        // —— 起播必然在缓冲，那是首帧还没解码，不是线路喂不饱。
                        // 不作废的话，出画的瞬间缓冲占比就已经过半，3 秒后这条**好线路**
                        // 会被自己的起播缓冲判成「供不上」（2026-11-06 实测）
                        val firstRender = !renderedOnce
                        renderedOnce = true
                        if (firstRender) {
                            renderedLineIndex = lineIndex
                            renderedTicks = 0
                            bufferingWindow.clear()
                        }
                        _state.value =
                            PlaybackState.Playing(
                                url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                                item = current.item,
                                lineIndex = lineIndex,
                                lineCount = lines.size,
                                // 回到 READY 有两种来路：正常起播（isPlaying 已经为 true），
                                // 以及「暂停中缓冲够了」（isPlaying 仍是 false，必须保住暂停位，
                                // 否则缓冲一结束 UI 就以为在播，OK 键又只发 pause）
                                paused = !videoPlayer.isPlaying,
                            )
                    }

                    Player.STATE_ENDED ->
                        // 直播流正常不该「播完」，真结束了按断流处理，走同一条重连路径；
                        // 点播播完是正常结局，交给上层决定下一集（VOD-05）——
                        // 这里若是也走 handleFailure，每集播完都会重播最后几秒再报错
                        if (current.kind == PlaybackKind.VOD) {
                            stopWatchdog()
                            _state.value =
                                PlaybackState.Ended(
                                    url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                                    item = current.item,
                                    lineIndex = lineIndex,
                                    lineCount = lines.size,
                                )
                        } else {
                            handleFailure(reason = REASON_STREAM_ENDED)
                        }
                }
            }

            /**
             * 暂停 / 继续是**横切**事件：它不改 `playbackState`（暂停时仍是 READY），
             * 只翻 `isPlaying`。所以只能在这里同步，靠 `onPlaybackStateChanged` 是看不见的。
             *
             * 只在已经 [PlaybackState.Playing] 时改写，其它状态（起播前 / 失败 / 播完）
             * 不该被一个「谁按了暂停」翻出新状态。
             */
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val current = _state.value
                // paused 与 isPlaying 应当互补，相等就说明这一位是旧的
                if (current is PlaybackState.Playing && current.paused == isPlaying) {
                    _state.value = current.copy(paused = !isPlaying)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 直播窗口滑走（HLS 的 `BehindLiveWindowException`）**不是这条线路坏了**：
                // 源是活的，只是我们挑的起点掉出了滑动窗口，重选一次直播边缘就能接着放。
                //
                // 不区分它会把一整类国内 IPTV 源判成死线。这类源的 playlist 只挂 4~6 个
                // 绝对命名的分片（`live_3_1_13732458.ts?key=...` 这种），窗口每 5 秒滑一格，
                // 起播/重连稍微慢一点就必然踩到。实测这条路径的表现是：M3U8 明明 200 拿到了，
                // 播放在 1 秒内抛错 → 被当成线路失败 → 换线 → 再抛 → 最后落到
                // 「连续 N 个台不可用」，而每一条线路其实都是好的。
                //
                // 只给直播用：点播的 HLS 也有这个错误码，但点播掉出窗口意味着这一集真的过期了，
                // 重选起点会悄悄跳剧情，交给失败处置更诚实。
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                    request?.kind != PlaybackKind.VOD &&
                    liveRealigns < MAX_LIVE_REALIGNS
                ) {
                    liveRealigns++
                    Log.d(PlaybackDiagnostics.TAG, "直播窗口滑走，回到直播边缘重来（第 $liveRealigns 次）")
                    videoPlayer.seekToDefaultPosition()
                    videoPlayer.prepare()
                    startWatchdog()
                    return
                }
                handleFailure(reason = describe(error))
            }

            // 换集 / 换线路 / 起播后轨道都会变。INFO 没有内容，不刷（每次都重建列表
            // 会让「音轨」面板在播放中被无意义地重绘）
            override fun onTracksChanged(tracks: Tracks) {
                refreshTracks()
            }
        }

    private val videoPlayer: ExoPlayer
        get() = kernel

    /**
     * 当前内核。
     *
     * 为什么是可变字段：**LoadControl 是 `ExoPlayer.Builder` 的构造期参数**，
     * 而直播与点播要的缓冲完全是两套（直播受 HLS 窗口限制只能攒 30 秒，
     * 点播则可以一路预读到三分钟）。同一个 ExoPlayer 无法中途换策略，只能换内核。
     * 见 [ensureKernelFor]。
     */
    private var kernel: ExoPlayer = buildPlayer(PlaybackKind.LIVE)

    /** 当前内核是按哪种播放类型建的。类型变了就得重建，否则缓冲参数不对 */
    private var playerKind: PlaybackKind = PlaybackKind.LIVE

    /**
     * 画面出口的代号（见 [surfaceToken]）。
     *
     * 重建内核时自增，让 Compose 用新的 key 重建 `AndroidView` —— 否则 factory
     * 一辈子只跑一次，新内核的 surface 永远没被挂上去，表现为有声音没画面。
     */
    private var surfaceVersion = 0

    /**
     * 当前的画面出口。
     *
     * 每次 `createSurfaceView` 都新建一个 `PlayerView`（不复用：Compose 会把它挂进
     * 视图树，同一个 View 实例不能同时属于两棵树），但**必须记住它**，
     * 这样 [release] 在 UI 忘了走 `onRelease` 时还能兜底解绑。
     */
    private var playerView: PlayerView? = null

    override fun createSurfaceView(context: Context): View {
        // 上一块出口若还在，先摘掉：`PlayerView.player = x` 会给 ExoPlayer 注册一整套
        // 监听（ComponentListener / SurfaceCallback / Player.Listener），不解绑就是纯泄漏
        playerView?.let { releaseSurfaceView(it) }
        return PlayerView(context)
            .apply {
                useController = false
                // 直播不裁画面：宁可留黑边，也不要把台标和字幕切掉
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = videoPlayer
            }.also { playerView = it }
    }

    override val surfaceToken: Any get() = SurfaceToken(this, surfaceVersion)

    /**
     * 画面出口代号。
     *
     * 不能直接用 `this`：重建内核（直播 ↔ 点播换缓冲策略）时同一个 controller 实例
     * 会换掉底层 ExoPlayer，而 `this` 永远相等 —— Compose 就不会重建 `AndroidView`，
     * 画面仍然挂在**已释放**的旧内核的 surface 上（有声音、没画面）。
     * 带上版本号之后，重建一次就换一个代号。
     *
     * 带上 owner 是因为兜底链里的 VLC 内核也用 `this` 当代号：
     * 只用版本号（Int）的话，两个实例的 0 会相等，换内核同样重建不了画面。
     */
    private data class SurfaceToken(
        val owner: Media3PlayerController,
        val version: Int,
    )

    override fun releaseSurfaceView(view: View) {
        if (view is PlayerView) {
            view.player = null
            if (playerView === view) playerView = null
        }
    }

    private var request: PlaybackRequest? = null
    private var lineIndex = 0
    private var retriesLeft = retryLimit

    /**
     * 本轮起播的续播点。
     *
     * 只在 `play()` 之后第一次建流时用：那时内核里还没有任何位置信息。
     * 之后（重连、换线）改问 `currentPosition` —— 播到一半断了应该接着断点播，
     * 而不是退回用户点进来时那个位置，让人重看一集。
     */
    private var resumeFromMs = 0L
    private var firstStart = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdog: Job? = null

    /** 加载统计的轮询任务。**只在播放期间存在**，见 [startStatsTicker] */
    private var statsTicker: Job? = null

    override fun play(request: PlaybackRequest) {
        this.request = request
        // 直播与点播的缓冲策略不同，而 LoadControl 是构造期参数 —— 类型变了先换内核。
        // 必须在后面的状态清理之前做：重建内核会把旧内核的播放状态一并扔掉
        ensureKernelFor(request.kind)
        lineIndex = firstLiveLineIndex(request.normalizedLines, 0)
        retriesLeft = retryLimit
        resumeFromMs = request.startPositionMs
        firstStart = true
        // 换台/换集要把上一轮的字节数与分辨率清掉：不清的话新画面黑屏时，
        // 浮层上还挂着上一路流的「已加载 12MB · 1920×1080」，比不显示更误导
        loadTracker.reset()
        _loadStats.value = LoadStats.None
        renderedOnce = false
        rebuffers.clear()
        autoSwitchCount = 0
        deadLines = 0
        liveRealigns = 0
        suppressRebufferUntil = 0L
        starvedTicks = 0
        unsupportedSkips = 0
        renderedOnce = false
        renderedTicks = 0
        renderedLineIndex = -1
        returnedToRendered = false
        bufferingWindow.clear()
        startStatsTicker()
        startCurrentLine()
    }

    override fun retry() {
        if (request == null) return
        // 手动重试重置重连次数：用户点了「重试」，说明他愿意再等一轮
        retriesLeft = retryLimit
        startCurrentLine()
    }

    override fun switchLine(delta: Int): Boolean {
        val current = request ?: return false
        val lines = current.normalizedLines
        if (lines.size <= 1) return false

        val next =
            if (delta > 0) {
                // 往后换时跳过本进程里已经「一个字节都没回」的线路：那类地址再等一个预算
                // 也是同样的结果，用户要的是「下一条能看的」，不是「下一条编号」
                firstLiveLineIndex(lines, (lineIndex + delta).coerceIn(0, lines.lastIndex))
            } else {
                (lineIndex + delta).coerceIn(0, lines.lastIndex)
            }
        if (next == lineIndex) return false

        lineIndex = next
        retriesLeft = retryLimit
        startCurrentLine()
        return true
    }

    override fun stop() {
        stopWatchdog()
        stopStatsTicker()
        videoPlayer.stop()
        clearTracks()
        _state.value = PlaybackState.Idle
    }

    override fun pause() {
        videoPlayer.pause()
        // 不等 onIsPlayingChanged：遥控器一次按键到 UI 刷新要走一帧，
        // 中间的这段时间里再按一次会读到旧状态（连按两下等于什么也没发生）
        markPaused(paused = true)
    }

    override fun resume() {
        videoPlayer.play()
        markPaused(paused = false)
    }

    /** 只在「已经就绪」时翻暂停位；起播前 / 失败 / 播完都没有暂停这个概念 */
    private fun markPaused(paused: Boolean) {
        val current = _state.value
        if (current is PlaybackState.Playing && current.paused != paused) {
            _state.value = current.copy(paused = paused)
        }
    }

    /** 位置与时长直接问内核。时长未知（直播 / 还没拿到 manifest）时 Media3 返回 0 或负数 */
    override fun seekTo(positionMs: Long) {
        videoPlayer.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setSpeed(speed: Float) {
        videoPlayer.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
    }

    override fun positionMs(): Long = videoPlayer.currentPosition.coerceAtLeast(0L)

    override fun durationMs(): Long = videoPlayer.duration.coerceAtLeast(0L)

    override fun release() {
        stopWatchdog()
        stopStatsTicker()
        request = null
        clearTracks()
        // 兜底解绑：正常路径由 Compose 的 onRelease 调用，但「Activity 直接 finish」
        // 之类的路径不保证走到 onRelease
        playerView?.let { releaseSurfaceView(it) }
        videoPlayer.release()
        scope.cancel()
        _state.value = PlaybackState.Idle
    }

    // --------------------------------------------------------------------- //
    // 轨道（PLAY-07）
    // --------------------------------------------------------------------- //

    override fun selectTrack(trackId: String) {
        val ref = trackIndex[trackId] ?: return
        val type = if (ref.kind == TrackKind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        videoPlayer.trackSelectionParameters =
            videoPlayer.trackSelectionParameters
                .buildUpon()
                // 先解除整类禁用再指定：上一轮「关闭字幕」禁的是整个类型，
                // 只设 override 而类型仍是禁用的，选了也不生效
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(ref.group, listOf(ref.index)))
                .build()
        refreshTracks()
    }

    override fun disableTracks(kind: TrackKind) {
        val type = if (kind == TrackKind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        videoPlayer.trackSelectionParameters =
            videoPlayer.trackSelectionParameters
                .buildUpon()
                // 关掉整类时把 override 一起清掉：留着它，下次打开这条流会「自动」选中
                // 上一次那条字幕，用户会以为关闭没生效
                .clearOverridesOfType(type)
                .setTrackTypeDisabled(type, true)
                .build()
        refreshTracks()
    }

    /**
     * 把 Media3 的轨道表翻译成内核无关的形式。
     *
     * 只收音频与字幕：视频轨的「切换」在盒子上没有实际意义（见 `TrackKind`）。
     * [Tracks.Group.isTrackSupported] 为 false 的也收 —— 它在菜单里应该显示成
     * 「选不了」而不是干脆消失：用户明明看到片子里有中文字幕，菜单里却没有，只会以为坏了。
     */
    private fun refreshTracks() {
        val tracks = videoPlayer.currentTracks
        val refs = HashMap<String, TrackRef>()
        val out = ArrayList<PlaybackTrack>()

        for (group in tracks.groups) {
            val kind =
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> TrackKind.AUDIO
                    C.TRACK_TYPE_TEXT -> TrackKind.TEXT
                    else -> continue
                }
            // 每类各自从 1 开始编号：菜单里说的是「音轨 2」「字幕 1」，不混着数
            val ordinal = out.count { it.kind == kind }
            for (i in 0 until group.length) {
                val id = "${kind.name}:${group.mediaTrackGroup.id}:$i"
                if (refs.containsKey(id)) continue
                val format = group.getTrackFormat(i)
                refs[id] = TrackRef(group.mediaTrackGroup, i, kind)
                out +=
                    PlaybackTrack(
                        id = id,
                        kind = kind,
                        label =
                            composeTrackLabel(
                                kind = kind,
                                ordinal = ordinal + i + 1,
                                label = format.label,
                                language = format.language,
                                mime = format.sampleMimeType,
                            ),
                        selected = group.isTrackSelected(i),
                    )
            }
        }

        trackIndex.clear()
        trackIndex.putAll(refs)
        _tracks.value = out.sortedBy { it.kind }
    }

    private fun clearTracks() {
        trackIndex.clear()
        _tracks.value = emptyList()
    }

    /** 从 [from] 起第一条没被判死的线路。规则与理由见 [firstLiveLineIndex] */
    private fun firstLiveLineIndex(
        lines: List<String>,
        from: Int,
    ): Int = firstLiveLineIndex(lines = lines, from = from, isDead = DeadLines::isDead)

    private fun startCurrentLine() {
        val current = request ?: return
        val lines = current.normalizedLines
        val url = lines[lineIndex.coerceIn(0, lines.lastIndex)]

        // 每起一条新线路都要重算「回过数据没有」：换线 / 重连 / 重试都会走到这里。
        // 探针按**时间戳**比对，所以上一条线路的迟到响应不会被算到这一条头上
        networkMonitor.reset()
        lineStartedAt = SystemClock.elapsedRealtime()
        // 直播窗口对齐的次数也按线路重算：看两小时碰上几次窗口滑走是正常的，
        // 不重置的话第四次起就会被当成线路坏了
        liveRealigns = 0
        // 「出过画」按线路重算：每一条线路都有自己的起播缓冲，不能把上一条的算到它头上
        renderedOnce = false
        renderedTicks = 0
        bufferingWindow.clear()

        // 起播/换线的轨迹（2026-11-06）：用户反馈「加载半天出不来」时，第一件要回答的事是
        // 「它到底试到第几条了」。只记下标与协议，不记地址（RULES §2.5）
        Log.d(
            PlaybackDiagnostics.TAG,
            "起流：第 ${lineIndex + 1}/${lines.size} 条线路（${url.substringBefore("://")}）",
        )

        _state.value =
            PlaybackState.Preparing(
                url = url,
                item = current.item,
                lineIndex = lineIndex,
                lineCount = lines.size,
            )

        // 每个源有自己的 UA / Referer，所以 data source 必须按请求重建（不能全局共用一份）
        val upstream =
            OkHttpDataSource.Factory(playbackClient)
                .setDefaultRequestProperties(current.headers)

        // 点播走磁盘缓存（PLAY-15）：同一集来回拖进度、切线路重看，已经下过的分片不必再拉 ——
        // 这是「看着卡」最直接的一块改善。直播刻意不缓存：HLS 直播的 playlist 每轮都在变，
        // 一旦命中旧的那份，表现就是换台后一直转圈或者卡在几分钟前。
        val network =
            if (current.kind == PlaybackKind.VOD) {
                PlayerCache.wrap(context, upstream)
            } else {
                upstream
            }

        val mediaSourceFactory =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, network))

        // 续播点只在本轮第一次建流时用；之后接着断点播（见 resumeFromMs 的注释）。
        // 但**已经播到末尾时不能拿它当续播点**：直播断流和点播播完都会让 currentPosition
        // 停在总时长上，续到末尾的新流一秒内又 ENDED，用户看到画面一闪一闪反复重连。
        // 靠近末尾一律从 0 重来（直播的 duration 是 C.TIME_UNSET，不会命中这里）。
        val rawStart = if (firstStart) resumeFromMs else videoPlayer.currentPosition
        val total = videoPlayer.duration
        val atEnd = total != C.TIME_UNSET && total > 0L && rawStart >= total - END_GUARD_MS
        val startAt = if (atEnd) 0L else rawStart.coerceAtLeast(0L)
        firstStart = false

        // 建流本身就会抛（2026-11-06 实测崩溃）：`DefaultMediaSourceFactory` 遇到
        // `rtsp://` 会去反射加载 `RtspMediaSource$Factory`，而这个模块没打包进来 ——
        // 抛的是 `IllegalStateException: ClassNotFoundException`，直接冒到主线程把 App 干掉。
        // 聚合源里 `rtsp://` 的线路很多（运营商内网专供），用户只是按了几下「换线路」就闪退。
        //
        // 这类线路**再试也一样**：记进 DeadLines 跳过去，按「这条线路播不了」的常规路径换线。
        // 用协程而不是直接调 handleFailure：它会再走回本函数，同步递归在所有线路都解不了时
        // 会一路戳到栈底；[unsupportedSkips] 再兜一层，防止「跳不动」时空转。
        val source =
            runCatching { mediaSourceFactory.createMediaSource(MediaItem.fromUri(url)) }
                .getOrElse {
                    DeadLines.mark(url)
                    unsupportedSkips++
                    Log.d(
                        PlaybackDiagnostics.TAG,
                        "这条线路的协议解不了，跳过（第 $unsupportedSkips 条）：${it.javaClass.simpleName}",
                    )
                    if (unsupportedSkips >= lines.size) {
                        stopWatchdog()
                        _state.value =
                            PlaybackState.Failed(
                                url = url,
                                item = current.item,
                                reason = REASON_UNSUPPORTED,
                                lineIndex = lineIndex,
                                lineCount = lines.size,
                            )
                    } else {
                        scope.launch { handleFailure(reason = REASON_UNSUPPORTED) }
                    }
                    return
                }
        videoPlayer.setMediaSource(source, startAt)
        videoPlayer.prepare()
        videoPlayer.playWhenReady = true

        startWatchdog()
    }

    /**
     * 起播看门狗（PLAY-08 的实际兜底）。
     *
     * 只靠 `onPlayerError` 不够：源不响应时 Media3 会一直「缓冲中」，既不报错也不出画。
     * 这里给每条线路一个时间预算，超了就当这条线路失败。
     *
     * **分两段判**（2026-11-04 优化，用户反馈「有时候等半天根本就没有东西」）：
     *
     * 1. 连**首字节**都没来 → [FIRST_BYTE_BUDGET_MS] 就切走。死线路的典型表现是
     *    连接挂着不返回，旧实现要等满 15 秒才认输，而源里常有好几条线路轮着死，
     *    用户看到的就是「转圈转到天荒地老」。
     * 2. 有字节但迟迟不出画 → 走完整的 [LINE_BUDGET_MS]。slow-but-alive 的线路
     *    （握手慢、首包慢）不该被当成死线掐掉。
     */
    private fun startWatchdog() {
        // 点播放宽：电影一集是几百兆的 1080p，起播要先攒 [BufferProfile.VOD_BUFFER]
        // 的 3 秒数据再解码，慢源上首帧常常要十几秒。用直播那套 9 秒预算的结果是
        // 「明明在正常下载，却在第 9 秒被判成没出画面 → 切到 VLC 重头再来一遍」——
        // 用户看到的就是「影视一直缓冲」，而且比不切还慢（实测 2026-11-05：
        // 19 秒时切内核，VLC 又要从 0 开始加载）。
        val forVod = request?.kind == PlaybackKind.VOD
        val candidateCount = request?.normalizedLines?.size ?: 1
        val firstByteBudget =
            when {
                forVod -> VOD_FIRST_BYTE_BUDGET_MS
                // 只有一条线时没有退路：宁可多等 1.5 秒，也不要因为握手慢就把这个台判死
                candidateCount <= 1 -> FIRST_BYTE_BUDGET_MS
                // 有候选就快试（2026-11-06「加载慢、无响应的源还在费时间」）：
                // 聚合源里一个台常有十条以上线路，按 4 秒一条走完要 40 秒，
                // 而其中绝大多数是**一次都没通过**的死链 —— 开头几次换台就被这些拖住了
                else -> FAST_FIRST_BYTE_BUDGET_MS
            }
        val lineBudget = if (forVod) VOD_LINE_BUDGET_MS else LINE_BUDGET_MS
        watchdog?.cancel()
        watchdog =
            scope.launch {
                delay(firstByteBudget)
                if (_state.value is PlaybackState.Preparing && !networkMonitor.respondedSince(lineStartedAt)) {
                    handleFailure(reason = REASON_TIMEOUT)
                    return@launch
                }
                delay((lineBudget - firstByteBudget).coerceAtLeast(0L))
                if (_state.value is PlaybackState.Preparing) {
                    handleFailure(reason = REASON_TIMEOUT)
                }
            }
    }

    private fun stopWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    /** 失败处置：先重连，再换线路，最后才认输。认输时把「还能换线路」交给 UI 判断。 */
    private fun handleFailure(reason: String) {
        val current = request ?: return
        val lines = current.normalizedLines

        // 播到末尾（直播断流常表现为 ENDED）后重连必须从头来：此刻 currentPosition 正好
        // 停在总时长上，[startCurrentLine] 再拿它当续播点，新建的流会立刻又 ENDED ——
        // 用户看到的是画面一闪一闪反复重连，永远播不满一秒。
        if (videoPlayer.playbackState == Player.STATE_ENDED) {
            firstStart = true
            resumeFromMs = 0L
        }

        // 「等了这么久一个字节都没来」不重试同一条线：同一个地址再等一轮也是同样的结果，
        // 只是把用户多关在转圈里（3 次重试 + 本轮）。直接交给下一条线路。
        // 只有**传输层真的报错**（IO/HTTP）才值得原地重试 —— 那种多半是瞬时抖动。
        //
        // 直播看门狗超时**不重试同一条线**（2026-11-06「加载半天出不来」）：
        // 一条直播线路 9 秒还没出画，同一条再来一轮也是 9 秒 —— 三次就是 27 秒，
        // 而聚合源里等着试的线路还有十几条。点播相反（慢但能通的源值得等），保留重试。
        if (reason == REASON_TIMEOUT && current.kind != PlaybackKind.VOD) retriesLeft = 0

        // 判据是「这次请求有没有收到响应头」：字节数要等整个分片下完（可能好几秒），
        // 而服务器只要活着，响应头几十毫秒就到。
        val nothingArrived = !networkMonitor.respondedSince(lineStartedAt)
        // 一个成功响应都没拿到（连接拒了 / 404 / 403 / 干脆不回包）时，原地重试同一个地址
        // 不会有别的结果，只是把用户多关在转圈里几个预算周期。直接换下一条线路。
        if (nothingArrived) {
            retriesLeft = 0
            // 记进进程级「不回包」名单：换台再切回来、或别的台也挂着同一台中转站时，
            // 直接从它后面开始试，不再为它重等一个预算（见 DeadLines）
            DeadLines.mark(lines[lineIndex.coerceIn(0, lines.lastIndex)])
        }

        // 连续几条线路都「一个字节没来」= 不是线路的问题，是源或网络整体不可用。
        // 聚合源里一个台常有十几二十条线路，逐条走完要一分钟 —— 这段时间屏幕上只有转圈，
        // 用户不知道发生了什么。到 [DEAD_LINE_LIMIT] 就认输，把失败出口交出去。
        if (nothingArrived) deadLines++
        if (nothingArrived && deadLines >= DEAD_LINE_LIMIT && retriesLeft <= 0) {
            if (returnToRenderedLine()) return
            stopWatchdog()
            _state.value =
                PlaybackState.Failed(
                    url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                    item = current.item,
                    reason = REASON_NO_RESPONSE,
                    lineIndex = lineIndex,
                    lineCount = lines.size,
                )
            return
        }

        if (retriesLeft > 0) {
            retriesLeft--
            startCurrentLine()
            return
        }

        if (lineIndex + 1 < lines.size) {
            lineIndex = firstLiveLineIndex(lines, lineIndex + 1)
            retriesLeft = retryLimit
            startCurrentLine()
            return
        }

        if (returnToRenderedLine()) return
        stopWatchdog()
        _state.value =
            PlaybackState.Failed(
                url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                item = current.item,
                reason = reason,
                lineIndex = lineIndex,
                lineCount = lines.size,
            )
    }

    /**
     * 全部线路都试完了，但其中有一条曾经出过画面 —— 回到那条，别直接认输。
     *
     * 这是「12 条线路难道没一条好的？」的直接答复：兜底线路（跨源同名捞来的、
     * 上游清单里早失效的）往往连响应头都不回，而**第一条**其实已经 1080p 出画了，
     * 只是被换线链一路换走、再也不回头。一条能出画的线路比十条连不通的都值。
     *
     * 只允许回退一次（[returnedToRendered]）：回退后仍失败就是真失败，
     * 再来回弹只会让用户看着画面反复重头。
     */
    private fun returnToRenderedLine(): Boolean {
        if (returnedToRendered) return false
        val target = renderedLineIndex
        if (target < 0 || target == lineIndex) return false
        val lines = request?.normalizedLines ?: return false
        if (target > lines.lastIndex) return false

        returnedToRendered = true
        lineIndex = target
        retriesLeft = retryLimit
        deadLines = 0
        autoSwitchCount = 0
        Log.d(PlaybackDiagnostics.TAG, "全线都试过了 → 回到曾出过画面的第 ${target + 1}/${lines.size} 条")
        startCurrentLine()
        return true
    }

    /**
     * 每 [STATS_TICK_MS] 读一次「缓冲位置 + 是否在缓冲」，并把字节数换算成速率。
     *
     * 为什么不把这些也放进 AnalyticsListener：`bufferedPosition` 不是事件，是状态 ——
     * 只能在轮询里读。速率也必须按「两次读数的差值」算，事件里只有累计值。
     *
     * 500ms 是「数字看起来在动」与「别为了一行字频繁唤醒」之间的折中。
     * 值没变时 StateFlow 会自动去重（data class equals），不会白白触发重组。
     *
     * ## 为什么改成「只在播放期间跑」（2026-11-04，2GB 机器防闪退）
     *
     * 原来在 `init` 里就 `while (true)` 起跑，永不停止：即使播放器处于 Idle，
     * 主线程仍然每 500ms 被唤醒一次去读三个播放器属性、算速率、可能写 StateFlow。
     * 低配机（A55 四小核）上这是纯浪费 —— 空闲时也进不了低功耗态，还和 4K 渲染抢调度。
     * 现在由 [play] 启动、[stop]/[release] 取消，并且用 `isActive` 兜住重复调用。
     */
    private fun startStatsTicker() {
        if (statsTicker?.isActive == true) return
        statsTicker =
            scope.launch {
                var lastBytes = 0L
                var lastAt = SystemClock.elapsedRealtime()
                while (true) {
                    delay(STATS_TICK_MS)
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = (now - lastAt).coerceAtLeast(1L)
                    val current = _loadStats.value
                    val speed = (current.bytesLoaded - lastBytes).coerceAtLeast(0L) * 1000L / elapsed
                    lastBytes = current.bytesLoaded
                    lastAt = now

                    val buffered =
                        (videoPlayer.bufferedPosition - videoPlayer.currentPosition).coerceAtLeast(0L)
                    val buffering = videoPlayer.playbackState == Player.STATE_BUFFERING
                    _loadStats.value =
                        current.copy(
                            buffering = buffering,
                            bufferedMs = buffered,
                            bytesPerSecond = speed,
                        )

                    // 缓冲窗口：最近 20 个周期（约 10 秒）里有几个在缓冲。
                    // 这是「码率未知」时唯一可靠的判据 —— 实测 HLS 直播的 Format.bitrate
                    // 常常是 0，那时候比值判据失效，占比判据接着干活。
                    //
                    // **只在出画之后才记账**：起播那几拍必然在缓冲，一起算进来的话，
                    // 每条线路刚出画就被自己的起播缓冲判成「供不上」（见 renderedTicks）。
                    if (renderedOnce) {
                        renderedTicks++
                        bufferingWindow.addLast(buffering)
                        while (bufferingWindow.size > REBUFFER_RATIO_MIN_TICKS) {
                            bufferingWindow.removeFirst()
                        }
                    }

                    // 「供不上」判据（2026-11-06）：**正在饿着**、且这一秒拿到的字节数
                    // 明显低于流自己声明的码率（或最近 10 秒一直在缓冲）——
                    // 这不是抖动，是线路天生喂不饱。3 秒没缓过来就换线，
                    // 不再等 45 秒里凑够 3 次缓冲（那种节奏下用户已经看完半场卡顿了）。
                    noteStarvation(
                        buffering = buffering,
                        speed = speed,
                        requiredBits = current.bitrateBps,
                        // 冷静期：出画后先看 4 秒再下结论。起播刚结束时的抖动（索引文件、
                        // 首分片、解码器热身）都会让缓冲忽有忽无，那不是线路的问题
                        played = renderedOnce && renderedTicks >= RENDER_GRACE_TICKS,
                    )
                }
            }
    }

    /**
     * 累计「饿」的连续周期，够久就换线。
     *
     * [buffering] 为 false 时清零：**不饿的时候速率低是正常的**（缓冲已满，内核干脆不下载），
     * 拿它当依据会把好线路判成坏的 —— 这是这条判据最容易写错的地方。
     */
    private fun noteStarvation(
        buffering: Boolean,
        speed: Long,
        requiredBits: Int,
        played: Boolean,
    ) {
        // 两条判据取并集：
        // 1. 码率已知 → 实测速率 vs 码率（准，且能秒级判定）；
        // 2. 码率未知（HLS 直播的常态）→ 最近 10 秒的缓冲时间占比。
        val starved =
            buffering &&
                played &&
                (
                    isLineStarved(measuredBytesPerSecond = speed, requiredBitsPerSecond = requiredBits) ||
                        isRebufferHeavy(
                            bufferingTicks = bufferingWindow.count { it },
                            totalTicks = bufferingWindow.size,
                        )
                )
        if (!starved) {
            starvedTicks = 0
            return
        }
        starvedTicks++
        if (starvedTicks < STARVED_SWITCH_TICKS) return
        starvedTicks = 0

        val description = if (requiredBits > 0) starvedDescription(requiredBits, speed) else "这条线路供不上：一直缓冲"
        Log.d(PlaybackDiagnostics.TAG, description)

        val lines = request?.normalizedLines ?: return
        // 只有一条线时没什么可换的：继续播（用户可能就想先看着），
        // 真断流了还有 onPlayerError 与看门狗那条链兜底
        if (lines.size <= 1) return
        if (autoSwitchCount >= AUTO_SWITCH_LIMIT) return

        autoSwitchCount++
        rebuffers.clear()
        suppressRebufferUntil = SystemClock.elapsedRealtime() + REBUFFER_SILENCE_MS
        Log.d(PlaybackDiagnostics.TAG, "判定这条线路供不上 → 换下一条（第 $autoSwitchCount 次）")
        // 从统计协程里直接 prepare 会重入播放器，挪到下一个循环再换
        scope.launch { switchLine(delta = 1) }
    }

    /** 停掉统计轮询。[stop]/[release] 调用；重复调用无副作用 */
    private fun stopStatsTicker() {
        statsTicker?.cancel()
        statsTicker = null
    }

    /**
     * 「动不动就卡」的自动处置（PLAY-13）。
     *
     * 真机实测（2026-10-26）：某路 CCTV 高清流 30 秒内进了 7 次 BUFFERING ——
     * 画面走走停停，但**一次错误都没有**，所以既有的重连/换线降级链完全不会触发
     * （那条链只在 `onPlayerError` 上跑）。用户看到的就是「动不动就卡」，而 App 无动于衷。
     *
     * 判据：**出画之后**、[REBUFFER_WINDOW_MS] 内卡了 [REBUFFER_LIMIT] 次 → 认定这条线路
     * 供不上，换下一条。只换 [AUTO_SWITCH_LIMIT] 次就停手：几条线路都慢是网络的问题，
     * 再跳只会让画面反复重来，比卡着更难受。
     *
     * 起播时的那次 BUFFERING 不算（[renderedOnce]）：那时候本来就还没画面。
     */
    private fun noteRebuffer() {
        if (!renderedOnce) return
        val now = SystemClock.elapsedRealtime()
        // 静默期：刚因为「卡」换过线，重新起播的这段缓冲不是新的证据
        if (now < suppressRebufferUntil) return

        rebuffers.addLast(now)
        while (rebuffers.isNotEmpty() && now - rebuffers.first() > REBUFFER_WINDOW_MS) {
            rebuffers.removeFirst()
        }
        if (rebuffers.size < REBUFFER_LIMIT) return

        val lines = request?.normalizedLines ?: return
        if (lines.size <= 1) return
        if (autoSwitchCount >= AUTO_SWITCH_LIMIT) return

        autoSwitchCount++
        rebuffers.clear()
        suppressRebufferUntil = now + REBUFFER_SILENCE_MS
        Log.d(
            PlaybackDiagnostics.TAG,
            "连续 ${REBUFFER_LIMIT} 次缓冲，判定这条线路供不上 → 换下一条（第 $autoSwitchCount 次）",
        )
        // 从状态回调里直接 prepare 会重入播放器，挪到下一个循环再换
        scope.launch { switchLine(delta = 1) }
    }

    /**
     * 按播放类型建内核（`kind` 决定缓冲策略，见 [BufferProfile.loadControl]）。
     *
     * 直播与点播的缓冲上限天生冲突：直播被 HLS 窗口卡在 30 秒，点播要一路预读到三分钟。
     * LoadControl 只能在 `ExoPlayer.Builder` 上设，所以两者之间切换时只能换一个内核实例 ——
     * 见 [ensureKernelFor]。
     */
    private fun buildPlayer(kind: PlaybackKind): ExoPlayer =
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    // 硬解初始化失败时退到系统软解，这是 PLAY-02 在 Media3 内能做到的兜底
                    .setEnableDecoderFallback(true),
            )
            // 缓冲参数由设置页（SET-01）与播放类型共同决定：
            // 直播吃档位（低延迟/均衡/抗卡顿），点播固定走「大缓冲」那一套。
            // 设备内存档位在这里注入：同一档在 2GB 盒子上要按字节收紧，
            // 否则 4K 码流会把进程撑到被系统杀掉（见 MemoryLevel 的说明）
            .setLoadControl(
                bufferProfile.loadControl(
                    MemoryLevel.of(context).also {
                        Log.d(PlaybackDiagnostics.TAG, "内存档位 ${MemoryLevel.describe(context)}")
                    },
                    forVod = kind == PlaybackKind.VOD,
                ),
            )
            .build()
            // 诊断日志：解码器是硬解还是软解、分辨率帧率、丢帧数 —— 用户说「卡」时先看这个
            .apply {
                addListener(playerListener)
                addAnalyticsListener(PlaybackDiagnostics())
                // 同一批事件里顺手攒出「已加载多少字节」，喂给加载浮层（PLAY-12）
                addAnalyticsListener(loadTracker)
            }

    /**
     * 保证内核是按 [kind] 的缓冲策略建出来的，不是就重建一个。
     *
     * 只在**播放类型发生变化**时触发（直播 ↔ 点播），同类型连续换台不会重建。
     *
     * 顺序很讲究：
     * 1. 先把 `PlayerView.player` 摘掉 —— 画面出口还指着旧内核时直接 release，
     *    下一帧 draw 就会摸到已释放的对象（native 崩溃，Kotlin 侧连栈都拿不到）。
     * 2. 再 release 旧内核。
     * 3. 建新内核、把出口接回去。
     * 4. 最后自增 [surfaceVersion]：UI 侧的 `key(surfaceToken)` 收到新代号后
     *    会把旧 View 交还（解绑）并让新内核重新造一块 —— 不换代号的话
     *    `AndroidView` 的 factory 不会重跑，画面就永远缺一块。
     */
    private fun ensureKernelFor(kind: PlaybackKind) {
        if (playerKind == kind) return
        val old = kernel
        playerView?.player = null
        runCatching { old.stop() }
        old.release()
        kernel = buildPlayer(kind)
        playerView?.player = kernel
        playerKind = kind
        surfaceVersion++
        Log.d(PlaybackDiagnostics.TAG, "内核按播放类型重建：$kind")
    }

    companion object {
        /** 单条线路上连续重连的次数上限；再失败就该换线了（PLAY-08） */
        const val PLAYBACK_RETRY_LIMIT = 3

        /** 加载统计的轮询间隔（PLAY-12）。见 startStatsTicker 的说明 */
        private const val STATS_TICK_MS = 500L

        /**
         * 出画之后先观察几个统计周期才允许「供不上」判据开火（2026-11-06）。
         *
         * 8 拍 = 4 秒。刚出画那几秒里，解码器在热身、首分片在补、索引文件在拉，
         * 缓冲忽有忽无是常态 —— 那不是线路的问题，按它换线就是**把好线路换掉**。
         */
        private const val RENDER_GRACE_TICKS = 8

        /**
         * 判定「这条线路供不上」的观察窗口（PLAY-13）。
         *
         * 2026-11-04 从 30 秒放宽到 45 秒：换线会清空缓冲、重新走一遍 playlist 与首分片，
         * 代价是 2~3 秒黑屏 —— 比「一次短暂抖动」大得多。窗口太窄时，一个正常的源
         * 只要在 30 秒内抖三次就被判定成坏线，用户感受反而是「一直卡、一直重来」。
         * 45 秒内还卡三次，才是真的供不上。
         */
        private const val REBUFFER_WINDOW_MS = 45_000L

        /** 窗口内卡几次算「供不上」。3 次是「明显不对」的最小值，1~2 次可能只是抖动 */
        private const val REBUFFER_LIMIT = 3

        /**
         * 一轮播放里最多自动换几次线。
         *
         * 3 而不是 2：聚合源里同一个台常有 4~6 条线路，只试 2 条就放弃，
         * 很容易停在同样慢的那几条上（实测 CCTV-8 有 5 条线路，质量差别很大）。
         *
         * 2026-11-06 提到 5：现在有了「供不上」这条**基于实测速率**的判据
         * （见 `isLineStarvation`），换线不再靠运气，而是「这条确实喂不饱」才换。
         * 一次换线约 3.5 秒，5 次 ≈ 18 秒 —— 这是「这个台的线路都不行」该有的代价，
         * 总比让用户在一条 0.8 Mbps 的线路上看十分钟「动一秒停两秒」强。
         */
        private const val AUTO_SWITCH_LIMIT = 5

        /** 换线后的静默期：这段时间的缓冲不计入统计 */
        private const val REBUFFER_SILENCE_MS = 8_000L

        /**
         * 距末尾多近算「已经播完」。
         *
         * 与 VLC 内核的 [VlcPlayerController] 同名常量保持同一量级：1.5 秒是
         * 「新画面到底出来了没有」的判别余量，再小会把正常短暂卡顿当成播完而白丢进度。
         */
        private const val END_GUARD_MS = 1_500L

        /** 错误码到人话的映射：UI 上直接显示，用户才知道该换线还是该检查网络 */
        fun describe(error: PlaybackException): String =
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                -> "网络连接失败"

                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                -> "源返回了错误"

                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "源地址不存在"

                // 正常路径里会被 onPlayerError 拦下来重选直播边缘，走到这里说明重选次数用完了
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "直播缓冲窗口已滑走"

                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                -> "这个格式解不了"

                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                -> "流地址格式不对"

                else -> "播放失败（${error.errorCodeName}）"
            }

        private const val REASON_STREAM_ENDED = "直播流已结束"
        private const val REASON_TIMEOUT = "这条线路一直没出画面"
        private const val REASON_UNSUPPORTED = "这条线路的协议播不了（rtsp 等）"

        /**
         * 轨道菜单上那一行字（PLAY-07）。
         *
         * 只吃三个纯字符串（label / language / mime）而不是 Media3 的 `Format`：
         * 这样这段「把 en 说成英语、把 audio/mp4a-latm 说成 AAC」的映射能单测，
         * 不必为一个字符串函数去跑模拟器。
         *
         * 顺序是「名字 · 编码」：名字优先取容器里自带的 label（「国语」「Director's
         * Commentary」这类信息 language 里没有），没有才退到语言代码，再没有就是
         * 「音轨 2」。编码只作为补充 —— 用户挑中文音轨时，AAC 还是 AC3 是次要信息，
         * 但两条都叫「中文」时它就是唯一区别。
         */
        fun composeTrackLabel(
            kind: TrackKind,
            ordinal: Int,
            label: String?,
            language: String?,
            mime: String?,
        ): String {
            val fallback = if (kind == TrackKind.AUDIO) "音轨 $ordinal" else "字幕 $ordinal"
            val name =
                label?.takeIf { it.isNotBlank() }
                    ?: language?.let { languageName(it) }
                    ?: fallback
            val codec = mime?.let { TRACK_CODEC_NAMES[it] }
            return listOfNotNull(name, codec).joinToString(" · ")
        }

        /** ISO 639-1 / 639-2 都认：不同封装里同一种语言可能写 `zh`、`chi` 或 `cmn` */
        fun languageName(code: String): String? {
            val key = code.trim().lowercase().substringBefore('-')
            if (key.isBlank()) return null
            return TRACK_LANGUAGE_NAMES[key]
        }

        private val TRACK_LANGUAGE_NAMES: Map<String, String> =
            mapOf(
                "zh" to "中文",
                "chi" to "中文",
                "zho" to "中文",
                "cmn" to "国语",
                "yue" to "粤语",
                "en" to "英语",
                "eng" to "英语",
                "ja" to "日语",
                "jpn" to "日语",
                "ko" to "韩语",
                "kor" to "韩语",
                "fr" to "法语",
                "fra" to "法语",
                "fre" to "法语",
                "de" to "德语",
                "deu" to "德语",
                "ger" to "德语",
                "es" to "西班牙语",
                "spa" to "西班牙语",
                "ru" to "俄语",
                "rus" to "俄语",
                "th" to "泰语",
                "tha" to "泰语",
            )

        /** 只收会在菜单里出现的几种：其余原样不显示，比显示 `audio/x-unknown` 有用 */
        private val TRACK_CODEC_NAMES: Map<String, String> =
            mapOf(
                "audio/mp4a-latm" to "AAC",
                "audio/aac" to "AAC",
                "audio/ac3" to "AC3",
                "audio/eac3" to "EAC3",
                "audio/opus" to "Opus",
                "audio/vorbis" to "Vorbis",
                "audio/mpeg" to "MP3",
                "audio/flac" to "FLAC",
                "application/x-subrip" to "SRT",
                "text/vtt" to "WebVTT",
                "text/x-ssa" to "ASS",
                "application/ttml+xml" to "TTML",
                "application/cea-608" to "CC608",
                "application/cea-708" to "CC708",
            )

        /**
         * 单条线路的时间预算（**有字节之后**的预算）。
         *
         * 连接超时 10s 已经用掉一半，留 9s 是为了「慢但能通」的源也有机会出画，
         * 同时保证最坏情况下用户 40 秒内一定能看到失败出口。
         * 从 15s 压到 9s：源里常见 4~6 条线路，按旧的 15s 走完一轮要一分半，
         * 那段时间屏幕上是全黑的。
         */
        private const val LINE_BUDGET_MS = 9_000L

        /**
         * 「连首字节都没来」的预算（2026-11-04 新增）。
         *
         * 死线路的典型表现是连接挂着不回包。这种线路再等 12 秒也不会有结果，
         * 4 秒足够判定 —— 给 TLS 握手 + 首包留了余量（3 秒实测会误杀握手慢的正常源），
         * 又不至于让用户盯着转圈。达到它就直接换下一条
         * （`handleFailure` 里的 `nothingArrived` 会跳过多余重试）。
         */
        private const val FIRST_BYTE_BUDGET_MS = 4_000L

        /**
         * 「还有别的线路可试」时的首字节预算（2026-11-06）。
         *
         * 3 秒是握手慢的正常源的下限（见上一条），比它再短会误杀；而 4 秒一条 ×
         * 十几条候选就是用户说的「加载半天也加载不出来」。取舍是：
         * **有候选就快速试错**，反正试错成本只有 2.5 秒；**没有候选就老实等着**。
         *
         * 2.5 秒足够完成「DNS + TCP + TLS + 首包」：活着的直播服务器响应头通常在
         * 100ms 量级，连上却 2.5 秒吐不出一个字节的线路，即便最后出画也撑不住一路
         * 10 Mbps 的 1080p（2026-11-06 实测的死链全是这个样子）。
         */
        private const val FAST_FIRST_BYTE_BUDGET_MS = 2_500L

        /**
         * 点播的「连首字节都没来」预算（2026-11-05）。
         *
         * 比直播宽一倍：点播源经常是同一台服务器上挂着几十个片子，连接排队是常态，
         * 4 秒对它们太苛刻；而点播本来就不追求「换台要快」。
         */
        private const val VOD_FIRST_BYTE_BUDGET_MS = 8_000L

        /**
         * 点播的单线路总预算（2026-11-05）。
         *
         * 25 秒：3 秒起播缓冲 + 1080p 首帧解码，慢源上 12~18 秒是见过的。
         * 再长就不该等了 —— 那说明这条线路真的不行，换线路比继续等划算。
         */
        private const val VOD_LINE_BUDGET_MS = 25_000L

        /**
         * 连续多少条线路「一个字节都没回」就直接认输。
         *
         * 2026-11-06 从 5 提到 12：候选里现在混着同名台在**别的源**的线路（见
         * `ChannelRepository.sameNameLines`），其中好线常常排在末尾 —— 5 条的额度
         * 会在摸到它之前就认输。而「没回包」的线路现在是**秒级**判死的（2.5 秒或
         * 直接 HTTP 报错），走完 12 条的代价是几秒，比漏掉那条能看的线路小得多。
         *
         * 5 条：源里线路质量参差是常态，偶尔撞上两三条死线很正常；连着五条都不理人，
         * 基本可以断定是订阅地址失效或整网不通。按 3 秒一条算，最坏 15 秒给出失败出口，
         * 而不是陪用户转满一分钟。
         */
        private const val DEAD_LINE_LIMIT = 12

        /**
         * 同一条线路最多「回到直播边缘」几次。
         *
         * 3 次够覆盖两种情况：起播时窗口刚好滑走、重连时又滑走一次。再多就是源那边
         * 的窗口短到跟不上（比如只剩 2 个分片的极端 playlist），继续重选也只是把
         * 转圈拉长，不如老实认输换线路。
         */
        private const val MAX_LIVE_REALIGNS = 3

        /** 「连续多条线路没有任何响应」的失败措辞 */
        private const val REASON_NO_RESPONSE = "这个源连续多条线路都没有响应"
    }
}
