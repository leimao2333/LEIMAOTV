package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.model.Channel

/**
 * 起播需要的全部信息：线路 + 该源配置的请求头（LIVE-06）。
 *
 * 请求头按「源」配置而不是按「频道」配置 —— 同一源下的台通常共用一套 UA / Referer。
 */
data class PlaybackConfig(
    val lines: List<String>,
    val userAgent: String? = null,
    val referer: String? = null,
    val basicUser: String? = null,
    val basicPass: String? = null,
)

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

    /** 起播配置：线路 + 源级请求头。换台时调用，全是主键查询 */
    suspend fun playbackConfig(channelId: Long): PlaybackConfig?

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

    override suspend fun playbackConfig(channelId: Long): PlaybackConfig? {
        val channel = channelDao.findById(channelId) ?: return null
        val lines = database.channelLineDao().linesFor(channelId).map { it.url }
        val source = database.sourceDao().findById(channel.sourceId)

        return PlaybackConfig(
            // 线路表为空时回落到频道自带的地址：升级安装的旧数据可能只有一列 url
            lines = lines.ifEmpty { listOf(channel.url) },
            userAgent = source?.userAgent,
            referer = source?.referer,
            basicUser = source?.basicUser,
            basicPass = source?.basicPass,
        )
    }

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
