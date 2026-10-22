package com.leimao.tv.feature.settings

import com.leimao.tv.core.data.EpgFailure
import com.leimao.tv.core.data.EpgImportResult
import com.leimao.tv.core.database.repository.EpgImportSummary
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节目单导入文案（LIVE-20）。
 *
 * 这几条断言守的是一件事：**失败的三种原因不能糊成一句话**。
 * 用户看到「文件打不开」会去检查权限，看到「不是节目单」会去换文件，
 * 看到「中途损坏」会去重新下载 —— 三种动作完全不同，糊在一起就等于没说。
 */
class DescribeEpgTest {
    @Test
    fun `成功时报告合并后的频道数与节目数`() {
        val text =
            describeEpg(
                EpgImportResult.Done(
                    EpgImportSummary(channelCount = 502, programmeCount = 14386, latestStartAt = null),
                ),
            )
        assertTrue(text, text.contains("502 个频道"))
        assertTrue(text, text.contains("14386 档节目"))
    }

    @Test
    fun `有最新一档时间时补上日期`() {
        // 2026-10-22 19:00 +0800
        val at = 1792666800000L
        val text =
            describeEpg(
                EpgImportResult.Done(
                    EpgImportSummary(channelCount = 1, programmeCount = 1, latestStartAt = at),
                ),
            )
        assertTrue(text, text.contains("最新一档"))
    }

    @Test
    fun `读不到文件时说文件打不开`() {
        val text = describeEpg(EpgImportResult.Failed(EpgFailure.CANNOT_READ, "no permission"))
        assertTrue(text, text.contains("打不开"))
    }

    @Test
    fun `不是节目单时明确说不是 XMLTV`() {
        val text = describeEpg(EpgImportResult.Failed(EpgFailure.NOT_XMLTV))
        assertTrue(text, text.contains("不是 XMLTV"))
    }

    @Test
    fun `半份文件时说中途损坏`() {
        val text = describeEpg(EpgImportResult.Failed(EpgFailure.BROKEN_XML, "eof"))
        assertTrue(text, text.contains("损坏"))
    }
}
