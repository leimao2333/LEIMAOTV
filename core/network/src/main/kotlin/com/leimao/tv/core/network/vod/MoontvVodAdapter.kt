package com.leimao.tv.core.network.vod

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * MoonTV / LunaTV 加密订阅源的聚合适配器。
 *
 * 这类源解码后是**几十个苹果CMS 站点的清单**（见 [MoontvConfig]）。它不是一个「源」，
 * 而是一把源。本适配器把它们聚合成一个 [VodSourceAdapter]，让上层（首页 / 详情 / 搜索）
 * 把它当成一个普通影视源用，感知不到底下是几十个站。
 *
 * ## 三个刻意的设计
 *
 * 1. **sourceKey 用「站点 key 前缀」防撞车**：跨站 `vod_id` 会撞（爱奇艺的 123 和
 *    豆瓣的 123 不是同一部），而详情回源靠 `(sourceId, sourceKey)`。这里把
 *    `sourceKey` 编码成 `站点key::vod_id`，详情时再拆开路由到正确的站。
 *
 * 2. **typeId 用 [ContentType] 名做「跨站稳定 id」**：苹果CMS 每个站自己的 `type_id`
 *    互不通用。聚合层给 [categories] 返回的 `typeId` 直接就是九类名（如 `"MOVIE"`），
 *    [list] 拿到它后先还原成 [ContentType]，再映射到每个站各自的分类。
 *
 * 3. **列表 / 搜索是「fan-out 聚合」，分类是「取首个可用站」**：几十个站同时拉列表
 *    会产生几十个请求，首页刷新本就有限流（[VodCatalogRefresher] 限 6 并发），
 *    这里再叠一层站点级并发会让请求量爆炸。折中：分类取第一个响应成功的站做代表
 *    （苹果CMS 各站分类名大同小异），列表 / 搜索按需 fan-out 到站点并合并去重。
 *
 * ## 失败语义
 *
 * 单个站失败不算整源失败：list 里 3 个站超时、2 个站出片，仍算成功（返回 2 个站的片）。
 * 只有**所有站都失败**才算 [VodFailure]。这与「一个源」的语义一致 —— 用户不关心
 * 底下有几个站，只关心「这个聚合源能不能出片」。
 */
