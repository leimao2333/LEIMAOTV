package com.leimao.tv.core.network

import java.io.IOException
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

    private fun inflate(bytes: ByteArray): ByteArray? =
        try {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } catch (_: IOException) {
            null
        }
}
