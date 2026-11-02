package com.leimao.tv.feature.iptv

import com.leimao.tv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 换台与分组是播放页里最容易「看起来对、用起来错」的两块：
 * 顺序错一位用户就会觉得「上下键乱跳」，到头不翻页用户会以为按键坏了。
 */
class ChannelGroupsTest {
    private fun channel(id: Long, name: String, group: String, number: Int) =
        Channel(
            id = id,
            name = name,
            groupName = group,
            logoUrl = null,
            streamUrl = "https://example.com/$id.m3u8",
            number = number,
        )

    private val channels =
        listOf(
            channel(1, "台一", "分组甲", 1),
            channel(2, "台二", "分组乙", 2),
            channel(3, "台三", "分组甲", 3),
        )

    @Test
    fun `按固定分类归并，组内保持传入顺序`() {
        // LIVE-29 之后不再用上游组名当分组名：认不出的一律进「其他频道」
        val groups = groupChannels(channels)

        assertEquals(listOf("其他频道"), groups.map { it.name })
        assertEquals(listOf(1L, 2L, 3L), groups[0].channels.map { it.id })
    }

    @Test
    fun `全部频道排第一且含全量，调台不会只剩最近看过的台`() {
        // LIVE-31：默认落点必须是全量。曾经默认落在「最近观看」，
        // 打开抽屉只有刚看过的十几个台，用户以为应用里就这些台
        val merged = browserGroups(groupChannels(channels), recentChannels = listOf(channels[2]))

        assertEquals(ALL_CHANNELS_GROUP, merged.first().name)
        assertEquals(listOf(1L, 2L, 3L), merged.first().channels.map { it.id })
        assertEquals(
            listOf(ALL_CHANNELS_GROUP, RECENT_CHANNELS_GROUP, "其他频道"),
            merged.map { it.name },
        )
    }

    @Test
    fun `没有最近观看时不出现这个分组`() {
        val merged = browserGroups(groupChannels(channels), recentChannels = emptyList())

        assertEquals(listOf(ALL_CHANNELS_GROUP, "其他频道"), merged.map { it.name })
    }

    @Test
    fun `最近观看沿用分类里的编号，按号换台不会错台`() {
        // 历史表里存的是入库时的旧编号（999），必须换成重排后的编号（3），
        // 否则「在最近观看里按 3」会跳到别的台或什么都不发生
        val stale = channels[2].copy(number = 999)
        val merged = browserGroups(groupChannels(channels), recentChannels = listOf(stale))

        val recent = merged.first { it.name == RECENT_CHANNELS_GROUP }
        assertEquals(3, recent.channels.single().number)
    }

    @Test
    fun `下键取后一个台`() {
        assertEquals(2L, neighborOf(channels, currentId = 1L, delta = 1)?.id)
    }

    @Test
    fun `上键取前一个台`() {
        assertEquals(2L, neighborOf(channels, currentId = 3L, delta = -1)?.id)
    }

    @Test
    fun `最后一个台往下翻回第一个`() {
        assertEquals(1L, neighborOf(channels, currentId = 3L, delta = 1)?.id)
    }

    @Test
    fun `第一个台往上翻到最后一个`() {
        assertEquals(3L, neighborOf(channels, currentId = 1L, delta = -1)?.id)
    }

    @Test
    fun `只有一个台时不换台，避免原地重播`() {
        assertNull(neighborOf(listOf(channel(1, "唯一", "分组甲", 1)), currentId = 1L, delta = 1))
    }

    @Test
    fun `当前台不在列表里时回到第一个`() {
        assertEquals(1L, neighborOf(channels, currentId = 99L, delta = 1)?.id)
    }

    @Test
    fun `空列表返回空`() {
        assertNull(neighborOf(emptyList(), currentId = 1L, delta = 1))
    }

    @Test
    fun `过滤空关键词原样返回`() {
        val groups = groupChannels(channels)
        assertEquals(groups, filterGroups(groups, "  "))
    }

    @Test
    fun `过滤按名称包含匹配且不区分大小写`() {
        val groups =
            listOf(
                ChannelGroup("分组甲", listOf(channel(1, "CCTV1", "分组甲", 1), channel(3, "cctv2", "分组甲", 3))),
                ChannelGroup("分组乙", listOf(channel(2, "凤凰卫视", "分组乙", 2))),
            )
        val result = filterGroups(groups, "cctv")

        assertEquals(1, result.size)
        assertEquals("分组甲", result[0].name)
        assertEquals(listOf(1L, 3L), result[0].channels.map { it.id })
    }

