package com.leimao.tv.core.player

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.SurfaceView
import android.view.View
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

    /** 本次播放里连超时的线路数（见 `handleFailure` 的收敛规则） */
    private var deadLines = 0
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
        // 先把上一块出口摘干净再建新的。
        //
        // libVLC 的 IVLCVout 对**同一个实例**重复 attachViews() 是原生断言，
        // 结果是 SIGABRT —— Kotlin 的未捕获异常兜底（CrashArchive）根本抓不到，
        // 用户看到的是「进播放页直接没了」。所以 attach 之前必须确保是 detach 状态。
        detachVout()
        val view =
            SurfaceView(context).apply {
                setBackgroundColor(Color.BLACK)
                vout.setVideoSurface(holder.surface, holder)
                vout.attachViews()
            }
        surfaceView = view
        return view
    }

    override val surfaceToken: Any get() = this

    override fun releaseSurfaceView(view: View) {
        // 只认自己的那一块：兜底链里 Media3 与 VLC 共用 VideoSurfaceHost，
        // 上层不保证把谁造的 View 还给了谁
        if (surfaceView === view) {
            detachVout()
            surfaceView = null
        }
    }

    /** 摘掉视频出口。libVLC 这边 detach 与 attach 必须成对，缺一次就是原生崩溃 */
    private fun detachVout() {
        runCatching { if (vout.areViewsAttached()) vout.detachViews() }
    }

    override fun play(request: PlaybackRequest) {
        this.request = request
        lineIndex = 0
        retriesLeft = retryLimit
        deadLines = 0
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
        // detach 必须排在 release 前面：带着已 attach 的 vout 去释放 libVLC，
        // 是 native 层的 use-after-free / 断言，属于最难查的一类崩溃
        detachVout()
        surfaceView = null
        mediaPlayer.stop()
        mediaPlayer.release()
        libVlc.release()
        scope.cancel()
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
            Media(libVlc, Uri.parse(vlcLocation(url))).apply {
                // 每个源有自己的 UA / Referer，VLC 用 :http-user-agent 与 :http-referrer 选项
                current.headers["User-Agent"]?.let { addOption(":http-user-agent=$it") }
                current.headers["Referer"]?.let { addOption(":http-referrer=$it") }
                // 软解：avcodec-hw=none 已在 LibVLC 实例级设好，这里显式关掉硬解开关兜底
                setHWDecoderEnabled(false, false)
            }

        mediaPlayer.media = media
        media.release()

        // 续播点的语义：本轮第一次建流用请求里给的位置，之后重连接着断点走。
        // 但**已经播到末尾时不能拿它当续播点** —— 直播断流常表现为「播到结束」，
        // 此时 mediaPlayer.time 就停在总时长上，直接续到末尾会让新流一秒内又结束，
        // 用户看到的是画面一闪一闪反复重连。靠近末尾一律从 0 重来。
        val rawStart = if (firstStart) resumeFromMs else mediaPlayer.time
        val total = mediaPlayer.length
        val atEnd = total > 0L && rawStart >= total - END_GUARD_MS
        val startAt = if (atEnd) 0L else rawStart.coerceAtLeast(0L)
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
                    handleFailure(reason = REASON_TIMEOUT)
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

        // 超时 = 这条线路一直没出画面，原地重试只会把用户再关在转圈里一个预算周期。
        // Media3 那边对「一个字节都没来」也是直接换线（`nothingArrived`），
        // 两个内核的失败观感要对齐，否则降级之后忽然变慢，用户以为是换了个 App
        if (reason == REASON_TIMEOUT) {
            retriesLeft = 0
            deadLines++
        }

        // 兜底内核本来就在「Media3 已经把整轮试完」之后才上，再逐条走完十几条线路
        // 等于让用户多等一分钟。连着三条超时就直接认输，把失败出口交出去
        if (deadLines >= DEAD_LINE_LIMIT) {
            stopWatchdog()
            _state.value =
                PlaybackState.Failed(
                    url = lines[lineIndex.coerceIn(0, lines.lastIndex)],
                    item = current.item,
                    reason = reason,
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

    companion object {
        /** 与 Media3 内核一致的重连上限：降级链的语义两内核要对齐 */
        const val PLAYBACK_RETRY_LIMIT = 3

        /**
         * 与 Media3 内核一致的单线时间预算。
         *
         * 2026-11-04 从 20s 压到 10s：VLC 只在 Media3 全轮失败后才接管，那时每条线路
         * 已经被证明过一次不可用，再给 20 秒只是把失败出口往后推。
         */
        private const val LINE_BUDGET_MS = 10_000L

        /** 超时的统一措辞：`handleFailure` 靠它判「不值得原地重试」 */
        private const val REASON_TIMEOUT = "这条线路一直没出画面"

        /**
         * 兜底内核连几条超时就认输。
         *
         * 比 Media3 的 5 条更小：走到这里说明 Media3 已经失败过一整轮，
         * 每条线路的价值都已经下降，没有理由再陪跑十几条。
         */
        private const val DEAD_LINE_LIMIT = 3

        /**
         * 距末尾多近算「已经播完」。
         *
         * 直播断流在 VLC 里表现为播放位置停在片子末尾；续播点正好落在末尾时，
         * 新起的流会立刻再次结束，形成一闪一闪的重连死循环。1.5 秒是留给人眼
         * 分辨「这是刚才那一帧还是新画面」的余量，再小就会把正常的短暂卡顿判成播完。
         */
        private const val END_GUARD_MS = 1_500L
    }
}

/**
 * 交给 libVLC 的地址：**必须带 scheme**。
 *
 * 这是 libVLC 的 Java 层一个很深的坑：`Media(LibVLC, String)` 与 `Media(LibVLC, Uri)`
 * 语义不同 —— 前者走 `libvlc_media_new_path`（**本地文件路径**），后者走
 * `libvlc_media_new_location`（网络地址）。传 String 时哪怕内容就是 `http://…`，
 * libVLC 也会把它当相对路径，补成绝对路径再转成 `file://` URI：
 *
 * ```
 * 传 "http://host/a.m3u8"
 *   → cannot open file //http://host/a.m3u8 (No such file or directory)
 *   → VLC is unable to open the MRL 'file:////http%3A//host%2Fa.m3u8'
 * ```
 *
 * 后果不是「VLC 播不了这一条」，而是**兜底内核整体失效**：Media3 播不动才叫 VLC，
 * 而 VLC 必然失败，用户看到的永远是「所有线路都试过了」（2026-11-02 模拟器日志实测）。
 *
 * 所以这里统一补 scheme：有 `scheme:` 的原样给（http / https / rtsp / rtmp / udp / file），
 * 没有的按本地文件补 `file://`。返回值交给 `Uri.parse` 再进 `Media`。
 */
internal fun vlcLocation(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return trimmed
    // 除 scheme 外还要认 "file:" —— 本地文件常写成 file:/sdcard/a.mp4（单斜杠也合法）
    if (trimmed.startsWith("file:", ignoreCase = true)) return trimmed
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "")
    val hasScheme =
        scheme.isNotEmpty() &&
            scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } &&
            trimmed.getOrNull(scheme.length) == ':'
    return if (hasScheme) trimmed else "file://$trimmed"
}
