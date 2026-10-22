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
    api(libs.okhttp)
}
