package com.leimao.tv.core.database.repository

import com.leimao.tv.core.database.dao.ChannelLineQualityDao
import com.leimao.tv.core.database.entity.ChannelLineQualityEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坏线路记忆裁剪（LIVE-25）。
 *
 * 用内存 fake DAO 单测 `prune` 的裁剪边界，不碰 Room（纯逻辑不该被数据库拖住）：
 * - 过期（180 天）删除；
 * - 超量（>200 条）只留最近 200 条；
 * - 裁剪异常被吞掉（runCatching），绝不让起播链路受影响。
 */
class ChannelLineQualityRepositoryTest {
    private open class FakeDao : ChannelLineQualityDao {
        val rows = mutableListOf<ChannelLineQualityEntity>()
        val deletedOlderThan = mutableListOf<Long>()
        var trimmedTo: Int? = null

        override suspend fun upsert(entity: ChannelLineQualityEntity) {
            rows.removeAll { it.channelId == entity.channelId && it.url == entity.url }
            rows.add(entity)
        }

        override suspend fun byChannel(channelId: Long): List<ChannelLineQualityEntity> =
            rows.filter { it.channelId == channelId }

        override suspend fun find(
            channelId: Long,
            url: String,
        ): ChannelLineQualityEntity? =
            rows.firstOrNull { it.channelId == channelId && it.url == url }

        override suspend fun deleteChannel(channelId: Long) {
            rows.removeAll { it.channelId == channelId }
        }

        override suspend fun deleteOlderThan(before: Long) {
            deletedOlderThan.add(before)
            rows.removeAll { it.lastSeenAt < before }
        }

        override suspend fun count(): Int = rows.size

        override suspend fun trimToKeep(keep: Int) {
            trimmedTo = keep
            rows.sortByDescending { it.lastSeenAt }
            if (rows.size > keep) rows.subList(keep, rows.size).clear()
        }
    }

    private fun repo(dao: FakeDao) = RoomChannelLineQualityRepository(dao)

    private fun row(
        channelId: Long,
        url: String,
        lastSeenAt: Long,
    ) = ChannelLineQualityEntity(channelId, url, successCount = 0, failCount = 1, lastSeenAt = lastSeenAt)

    @Test
    fun `超过180天的记录被删除`() =
        runBlocking {
            val dao = FakeDao()
            val now = 1_000_000_000_000L
            dao.upsert(row(1, "new", now))
            dao.upsert(row(2, "old", now - 181L * 24 * 60 * 60 * 1000))

            repo(dao).prune(now)

            assertTrue(dao.rows.none { it.url == "old" })
            assertTrue(dao.rows.any { it.url == "new" })
        }

    @Test
    fun `超过200条只留最近200条`() =
        runBlocking {
            val dao = FakeDao()
            val now = 1_000_000_000_000L
            repeat(250) { i ->
                dao.upsert(row(i.toLong(), "url$i", now - i * 1000))
            }

            repo(dao).prune(now)

            assertEquals(200, dao.rows.size)
            // 留的是最近 200 条：最旧的 url249 被删，最新的 url0 还在
            assertTrue(dao.rows.none { it.url == "url249" })
            assertTrue(dao.rows.any { it.url == "url0" })
        }

    @Test
    fun `未超量不触发trim`() =
        runBlocking {
            val dao = FakeDao()
            val now = 1_000_000_000_000L
            repeat(10) { i -> dao.upsert(row(i.toLong(), "url$i", now - i)) }

            repo(dao).prune(now = now)

            assertEquals(null, dao.trimmedTo)
            assertEquals(10, dao.rows.size)
        }

    @Test
    fun `裁剪异常不抛出`() =
        runBlocking {
            val dao =
                object : FakeDao() {
                    override suspend fun deleteOlderThan(before: Long) {
                        throw RuntimeException("模拟数据库故障")
                    }
                }
            dao.upsert(row(1, "a", 1_000_000L))

            // 不抛异常即通过：裁剪失败不该打断起播（§6.6 关键路径）
            repo(dao).prune(now = 1_000_000_000_000L)
        }
}
