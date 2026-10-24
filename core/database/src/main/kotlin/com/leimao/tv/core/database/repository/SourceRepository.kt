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

    /** 把全部源（含停用）序列化成 JSON，供导出备份（SET-03） */
    suspend fun exportJson(): String

    /** 从导出的 JSON 恢复源，追加到现有列表末尾（不覆盖、不按 id 对齐） */
    suspend fun importJson(json: String): Int
}

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

    override suspend fun exportJson(): String = SourceJson.encode(dao.listAll().map { it.toSource() })

    override suspend fun importJson(json: String): Int {
        val parsed = SourceJson.decode(json)
        var count = 0
        var order = dao.maxSortOrder() + 1
        for (src in parsed) {
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
                ),
            )
            order += 1
            count += 1
        }
        return count
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
    )
