package com.leimao.tv.core.player

import android.content.Context
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
        videoPlayer.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val current = request ?: return
                    val lines = current.normalizedLines
                    when (playbackState) {
                        // 只认 BUFFERING：STATE_IDLE 会在出错后紧跟着回调一次，
                        // 如果把它也当成 Preparing，刚刚设好的 Failed 会被立刻覆盖，
                        // 用户看到的就是「一直转圈、永远不报错」
                        Player.STATE_BUFFERING ->
                            _state.value =
                                PlaybackState.Preparing(
                                    url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                                    item = current.item,
                                    lineIndex = lineIndex,
                                    lineCount = lines.size,
                                )

                        Player.STATE_READY -> {
                            stopWatchdog()
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

    companion object {
        /** 单条线路上连续重连的次数上限；再失败就该换线了（PLAY-08） */
        const val PLAYBACK_RETRY_LIMIT = 3

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
        private const val LINE_BUDGET_MS = 20_000L
    }
}
