package com.leimao.tv.core.network.vod

import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodEpisode
import com.leimao.tv.core.model.vod.VodLine
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.model.vod.contentTypeOfSourceName
import com.leimao.tv.core.network.json.JsonValue
import com.leimao.tv.core.network.json.MiniJson
import com.leimao.tv.core.network.json.array
import com.leimao.tv.core.network.json.asObjectOrNull
import com.leimao.tv.core.network.json.int
import com.leimao.tv.core.network.json.text

/**
 * 苹果CMS v10 / TVBox 系采集接口的响应解析（VOD-01）。
 *
 * 这层只做「字符串 → 领域模型」，不发请求、不落库 —— 于是全部逻辑都能用 JVM 单测钉死，
 * 不需要连真实源（RULES §2 也不允许把真实源写进测试）。
 *
 * 容错取向是**「脏数据丢掉、结构不对报错」**：
 * - 单条 `list` 项缺 id / 缺名字 / 类型认不出 → 丢这一条，其余照收（源的脏数据很常见）；
 * - 整个响应不是 JSON、或没有 `class` / `list` 字段 → 抛 [IllegalArgumentException]，
 *   由上层转成「这个源看起来不是影视源」这类人话。绝不能悄悄返回空列表，
 *   否则用户会以为是「这个源没片」，而不是「地址填错了」。
 */
object AppleCmsVodParser {
    private const val LINE_SEPARATOR = "\$\$\$"
    private const val EPISODE_SEPARATOR = "#"
    private const val EPISODE_NAME_URL = '$'

    /** 有些源的简介里有大量 <br/>，压成一格的规则见 [cleanHtml] */
    private val HTML_TAG = Regex("<[^>]*>")
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 分类（`ac=list` 的 `class` 数组）。
     *
     * 保留映射不出类型的分类（[VodCategory.type] 为 null）：UI 可能想显示「这个源还有
     * 『福利』这类分类但本项目不展示」，比直接消失更诚实。
     */
    fun parseCategories(json: String): List<VodCategory> {
        val root = requireRoot(json)
        val classArray = root.array("class")
        if (classArray.isEmpty()) {
            throw IllegalArgumentException("这个源没有返回分类列表（可能不是苹果CMS 影视接口，或地址缺了 /api.php/provide/vod/）")
        }
        return classArray.mapNotNull { element ->
            val obj = element.asObjectOrNull() ?: return@mapNotNull null
            val typeId = obj.text("type_id")?.trim().orEmpty()
            val name = obj.text("type_name")?.trim().orEmpty()
            if (typeId.isEmpty() || name.isEmpty()) return@mapNotNull null
            VodCategory(typeId = typeId, name = name, type = contentTypeOfSourceName(name))
        }
    }

    /** 列表 / 搜索结果（两者的响应结构一致，只差请求参数） */
    fun parseList(
        json: String,
        sourceId: Long,
    ): VodPage {
        val root = requireRoot(json)
        val rawItems = root.array("list")
        val items = ArrayList<VodSummary>(rawItems.size)
        var dropped = 0
        for (element in rawItems) {
            val obj = element.asObjectOrNull()
            val summary = obj?.let { toSummary(it, sourceId) }
            if (summary == null) {
                dropped += 1
            } else {
                items.add(summary)
            }
        }
        return VodPage(
            items = items,
            page = root.int("page") ?: 1,
            pageCount = root.int("pagecount") ?: 0,
            total = root.int("total") ?: 0,
            droppedCount = dropped,
        )
    }

    /** 详情（`ac=detail&ids=<id>` 的 `list` 第一条）。返回 null 表示这条数据不可用。 */
    fun parseDetail(
        json: String,
        sourceId: Long,
    ): VodDetail? {
        val root = requireRoot(json)
        val obj = root.array("list").firstOrNull()?.asObjectOrNull() ?: return null
        val summary = toSummary(obj, sourceId) ?: return null
        return VodDetail(
            summary = summary,
            content = cleanHtml(obj.text("vod_content") ?: obj.text("vod_blurb")),
            actor = cleanPeople(obj.text("vod_actor")),
            director = cleanPeople(obj.text("vod_director")),
            score = obj.text("vod_score")?.trim()?.takeIf { it.isNotEmpty() && it != "0.0" && it != "0" },
            lang = obj.text("vod_lang")?.trim()?.takeIf { it.isNotEmpty() },
            lines = parseLines(obj.text("vod_play_from"), obj.text("vod_play_url")),
        )
    }

