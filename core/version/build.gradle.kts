plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 纯 Kotlin：版本号要被单测、被 :app 读、被发版脚本读，不能绑在 Android 运行时上。
// 本模块是 CURRENT_VERSION 的唯一来源（RULES §10.2、§10.3 第 2 项）
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
