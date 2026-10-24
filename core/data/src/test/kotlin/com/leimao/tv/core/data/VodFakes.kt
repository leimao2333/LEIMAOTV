package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.MediaRepository
import com.leimao.tv.core.database.repository.NewSource
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders

// 数据层单测共用的替身。
//
// 从 `VodCatalogRefresherTest` 里提出来是因为 VOD-04 的详情加载也要用同一份 ——
// 两个测试各写一个 `FakeSourceRepository`，加一个新接口方法时就得改两处，
// 而漏改的那一处会在运行时才炸（`NoSuchMethodError` 比编译错误难查得多）。

/** 单测一律不联网：真有代码走到网络，断言会以「失败原因 = NETWORK」的形式暴露出来 */
internal object FakeHttp : HttpClient {
    override suspend fun fetch(
        url: String,
        headers: RequestHeaders,
    ): FetchOutcome = FetchOutcome.Failure(FetchErrorKind.NETWORK, "单测不联网")
}

internal class FakeSourceRepository(
    private val data: List<Source>,
) : SourceRepository {
    override suspend fun list(): List<Source> = data

    override suspend fun enabled(): List<Source> = data.filter { it.enabled }

    override suspend fun find(id: Long): Source? = data.firstOrNull { it.id == id }

    override suspend fun countEnabled(): Int = data.count { it.enabled }

    override suspend fun countEnabledLive(): Int = data.count { it.enabled && it.type == SourceType.LIVE }

    override suspend fun add(source: NewSource): Long = 0

    override suspend fun delete(id: Long) = Unit

    override suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    ) = Unit

    override suspend fun markRefreshed(
        id: Long,
        at: Long,
        note: String?,
    ) = Unit

    override suspend fun move(
        id: Long,
        direction: Int,
    ) = Unit

    override suspend fun exportJson(): String = ""

    override suspend fun importJson(json: String): Int = 0
}

internal class FakeMediaRepository : MediaRepository {
    /** 每次 replace 的 (sourceId, pages, now)，便于断言「哪些类型落库了」 */
    val replaced = mutableListOf<Triple<Long, Map<ContentType, List<VodSummary>>, Long>>()

    /** 详情页进场时读的本地快照，按 `(sourceId, sourceKey)` 摆好 */
    val stored = mutableMapOf<Pair<Long, String>, VodSummary>()

    override suspend fun replace(
        sourceId: Long,
        pages: Map<ContentType, List<VodSummary>>,
        now: Long,
    ) {
        replaced += Triple(sourceId, pages, now)
    }

    override suspend fun recent(limit: Int): List<VodSummary> = emptyList()

    override suspend fun byType(
        type: ContentType,
        year: Int?,
        area: String?,
        tag: String?,
        limit: Int,
    ): List<VodSummary> = emptyList()

    override suspend fun bySource(
        sourceId: Long,
        limit: Int,
    ): List<VodSummary> = emptyList()

    override suspend fun yearsOf(type: ContentType): List<Int> = emptyList()

    override suspend fun areasOf(type: ContentType): List<String> = emptyList()

    override suspend fun tagsOf(type: ContentType): List<String> = emptyList()

    override suspend fun lastFetchedAt(): Long? = null

    override suspend fun find(
        sourceId: Long,
        sourceKey: String,
    ): VodSummary? = stored[sourceId to sourceKey]

    override suspend fun clearSource(sourceId: Long) = Unit

    override suspend fun isEmpty(): Boolean = true
}
