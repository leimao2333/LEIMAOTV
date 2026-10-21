plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 纯 Kotlin，不依赖 Android SDK（RULES §5.2）：白名单与启动落点都是业务规则，
// 必须能在 JVM 上用普通单测跑，不该被 Android 运行时拖住
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