    /**
     * 把 `vod_play_from` 与 `vod_play_url` 合成线路列表。
     *
     * 两个字段是**按下标对齐**的两串：`from` 用 `$$$` 分隔线路名，`url` 同样用 `$$$`
     * 分隔每条线路的剧集串。所以名字只能按下标取，不能按内容匹配。
     *
     * 形状长这样（真实格式，地址此处换成占位符）：
     * ```
     * vod_play_from = "线路甲$$$线路乙"
     * vod_play_url  = "第01集$http://example.com/1.m3u8#第02集$http://example.com/2.m3u8$$$正片$http://example.com/x.mp4"
     * ```
     */
    internal fun parseLines(
        playFrom: String?,
        playUrl: String?,
    ): List<VodLine> {
        val urlBlocks = (playUrl ?: "").split(LINE_SEPARATOR)
        if (urlBlocks.all { it.isBlank() }) return emptyList()
        val names = (playFrom ?: "").split(LINE_SEPARATOR)
        return urlBlocks.mapIndexedNotNull { index, block ->
            val episodes = parseEpisodes(block)
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            VodLine(name = names.getOrNull(index)?.trim().orEmpty(), episodes = episodes)
        }
    }

    /** `第01集$url#第02集$url` → 剧集列表。只收 http(s) 地址（源里还混着自家协议跳转，电视上播不了）。 */
    internal fun parseEpisodes(block: String): List<VodEpisode> =
        block.split(EPISODE_SEPARATOR).mapNotNull { segment ->
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val cut = trimmed.indexOf(EPISODE_NAME_URL)
            if (cut < 0) {
                // 没有「集名$」前缀：电影线路常见，整段就是地址
                if (!trimmed.isHttpUrl()) return@mapNotNull null
                VodEpisode(name = "", url = trimmed)
            } else {
                val name = trimmed.substring(0, cut).trim()
                val url = trimmed.substring(cut + 1).trim()
                if (!url.isHttpUrl()) return@mapNotNull null
                VodEpisode(name = name, url = url)
            }
        }

    /**
     * 简介清洗：去标签、解常见实体、把连续空白压成一格。
     *
     * 只处理**文本类**实体（`&nbsp;` 之类），数字实体（`&#39;` 除外）不做 —— 简介里出现
     * 生僻数字实体极少，为它写一张映射表不值当，残留的 `&#12345;` 也不影响阅读。
     */
    internal fun cleanHtml(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val decoded =
            HTML_TAG
                .replace(text, " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
        return WHITESPACE_RUN.replace(decoded, " ").trim().ifEmpty { null }
    }

    /** 演职员：源里可能是 `张三,李四` / `张三、李四` / `张三 / 李四`，统一成 `张三 / 李四` */
    internal fun cleanPeople(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.split(',', '，', '、', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
            .ifEmpty { null }
    }

    private fun toSummary(
        obj: Map<String, JsonValue>,
        sourceId: Long,
    ): VodSummary? {
        val sourceKey = obj.text("vod_id")?.trim().orEmpty()
        val name = obj.text("vod_name")?.trim().orEmpty()
        if (sourceKey.isEmpty() || name.isEmpty()) return null
        // 类型认不出就丢这条：宁可少显示，也不把「未分类」塞进九类体系里
        // （VOD-02 与 SEARCH-02 白名单共用同一套 ContentType）
        val type = contentTypeOfSourceName(obj.text("type_name")) ?: return null
        return VodSummary(
            sourceId = sourceId,
            sourceKey = sourceKey,
            name = name,
            type = type,
            pic = obj.text("vod_pic")?.trim()?.takeIf { it.isHttpUrl() },
            remarks = obj.text("vod_remarks")?.trim()?.takeIf { it.isNotEmpty() },
            year = parseYear(obj.text("vod_year")),
            area = obj.text("vod_area")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** 年份：源里可能给 `2023`、`2023-01-01`、`2023年`；只接受 1900~2100 之间的四位数 */
    private fun parseYear(raw: String?): Int? {
        val head = raw?.trim()?.take(4)?.filter { it.isDigit() }.orEmpty()
        val year = head.toIntOrNull() ?: return null
        return year.takeIf { it in 1900..2100 }
    }

    /**
     * 取根对象。
     *
     * 把「根本不是 JSON」也翻译成人话：用户看到 `JSON 解析失败（位置 0）：无法识别的字符 '<'`
     * 只知道出事了，看到「这个地址返回的不是 JSON（可能是网页或错误提示）」才知道该检查什么。
     * 技术细节保留在冒号后面，排查时仍能定位到具体位置。
     */
    private fun requireRoot(json: String): Map<String, JsonValue> {
        val parsed =
            try {
                MiniJson.parse(json)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("这个地址返回的不是 JSON（可能是网页或错误提示）：${e.message}")
            }
        return parsed.asObjectOrNull()
            ?: throw IllegalArgumentException("影视源返回的不是 JSON 对象（可能拿到了网页或错误提示）")
    }

    /**
     * 只认 http(s)：既过滤空串，也过滤 `qqplay://`、`thunder://` 这类电视端播不了的自家协议。
     * 大小写不敏感 —— 少数源写成 `HTTP://`。
     */
    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}
