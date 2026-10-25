package com.leimao.tv.core.network

/**
 * Bitcoin Base58 编解码（供 MoonTV / LunaTV 加密订阅格式用）。
 *
 * 这类订阅文件把一段 JSON 用 Base58 编码成单行字符串，客户端下载后先解码再解析。
 * 这里只实现**解码**：编码是发布方的事，客户端永远不需要把配置再编回去。
 *
 * 为什么手写而不引库（RULES §4 依赖纪律）：算法是纯位运算，几十行就能写清，
 * 引 bitcoinj / bs58 都要为这一个函数加依赖。手写的好处与 [com.leimao.tv.core.network.json.MiniJson]
 * 一致 —— 纯函数、JVM 单测零依赖、行为完全可控。
 *
 * 用 Bitcoin 字母表（不是 Ripple 的）：Bitcoin 去掉 `0 O I l` 四个易混字符，
 * 字母表是 `123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz`。
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** 字符 → 数值。不在字母表里的字符返回 -1（调用方决定报错还是跳过）。 */
    private val VALUES: IntArray =
        IntArray(128) { -1 }.also { table ->
            ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
        }

    /**
     * 把 Base58 字符串解码为原始字节。
     *
     * 前导的 `1` 在 Base58 里代表一个前导零字节（类似十六进制的 `00`），
     * 必须原样还原，否则短输入会解出错误长度。除 `1` 之外**不允许任何非法字符** ——
     * 配置串里混进空格 / 换行通常是下载过程出了问题，静默跳过只会解出一段坏 JSON。
     *
     * @throws IllegalArgumentException 输入含 Base58 字母表之外的字符时
     */
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        var leadingZeros = 0
        while (leadingZeros < input.length && input[leadingZeros] == '1') {
            leadingZeros += 1
        }

        // 十进制大整数：逐字符 × 58 + 值。字节串不长（几十 KB），Long 不够就用 List。
        var bytes = mutableListOf<Byte>()
        for (c in input) {
            val value = if (c.code < 128) VALUES[c.code] else -1
            if (value < 0) {
                throw IllegalArgumentException("Base58 解码失败：字符 '$c' 不在字母表内")
            }
            var carry = value
            var i = 0
            while (carry != 0 || i < bytes.size) {
                if (i == bytes.size) bytes.add(0)
                val cur = bytes[i].toInt() and 0xFF
                val next = cur * 58 + carry
                bytes[i] = (next and 0xFF).toByte()
                carry = next ushr 8
                i += 1
            }
        }

        // 上面是「低位在前」的 big-number 表示，反转成正常的字节序
        val result = ByteArray(leadingZeros + bytes.size)
        var out = leadingZeros
        for (i in bytes.indices.reversed()) {
            result[out++] = bytes[i]
        }
        return result
    }
}
