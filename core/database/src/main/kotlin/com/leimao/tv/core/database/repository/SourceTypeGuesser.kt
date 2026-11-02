package com.leimao.tv.core.database.repository

/**
 * 订阅源类型的自动识别（SET-14「混合导入」）。
 *
 * ## 为什么需要它
 *
 * 一个订阅备份里常常既有直播源（M3U / TXT 播放列表）又有影视源（苹果CMS 采集接口），
 * 甚至同一个地址列表里两种混着。要用户在遥控器上一条条选「这条是直播、那条是影视」，
 * 是最典型的把机器的活交给人的设计 —— 而且选错了不会报错，只会得到一条「抓取失败」，
 * 用户根本不知道是地址坏了还是类型选错了（2026-11-02 反馈）。
 *
 * ## 判定顺序
 *
 * 正文特征 → 地址**硬**特征 → 宿主给的声明 → 地址**软**特征 → 兜底直播。
 *
 * 正文比地址可靠（`api.php` 也可能出现在别处），所以调用方**知道正文时一定要传**；
 * 不知道正文（只粘了一个 URL）才退回地址特征。
 *
 * 地址特征分两档，是因为它们的信息量差得远（`SourceJsonTest` 里那份聚合源清单暴露的）：
 * `provide/vod`、`ac=list` 只可能是采集接口，`.m3u` 只可能是播放列表 —— 这些是**硬**的，
 * 与声明冲突时以地址为准；而 `.txt` 既可能是一份频道列表，也可能是影视源的配置文件
 * （`format=moontv` 的源就常常挂在 `.txt` 上），只能算**软**的，冲突时听声明的。
 *
 * 认不出时兜底 [SourceType.LIVE]：这是历史默认值，且直播源的失败是可自证的
 * （刷新后 0 个频道，用户立刻看得见），不像影视源失败那样静默。
 */
object SourceTypeGuesser {
    /**
     * 按地址特征猜。猜不出返回 null（交给正文或用户）。
     *
     * 只看 path 与 query，不看域名后缀：影视采集站没有固定域名规律，
     * 而路径里的 `provide/vod`、`ac=list` 是 CMS 的固定约定。
     */
    fun guessFromUrl(url: String): String? = strongFromUrl(url) ?: weakFromUrl(url)

    /**
     * 地址里的**硬**特征：命中就足以推翻声明。
     *
     * 判据只收「不可能是另一类」的写法 —— 影视采集站的接口路径是苹果CMS 的固定约定，
     * `.m3u` / `.m3u8` 则是播放列表的扩展名，两边不会互相冒充。
     */
    fun strongFromUrl(url: String): String? {
        val lower = url.trim().lowercase()
        if (lower.isEmpty()) return null

        val path = lower.substringBefore('?')
        // 播放列表扩展名：`.m3u8` 一定是播放列表，影视接口不会给这个后缀
        if (path.endsWith(".m3u") || path.endsWith(".m3u8")) return SourceType.LIVE

        // ---- 影视：苹果CMS / 常见采集接口的固定约定 ----
        // `ac=list` / `ac=videolist` 是苹果CMS 的接口动作，几乎不会出现在直播地址里
        if (lower.contains("ac=list") || lower.contains("ac=videolist")) return SourceType.VOD
        if (lower.contains("provide/vod") || lower.contains("/provide/vod/")) return SourceType.VOD
        if (lower.contains("api.php/provide")) return SourceType.VOD
        // `api.php?` 且带 `at=` / `wd=` 这类采集参数，基本就是 CMS 接口
        if (lower.contains("api.php") && (lower.contains("at=") || lower.contains("wd="))) {
            return SourceType.VOD
        }
        return null
    }

    /**
     * 地址里的**软**特征：只能在没有声明时用来猜。
     *
     * `.txt` 是这里最关键的一条：既是直播清单最常见的形态，也是不少影视源配置文件的形态。
     * 拿它去推翻声明，会把 `format=moontv` 的影视源判成直播，然后永远刷不出频道。
     */
    fun weakFromUrl(url: String): String? {
        val lower = url.trim().lowercase()
        if (lower.isEmpty()) return null

        val path = lower.substringBefore('?')
        if (path.endsWith(".txt") || path.endsWith(".list")) return SourceType.LIVE
        if (lower.contains("type=m3u") || lower.contains("format=m3u")) return SourceType.LIVE
        if (path.contains("iptv") || path.contains("playlist")) return SourceType.LIVE
        if (path.contains("/live/") && !path.contains("api")) return SourceType.LIVE
        return null
    }

