package com.leimao.tv.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「宿主离开前台就不许有播放」的闸门（2026-11-07 用户反馈：退出后后台还在出声）。
 *
 * 这里钉住的不是某个 UI 按钮，而是编排器本身的契约：Activity 只负责告诉它前后台，
 * 停流、拒绝后台起播、回前台不自动续播，全都在这一层兜住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostPlaybackGuardTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val httpRequest =
        PlaybackRequest(
            item = PlaybackItem.Channel(channelId = 1L),
            url = "http://example.invalid/live.m3u8",
        )
    private val rtspRequest =
        PlaybackRequest(
            item = PlaybackItem.Channel(channelId = 2L),
            url = "rtsp://example.invalid/live",
        )

    @Test
    fun `策略 进后台彻底停 回前台不自动恢复`() {
        assertEquals(HostPlaybackAction.STOP_ALL, playbackActionOnHostChange(hostForeground = false))
        assertEquals(
            HostPlaybackAction.NO_AUTO_RESUME,
            playbackActionOnHostChange(hostForeground = true),
        )
    }

    @Test
    fun `进后台立刻停掉当前内核并把状态清回 Idle`() {
        val primary = RecordingPlayer()
        val controller = newController(primary)

        controller.play(httpRequest)
        assertEquals(1, primary.playCount)

        controller.setHostForeground(false)

        assertEquals(1, primary.stopCount)
        assertEquals(PlaybackState.Idle, controller.state.value)
        controller.release()
    }

    @Test
    fun `两个内核都活着时 进后台一起停 不留第二路声音`() {
        val primary = RecordingPlayer()
        val fallback = RecordingPlayer()
        val controller = newController(primary, fallback)

        // rtsp 直接走软解内核，于是兜底实例被真的创建出来
        controller.play(rtspRequest)
        assertEquals(1, fallback.playCount)
        // 换内核时 primary 已经被停过一次（避免两路声音），这里只关心「进后台又各停了一次」
        val primaryStopsBefore = primary.stopCount
        val fallbackStopsBefore = fallback.stopCount

        controller.setHostForeground(false)

        assertEquals(primaryStopsBefore + 1, primary.stopCount)
        assertEquals(fallbackStopsBefore + 1, fallback.stopCount)
        controller.release()
    }

    @Test
    fun `后台期间起播被拒 不会重新拉起声音`() {
        val primary = RecordingPlayer()
        val controller = newController(primary)

        controller.play(httpRequest)
        controller.setHostForeground(false)
        val stoppedAt = primary.stopCount

        controller.play(httpRequest)

        assertEquals(1, primary.playCount)
        assertEquals(PlaybackState.Idle, controller.state.value)
        // 后台 play 会再兜一次底，但绝不能变成「又开始播」
        assertTrue(primary.stopCount >= stoppedAt)
        controller.release()
    }

    @Test
    fun `后台期间重试换线续播全部被拒`() {
        val primary = RecordingPlayer()
        val controller = newController(primary)

        controller.play(httpRequest)
        controller.setHostForeground(false)

        controller.retry()
        controller.resume()
        val switched = controller.switchLine(1)

        assertFalse(switched)
        assertEquals(0, primary.retryCount)
        assertEquals(0, primary.resumeCount)
        assertEquals(0, primary.switchCount)
        controller.release()
    }

    @Test
    fun `回前台不自动响 但用户主动起播照常工作`() {
        val primary = RecordingPlayer()
        val controller = newController(primary)

        controller.play(httpRequest)
        controller.setHostForeground(false)
        controller.setHostForeground(true)

        assertEquals(1, primary.playCount)
        assertEquals(PlaybackState.Idle, controller.state.value)

        controller.play(httpRequest)
        assertEquals(2, primary.playCount)
        controller.release()
    }

    private fun newController(
        primary: RecordingPlayer,
        fallback: RecordingPlayer = RecordingPlayer(),
    ): FallbackPlayerController =
        FallbackPlayerController(
            primary = primary,
            fallbackFactory = { fallback },
        )

    /** 只记调用，不真的解码：闸门测试要的是「有没有被叫」，不是播放结果 */
    private class RecordingPlayer : PlayerController {
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = _state

        private val _tracks = MutableStateFlow<List<PlaybackTrack>>(emptyList())
        override val tracks: StateFlow<List<PlaybackTrack>> = _tracks

        var playCount = 0
            private set
        var stopCount = 0
            private set
        var retryCount = 0
            private set
        var resumeCount = 0
            private set
        var switchCount = 0
            private set

        override fun selectTrack(trackId: String) = Unit

        override fun disableTracks(kind: TrackKind) = Unit

        override fun play(request: PlaybackRequest) {
            playCount++
        }

        override fun retry() {
            retryCount++
        }

        override fun switchLine(delta: Int): Boolean {
            switchCount++
            return true
        }

        override fun pause() = Unit

        override fun resume() {
            resumeCount++
        }

        override fun seekTo(positionMs: Long) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun positionMs(): Long = 0L

        override fun durationMs(): Long = 0L

        override fun stop() {
            stopCount++
            _state.value = PlaybackState.Idle
        }

        override fun release() {
            _state.value = PlaybackState.Idle
        }
    }
}
