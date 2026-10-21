package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.SourceEntity

/** 源管理页与启动判定共用的读写口（LIVE-05、SET-03）。 */
interface SourceRepository {
    suspend fun list(): List<Source>

    suspend fun enabled(): List<Source>

    suspend fun find(id: Long): Source?

    suspend fun countEnabled(): Int

    suspend fun add(source: NewSource): Long

    suspend fun delete(id: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 刷新结束后记一笔：成功写「上次更新时间」，失败写原因（源管理页直接展示） */
    suspend fun markRefreshed(id: Long, at: Long, note: String?)
}

class RoomSourceRepository(
    private val database: LeimaoDatabase,
) : SourceRepository {
    private val dao get() = database.sourceDao()

    override suspend fun list(): List<Source> = dao.listAll().map { it.toSource() }

    override suspend fun enabled(): List<Source> = dao.listEnabled().map { it.toSource() }

    override suspend fun find(id: Long): Source? = dao.findById(id)?.toSource()

    override suspend fun countEnabled(): Int = dao.countEnabled()

    override suspend fun add(source: NewSource): Long =
        dao.insert(
            SourceEntity(
                type = source.type,
                name = source.name,
                url = source.url,
                userAgent = source.userAgent?.takeIf { it.isNotBlank() },
                referer = source.referer?.takeIf { it.isNotBlank() },
                basicUser = source.basicUser?.takeIf { it.isNotBlank() },
                basicPass = source.basicPass?.takeIf { it.isNotBlank() },
                enabled = true,
            ),
        )

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun markRefreshed(id: Long, at: Long, note: String?) = dao.markRefreshed(id, at, note)
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
    )
