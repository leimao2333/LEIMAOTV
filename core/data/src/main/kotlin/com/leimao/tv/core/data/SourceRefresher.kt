package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelWriteStats
import com.leimao.tv.core.database.repository.ChannelWriter
import com.leimao.tv.core.database.repository.NewSource
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.playlist.ParsedPlaylist
import com.leimao.tv.core.model.playlist.PlaylistFormat
import com.leimao.tv.core.model.playlist.mergeChannels
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import com.leimao.tv.core.network.playlist.Playlists

/** 一次成功的刷新到底改了什么。用于「刷新完成」提示与源管理页的「上次更新」。 */
data class RefreshOutcome(
    val sourceId: Long,
    val sourceName: String,
    val format: PlaylistFormat,
    val channelCount: Int,
    val skippedLines: Int,
    val stats: ChannelWriteStats,
)

/** 刷新失败的原因集合：既有抓取失败，也有「抓到了但不是播放列表」。 */
enum class RefreshFailure {
    TIMEOUT,
    HTTP,
    NETWORK,
    EMPTY,

    /** 抓到了内容，但一条频道都没解析出来 */
    NO_CHANNELS,

    /** 源已经被删了（并发操作） */
    SOURCE_MISSING,
}

sealed interface RefreshResult {
    data class Done(val outcome: RefreshOutcome) : RefreshResult

    data class Failed(val sourceId: Long, val reason: RefreshFailure, val detail: String? = null) : RefreshResult
}

/**
 * 「抓取 → 解析 → 合并 → 落库」的唯一编排入口（LIVE-03 / LIVE-05 / LIVE-07）。
 *
 * 之所以独立在 :core:data 而不是塞进某个 feature：源管理页的手动刷新、启动时的后台刷新、
 * 定时刷新的 WorkManager 都要走同一条路径。三处各写一遍，迟早出现「手动刷新不更新线路、
 * 定时刷新把收藏删了」这类只在某条路径复现的缺陷。
 *
 * 失败一律**落库记录原因**（`lastRefreshNote`），而不是只弹一次 toast ——
 * 用户下次打开源管理页还能看到「上次为什么没成功」。
 */
class SourceRefresher(
    private val sources: SourceRepository,
    private val writer: ChannelWriter,
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun refresh(sourceId: Long): RefreshResult {
        val source = sources.find(sourceId) ?: return RefreshResult.Failed(sourceId, RefreshFailure.SOURCE_MISSING)

        val outcome = http.fetch(source.url, source.toRequestHeaders())
        if (outcome is FetchOutcome.Failure) {
            sources.markRefreshed(sourceId, clock(), describe(outcome))
            return RefreshResult.Failed(sourceId, outcome.kind.toRefreshFailure(), outcome.detail)
        }

        val text = (outcome as FetchOutcome.Success).text
        return parseAndStore(source, text, fromNetwork = true)
    }

    suspend fun refreshAll(): List<RefreshResult> =
        sources.enabled()
            // 影视源不是播放列表：拿它走直播解析只会失败，并给源管理页写一条
            // 「内容不是 M3U/TXT」的脏记录。两条链路各刷各的（VOD-03）
            .filter { it.type == SourceType.LIVE }
            .map { source -> refresh(source.id) }

    /**
     * 本地文件导入（LIVE-04）。
     *
     * 文件已由 UI 层（SAF）读出文本再交进来：content:// 的读取权限与生命周期属于 Activity，
     * 放在这一层反而要在数据层到处要 Context。这里只负责「建源 + 解析 + 落库」。
     */
    suspend fun importText(
        name: String,
        uri: String,
        text: String,
        headers: RequestHeaders = RequestHeaders.NONE,
    ): RefreshResult {
        val sourceId =
            sources.add(
                NewSource(
                    name = name,
                    url = uri,
                    userAgent = headers.userAgent,
                    referer = headers.referer,
                    basicUser = headers.basicAuth?.username,
                    basicPass = headers.basicAuth?.password,
                ),
            )
        val source = sources.find(sourceId) ?: return RefreshResult.Failed(sourceId, RefreshFailure.SOURCE_MISSING)
        return parseAndStore(source, text, fromNetwork = false)
    }

    private suspend fun parseAndStore(
        source: Source,
        text: String,
        fromNetwork: Boolean,
    ): RefreshResult {
        val parsed: ParsedPlaylist = Playlists.parse(text)
        if (parsed.isEmpty) {
            // 「抓到了 HTML 错误页」「文件是空的」都会走到这里：这不是网络问题，
            // 而是这个源当前不可用，要如实告诉用户，而不是显示「0 个频道」当成功
            sources.markRefreshed(source.id, clock(), NOTE_NO_CHANNELS)
            return RefreshResult.Failed(source.id, RefreshFailure.NO_CHANNELS)
        }

        val merged = mergeChannels(parsed.channels)
        val stats = writer.replaceForSource(source.id, merged)
        sources.markRefreshed(source.id, clock(), noteFor(merged.size, fromNetwork))

        return RefreshResult.Done(
            RefreshOutcome(
                sourceId = source.id,
                sourceName = source.name,
                format = parsed.format,
                channelCount = merged.size,
                skippedLines = parsed.skippedLines,
                stats = stats,
            ),
        )
    }

    private fun noteFor(channelCount: Int, fromNetwork: Boolean): String =
        if (fromNetwork) "$channelCount 个频道" else "$channelCount 个频道（本地文件）"

    private fun describe(failure: FetchOutcome.Failure): String =
        when (failure.kind) {
            FetchErrorKind.TIMEOUT -> NOTE_TIMEOUT
            FetchErrorKind.HTTP -> "抓取失败：${failure.detail ?: "HTTP 错误"}"
            FetchErrorKind.NETWORK -> "网络错误：${failure.detail ?: "不可达"}"
            FetchErrorKind.EMPTY -> NOTE_EMPTY
        }

    private companion object {
        const val NOTE_TIMEOUT = "超时：源没有在规定时间内响应"
        const val NOTE_EMPTY = "抓到的内容是空的"
        const val NOTE_NO_CHANNELS = "解析失败：内容不是 M3U / TXT 播放列表"
    }
}

private fun FetchErrorKind.toRefreshFailure(): RefreshFailure =
    when (this) {
        FetchErrorKind.TIMEOUT -> RefreshFailure.TIMEOUT
        FetchErrorKind.HTTP -> RefreshFailure.HTTP
        FetchErrorKind.NETWORK -> RefreshFailure.NETWORK
        FetchErrorKind.EMPTY -> RefreshFailure.EMPTY
    }
