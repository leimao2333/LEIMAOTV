package com.leimao.tv.core.network.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迷你 JSON 解析器（VOD-01 的地基）。
 *
 * 重点覆盖两类：**以后会踩的真实数据**（大 id、BOM、中文转义）和
 * **解析器自己的边界**（未闭合、尾逗号、深度炸弹）。后者一旦漏了，
 * 表现是「某个源导致 App 崩溃」而不是「解析失败」，很难归因。
 */
class MiniJsonTest {
    @Test
    fun `基本类型`() {
        assertEquals(JsonValue.Str("abc"), MiniJson.parse("\"abc\""))
        assertEquals(JsonValue.Num("42"), MiniJson.parse("42"))
        assertEquals(JsonValue.Num("-1.5e3"), MiniJson.parse("-1.5e3"))
        assertEquals(JsonValue.Bool(true), MiniJson.parse("true"))
        assertEquals(JsonValue.Bool(false), MiniJson.parse("false"))
        assertEquals(JsonValue.Null, MiniJson.parse("null"))
    }

    @Test
    fun `对象与数组`() {
        val value = MiniJson.parse("""{"a":1,"b":[1,2,{"c":"x"}],"d":{"e":null}}""")
        val root = value.asObjectOrNull()
        assertEquals(1L, root?.long("a"))
        assertEquals(3, root?.array("b")?.size)
        assertEquals("x", (root?.array("b")?.get(2)?.asObjectOrNull())?.text("c"))
        assertNull(root?.obj("d")?.text("e"))
    }

    @Test
    fun `空容器`() {
        assertEquals(0, MiniJson.parse("{}").asObjectOrNull()?.size)
        assertEquals(0, MiniJson.parse("[]").asArrayOrNull()?.size)
        // 空白也算空容器
        assertEquals(0, MiniJson.parse("{  }").asObjectOrNull()?.size)
        assertEquals(0, MiniJson.parse("[ \n ]").asArrayOrNull()?.size)
    }

    @Test
    fun `转义字符`() {
        // 用 raw string + trim 表达 JSON 原文，避免 Kotlin 自身转义和 JSON 转义叠在一起看不清
        assertEquals("引号\"与反斜杠\\", MiniJson.parse(""" "引号\"与反斜杠\\" """.trim()).asTextOrNull())
        assertEquals("换行\n制表\t", MiniJson.parse(""" "换行\n制表\t" """.trim()).asTextOrNull())
        assertEquals("斜杠/", MiniJson.parse(""" "斜杠\/" """.trim()).asTextOrNull())
    }

    @Test
    fun `unicode 转义还原成中文`() {
        // 采集站常有 \u4e2d 这种写法
        assertEquals("中文", MiniJson.parse(""" "\u4e2d\u6587" """.trim()).asTextOrNull())
    }

    @Test
    fun `大整数不丢精度`() {
        // 超过 2^53 的 id：这一条是「为什么 Num 保留字面量而不是存 Double」的理由
        val id = 9007199254740993L
        assertEquals(id, MiniJson.parse("9007199254740993").asLongOrNull())
        // 字符串形式的 id 也要能取出来（有的源就是这么给的）
        assertEquals(id, MiniJson.parse("\"9007199254740993\"").asLongOrNull())
    }

    @Test
    fun `跳过 BOM 与前后空白`() {
        assertEquals(1L, MiniJson.parse("\uFEFF   {\"a\":1}\n\t ").asObjectOrNull()?.long("a"))
    }

    @Test
    fun `未闭合的字符串与容器要报错`() {
        assertTrue(parseError("\"abc").contains("字符串没有闭合"))
        assertTrue(parseError("{\"a\":1").contains("对象没有闭合"))
        assertTrue(parseError("[1,2").contains("数组没有闭合"))
    }

    @Test
    fun `不符合标准 JSON 的写法要报错而不是猜`() {
        // 尾逗号
        assertTrue(parseError("""{"a":1,}""").contains("key 必须是字符串"))
        assertTrue(parseError("[1,2,]").contains("无法识别的字符"))
        // 缺冒号
        assertTrue(parseError("""{"a" 1}""").contains("期望 ':'"))
        // 单引号
        assertTrue(parseError("'abc'").contains("无法识别的字符"))
    }

    @Test
    fun `结束后还有多余内容要报错`() {
        assertTrue(parseError("""{"a":1}{"b":2}""").contains("多余内容"))
        assertTrue(parseError("42 43").contains("多余内容"))
    }

    @Test
    fun `嵌套过深要报错`() {
        // 构造 70 层数组：真实源不会这样，但畸形响应不能把栈打穿
        val deep = "[".repeat(70) + "]".repeat(70)
        assertTrue(parseError(deep).contains("嵌套超过"))
    }

    @Test
    fun `取值便利函数对缺失与类型不符给 null`() {
        val root = MiniJson.parse("""{"s":"x","n":3,"b":true,"o":{"k":1},"arr":[1],"nil":null}""").asObjectOrNull()!!
        assertEquals("x", root.text("s"))
        assertEquals("3", root.text("n")) // 数字取字面量：源的 id 常写成数字
        assertEquals(3L, root.long("n"))
        assertEquals(3, root.int("n"))
        assertNull(root.text("o")) // 对象不是文本
        assertNull(root.text("arr")) // 数组不是文本
        assertNull(root.text("nil"))
        assertNull(root.long("s")) // 非数字字符串
        assertEquals(0, root.array("missing").size)
        assertNull(root.obj("missing"))
    }

    private fun parseError(text: String): String =
        try {
            MiniJson.parse(text)
            throw AssertionError("期望解析失败，但成功了：$text")
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
}
