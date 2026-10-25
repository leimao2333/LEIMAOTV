package com.leimao.tv.feature.settings

import com.leimao.tv.core.data.SourceHealthChecker
import com.leimao.tv.core.data.SourceHealthStore
import com.leimao.tv.core.database.repository.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 源卡片上的三句文案（SET-09）。
 *
 * 这三句是「用户判断该不该动这条源」的全部依据，所以钉住的是**信息量**而不只是格式：
 * 失效要带原因、正常要带延迟、未测要显式写出来（空白会被当成「没问题」）。
 */
class SourceCardTextTest {
    private fun entry(
        ok: Boolean,
        latencyMs: Long,
        detail: String? = null,
        at: Long = 0,
    ) = SourceHealthStore.Entry(
        health =
            SourceHealthChecker.Health(
                sourceId = 1,
                sourceName = "样例源",
                ok = ok,
                detail = detail,
                latencyMs = latencyMs,
            ),
        checkedAt = at,
    )

    @Test
    fun `未测必须显式写出来`() {
        assertEquals("未测", describeHealth(null))
    }

    @Test
    fun `正常带延迟，慢源单独一档`() {
        assertEquals("正常 320ms", describeHealth(entry(ok = true, latencyMs = 320)))
        // 刚好到阈值就算慢：宁可早一点提醒，也不要让用户刷不动列表时毫无线索
        assertEquals("慢 3.0s", describeHealth(entry(ok = true, latencyMs = SLOW_SOURCE_MS)))
        assertEquals("慢 12.4s", describeHealth(entry(ok = true, latencyMs = 12_400)))
    }

    @Test
    fun `失效要带原因，没原因也不能空着`() {
        assertEquals("失效（HTTP 404）", describeHealth(entry(ok = false, latencyMs = 80, detail = "HTTP 404")))
        assertEquals("失效（未知原因）", describeHealth(entry(ok = false, latencyMs = 80)))
    }

    @Test
    fun `条目数量词按类型分开`() {
        assertEquals("1234 个频道", describeEntryCount(SourceType.LIVE, 1234))
        assertEquals("430 部影片", describeEntryCount(SourceType.VOD, 430))
        // 0 与 null 都是「还没抓到东西」，但量词仍要跟类型走
        assertEquals("还没有频道", describeEntryCount(SourceType.LIVE, 0))
        assertEquals("还没有影片", describeEntryCount(SourceType.VOD, null))
    }

    @Test
    fun `刷新时间用相对时间，从未刷新单独一句`() {
        val now = 1_700_000_000_000L
        assertEquals("从未刷新", describeRefreshTime(0, now))
        assertEquals("刚刚刷新", describeRefreshTime(now - 30_000, now))
        assertEquals("5 分钟前刷新", describeRefreshTime(now - 5 * 60_000, now))
        assertEquals("3 小时前刷新", describeRefreshTime(now - 3 * 3_600_000, now))
        assertEquals("2 天前刷新", describeRefreshTime(now - 2 * 86_400_000, now))
    }

    @Test
    fun `时间倒退也不出负数`() {
        // 系统时间被改、或者数据库里留了未来时间：不能显示「-3 分钟前」
        assertEquals("刚刚刷新", describeRefreshTime(2_000, 1_000))
    }
}
