package com.leimao.tv.core.database.repository

import androidx.room.withTransaction
import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.database.entity.ChannelLineEntity
import com.leimao.tv.core.model.playlist.ChannelWrite
import com.leimao.tv.core.model.playlist.ExistingChannel
import com.leimao.tv.core.model.playlist.MergedChannel
import com.leimao.tv.core.model.playlist.planChannelWrites

/** 一次刷新对某个源造成的实际变化，直接用于「刷新完成」提示与日志（不含任何地址）。 */
data class ChannelWriteStats(
    val inserted: Int,
    val updated: Int,
    val removed: Int,
    val lineCount: Int,
)

/**
 * 频道写入。**只被 :core:data 的刷新编排调用**，UI 拿不到它 ——
 * 频道表的写入路径越少，「id 稳定」这个不变式越不容易被破坏。
 */
interface ChannelWriter {
    /**
     * 用 [merged] 覆盖某个源下的频道集合：
     * 匹配到的原地更新（id 不变，收藏与历史不受影响），匹配不到的插入，源里已消失的删除。
     */
    suspend fun replaceForSource(sourceId: Long, merged: List<MergedChannel>): ChannelWriteStats
}

class RoomChannelWriter(
    private val database: LeimaoDatabase,
) : ChannelWriter {
    override suspend fun replaceForSource(sourceId: Long, merged: List<MergedChannel>): ChannelWriteStats =
        database.withTransaction {
            val channelDao = database.channelDao()
            val existing = channelDao.listBySource(sourceId)
            val plan = planChannelWrites(existing.map { it.toExisting() }, merged)

            var inserted = 0
            var updated = 0

            plan.writes.forEach { write ->
                when (write) {
                    is ChannelWrite.Insert -> {
                        val id =
                            channelDao.insert(
                                ChannelEntity(
                                    sourceId = sourceId,
                                    tvgId = write.merged.tvgId,
                                    name = write.merged.name,
                                    groupName = write.merged.groupName,
                                    logo = write.merged.logoUrl,
                                    url = write.merged.primaryLine,
                                    number = write.number,
                                ),
                            )
                        replaceLines(channelId = id, channel = write.merged)
                        inserted++
                    }

                    is ChannelWrite.Update -> {
                        if (write.changed) {
                            channelDao.update(
                                ChannelEntity(
                                    id = write.id,
                                    sourceId = sourceId,
                                    tvgId = write.merged.tvgId,
                                    name = write.merged.name,
                                    groupName = write.merged.groupName,
                                    logo = write.merged.logoUrl,
                                    url = write.merged.primaryLine,
                                    number = write.number,
                                ),
                            )
                            updated++
                        }
                        // 即使频道字段没变，线路也可能变了（换了备用地址），线路表始终对齐
                        replaceLines(channelId = write.id, channel = write.merged)
                    }
                }
            }

            plan.deleteIds.forEach { channelDao.deleteById(it) }

            ChannelWriteStats(
                inserted = inserted,
                updated = updated,
                removed = plan.deleteIds.size,
                lineCount = merged.sumOf { it.lines.size },
            )
        }

    private suspend fun replaceLines(channelId: Long, channel: MergedChannel) {
        val lineDao = database.channelLineDao()
        lineDao.insertAll(
            channel.lines.mapIndexed { index, url ->
                ChannelLineEntity(channelId = channelId, url = url, position = index)
            },
        )
        lineDao.deleteExcept(channelId = channelId, keepUrls = channel.lines)
    }

    private fun ChannelEntity.toExisting(): ExistingChannel =
        ExistingChannel(
            id = id,
            name = name,
            tvgId = tvgId,
            number = number,
            groupName = groupName,
            logoUrl = logo,
            primaryUrl = url,
        )
}
