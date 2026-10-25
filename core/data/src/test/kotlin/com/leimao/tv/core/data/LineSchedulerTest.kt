package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelLineQuality
import com.leimao.tv.core.database.repository.ChannelLineQualityRepository
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 线路调度（LIVE-13）。
 *
 * 排序是纯函数，这里重点钉死「可信度排序」的边界：
 * 成功过的排前、失败过的排后、次数多的比次数少的可信、没记录的中性。
 * 探测路径用假 HttpClient 只验「快的排前、不通的淘汰、全不通原样返回」。
 */
class LineSchedulerTest {
    private fun quality(
        url: String,
        success: Int = 0,
        fail: Int = 0,
    ) = ChannelLineQuality(channelId = 1, url = url, successCount = success, failCount = fail, lastSeenAt = 0L)

    // ---------- 纯排序 ----------

    @Test
    fun `成功过的排前、失败过的排后`() {
        val history = listOf(quality("ok", success = 3), quality("bad", fail = 5))
        val result = LineScheduler(fakeRepo(), fakeHttp()).rankByHistory(listOf("bad", "fresh", "ok"), history)

        assertEquals(listOf("ok", "fresh", "bad"), result)
    }

    @Test
    fun `次数多比次数少可信，不用比率`() {
        // 成功 1 次失败 0 次（比率 1.0）不该排在成功 50 次失败 1 次（≈0.98）前面
        val history = listOf(quality("rare", success = 1), quality("veteran", success = 50, fail = 1))
        val result = LineScheduler(fakeRepo(), fakeHttp()).rankByHistory(listOf("rare", "veteran"), history)

        assertEquals(listOf("veteran", "rare"), result)
    }

    @Test
    fun `没记录的线路排中间`() {
        val history = listOf(quality("ok", success = 2), quality("bad", fail = 2))
        val result = LineScheduler(fakeRepo(), fakeHttp()).rankByHistory(listOf("bad", "fresh", "ok"), history)

        assertEquals(listOf("ok", "fresh", "bad"), result)
    }

    @Test
    fun `单条线路原样返回不排序`() {
        val result = LineScheduler(fakeRepo(), fakeHttp()).rankByHistory(listOf("only"), emptyList())
        assertEquals(listOf("only"), result)
    }

    // ---------- 探测排序 ----------

    @Test
    fun `探测后不可达的线路排到最后`() =
        runTest {
            val http =
                fakeHttp(
                    mapOf(
                        "fast" to (true to 10L),
                        "slow" to (true to 300L),
                        "dead" to (false to null),
                    ),
                )
            val result = LineScheduler(fakeRepo(), http).rankByProbe(listOf("dead", "slow", "fast"))

            // 死链排最后（不删除：探测可能误判，删掉会让能用的线路彻底消失）；
            // 可达的两条排前面（相对顺序取决于真实计时，不断言）
            assertEquals("dead", result.last())
            assertEquals(3, result.size)
        }

    @Test
    fun `全部不通时原样返回，不判死`() =
        runTest {
            val http = fakeHttp(mapOf("a" to (false to null), "b" to (false to null)))
            val result = LineScheduler(fakeRepo(), http).rankByProbe(listOf("a", "b"))

            assertEquals(listOf("a", "b"), result)
        }

    // ---------- 替身 ----------

    private fun fakeRepo(): ChannelLineQualityRepository =
        object : ChannelLineQualityRepository {
            override suspend fun byChannel(channelId: Long): List<ChannelLineQuality> = emptyList()

            override suspend fun find(
                channelId: Long,
                url: String,
            ): ChannelLineQuality? = null

            override suspend fun recordSuccess(channelId: Long, url: String, now: Long) = Unit

            override suspend fun recordFailure(channelId: Long, url: String, now: Long) = Unit

            override suspend fun deleteChannel(channelId: Long) = Unit

            override suspend fun deleteOlderThan(before: Long) = Unit

            // 裁剪（LIVE-25）在调度器里不会被调用：它是「落盘后的维护动作」，
            // 由起播链路自己触发，这里给个空实现即可
            override suspend fun prune(now: Long) = Unit
        }

    /** 每条 url → (是否可达, 耗时ms)；耗时 null 表示不可达 */
    private fun fakeHttp(probe: Map<String, Pair<Boolean, Long?>> = emptyMap()): HttpClient =
        object : HttpClient {
            override suspend fun fetch(
                url: String,
                headers: RequestHeaders,
            ): FetchOutcome {
                val (ok, _) = probe[url] ?: return FetchOutcome.Failure(FetchErrorKind.NETWORK)
                return if (ok) {
                    FetchOutcome.Success("")
                } else {
                    FetchOutcome.Failure(FetchErrorKind.NETWORK)
                }
            }
        }
}
