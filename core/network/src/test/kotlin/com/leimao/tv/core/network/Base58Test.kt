package com.leimao.tv.core.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Bitcoin Base58 解码（MoonTV / LunaTV 订阅格式）。
 *
 * 断言用的期望值是**固定字节串**，不是「再编一次再解」—— 那会把编码和解码的
 * 同一个 bug 相互抵消，等于没测。这里用两段已知向量钉死：
 * - `"hello world"` 的 Bitcoin Base58 编码是 `StV1DL6CwTryKyV`；
 * - 空串 / 前导 1 的边界行为也逐条覆盖。
 */
class Base58Test {
    @Test
    fun `已知向量 hello world`() {
        assertArrayEquals(
            "hello world".encodeToByteArray(),
            Base58.decode("StV1DL6CwTryKyV"),
        )
    }

    @Test
    fun `空串解出空字节`() {
        assertArrayEquals(ByteArray(0), Base58.decode(""))
    }

    @Test
    fun `前导 1 还原前导零字节`() {
        // '1' 代表一个零字节；"1" 应解出单个 0x00
        assertArrayEquals(byteArrayOf(0), Base58.decode("1"))
        // "11" 应解出两个 0x00
        assertArrayEquals(byteArrayOf(0, 0), Base58.decode("11"))
    }

    @Test
    fun `非法字符抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { Base58.decode("0") }
        assertThrows(IllegalArgumentException::class.java) { Base58.decode("O") }
        assertThrows(IllegalArgumentException::class.java) { Base58.decode("l") }
        assertThrows(IllegalArgumentException::class.java) { Base58.decode(" ") }
        assertThrows(IllegalArgumentException::class.java) { Base58.decode("StV1 DL6") }
    }

    @Test
    fun `单字符边界`() {
        // 字母表 '123...'：'1'=0、'2'=1、...、'z'=57
        assertArrayEquals(byteArrayOf(1), Base58.decode("2"))
        // 'z' 是字母表最后一个，值 = 57
        assertArrayEquals(byteArrayOf(57), Base58.decode("z"))
    }
}
