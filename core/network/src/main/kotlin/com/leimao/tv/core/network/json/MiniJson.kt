package com.leimao.tv.core.network.json

/** 真实源不会超过十层；限制它纯粹是防畸形/恶意响应把栈打穿 */
private const val MAX_JSON_DEPTH = 64

/**
 * 一个够用就好的 JSON 值模型。
 *
 * 为什么又手写一个 JSON 解析器（`SourceJson` 已经手写过一轮）：
 * 两者难度不同 —— 源备份是我们自己导出的**扁平固定结构**，按 key 字符串找值就够了；
 * 影视源的响应是**外部任意嵌套**的（`{class:[...], list:[{...}]}`，字段名由采集站决定），
 * 按 key 扫描会在「嵌套对象里有同名 key」「数组里混着 null」这些情况下静默出错，必须真解析。
 *
 * 为什么不用 kotlinx.serialization / org.json（RULES §4 依赖纪律）：
 * 1. 引 kotlinx.serialization 要加 plugin + 依赖，为一个解析器改构建不划算；
 * 2. org.json 是 Android 内置但 JVM 单测里是 stub（调用即抛异常），要额外加 test 依赖才能测；
 * 3. 手写这 200 行完全可控，且 `core:network` 的解析器本来就是「输入字符串、输出模型」的纯函数。
 *
 * 数字**不转成 Double** 而是保留字面量：`vod_id` 这类字段在源里有时是数字、有时是字符串，
 * 转 Double 会在大 id 上丢精度（超过 2^53），保留原文才能安全地拿 [asLongOrNull]。
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    /** 保留源里的原始字面量（`1`、`1.5`、`-2e3` 都原样） */
    data class Num(val raw: String) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** 最小可用的标准 JSON 解析器：无依赖、带错误位置、限制嵌套深度。 */
object MiniJson {
    /**
     * 解析完整文档。
     *
     * 严格按标准 JSON（不容忍尾逗号、注释、单引号）—— 采集站是 `json_encode` 出来的，
     * 真的不规范时**明确报错**比悄悄猜要可靠：源坏了一眼能看出来，而不是解析出一堆空条目。
     *
     * @throws IllegalArgumentException 内容不是合法 JSON 时，消息里带出错位置
     */
    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipBom()
        val value = reader.readValue(depth = 0)
        reader.skipWhitespace()
        if (!reader.atEnd()) throw reader.error("JSON 结束后还有多余内容")
        return value
    }
}

// ---------------------------------------------------------------- 取值便利函数
//
// 源里的字段「缺失 / 为 null / 类型不对」三种情况都归结为 null，
// 让调用处只写 `obj.text("vod_name") ?: ""`，不必层层判空 —— 采集站的字段残缺率相当高。

