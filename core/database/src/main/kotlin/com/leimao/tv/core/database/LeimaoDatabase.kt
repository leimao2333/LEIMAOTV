package com.leimao.tv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.leimao.tv.core.database.dao.ChannelDao
import com.leimao.tv.core.database.dao.ChannelLineDao
import com.leimao.tv.core.database.dao.FavoriteDao
import com.leimao.tv.core.database.dao.HistoryDao
import com.leimao.tv.core.database.dao.SourceDao
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.database.entity.ChannelLineEntity
import com.leimao.tv.core.database.entity.FavoriteEntity
import com.leimao.tv.core.database.entity.HistoryEntity
import com.leimao.tv.core.database.entity.SourceEntity

/**
 * 版本 2 补齐了 M1 真正要用到的表：
 *
 * | 表 | 为什么现在建 |
 * |---|---|
 * | `channel_line` | 多线路（LIVE-13）：播放失败要能切下一条 |
 * | `favorite` | 启动落点优先级 #2（§6.6）与收藏页 |
 * | `history` | 落点优先级 #1：最近一条观看记录 |
 *
 * M0 时刻意没把这些表一次建全 —— 表结构跟着真正用它的功能一起长，第一版迁移才不是猜谜。
 */
@Database(
    entities = [
        SourceEntity::class,
        ChannelEntity::class,
        ChannelLineEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LeimaoDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao

    abstract fun channelDao(): ChannelDao

    abstract fun channelLineDao(): ChannelLineDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun historyDao(): HistoryDao

    companion object {
        private const val DB_NAME = "leimao-tv.db"

        /**
         * 1 → 2：只做加法（加一列、加三张表、加索引）。
         *
         * 为什么这次不再靠破坏性重建：M1 起用户会往里放自己的源与收藏，重建等于让它们凭空消失。
         * 加法迁移写起来最省事，也没有搬数据的风险。
         */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `source` ADD COLUMN `lastRefreshNote` TEXT")
                    db.execSQL("ALTER TABLE `source` ADD COLUMN `basicUser` TEXT")
                    db.execSQL("ALTER TABLE `source` ADD COLUMN `basicPass` TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_groupName` ON `channel` (`groupName`)")
                    // 唯一键放宽：同名不同 tvg-id 的两个台要能共存
                    db.execSQL("DROP INDEX IF EXISTS `index_channel_sourceId_name`")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_channel_sourceId_name_tvgId` " +
                            "ON `channel` (`sourceId`, `name`, `tvgId`)",
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `channel_line` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`channelId` INTEGER NOT NULL, " +
                            "`url` TEXT NOT NULL, " +
                            "`label` TEXT, " +
                            "`position` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`channelId`) REFERENCES `channel`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_channel_line_channelId_url` " +
                            "ON `channel_line` (`channelId`, `url`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_channel_line_channelId` " +
                            "ON `channel_line` (`channelId`)",
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `favorite` (" +
                            "`channelId` INTEGER NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`channelId`), " +
                            "FOREIGN KEY(`channelId`) REFERENCES `channel`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_favorite_addedAt` ON `favorite` (`addedAt`)",
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `history` (" +
                            "`channelId` INTEGER NOT NULL, " +
                            "`watchedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`channelId`), " +
                            "FOREIGN KEY(`channelId`) REFERENCES `channel`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_history_watchedAt` ON `history` (`watchedAt`)",
                    )

                    // 给存量频道补一行线路：channel.url 是既有数据的唯一来源。
                    // 不补的话，升级用户的「换线路」永远只有一条，等于功能不存在
                    db.execSQL(
                        "INSERT OR IGNORE INTO `channel_line` (`channelId`, `url`, `label`, `position`) " +
                            "SELECT `id`, `url`, NULL, 0 FROM `channel`",
                    )
                }
            }

        fun build(context: Context): LeimaoDatabase =
            Room.databaseBuilder(context.applicationContext, LeimaoDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
