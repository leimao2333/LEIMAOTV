plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.leimao.tv.core.player"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.android)

    // 默认内核（PLAY-01）。HLS 与 Progressive 两种封装覆盖了绝大多数直播源；
    // datasource-okhttp 让播放与解析共用同一个 OkHttp 实例（连接池复用）
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.exoplayer.hls)
    api(libs.androidx.media3.datasource.okhttp)
    api(libs.androidx.media3.ui)
    api(libs.okhttp)

    // 软解兜底内核（PLAY-02 / D-02）：Media3 硬解失败、线路用尽后接管。libvlc-all 是含全部
    // ABI 的 fat 包（约 80MB），真正的 ABI 拆包在 :app 的 splits 里做，最终各包只带自己的 so
    api(libs.libvlc.all)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
