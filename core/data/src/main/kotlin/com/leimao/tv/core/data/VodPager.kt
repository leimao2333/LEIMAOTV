package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.vod.VodAdapters
import com.leimao.tv.core.network.vod.VodFetchResult
import com.leimao.tv.core.network.vod.VodSourceAdapter
import com.leimao.tv.core.network.vod.VodSourceRef
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 影视首页「往下翻」的翻页器（VOD-14）。
 *
 * ## 为什么需要它
 *
 * 首页原本只读本地快照（每个源每类只抓第一页，约 30 条），翻两屏就到底了 ——
 * 用户的原话是「只有那么几个推荐的太少了，要能一直翻」。快照是为**首屏不白屏**服务的，
 * 不该成为内容上限：想继续看，就得按页去源上要。
 *
 * ## 两个刻意的取舍
 *
 * 1. **不动本地快照**。翻出来的页只交给当前页面显示，不写库：
 *    写进去会让「刷新」与「翻页」两套数据互相覆盖，且快照会无限膨胀，
 *    下次冷启动又要读一堆用户早就划过去的内容。
 * 2. **分类结果在内存里缓存 [CATEGORY_TTL_MS]**。翻页时每页都先问一次分类是浪费 ——
 *    分类几乎不变，而一个源一次分类请求就是一次往返。
 *
 * ## 戏曲为什么走搜索
 *
 * 聚合源里「戏曲」分类名对得上，但**内容不对**：大量采集站的戏曲分类里混着
 * 短剧、相声、甚至电视剧片段（真机实测：戏曲板块里全是无关内容）。
 * 与其维护一张永远补不完的黑名单，不如直接按关键词搜「戏曲」——
 * 源自己的搜索结果反而更贴题。
 */
class VodPager(
    private val sources: SourceRepository,
    private val http: HttpClient,
    private val adapterFactory: (VodSourceRef) -> VodSourceAdapter = { VodAdapters.forSource(http, it) },
    private val maxConcurrency: Int = DEFAULT_CONCURRENCY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 源 id → 分类（带抓取时刻）。只缓存在内存里：分类变了重开一次 App 就自动跟上 */
    private val categoryCache = HashMap<Long, CachedCategories>()

    private data class CachedCategories(
        val at: Long,
        val categories: List<VodCategory>,
    )

    /**
     * 取某一类内容的第 [page] 页（1 起）。
     *
     * 多个源并发拉、结果按源顺序拼接：源之间的顺序保持稳定，
     * 翻页时不会因为「这次谁先回来」导致列表跳来跳去。
     */
    suspend fun page(
        type: ContentType,
        page: Int,
    ): VodPageResult {
        if (page < 1) return VodPageResult(emptyList(), okSources = 0)
        val vodSources = sources.list().filter { it.type == SourceType.VOD && it.enabled }
        if (vodSources.isEmpty()) return VodPageResult(emptyList(), okSources = 0)

        val gate = Semaphore(maxConcurrency)
        val results =
            coroutineScope {
                vodSources.map { source -> async { fetchOne(source, type, page, gate) } }.awaitAll()
            }

        val items =
            results.filterNotNull().flatten()
                // 跨页去重交给调用方之前先做一次页内去重：同一个源翻页时偶尔会重复吐出边界条目
                .distinctBy { it.sourceId to it.sourceKey }
        return VodPageResult(items = items, okSources = results.count { it != null })
    }

    /** 返回 null = 这个源这次没成功（网络/格式），空列表 = 成功了但这一页确实没内容 */
    private suspend fun fetchOne(
        source: Source,
        type: ContentType,
        page: Int,
        gate: Semaphore,
    ): List<VodSummary>? {
        val adapter =
            adapterFactory(
                VodSourceRef(
                    id = source.id,
                    baseUrl = source.url,
                    headers = source.toRequestHeaders(),
                    format = source.format,
                ),
            )

        if (type == ContentType.OPERA) {
            // 见类注释：戏曲不走分类，直接搜
            val result = gate.withPermit { adapter.search(OPERA_KEYWORD, page) }
            return (result as? VodFetchResult.Success)?.value?.items
        }

        val categories = categoriesOf(source, adapter, gate) ?: return null
        val candidates = pickVodCandidates(categories)[type] ?: return emptyList()

        // 与刷新器同一套策略：同一个类型可能对应源里多个分类，依次试到有内容为止
        for (category in candidates) {
            val result = gate.withPermit { adapter.list(category.typeId, page) }
            if (result is VodFetchResult.Success && result.value.items.isNotEmpty()) {
                return result.value.items
            }
        }
        return emptyList()
    }

    private suspend fun categoriesOf(
        source: Source,
        adapter: VodSourceAdapter,
        gate: Semaphore,
    ): List<VodCategory>? {
        val now = clock()
        categoryCache[source.id]?.let { cached ->
            if (now - cached.at < CATEGORY_TTL_MS) return cached.categories
        }
        val result = gate.withPermit { adapter.categories() }
        val categories = (result as? VodFetchResult.Success)?.value ?: return null
        categoryCache[source.id] = CachedCategories(now, categories)
        return categories
    }

    /** 源被删/停用后清掉缓存，免得内存里一直留着一份用不上的分类表 */
    fun forget(sourceId: Long) {
        categoryCache.remove(sourceId)
    }

    private companion object {
        const val DEFAULT_CONCURRENCY = 4

        /** 分类缓存有效期：10 分钟。够覆盖一次「一直往下翻」，又不会拿隔夜的分类去查 */
        const val CATEGORY_TTL_MS = 10 * 60 * 1000L

        /** 戏曲板块的搜索词。见类注释里「戏曲为什么走搜索」 */
        const val OPERA_KEYWORD = "戏曲"
    }
}

/**
 * 一页翻页结果。
 *
 * [okSources] 用来区分两种「没有内容」：**所有源都失败**是网络/格式问题（该说「加载失败，
 * 重试」），**源都成功但这一页是空**才是真的到底了（该说「没有更多了」）。
 * 混成一个空列表，用户永远分不清该不该继续等。
 */
data class VodPageResult(
    val items: List<VodSummary>,
    val okSources: Int,
) {
    val allFailed: Boolean get() = okSources == 0
}
