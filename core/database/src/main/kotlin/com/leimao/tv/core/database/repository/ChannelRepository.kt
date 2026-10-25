package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.catchup.Catchup
import com.leimao.tv.core.model.catchup.CatchupMode

/**
 * 起播需要的全部信息：线路 + 该源配置的请求头（LIVE-06）+ 回看能力（LIVE-24）。
 *
 * 请求头按「源」配置而不是按「频道」配置 —— 同一源下的台通常共用一套 UA / Referer。
 * 回看能力挂在这里，是因为它和线路、请求头一样都是「起播那一刻才知道要不要用」的东西：
 * 同一次主键查询就能取到，不必让列表里的每个频道都背着它。
 */
data class PlaybackConfig(
    val lines: List<String>,
    val userAgent: String? = null,
    val referer: String? = null,
    val basicUser: String? = null,
    val basicPass: String? = null,
    /** null = 这个台不支持回看，播放页不出现入口 */
    val catchup: Catchup? = null,
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

    /**
     * 启用源下的全部频道（带源名）。拼音简写搜索要在内存里比对名字，
     * 需要的是「全部候选 + 源名」，而不是 SQL 已经筛过一轮的结果（SEARCH-06）。
     */
    suspend fun enabledWithSource(): List<ChannelWithSource>

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
            catchup = channel.toCatchup(),
        )
    }

    override suspend fun firstChannelId(): Long? = channelDao.firstChannelId()

    override suspend fun search(
        keyword: String,
        limit: Int,
    ): List<ChannelWithSource> = channelDao.searchByName(keyword.escapeLike(), limit)

    override suspend fun enabledWithSource(): List<ChannelWithSource> = channelDao.listEnabledWithSource()
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

/**
 * 三列 → [Catchup]（LIVE-24）。
 *
 * `catchupMode` 存的是枚举名：多存一个映射函数而不是存序号，
 * 是为了让库里能被人眼看懂，也让「枚举重排」不会悄悄改变老数据的含义。
 * 认不出的名字（被降级安装后写入的、或手工改过的库）一律当「不支持」。
 */
internal fun ChannelEntity.toCatchup(): Catchup? {
    val mode =
        catchupMode
            ?.let { name -> CatchupMode.entries.firstOrNull { it.name == name } }
            ?: return null
    val result =
        Catchup(
            mode = mode,
            template = catchupTemplate,
            days = if (catchupDays > 0) catchupDays else Catchup.DEFAULT_DAYS,
        )
    return result.takeIf { it.isUsable }
}

/** 模型 → 三列。`Catchup` 为 null 时三列一起清空，不留半条能力在库里。 */
internal fun Catchup?.toColumns(): Triple<String?, String?, Int> =
    if (this == null) {
        Triple(null, null, 0)
    } else {
        Triple(mode.name, template, days)
    }
