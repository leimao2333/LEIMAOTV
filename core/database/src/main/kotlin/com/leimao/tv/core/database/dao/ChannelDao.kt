package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.database.repository.ChannelWithSource
import com.leimao.tv.core.database.repository.SourceEntryCount

@Dao
interface ChannelDao {
    @Query("SELECT COUNT(*) FROM channel")
    suspend fun count(): Int

    /**
     * 只查「这条频道还在不在」，不查它的内容。
     *
     * 这是启动关键路径上的查询（RULES §6.6 约束 2）：上次看的台可能因为换源 / 改名已经没了，
     * 用一条主键查询就能判断要不要降级，不必把整个频道表读进来。
     */
    @Query("SELECT id FROM channel WHERE id = :id")
    suspend fun existingIdOrNull(id: Long): Long?

    @Query("SELECT id FROM channel ORDER BY number ASC, id ASC LIMIT 1")
    suspend fun firstChannelId(): Long?

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun findById(id: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE sourceId = :sourceId AND name = :name")
    suspend fun findBySourceAndName(sourceId: Long, name: String): ChannelEntity?

    /**
     * 列表页用的全量查询：只从**启用的源**取频道。
     *
     * 为什么在 SQL 里 join 而不是取出来再在 Kotlin 里过滤：5000 条频道的列表要按 §7 不丢帧，
     * 让数据库多做一次 join、少给内存一批行，比在 UI 线程外多一轮过滤更省。
     */
    @Query(
        """
        SELECT c.* FROM channel c
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
        ORDER BY c.number ASC, c.id ASC
        """,
    )
    suspend fun listFromEnabledSources(): List<ChannelEntity>

    @Query(
        """
        SELECT DISTINCT c.groupName FROM channel c
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
        ORDER BY c.groupName ASC
        """,
    )
    suspend fun listGroups(): List<String>

    /**
     * 按名称搜索频道（SEARCH-01），只搜**启用的源**。
     *
     * 三处刻意的写法：
     * 1. `REPLACE(...)` 去掉半角与全角空格再比：源里的频道名「CCTV-1」「CCTV 1」「CCTV1」
     *    三种写法都有，用户不可能记得自己搜的是哪一种（归一化口径见 `normalizeSearchQuery`）；
     * 2. 关联 `source` 取源名：同名频道跨源都存在，副标题不带源名用户分不清点的是哪个；
     * 3. 不做分页：频道总数几千条、命中集通常几十条，`LIMIT` 一档就够，翻页只会让焦点更难落。
     */
    @Query(
        """
        SELECT c.id AS id, c.name AS name, c.groupName AS groupName, c.logo AS logoUrl,
               c.url AS streamUrl, c.number AS number, c.sourceId AS sourceId,
               s.name AS sourceName
        FROM channel c
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
          AND REPLACE(REPLACE(c.name, ' ', ''), char(12288), '') LIKE '%' || :keyword || '%' ESCAPE '\'
        ORDER BY c.number ASC, c.id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchByName(
        keyword: String,
        limit: Int,
    ): List<ChannelWithSource>

    /**
     * 启用的源下的全部频道（带源名），供**拼音简写搜索**在内存里过一遍（SEARCH-06）。
     *
     * 为什么不在 SQL 里做：拼音不是频道的固有列，SQLite 也不会把汉字转拼音 ——
     * 要么存一份拼音索引（要迁移 + 写入时维护），要么把候选集捞到内存里比对。
     * 频道总量几千条、一次搜索只跑一遍，后者更简单也没有一致性风险。
     */
    @Query(
        """
        SELECT c.id AS id, c.name AS name, c.groupName AS groupName, c.logo AS logoUrl,
               c.url AS streamUrl, c.number AS number, c.sourceId AS sourceId,
               s.name AS sourceName
        FROM channel c
        INNER JOIN source s ON s.id = c.sourceId
        WHERE s.enabled = 1
        ORDER BY c.number ASC, c.id ASC
        """,
    )
    suspend fun listEnabledWithSource(): List<ChannelWithSource>

    @Query("SELECT * FROM channel WHERE sourceId = :sourceId ORDER BY number ASC, id ASC")
    suspend fun listBySource(sourceId: Long): List<ChannelEntity>

    /**
     * 每条源在本地有多少频道（SET-09 源卡片上的条目数）。
     *
     * 用 `GROUP BY` 一次算完，而不是每条源发一次 `COUNT` —— 源管理页一屏十几条源，
     * 一次一条就是十几次往返，而这只是给卡片填一个数字。
     */
    @Query("SELECT sourceId AS sourceId, COUNT(*) AS total FROM channel GROUP BY sourceId")
    suspend fun countBySource(): List<SourceEntryCount>

    /**
     * 刻意用 ABORT 而不是 REPLACE。
     *
     * REPLACE 的语义是「删掉冲突行再插入」，主键会变；而 favorite / history 靠外键级联挂在
     * 频道上 —— 一次刷新就会把用户的收藏与观看历史全删掉。刷新必须走「查到就 UPDATE、
     * 查不到才 INSERT」，id 才能稳定（见 SourceRefresh 的实现）。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(channel: ChannelEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(channels: List<ChannelEntity>): List<Long>

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("DELETE FROM channel WHERE id = :id")
    suspend fun deleteById(id: Long)
}
