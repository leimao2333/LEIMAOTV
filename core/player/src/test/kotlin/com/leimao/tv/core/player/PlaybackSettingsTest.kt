package com.leimao.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 播放设置档位映射（SET-01）的纯逻辑测试。
 *
 * 只测「档位 ↔ 参数」的翻译层与反解析，不碰 Media3 的 [androidx.media3.exoplayer.DefaultLoadControl]
 * 内部 —— 那部分属于库的职责，且它在 JVM 上实例化受 `Assertions.checkState` 约束（必须在 Looper 线程），
 * 不该在单测里硬啃。
 */
class PlaybackSettingsTest {
    @Test
    fun `默认档是均衡`() {
        assertEquals(BufferProfile.BALANCED, PlaybackSettings.DEFAULT.bufferProfile)
    }

    @Test
    fun `默认快进步长是 30 秒`() {
        assertEquals(SeekStep.SECONDS_30.millis, PlaybackSettings.DEFAULT.seekIncrementMs)
    }

    @Test
    fun `缓冲档位反解析_不认识的回落均衡`() {
        assertEquals(BufferProfile.BALANCED, BufferProfile.fromName(null))
        assertEquals(BufferProfile.BALANCED, BufferProfile.fromName(""))
        assertEquals(BufferProfile.BALANCED, BufferProfile.fromName("NOPE"))
    }

    @Test
    fun `缓冲档位反解析_认识的名称原样取回`() {
        BufferProfile.entries.forEach { profile ->
            assertEquals(profile, BufferProfile.fromName(profile.name))
        }
    }

    @Test
    fun `三档缓冲参数互不相同且单调_档位越抗卡顿缓冲越足`() {
        // 抗卡顿档的缓冲上限、起播门槛都应高于低延迟档（这就是「档位」的意义）
        assertNotEquals(
            BufferProfile.LOW_LATENCY.maxBufferMs,
            BufferProfile.ANTI_JITTER.maxBufferMs,
        )
        assert(BufferProfile.ANTI_JITTER.maxBufferMs > BufferProfile.LOW_LATENCY.maxBufferMs)
        assert(BufferProfile.ANTI_JITTER.bufferForPlaybackMs > BufferProfile.LOW_LATENCY.bufferForPlaybackMs)
    }

    @Test
    fun `快进步长_毫秒换算正确`() {
        assertEquals(10_000L, SeekStep.SECONDS_10.millis)
        assertEquals(30_000L, SeekStep.SECONDS_30.millis)
        assertEquals(60_000L, SeekStep.SECONDS_60.millis)
    }

    @Test
    fun `快进步长_反解析不认识的回落 30 秒`() {
        assertEquals(SeekStep.SECONDS_30, SeekStep.fromMillis(0L))
        assertEquals(SeekStep.SECONDS_30, SeekStep.fromMillis(99_999L))
    }

    @Test
    fun `快进步长_反解析已知毫秒取回原档`() {
        SeekStep.entries.forEach { step ->
            assertEquals(step, SeekStep.fromMillis(step.millis))
        }
    }
}
