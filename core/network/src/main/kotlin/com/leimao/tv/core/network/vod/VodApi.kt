package com.leimao.tv.core.network.vod

import java.net.URLEncoder

/**
 * 苹果CMS v10 / TVBox 系「采集接口」的 URL 构造（VOD-01）。
 *
 * 这类源的约定是**一个地址带不同查询参数**，而不是 REST 的多个路径：
 * | 能力 | 参数 | 返回里的关键字段 |
 * |---|---|---|
 * | 分类 | `ac=list` | `class[]` |
 * | 列表 | `ac=detail&t=<分类>&pg=<页>` | `list[]` + `page/pagecount/total` |
 * | 详情 | `ac=detail&ids=<id>` | `list[]`（单条，字段全） |
 * | 搜索 | `ac=detail&wd=<关键词>&pg=<页>` | `list[]` |
 *
 * 注意列表与详情**都走 `ac=detail`**：`t` / `ids` / `wd` 三选一。这是这类源的既有行为，
 * 不是笔误 —— 换成 `ac=list` 拿不到 `vod_play_url`，详情页就没有选集。
 */
object VodApi {
    fun categoriesUrl(base: String): String = buildUrl(base, listOf("ac" to "list"))

    fun listUrl(
        base: String,
        typeId: String,
        page: Int,
    ): String =
        buildUrl(
            base,
            listOf(
                "ac" to "detail",
                "t" to typeId,
                "pg" to page.toString(),
            ),
        )

    fun detailUrl(base: String, sourceKey: String): String =
        buildUrl(base, listOf("ac" to "detail", "ids" to sourceKey))

    fun searchUrl(
        base: String,
        keyword: String,
        page: Int,
    ): String =
        buildUrl(
            base,
            listOf(
                "ac" to "detail",
                "wd" to keyword,
                "pg" to page.toString(),
            ),
        )

    /**
     * 稳健拼接：源地址可能带尾斜杠、可能自带查询串（有些源的地址里就带 `?ac=...` 的模板）。
     *
     * 只做「拼」不做「猜」—— 用户少填了 `/api.php/provide/vod/` 时不去自动补全，
     * 因为补错的路径比报错更难查。缺路径的提示放在源管理页的输入说明里。
     */
    private fun buildUrl(
        base: String,
        params: List<Pair<String, String>>,
    ): String {
        val clean = base.trim().trimEnd('&', '?')
        require(clean.isNotEmpty()) { "影视源地址为空" }
        val separator = if (clean.contains('?')) '&' else '?'
        val query = params.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$clean$separator$query"
    }

    /** 关键词可能是中文，必须编码；空格按 `%20` 而不是 `+`（少数源对 `+` 处理不一致） */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
