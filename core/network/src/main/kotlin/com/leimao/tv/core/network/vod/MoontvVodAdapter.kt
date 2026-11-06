package com.leimao.tv.core.network.vod

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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
        // 聚合层只认九类，一次问出来就一直用。不加这层缓存的话，首页每次刷新、
        // 每次翻页都会重新去问第一个站「你有哪些分类」
        categoryListCache?.let { return VodFetchResult.Success(it) }
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
                    categoryListCache = mapped
                    return VodFetchResult.Success(mapped)
                }
                is VodFetchResult.Failed -> lastFailure = result.reason
            }
        }
        return VodFetchResult.Failed(lastFailure ?: VodFailure.NOT_VOD_SOURCE)
    }

    /** 聚合层分类表（九类）。见 [categories] */
    private var categoryListCache: List<VodCategory>? = null

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
        val pages = fanOut(sites, LIST_BUDGET_MS) { site -> listOne(site, type, page) }
        return mergePages(pages, page)
    }

    /**
     * 单个站点在第 [page] 页该取「第几个子分类、那个子分类的第几页」（2026-11-06）。
     *
     * 原来所有站点都用同一个 `page`：第 2 页就是「每个站的第 2 页」。
     * 可采集站**普遍忽略 `pg`**（第 2 页还是第 1 页那批），于是第 2 页开始各站回的都是
     * 已经给过的内容，去重后一条不剩 —— 用户看到的正是「翻几页就没有了」。
     *
     * 换成二维推进后，深度从「页码」变成「页码 × 该站该类型的子分类数」：
     * 28 个站、每站每类十来个子分类（动作片 / 喜剧片 / 科幻片…），怎么翻都够。
     * 而且不吃额外请求 —— 每页仍然只问每个站一次，只是问的东西往后挪了。
     */
    private suspend fun listOne(
        site: MoontvSite,
        type: ContentType,
        page: Int,
    ): VodFetchResult<VodPage> {
        val adapter = adapterFor(site)
        val ofType = categoriesOfSite(site, adapter).filter { it.type == type }
        if (ofType.isEmpty()) return VodFetchResult.Failed(VodFailure.NOT_VOD_SOURCE)
        val index = (page - 1) % ofType.size
        val roundPage = (page - 1) / ofType.size + 1
        return when (val result = adapter.list(ofType[index].typeId, roundPage)) {
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
        val pages = fanOut(sites, SEARCH_BUDGET_MS) { site -> searchOne(site, keyword, page) }
        return mergePages(pages, page)
    }

    /**
     * 扇出到所有站点，**在预算内能收多少收多少**（2026-11-06）。
     *
     * ## 为什么不能再用 `awaitAll()`
     *
     * 一个聚合源有几十个站（实测 28 个），而这类采集站的生命周期很不稳定：
     * 实测十个站里只有四个能正常返回搜索结果，其余要么 TLS 握手就断、要么回一段网页。
     * 原来的实现是 `sites.map { async { ... } }.awaitAll()` —— 一个站慢就全体等到它，
     * 而外层 `SearchRepository` 只给 8 秒，超时后**连已经回来的那几个站的结果一起丢掉**。
     * 用户看到的就是「搜什么都搜不到」，但其实是四个站明明已经搜到了。
     *
     * 现在的语义分三层：
     * 1. **单站限时**（[SITE_TIMEOUT_MS]）：坏站到点就放弃，不拖累别人；
     * 2. **整轮限时**（[budgetMs]）：到点返回已经收到的结果，部分成功也算成功；
     * 3. **够用即停**（[ENOUGH_ITEMS]）：已经凑够一屏多，没必要为了更全而让用户多等。
     *
     * 这与 [mergePages] 早已声明的语义一致（「单站失败不算整源失败」）—— 之前只是
     * 停在注释里，没落到代码上。
     */
    private suspend fun fanOut(
        sites: List<MoontvSite>,
        budgetMs: Long,
        block: suspend (MoontvSite) -> VodFetchResult<VodPage>,
    ): List<VodFetchResult<VodPage>> {
        val gate = Semaphore(maxConcurrency)
        val out = mutableListOf<VodFetchResult<VodPage>>()
        val mutex = Mutex()
        val enough = AtomicBoolean(false)
        var collected = 0
        coroutineScope {
            val jobs =
                sites.map { site ->
                    async {
                        if (enough.get()) return@async
                        val result = withTimeoutOrNull(SITE_TIMEOUT_MS) { gate.withPermit { block(site) } }
                        if (result != null) {
                            mutex.withLock {
                                out += result
                                if (result is VodFetchResult.Success) collected += result.value.items.size
                                if (collected >= ENOUGH_ITEMS) enough.set(true)
                            }
                        }
                    }
                }
            // 预算用完就把还没回来的掐掉，已收到的结果照样返回
            withTimeoutOrNull(budgetMs) { jobs.forEach { it.join() } }
            jobs.forEach { it.cancel() }
        }
        return out
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

    /**
     * 单个站点的分类表。
     *
     * **失败不写缓存**：原来 `getOrPut` 在失败时写入 `emptyList()`，等于把这个站永久判死 ——
     * 而适配器现在会被缓存复用（见 [VodAdapterCache]），一次网络抖动就变成「这个站以后都不出片」。
     */
    private suspend fun categoriesOfSite(
        site: MoontvSite,
        adapter: VodSourceAdapter,
    ): List<VodCategory> {
        categoryCache[site.key]?.let { return it }
        return when (val r = adapter.categories()) {
            is VodFetchResult.Success -> {
                categoryCache[site.key] = r.value
                r.value
            }
            is VodFetchResult.Failed -> emptyList()
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
        const val DEFAULT_SITE_CONCURRENCY = 8

        /**
         * 单个站点的限时（2026-11-06）。
         *
         * 这类采集站里有相当一部分是死的：实测十个站里四个能搜出结果，
         * 其余要么 TLS 握手就断、要么回一段 HTML。给活站 3 秒足够（实测 0.6~2.2 秒），
         * 死站到点就放弃 —— 让死站决定整次搜索的成败，是原来「什么都搜不出」的直接原因。
         */
        const val SITE_TIMEOUT_MS = 3_000L

        /**
         * 搜索的总预算。
         *
         * 外层 `SearchRepository` 给 8 秒，这里留 2 秒给「解订阅 → 建适配器 → 解析」的余量。
         * 预算到点就带着已收到的结果返回，而不是把整次搜索判成超时。
         */
        const val SEARCH_BUDGET_MS = 6_000L

        /**
         * 列表（首页翻页）的总预算。
         *
         * 比搜索宽（翻页没有「盯着输入框」的紧迫感，收得太早一页会稀稀拉拉），
         * 但不能无限等：28 个站、并发 8，跑完要 4 轮，一轮按 3 秒算就是 12 秒 ——
         * 用户划到底等 12 秒早划走了。8 秒能把两轮多的结果收进来，够铺满一屏。
         */
        const val LIST_BUDGET_MS = 8_000L

        /**
         * 收到这么多条就不再等剩下的站（够铺一屏还有富余）。
         *
         * 冷门关键词下永远到不了这个数，那就老老实实等满预算 —— 两者不冲突。
         */
        const val ENOUGH_ITEMS = 60
    }
}
