package com.leimao.tv.core.network.vod

import com.leimao.tv.core.network.Base58
import com.leimao.tv.core.network.json.MiniJson
import com.leimao.tv.core.network.json.asObjectOrNull
import com.leimao.tv.core.network.json.text

/**
 * MoonTV / LunaTV 加密订阅配置的解析。
 *
 * 这类源是一个**单行 Base58 字符串**，解码后是一段 JSON：
 *
 * ```json
 * { "cache_time": 7200, "api_site": {
 *     "iqiyizyapi.com": { "name": "🎬-爱奇艺-", "api": "https://.../api.php/provide/vod", "detail": "..." },
 *     "dbzy.tv": { "name": "🎬豆瓣资源", "api": "https://.../api.php/provide/vod", "detail": "..." }
 * } }
 * ```
 *
 * 关键点：`api_site` 里的每个站点**本身就是标准的苹果CMS V10 接口**（`api` 字段就是
 * `provide/vod` 地址），MoonTV 客户端解完码后仍然是逐个站点去拉。所以本项目的做法是
 * 把这份「多站点清单」解出来，交给 [MoontvVodAdapter] 聚合复用现有的苹果CMS 链路。
 *
 * 与 [AppleCmsVodParser] 一样，这层只做「字符串 → 模型」，不发请求，全部可 JVM 单测。
 */
data class MoontvSite(
    /** 清单里的 key（如 `iqiyizyapi.com`）。用于跨站点拼唯一键，见 [MoontvVodAdapter] */
    val key: String,
    val name: String,
    /** 标准苹果CMS `provide/vod` 接口地址 */
    val api: String,
)

/**
 * 解析 Base58 编码的 MoonTV 订阅串。
 *
 * 容错取向：**坏字符 / 坏 JSON 直接抛 [IllegalArgumentException]**（下载过程出了问题，
 * 静默跳过只会让用户以为「这源没片」）；单条站点缺 `api` 则丢弃这一条、其余照收
 * （清单里偶尔有失效站，少一个不该拖垮整份清单）。
 *
 * @throws IllegalArgumentException 内容不是合法 Base58、或解码后不是预期 JSON 结构时
 */
object MoontvConfig {
    fun parse(encoded: String): List<MoontvSite> {
        val raw = Base58.decode(encoded.trim())
        val json = raw.toString(Charsets.UTF_8)
        val root =
            MiniJson.parse(json).asObjectOrNull()
                ?: throw IllegalArgumentException("MoonTV 配置不是 JSON 对象")

        val sites =
            root["api_site"]?.asObjectOrNull()
                ?: throw IllegalArgumentException("MoonTV 配置缺少 api_site 清单")

        val result = mutableListOf<MoontvSite>()
        for ((key, value) in sites) {
            val obj = value.asObjectOrNull() ?: continue
            val api = obj.text("api")?.trim().orEmpty()
            if (api.isEmpty()) continue
            val name = obj.text("name")?.trim().orEmpty().ifBlank { key }
            result.add(MoontvSite(key = key, name = name, api = api))
        }
        if (result.isEmpty()) throw IllegalArgumentException("MoonTV 配置里没有任何可用站点")
        return result
    }
}
