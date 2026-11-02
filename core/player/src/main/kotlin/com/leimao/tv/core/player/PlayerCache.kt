package com.leimao.tv.core.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 播放磁盘缓存（PLAY-15）。
 *
 * ## 为什么需要它
 *
 * 在这之前，播放链路上**一点磁盘缓存都没有**：每一秒画面都是现从网上拉的。
 * 网络抖一下就是一次卡顿，用户往回拖十秒也要重新下载 —— 这正是「视频播放太卡」的根因之一
 * （2026-11-02 反馈）。图片有 Coil 的磁盘缓存，视频反而没有，是很反常的取舍。
 *
 * ## 只给点播开
 *
 * 直播流（尤其是 HLS 直播）的 playlist 每次请求都在变，缓存命中反而会把旧的播放列表
 * 喂回内核 —— 表现是「换台后一直转圈」或者「卡在几分钟前」。所以直播继续直连，
 * 点播（电影/剧集）走缓存：同一集来回拖进度、切线路重看，命中的部分不用再下载。
 *
 * ## 为什么是单例
 *
 * Media3 的 [SimpleCache] 对同一个目录**只允许存在一个实例**（内部持有跨进程文件锁），
 * 建第二个会直接抛异常。而它的生命周期必须跟着进程走，不能跟着某个 Activity 或某次起播走。
 */
object PlayerCache {
    /** 缓存目录名。放在 cacheDir 下，系统「清除缓存」也能兜底回收 */
    const val DIR_NAME = "player"

    /**
     * 缓存上限 512MB。
     *
     * 电视盒子普遍只有 8GB 存储，缓存不是越大越好：一集 1080p 约 1~2GB 的源也有，
     * 512MB 够装下正在看的一集加上几集看过的片段，又能保证不会把用户的空间吃掉。
     * LRU 会在超限时自己淘汰最久没用的部分，不需要手动清理。
     */
    private const val MAX_BYTES = 512L * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    /** 取（或首次创建）缓存实例。同目录只能有一个，所以整个进程共用这一份 */
    @Synchronized
    fun get(context: Context): SimpleCache {
        instance?.let { return it }
        val dir = File(context.applicationContext.cacheDir, DIR_NAME)
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_BYTES)
        return SimpleCache(dir, evictor, StandaloneDatabaseProvider(context.applicationContext))
            .also { instance = it }
    }

    /**
     * 用缓存包一层上游数据源。
     *
     * [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR]：缓存目录读不了 / 写满了的时候
     * 直接回落成直连，而不是让整次播放失败 —— 缓存是加速手段，不该成为新的故障点。
     */
    fun wrap(
        context: Context,
        upstream: DataSource.Factory,
    ): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * 释放并清空（SET-05 的「清理缓存」）。
     *
     * 必须先 release 再删目录：SimpleCache 持有目录和数据库句柄，
     * 直接 `deleteRecursively` 会让它下次写入对不上账，进而把播放搞挂。
     */
    @Synchronized
    fun clear(context: Context) {
        val dir = File(context.applicationContext.cacheDir, DIR_NAME)
        runCatching { instance?.release() }
        instance = null
        runCatching { dir.deleteRecursively() }
    }

    /** 缓存占用的字节数（SET-05 的统计口径）。未创建时返回 0，不为了统计去建缓存 */
    fun sizeBytes(context: Context): Long {
        val dir = File(context.applicationContext.cacheDir, DIR_NAME)
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}
