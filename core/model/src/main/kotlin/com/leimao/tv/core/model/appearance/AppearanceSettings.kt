package com.leimao.tv.core.model.appearance

/**
 * 主题（SET-02）。
 *
 * 默认深色是电视环境的惯例而不是审美偏好：客厅看片时浅色界面会把眼睛的暗适应顶掉，
 * 播放画面的黑位也会显得发灰。浅色留给「白天当闹钟/信息屏用」的那部分用户。
 */
enum class ThemeMode(
    val label: String,
    val description: String,
) {
    DARK("深色", "客厅环境的默认选择，播放画面黑位更准"),
    LIGHT("浅色", "白天光线强时更清楚"),
    SYSTEM("跟随系统", "跟随盒子的系统深色设置"),
    ;

    companion object {
        val DEFAULT = DARK

        /**
         * 从存储里读档位。
         *
         * 认不出来的值一律回落默认，**不抛异常**：枚举改过名之后，旧版本写在 DataStore 里的
         * 字符串还在，为一个显示设置崩掉整个设置页不值得。
         */
        fun fromStorage(raw: String?): ThemeMode = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * 全局字号缩放（SET-02）。
 *
 * 只能在整体上乘一个系数、不能逐页调小：RULES §6.3 的 16sp / 14sp 底线是按 1.0 定的，
 * 各页自己改字号就没法保证「任意页面都不低于底线」了。
 *
 * 上限停在 1.3 而不是继续放大：海报卡与英雄区的尺寸是按 1.0 写死的，再大就要改布局，
 * 那是另一件事。
 */
enum class FontScale(
    val label: String,
    val scale: Float,
) {
    SMALL("小", 0.9f),
    STANDARD("标准", 1.0f),
    LARGE("大", 1.15f),
    HUGE("特大", 1.3f),
    ;

    companion object {
        val DEFAULT = STANDARD

        fun fromStorage(raw: String?): FontScale = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * 最终用不用深色。
 *
 * 「跟随系统」这一档必须由调用方把系统当前状态传进来 —— 这个模块是纯 JVM（RULES §5.2），
 * 拿不到 `isSystemInDarkTheme()`。好处是三种档位 × 系统两种状态一共六种组合都能单测。
 */
fun shouldUseDarkTheme(
    mode: ThemeMode,
    systemInDark: Boolean,
): Boolean =
    when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemInDark
    }
