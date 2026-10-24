package com.leimao.tv.core.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    override val videoPlayer: ExoPlayer = buildPlayer()

    private var request: PlaybackRequest? = null
    private var lineIndex = 0
    private var retriesLeft = retryLimit

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
            },
        )
    }

    override fun play(request: PlaybackRequest) {
        this.request = request
        lineIndex = 0
        retriesLeft = retryLimit
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

    override fun positionMs(): Long = videoPlayer.currentPosition.coerceAtLeast(0L)

    override fun durationMs(): Long = videoPlayer.duration.coerceAtLeast(0L)

    override fun release() {
        stopWatchdog()
        request = null
        videoPlayer.release()
        _state.value = PlaybackState.Idle
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

        videoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(MediaItem.fromUri(url)))
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
         * 单条线路的时间预算。
         *
         * 连接超时 10s 已经用掉一半，留 20s 是为了「慢但能通」的源也有机会出画，
         * 同时保证最坏情况下用户 40 秒内一定能看到失败出口（两条线路各 20s）。
         */
        private const val LINE_BUDGET_MS = 20_000L
    }
}
