package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 订阅源健康检查（LIVE-09）。
 *
 * 与「刷新」分开是两回事：刷新要拉全文、解析、落库，一次几十秒；
 * 健康检查只问「这条源现在通不通」，用来回答「频道列表是旧的，是该刷新还是该换源」。
 * 所以探测只发一次请求、拿到响应就算通 —— 内容对不对是刷新的事，这里不管。
 *
 * 探测粒度是**源级**而不是频道级：一条源动辄几千个频道，逐个频道发请求在电视上
 * 是把用户的带宽和盒子的内存拿去换一份没人看得完的报告。源是「用户能操作的最小单位」，
 * 报到源级，用户就已经知道该刷哪条、停哪条了。
 */
class SourceHealthChecker(
    private val sources: SourceRepository,
    private val http: HttpClient,
) {
    data class Health(
        val sourceId: Long,
        val sourceName: String,
        val ok: Boolean,
        /** 失败时的原因（「网络连接失败」/「HTTP 404」）；成功时为 null */
        val detail: String?,
        /** 请求耗时（毫秒）。用户用它是分辨「能通但很慢」的唯一线索 */
        val latencyMs: Long,
    )

    suspend fun checkAll(): List<Health> =
        coroutineScope {
            sources.enabled()
                .map { source -> async { check(source) } }
                .awaitAll()
        }

    suspend fun check(source: Source): Health {
        val start = System.currentTimeMillis()
        val outcome = http.fetch(source.url, source.toRequestHeaders())
        val latency = System.currentTimeMillis() - start
        return when (outcome) {
            is FetchOutcome.Success ->
                Health(
                    sourceId = source.id,
                    sourceName = source.name,
                    ok = true,
                    detail = null,
                    latencyMs = latency,
                )

            is FetchOutcome.Failure ->
                Health(
                    sourceId = source.id,
                    sourceName = source.name,
                    ok = false,
                    detail = describeFailure(outcome),
                    latencyMs = latency,
                )
        }
    }

    /** 一条探测结果 → 一句人话。与源管理页的 describe(result) 同一套口径 */
    fun describe(health: Health): String =
        if (health.ok) {
            "「${health.sourceName}」可用 · ${health.latencyMs}ms"
        } else {
            "「${health.sourceName}」连不上（${health.detail ?: "未知原因"}）"
        }

    private fun describeFailure(outcome: FetchOutcome.Failure): String =
        when (outcome.kind) {
            com.leimao.tv.core.network.FetchErrorKind.NETWORK -> "网络连接失败"
            com.leimao.tv.core.network.FetchErrorKind.TIMEOUT -> "连接超时"
            com.leimao.tv.core.network.FetchErrorKind.HTTP -> outcome.detail ?: "源返回了错误"
            com.leimao.tv.core.network.FetchErrorKind.EMPTY -> "源返回了空内容"
        }
}