fun JsonValue.asObjectOrNull(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.fields

fun JsonValue.asArrayOrNull(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

/** 文本：字符串原样；数字取字面量；布尔转 `true`/`false`；null 与对象/数组给 null。 */
fun JsonValue.asTextOrNull(): String? =
    when (this) {
        is JsonValue.Str -> value
        is JsonValue.Num -> raw
        is JsonValue.Bool -> if (value) "true" else "false"
        else -> null
    }

/** 整数：兼容 `"123"` 与 `123` 两种写法；小数按截断处理（源的页码不会带小数）。 */
fun JsonValue.asLongOrNull(): Long? =
    when (this) {
        is JsonValue.Num -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
        is JsonValue.Str -> value.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        else -> null
    }

fun JsonValue.asIntOrNull(): Int? = asLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

fun Map<String, JsonValue>.text(key: String): String? = this[key]?.asTextOrNull()

fun Map<String, JsonValue>.long(key: String): Long? = this[key]?.asLongOrNull()

fun Map<String, JsonValue>.int(key: String): Int? = this[key]?.asIntOrNull()

fun Map<String, JsonValue>.array(key: String): List<JsonValue> = this[key]?.asArrayOrNull().orEmpty()

fun Map<String, JsonValue>.obj(key: String): Map<String, JsonValue>? = this[key]?.asObjectOrNull()

/**
 * 递归下降解析器。
 *
 * 状态只有一个 [pos]，所有函数成功后都把 [pos] 推到「下一个待读字符」，
 * 失败统一抛 [error]，消息里带位置 —— 排查真实源的响应问题时，位置是最有用的信息。
 */
private class Reader(private val src: String) {
    private var pos = 0
    private val len = src.length

    fun atEnd(): Boolean = pos >= len

    fun error(message: String): IllegalArgumentException = IllegalArgumentException("JSON 解析失败（位置 $pos）：$message")

    /** 有些源会带 UTF-8 BOM，直接跳过，别让它变成「无法识别的字符」 */
    fun skipBom() {
        if (pos < len && src[pos] == '\uFEFF') pos += 1
    }

    fun skipWhitespace() {
        while (pos < len && src[pos].isJsonWhitespace()) pos += 1
    }

    fun readValue(depth: Int): JsonValue {
        if (depth > MAX_JSON_DEPTH) throw error("嵌套超过 $MAX_JSON_DEPTH 层")
        skipWhitespace()
        if (pos >= len) throw error("内容意外结束")
        return when (val c = src[pos]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonValue.Str(readString())
            't' -> readLiteral("true", JsonValue.Bool(true))
            'f' -> readLiteral("false", JsonValue.Bool(false))
            'n' -> readLiteral("null", JsonValue.Null)
            else -> if (c == '-' || c.isAsciiDigit()) readNumber() else throw error("无法识别的字符 '$c'")
        }
    }

    private fun readObject(depth: Int): JsonValue.Obj {
        pos += 1 // 跳过 '{'
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (pos < len && src[pos] == '}') {
            pos += 1
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            if (pos >= len || src[pos] != '"') throw error("对象的 key 必须是字符串")
            val key = readString()
            skipWhitespace()
            expect(':')
            // 同名 key 后者覆盖前者：与多数 JSON 实现一致，也符合「源里后写的更可信」的直觉
            fields[key] = readValue(depth + 1)
            skipWhitespace()
            if (pos >= len) throw error("对象没有闭合")
            when (src[pos]) {
                ',' -> pos += 1
                '}' -> {
                    pos += 1
                    return JsonValue.Obj(fields)
                }
                else -> throw error("对象里期望 ',' 或 '}'")
            }
        }
    }

    private fun readArray(depth: Int): JsonValue.Arr {
        pos += 1 // 跳过 '['
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (pos < len && src[pos] == ']') {
            pos += 1
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(readValue(depth + 1))
            skipWhitespace()
            if (pos >= len) throw error("数组没有闭合")
            when (src[pos]) {
                ',' -> pos += 1
                ']' -> {
                    pos += 1
                    return JsonValue.Arr(items)
                }
                else -> throw error("数组里期望 ',' 或 ']'")
            }
        }
    }

    private fun readString(): String {
        pos += 1 // 跳过开头的引号
        val sb = StringBuilder()
        while (true) {
            if (pos >= len) throw error("字符串没有闭合")
            when (val c = src[pos]) {
                '"' -> {
                    pos += 1
                    return sb.toString()
                }
                '\\' -> {
                    pos += 1
                    sb.append(readEscape())
                }
                else -> {
                    sb.append(c)
                    pos += 1
                }
            }
        }
    }

    private fun readEscape(): Char {
        if (pos >= len) throw error("转义符后没有字符")
        val c = src[pos]
        pos += 1
        return when (c) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> throw error("不支持的转义 \\$c")
        }
    }

    /** `\uXXXX`。代理对是两个连续的 \u 转义，这里逐个还原，拼起来的字符串自然正确。 */
    private fun readUnicodeEscape(): Char {
        if (pos + 4 > len) throw error("\\u 后面不足 4 位")
        val hex = src.substring(pos, pos + 4)
        val code = hex.toIntOrNull(16) ?: throw error("\\u$hex 不是合法十六进制")
        pos += 4
        return code.toChar()
    }

    private fun readNumber(): JsonValue.Num {
        val start = pos
        if (pos < len && src[pos] == '-') pos += 1
        while (pos < len && src[pos].isAsciiDigit()) pos += 1
        if (pos < len && src[pos] == '.') {
            pos += 1
            while (pos < len && src[pos].isAsciiDigit()) pos += 1
        }
        if (pos < len && (src[pos] == 'e' || src[pos] == 'E')) {
            pos += 1
            if (pos < len && (src[pos] == '+' || src[pos] == '-')) pos += 1
            while (pos < len && src[pos].isAsciiDigit()) pos += 1
        }
        val raw = src.substring(start, pos)
        if (raw.isEmpty() || raw == "-") throw error("数字格式不对")
        return JsonValue.Num(raw)
    }

    private fun readLiteral(literal: String, value: JsonValue): JsonValue {
        if (!src.startsWith(literal, pos)) throw error("期望 $literal")
        pos += literal.length
        return value
    }

    private fun expect(c: Char) {
        if (pos >= len || src[pos] != c) throw error("期望 '$c'")
        pos += 1
    }
}

private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
