package com.leimao.tv.core.player

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 降级链编排器（PLAY-02）。
 *
 * 把「首选内核」和「软解兜底内核」接成一条链：Media3 硬解（含系统软解 fallback）负责绝大多数
 * 源；当它把整份请求的所有线路都试过仍失败（[PlaybackState.Failed]）时，自动把**同一个请求**
 * 交给 VLC 软解再试一轮。UI 与上层只看到这一个内核，不知道下面有两个实现 ——
 * 这正是「换内核不改 UI」的落地形态。
 *
 * 为什么兜底只触发一次：VLC 也失败就认输。两个内核各试一轮所有线路已经足够穷尽
 * 「这个源能不能播」，再来回切只会把用户钉在错误页上更久。
 *
 * 另有一条**反方向**的规则（PLAY-11）：地址是 `rtsp://` / `rtmp://` 时直接让软解先上。
 * 这类地址（IPTV 单播源的主要形态）Media3 侧没有对应的数据源，按默认顺序只会先白等
 * 一轮「播放失败」；顺序反过来，用户感觉到的差别是换台快了两秒。
 *
 * 画面出口：当前活跃内核决定 `createSurfaceView` 返回什么。换内核时 [surfaceToken]
 * 会跟着变，上层必须用它当 `key` 重建 `AndroidView` —— 这个 View 的 `factory`
 * 一辈子只跑一次，光靠重组是换不掉画面的。
 */
