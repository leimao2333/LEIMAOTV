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

    // ---- 点播缓冲（2026-11-04：影视「一直缓冲」）----

    @Test
    fun `点播缓冲比任何一个直播档位都厚`() {
        // 用户要的是「缓存多一点、一直缓存」：点播的下限与上限都必须压过最厚的直播档，
        // 否则改了等于没改（直播档最大上限 60 秒，点播要 3 分钟）
        val vod = BufferProfile.VOD_DURATIONS
        BufferProfile.entries.forEach { live ->
            assert(vod.minBufferMs > live.minBufferMs) {
                "点播最小缓冲 ${vod.minBufferMs} 不该低于 ${live.name} 的 ${live.minBufferMs}"
            }
        }
        assert(vod.maxBufferMs > BufferProfile.ANTI_JITTER.maxBufferMs)
    }

    @Test
    fun `点播缓冲四段必须自洽_否则内核构造期就会抛异常`() {
        // Media3 的 DefaultLoadControl 有硬校验：起播门槛不得大于最小缓冲，
        // 卡后门槛不得大于最小缓冲。写反了是**构造期崩溃**，不是「效果差一点」
        val vod = BufferProfile.VOD_DURATIONS
        assert(vod.bufferForPlaybackMs <= vod.minBufferMs)
        assert(vod.bufferAfterRebufferMs <= vod.minBufferMs)
        assert(vod.minBufferMs <= vod.maxBufferMs)
    }

    @Test
    fun `点播缓冲能一直往前读_上限至少三分钟`() {
        // 「要一直缓存」的落点就在这个上限：内核攒够它就停手，
        // 30 秒的老值在 10Mbps 的源上只够 30 秒，一次抽风就抽干了
        assertEquals(180_000, BufferProfile.VOD_DURATIONS.maxBufferMs)
        assertEquals(30_000, BufferProfile.VOD_DURATIONS.minBufferMs)
    }

    @Test
    fun `点播与直播的缓冲参数不是同一套`() {
        // 直播被 HLS 窗口卡在 30 秒，点播要 3 分钟 —— 两者共用一个 LoadControl 是不可能的，
        // 所以 Media3PlayerController 才会按播放类型重建内核。这条测试守住那个前提
        assertNotEquals(
            BufferProfile.BALANCED.durations().maxBufferMs,
            BufferProfile.VOD_DURATIONS.maxBufferMs,
        )
    }

    @Test
    fun `点播卡后恢复门槛要低_不再等补到五秒`() {
        // 2026-11-05 用户截图：底部写着「已缓冲 4.6 秒」，中间还在转圈 —— 因为恢复门槛
        // 是 5 秒，手上明明还能放 4.6 秒却已经停住了。慢源上每次轻微掉速都要等一次
        // 半分钟量级的补货，表现就是「一直缓冲」。
        // 门槛回到 2 秒：既能躲开「卡—放半秒—再卡」，也不至于把一次小抖动拖成长停顿
        val vod = BufferProfile.VOD_DURATIONS
        assert(vod.bufferAfterRebufferMs <= 2_500) {
            "卡后恢复门槛 ${vod.bufferAfterRebufferMs}ms 太高：缓冲掉到它以下就得停住" +
                "等补到这么多才继续，慢源上每次小抖动都会变成一次明显停顿"
        }
    }
}
