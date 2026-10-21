package com.leimao.tv

import com.leimao.tv.core.version.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 双份 CHANGELOG 的一致性（RULES §10.3 第 3 项、§11）。
 *
 * 两份日志天然会漂移：一份在 markdown 里给仓库访客看，一份在 Kotlin 里给应用内「关于」页看。
 * 内容按 §10.3 的分工允许裁剪（应用内去掉构建 / 测试这类开发者细节），
 * 但**版本与日期必须一一对应且顺序一致** —— 所以这里钉的是版本序列与日期序列，不是逐字比对。
 */
class ChangelogSyncTest {
    @Test
    fun `仓库 CHANGELOG 的首条版本等于 CURRENT_VERSION`() {
        val entries = markdownEntries()

        assertTrue("CHANGELOG.md 里没找到任何 `## vX.Y.Z.W（日期` 标题", entries.isNotEmpty())
        assertEquals(AppVersion.CURRENT_VERSION, entries.first().version)
    }

    @Test
    fun `仓库 CHANGELOG 的版本序列与应用内条目一致`() {
        assertEquals(
            markdownEntries().map { it.version },
            AppVersion.CHANGELOG.map { it.version },
        )
    }

    @Test
    fun `仓库 CHANGELOG 的日期与应用内条目一致`() {
        assertEquals(
            markdownEntries().map { it.date },
            AppVersion.CHANGELOG.map { it.date },
        )
    }

    private data class Entry(val version: String, val date: String)

    private fun markdownEntries(): List<Entry> =
        changelogFile().readLines(Charsets.UTF_8).mapNotNull { line ->
            HEADING.find(line)?.let { match ->
                Entry(version = match.groupValues[1], date = match.groupValues[2])
            }
        }

    private fun changelogFile(): File {
        val workingDir = File(".").absoluteFile
        return listOf(
            workingDir.resolve("../CHANGELOG.md").normalize(),
            workingDir.resolve("CHANGELOG.md").normalize(),
        ).firstOrNull { it.exists() }
            ?: error("找不到 CHANGELOG.md（工作目录：$workingDir）")
    }

    private companion object {
        /** 标题形如 `## v0.1.1.0（2026-10-21）`，括号内日期后可跟补充说明 */
        val HEADING = Regex("""^##\s+v(\d+\.\d+\.\d+\.\d+)（(\d{4}-\d{2}-\d{2})""")
    }
}
