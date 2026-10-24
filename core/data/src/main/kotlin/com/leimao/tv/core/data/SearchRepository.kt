package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelRepository
import com.leimao.tv.core.database.repository.ChannelWithSource
import com.leimao.tv.core.database.repository.MediaRepository
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.SearchScope
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
                        channels.search(keyword).forEach { add(it.toHit()) }
                    }
                    media.search(keyword).forEach { add(it.toHit(sourceNames[it.sourceId])) }
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

    private suspend fun searchOne(
        source: Source,
        keyword: String,
        gate: Semaphore,
    ): SourceResult {
        val adapter =
            adapterFactory(
                VodSourceRef(id = source.id, baseUrl = source.url, headers = source.toRequestHeaders()),
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
