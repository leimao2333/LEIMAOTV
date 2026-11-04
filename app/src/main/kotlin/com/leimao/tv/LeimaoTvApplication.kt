package com.leimao.tv

import android.app.Application
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import com.leimao.tv.core.data.RefreshInterval
import com.leimao.tv.core.player.MemoryLevel
import com.leimao.tv.data.CacheManager
import com.leimao.tv.data.CrashArchive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        // 崩溃本地留档（SYS-04）：必须最先装 —— 装晚一步，容器构造期间的崩溃就漏掉了
        CrashArchive.install(this)
        container = AppContainer(this)
        // EPG 定时刷新（LIVE-23）：幂等注册，用 UPDATE 覆盖旧周期。没有配置节目单
        // 订阅地址时 Worker 会空跑直接退出，不产生任何打扰。
        EpgRefreshWorker.schedule(this)
        // 直播源定时刷新（LIVE-07）：按设置里的周期注册（默认 24h，关闭则取消）。
        // 启动还额外异步刷一次，保证列表不过期 —— 但绝不能阻塞冷启动落点（§6.6），
        // 所以放 applicationScope 里 fire-and-forget，落点判定照常走。
        scheduleSourceRefresh()
        kickOffInitialRefresh()
    }

    private fun scheduleSourceRefresh() {
        // 读刷新周期是一次磁盘 IO，**不能**放在 onCreate 的同步路径上（RULES §7：主线程禁止
        // 任何磁盘操作，冷启动预算也只有 2s）。改成后台读、读完再注册定时任务：
        // 定时刷新早注册晚注册一秒无所谓，冷启动那 2s 才是硬指标。
        container.applicationScope.launch {
            val interval =
                runCatching { container.settings.refreshInterval.first() }
                    .getOrElse { RefreshInterval.DEFAULT }
            SourceRefreshWorker.schedule(this@LeimaoTvApplication, interval)
        }
    }

    private fun kickOffInitialRefresh() {
        container.applicationScope.launch {
            // 首启无源时 refreshAll 返回空列表，无副作用；有源则后台补齐，
            // 播放页已经在播「上次的台」，不会被这次刷新打断。
            container.sourceRefresher.refreshAll()
        }
    }

    override fun newImageLoader(): ImageLoader {
        // 内存档位：2GB 盒子（堆上限常见 192MB）上，8% 的图片缓存就是 15MB，
        // 而 4K 分辨率下一屏海报的真实像素是 1080p 的四倍 —— 缓存还没满，
        // 解码过程中的瞬时峰值就已经很危险了。低配机压到 4%，宁可多重解码几次
        val level = MemoryLevel.of(this)
        return ImageLoader.Builder(this)
            // 走 AppContainer 的 OkHttp：用户配了代理 / 超时后，海报请求与接口同路。
            // 不接的话 Coil 用自己的默认 client，隔离网络下海报全都拉不到（2026-10-24 实测）
            .okHttpClient(container.imageHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    // 台标解码后约 40×40dp，256 个足够覆盖一屏来回滚动
                    .maxSizePercent(if (level.constrained) 0.04 else 0.08)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(CacheManager.IMAGE_CACHE_DIR))
                    // 32MB 上限：超出后按 LRU 淘汰，不会无限增长
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            // 只在 debug 打日志：海报/台标拉不到时，界面上只有一个灰盒子，
            // 而原因（DNS / 403 / 解码失败）只有这里看得到
            .logger(if (BuildConfig.DEBUG) coil.util.DebugLogger() else null)
            .build()
    }

    /**
     * 内存吃紧时先把图片缓存整个丢掉（低配机防闪退）。
     *
     * 图片是本应用里**唯一可以随时重新拿回来**的大块内存：丢了最多重下一张海报，
     * 而播放器缓冲丢了会直接卡一下。所以内存一紧，先牺牲它。
     *
     * 阈值取 [TRIM_MEMORY_RUNNING_LOW]：比它更轻的两档（RUNNING_MODERATE）说明
     * 后台还有别的应用在跑，那时丢缓存属于白丢 —— 用户切回来还得重下。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching { imageLoader.memoryCache?.clear() }
        }
    }
}
