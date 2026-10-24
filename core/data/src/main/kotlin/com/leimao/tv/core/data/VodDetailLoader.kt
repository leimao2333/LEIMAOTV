package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.vod.VodAdapters
import com.leimao.tv.core.network.vod.VodFailure
import com.leimao.tv.core.network.vod.VodFetchResult
import com.leimao.tv.core.network.vod.VodSourceAdapter
import com.leimao.tv.core.network.vod.VodSourceRef

/**
 * 详情加载的结果。
 *
 * 四种情况在 UI 上是**四种说法**，所以这里不能压成「有 / 没有」两态：
 * 源删了该去源管理，条目下架了该返回首页，网络挂了该重试 —— 给错按钮比不给更糟。
 */
sealed interface VodDetailResult {
    /**
     * [headers] 是这个源要求的 UA / Referer（VOD-05）。
     *
     * 跟着结果一起带出来，是为了让播放页拿到的就是**同一份**请求头 ——
     * 详情能拉到、播放器却拉不到，绝大多数时候就是播放请求少了 `Referer`。
     */
    data class Loaded(
        val detail: VodDetail,
        val headers: Map<String, String> = emptyMap(),
    ) : VodDetailResult

    /** 源还在，但它自己也查不到这条（下架 / 改 id）。不是错误，是内容没了 */
    data object Gone : VodDetailResult

    /** 源不存在或已停用：详情页没法自愈，只能引导用户去源管理 */
    data object NoSource : VodDetailResult

    data class Failed(
        val reason: VodFailure,
        /** 只放对用户有意义的信息，不放完整地址（RULES §2.5） */
        val detail: String? = null,
    ) : VodDetailResult
}

/**
 * 影视详情的数据编排（VOD-04）。
 *
 * 只做一件事：`(sourceId, sourceKey)` → 找到源 → 造适配器 → 拉详情 → 翻译结果。
 * 与 [VodCatalogRefresher] 的分工是「一条」和「一批」：首页要的是广度，
 * 详情要的是一条的深度，两者的失败处理也不同（首页一条失败可以跳过，详情失败就是整页失败）。
 *
 * **不落库**：详情带剧集列表，一集一条，几十上百条；首页快照只需要摘要。
 * 剧集要等 VOD-05 连播时才有存下来的必要（那时它还要承载进度），现在存反而是负担。
 */
class VodDetailLoader(
    private val sources: SourceRepository,
    private val http: HttpClient,
    private val adapterFactory: (VodSourceRef) -> VodSourceAdapter = { VodAdapters.forSource(http, it) },
) {
    suspend fun load(
        sourceId: Long,
        sourceKey: String,
    ): VodDetailResult {
        val source = sources.find(sourceId)
        if (source == null || !source.enabled || source.type != SourceType.VOD) {
            return VodDetailResult.NoSource
        }

        val headers = source.toRequestHeaders()
        val adapter =
            adapterFactory(
                VodSourceRef(id = source.id, baseUrl = source.url, headers = headers),
            )

        return when (val result = adapter.detail(sourceKey)) {
            is VodFetchResult.Failed ->
                VodDetailResult.Failed(reason = result.reason, detail = result.detail)

            is VodFetchResult.Success ->
                // Success(null) 是「源说查无此片」，与抓取失败严格区分（VOD-01 的约定）
                result.value?.let {
                    VodDetailResult.Loaded(it, headers.toPlaybackHeaders())
                } ?: VodDetailResult.Gone
        }
    }
}
