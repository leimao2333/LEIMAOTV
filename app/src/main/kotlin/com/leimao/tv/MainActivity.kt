package com.leimao.tv

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.leimao.tv.core.designsystem.theme.TvTheme
import com.leimao.tv.core.model.startup.StartDestination
import com.leimao.tv.debug.DebugSeed
import com.leimao.tv.ui.LeimaoTvApp

/**
 * 电脑键盘 → 遥控器的按键映射。
 *
 * 为什么需要它：在模拟器（或插了键盘的盒子）上验收时，方向键散落在主机键盘各处，
 * 每调一次界面都要来回摸鼠标。统一成「小键盘方向 + 小键盘回车 + ESC」，一只手就能走完全部交互，
 * 测试成本降下来，验收才会真的逐条做。
 *
 * | 键盘 | 等价遥控器键 |
 * |---|---|
 * | 小键盘 8 / 2 / 4 / 6 | ↑ / ↓ / ← / → |
 * | 小键盘 Enter | OK |
 * | ESC | 返回 |
 *
 * 只映射这几类，别的键原样放行 —— 输入订阅地址时还得靠它们。
 */
internal object KeyRemap {
    /** 返回等价的遥控器键码；不需要映射时返回 null */
    fun targetOf(keyCode: Int): Int? =
        when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_8 -> KeyEvent.KEYCODE_DPAD_UP
            KeyEvent.KEYCODE_NUMPAD_2 -> KeyEvent.KEYCODE_DPAD_DOWN
            KeyEvent.KEYCODE_NUMPAD_4 -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyEvent.KEYCODE_NUMPAD_6 -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_ESCAPE -> KeyEvent.KEYCODE_BACK
            else -> null
        }

    /**
     * 把一次按键换码。保留按下/抬起的成对关系 —— 只发 ACTION_DOWN 会让
     * `clickable` 收不到抬手事件，按钮变成「按了不算点」。
     */
    fun translate(event: KeyEvent, target: Int): KeyEvent =
        KeyEvent(event.downTime, event.eventTime, event.action, target, event.repeatCount)
}

/**
 * 唯一的 Activity。它的职责只有三件：建容器、算落点、挂导航图。
 *
 * 落点必须在 NavHost 建起来**之前**算出来，因为 NavHost 的 startDestination 一旦确定就不该再改 ——
 * 这也是为什么这里是 `produceState` 而不是「先进主页再跳播放页」。
 */
class MainActivity : ComponentActivity() {
    /**
     * 全局菜单（HOME-08）的信号量：菜单键按下一次就 +1。
     *
     * 为什么用信号量而不是布尔开关：菜单「是否打开」这个状态归 Compose 管（返回键收起、
     * 选中项收起都要改它），Activity 若也持有一份就会两份状态打架。信号量只表达
     * 「有人按了菜单键」这个事实，Compose 端观察到变化后自行 toggle，两边各管各的。
     */
    private val menuSignal = mutableIntStateOf(0)

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
                            menuSignal = menuSignal.intValue,
                            onExitApp = { finish() },
                        )
                }
            }
        }
    }

    /**
     * 键盘映射的落点：在 Activity 这一层换码，Compose 收到的事件就跟遥控器发的一模一样，
     * 所有页面（含 Dialog、输入框、BackHandler）都不用感知「这次是键盘还是遥控器」。
     *
     * ESC 特殊处理：直接走 [onBackPressedDispatcher]，这样能命中 Compose 的 BackHandler，
     * 效果等价于遥控器返回键（§6.5「控制条开着时返回先收控制条」也照样成立）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 排查「键盘按了没反应」时，logcat 里这两行就是全部证据：
        // 有 KEY 说明事件到了 Activity，有 REMAP 说明映射命中；两行都没有 = 事件根本没进来
        // （窗口没焦点，或输入法把键吃掉了）
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "KEY keyCode=${event.keyCode} scan=${event.scanCode} source=${event.source}")
        }

        // 菜单键在任意页面呼出全局菜单（HOME-08）：这里无条件拦截，事件不再下发给
        // 焦点系统 / 各页面的 onPreviewKeyEvent —— 播放页等地方的菜单键逻辑因此永远不命中。
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "MENU key -> toggle global menu")
                menuSignal.intValue += 1
            }
            return true
        }

        val target = KeyRemap.targetOf(event.keyCode) ?: return super.dispatchKeyEvent(event)
        Log.d(TAG, "REMAP ${event.keyCode} -> $target action=${event.action}")

        if (target == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }

        return super.dispatchKeyEvent(KeyRemap.translate(event, target))
    }

    private companion object {
        const val TAG = "KeyRemap"
    }
}
