package com.leimao.tv.core.database.repository

import androidx.room.Embedded
import com.leimao.tv.core.model.Channel

/**
 * 订阅源在业务层的形态。
 *
 * 为什么不直接把 `SourceEntity` 交出去：实体带着数据库细节（自增 id、列名、migration 顺序），
 * UI 拿到它以后很容易直接拼 SQL 或依赖某列的存在。业务层只该看到「一个源有哪些可配项」。
 */
data class Source(
    val id: Long,
    val name: String,
    val url: String,
    val type: String = SourceType.LIVE,
    val enabled: Boolean = true,
    val updatedAt: Long = 0,
    val lastRefreshNote: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val basicUser: String? = null,
    val basicPass: String? = null,
    val sortOrder: Int = 0,
) {
    val hasCustomHeaders: Boolean
        get() = !userAgent.isNullOrBlank() || !referer.isNullOrBlank() || !basicUser.isNullOrBlank()
}

/** 新增源时的入参（还没有 id 与时间戳，由 [SourceRepository.add] 补）。 */
data class NewSource(
    val type: String = SourceType.LIVE,
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val referer: String? = null,
    val basicUser: String? = null,
    val basicPass: String? = null,
)

/**
 * 搜索结果里的一条频道：频道本体 + 它所属的源名（SEARCH-01）。
 *
 * 为什么要把源名一起带出来：同名频道在多个源里都存在（「CCTV-1」几乎每个源都有），
 * 搜索结果只显示频道名的话，用户根本分不清点的是哪个源的哪条线路。
 */
data class ChannelWithSource(
    @Embedded val channel: Channel,
    val sourceId: Long,
    val sourceName: String,
)

/** 源的业务类型。M1 只处理直播；影视源在 M2 复用同一张表（架构设计 §5）。 */
object SourceType {
    const val LIVE = "LIVE"
    const val VOD = "VOD"
}

/**
 * 把用户输入里的 LIKE 通配符转义掉（`\` `%` `_`）。
 *
 * 不转义的话，搜「100%」会变成「以 100 开头」，搜「a_b」会变成「a 加任意一个字符」——
 * 用户看到的不是报错而是「搜什么都出一堆不相干的东西」，最难排查。
 * 两边的 SQL 都写了 `ESCAPE '\'`，这三行与它们是配套的，改一处必须改另一处。
 */
internal fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
