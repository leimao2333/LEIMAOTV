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
 * 画面出口：当前活跃内核决定 `createSurfaceView` 返回什么。切内核后，上层在下次重组时
 * 重新取一次 view 即可（[VlcPlayerController.createSurfaceView] 会复用同一个 SurfaceView）。
 */
class FallbackPlayerController(
    private val primary: PlayerController,
    private val fallback: PlayerController,
) : PlayerController,
    VideoSurfaceHost {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow<List<PlaybackTrack>>(emptyList())
    override val tracks: StateFlow<List<PlaybackTrack>> = _tracks.asStateFlow()

    /** 当前正在服务的那个内核；切内核就切它 */
    private var active: PlayerController = primary

    /** 本请求是否已经用过兜底内核；每个 `play()` 重置 */
    private var fallbackUsed = false

    private var currentRequest: PlaybackRequest? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var primaryJob: Job? = null
    private var fallbackJob: Job? = null

    init {
        primaryJob =
            scope.launch {
                primary.state.collect { s -> onPrimaryState(s) }
            }
        fallbackJob =
            scope.launch {
                fallback.state.collect { s -> onFallbackState(s) }
            }
    }

    override fun createSurfaceView(context: Context): View =
        (active as? VideoSurfaceHost)?.createSurfaceView(context)
            ?: (primary as? VideoSurfaceHost)?.createSurfaceView(context)
            ?: (fallback as? VideoSurfaceHost)?.createSurfaceView(context)
            ?: FrameLayout(context)

    override fun play(request: PlaybackRequest) {
        currentRequest = request
        // rtsp / rtmp 直接交给软解内核（PLAY-11）：Media3 侧没引 RTSP 数据源，
        // 拿它去试只会白等一轮「播放失败」再切内核。电信/联通的 IPTV 单播源
        // 基本只有 rtsp，这个等待在换台时是能感觉到的。
        val preferFallback = requiresSoftwareKernel(request.url)
        fallbackUsed = preferFallback

        val next = if (preferFallback) fallback else primary
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
        active.retry()
    }

    override fun switchLine(delta: Int): Boolean = active.switchLine(delta)

    override fun stop() {
        primary.stop()
        fallback.stop()
        active = primary
        _state.value = PlaybackState.Idle
        _tracks.value = emptyList()
    }

    override fun pause() = active.pause()

    override fun resume() = active.resume()

    override fun seekTo(positionMs: Long) = active.seekTo(positionMs)

    override fun setSpeed(speed: Float) = active.setSpeed(speed)

    override fun positionMs(): Long = active.positionMs()

    override fun durationMs(): Long = active.durationMs()

    override fun release() {
        primaryJob?.cancel()
        fallbackJob?.cancel()
        scope.cancel()
        primary.release()
        fallback.release()
        _state.value = PlaybackState.Idle
    }

    override fun selectTrack(trackId: String) = active.selectTrack(trackId)

    override fun disableTracks(kind: TrackKind) = active.disableTracks(kind)

    private fun onPrimaryState(s: PlaybackState) {
        if (active !== primary) return
        if (s is PlaybackState.Failed && !fallbackUsed && currentRequest != null) {
            fallbackUsed = true
            active = fallback
            fallback.play(currentRequest!!)
            _state.value = fallback.state.value
            _tracks.value = fallback.tracks.value
        } else {
            _state.value = s
            _tracks.value = primary.tracks.value
        }
    }

    private fun onFallbackState(s: PlaybackState) {
        if (active !== fallback) return
        _state.value = s
        _tracks.value = fallback.tracks.value
    }
}
