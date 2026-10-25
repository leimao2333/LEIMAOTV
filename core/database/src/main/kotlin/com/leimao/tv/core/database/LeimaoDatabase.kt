package com.leimao.tv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.leimao.tv.core.database.dao.ChannelDao
import com.leimao.tv.core.database.dao.ChannelLineDao
import com.leimao.tv.core.database.dao.ChannelLineQualityDao
import com.leimao.tv.core.database.dao.EpgDao
import com.leimao.tv.core.database.dao.EpgSourceDao
import com.leimao.tv.core.database.dao.FavoriteDao
import com.leimao.tv.core.database.dao.HistoryDao
import com.leimao.tv.core.database.dao.MediaDao
import com.leimao.tv.core.database.dao.SourceDao
import com.leimao.tv.core.database.dao.VodFavoriteDao
import com.leimao.tv.core.database.dao.VodProgressDao
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.database.entity.ChannelLineEntity
import com.leimao.tv.core.database.entity.ChannelLineQualityEntity
import com.leimao.tv.core.database.entity.EpgChannelEntity
import com.leimao.tv.core.database.entity.EpgProgrammeEntity
import com.leimao.tv.core.database.entity.EpgSourceEntity
import com.leimao.tv.core.database.entity.FavoriteEntity
import com.leimao.tv.core.database.entity.HistoryEntity
import com.leimao.tv.core.database.entity.MediaEntity
import com.leimao.tv.core.database.entity.SourceEntity
import com.leimao.tv.core.database.entity.VodFavoriteEntity
import com.leimao.tv.core.database.entity.VodProgressEntity

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
        EpgChannelEntity::class,
        EpgProgrammeEntity::class,
        EpgSourceEntity::class,
        MediaEntity::class,
        VodProgressEntity::class,
        VodFavoriteEntity::class,
        ChannelLineQualityEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
