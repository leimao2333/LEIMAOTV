package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.MediaEntity
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodSummary

/**
 * 影视条目的本地缓存（VOD-03）。
 *
 * 出入都走 [VodSummary]：首页、搜索、详情页吃的是同一种摘要，没必要在中间再多一层
 * 只差字段名的模型。表里的 `type` 存的是枚举名，映射不回来（脏数据 / 老版本）时
 * 直接跳过这条 —— 与其把「未知类型」塞进列表，不如让它消失。
 */
interface MediaRepository {
    /** 一次替换某源若干类型的快照（只传本次**成功抓到**的类型，详见 [MediaDao.replaceTypes]） */
    suspend fun replace(
        sourceId: Long,
        pages: Map<ContentType, List<VodSummary>>,
        now: Long,
    )

    /** 全库最近更新 */
    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<VodSummary>

    /** 按类型查，年份 / 地区为 null 表示不限 */
    suspend fun byType(
        type: ContentType,
        year: Int? = null,
        area: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<VodSummary>

    /** 某个源的一行 */
    suspend fun bySource(
        sourceId: Long,
        limit: Int = DEFAULT_LIMIT,
    ): List<VodSummary>

    /** 该类型下出现过的年份（倒序），做筛选项 */
    suspend fun yearsOf(type: ContentType): List<Int>

    suspend fun areasOf(type: ContentType): List<String>

    /** 上次入库时间，用于「缓存多久没刷了」的判断 */
    suspend fun lastFetchedAt(): Long?

    suspend fun clearSource(sourceId: Long)

    suspend fun isEmpty(): Boolean

    companion object {
        /** 每行给多少张卡。1080p 一行放 5～6 张，30 条够往下翻好几屏，也不至于一屏拉太多图 */
        const val DEFAULT_LIMIT = 30
    }
}

class RoomMediaRepository(
    private val database: LeimaoDatabase,
) : MediaRepository {
    private val dao get() = database.mediaDao()

    override suspend fun replace(
        sourceId: Long,
        pages: Map<ContentType, List<VodSummary>>,
        now: Long,
    ) {
        val types = pages.keys.filter { it != ContentType.LIVE_CHANNEL }.map { it.name }
        if (types.isEmpty()) return
        val items = pages.flatMap { (type, list) -> list.map { it.toEntity(now, type) } }
        dao.replaceTypes(sourceId = sourceId, types = types, items = items)
    }

    override suspend fun recent(limit: Int): List<VodSummary> = dao.recent(limit).toSummaries()

    override suspend fun byType(
        type: ContentType,
        year: Int?,
        area: String?,
        limit: Int,
    ): List<VodSummary> = dao.filtered(type.name, year, area, limit).toSummaries()

    override suspend fun bySource(
        sourceId: Long,
        limit: Int,
    ): List<VodSummary> = dao.bySource(sourceId, limit).toSummaries()

    override suspend fun yearsOf(type: ContentType): List<Int> = dao.yearsOf(type.name)

    override suspend fun areasOf(type: ContentType): List<String> = dao.areasOf(type.name)

    override suspend fun lastFetchedAt(): Long? = dao.lastFetchedAt()

    override suspend fun clearSource(sourceId: Long) = dao.clearSource(sourceId)

    override suspend fun isEmpty(): Boolean = dao.count() == 0
}

/**
 * 摘要 → 表行。
 *
 * [VodSummary.updatedAt] 为空时用入库时间顶上：源没给 `vod_time` 的条目至少还能按
 * 「这次抓到的先后」排出个顺序，而不是全部塌到 0 挤在一起。
 */
private fun VodSummary.toEntity(
    now: Long,
    type: ContentType,
): MediaEntity =
    MediaEntity(
        sourceId = sourceId,
        sourceKey = sourceKey,
        type = type.name,
        title = name,
        poster = pic,
        year = year,
        area = area,
        remarks = remarks,
        updatedAt = updatedAt ?: now,
        fetchedAt = now,
    )

private fun List<MediaEntity>.toSummaries(): List<VodSummary> = mapNotNull { it.toSummary() }

private fun MediaEntity.toSummary(): VodSummary? {
    val contentType = runCatching { ContentType.valueOf(type) }.getOrNull() ?: return null
    if (contentType == ContentType.LIVE_CHANNEL) return null
    return VodSummary(
        sourceId = sourceId,
        sourceKey = sourceKey,
        name = title,
        type = contentType,
        pic = poster,
        remarks = remarks,
        year = year,
        area = area,
        updatedAt = updatedAt,
    )
}
