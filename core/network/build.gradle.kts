plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.leimao.tv.core.network"
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

    // 解析器本体只要 JDK；OkHttp 只用于真正的抓取（HttpClient 实现），
    // 解析器保持「输入字符串、输出频道」的纯函数形态，单测不需要网络
    api(libs.okhttp)

    testImplementation(libs.junit)
}
