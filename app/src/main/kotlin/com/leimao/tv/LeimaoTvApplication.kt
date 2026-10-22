package com.leimao.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 应用入口：只做两件事 —— 建容器、配图片加载器。
 *
 * 为什么要实现 [ImageLoaderFactory]：台标是列表里唯一的外部图片，如果不给 Coil 设上限，
 * 一个 5000 条频道的源能把内存吃满（RULES §7 的「常驻内存 ≤ 300MB」就这么被撑破的）。
 * 上限给得比较小，是因为台标本来就是缩略图尺寸，缓存太多没有收益。
 */
class LeimaoTvApplication :
    Application(),
    ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // EPG 定时刷新（LIVE-23）：幂等注册，用 UPDATE 覆盖旧周期。没有配置节目单
        // 订阅地址时 Worker 会空跑直接退出，不产生任何打扰。
        EpgRefreshWorker.schedule(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    // 台标解码后约 40×40dp，256 个足够覆盖一屏来回滚动
                    .maxSizePercent(0.08)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("logo_cache"))
                    // 32MB 上限：超出后按 LRU 淘汰，不会无限增长
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
