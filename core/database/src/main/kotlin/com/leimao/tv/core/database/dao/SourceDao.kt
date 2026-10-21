package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.leimao.tv.core.database.entity.SourceEntity

@Dao
interface SourceDao {
    @Query("SELECT COUNT(*) FROM source WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Query("SELECT COUNT(*) FROM source")
    suspend fun countAll(): Int

    @Query("SELECT * FROM source ORDER BY id ASC")
    suspend fun listAll(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE enabled = 1 ORDER BY id ASC")
    suspend fun listEnabled(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun findById(id: Long): SourceEntity?

    /** 同 ChannelDao：REPLACE 会换主键，连带 cascade 删掉该源下所有频道与收藏 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("UPDATE source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE source SET updatedAt = :updatedAt, lastRefreshNote = :note WHERE id = :id")
    suspend fun markRefreshed(id: Long, updatedAt: Long, note: String?)

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun deleteById(id: Long)
}
