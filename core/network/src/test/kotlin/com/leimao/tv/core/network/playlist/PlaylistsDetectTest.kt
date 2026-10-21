package com.leimao.tv.core.network.playlist

import com.leimao.tv.core.model.playlist.PlaylistFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 格式嗅探（LIVE-01/02 的前置判断）。
 *
 * 用户只有一个地址、不知道里面是什么格式，所以「选错解析器」这件事只能由代码兜住。
 */
class PlaylistsDetectTest {
    @Test
    fun `标准 M3U 被识别`() {
        assertEquals(PlaylistFormat.M3U, Playlists.detect("#EXTM3U\n#EXTINF:-1,A\nhttps://example.com/a"))
    }

    @Test
    fun `缺少 EXTM3U 只有 EXTINF 也认作 M3U`() {
        assertEquals(PlaylistFormat.M3U, Playlists.detect("#EXTINF:-1,A\nhttps://example.com/a"))
    }

    @Test
    fun `TXT 被识别`() {
        assertEquals(PlaylistFormat.TXT, Playlists.detect("央视,#genre#\nCCTV-1,https://example.com/a"))
    }

    @Test
    fun `乱码与空白识别为未知`() {
        assertNull(Playlists.detect("这不是播放列表"))
        assertNull(Playlists.detect("   "))
        assertNull(Playlists.detect(""))
    }

    @Test
    fun `未知格式返回空结果而不是抛异常`() {
        val result = Playlists.parse("<html>404</html>")

        assertEquals(0, result.channels.size)
    }
}
