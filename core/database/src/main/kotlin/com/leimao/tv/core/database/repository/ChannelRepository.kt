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

    /**
     * 按名称搜索频道（SEARCH-01），只搜启用的源。
     *
     * [keyword] 必须**已经归一化**（`normalizeSearchQuery`），这里的 SQL 只去空格、不处理全角半角 ——
     * 「怎么算同一个词」这件事只有一份实现，放在 core:model 里。
     */
    suspend fun search(
        keyword: String,
        limit: Int = SEARCH_LIMIT,
    ): List<ChannelWithSource>

    suspend fun firstChannelId(): Long?

    companion object {
        /** 单次搜索最多返回多少条频道：再多用户也不会翻，只是把焦点顺序拖长 */
        const val SEARCH_LIMIT = 40
    }
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

    override suspend fun search(
        keyword: String,
        limit: Int,
    ): List<ChannelWithSource> = channelDao.searchByName(keyword.escapeLike(), limit)
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
