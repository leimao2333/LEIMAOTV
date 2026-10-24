package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.leimao.tv.core.database.entity.MediaEntity

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE sourceId = :sourceId AND type IN (:types)")
    suspend fun deleteByTypes(
        sourceId: Long,
        types: List<String>,
    )

    /**
     * 用一批新数据替换「某源某几个类型」的旧数据。
     *
     * 为什么按类型替换而不是整源替换：刷新时某个类型可能抓取失败（源那一栏临时挂了），
     * 整源替换会把它上次的数据一起清掉，用户看到的是「内容凭空少了」。
     * 只替换本次**成功拿到**的类型，失败的保留旧快照。
     */
    @Transaction
    suspend fun replaceTypes(
        sourceId: Long,
        types: List<String>,
        items: List<MediaEntity>,
    ) {
        if (types.isNotEmpty()) deleteByTypes(sourceId, types)
        if (items.isNotEmpty()) upsertAll(items)
    }

    /** 全库最近更新（首页「最近更新」一行） */
    @Query("SELECT * FROM media ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MediaEntity>

    /**
     * 按类型查询，年份 / 地区为 null 表示不限。
     *
     * 用 `(:year IS NULL OR ...)` 而不是拼 SQL：三个筛选条件（不限 / 只要年份 / 年份+地区）
     * 合成一条语句，编译期就能校验，也避免动态拼串时漏加引号这类事故。
     */
    @Query(
        """
        SELECT * FROM media
        WHERE type = :type
          AND (:year IS NULL OR year = :year)
          AND (:area IS NULL OR area = :area)
          AND (:tag IS NULL OR (',' || IFNULL(tags, '') || ',') LIKE '%,' || :tag || ',%')
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun filtered(
        type: String,
        year: Int?,
        area: String?,
        tag: String?,
        limit: Int,
    ): List<MediaEntity>

    /**
     * 某类型下所有条目的标签列（VOD-08）。
     *
     * 返回的是**原始拼接串**，聚合（拆开、计数、排序）在仓库层用 Kotlin 做：
     * SQLite 没有 `split`，硬用递归 CTE 拆串既难读也难测，而几千行短字符串的
     * 一次聚合远不到一毫秒。
     */
    @Query("SELECT tags FROM media WHERE type = :type AND tags IS NOT NULL AND tags != ''")
    suspend fun tagColumnOf(type: String): List<String>

    /** 「每源一行」 */
    @Query("SELECT * FROM media WHERE sourceId = :sourceId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun bySource(
        sourceId: Long,
        limit: Int,
    ): List<MediaEntity>

    /** 该类型下出现过的年份，倒序 —— 筛选项按「新的在前」排 */
    @Query(
        """
        SELECT DISTINCT year FROM media
        WHERE type = :type AND year IS NOT NULL
        ORDER BY year DESC
        """,
    )
    suspend fun yearsOf(type: String): List<Int>

    @Query(
        """
        SELECT DISTINCT area FROM media
        WHERE type = :type AND area IS NOT NULL AND area != ''
        ORDER BY area ASC
        """,
    )
    suspend fun areasOf(type: String): List<String>

    /** 缓存新鲜度判断用：全库最近一次入库时间 */
    @Query("SELECT MAX(fetchedAt) FROM media")
    suspend fun lastFetchedAt(): Long?

    /**
     * 按跨源唯一键取一条（详情页进场时先把本地摘要渲染出来，不等网络）。
     *
     * 走 `(sourceId, sourceKey)` 的唯一索引，是一次主键级查找；返回 null 说明这条
     * 从来没进过首页快照（例如源刚被清空），此时详情页只能直接等回源结果。
     */
    @Query("SELECT * FROM media WHERE sourceId = :sourceId AND sourceKey = :sourceKey")
    suspend fun find(
        sourceId: Long,
        sourceKey: String,
    ): MediaEntity?

    /** 源被删除时连带清理它的条目，避免首页出现点进去拉不到详情的历史残留 */
    @Query("DELETE FROM media WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: Long)

    /**
     * 按片名搜索本地快照（SEARCH-01）。
     *
     * 为什么本地也要搜一遍：远端每源最多等 8 秒，而库里已经有上一轮刷新的几百条，
     * 本地命中能在一帧内出来（架构设计 §3 的「本地先出，远端逐源补入」）。
     * 关联 `source` 而不是只查 `media`：已停用源的条目点进去必然拉不到详情，
     * 与其让用户点一次死链，不如一开始就不给。
     *
     * 空格处理与 [ChannelDao.searchByName] 一致：先去空格再比，否则「流浪地球2」
     * 搜不到「流浪地球 2」。
     */
    @Query(
        """
        SELECT m.* FROM media m
        INNER JOIN source s ON s.id = m.sourceId
        WHERE s.enabled = 1
          AND REPLACE(REPLACE(m.title, ' ', ''), char(12288), '') LIKE '%' || :keyword || '%' ESCAPE '\'
        ORDER BY m.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchByTitle(
        keyword: String,
        limit: Int,
    ): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media")
    suspend fun count(): Int
}
