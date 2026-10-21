package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.model.Channel

/**
 * 频道读取。放在 :core:database 而不是 :app，是为了让「实体怎么映射成模型」这件事
 * 只有一处实现 —— :app 只做组装，不写业务映射（RULES §5.5）。
 */
interface ChannelRepository {
    suspend fun find(id: Long): Channel?
}

class RoomChannelRepository(
    private val database: LeimaoDatabase,
) : ChannelRepository {
    override suspend fun find(id: Long): Channel? =
        database.channelDao().findById(id)?.toModel()
}

private fun ChannelEntity.toModel(): Channel =
    Channel(
        id = id,
        name = name,
        groupName = groupName,
        logoUrl = logo,
        streamUrl = url,
        number = number,
    )
