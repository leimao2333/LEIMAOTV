package com.leimao.tv

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leimao.tv.core.data.EpgImportResult
import java.util.concurrent.TimeUnit

/**
 * EPG 定时刷新（LIVE-23）。
 *
 * 只抓「已配置的订阅地址」；没有地址时什么都不做直接成功退出 ——
 * 定时任务不该在用户没配节目单源的时候反复报错刷存在感。
 *
 * 失败不外抛（return success）：WorkManager 失败会按退避策略重试，
 * 但抓取失败（地址过期、网络抖动）重试意义有限，且失败原因已由 [EpgImporter.importUrl]
 * 写进数据库，用户打开源管理页就能看到「上次为什么没成功」。这里让任务「完成」，
 * 下一轮周期照常再试。
 */
class EpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LeimaoTvApplication).container
        val url = container.epgRepository.sourceUrl() ?: return Result.success()

        return when (container.epgImporter.importUrl(url)) {
            is EpgImportResult.Done -> Result.success()
            is EpgImportResult.Failed -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "epg_refresh"
        private const val REPEAT_HOURS = 24L

        /**
         * 注册或更新定时任务。用 [ExistingPeriodicWorkPolicy.UPDATE] 幂等：
         * 重复调用不会叠加出多份任务，改了周期也能覆盖旧的。
         *
         * 周期取 24 小时：节目单是「一天一更」的数据，更密没有可见收益，
         * 反而每次都拉一份几 MB 的文件。
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<EpgRefreshWorker>(REPEAT_HOURS, TimeUnit.HOURS)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
