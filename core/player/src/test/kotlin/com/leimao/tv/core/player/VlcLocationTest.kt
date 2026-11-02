package com.leimao.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 兜底内核的地址处理（2026-11-02 修）。
 *
 * 这组用例钉的是一类**整条降级链失效**的错误：libVLC 的 `Media(LibVLC, String)` 走的是
 * 「本地文件路径」语义，把 `http://…` 当相对路径处理，最终报
 * `VLC is unable to open the MRL 'file:////http%3A//…'`。
 * 结果是 Media3 失败之后换到 VLC 也必然失败 —— 用户看到「所有线路都试过了」，
 * 而其实一根线都没真正试过。
 */
class VlcLocationTest {
    @Test
    fun `http 地址原样交给 libVLC`() {
        assertEquals("http://10.0.2.2:8000/stream/1.m3u8", vlcLocation("http://10.0.2.2:8000/stream/1.m3u8"))
    }

    @Test
    fun `rtsp 与 https 都不动`() {
        assertEquals("rtsp://example.com/a", vlcLocation("rtsp://example.com/a"))
        assertEquals("https://example.com/a.m3u8", vlcLocation("https://example.com/a.m3u8"))
    }

    @Test
    fun `udp 组播带 at 也不动`() {
        assertEquals("udp://@239.0.0.1:1234", vlcLocation("udp://@239.0.0.1:1234"))
    }

    @Test
    fun `file 冒号已经写了的不重复补`() {
        assertEquals("file:/sdcard/movie.mp4", vlcLocation("file:/sdcard/movie.mp4"))
        assertEquals("file:///sdcard/movie.mp4", vlcLocation("file:///sdcard/movie.mp4"))
    }

    @Test
    fun `裸路径按本地文件补 scheme`() {
        assertEquals("file:///sdcard/movie.mp4", vlcLocation("/sdcard/movie.mp4"))
        assertEquals("file://downloads/a.mp4", vlcLocation("downloads/a.mp4"))
    }

    @Test
    fun `前后空格会被去掉`() {
        assertEquals("http://a/b", vlcLocation("  http://a/b  "))
    }

    @Test
    fun `空地址返回空 不凭空造一个 file 地址`() {
        assertEquals("", vlcLocation(""))
        assertEquals("", vlcLocation("   "))
    }

    @Test
    fun `带端口与查询串的地址不会被误判成本地路径`() {
        assertEquals("http://host:8080/a.m3u8?t=1&s=2", vlcLocation("http://host:8080/a.m3u8?t=1&s=2"))
    }
}
