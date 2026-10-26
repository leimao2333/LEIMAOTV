package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.SourceEntity

/** 源管理页与启动判定共用的读写口（LIVE-05、SET-03）。 */
interface SourceRepository {
    suspend fun list(): List<Source>

    suspend fun enabled(): List<Source>

    suspend fun find(id: Long): Source?

    suspend fun countEnabled(): Int

    /** 只数直播源。启动落点判定用它，影视源不算「有台可播」（VOD-03） */
    suspend fun countEnabledLive(): Int

    suspend fun add(source: NewSource): Long

    suspend fun delete(id: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 刷新结束后记一笔：成功写「上次更新时间」，失败写原因（源管理页直接展示） */
    suspend fun markRefreshed(id: Long, at: Long, note: String?)

    /** 上移/下移（SET-03）。direction = -1 上移、+1 下移；边界处不动作 */
    suspend fun move(id: Long, direction: Int)

    /**
     * 每条源在本地有多少条目：直播源 = 频道数，影视源 = 影片数（SET-09 源卡片）。
     *
     * 没有条目的源不出现在 map 里（而不是给 0）—— 「刚加的源还没刷过」与
     * 「刷过但一条没抓到」在卡片上是两种文案，0 会把它们混成一种。
     */
    suspend fun entryCounts(): Map<Long, Int>

    /** 把全部源（含停用）序列化成 JSON，供导出备份（SET-03） */
    suspend fun exportJson(): String

    /**
     * 从导出的 JSON 恢复源，追加到现有列表末尾（不覆盖、不按 id 对齐）。
     *
     * 已存在同地址的源会被跳过（[SourceImportSummary.skipped]），所以反复导入同一份
     * 备份不会叠加。返回新源的 id 是为了让调用方**导入后立刻刷一遍** ——
     * 不刷的话 7 条源全是「还没有频道」，用户还得逐条点刷新。
     */
    suspend fun importJson(json: String): SourceImportSummary
}

/** 一次备份导入的结果（SET-03）：进来几个、跳过几个、新增的直播源是哪几个 */
data class SourceImportSummary(
    val added: Int,
    val skipped: Int,
    /** 新增的直播源 id。影视源不在其中：它走另一条刷新链路（VOD-03） */
    val addedLiveIds: List<Long> = emptyList(),
)

class RoomSourceRepository(
    private val database: LeimaoDatabase,
) : SourceRepository {
    private val dao get() = database.sourceDao()

    override suspend fun list(): List<Source> = dao.listAll().map { it.toSource() }

    override suspend fun enabled(): List<Source> = dao.listEnabled().map { it.toSource() }

    override suspend fun find(id: Long): Source? = dao.findById(id)?.toSource()

    override suspend fun countEnabled(): Int = dao.countEnabled()

    override suspend fun countEnabledLive(): Int = dao.countEnabledLive()

    override suspend fun add(source: NewSource): Long {
        val nextOrder = dao.maxSortOrder() + 1
        return dao.insert(
            SourceEntity(
                type = source.type,
                name = source.name,
                url = source.url,
                userAgent = source.userAgent?.takeIf { it.isNotBlank() },
                referer = source.referer?.takeIf { it.isNotBlank() },
                basicUser = source.basicUser?.takeIf { it.isNotBlank() },
                basicPass = source.basicPass?.takeIf { it.isNotBlank() },
                enabled = true,
                sortOrder = nextOrder,
                format = source.format,
            ),
        )
    }

    /**
     * 删源时连带清掉它的影视条目（VOD-03）。
     *
     * 不清的话，首页会留着点进去却拉不到详情的残留卡片 —— 那些只是缓存，
     * 删干净才符合「删了就没了」的直觉（收藏 / 历史属于用户数据，另有级联规则）。
     */
    override suspend fun delete(id: Long) {
        dao.deleteById(id)
        database.mediaDao().clearSource(id)
        // 进度没有外键（见 VodProgressEntity 的注释），源没了就得显式清：
        // 否则「继续观看」里会留一条点进去必然失败的记录
        database.vodProgressDao().clearSource(id)
        // 收藏同理（VOD-07）：源没了，收藏的那个条目也就点不进去了。
        // 这里与「删源不动直播收藏」不一样 —— 直播收藏靠外键级联，语义一致
        database.vodFavoriteDao().clearSource(id)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun markRefreshed(id: Long, at: Long, note: String?) = dao.markRefreshed(id, at, note)

    override suspend fun move(id: Long, direction: Int) {
        val ordered = dao.listAll()
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = index + direction
        if (target < 0 || target >= ordered.size) return
        val a = ordered[index]
        val b = ordered[target]
        dao.swapSortOrder(a.id, a.sortOrder, b.id, b.sortOrder)
    }

    override suspend fun entryCounts(): Map<Long, Int> {
        val channels = database.channelDao().countBySource().associate { it.sourceId to it.total }
        val media = database.mediaDao().countBySource().associate { it.sourceId to it.total }
        return (channels.keys + media.keys).associateWith { (channels[it] ?: 0) + (media[it] ?: 0) }
    }

    override suspend fun exportJson(): String = SourceJson.encode(dao.listAll().map { it.toSource() })

    override suspend fun importJson(json: String): SourceImportSummary {
        val parsed = SourceJson.decode(json)
        val (fresh, skipped) =
            SourceJson.filterNew(
                parsed = parsed,
                existingUrls = dao.listAll().mapTo(mutableSetOf()) { it.url },
            )

        var order = dao.maxSortOrder() + 1
        val addedLiveIds = mutableListOf<Long>()
        for (src in fresh) {
            val id =
                dao.insert(
                    SourceEntity(
                        type = src.type,
                        name = src.name,
                        url = src.url,
                        userAgent = src.userAgent?.takeIf { it.isNotBlank() },
                        referer = src.referer?.takeIf { it.isNotBlank() },
                        basicUser = src.basicUser?.takeIf { it.isNotBlank() },
                        basicPass = src.basicPass?.takeIf { it.isNotBlank() },
                        enabled = src.enabled,
                        sortOrder = order,
                        format = src.format,
                    ),
                )
            order += 1
            // 停用的源不刷：它多半是「已知不可用、先留着备查」的那一条，刷它只是白等一次超时
            if (src.type == SourceType.LIVE && src.enabled) addedLiveIds += id
        }
        return SourceImportSummary(added = fresh.size, skipped = skipped, addedLiveIds = addedLiveIds)
    }
}

private fun SourceEntity.toSource(): Source =
    Source(
        id = id,
        type = type,
        name = name,
        url = url,
        enabled = enabled,
        updatedAt = updatedAt,
        lastRefreshNote = lastRefreshNote,
        userAgent = userAgent,
        referer = referer,
        basicUser = basicUser,
        basicPass = basicPass,
        sortOrder = sortOrder,
        format = format,
    )
