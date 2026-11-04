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

    /**
     * 推荐栏目的第 [page] 页（VOD-14 的「推荐也要能一直翻」）。
     *
     * 与 [page] 是两种翻法：
     * - 类型栏目是「一个类型的第 N 页」，同一个分类一直往后翻；
     * - 推荐是「跨类型的第 N 屏」，把每个源的候选分类排成一个环，按页推进 ——
     *   第 1 页取第 1 个分类的第 1 页，第 2 页取第 2 个分类的第 1 页……一圈轮完
     *   再从第 1 个分类借第 2 页。
     *
     * 为什么不能只翻一个分类：采集站的分类往往只有十几页，而且不少站**忽略 pg 参数**
     * （翻页返回同一批），去重后就是空 —— 用户看到的正是「翻两下就没了」。
     * 换分类能绕开这两种情况：只要源里还有别的分类，就一直有内容。
     */
    suspend fun recommendPage(page: Int): VodPageResult {
        if (page < 1) return VodPageResult(emptyList(), okSources = 0)
        val vodSources = sources.list().filter { it.type == SourceType.VOD && it.enabled }
        if (vodSources.isEmpty()) return VodPageResult(emptyList(), okSources = 0)

        val gate = Semaphore(maxConcurrency)
        val results =
            coroutineScope {
                vodSources.map { source -> async { fetchRecommend(source, page, gate) } }.awaitAll()
            }
        val items =
            results.filterNotNull().flatten().distinctBy { it.sourceId to it.sourceKey }
        return VodPageResult(items = items, okSources = results.count { it != null })
    }

    /** 推荐页的单个源：分类环推进。返回 null = 这个源这次不可用 */
    private suspend fun fetchRecommend(
        source: Source,
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
        val categories = categoriesOf(source, adapter, gate) ?: return null
        // 所有类型下的候选分类拍平成一个环：顺序稳定（LinkedHashMap 的插入序），
        // 同一个源每次翻页取到的分类是可预期的，不会这次是电影下次又是综艺。
        // 环的大小直接决定「能往下翻多少页」，所以这里同样用翻页的宽口径
        val ring =
            pickVodCandidates(categories, PAGER_CANDIDATES_PER_TYPE).values.flatten()
                .distinctBy { it.typeId }
        if (ring.isEmpty()) return null

        val (index, roundPage) = recommendSlot(page, ring.size)
        val result = gate.withPermit { adapter.list(ring[index].typeId, roundPage) }
        return (result as? VodFetchResult.Success)?.value?.items
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
        // 翻页用「多余量」的候选分类：环里有几个分类，就能翻几页新内容（见 PAGER_CANDIDATES_PER_TYPE）
        val candidates =
            pickVodCandidates(categories, PAGER_CANDIDATES_PER_TYPE)[type] ?: return emptyList()
        if (candidates.isEmpty()) return emptyList()

        // 与推荐栏目同一套环推进：先把每个分类的第 1 页走一遍，再回头借第 2 页。
        //
        // 这是「翻两下就没了」的直接修法。原来所有分类都用**同一个 page** 依次试，
        // 采集站普遍忽略 `pg` 参数（第 2 页返回的还是第 1 页那批），
        // 于是第 2 页就全军覆没 → 界面停在「没有更多了」。
        // 换成环之后，即使每个分类只会吐第一页，10 个分类也能撑 10 页；
        // 真支持翻页的源，第二圈照样能拿到第 2 页。
        val (index, roundPage) = recommendSlot(page, candidates.size)
        val result = gate.withPermit { adapter.list(candidates[index].typeId, roundPage) }
        return (result as? VodFetchResult.Success)?.value?.items
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

        /**
         * 翻页时每个类型最多用几个候选分类（2026-11-04）。
         *
         * 抓首屏用 2 个就够（多试就是多打请求），但**翻页是拿分类当环用的** ——
         * 环里有几个分类，就能翻几页新内容。原来沿用 2，环只有两格：
         * 第 3 页绕回第一个分类的第 2 页，而采集站普遍忽略 `pg`，
         * 拿回来的还是第 1 页那批，去重后一条不剩，界面就停在「没有更多了」——
         * 这正是用户说的「随便翻两下就直接没新东西了」。
         *
         * 12 是折中：苹果CMS 一个类型的子分类（动作片 / 喜剧片 / 科幻片…）
         * 通常就有十几个，取 12 意味着 12 页、约 240 部片，够翻很久了；
         * 再大就只是把「源里没有」推迟几页。
         */
        const val PAGER_CANDIDATES_PER_TYPE = 12
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

/**
 * 推荐栏目第 [page] 屏该取「分类环里的第几个分类、那个分类的第几页」。
 *
 * 第 1 屏取第 1 个分类的第 1 页，第 2 屏取第 2 个分类的第 1 页……
 * 一圈轮完再从第 1 个分类借第 2 页。抽成纯函数是为了能单测：
 * 这里算错的表现是「翻两页又从头来」，用户看到的全是重复内容。
 */
internal fun recommendSlot(
    page: Int,
    categoryCount: Int,
): Pair<Int, Int> {
    if (categoryCount <= 0 || page < 1) return 0 to 1
    return (page - 1) % categoryCount to (page - 1) / categoryCount + 1
}
