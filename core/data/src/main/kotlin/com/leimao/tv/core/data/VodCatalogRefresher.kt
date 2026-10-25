package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.MediaRepository
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.vod.VodAdapters
import com.leimao.tv.core.network.vod.VodFailure
import com.leimao.tv.core.network.vod.VodFetchResult
import com.leimao.tv.core.network.vod.VodSourceAdapter
import com.leimao.tv.core.network.vod.VodSourceRef
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 单个影视源这次刷出了什么。
 *
 * [fetchedTypes] 只含**真的拿到内容**的类型 —— UI 据此回答「为什么电影这一栏是空的」：
 * 是这个源没有电影分类，还是这次没抓成功。
 */
data class VodSourceOutcome(
    val sourceId: Long,
    val sourceName: String,
    val itemCount: Int,
    val fetchedTypes: Set<ContentType>,
    val failure: VodFailure? = null,
    val detail: String? = null,
) {
    val ok: Boolean get() = failure == null && itemCount > 0
}

/** 一次刷新全貌。首页用它区分「没有影视源」与「有源但都没抓到」。 */
data class VodCatalogReport(
    val at: Long,
    val outcomes: List<VodSourceOutcome>,
) {
    val totalItems: Int get() = outcomes.sumOf { it.itemCount }

    val hasSource: Boolean get() = outcomes.isNotEmpty()

    val failedCount: Int get() = outcomes.count { !it.ok }
}

/**
 * 影视首页的数据编排（VOD-03）。
 *
 * 干的事：对每个启用的影视源 → 拉分类 → 把源分类映射到九类 → 每类拉第一页 → 落库。
 * 首页只读库，不等网络（RULES §7：主线程不做 IO，首屏不白屏）。
 *
 * 三个刻意的取舍：
 *
 * 1. **每类只拉第一页**。首页是「橱窗」不是「片库」，30 条够翻几屏；
 *    真要找东西走搜索。全量翻页会把一个源打成几千条，首页永远刷不完。
 * 2. **一个类型可能对应源里多个分类**（「电影」下面还有「动作片」「喜剧片」）。
 *    优先用顶层分类；顶层是空的（不少源这么干）再试同类型下一个，最多两个候选 ——
 *    这是「宁可多一次请求，也不要给用户一个空栏目」的折中。
 * 3. **源之间并发、类型之间并发，但总量用信号量压住**。一个源 6 个类型、三个源就是
 *    18 个请求，全放出去既打对方也打自己；限 6 个并发既快又不至于被风控。
 */
class VodCatalogRefresher(
    private val sources: SourceRepository,
    private val media: MediaRepository,
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxConcurrency: Int = DEFAULT_CONCURRENCY,
    /**
     * 适配器工厂。默认按源造真实适配器；测试注入假实现，
     * 这样「分类怎么挑、失败怎么归类」这些判断逻辑能在 JVM 单测里跑，不用起网络。
     */
    private val adapterFactory: (VodSourceRef) -> VodSourceAdapter = { VodAdapters.forSource(http, it) },
) {
    suspend fun refreshAll(): VodCatalogReport = refresh(sources.enabled())

    /** 只刷影视源；直播源走 [SourceRefresher]，两条链路互不干扰 */
    suspend fun refresh(candidates: List<Source>): VodCatalogReport {
        val now = clock()
        val vodSources = candidates.filter { it.type == SourceType.VOD && it.enabled }
        if (vodSources.isEmpty()) return VodCatalogReport(at = now, outcomes = emptyList())

        val gate = Semaphore(maxConcurrency)
        val outcomes =
            coroutineScope {
                vodSources.map { source -> async { refreshOne(source, now, gate) } }.awaitAll()
            }
        return VodCatalogReport(at = now, outcomes = outcomes)
    }

    private suspend fun refreshOne(
        source: Source,
        now: Long,
        gate: Semaphore,
    ): VodSourceOutcome {
        val adapter =
            adapterFactory(
                VodSourceRef(
                    id = source.id,
                    baseUrl = source.url,
                    headers = source.toRequestHeaders(),
                    format = source.format,
                ),
            )

        val categories =
            when (val result = adapter.categories()) {
                is VodFetchResult.Success -> result.value
                is VodFetchResult.Failed ->
                    return VodSourceOutcome(
                        sourceId = source.id,
                        sourceName = source.name,
                        itemCount = 0,
                        fetchedTypes = emptySet(),
                        failure = result.reason,
                        detail = result.detail,
                    )
            }

        val candidates = pickVodCandidates(categories)
        if (candidates.isEmpty()) {
            // 分类一个都认不出来：地址可能填成了直播源，或者是别家的接口格式
            return VodSourceOutcome(
                sourceId = source.id,
                sourceName = source.name,
                itemCount = 0,
                fetchedTypes = emptySet(),
                failure = VodFailure.NOT_VOD_SOURCE,
                detail = "分类名对不上任何一类内容",
            )
        }

        val pages =
            coroutineScope {
                candidates.entries
                    .map { (type, list) -> async { fetchType(adapter, type, list, gate) } }
                    .awaitAll()
            }.filterNotNull()
                .toMap()

        if (pages.isEmpty()) {
            return VodSourceOutcome(
                sourceId = source.id,
                sourceName = source.name,
                itemCount = 0,
                fetchedTypes = emptySet(),
                failure = VodFailure.EMPTY,
                detail = "所有分类都没返回内容",
            )
        }

        // 落库按类型替换：这次没抓成功的类型保留上次快照（见 MediaRepository.replace）
        media.replace(source.id, pages, now)

        return VodSourceOutcome(
            sourceId = source.id,
            sourceName = source.name,
            itemCount = pages.values.sumOf { it.size },
            fetchedTypes = pages.keys,
        )
    }

    /** 依次试候选分类，第一个拿到内容的就算这一类的数据源 */
    private suspend fun fetchType(
        adapter: VodSourceAdapter,
        type: ContentType,
        candidates: List<VodCategory>,
        gate: Semaphore,
    ): Pair<ContentType, List<VodSummary>>? {
        for (category in candidates) {
            val result = gate.withPermit { adapter.list(category.typeId, page = 1) }
            if (result is VodFetchResult.Success && result.value.items.isNotEmpty()) {
                return type to result.value.items
            }
        }
        return null
    }

    private companion object {
        /** 同时最多 6 个请求：3 个源 × 6 个类型 = 18 个，分三批跑完 */
        const val DEFAULT_CONCURRENCY = 6
    }
}

/** 一个类型最多试 2 个分类：顶层 + 一个子分类，再多就是在猜了 */
private const val CANDIDATES_PER_TYPE = 2

/**
 * 每个类型挑最多 [CANDIDATES_PER_TYPE] 个候选分类，顺序即优先级。
 *
 * **不改分类顺序**：苹果CMS 返回的分类通常顶层在前，按原序取第一个命中率最高。
 * 映射不出九类的分类（源自定义的「福利」之类）直接跳过 —— 合规上也不该展示。
 */
internal fun pickVodCandidates(categories: List<VodCategory>): Map<ContentType, List<VodCategory>> {
    val result = linkedMapOf<ContentType, MutableList<VodCategory>>()
    for (category in categories) {
        val type = category.type ?: continue
        if (type == ContentType.LIVE_CHANNEL) continue
        val list = result.getOrPut(type) { mutableListOf() }
        if (list.size < CANDIDATES_PER_TYPE) list.add(category)
    }
    return result
}
