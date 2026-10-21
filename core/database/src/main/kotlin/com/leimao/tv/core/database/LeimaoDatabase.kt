package com.leimao.tv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.leimao.tv.core.database.dao.ChannelDao
import com.leimao.tv.core.database.dao.SourceDao
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.database.entity.SourceEntity

/**
 * 数据库版本 1 只带 M0 用得到的表：source / channel。
 *
 * 刻意没把 program / media / favorite / history 一次建全 —— 表结构要跟着真正用它的功能
 * 一起长（M1 的 LIVE-14/15、M2 的 VOD-*），先建空表只会让第一版迁移变成猜谜。
 */
@Database(
    entities = [SourceEntity::class, ChannelEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LeimaoDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao

    abstract fun channelDao(): ChannelDao

    companion object {
        private const val DB_NAME = "leimao-tv.db"

        fun build(context: Context): LeimaoDatabase =
            Room.databaseBuilder(context.applicationContext, LeimaoDatabase::class.java, DB_NAME)
                // M0 还没有历史数据要保：结构与功能同步演进，等首个正式版后才关掉这行
                .fallbackToDestructiveMigration()
                .build()
    }
}
