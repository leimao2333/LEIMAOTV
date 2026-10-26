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
 * 关于「软解兜底」：这一版用 Media3 的 [DefaultRenderersFactory.setEnableDecoderFallback]
 * —— 硬解初始化失败时自动退到系统软解。内置 libVLC 内核是 D-02 的决策，但它是 +20MB 的
 * native 依赖，按 RULES §4 的体积条款必须单独一个 PR 写清代价与替代方案，不能混在功能提交里。
 */
class Media3PlayerController(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val retryLimit: Int = PLAYBACK_RETRY_LIMIT,
    private val bufferProfile: BufferProfile = BufferProfile.BALANCED,
) : PlayerController,
    VideoSurfaceHost {
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

    override fun createSurfaceView(context: Context): View =
        PlayerView(context).apply {
            useController = false
            // 直播不裁画面：宁可留黑边，也不要把台标和字幕切掉
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            player = videoPlayer
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

    init {
        startStatsTicker()
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
        suppressRebufferUntil = 0L
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
        request = null
        clearTracks()
        videoPlayer.release()
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

        _state.value =
            PlaybackState.Preparing(
                url = url,
                item = current.item,
                lineIndex = lineIndex,
                lineCount = lines.size,
            )

        // 每个源有自己的 UA / Referer，所以 data source 必须按请求重建（不能全局共用一份）
        val mediaSourceFactory =
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    OkHttpDataSource.Factory(okHttpClient)
                        .setDefaultRequestProperties(current.headers),
                ),
            )

        // 续播点只在本轮第一次建流时用；之后接着断点播（见 resumeFromMs 的注释）
        val startAt = (if (firstStart) resumeFromMs else videoPlayer.currentPosition).coerceAtLeast(0L)
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
     * 这里给每条线路一个时间预算，超了就当这条线路失败 —— 用户在电视前等 20 秒
     * 已经够久了，不能再让他等下去（§7 的换台预算是 1.5s，失败路径也必须收敛）。
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog =
            scope.launch {
                delay(LINE_BUDGET_MS)
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

        // 「等了 20 秒一个字节都没来」不重试同一条线：同一个地址再等 20 秒也是同样的结果，
        // 只是把用户多关在转圈里 80 秒（3 次重试 + 本轮）。直接交给下一条线路。
        // 只有**传输层真的报错**（IO/HTTP）才值得原地重试 —— 那种多半是瞬时抖动。
        val nothingArrived = _loadStats.value.bytesLoaded <= 0L
        if (reason == REASON_TIMEOUT && nothingArrived) {
            retriesLeft = 0
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
     */

    private fun startStatsTicker() {
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
            // 缓冲档位由设置页（SET-01）决定，默认均衡档
            .setLoadControl(bufferProfile.loadControl())
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

        /** 判定「这条线路供不上」的观察窗口（PLAY-13） */
        private const val REBUFFER_WINDOW_MS = 30_000L

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
         * 单条线路的时间预算。
         *
         * 连接超时 10s 已经用掉一半，留 20s 是为了「慢但能通」的源也有机会出画，
         * 同时保证最坏情况下用户 40 秒内一定能看到失败出口（两条线路各 20s）。
         */
        private const val LINE_BUDGET_MS = 15_000L
    }
}
