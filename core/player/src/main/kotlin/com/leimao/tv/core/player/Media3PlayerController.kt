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

    private val videoPlayer: ExoPlayer = buildPlayer()

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

    override val surfaceToken: Any get() = this

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

    init {
        videoPlayer.addListener(
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
                            _state.value =
                                PlaybackState.Preparing(
                                    url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                                    item = current.item,
                                    lineIndex = lineIndex,
                                    lineCount = lines.size,
                                )
                        }

                        Player.STATE_READY -> {
                            stopWatchdog()
                            renderedOnce = true
                            _state.value =
                                PlaybackState.Playing(
                                    url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                                    item = current.item,
                                    lineIndex = lineIndex,
                                    lineCount = lines.size,
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

                override fun onPlayerError(error: PlaybackException) {
                    handleFailure(reason = describe(error))
                }

                // 换集 / 换线路 / 起播后轨道都会变。INFO 没有内容，不刷（每次都重建列表
                // 会让「音轨」面板在播放中被无意义地重绘）
                override fun onTracksChanged(tracks: Tracks) {
                    refreshTracks()
                }
            },
        )
    }

    override fun play(request: PlaybackRequest) {
        this.request = request
        lineIndex = 0
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
        suppressRebufferUntil = 0L
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

        val next = (lineIndex + delta).coerceIn(0, lines.lastIndex)
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
    }

    override fun resume() {
        videoPlayer.play()
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

    private fun startCurrentLine() {
        val current = request ?: return
        val lines = current.normalizedLines
        val url = lines[lineIndex.coerceIn(0, lines.lastIndex)]

        // 每起一条新线路都要重算「回过数据没有」：换线 / 重连 / 重试都会走到这里。
        // 探针按**时间戳**比对，所以上一条线路的迟到响应不会被算到这一条头上
        networkMonitor.reset()
        lineStartedAt = SystemClock.elapsedRealtime()

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

        videoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(MediaItem.fromUri(url)), startAt)
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
        watchdog?.cancel()
        watchdog =
            scope.launch {
                delay(FIRST_BYTE_BUDGET_MS)
                if (_state.value is PlaybackState.Preparing && !networkMonitor.respondedSince(lineStartedAt)) {
                    handleFailure(reason = REASON_TIMEOUT)
                    return@launch
                }
                delay((LINE_BUDGET_MS - FIRST_BYTE_BUDGET_MS).coerceAtLeast(0L))
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
        // 判据是「这次请求有没有收到响应头」：字节数要等整个分片下完（可能好几秒），
        // 而服务器只要活着，响应头几十毫秒就到。
        val nothingArrived = !networkMonitor.respondedSince(lineStartedAt)
        // 一个成功响应都没拿到（连接拒了 / 404 / 403 / 干脆不回包）时，原地重试同一个地址
        // 不会有别的结果，只是把用户多关在转圈里几个预算周期。直接换下一条线路。
        if (nothingArrived) {
            retriesLeft = 0
        }

        // 连续几条线路都「一个字节没来」= 不是线路的问题，是源或网络整体不可用。
        // 聚合源里一个台常有十几二十条线路，逐条走完要一分钟 —— 这段时间屏幕上只有转圈，
        // 用户不知道发生了什么。到 [DEAD_LINE_LIMIT] 就认输，把失败出口交出去。
        if (nothingArrived) deadLines++
        if (nothingArrived && deadLines >= DEAD_LINE_LIMIT && retriesLeft <= 0) {
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
            lineIndex++
            retriesLeft = retryLimit
            startCurrentLine()
            return
        }

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
                    _loadStats.value =
                        current.copy(
                            buffering = videoPlayer.playbackState == Player.STATE_BUFFERING,
                            bufferedMs = buffered,
                            bytesPerSecond = speed,
                        )
                }
            }
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

    private fun buildPlayer(): ExoPlayer =
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    // 硬解初始化失败时退到系统软解，这是 PLAY-02 在 Media3 内能做到的兜底
                    .setEnableDecoderFallback(true),
            )
            // 缓冲档位由设置页（SET-01）决定，默认均衡档。
            // 设备内存档位在这里注入：同一档在 2GB 盒子上要按字节收紧，
            // 否则 4K 码流会把进程撑到被系统杀掉（见 MemoryLevel 的说明）
            .setLoadControl(
                bufferProfile.loadControl(
                    MemoryLevel.of(context).also {
                        Log.d(PlaybackDiagnostics.TAG, "内存档位 ${MemoryLevel.describe(context)}")
                    },
                ),
            )
            .build()
            // 诊断日志：解码器是硬解还是软解、分辨率帧率、丢帧数 —— 用户说「卡」时先看这个
            .apply {
                addAnalyticsListener(PlaybackDiagnostics())
                // 同一批事件里顺手攒出「已加载多少字节」，喂给加载浮层（PLAY-12）
                addAnalyticsListener(loadTracker)
            }

    companion object {
        /** 单条线路上连续重连的次数上限；再失败就该换线了（PLAY-08） */
        const val PLAYBACK_RETRY_LIMIT = 3

        /** 加载统计的轮询间隔（PLAY-12）。见 startStatsTicker 的说明 */
        private const val STATS_TICK_MS = 500L

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
         */
        private const val AUTO_SWITCH_LIMIT = 3

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
         * 连续多少条线路「一个字节都没回」就直接认输。
         *
         * 5 条：源里线路质量参差是常态，偶尔撞上两三条死线很正常；连着五条都不理人，
         * 基本可以断定是订阅地址失效或整网不通。按 3 秒一条算，最坏 15 秒给出失败出口，
         * 而不是陪用户转满一分钟。
         */
        private const val DEAD_LINE_LIMIT = 5

        /** 「连续多条线路没有任何响应」的失败措辞 */
        private const val REASON_NO_RESPONSE = "这个源连续多条线路都没有响应"
    }
}
