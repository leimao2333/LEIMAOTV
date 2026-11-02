package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.dao.EpgDao
import com.leimao.tv.core.database.dao.EpgSourceDao
import com.leimao.tv.core.database.entity.EpgChannelEntity
import com.leimao.tv.core.database.entity.EpgProgrammeEntity
import com.leimao.tv.core.database.entity.EpgSourceEntity
import com.leimao.tv.core.model.epg.EpgGuide
import com.leimao.tv.core.model.epg.EpgProgramme
import com.leimao.tv.core.model.epg.NamedProgramme
import com.leimao.tv.core.model.epg.currentAt
import com.leimao.tv.core.model.epg.latestPerChannel
import com.leimao.tv.core.model.epg.nextAfter
import com.leimao.tv.core.model.epg.normalizeEpgName

/** 导入完成后的统计，用来在源管理页诚实地报告「到底进来了多少」 */
data class EpgImportSummary(
    val channelCount: Int,
    val programmeCount: Int,
    val latestStartAt: Long?,
)

/** 一个频道的「正在播 / 下一档」。两者都可能为空：节目单没覆盖这个台时不要编造 */
data class NowNext(
    val current: EpgProgramme?,
    val next: EpgProgramme?,
) {
    val isEmpty: Boolean get() = current == null && next == null
}

/**
 * 节目单仓库（LIVE-20）。
 *
 * 职责边界很窄：存「合并好的一份节目单」，再按频道名给出当前/下一档。
 * 解析与合并都在 [com.leimao.tv.core.data] 里做 —— 数据库模块不该知道 XMLTV 长什么样。
 */
class EpgRepository(
    private val dao: EpgDao,
    private val sourceDao: EpgSourceDao,
) {
    suspend fun replaceAll(guide: EpgGuide) {
        dao.replaceAll(
            channels =
                guide.channels.map {
                    EpgChannelEntity(id = it.ids.first(), name = it.name, normalizedName = it.normalizedName)
                },
            // 合并后每个「归一名字」对应一份节目，写库时把 id 映射成主 id 即可：
            // 导入阶段已经把多个 id 的节目并到一起了，这里不需要再按 id 展开
            programmes =
                guide.programmes.flatMap { (normalizedName, list) ->
                    val channelId = guide.channels.firstOrNull { it.normalizedName == normalizedName }?.ids?.first()
                    if (channelId == null) emptyList() else list.map { it.toEntity(channelId) }
                },
        )
    }

    suspend fun summary(): EpgImportSummary =
        EpgImportSummary(
            channelCount = dao.channelCount(),
            programmeCount = dao.programmeCount(),
            latestStartAt = dao.latestStartAt(),
        )

    /**
     * 某个频道从 [nowMillis] 起的节目（默认给 12 小时窗口）。
     *
     * 窗口取「现在往前 2 小时 ~ 往后 12 小时」：往前留一点是为了「刚过点还在播」的那档，
     * 往后 12 小时足够覆盖「下一档」与今晚的节目，也不会把整份节目单读进内存。
     */
    suspend fun programmesFor(
        channelName: String,
        nowMillis: Long,
        lookBackMillis: Long = 2 * 3_600_000L,
        lookAheadMillis: Long = 12 * 3_600_000L,
    ): List<EpgProgramme> {
        val normalized = normalizeEpgName(channelName)
        if (normalized.isEmpty()) return emptyList()

        val ids = dao.channelsByNormalizedName(normalized).map { it.id }
        if (ids.isEmpty()) return emptyList()

        // 归一名字可能对应多个 id（同类合并），一次查出后按时间合并
        return dao
            .programmesInWindow(listOf(normalized), nowMillis - lookBackMillis, nowMillis + lookAheadMillis)
            .map { it.toModel() }
            .sortedBy { it.startAt }
            .distinctBy { it.startAt to it.title }
    }

    suspend fun nowNext(channelName: String, nowMillis: Long): NowNext {
        val list = programmesFor(channelName, nowMillis)
        return NowNext(current = list.currentAt(nowMillis), next = list.nextAfter(nowMillis))
    }

    /**
     * 一批频道此刻在播什么，key = [normalizeEpgName] 后的频道名（LIVE-21）。
     *
     * 频道列表一屏就是几百上千个台，逐个台调 [nowNext] 等于几百次数据库往返，
     * 遥控器翻一页就要卡一下。这里一次问完，在内存里按台归堆。
     *
     * 分批是因为 SQLite 的绑定参数上限（旧版本 999）：5000 个台的名字一次塞进 `IN`，
     * Room 会直接抛 `too many SQL variables`。
     *
     * 没有任何节目单（没导入 EPG）时返回空 map —— 调用方据此**不画**节目名，
     * 而不是画一行「无节目信息」。列表里满是「无节目信息」比空白更吵。
     */
    suspend fun nowPlaying(
        channelNames: Collection<String>,
        nowMillis: Long,
    ): Map<String, EpgProgramme> {
        val normalized =
            channelNames
                .asSequence()
                .map { normalizeEpgName(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
        if (normalized.isEmpty()) return emptyMap()

        val rows = mutableListOf<NamedProgramme>()
        normalized.chunked(EPG_NAME_BATCH).forEach { batch ->
            rows += dao.liveProgrammes(batch, nowMillis)
        }
        return latestPerChannel(rows)
    }

    private fun EpgProgramme.toEntity(channelId: String) =
        EpgProgrammeEntity(
            epgChannelId = channelId,
            startAt = startAt,
            endAt = endAt,
            title = title,
            description = description,
        )

    private fun EpgProgrammeEntity.toModel() =
        EpgProgramme(
            channelId = epgChannelId,
            startAt = startAt,
            endAt = endAt,
            title = title,
            description = description,
        )

    // ---- 订阅地址（LIVE-23）----

    /** 记录（或更新）节目单订阅地址。返回落库后的源。 */
    suspend fun saveSource(url: String, updatedAt: Long): EpgSourceEntity {
        val existing = sourceDao.first()
        val entity =
            EpgSourceEntity(
                id = existing?.id ?: 0,
                url = url,
                updatedAt = updatedAt,
                lastRefreshNote = existing?.lastRefreshNote,
            )
        sourceDao.upsert(entity)
        return sourceDao.first() ?: entity
    }

    /** 当前订阅地址，没有返回 null。 */
    suspend fun sourceUrl(): String? = sourceDao.first()?.url

    /** 刷新结果落库：更新「上次更新时间」与失败原因。 */
    suspend fun markRefreshed(url: String, updatedAt: Long, note: String?) {
        val existing = sourceDao.first()
        sourceDao.upsert(
            EpgSourceEntity(
                id = existing?.id ?: 0,
                url = url,
                updatedAt = updatedAt,
                lastRefreshNote = note,
            ),
        )
    }

    private companion object {
        /**
         * 一次 `IN (...)` 里塞多少个频道名。
         *
         * SQLite 的绑定参数上限是 999（3.32 起才提到 32766，但 Android 各版本带的版本不一致），
         * 一个频道名算一个参数，取 400 留足余量：5000 个台分成 13 批，比一次查询多几次往返，
         * 但不会在老机器上抛 `too many SQL variables`。
         */
        const val EPG_NAME_BATCH = 400
    }
}
