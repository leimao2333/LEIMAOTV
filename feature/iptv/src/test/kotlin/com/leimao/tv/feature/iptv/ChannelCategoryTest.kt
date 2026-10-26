package com.leimao.tv.feature.iptv

import com.leimao.tv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 直播频道固定分类（LIVE-29）。
 *
 * 这套判定是**用户找台的目录**，错一条就会让「戏曲频道」里混进新闻台，
 * 所以每条规则都用真实源里的组名/台名钉一遍 —— 这些名字都是从聚合订阅里抄的。
 */
class ChannelCategoryTest {
    @Test
    fun `央视按组名与台名都能认出来`() {
        assertEquals("央视频道", categoryOf(channel("CCTV-1 综合", "央视频道")))
        // CCTV5+ 现在归体育频道：体育是窄类别，排在央视之前判定
        assertEquals("体育频道", categoryOf(channel("CCTV5+体育赛事", "央视高清")))
        assertEquals("央视频道", categoryOf(channel("CGTN", "中央台")))
    }

    @Test
    fun `戏曲优先于央视`() {
        // CCTV-11 的组名往往只写「央视频道」，靠台名里的「戏曲」才能进对分类
        assertEquals("戏曲频道", categoryOf(channel("CCTV-11 戏曲", "央视频道")))
        assertEquals("戏曲频道", categoryOf(channel("梨园频道", "曲艺")))
    }

    @Test
    fun `音乐优先于央视`() {
        assertEquals("音乐频道", categoryOf(channel("CCTV-15 音乐", "央视频道")))
        assertEquals("音乐频道", categoryOf(channel("MTV 演唱会", "其他")))
    }

    @Test
    fun `广播优先于音乐与戏曲`() {
        assertEquals("广播频道", categoryOf(channel("音乐广播", "音乐")))
        assertEquals("广播频道", categoryOf(channel("中国之声", "电台")))
        assertEquals("广播频道", categoryOf(channel("FM97.4", "其他")))
    }

    @Test
    fun `卫视与省名组算省份地方`() {
        assertEquals("省份地方", categoryOf(channel("湖南卫视", "卫视")))
        assertEquals("省份地方", categoryOf(channel("北京新闻", "北京频道")))
    }

    @Test
    fun `台名以城市开头也算省份地方`() {
        // 「上虞新闻综合」这种没有省名的台，靠 CityProvince 反查
        assertEquals("省份地方", categoryOf(channel("上虞新闻综合", "地方频道")))
    }

    @Test
    fun `认不出的进其他`() {
        assertEquals("其他频道", categoryOf(channel("4K 超高清", "4K专区")))
        assertEquals("其他频道", categoryOf(channel("纪实人文", "纪录")))
    }

    @Test
    fun `空分组不会出现且顺序固定`() {
        val groups =
            groupChannels(
                listOf(
                    channel("CCTV-1 综合", "央视频道"),
                    channel("湖南卫视", "卫视"),
                    channel("CCTV-11 戏曲", "央视频道"),
                ),
            )
        // 没有广播/音乐/其他 → 这三类不出现；顺序按 CANONICAL_GROUPS
        assertEquals(listOf("央视频道", "省份地方", "戏曲频道"), groups.map { it.name })
        // CCTV-11 进了戏曲，所以央视频道这一组只剩 CCTV-1
        assertEquals(1, groups.first().channels.size)
    }

    private fun channel(
        name: String,
        group: String,
    ) = Channel(
        id = 0,
        name = name,
        groupName = group,
        streamUrl = "http://example.com/$name.m3u8",
        logoUrl = null,
        number = 0,
    )
}
