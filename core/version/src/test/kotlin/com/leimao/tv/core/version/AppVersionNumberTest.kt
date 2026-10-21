package com.leimao.tv.core.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RULES §9.1 要求的两条：versionCode 公式、版本递增（含进位与 1.0.0.0 例外）。 */
class AppVersionNumberTest {
    @Test
    fun `versionCode 与 RULES 10_2 的算例表逐行相等`() {
        // 表来自 RULES §10.2；这四行覆盖了 0 顶端、单段、修订位与 1.0 顶端四种形态
        assertEquals(10_000, AppVersionNumber.parse("0.1.0.0").versionCode)
        assertEquals(10_100, AppVersionNumber.parse("0.1.1.0").versionCode)
        assertEquals(20_000, AppVersionNumber.parse("0.2.0.0").versionCode)
        assertEquals(20_103, AppVersionNumber.parse("0.2.1.3").versionCode)
        assertEquals(1_000_000, AppVersionNumber.parse("1.0.0.0").versionCode)
    }

    @Test
    fun `里程碑内发版只动小版本`() {
        assertEquals("0.1.2.0", AppVersionNumber.parse("0.1.1.0").nextMinorRelease().versionName)
    }

    @Test
    fun `小版本走满十个数时向大版本进位`() {
        assertEquals("0.2.0.0", AppVersionNumber.parse("0.1.9.0").nextMinorRelease().versionName)
    }

    @Test
    fun `大版本走满十个数时向顶端版本进位`() {
        assertEquals("1.0.0.0", AppVersionNumber.parse("0.9.9.0").nextMinorRelease().versionName)
    }

    @Test
    fun `进入下一个里程碑时大版本加一且小版本归零`() {
        assertEquals("0.2.0.0", AppVersionNumber.parse("0.1.5.0").nextMilestone().versionName)
    }

    @Test
    fun `首个正式版是允许的语义化例外`() {
        // M4 完成时大版本并未走满十个数，仍直接进 1.0.0.0（§10.2 差异第 2 条）
        assertEquals("1.0.0.0", AppVersionNumber.parse("0.4.9.0").toFirstStable().versionName)
        assertEquals(1_000_000, AppVersionNumber.parse("0.4.9.0").toFirstStable().versionCode)
    }

    @Test
    fun `解析时容忍 v 前缀`() {
        assertEquals("0.1.1.0", AppVersionNumber.parse("v0.1.1.0").versionName)
    }

    @Test
    fun `应用内更新日志的最新条目必须是当前版本`() {
        // 这条钉住「先改版本号、日志回头补」这个反模式（§10.5）：版本动了日志没动，测试就红
        assertEquals(AppVersion.CURRENT_VERSION, AppVersion.CHANGELOG.first().version)
    }

    @Test
    fun `更新日志按版本倒序排列`() {
        val versions = AppVersion.CHANGELOG.map { AppVersionNumber.parse(it.version) }
        versions.zipWithNext().forEach { (newer, older) ->
            assertTrue("$newer 应排在 $older 之前", newer.versionCode > older.versionCode)
        }
    }
}