    @Test
    fun `过滤后无命中的分组被整组去掉`() {
        val groups =
            listOf(
                ChannelGroup("分组甲", listOf(channel(1, "台一", "分组甲", 1))),
                ChannelGroup("分组乙", listOf(channel(2, "台二", "分组乙", 2))),
            )
        val result = filterGroups(groups, "台一")

        assertEquals(1, result.size)
        assertEquals("分组甲", result[0].name)
    }

    @Test
    fun `过滤无结果时返回空列表`() {
        val groups = groupChannels(channels)
        assertEquals(emptyList<ChannelGroup>(), filterGroups(groups, "不存在"))
    }

    // ---------- 固定分类（LIVE-29）：上游组名只当判定依据 ----------

    @Test
    fun `地方台无论原来在哪个组都归省份地方`() {
        val result =
            groupChannels(
                listOf(
                    channel(1, "上虞新闻综合", "地方频道", 1),
                    channel(2, "哈尔滨娱乐", "地方频道", 2),
                    channel(3, "四川科教", "四川频道", 3),
                ),
            )

        assertEquals(listOf("省份地方"), result.map { it.name })
        assertEquals(listOf(1L, 2L, 3L), result[0].channels.map { it.id })
    }

    @Test
    fun `认不出的台进其他频道而不是被丢掉`() {
        val result =
            groupChannels(
                listOf(channel(1, "中国蓝新闻", "地方频道", 1)),
            )

        // 「中国蓝」反查不到省市，但组名带「地方」→ 省份地方
        assertEquals(listOf("省份地方"), result.map { it.name })
        assertEquals(listOf(1L), result[0].channels.map { it.id })
    }

    @Test
    fun `上游组名被重建成固定分类`() {
        val result =
            groupChannels(
                listOf(
                    channel(1, "CCTV1 综合", "央视频道", 1),
                    channel(2, "湖南卫视", "卫视频道", 2),
                ),
            )

        assertEquals(listOf("央视频道", "省份地方"), result.map { it.name })
    }

    /**
     * 真实订阅列表的规模与脏数据（LIVE-31 的底线）。
     *
     * 上面每条用例都只有三个台，跑得通不代表真实列表跑得通。真实的一份 IPTV 清单动辄
     * 两三百个台，且一定带三类脏数据：台名重复（同一路流挂两个名字）、上游编号不单调、
     * 上游组名五花八门。这几条一起施加，验证的才是用户手里那份文件。
     */
    @Test
    fun `几百个台的清单里一个都不能丢`() {
        val big =
            (1..300).map { i ->
                // 每 50 个重名一次：真实清单里同一路流挂多个台名很常见
                val name = if (i % 50 == 0) "CCTV1 综合" else "频道 $i"
                val group =
                    when (i % 3) {
                        0 -> "央视频道"
                        1 -> "卫视频道"
                        else -> "地方台"
                    }
                // 上游编号故意反着给：不能靠它来排
                channel(i.toLong(), name, group, 301 - i)
            }
        val groups = groupChannels(big)
        val all = browserGroups(groups, recentChannels = emptyList()).first()

        assertEquals(ALL_CHANNELS_GROUP, all.name)
        // ① 重名不能被当成重复而合并：合并等于凭空少了台
        assertEquals(300, all.channels.size)
        assertEquals(300, all.channels.distinctBy { it.id }.size)
        // ② 编号按重排结果严格递增 1..300，与上游给的顺序无关
        assertEquals((1..300).toList(), all.channels.map { it.number })
        // ③ 各分类加起来还是 300：归类不能吞台
        assertEquals(300, groups.sumOf { it.channels.size })
    }

    @Test
    fun `一直按上键能走遍每个台且回到起点`() {
        val big =
            (1..300).map { i ->
                val group =
                    when (i % 3) {
                        0 -> "央视频道"
                        1 -> "卫视频道"
                        else -> "地方台"
                    }
                channel(i.toLong(), "频道 $i", group, i)
            }
        val all = browserGroups(groupChannels(big), recentChannels = emptyList()).first().channels

        // 从第一个台一路按上键（到头翻回末尾）：走满 300 步必须是把整圈走完
        val visited = mutableListOf<Long>()
        var current = all.first().id
        repeat(all.size) {
            visited += current
            current = neighborOf(all, current, delta = -1)!!.id
        }

        assertEquals(300, visited.distinct().size)
        assertEquals("走满一圈必须回到起点", all.first().id, current)
    }
}
