package com.leimao.tv.core.network.epg

import com.leimao.tv.core.model.epg.mergeEpgGuide
import com.leimao.tv.core.model.epg.normalizeEpgName
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实节目单样本的集成验证。
 *
 * 单元测试用的是手写小样本，看不出「503 个频道 / 一万四千条节目」这种量级下的问题：
 * 解析耗时、同名归并率、节目能不能对上播放列表的频道名。
 *
 * 样本太大，不进仓库。跑法：
 *
 * ```
 * EPG_SAMPLE=/path/to/epg.xml ./gradlew :core:network:testDebugUnitTest --tests "*XmltvSampleTest*"
 * ```
 *
 * 没设环境变量时自动跳过，不会让 CI 变红。
 */
class XmltvSampleTest {
    @Test
    fun `真实节目单样本解析与合并`() {
        val path = System.getenv("EPG_SAMPLE").orEmpty()
        assumeTrue("未提供 EPG_SAMPLE，跳过", path.isNotEmpty() && File(path).exists())

        val file = File(path)
        val startedAt = System.currentTimeMillis()
        val parsed = file.inputStream().use { XmltvParser.parse(it) }
        val parseCost = System.currentTimeMillis() - startedAt

        val mergeStartedAt = System.currentTimeMillis()
        val guide = mergeEpgGuide(parsed.channels, parsed.programmes)
        val mergeCost = System.currentTimeMillis() - mergeStartedAt

        println(
            "EPG 样本：${file.length() / 1024}KB　" +
                "解析 ${parsed.channels.size} 个频道 / ${parsed.programmes.size} 条节目，" +
                "跳过 ${parsed.skipped}，耗时 ${parseCost}ms；" +
                "合并后 ${guide.channels.size} 个频道 / ${guide.programmeCount} 条节目，" +
                "耗时 ${mergeCost}ms",
        )

        assertTrue("频道数不对", parsed.channels.size > 100)
        assertTrue("节目数不对", parsed.programmes.size > 1000)
        // 合并只会减少频道数，不会变多
        assertTrue(guide.channels.size <= parsed.channels.size)
        // 解析 6MB 级别的文件不该超过 3 秒（低端盒子另算，这里守 JVM 的基线）
        assertTrue("解析过慢：${parseCost}ms", parseCost < 3_000)

        // 打印几个归一化结果，方便人工核对「CCTV-1 / CCTV1」这类错位是否真的被抹平
        val sample = guide.channels.take(5).map { "${it.name}→${it.normalizedName}" }
        println("归一化示例：$sample　cctv-1 高清→${normalizeEpgName("cctv-1 高清")}")
    }
}
