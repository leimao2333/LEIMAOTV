package com.leimao.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.leimao.tv.core.database.entity.VodFavoriteEntity

@Dao
interface VodFavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VodFavoriteEntity)

    @Query("SELECT * FROM vod_favorite WHERE sourceId = :sourceId AND sourceKey = :sourceKey")
    suspend fun find(
        sourceId: Long,
        sourceKey: String,
    ): VodFavoriteEntity?

    /**
     * 收藏列表，最近收藏的在前。
     *
     * 只认**启用中的源**：源停用后点进去也拉不到详情，摆在收藏页第一屏只会让人白点 ——
     * 与 `VodProgressDao.recent` 同一套口径。
     */
    @Query(
        """
        SELECT f.* FROM vod_favorite f
        INNER JOIN source s ON s.id = f.sourceId
        WHERE s.enabled = 1
        ORDER BY f.addedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun list(limit: Int): List<VodFavoriteEntity>

    @Query("DELETE FROM vod_favorite WHERE sourceId = :sourceId AND sourceKey = :sourceKey")
    suspend fun delete(
        sourceId: Long,
        sourceKey: String,
    )

    @Query("DELETE FROM vod_favorite WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: Long)
}
