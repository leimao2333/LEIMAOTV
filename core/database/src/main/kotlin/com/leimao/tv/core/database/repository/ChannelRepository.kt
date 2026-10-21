package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.model.Channel

/**
 * 频道读取。放在 :core:database 而不是 :app，是为了让「实体怎么映射成模型」这件事
 * 只有一处实现 —— :app 只做组装，不写业务映射（RULES §5.5）。
 *
 * 写入（导入 / 刷新）不在这里：那是「解析 + 合并 + 落库」的编排，属于 :core:data。
 */
interface ChannelRepository {
    suspend fun find(id: Long): Channel?

    /** 列表页与换台用的全量频道，只含启用的源（LIVE-10） */
    suspend fun listFromEnabledSources(): List<Channel>

    /** 分组名列表，按名称排序（左侧分组栏） */
    suspend fun groups(): List<String>

    /** 某条频道的全部线路，按优先级排序（LIVE-13 换线路） */
    suspend fun linesOf(channelId: Long): List<String>

    suspend fun firstChannelId(): Long?
}

class RoomChannelRepository(
    private val database: LeimaoDatabase,
) : ChannelRepository {
    private val channelDao get() = database.channelDao()

    override suspend fun find(id: Long): Channel? = channelDao.findById(id)?.toModel()

    override suspend fun listFromEnabledSources(): List<Channel> =
        channelDao.listFromEnabledSources().map { it.toModel() }

    override suspend fun groups(): List<String> = channelDao.listGroups()

    override suspend fun linesOf(channelId: Long): List<String> =
        database.channelLineDao().linesFor(channelId).map { it.url }

    override suspend fun firstChannelId(): Long? = channelDao.firstChannelId()
}

internal fun ChannelEntity.toModel(): Channel =
    Channel(
        id = id,
        name = name,
        groupName = groupName,
        logoUrl = logo,
        streamUrl = url,
        number = number,
    )
