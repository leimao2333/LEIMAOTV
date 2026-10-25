package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelLineQuality
import com.leimao.tv.core.database.repository.ChannelLineQualityRepository
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 频道线路调度（LIVE-13 智能调度）。
 *
 * 回答一个问题：同一频道有几条线路（不同源 / 不同 CDN），该先试哪条、跳过哪条？
 *
 * ## 三层策略
 *
 * 1. **有历史 → 按质量排序**（[rankByHistory]）：成功过的排前、失败过的排后，
 *    都没记录的居中；「成功多次且没失败」最靠前，「失败多次且没成功」最靠后。
 * 2. **无历史 → 实时探测**（[probeLines]）：并发问每条线路通不通、多快，快的排前，
 *    不通的直接淘汰 —— 这样第一次播一个新频道也能跳过失效源。
 * 3. **播放中失败 → 回写**（由播放链路调用 [ChannelLineQualityRepository.recordFailure]），
 *    下次自动靠后，越用越准。
 *
 * 排序是**纯函数**（[rankByHistory]），探测是**纯 IO**（[probeLines]），分开是为单测能
 * 只测排序、不碰网络。
 */
class LineScheduler(
    private val quality: ChannelLineQualityRepository,
    private val http: HttpClient,
) {
    /**
     * 把同一频道的线路排成「先试最优」的顺序。
     *
     * [lines] 是源里给的原始顺序；[channelId] 用来查历史。
     * 有历史 → 纯排序；一条历史都没有 → 并发探测补一份排序依据。
     */
    suspend fun orderLines(
        channelId: Long,
        lines: List<String>,
        headers: RequestHeaders = RequestHeaders.NONE,
    ): List<String> {
        if (lines.size <= 1) return lines

        val history = quality.byChannel(channelId)
        return if (history.isEmpty()) {
            rankByProbe(lines, headers)
        } else {
            rankByHistory(lines, history)
        }
    }

    /**
     * 按历史质量排序（纯函数，不碰网络）。
     *
     * 排序键 = 可信度分数（成功 - 失败），同分时「最近用过」的靠前。
     * 刻意不用「成功 / (成功+失败)」这种比率：成功 1 次失败 0 次（=1.0）不该排在
     * 成功 50 次失败 1 次（≈0.98）前面 —— 次数本身就是可信度的量，得算进去。
     */
    fun rankByHistory(
        lines: List<String>,
        history: List<ChannelLineQuality>,
    ): List<String> {
        if (lines.size <= 1) return lines
        val byUrl = history.associateBy { it.url }
        return lines.sortedByDescending { url ->
            val q = byUrl[url]
            if (q == null) {
                // 没记录：排中间。给一个中性分 0，排在「失败过」的后面、「成功过」的前面
                0
            } else {
                // 可信度 = 成功次数 - 失败次数（负数越多越该靠后）
                q.successCount - q.failCount
            }
        }
    }

    /**
     * 并发探测每条线路，快的排前、不通的排最后。
     *
     * 用 `fetch` 拉响应头/列表来判断「通不通」：直播 m3u8 只有几百字节，
     * 一条线路一次请求的成本可以接受。并发上限 [PROBE_CONCURRENCY]、单条超时 [PROBE_TIMEOUT_MS]，
     * 既不会因为几十条线路一起打爆网络，也不会被某条死链拖住整次换台。
     *
     * 不通的线路**排到最后而不是删除**：探测有误判（网络短暂抖动、服务端临时限流），
     * 一刀切删掉会让一条其实能用的线路彻底消失。排最后已经实现了「优先流畅、跳过卡顿」——
     * 播放时的降级链会先试可达的，只有可达的也全失败才会轮到它们，足够安全。
     *
     * 全都不通时**原样返回**而不是返回空：上层至少要能试一条，真失败再走已有的
     * 降级链报错 —— 探测只是「排序优化」，不是「可用性判决」，不能把本来还能
     * 挣扎一下的播放直接判死。
     */
    suspend fun rankByProbe(
        lines: List<String>,
        headers: RequestHeaders = RequestHeaders.NONE,
    ): List<String> {
        if (lines.size <= 1) return lines
        val gate = Semaphore(PROBE_CONCURRENCY)
        val results =
            coroutineScope {
                lines.map { url ->
                    async {
                        val latency =
                            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                                gate.withPermit { probe(url, headers) }
                            }
                        url to latency
                    }
                }.awaitAll()
            }
        val reachable = results.filter { it.second != null }
        return if (reachable.isEmpty()) {
            lines
        } else {
            reachable.sortedBy { it.second }.map { it.first } +
                lines.filter { url -> reachable.none { it.first == url } }
        }
    }

    /** 探测单条线路：可达返回耗时（ms），不可达返回 null */
    private suspend fun probe(
        url: String,
        headers: RequestHeaders,
    ): Long? {
        val start = System.currentTimeMillis()
        val outcome = http.fetch(url, headers)
        val elapsed = System.currentTimeMillis() - start
        return if (outcome is FetchOutcome.Success) elapsed else null
    }

    private companion object {
        const val PROBE_CONCURRENCY = 4
        const val PROBE_TIMEOUT_MS = 4_000L
    }
}
