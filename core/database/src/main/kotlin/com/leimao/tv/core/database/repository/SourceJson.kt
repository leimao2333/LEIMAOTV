package com.leimao.tv.core.database.repository

/**
 * 订阅源的 JSON 编解码（SET-03 导入导出）。
 *
 * 为什么手写而不引 JSON 库：字段固定、都是简单类型，引入 kotlinx.serialization 或 org.json
 * 都要在 build 里加依赖与 plugin，对这么小的需求是杀鸡用牛刀。手写的好处是——
 * 1. 完全可控：转义、嵌套都在这几十行里，出问题一眼看到；
 * 2. JVM 单测零依赖：不碰 `android.util` 或 `org.json`，`test` 直接跑。
 *
 * 格式是「{ "version": 1, "sources": [ {...}, ... ] }」，带 version 是给以后
 * 加字段留退路：旧文件能读，新字段可忽略。
 */
internal object SourceJson {
    private const val VERSION = 1

    /** 仅导出备份需要的字段：id / 时间戳 / 排序这些本机状态一律不带。 */
    fun encode(sources: List<Source>): String {
        val sb = StringBuilder()
        sb.append("{\"version\":").append(VERSION).append(",\"sources\":[")
        sources.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append(field("type", s.type)).append(',')
            sb.append(field("name", s.name)).append(',')
            sb.append(field("url", s.url)).append(',')
            if (s.userAgent != null) sb.append(field("userAgent", s.userAgent)).append(',')
            if (s.referer != null) sb.append(field("referer", s.referer)).append(',')
            if (s.basicUser != null) sb.append(field("basicUser", s.basicUser)).append(',')
            if (s.basicPass != null) sb.append(field("basicPass", s.basicPass)).append(',')
            sb.append("\"enabled\":").append(s.enabled)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 解析导出的 JSON。格式不对时抛 [IllegalArgumentException]，由调用方转成人话。 */
    fun decode(json: String): List<Source> {
        val text = json.trim()
        // 只认我们导出的扁平结构，逐字段用字符串定位，避免引完整 JSON 解析器
        val sourcesIdx = text.indexOf("\"sources\"")
        if (sourcesIdx < 0) throw IllegalArgumentException("不是有效的源备份文件：找不到 sources 列表")
        val listStart = text.indexOf('[', sourcesIdx)
        if (listStart < 0) throw IllegalArgumentException("不是有效的源备份文件：sources 不是数组")

        val result = mutableListOf<Source>()
        var i = listStart + 1
        while (i < text.length) {
            when (text[i]) {
                ']' -> break
                '{' -> {
                    val (obj, next) = readObject(text, i)
                    result.add(parseSource(obj))
                    i = next
                }
                else -> i += 1
            }
        }
        if (result.isEmpty()) throw IllegalArgumentException("备份文件里没有任何源")
        return result
    }

    /** 从 `{` 处读一个完整对象，返回 (对象体, 下一个可扫描位置)。 */
    private fun readObject(text: String, start: Int): Pair<String, Int> {
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') {
                    i += 1
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) return text.substring(start, i + 1) to (i + 1)
                    }
                }
            }
            i += 1
        }
        throw IllegalArgumentException("备份文件里的源对象没有正确闭合")
    }

    private fun parseSource(obj: String): Source {
        var type = SourceType.LIVE
        var name = ""
        var url = ""
        var enabled = true
        var userAgent: String? = null
        var referer: String? = null
        var basicUser: String? = null
        var basicPass: String? = null

        val typeV = valueOf(obj, "type")
        if (typeV != null) type = typeV
        val nameV = valueOf(obj, "name")
        if (nameV != null) name = nameV
        val urlV = valueOf(obj, "url")
        if (urlV != null) url = urlV
        val enabledV = valueOf(obj, "enabled")
        if (enabledV != null) enabled = enabledV == "true"
        userAgent = valueOf(obj, "userAgent")
        referer = valueOf(obj, "referer")
        basicUser = valueOf(obj, "basicUser")
        basicPass = valueOf(obj, "basicPass")

        if (url.isBlank()) throw IllegalArgumentException("备份里的某个源缺少 url")
        return Source(
            id = 0,
            type = type,
            name = name.ifBlank { "订阅源" },
            url = url,
            enabled = enabled,
            userAgent = userAgent,
            referer = referer,
            basicUser = basicUser,
            basicPass = basicPass,
        )
    }

    /** 从对象体里按 key 找字符串值（已反转义）。找不到返回 null。 */
    private fun valueOf(obj: String, key: String): String? {
        val idx = obj.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = obj.indexOf(':', idx)
        if (colon < 0) return null
        var i = colon + 1
        while (i < obj.length && obj[i] == ' ') i += 1
        if (i >= obj.length) return null
        return if (obj[i] == '"') {
            // 字符串值：读到配对的引号，处理转义
            val sb = StringBuilder()
            i += 1
            while (i < obj.length && obj[i] != '"') {
                if (obj[i] == '\\' && i + 1 < obj.length) {
                    val esc = obj[i + 1]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> sb.append(esc)
                    }
                    i += 2
                } else {
                    sb.append(obj[i])
                    i += 1
                }
            }
            sb.toString()
        } else {
            // 布尔/数字值：读到逗号或右括号
            val end = obj.indexOfFirstFrom(i) { it == ',' || it == '}' }
            obj.substring(i, if (end < 0) obj.length else end).trim()
        }
    }

    private fun field(key: String, value: String): String =
        "\"$key\":\"${escape(value)}\""

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        var i = start
        while (i < length) {
            if (predicate(this[i])) return i
            i += 1
        }
        return -1
    }
}