class FallbackPlayerController(
    private val primary: PlayerController,
    /**
     * 换内核时的通知（诊断用）。
     *
     * 做成回调而不是在这里直接 `Log.d`：这个类是**纯编排逻辑**，有 JVM 单测覆盖；
     * 一调 `android.util.Log` 单测里就抛「not mocked」，而异常发生在协程里会被吞掉，
     * 表现成「兜底内核一次都没被唤起」，排查方向会被带偏到降级链本身。
     * 由 app 层注入真正的日志实现，核心模块保持可测。
     *
     * 放在 `fallbackFactory` 之前是有意的：它是带默认值的可选参数，
     * 而 [fallbackFactory] 必须留在最后，测试才能继续用尾随 lambda 写法。
     */
    private val onKernelSwitch: (from: String, to: String, reason: String) -> Unit = { _, _, _ -> },
    /**
     * 兜底内核**按需创建**（不传实例）。
     *
     * libVLC 的构造会加载约 50MB 原生库，而绝大多数起播根本用不到它（Media3 直接成功）。
     * 做成工厂后只有真的需要软解时才付这份钱 —— 实测常驻内存少约 35MB（130 → 95MB PSS）。
     */
    private val fallbackFactory: () -> PlayerController,
) : PlayerController,
    VideoSurfaceHost {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow<List<PlaybackTrack>>(emptyList())
    override val tracks: StateFlow<List<PlaybackTrack>> = _tracks.asStateFlow()

    /**
     * 加载统计（PLAY-12）在这里**镜像**当前内核的那一份。
     *
     * 不做成 `get() = active.loadStats`：切内核时 flow 实例会换掉，而 Compose 已经
     * 订阅了旧的 —— 不重新订阅就永远收不到新内核的数据（表现是浮层一直停在 0）。
     */
    private val _loadStats = MutableStateFlow(LoadStats.None)
    override val loadStats: StateFlow<LoadStats> = _loadStats.asStateFlow()

    /** 当前正在服务的那个内核；切内核就切它 */
    private var active: PlayerController = primary

    /**
     * 宿主（Activity）是否在前台（2026-11-07）。
     *
     * 默认 `true`：容器在 Application 里构造，早于第一个 `onStart`，冷启动起播不能被拦。
     * 播放器不随 Activity 销毁，光 `finish()` 停不住它 —— 必须有人显式把前后台告诉它。
     */
    private var hostForeground = true

    /** 本请求是否已经用过兜底内核；每个 `play()` 重置 */
    private var fallbackUsed = false

    private var currentRequest: PlaybackRequest? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var primaryJob: Job? = null
    private var fallbackJob: Job? = null

    /** 已经创建出来的兜底内核；null 表示还没用到过 */
    private var fallbackInstance: PlayerController? = null

    /**
     * 取兜底内核，第一次调用时才真正创建它并开始收它的状态。
     *
     * 状态收集必须跟着创建一起做：提前 collect 就得提前创建，白付 libVLC 的初始化成本。
     */
    private fun fallback(): PlayerController =
        fallbackInstance
            ?: fallbackFactory().also { created ->
                fallbackInstance = created
                fallbackJob =
                    scope.launch {
                        created.state.collect { s -> onFallbackState(s) }
                    }
                scope.launch {
                    created.loadStats.collect { stats ->
                        if (active === created) _loadStats.value = stats
                    }
                }
            }

    init {
        primaryJob =
            scope.launch {
                primary.state.collect { s -> onPrimaryState(s) }
            }
        scope.launch {
            primary.loadStats.collect { stats ->
                if (active === primary) _loadStats.value = stats
            }
        }
    }

    override fun createSurfaceView(context: Context): View =
        (active as? VideoSurfaceHost)?.createSurfaceView(context)
            ?: (primary as? VideoSurfaceHost)?.createSurfaceView(context)
            // 只在已经创建过兜底内核时才问它要 SurfaceView：没创建就说明这次播放用不到它，
            // 为了取一个 view 去加载 50MB 原生库是本末倒置
            ?: (fallbackInstance as? VideoSurfaceHost)?.createSurfaceView(context)
            ?: FrameLayout(context)

    /**
     * 画面出口代号 = **当前活跃内核本身**。
     *
     * 换内核（Media3 全线路失败 → VLC 兜底）时 `active` 换成另一个对象，
     * 这个代号跟着变，Compose 的 `key()` 就会把旧 View 拆掉、向新内核要一块新的。
     *
     * 不做成固定值是有原因的：`AndroidView` 的 `factory` 一辈子只调一次，
     * 代号不变就意味着画面永远挂在**旧内核**的 View 上 —— 换到 VLC 之后
     * 用户看到的是「有声音、没画面」，而且完全没有报错，极难从日志上看出来。
     */
    override val surfaceToken: Any get() = active

    override fun releaseSurfaceView(view: View) {
        // 不知道这块 View 是谁造的，问一圈；两边都会靠 `=== ` 自己排除掉不是自己的
        (primary as? VideoSurfaceHost)?.releaseSurfaceView(view)
        (fallbackInstance as? VideoSurfaceHost)?.releaseSurfaceView(view)
    }

    override fun play(request: PlaybackRequest) {
        // 后台一律不起播。自愈 / 下一集 / 重连 / 定时刷新都收敛到 play()，
        // 只在 Activity 里 stop() 一次是拦不住它们的：stop 之后异步路径照样能重新拉起。
        // 这道闸门是「后台不许出声」的最后一道，也是最要紧的一道。
        if (!hostForeground) {
            stop()
            return
        }
        currentRequest = request
        // rtsp / rtmp 直接交给软解内核（PLAY-11）：Media3 侧没引 RTSP 数据源，
        // 拿它去试只会白等一轮「播放失败」再切内核。电信/联通的 IPTV 单播源
        // 基本只有 rtsp，这个等待在换台时是能感觉到的。
        val preferFallback = requiresSoftwareKernel(request.url)
        fallbackUsed = preferFallback

        val next = if (preferFallback) fallback() else primary
        val previous = active
        active = next
        // 换内核前把另一个停掉：两个内核同时活着就是两路声音。
        // 从 rtsp 台（VLC）切回 http 台（Media3）时必然踩到。
        if (previous !== next) previous.stop()

        next.play(request)
        _state.value = next.state.value
        _tracks.value = next.tracks.value
    }

    /** 只有软解内核能播的协议。判据只看协议头，不看后缀：`rtsp://…/x.smil` 没有可用的后缀。 */
    private fun requiresSoftwareKernel(url: String): Boolean =
        url.startsWith("rtsp://", ignoreCase = true) || url.startsWith("rtmp://", ignoreCase = true)

    override fun retry() {
        if (!hostForeground) return
        active.retry()
    }

    override fun switchLine(delta: Int): Boolean {
        if (!hostForeground) return false
        return active.switchLine(delta)
    }

    override fun pause() = active.pause()

    override fun resume() {
        // 后台的 resume 多半来自延迟回调（控制条自动隐藏、焦点回归之类），一样拦掉
        if (!hostForeground) return
        active.resume()
    }

    override fun stop() {
        primary.stop()
        fallbackInstance?.stop()
        active = primary
        _state.value = PlaybackState.Idle
        _tracks.value = emptyList()
    }

    /**
     * 宿主前后台切换。进后台立刻 `stop()`（含两个内核、watchdog、统计轮询）；
     * 回前台**刻意不自动恢复** —— 见 [HostPlaybackAction.NO_AUTO_RESUME]。
     */
    override fun setHostForeground(foreground: Boolean) {
        hostForeground = foreground
        if (playbackActionOnHostChange(foreground) == HostPlaybackAction.STOP_ALL) stop()
    }

    override fun seekTo(positionMs: Long) = active.seekTo(positionMs)

    override fun setSpeed(speed: Float) = active.setSpeed(speed)

    override fun positionMs(): Long = active.positionMs()

    override fun durationMs(): Long = active.durationMs()

    override fun release() {
        primaryJob?.cancel()
        fallbackJob?.cancel()
        scope.cancel()
        primary.release()
        fallbackInstance?.release()
        _state.value = PlaybackState.Idle
    }

    override fun selectTrack(trackId: String) = active.selectTrack(trackId)

    override fun disableTracks(kind: TrackKind) = active.disableTracks(kind)

    private fun onPrimaryState(s: PlaybackState) {
        if (active !== primary) return
        if (s is PlaybackState.Failed && !fallbackUsed && currentRequest != null) {
            fallbackUsed = true
            val soft = fallback()
            active = soft
            // 这一行是「有声音没画面」类问题的唯一线索：换内核后 UI 必须靠 surfaceToken
            // 重建画面出口，日志里看到这行而画面没恢复，问题就在渲染侧而不在解码侧
            onKernelSwitch("硬解", "软解", s.reason)
            soft.play(currentRequest!!)
            _state.value = soft.state.value
            _tracks.value = soft.tracks.value
        } else {
            _state.value = s
            _tracks.value = primary.tracks.value
        }
    }

    private fun onFallbackState(s: PlaybackState) {
        val soft = fallbackInstance ?: return
        if (active !== soft) return
        _state.value = s
        _tracks.value = soft.tracks.value
    }
}
