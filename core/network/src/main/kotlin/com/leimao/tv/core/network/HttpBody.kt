package com.leimao.tv.core.network

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/**
 * 响应体的文本解码（LIVE-28）。
 *
 * 为什么要在这一层解压：节目单/播放列表的 `.xml.gz` 是把**整份文件**压成 gzip 再原样当正文发，
 * 响应头里只有 `Content-Type: application/gzip`，**没有** `Content-Encoding: gzip` ——
 * 后者才是 OkHttp 会自动解压的开关。少了这一步，`body.string()` 拿到的是二进制乱码，
 * XML 解析器报「节目单损坏」，用户完全看不出真正的原因是「少解了一次压」。
 *
 * 判据用**魔数**而不是扩展名/Content-Type：地址可能被 CDN 改写、后缀可能写成 `.xml`，
 * 但 `1f 8b` 这两个字节是 gzip 的定义，骗不了人。也只认这一个：
 * 认宽了会把恰好以这两个字节开头的正常文本当成压缩包。
 */
internal object HttpBody {
    private const val GZIP_MAGIC_FIRST: Byte = 0x1f
    private const val GZIP_MAGIC_SECOND: Byte = 0x8b.toByte()

    fun looksGzipped(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == GZIP_MAGIC_FIRST && bytes[1] == GZIP_MAGIC_SECOND

    /**
     * 字节 → 文本。压缩包先解开再按 [charset] 解码。
     *
     * 解压失败（半截文件、伪装成 gzip 的脏数据）时**退回原始字节**而不是抛异常：
     * 这一层的调用方（播放列表解析、节目单解析）本来就要处理「内容不是我要的格式」，
     * 让它们按乱码报「不是播放列表」比在这里抛一个网络异常更接近事实 ——
     * 网络明明是通的。
     */
    fun decode(
        bytes: ByteArray,
        charset: Charset = Charsets.UTF_8,
    ): String {
        if (bytes.isEmpty()) return ""
        val plain = if (looksGzipped(bytes)) inflate(bytes) ?: bytes else bytes
        return plain.toString(charset)
    }

    /**
     * 流式版本：按魔数决定要不要套一层 [GZIPInputStream]，**全程不解压到内存**。
     *
     * 给「文件本身很大、但解析器本来就逐条吃」的场景用（节目单 `.xml.gz`）。
     * 判据与 [looksGzipped] 完全一致，只是从「读字节数组的前两个字节」换成
     * 「mark / read / reset」——`BufferedInputStream` 支持 mark，所以不会吃掉正文。
     */
    fun gzipIfNeeded(input: InputStream): InputStream {
        val buffered =
            if (input is BufferedInputStream) input else BufferedInputStream(input, STREAM_BUFFER_BYTES)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == GZIP_FIRST_INT && second == GZIP_SECOND_INT) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }
    }

    /**
     * 流式判定的魔数用 **Int** 而不是复用上面那两个 Byte。
     *
     * `InputStream.read()` 返回 0..255，而 `0x8b.toByte()` 是 -117：直接比较永远不相等，
     * 结果就是「gzip 文件被当成明文 XML 喂给解析器」—— 报错是「节目单损坏」，
     * 而真实原因只是这里少了一次 `and 0xFF`。索性别做类型转换，两套常量各写各的。
     */
    private const val GZIP_FIRST_INT = 0x1f
    private const val GZIP_SECOND_INT = 0x8b

    /** 流式解析的读缓冲：8KB 够 amortize 系统调用，又不会为「边下边解」多占内存 */
    private const val STREAM_BUFFER_BYTES = 8 * 1024

    private fun inflate(bytes: ByteArray): ByteArray? =
        try {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } catch (_: IOException) {
            null
        }
}
