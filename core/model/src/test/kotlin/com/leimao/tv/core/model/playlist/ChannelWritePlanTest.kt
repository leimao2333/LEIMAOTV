package com.leimao.tv.core.model.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新落库计划（LIVE-05 的核心）。
 *
 * 这里每一条用例背后都对应一类真实事故：
 * 「刷新后收藏没了」「换台号乱了」「改个名字变成两个台」。
 */
class ChannelWritePlanTest {
    @Test
    fun `改名的频道原地更新 不换 id`() {
        val existing = listOf(existing(id = 7, name = "CCTV-1 高清", number = 3))
        val merged = listOf(merged(name = "CCTV-1", tvgId = null))

        val plan = planChannelWrites(existing, merged)

        val write = plan.writes.single() as ChannelWrite.Update
        // id 不变，收藏与观看历史才挂得住
        assertEquals(7L, write.id)
        assertEquals(3, write.number)
        assertTrue(write.changed)
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test
    fun `源里消失的频道被删除`() {
        val existing = listOf(existing(id = 1, name = "A", number = 1), existing(id = 2, name = "B", number = 2))
        val merged = listOf(merged(name = "A"))

        val plan = planChannelWrites(existing, merged)

        assertEquals(listOf(2L), plan.deleteIds)
        assertEquals(1, plan.writes.size)
    }

    @Test
    fun `新频道拿到之后的频道号`() {
        val existing = listOf(existing(id = 1, name = "A", number = 5))
        val merged = listOf(merged(name = "A"), merged(name = "B"))

        val plan = planChannelWrites(existing, merged)

        val insert = plan.writes.filterIsInstance<ChannelWrite.Insert>().single()
        assertEquals(6, insert.number)
        assertEquals(7, plan.nextNumber)
    }

    @Test
    fun `文件里写了频道号时优先使用`() {
        val merged = listOf(merged(name = "A", numberInPlaylist = 12))

        val plan = planChannelWrites(emptyList(), merged)

        assertEquals(12, (plan.writes.single() as ChannelWrite.Insert).number)
    }

    @Test
    fun `文件里的频道号与已有编号冲突时改用下一个空位`() {
        val existing = listOf(existing(id = 1, name = "A", number = 12))
        val merged = listOf(merged(name = "A"), merged(name = "B", numberInPlaylist = 12))

        val plan = planChannelWrites(existing, merged)

        val insert = plan.writes.filterIsInstance<ChannelWrite.Insert>().single()
        assertEquals(13, insert.number)
    }

    @Test
    fun `tvg-id 匹配优先于名称`() {
        val existing =
            listOf(
                existing(id = 1, name = "旧名字", tvgId = "cctv1", number = 1),
                existing(id = 2, name = "新名字", tvgId = "cctv2", number = 2),
            )
        val merged = listOf(merged(name = "新名字", tvgId = "cctv1"))

        val plan = planChannelWrites(existing, merged)

        // 名称能对上 id=2，但 tvg-id 说这是 cctv1；标识更可信
        assertEquals(1L, (plan.writes.single() as ChannelWrite.Update).id)
        assertEquals(listOf(2L), plan.deleteIds)
    }

    @Test
    fun `字段完全没变时标记为未变更 避免多余的写`() {
        val existing =
            listOf(
                existing(
                    id = 3,
                    name = "CCTV-1",
                    tvgId = "cctv1",
                    number = 1,
                    group = "央视",
                    url = "https://example.com/1",
                ),
            )
        val merged =
            listOf(
                merged(name = "CCTV-1", tvgId = "cctv1", group = "央视", lines = listOf("https://example.com/1")),
            )

        val write = planChannelWrites(existing, merged).writes.single() as ChannelWrite.Update

        assertEquals(false, write.changed)
    }

    @Test
    fun `备用线路变化也算变更`() {
        val existing =
            listOf(
                existing(id = 3, name = "CCTV-1", number = 1, url = "https://example.com/old"),
            )
        val merged = listOf(merged(name = "CCTV-1", lines = listOf("https://example.com/new")))

        val write = planChannelWrites(existing, merged).writes.single() as ChannelWrite.Update

        assertTrue(write.changed)
    }

    @Test
    fun `空库时全部插入 编号从 1 开始`() {
        val plan = planChannelWrites(emptyList(), listOf(merged(name = "A"), merged(name = "B")))

        assertEquals(listOf(1, 2), plan.writes.filterIsInstance<ChannelWrite.Insert>().map { it.number })
    }

    private fun existing(
        id: Long,
        name: String,
        tvgId: String? = null,
        number: Int = 1,
        group: String = UNGROUPED,
        url: String = "https://example.com/$id",
    ) = ExistingChannel(
        id = id,
        name = name,
        tvgId = tvgId,
        number = number,
        groupName = group,
        logoUrl = null,
        primaryUrl = url,
    )

    private fun merged(
        name: String,
        tvgId: String? = null,
        group: String = UNGROUPED,
        lines: List<String> = listOf("https://example.com/$name"),
        numberInPlaylist: Int? = null,
    ) = MergedChannel(
        name = name,
        groupName = group,
        tvgId = tvgId,
        logoUrl = null,
        numberInPlaylist = numberInPlaylist,
        lines = lines,
    )
}
