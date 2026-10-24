package com.leimao.tv.core.network.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub Release 响应解析（SET-07）。
 *
 * 样本照抄 `GET /repos/{owner}/{repo}/releases/latest` 的字段名与嵌套形状（只留本应用读的那几个），
 * 加粗的数值与换行都保留：解析器最容易死在 `\n` 与转义上，而 Release 正文恰恰是整段 markdown。
 */
class ReleaseJsonParserTest {
    @Test
    fun `解析完整的发布对象`() {
        val release = ReleaseJsonParser.parse(SAMPLE)!!

        assertEquals("v0.2.9.7", release.tagName)
        assertEquals("0.2.9.7", release.version)
        assertEquals("v0.2.9.7 · 检查更新", release.title)
        assertTrue(release.notes.contains("检查更新"))
        // 正文里的 `\n` 必须还原成真换行：展示层按行排版，留着字面量 `\n` 会挤成一坨
        assertTrue(release.notes.contains("\n"))
        assertEquals("2026-10-24T04:00:00Z", release.publishedAt)
        assertEquals(3, release.assets.size)
        assertEquals("leimao-tv-v0.2.9.7.apk", release.assets.first().name)
        assertEquals(28_000_000L, release.assets.first().sizeBytes)
        assertTrue(release.assets.first().downloadUrl.startsWith("https://github.com/"))
    }

    @Test
    fun `没有附件时给空列表而不是 null`() {
        val json = """{"tag_name":"v0.2.9.7","assets":[]}"""
        val release = ReleaseJsonParser.parse(json)!!
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `缺地址或缺名字的附件被跳过`() {
        val json =
            """
            {"tag_name":"v0.2.9.7","assets":[
              {"name":"leimao-tv-v0.2.9.7.apk"},
              {"browser_download_url":"https://example.test/x.apk","size":1},
              {"name":"leimao-tv-v0.2.9.7-v7a.apk","browser_download_url":"https://example.test/v7a.apk"}
            ]}
            """.trimIndent()
        val release = ReleaseJsonParser.parse(json)!!
        assertEquals(listOf("leimao-tv-v0.2.9.7-v7a.apk"), release.assets.map { it.name })
        // size 缺失按「不知道」处理，不是 0 字节
        assertEquals(0L, release.assets.single().sizeBytes)
    }

    @Test
    fun `没有 tag 时判为不是发布对象`() {
        assertNull(ReleaseJsonParser.parse("""{"name":"v0.2.9.7"}"""))
        assertNull(ReleaseJsonParser.parse("""{"tag_name":"  "}"""))
    }

    @Test
    fun `错误响应与非法 JSON 都不抛异常`() {
        // GitHub 的 404 错误体
        assertNull(ReleaseJsonParser.parse("""{"message":"Not Found","status":"404"}"""))
        assertNull(ReleaseJsonParser.parse("[]"))
        assertNull(ReleaseJsonParser.parse("not json at all"))
        assertNull(ReleaseJsonParser.parse(""))
    }

    @Test
    fun `title 缺失时回落到 tag`() {
        assertEquals("v1.2.3.4", ReleaseJsonParser.parse("""{"tag_name":"v1.2.3.4"}""")!!.title)
    }

    private companion object {
        val SAMPLE =
            """
            {
              "url": "https://api.github.com/repos/leimao2333/LEIMAOTV/releases/1",
              "tag_name": "v0.2.9.7",
              "name": "v0.2.9.7 · 检查更新",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-10-24T04:00:00Z",
              "body": "## 新增\n- 设置里多了「检查更新」\n- **下载后直接装**",
              "assets": [
                {
                  "name": "leimao-tv-v0.2.9.7.apk",
                  "size": 28000000,
                  "browser_download_url": "https://github.com/leimao2333/LEIMAOTV/releases/download/v0.2.9.7/leimao-tv-v0.2.9.7.apk"
                },
                {
                  "name": "leimao-tv-v0.2.9.7-v7a.apk",
                  "size": 25000000,
                  "browser_download_url": "https://github.com/leimao2333/LEIMAOTV/releases/download/v0.2.9.7/leimao-tv-v0.2.9.7-v7a.apk"
                },
                {
                  "name": "leimao-tv-v0.2.9.7-universal.apk",
                  "size": 45000000,
                  "browser_download_url": "https://github.com/leimao2333/LEIMAOTV/releases/download/v0.2.9.7/leimao-tv-v0.2.9.7-universal.apk"
                }
              ]
            }
            """.trimIndent()
    }
}