    /**
     * 按正文内容猜（只读开头一两千字符就够，调用方不必把整个文件读完再判断）。
     *
     * M3U 的 `#EXTM3U` 是文件头魔数，最可靠；TXT 频道列表的特征是
     * 「台名,http 地址」成行出现；影视接口返回的 JSON 一定带 `class` 与 `list`。
     */
    fun guessFromContent(text: String): String? {
        val head = text.trimStart()
        if (head.isEmpty()) return null

        if (head.startsWith("#EXTM3U", ignoreCase = true)) return SourceType.LIVE
        if (head.startsWith("#EXTINF", ignoreCase = true)) return SourceType.LIVE

        // 苹果CMS 的列表响应：{"code":1,"msg":"数据列表","page":1,"pagecount":..,"limit":"20","total":..,"list":[...]}
        // 只认「同时出现 class 与 list」：单看 list 会把很多无关 JSON 认成影视
        val jsonHead = head.take(CONTENT_PROBE_CHARS)
        val looksJson = jsonHead.startsWith("{") || jsonHead.startsWith("[")
        if (looksJson && jsonHead.contains("\"list\"") && jsonHead.contains("\"class\"")) {
            return SourceType.VOD
        }

        // TXT 频道列表：前几行里出现「非空台名 + 逗号 + http(s) 地址」
        val lines = head.lineSequence().take(CONTENT_PROBE_LINES)
        val commaLines =
            lines.count { line ->
                val trimmed = line.trim()
                val comma = trimmed.indexOf(',')
                if (comma <= 0) return@count false
                val tail = trimmed.substring(comma + 1).trim()
                tail.startsWith("http://", ignoreCase = true) ||
                    tail.startsWith("https://", ignoreCase = true) ||
                    tail.startsWith("rtmp://", ignoreCase = true) ||
                    tail.startsWith("rtsp://", ignoreCase = true)
            }
        if (commaLines >= 2) return SourceType.LIVE

        return null
    }

    /**
     * 综合判定：正文 → 地址硬特征 → 宿主声明 → 地址软特征 → 兜底直播。
     *
     * 为什么声明排在**硬**地址特征**之后**：备份文件里的 `type` 经常是默认值（导出时缺字段、
     * 或者从别处抄来的 JSON），一个写着 `LIVE` 但地址是 `api.php/provide/vod` 的条目
     * 照着声明导入，只会得到一条永远刷新不出频道的死源。
     *
     * 又为什么排在**软**特征之前：声明承载了地址看不出来的信息 —— 一个 `.txt` 地址
     * 可能是直播清单，也可能是 `format=moontv` 的影视配置（聚合源清单里就有这种）。
     * 光看后缀就推翻声明，等于把用户明确填对的类型改错。
     */
    fun resolve(
        declared: String?,
        url: String,
        content: String? = null,
    ): String {
        val stated = normalize(declared)
        content?.let { text -> guessFromContent(text)?.let { return it } }
        // 硬特征：与声明冲突时以地址为准（`.m3u` 不会是影视接口，`provide/vod` 不会是播放列表）
        strongFromUrl(url)?.let { return it }
        // 没有硬特征时尊重声明，最后才用软特征兜
        if (stated != null) return stated
        weakFromUrl(url)?.let { return it }
        return SourceType.LIVE
    }

    /** 把外部写法归一：`vod` / `VOD` / `影视` / `live` / `直播` 都认；认不出返回 null */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty()) return null
        return when {
            value == "vod" || value == "影视" || value == "movie" || value == "video" -> SourceType.VOD
            value == "live" || value == "直播" || value == "iptv" || value == "tv" -> SourceType.LIVE
            else -> null
        }
    }

    /** 正文探测只看开头这么多字符：够判 M3U 头与 JSON 的 class/list，不必读完整个文件 */
    private const val CONTENT_PROBE_CHARS = 4096

    /** TXT 频道列表判定的行数：两行就够了，看多了只是白扫 */
    private const val CONTENT_PROBE_LINES = 20
}
