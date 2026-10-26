plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leimao.tv.core.designsystem"
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
    buildFeatures {
        compose = true
    }
}

// api 而非 implementation：凡是「UI 契约」都该跟着本模块一起传出去，
// 否则每个 feature 都得自己再写一遍 tv-material / compose 依赖（RULES §4 禁止散落的版本号）
dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.tv.material)
    // 海报 / 台标的统一入口（RemoteImage）：图片加载属于「UI 契约」，跟着本模块一起传出去
    api(libs.coil.compose)

    testImplementation(libs.junit)
}