abstract class LeimaoDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao

    abstract fun channelDao(): ChannelDao

    abstract fun channelLineDao(): ChannelLineDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun historyDao(): HistoryDao

    abstract fun epgDao(): EpgDao

    abstract fun epgSourceDao(): EpgSourceDao

    abstract fun mediaDao(): MediaDao

    abstract fun vodProgressDao(): VodProgressDao

    abstract fun vodFavoriteDao(): VodFavoriteDao

    abstract fun channelLineQualityDao(): ChannelLineQualityDao

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

        /**
         * 2 → 3：加节目单两张表（LIVE-20）。
         *
         * 仍然是纯加法：节目单是「随时可再导入一次」的派生数据，迁移里不需要也不会去搬它。
         * 用户已有的源 / 频道 / 收藏 / 历史一行都不动。
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `epg_channel` (" +
                            "`id` TEXT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`normalizedName` TEXT NOT NULL, " +
                            "PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_epg_channel_normalizedName` " +
                            "ON `epg_channel` (`normalizedName`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `epg_programme` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`epgChannelId` TEXT NOT NULL, " +
                            "`startAt` INTEGER NOT NULL, " +
                            "`endAt` INTEGER NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`description` TEXT)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_epg_programme_epgChannelId` " +
                            "ON `epg_programme` (`epgChannelId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_epg_programme_startAt` " +
                            "ON `epg_programme` (`startAt`)",
                    )
                }
            }

        /**
         * 3 → 4：加节目单订阅地址表（LIVE-23）。
         *
         * 仍是纯加法：节目单的频道/节目表不动，只多一张「这个 XMLTV 从哪个 URL 抓」的表，
         * 供定时刷新用。已有数据一行都不搬。
         */
        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `epg_source` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`url` TEXT NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "`lastRefreshNote` TEXT)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_source_url` " +
                            "ON `epg_source` (`url`)",
                    )
                }
            }

        /**
         * 4 → 5：给源加 sortOrder 列（SET-03 上移/下移排序）。
         *
         * 纯加法 + 回填：既有行按 id 顺序补成 0,1,2…，保证升级后顺序与之前一致。
         * 源 / 频道 / 收藏 / 历史一行都不搬。
         */
        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `source` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                    // 回填：直接用 id 作排序值，与旧的「按 id 排序」语义完全一致（id 单调递增）。
                    // 新源追加时走 maxSortOrder()+1，仍能排到末尾。
                    db.execSQL("UPDATE `source` SET `sortOrder` = `id`")
                }
            }

        /**
         * 5 → 6：加影视条目表（VOD-03）。
         *
         * 仍是纯加法。这张表是**可再生的缓存** —— 用户真正的资产（源 / 频道 / 收藏 / 历史）
         * 一行都不动，升级后首页自动重新抓一遍就填满了。
         */
        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `media` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`sourceId` INTEGER NOT NULL, " +
                            "`sourceKey` TEXT NOT NULL, " +
                            "`type` TEXT NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`poster` TEXT, " +
                            "`year` INTEGER, " +
                            "`area` TEXT, " +
                            "`remarks` TEXT, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "`fetchedAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_sourceId_sourceKey` " +
                            "ON `media` (`sourceId`, `sourceKey`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_type_updatedAt` " +
                            "ON `media` (`type`, `updatedAt`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_sourceId_updatedAt` " +
                            "ON `media` (`sourceId`, `updatedAt`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_media_updatedAt` " +
                            "ON `media` (`updatedAt`)",
                    )
                }
            }

        /**
         * 6 → 7：加看片进度表（VOD-06）。
         *
         * 纯加法。**没有外键指向 `media`**：`media` 每次刷新都整类删了重插，
         * 外键 CASCADE 会让升级后的第一次刷新把用户所有进度清空 —— 见
         * `VodProgressEntity` 的注释。
         */
        val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `vod_progress` (" +
                            "`sourceId` INTEGER NOT NULL, " +
                            "`sourceKey` TEXT NOT NULL, " +
                            "`lineIndex` INTEGER NOT NULL, " +
                            "`episodeIndex` INTEGER NOT NULL, " +
                            "`positionMs` INTEGER NOT NULL, " +
                            "`durationMs` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`poster` TEXT, " +
                            "PRIMARY KEY(`sourceId`, `sourceKey`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_vod_progress_updatedAt` " +
                            "ON `vod_progress` (`updatedAt`)",
                    )
                }
            }

        /**
         * 7 → 8：加影视收藏表 + 进度表加「看完」标记（VOD-07）。
         *
         * 两处都刻意是加法：
         * - `vod_favorite` 是新表，存量数据一行不动；
         * - `vod_progress` 加一列 `finished`，默认 0 = 没看完 —— 升级后所有既有进度
         *   照旧出现在「继续观看」，不会因为多了个字段就集体变成「看过」。
         *
         * 「看完」从**删行**改成**置标记**，是因为 VOD-07 要把看完的片子当「看过的」
         * 历史用。删掉之后，用户想找上周看完那部片只能凭记忆搜片名。
         */
        val MIGRATION_7_8: Migration =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `vod_progress` ADD COLUMN `finished` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `vod_favorite` (" +
                            "`sourceId` INTEGER NOT NULL, " +
                            "`sourceKey` TEXT NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`poster` TEXT, " +
                            "`type` TEXT NOT NULL, " +
                            "`year` INTEGER, " +
                            "`area` TEXT, " +
                            "`remarks` TEXT, " +
                            "`addedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`sourceId`, `sourceKey`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_vod_favorite_addedAt` " +
                            "ON `vod_favorite` (`addedAt`)",
                    )
                }
            }

        /**
         * 8 → 9：`media` 加标签列（VOD-08）。
         *
         * 纯加法。老行 `tags = NULL`，界面上的标签行自动为空 —— 等下一轮刷新把
         * `vod_class` 抓回来就填上了，不需要在迁移里做回填（回填也无从谈起：
         * 标签来自网络，库里原本没有这份数据）。
         */
        val MIGRATION_8_9: Migration =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `media` ADD COLUMN `tags` TEXT")
                }
            }

        /**
         * 9 → 10：`source` 加格式列（VOD moontv 聚合）。
         *
         * 纯加法。老行 `format = 'applecms'`（默认值），直播源与老影视源都落这个值，
         * 只有新导入的 moontv 加密订阅会写 `'moontv'`。
         */
        val MIGRATION_9_10: Migration =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `source` ADD COLUMN `format` TEXT NOT NULL DEFAULT 'applecms'")
                }
            }

        /**
         * 10 → 11：新建频道线路质量表（LIVE-13 智能调度）。
         *
         * 纯加法。复合主键 `channelId + url`，记录每条线路的成功 / 失败次数与最近时间戳。
         * 老用户升级后表是空的，第一次播某频道走「无历史 → 实时探测」，随后逐步积累。
         */
        val MIGRATION_10_11: Migration =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `channel_line_quality` (
                            `channelId` INTEGER NOT NULL,
                            `url` TEXT NOT NULL,
                            `successCount` INTEGER NOT NULL,
                            `failCount` INTEGER NOT NULL,
                            `lastSeenAt` INTEGER NOT NULL,
                            PRIMARY KEY(`channelId`, `url`)
                        )
                        """.trimIndent(),
                    )
                }
            }

        /**
         * 11 → 12：频道表加三列，承载回看能力（LIVE-24）。
         *
         * 只做加法，老数据不用搬：三列默认值就是「不支持回看」，
         * 升级上来的用户第一次刷新源时才会拿到能力（那正是应该拿到的时候）。
         */
        val MIGRATION_11_12: Migration =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `channel` ADD COLUMN `catchupMode` TEXT")
                    db.execSQL("ALTER TABLE `channel` ADD COLUMN `catchupTemplate` TEXT")
                    db.execSQL(
                        "ALTER TABLE `channel` ADD COLUMN `catchupDays` INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        fun build(context: Context): LeimaoDatabase =
            Room.databaseBuilder(context.applicationContext, LeimaoDatabase::class.java, DB_NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                )
                .build()
    }
}
