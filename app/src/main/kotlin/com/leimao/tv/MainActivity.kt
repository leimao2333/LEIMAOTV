package com.leimao.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.leimao.tv.core.designsystem.theme.TvTheme
import com.leimao.tv.core.model.startup.StartDestination
import com.leimao.tv.debug.DebugSeed
import com.leimao.tv.ui.LeimaoTvApp

/**
 * 唯一的 Activity。它的职责只有三件：建容器、算落点、挂导航图。
 *
 * 落点必须在 NavHost 建起来**之前**算出来，因为 NavHost 的 startDestination 一旦确定就不该再改 ——
 * 这也是为什么这里是 `produceState` 而不是「先进主页再跳播放页」。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LeimaoTvApplication).container

        setContent {
            TvTheme {
                val startup by produceState<StartDestination?>(initialValue = null) {
                    // 调试包会在这里种一条 example.com 演示源（正式包为空实现，见 src/release）
                    DebugSeed.seedIfEmpty(container)
                    value = container.startupResolver.resolve()
                }

                when (val resolved = startup) {
                    null ->
                        Box(
                            // 与 windowBackground 同色：冷启动到首帧之间看起来是「黑着起来」，
                            // 不会闪一下白屏（§7 冷启动 2s 红线里，视觉抖动也算失败）
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                        )

                    else ->
                        LeimaoTvApp(
                            container = container,
                            startDestination = resolved,
                            onExitApp = { finish() },
                        )
                }
            }
        }
    }
}