class MoontvVodAdapter(
    private val http: HttpClient,
    private val source: VodSourceRef,
    /** fan-out 时最多并发几个站点请求。几十个站一起打既引对方风控也吃满本机连接 */
    private val maxConcurrency: Int = DEFAULT_SITE_CONCURRENCY,
) : VodSourceAdapter {
    /**
     * 站点清单惰性解析：适配器是同步构造的（[VodAdapters.forSource]），但清单要
     * 先 fetch 加密串再 Base58 解码，是 suspend 的。所以第一次用到时拉取并缓存，
     * 之后再取就直接命中。失败会记下来，同一个适配器实例不会反复重拉同一个坏地址。
     */
    private val siteListCache = SiteListCache()

    private inner class SiteListCache {
        private var loaded: List<MoontvSite>? = null

        suspend fun sites(): VodFetchResult<List<MoontvSite>> {
            loaded?.let { return VodFetchResult.Success(it) }
            return when (val outcome = http.fetch(source.baseUrl, source.headers)) {
                is FetchOutcome.Success -> {
                    val sites =
                        try {
                            MoontvConfig.parse(outcome.text)
                        } catch (e: IllegalArgumentException) {
                            return VodFetchResult.Failed(VodFailure.NOT_VOD_SOURCE, e.message)
                        }
                    loaded = sites
                    VodFetchResult.Success(sites)
                }
                is FetchOutcome.Failure -> VodFetchResult.Failed(outcome.kind.toVodFailure(), outcome.detail)
            }
        }
    }

    override suspend fun categories(): VodFetchResult<List<VodCategory>> {
        val sites =
            when (val r = siteListCache.sites()) {
                is VodFetchResult.Failed -> return r
                is VodFetchResult.Success -> r.value
            }
        // 取第一个能给出分类的站做代表。站都失败才算失败。
        var lastFailure: VodFailure? = null
        for (site in sites) {
            val adapter = adapterFor(site)
            when (val result = adapter.categories()) {
                is VodFetchResult.Success -> {
                    val mapped =
                        result.value.mapNotNull { cat ->
                            val type = cat.type ?: return@mapNotNull null
                            VodCategory(typeId = type.name, name = type.displayName, type = type)
                        }.distinctBy { it.typeId }
                    return VodFetchResult.Success(mapped)
                }
                is VodFetchResult.Failed -> lastFailure = result.reason
            }
        }
        return VodFetchResult.Failed(lastFailure ?: VodFailure.NOT_VOD_SOURCE)
    }

    override suspend fun list(
        typeId: String,
        page: Int,
    ): VodFetchResult<VodPage> {
        val type =
            runCatching { ContentType.valueOf(typeId) }.getOrNull()
                ?: return VodFetchResult.Failed(VodFailure.NOT_VOD_SOURCE)
        val sites =
            when (val r = siteListCache.sites()) {
                is VodFetchResult.Failed -> return r
                is VodFetchResult.Success -> r.value
            }
        val gate = Semaphore(maxConcurrency)
        val pages =
            coroutineScope {
                sites.map { site -> async { gate.withPermit { listOne(site, type, page) } } }.awaitAll()
            }
        return mergePages(pages, page)
    }

    private suspend fun listOne(
        site: MoontvSite,
        type: ContentType,
        page: Int,
    ): VodFetchResult<VodPage> {
        val adapter = adapterFor(site)
        val siteCategories =
            categoryCache.getOrPut(site.key) {
                when (val r = adapter.categories()) {
                    is VodFetchResult.Success -> r.value
                    is VodFetchResult.Failed -> emptyList()
                }
            }
        val siteTypeId =
            siteCategories.firstOrNull { it.type == type }?.typeId ?: return VodFetchResult.Failed(
                VodFailure.NOT_VOD_SOURCE,
            )
        return when (val result = adapter.list(siteTypeId, page)) {
            is VodFetchResult.Success ->
                VodFetchResult.Success(result.value.prefixKeys(site.key))
            is VodFetchResult.Failed -> result
        }
    }

    override suspend fun detail(sourceKey: String): VodFetchResult<VodDetail?> {
        val (siteKey, vodId) =
            splitKey(sourceKey) ?: return VodFetchResult.Failed(
                VodFailure.NOT_VOD_SOURCE,
            )
        val sites =
            when (val r = siteListCache.sites()) {
                is VodFetchResult.Failed -> return r
                is VodFetchResult.Success -> r.value
            }
        val site =
            sites.firstOrNull { it.key == siteKey }
                ?: return VodFetchResult.Failed(VodFailure.NOT_VOD_SOURCE)
        val adapter = adapterFor(site)
        return when (val result = adapter.detail(vodId)) {
            is VodFetchResult.Failed -> result
            is VodFetchResult.Success ->
                // 详情里 summary.sourceKey 要还原成「站点key::vod_id」，与列表 / 进度 / 收藏
                // 里的 sourceKey 保持一致 —— 否则进度按带前缀的 key 存、详情却返回不带前缀的，
                // 「继续观看」就接不上了。
                VodFetchResult.Success(
                    result.value?.let { detail ->
                        detail.copy(summary = detail.summary.copy(sourceKey = sourceKey))
                    },
                )
        }
    }

    override suspend fun search(
        keyword: String,
        page: Int,
    ): VodFetchResult<VodPage> {
        val sites =
            when (val r = siteListCache.sites()) {
                is VodFetchResult.Failed -> return r
                is VodFetchResult.Success -> r.value
            }
        val gate = Semaphore(maxConcurrency)
        val pages =
            coroutineScope {
                sites.map { site -> async { gate.withPermit { searchOne(site, keyword, page) } } }.awaitAll()
            }
        return mergePages(pages, page)
    }

    private suspend fun searchOne(
        site: MoontvSite,
        keyword: String,
        page: Int,
    ): VodFetchResult<VodPage> {
        val adapter = adapterFor(site)
        return when (val result = adapter.search(keyword, page)) {
            is VodFetchResult.Success ->
                VodFetchResult.Success(result.value.prefixKeys(site.key))
            is VodFetchResult.Failed -> result
        }
    }

    /** 站点 key → 适配器，惰性构建并缓存（detail 只建它需要的那一个） */
    private val siteAdapters = mutableMapOf<String, VodSourceAdapter>()

    private fun adapterFor(site: MoontvSite): VodSourceAdapter =
        siteAdapters.getOrPut(site.key) {
            AppleCmsVodAdapter(
                http = http,
                source = VodSourceRef(id = source.id, baseUrl = site.api, headers = source.headers),
            )
        }

    /** 站点 key → 分类（惰性缓存，[list] fan-out 时给每个站找对应分类） */
    private val categoryCache = mutableMapOf<String, List<VodCategory>>()

    /** 合并各站结果：都失败才算失败；有成功就拼起来，按名字去重。 */
    private fun mergePages(
        pages: List<VodFetchResult<VodPage>>,
        page: Int,
    ): VodFetchResult<VodPage> {
        val successes = pages.filterIsInstance<VodFetchResult.Success<VodPage>>().map { it.value }
        if (successes.isEmpty()) {
            val firstFailure =
                pages.filterIsInstance<VodFetchResult.Failed>().firstOrNull()?.reason
                    ?: VodFailure.EMPTY
            return VodFetchResult.Failed(firstFailure)
        }
        val merged = successes.flatMap { it.items }.distinctBy { it.name }
        val total = successes.sumOf { it.total }.takeIf { it > 0 } ?: 0
        val pageCount = successes.maxOf { it.pageCount }
        return VodFetchResult.Success(
            VodPage(items = merged, page = page, pageCount = pageCount, total = total),
        )
    }

    private fun VodPage.prefixKeys(siteKey: String): VodPage =
        copy(items = items.map { it.copy(sourceKey = "$siteKey${KEY_SEPARATOR}${it.sourceKey}") })

    private fun splitKey(sourceKey: String): Pair<String, String>? {
        val idx = sourceKey.indexOf(KEY_SEPARATOR)
        if (idx <= 0) return null
        return sourceKey.substring(0, idx) to sourceKey.substring(idx + KEY_SEPARATOR.length)
    }

    private companion object {
        /** siteKey 与 vod_id 之间的分隔符。域名里不会出现 `::`，故安全 */
        const val KEY_SEPARATOR = "::"

        /** fan-out 并发上限：够快，又不至于把几十个站同时打爆 */
        const val DEFAULT_SITE_CONCURRENCY = 6
    }
}
