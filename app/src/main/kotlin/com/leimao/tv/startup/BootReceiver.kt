package com.leimao.tv.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.leimao.tv.MainActivity
import com.leimao.tv.data.DataStoreSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 开机自启动（SET-08）。
 *
 * 只做一件事：开关开着就把应用拉起来。拉起后落在哪**不由这里决定** ——
 * MainActivity 走 StartupResolver（§6.6）的正常落点判定（合规 → 添加源 → 播放页），
 * 和用户从桌面点图标进来是同一条路。这里绝不自己构造「直达播放页」的 Intent：
 * 绕过落点判定意味着绕过合规确认，那是红线。
 *
 * 默认不生效：开关没开时这个接收器拿到广播后第一件事就是 return，
 * 用户不开开关，这个组件存在与否对他没有任何可感知差异。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val enabled =
            runBlocking {
                // 接收器里同步读一次设置：只读一个布尔值，毫秒级；
                // 换成异步的话进程可能在协程跑起来之前就被回收了
                runCatching { DataStoreSettingsStore(context).bootAutostart.first() }.getOrDefault(false)
            }
        if (!enabled) return

        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
