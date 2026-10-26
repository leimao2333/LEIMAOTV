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
            if (s.format != SourceFormat.APPLECMS) sb.append(field("format", s.format)).append(',')
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

    /**
     * 挑出真正需要新增的源（SET-03）。
     *
     * 导入必须**幂等**：同一份备份点两次，不该变成两份源。以前是直接追加，于是
     * 「导入清单 → 发现少点什么 → 再导一次」会得到双份列表，用户只能逐条手工删。
     * 判据用**地址**而不是名字：名字是给人看的，用户随时会改；地址才是源的身份。
     *
     * 文件内部重复的也一并去掉 —— 上游清单里同一个地址挂两个名字是常见写法。
     */
    fun filterNew(
        parsed: List<Source>,
        existingUrls: Set<String>,
    ): Pair<List<Source>, Int> {
        val seen = existingUrls.mapTo(mutableSetOf()) { it.trim() }
        val fresh = mutableListOf<Source>()
        var skipped = 0
        for (source in parsed) {
            if (seen.add(source.url.trim())) fresh += source else skipped++
        }
        return fresh to skipped
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
        var format = SourceFormat.APPLECMS

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
        val formatV = valueOf(obj, "format")
        if (formatV != null) format = normalizeFormat(formatV)

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
            format = format,
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

    /**
     * 外部文件的 format 字段写法不统一（`moontv` / `moontv-encrypted`），
     * 归一成客户端枚举名。认不出的落回 applecms。
     */
    private fun normalizeFormat(raw: String): String =
        when (raw.trim().lowercase()) {
            "moontv", "moontv-encrypted", "moontv_encrypted" -> SourceFormat.MOONTV
            "applecms" -> SourceFormat.APPLECMS
            else -> SourceFormat.APPLECMS
        }

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
