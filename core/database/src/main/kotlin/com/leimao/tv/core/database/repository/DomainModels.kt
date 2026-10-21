package com.leimao.tv.core.database.repository

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

/** 源的业务类型。M1 只处理直播；影视源在 M2 复用同一张表（架构设计 §5）。 */
object SourceType {
    const val LIVE = "LIVE"
    const val VOD = "VOD"
}
