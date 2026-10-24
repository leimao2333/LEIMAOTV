plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leimao.tv.feature.vod"
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

dependencies {
    api(project(":core:model"))
    implementation(project(":core:designsystem"))
    // 海报加载与直播台标共用 Coil，内存/磁盘上限在 TvApp 里统一配置（RULES §7）
    implementation(libs.coil.compose)

    // VodDetailState 里的倒序换算 / 副信息行都是纯函数，可在 JVM 上直接测
    testImplementation(libs.junit)
}
