package com.leimao.tv.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 降级链编排（PLAY-02）。
 *
 * 这段逻辑唯一值得单测的是「什么时候切内核、切完谁说了算」：主内核 Failed 之前状态透传，
 * Failed 之后同一个请求转给兜底内核，兜底内核的状态接替成为对外状态。两个内核各自的
 * 重连/换线逻辑已经各有实现，这里只钉住编排层的切换规则。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FallbackPlayerControllerTest {
    @Before
    fun setUp() {
        // FallbackPlayerController 用 Dispatchers.Main.immediate 收集状态，纯 JVM 单测里
        // 没有 Android 主线程，必须换成一个测试调度器
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 可控内核：想让它成功就成功、想让它失败就失败，状态直接由测试推 */
    private class ScriptedPlayer : PlayerController {
        override val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val tracks = MutableStateFlow<List<PlaybackTrack>>(emptyList())

        var lastRequest: PlaybackRequest? = null
        var playCount = 0

        override fun play(request: PlaybackRequest) {
            lastRequest = request
            playCount++
        }

        override fun retry() = Unit

        override fun switchLine(delta: Int): Boolean = false

        override fun stop() {
            state.value = PlaybackState.Idle
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun positionMs(): Long = 0L

        override fun durationMs(): Long = 0L

        override fun release() = Unit

        override fun selectTrack(trackId: String) = Unit

        override fun disableTracks(kind: TrackKind) = Unit
    }

    private val request =
        PlaybackRequest(
            item = PlaybackItem.Channel(channelId = 1L),
            url = "http://a/live.m3u8",
            lines = listOf("http://a/live.m3u8", "http://b/live.m3u8"),
        )

    @Test
    fun `主内核没失败时状态透传`() {
        val primary = ScriptedPlayer()
        val fallback = ScriptedPlayer()
        val sut = FallbackPlayerController(primary) { fallback }

        sut.play(request)
        primary.state.value =
            PlaybackState.Playing(url = "http://a/live.m3u8", item = request.item, lineIndex = 0, lineCount = 2)

        assertTrue(sut.state.value is PlaybackState.Playing)
        // 兜底内核不该被唤起
        assertEquals(0, fallback.playCount)
    }

    @Test
    fun `主内核失败后同一个请求转给兜底内核`() {
        val primary = ScriptedPlayer()
        val fallback = ScriptedPlayer()
        val sut = FallbackPlayerController(primary) { fallback }

        sut.play(request)
        primary.state.value =
            PlaybackState.Failed(
                url = "http://b/live.m3u8",
                item = request.item,
                reason = "线路用尽",
                lineIndex = 1,
                lineCount = 2,
            )

        // 兜底内核被唤起，且拿到的是同一个请求
        assertEquals(1, fallback.playCount)
        assertSame(request, fallback.lastRequest)
    }

    @Test
    fun `兜底内核失败不再回切`() {
        val primary = ScriptedPlayer()
        val fallback = ScriptedPlayer()
        val sut = FallbackPlayerController(primary) { fallback }

        sut.play(request)
        primary.state.value =
            PlaybackState.Failed(
                url = "http://b/live.m3u8",
                item = request.item,
                reason = "线路用尽",
                lineIndex = 1,
                lineCount = 2,
            )

        // 兜底也失败：对外状态就是兜底的 Failed，主内核不会二次接管
        fallback.state.value =
            PlaybackState.Failed(
                url = "http://b/live.m3u8",
                item = request.item,
                reason = "软解也解不了",
                lineIndex = 1,
                lineCount = 2,
            )

        val state = sut.state.value
        assertTrue(state is PlaybackState.Failed)
        assertEquals("软解也解不了", (state as PlaybackState.Failed).reason)
        assertEquals(1, fallback.playCount) // 兜底只被唤起一次，不因自己 Failed 再触发
    }

    @Test
    fun `每个新请求都重置兜底开关`() {
        val primary = ScriptedPlayer()
        val fallback = ScriptedPlayer()
        val sut = FallbackPlayerController(primary) { fallback }

        // 第一次请求走完降级
        sut.play(request)
        primary.state.value =
            PlaybackState.Failed(url = "u", item = request.item, reason = "x", lineIndex = 1, lineCount = 2)
        assertEquals(1, fallback.playCount)

        // 第二次请求：兜底开关重置，主内核失败仍会再次唤起兜底
        sut.play(request)
        // 真实内核在 play() 时会把状态拨回 Preparing，这里模拟这一步（否则 StateFlow
        // 因「上一次已是 Failed」去重、不再发射，反而测不到真实的降级路径）
        primary.state.value =
            PlaybackState.Preparing(url = "u", item = request.item, lineIndex = 0, lineCount = 2)
        primary.state.value =
            PlaybackState.Failed(url = "u", item = request.item, reason = "x", lineIndex = 1, lineCount = 2)
        assertEquals(2, fallback.playCount)
    }

    @Test
    fun `switchLine 转发给当前活跃内核`() {
        val primary = ScriptedPlayer()
        val fallback = ScriptedPlayer()
        val sut = FallbackPlayerController(primary) { fallback }
        sut.play(request)
        // 活跃内核仍是 primary，switchLine 由它处理（这里 ScriptedPlayer 恒返回 false）
        assertEquals(false, sut.switchLine(1))
    }

    // ---- rtsp / rtmp 直接走软解（PLAY-11）----

    private fun rtspRequest() =
        PlaybackRequest(
            item = PlaybackItem.Channel(channelId = 2L),
            url = "rtsp://example.com/live/1.smil",
            lines = listOf("rtsp://example.com/live/1.smil"),
        )

    @Test
    fun `rtsp 地址直接交给软解内核`() {
        val primary = ScriptedPlayer()
        val fallback = ScriptedPlayer()
        val sut = FallbackPlayerController(primary) { fallback }

        sut.play(rtspRequest())

        // Media3 侧没有 RTSP 数据源，让它先试一轮只是白等
        assertEquals(0, primary.playCount)
        assertEquals(1, fallback.playCount)
        assertEquals("rtsp://example.com/live/1.smil", fallback.lastRequest?.url)
    }

    @Test
    fun `rtsp 软解失败后不再回头试硬解`() {
        val primary = ScriptedPlayer()
        val fallback = ScriptedPlayer()
        val sut = FallbackPlayerController(primary) { fallback }

        sut.play(rtspRequest())
        fallback.state.value =
            PlaybackState.Failed(
                url = "rtsp://example.com/live/1.smil",
                item = rtspRequest().item,
                reason = "播不了",
                lineIndex = 0,
                lineCount = 1,
            )

        // 两个内核来回切只会把用户钉在错误页上更久；硬解本来也播不了 rtsp
        assertEquals(0, primary.playCount)
        assertTrue(sut.state.value is PlaybackState.Failed)
    }

    @Test
    fun `从 rtsp 台切回 http 台会停掉软解内核`() {
        val primary = ScriptedPlayer()
        val fallback = ScriptedPlayer()
        val sut = FallbackPlayerController(primary) { fallback }

        sut.play(rtspRequest())
        sut.play(request)

        // 两个内核同时活着就是两路声音：换台时另一个必须停
        assertEquals(PlaybackState.Idle, fallback.state.value)
        assertEquals(1, primary.playCount)
        assertTrue(primary.lastRequest === request)
    }
}
