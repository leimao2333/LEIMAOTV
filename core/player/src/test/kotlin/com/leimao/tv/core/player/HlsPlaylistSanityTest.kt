package com.leimao.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这份 playlist 会不会把 libVLC 弄崩」的判据。
 *
 * 崩溃现场（2026-11-06，聚合源实测）：
 * `SegmentList.cpp:133: assertion "duration" failed` → SIGABRT → 进程没了。
 * 所以这里的用例是**防闪退闸门**，不是「画质好不好」的评估。
 */
class HlsPlaylistSanityTest {
    @Test
    fun `正常直播清单放行`() {
        val text =
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.000,
            seg1.ts
            #EXTINF:4.000,
            seg2.ts
            """.trimIndent()
        assertFalse(isPlaylistRejectedByVlc(text))
    }

    @Test
    fun `EXTINF 后面没有时长要拦住`() {
        val text =
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:,
            seg1.ts
            """.trimIndent()
        assertTrue(isPlaylistRejectedByVlc(text))
    }

    @Test
    fun `时长为零要拦住`() {
        val text =
            """
            #EXTM3U
            #EXTINF:0,
            seg1.ts
            """.trimIndent()
        assertTrue(isPlaylistRejectedByVlc(text))
    }

    @Test
    fun `时长不是数字要拦住`() {
        val text =
            """
            #EXTM3U
            #EXTINF:abc,
            seg1.ts
            """.trimIndent()
        assertTrue(isPlaylistRejectedByVlc(text))
    }

    @Test
    fun `主列表没有 EXTINF 是合法的`() {
        val text =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080
            high/index.m3u8
            """.trimIndent()
        assertFalse(isPlaylistRejectedByVlc(text))
    }

    @Test
    fun `缺 TARGETDURATION 不单独判死`() {
        // 国产源里很常见；它不是 abort 的触发点，判死会误伤一批能播的线路
        val text =
            """
            #EXTM3U
            #EXTINF:3.2,
            a.ts
            """.trimIndent()
        assertFalse(isPlaylistRejectedByVlc(text))
    }

    @Test
    fun `根本不是 m3u8 的正文不判死`() {
        assertFalse(isPlaylistRejectedByVlc("<html>404 not found</html>"))
        assertFalse(isPlaylistRejectedByVlc(""))
    }

    @Test
    fun `只有一行里的一条是坏的就够拦`() {
        val text =
            """
            #EXTM3U
            #EXTINF:4.000,
            ok1.ts
            #EXTINF:,
            bad.ts
            #EXTINF:4.000,
            ok2.ts
            """.trimIndent()
        assertTrue(isPlaylistRejectedByVlc(text))
    }
}
