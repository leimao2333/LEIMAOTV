plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leimao.tv.feature.settings"
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
    // 应用内更新日志要读 CURRENT_VERSION / CHANGELOG（SYS-03），这层依赖是它的正当理由
    api(project(":core:version"))
    implementation(project(":core:designsystem"))

    testImplementation(libs.junit)
}
