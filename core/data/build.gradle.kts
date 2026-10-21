plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.leimao.tv.core.data"
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

// 这一层只做「编排」：抓取（core:network）+ 解析（core:network）+ 合并（core:model）
// + 落库（core:database）。不含 UI，也不含业务规则本身 —— 规则都在 core:model 的纯函数里
dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
    api(project(":core:network"))
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
