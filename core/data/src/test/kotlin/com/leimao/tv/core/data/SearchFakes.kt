package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelRepository
import com.leimao.tv.core.database.repository.ChannelWithSource
import com.leimao.tv.core.database.repository.PlaybackConfig
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.network.vod.VodFailure
import com.leimao.tv.core.network.vod.VodFetchResult
import com.leimao.tv.core.network.vod.VodSourceAdapter
import kotlinx.coroutines.delay

// SEARCH-01/02/09 的测试替身。
//
// 与 VodFakes.kt 分开放：那边是「抓取 / 入库」链路的，这边是「搜索」链路的 ——
// 混在一个文件里，加功能时得在一堆无关替身里翻。

/** 本地频道搜索。命中表按**归一化后的关键词**配好，同时记下被搜过的词，便于断言「这个板块根本没查频道」 */
internal class FakeChannelRepository(
    private val hits: Map<String, List<ChannelWithSource>> = emptyMap(),
    /** 拼音简写搜索的候选集（模拟「启用源下的全部频道」） */
    private val allChannels: List<ChannelWithSource> = emptyList(),
) : ChannelRepository {
    val searched = mutableListOf<String>()

    override suspend fun find(id: Long): Channel? = null

    override suspend fun listFromEnabledSources(): List<Channel> = emptyList()

    override suspend fun groups(): List<String> = emptyList()

    override suspend fun linesOf(channelId: Long): List<String> = emptyList()

    override suspend fun playbackConfig(channelId: Long): PlaybackConfig? = null

    override suspend fun search(
        keyword: String,
        limit: Int,
    ): List<ChannelWithSource> {
        searched += keyword
        return hits[keyword].orEmpty().take(limit)
    }

    override suspend fun enabledWithSource(): List<ChannelWithSource> = allChannels

    override suspend fun firstChannelId(): Long? = null
}

/**
 * 远端源替身。
 *
 * [delayMs] 走虚拟时间，`runTest` 里配 30_000 也不会真等 30 秒 ——
 * 超时分支能真跑一遍，测试仍是毫秒级。
 */
internal class FakeVodSearchAdapter(
    private val delayMs: Long = 0,
    private val page: VodPage = VodPage(items = emptyList()),
    private val failure: VodFailure? = null,
) : VodSourceAdapter {
    /** 被搜了几次：空关键词不该产生任何一次 */
    var searchCalls = 0
        private set

    override suspend fun categories(): VodFetchResult<List<VodCategory>> =
        VodFetchResult.Failed(VodFailure.NOT_VOD_SOURCE)

    override suspend fun list(
        typeId: String,
        page: Int,
    ): VodFetchResult<VodPage> = VodFetchResult.Failed(VodFailure.NOT_VOD_SOURCE)

    override suspend fun detail(sourceKey: String): VodFetchResult<VodDetail?> =
        VodFetchResult.Failed(VodFailure.NOT_VOD_SOURCE)

    override suspend fun search(
        keyword: String,
        page: Int,
    ): VodFetchResult<VodPage> {
        searchCalls += 1
        if (delayMs > 0) delay(delayMs)
        return failure?.let { VodFetchResult.Failed(it) } ?: VodFetchResult.Success(this.page)
    }
}
