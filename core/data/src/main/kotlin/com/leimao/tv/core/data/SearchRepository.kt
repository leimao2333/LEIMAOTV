package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelRepository
import com.leimao.tv.core.database.repository.ChannelWithSource
import com.leimao.tv.core.database.repository.MediaRepository
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.allowedTypesFor
import com.leimao.tv.core.model.filterForScope
import com.leimao.tv.core.model.search.SearchHit
import com.leimao.tv.core.model.search.SearchKind
import com.leimao.tv.core.model.search.SearchOutcome
import com.leimao.tv.core.model.search.channelSearchKey
import com.leimao.tv.core.model.search.normalizeSearchQuery
import com.leimao.tv.core.model.search.vodSearchKey
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.vod.VodAdapters
import com.leimao.tv.core.network.vod.VodFailure
import com.leimao.tv.core.network.vod.VodFetchResult
import com.leimao.tv.core.network.vod.VodSourceAdapter
import com.leimao.tv.core.network.vod.VodSourceRef
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 跨库搜索（SEARCH-01）：本地频道 + 本地影视快照 + 远端多源并发（SEARCH-09）。
 *
 * ## 为什么返回 `Flow` 而不是 `suspend fun`
 *
 * 三类结果的时间尺度差两个数量级：本地库一帧内出、快源 300ms、慢源能拖到 8 秒。
 * 等全部回来再一次性返回，用户在这 8 秒里看到的是一个空页面 —— 而频道早就搜到了。
 * 所以这里**每回来一条源就发一帧快照**（SEARCH-05），UI 只管整块重画。
 *
 * ## 白名单在出口（SEARCH-02）
 *
 * 每一次发射的 [SearchOutcome] 都已经过 `filterForScope`，UI 拿到的永远是合法结果。
 * 位置就在这里、不在 UI，是因为 UI 会改、会复用、会被复制粘贴 —— 出口只有一个，漏不掉（RULES §3.2）。
 *
 * ## 失败要记名，不能只记个数
 *
 * 「有几条源没查完」和「这几条源搜不了」在界面上是两种说法，用户据此决定是等一会儿还是去源管理。
 */
