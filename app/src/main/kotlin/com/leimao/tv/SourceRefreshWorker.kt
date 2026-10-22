package com.leimao.tv

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leimao.tv.core.data.RefreshInterval
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 直播源定时刷新（LIVE-07）。
 *
 * 与 [EpgRefreshWorker] 各管一摊：那个抓「节目单订阅地址」，这个刷「直播源列表」。
 * 直播源是用户换台的依据，过期了会看到一堆播不了的台，所以周期比节目单更密。
 *
 * 失败不外抛（return success）：抓取失败的原因已由 [SourceRefresher] 写进 `lastRefreshNote`，
 * 用户打开源管理页能看到「上次为什么没成功」，重试意义有限，下一轮周期照常再试。
 *
 * 周期由设置（[RefreshInterval]）决定；用户在设置页改成「关闭」时，[schedule] 会取消任务。
 */
class SourceRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LeimaoTvApplication).container

        // 周期可在任务运行前被用户改成「关闭」：这里再读一次，避免「取消了还刷」
        val interval = container.settings.refreshInterval.first()
        if (interval == RefreshInterval.OFF) return Result.success()

        // refreshAll 本身是「尽力而为」：单个源失败不中断整批，结果已各自落库
        container.sourceRefresher.refreshAll()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "source_refresh"

        /**
         * 按 [interval] 注册 / 更新 / 取消定时任务，幂等。
         *
         * 周期取 6~24 小时：直播源不是分钟级变化的数据，更密只会徒增抓取。
         * [RefreshInterval.OFF] 时取消任务；其余用 [ExistingPeriodicWorkPolicy.UPDATE]
         * 覆盖旧周期，重复调用不会叠加多份。
         */
        fun schedule(context: Context, interval: RefreshInterval) {
            val workManager = WorkManager.getInstance(context)
            val hours = interval.hours
            if (hours == null) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val request =
                PeriodicWorkRequestBuilder<SourceRefreshWorker>(hours, TimeUnit.HOURS)
                    .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
