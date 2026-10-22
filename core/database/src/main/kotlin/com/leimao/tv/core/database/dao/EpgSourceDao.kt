package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.leimao.tv.core.database.entity.EpgSourceEntity

/**
 * 节目单订阅地址读写（LIVE-23）。
 *
 * 只有一个源的场景为主，所以接口按「单源」设计：取第一个启用源、按 url upsert。
 * 真出现多源需求时再扩成列表。
 */
@Dao
interface EpgSourceDao {
    @Query("SELECT * FROM epg_source LIMIT 1")
    suspend fun first(): EpgSourceEntity?

    @Query("SELECT * FROM epg_source")
    suspend fun all(): List<EpgSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: EpgSourceEntity): Long

    @Query("DELETE FROM epg_source WHERE id = :id")
    suspend fun delete(id: Long)
}