class SearchRepository(
    private val channels: ChannelRepository,
    private val media: MediaRepository,
    private val sources: SourceRepository,
    private val http: HttpClient,
    /** 单源超时（SEARCH-09）。8 秒是「用户还能忍」与「慢源别拖死整页」的折中 */
    private val perSourceTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** 同时最多几个源在搜：源多时一起打既引对方风控，也把本机并发连接吃满 */
    private val maxConcurrency: Int = DEFAULT_CONCURRENCY,
    private val adapterFactory: (VodSourceRef) -> VodSourceAdapter = { VodAdapters.forSource(http, it) },
    /**
     * 汉字→拼音表（SEARCH-06）。给了它，频道还能按拼音搜：
     * `hunan` / `hnws` →「湖南卫视」。没给就退化成只按名字搜。
     */
    private val pinyin: PinyinTable = PinyinTable.EMPTY,
) {
    /**
     * 发起一次搜索。[scope] 决定搜哪些库、结果要不要过滤：
     * - [SearchScope.IPTV]：频道 + 影视，影视按 `IPTV_ALLOWED_TYPES` 过滤；
     * - [SearchScope.VOD]：只搜影视，九类全返回（SEARCH-04）。
     *
     * 空关键词**不发任何请求**，直接给一帧空结果 —— 遥控器输入时每敲一个键都会触发搜索，
     * 清空输入的那一刻不该产生一次网络风暴。
     */
    fun search(
        query: String,
        scope: SearchScope,
    ): Flow<SearchOutcome> =
        channelFlow {
            val keyword = normalizeSearchQuery(query)
            if (keyword.isEmpty()) {
                send(SearchOutcome(query = keyword, scope = scope))
                return@channelFlow
            }

            val vodSources = sources.enabled().filter { it.type == SourceType.VOD }
            val sourceNames = vodSources.associate { it.id to it.name }

            // ① 本地：频道只在直播板块搜（影视板块搜出频道是噪音），影视快照两个板块都搜
            val localHits =
                buildList {
                    if (scope == SearchScope.IPTV) {
                        val byName = channels.search(keyword)
                        byName.forEach { add(it.toHit()) }
                        addAll(pinyinHits(keyword, byName.mapTo(HashSet()) { it.channel.id }))
                    }
                    media.search(keyword).forEach { add(it.toHit(sourceNames[it.sourceId])) }
                    // 影视的拼音命中：26 键打全拼（hunan / aichidian）是主要输入方式，
                    // 只支持频道的拼音等于没支持影视搜索 —— 这是 26 键的核心场景
                    addAll(mediaPinyinHits(keyword, sourceNames))
                }

            val state = SearchState(hits = localHits, pending = vodSources.size)
            send(state.snapshot(keyword, scope))

            if (vodSources.isEmpty()) return@channelFlow

            // ② 远端：并发多源，谁先回来谁先补一帧
            val gate = Semaphore(maxConcurrency)
            coroutineScope {
                vodSources.forEach { source ->
                    launch {
                        val result = searchOne(source, keyword, gate)
                        val snapshot =
                            state.mutex.withLock {
                                state.record(source, result)
                                state.snapshot(keyword, scope)
                            }
                        // 锁外发送：持锁 send 会让慢的收集方把所有源一起卡住
                        send(snapshot)
                    }
                }
            }
        }

    /**
     * 拼音简写补一轮（SEARCH-06）。
     *
     * 只在**查询是纯 ASCII 字母数字**时跑：中文查询走 SQL 的 LIKE 已经够准，
     * 再比一遍拼音只会把「搜『湖南』」变成「搜 hn」那种误命中。
     *
     * [alreadyHit] 是名字已经命中的频道 id：两套口径会重合（`cctv5` 既在名字里、
     * 也在简写里），去重靠它，不靠结果 key 的二次去重 —— 那样会把名字命中的顺序打乱。
     */
    private suspend fun pinyinHits(
        keyword: String,
        alreadyHit: Set<Long>,
    ): List<SearchHit> {
        if (pinyin.size == 0) return emptyList()
        if (keyword.isEmpty() || keyword.any { it.code >= 128 || !it.isLetterOrDigit() }) return emptyList()

        return channels
            .enabledWithSource()
            .asSequence()
            .filter { it.channel.id !in alreadyHit }
            .filter { pinyin.matches(it.channel.name, keyword) }
            .take(PINYIN_LIMIT)
            .map { it.toHit() }
            .toList()
    }

    /**
     * 中文候选词（SEARCH-06 的「提示词」）。
     *
     * 九宫格只能打出字母，用户看不到自己打的是什么字 —— 所以要把命中的中文名回显成候选：
     * 打 `hnws` 提示「湖南卫视」，按 OK 直接用它搜。没有这一步，拼音简写搜索在电视上
     * 就是「打完四个字母，等着看有没有猜对」。
     *
     * 中文词优先、去重、限条数：候选是「帮你确认输入」的，不是第二个结果列表。
     */
    suspend fun suggest(
        query: String,
        scope: SearchScope,
        limit: Int = SUGGEST_LIMIT,
    ): List<String> {
        val keyword = normalizeSearchQuery(query)
        if (keyword.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        if (keyword.all { it.code < 128 && it.isLetterOrDigit() }) {
            // 字母查询：频道名与影视片名都按拼音过一遍（全拼 hunan / 简写 hnws 都命中）
            channels
                .enabledWithSource()
                .asSequence()
                .filter { pinyin.matches(it.channel.name, keyword) }
                .map { it.channel.name }
                .forEach { if (out.size < limit) out.add(it) }
            if (out.size < limit) {
                media
                    .titles()
                    .asSequence()
                    // 候选词与结果同守白名单：直播板块提示「短剧搞笑社」却搜不出它，
                    // 比不提示更误导（2026-10-25 验收实测）
                    .filter { ContentType.valueOf(it.type) in allowedTypesFor(scope) }
                    .filter { pinyin.matches(it.text, keyword) }
                    .map { it.text }
                    .forEach { if (out.size < limit) out.add(it) }
            }
        } else {
            // 中文查询（系统输入法打出来的）：直接拿名字里含这个词的
            channels
                .search(keyword)
                .asSequence()
                .map { it.channel.name }
                .forEach { if (out.size < limit) out.add(it) }
        }
        if (out.size < limit) {
            media
                .search(keyword)
                .asSequence()
                .filter { it.type in allowedTypesFor(scope) }
                .map { it.name }
                .forEach { if (out.size < limit) out.add(it) }
        }
        return out.toList()
    }

    /**
     * 影视的拼音命中（LIVE-13 / SEARCH-06）：全拼与简写都能命中标题。
     *
     * 与 [pinyinHits]（频道）分开：频道的命中集来自 `enabledWithSource()`，
     * 影视的来自 `allSummaries()` —— 两边的数据形态与上限都不同。
     * 白名单在 snapshot 出口统一过滤，这里不重复判（但**提前滤掉**能省掉白名单外的转换）。
     */
    private suspend fun mediaPinyinHits(
        keyword: String,
        sourceNames: Map<Long, String>,
    ): List<SearchHit> {
        if (pinyin.size == 0) return emptyList()
        if (keyword.isEmpty() || keyword.any { it.code >= 128 || !it.isLetterOrDigit() }) return emptyList()

        return media
            .allSummaries()
            .asSequence()
            .filter { pinyin.matches(it.name, keyword) }
            .take(PINYIN_LIMIT)
            .map { it.toHit(sourceNames[it.sourceId]) }
            .toList()
    }

    private suspend fun searchOne(
        source: Source,
        keyword: String,
        gate: Semaphore,
    ): SourceResult {
        val adapter =
            adapterFactory(
                VodSourceRef(
                    id = source.id,
                    baseUrl = source.url,
                    headers = source.toRequestHeaders(),
                    format = source.format,
                ),
            )

        return when (
            val result = withTimeoutOrNull(perSourceTimeoutMs) { gate.withPermit { adapter.search(keyword) } }
        ) {
            null -> SourceResult.TimedOut
            is VodFetchResult.Failed -> SourceResult.Failed(result.reason)
            is VodFetchResult.Success -> SourceResult.Hits(result.value.toHits(source.name))
        }
    }

    /** 一条源搜完之后的增量。整个类只有 [SearchState.mutex] 保护它，别处不直接改这两个字段 */
    private class SearchState(
        var hits: List<SearchHit>,
        var pending: Int,
    ) {
        val mutex = Mutex()
        val failed = mutableListOf<String>()

        fun record(
            source: Source,
            result: SourceResult,
        ) {
            when (result) {
                is SourceResult.Hits -> hits = hits + result.hits
                SourceResult.TimedOut, is SourceResult.Failed -> failed += source.name
            }
            pending -= 1
        }

        fun snapshot(
            keyword: String,
            scope: SearchScope,
        ): SearchOutcome =
            SearchOutcome(
                query = keyword,
                scope = scope,
                // 出口过滤：先去重（本地快照与远端结果会重复），再过白名单
                hits = hits.distinctBy { it.key }.filterForScope(scope) { it.type },
                pendingSources = pending,
                failedSources = failed.toList(),
            )
    }

    private sealed interface SourceResult {
        data class Hits(val hits: List<SearchHit>) : SourceResult

        data object TimedOut : SourceResult

        data class Failed(val reason: VodFailure) : SourceResult
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val DEFAULT_CONCURRENCY = 4

        /** 拼音补的最多条数：简写很宽（`c` 能命中一大片），截一屏就够，别把结果区灌满 */
        const val PINYIN_LIMIT = 30

        /** 候选词条数：一行放得下、也不至于把用户看花 */
        const val SUGGEST_LIMIT = 6
    }
}

/** 频道 → 结果。直播频道是唯一 `type` 固定的种类，不必再判断 */
private fun ChannelWithSource.toHit(): SearchHit =
    SearchHit(
        kind = SearchKind.CHANNEL,
        key = channelSearchKey(channel.id),
        type = ContentType.LIVE_CHANNEL,
        title = channel.name,
        subtitle = "${channel.groupName} · $sourceName",
        posterUrl = channel.logoUrl,
        sourceId = sourceId,
        sourceName = sourceName,
        channelId = channel.id,
    )

/**
 * 影视摘要 → 结果。
 *
 * 副标题拼「类型 · 年份 · 地区 · 更新备注」而不是只放类型：搜索结果里同名的片有好几版
 * （电影版 / 剧版 / 不同年份），年份与备注是用户唯一能分辨的依据。
 */
private fun VodSummary.toHit(sourceName: String?): SearchHit =
    SearchHit(
        kind = SearchKind.VOD,
        key = vodSearchKey(sourceId, sourceKey),
        type = type,
        title = name,
        subtitle =
            listOfNotNull(
                type.displayName,
                year?.toString(),
                area?.takeIf { it.isNotBlank() },
                remarks?.takeIf { it.isNotBlank() },
            ).joinToString(SEPARATOR)
                .ifEmpty { null },
        posterUrl = pic,
        sourceId = sourceId,
        sourceName = sourceName,
        sourceKey = sourceKey,
    )

private fun VodPage.toHits(sourceName: String): List<SearchHit> =
    items.map { it.toHit(sourceName) }

private const val SEPARATOR = " · "
