plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leimao.tv.feature.iptv"
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

// feature 只依赖 core，绝不依赖另一个 feature（RULES §5.1）
dependencies {
    api(project(":core:model"))
    api(project(":core:player"))
    implementation(project(":core:designsystem"))
    // 播放画面是 View（PlayerView），Compose 侧用 AndroidView 挂上去
    implementation(libs.androidx.media3.ui)
    // 台标加载统一走 Coil，内存/磁盘上限在 TvApp 里统一定义（RULES §7）
    implementation(libs.coil.compose)
    // BackHandler：Android 13+ 的系统返回走 OnBackPressedDispatcher，
    // 预览按键里收不到 Key.Back，必须在这一层接管「先收控制条」
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
}
