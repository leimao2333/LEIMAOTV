package com.leimao.tv.core.player

import android.content.Context
import android.graphics.Color
import android.view.SurfaceView
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * libVLC 软解兜底内核（PLAY-02 / D-02）。
 *
 * 它不做「首选内核」，只在 Media3 硬解失败、线路用尽之后才接管 —— 这是降级链的最后一环：
 *
 * ```
 * Media3 硬解 → 换线路 ×N → VLC 软解兜底 → Failed
 * ```
 *
 * 为什么 VLC 是兜底而不是平起平坐：libvlc-all 是 80MB 的 native 依赖，把它默认加载进
 * 内存换来的只是「少数几个硬解不支持的编码也能播」。而 Media3 的
 * `setEnableDecoderFallback` 已经覆盖了系统软解，VLC 补的是「连系统软解都没有」的那批
 * 私有/冷门编码。所以 VLC 只在真的播不动时才被唤起。
 *
 * 内核无关性：这里和 [Media3PlayerController] 实现同一个 [PlayerController]，UI 不知道
 * 自己接的是哪个内核 —— 换内核不改 UI（架构设计 §6）。
 */
class VlcPlayerController(
    private val context: Context,
    private val retryLimit: Int = PLAYBACK_RETRY_LIMIT,
) : PlayerController,
    VideoSurfaceHost {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // VLC 的轨道枚举是它的内部对象，翻译成内核无关的 PlaybackTrack 才能给 UI。
    // 直播绝大多数流没有可选轨道，先给空表 —— VOD 的音轨/字幕切换是后续项，不在本内核首版。
    private val _tracks = MutableStateFlow<List<PlaybackTrack>>(emptyList())
    override val tracks: StateFlow<List<PlaybackTrack>> = _tracks.asStateFlow()

    private val libVlc: LibVLC =
        LibVLC(
            context,
            // 注意：LibVLC 构造器会往这个 list 里 add 元素（写回默认选项），必须传可变 list，
            // 传 listOf() 会在构造时抛 UnsupportedOperationException
            mutableListOf(
                // 软解优先：这正是「兜底」的语义 —— 硬解已经试过失败了，这里直接用 CPU 解
                "--avcodec-hw=none",
                // 低延迟：直播场景下关掉大的预读缓冲，追实时
                "--network-caching=1500",
                // 别把日志刷到 stdout 去骚扰 release 包（RULES §8.6）
                "--quiet",
            ),
        )

    private val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)

    private var surfaceView: SurfaceView? = null
    private val vout: IVLCVout = mediaPlayer.getVLCVout()

    private var request: PlaybackRequest? = null
    private var lineIndex = 0
    private var retriesLeft = retryLimit
    private var resumeFromMs = 0L
    private var firstStart = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdog: Job? = null

    init {
        mediaPlayer.setEventListener(
            object : MediaPlayer.EventListener {
                override fun onEvent(event: MediaPlayer.Event) {
                    when (event.type) {
                        MediaPlayer.Event.Playing -> onPlaying()
                        MediaPlayer.Event.EncounteredError -> onError()
                        MediaPlayer.Event.EndReached -> onEndReached()
                        else -> Unit
                    }
                }
            },
        )
    }

    override fun createSurfaceView(context: Context): View {
        // 复用同一份 SurfaceView：内核只有一个 mediaPlayer，画面也只能有一个出口
        val view =
            surfaceView
                ?: SurfaceView(context).apply {
                    setBackgroundColor(Color.BLACK)
                    vout.setVideoSurface(holder.surface, holder)
                    vout.attachViews()
                }.also { surfaceView = it }
        return view
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
        mediaPlayer.stop()
        _state.value = PlaybackState.Idle
    }

    override fun pause() {
        mediaPlayer.pause()
    }

    override fun resume() {
        mediaPlayer.play()
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer.time = positionMs.coerceAtLeast(0L)
    }

    override fun setSpeed(speed: Float) {
        mediaPlayer.rate = speed.coerceIn(0.25f, 4f)
    }

    override fun positionMs(): Long = mediaPlayer.time.coerceAtLeast(0L)

    override fun durationMs(): Long = mediaPlayer.length.coerceAtLeast(0L)

    override fun release() {
        stopWatchdog()
        request = null
        mediaPlayer.stop()
        mediaPlayer.release()
        libVlc.release()
        _state.value = PlaybackState.Idle
    }

    // VLC 的轨道切换：本内核首版不接音轨/字幕切换（直播场景几乎没有），留空实现保持接口完整
    override fun selectTrack(trackId: String) = Unit

    override fun disableTracks(kind: TrackKind) = Unit

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

        val media =
            Media(libVlc, url).apply {
                // 每个源有自己的 UA / Referer，VLC 用 :http-user-agent 与 :http-referrer 选项
                current.headers["User-Agent"]?.let { addOption(":http-user-agent=$it") }
                current.headers["Referer"]?.let { addOption(":http-referrer=$it") }
                // 软解：avcodec-hw=none 已在 LibVLC 实例级设好，这里显式关掉硬解开关兜底
                setHWDecoderEnabled(false, false)
            }

        mediaPlayer.media = media
        media.release()

        val startAt = (if (firstStart) resumeFromMs else mediaPlayer.time).coerceAtLeast(0L)
        firstStart = false
        if (startAt > 0L) {
            mediaPlayer.time = startAt
        }

        mediaPlayer.play()
        startWatchdog()
    }

    private fun onPlaying() {
        stopWatchdog()
        val current = request ?: return
        val lines = current.normalizedLines
        _state.value =
            PlaybackState.Playing(
                url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                item = current.item,
                lineIndex = lineIndex,
                lineCount = lines.size,
            )
    }

    private fun onError() {
        handleFailure(reason = "软解也解不了这个源")
    }

    private fun onEndReached() {
        val current = request ?: return
        if (current.kind == PlaybackKind.VOD) {
            stopWatchdog()
            val lines = current.normalizedLines
            _state.value =
                PlaybackState.Ended(
                    url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                    item = current.item,
                    lineIndex = lineIndex,
                    lineCount = lines.size,
                )
        } else {
            handleFailure(reason = "直播流已结束")
        }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog =
            scope.launch {
                delay(LINE_BUDGET_MS)
                if (_state.value is PlaybackState.Preparing) {
                    handleFailure(reason = "这条线路一直没出画面")
                }
            }
    }

    private fun stopWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

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

    companion object {
        /** 与 Media3 内核一致的重连上限：降级链的语义两内核要对齐 */
        const val PLAYBACK_RETRY_LIMIT = 3

        /** 与 Media3 内核一致的单线时间预算 */
        private const val LINE_BUDGET_MS = 20_000L
    }
}
