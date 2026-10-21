// 根构建脚本只声明插件版本，不 apply；具体模块自己 apply（避免把 Android 插件塞进纯 Kotlin 模块）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
}

// ktlint 在根上统一施加给所有子模块：格式规则只该有一份，
// 各模块自己配容易出现「A 模块 120 列、B 模块 100 列」这种没人说得清的状态（RULES §8.1）
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
