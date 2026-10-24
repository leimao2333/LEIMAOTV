plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leimao.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leimao.tv"
        minSdk = 21
        targetSdk = 35

        // versionCode 按 RULES §10.2 公式算：0×1000000 + 2×10000 + 9×100 + 0 = 20900
        // versionName 必须与 AppVersion.CURRENT_VERSION 逐字符相等（SYS-10，单测钉住）
        versionCode = 20900
        versionName = "0.2.9.0"
    }

    buildTypes {
        debug {
            // 与正式包共存，方便同一台盒子上装着旧版对照行为
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }
}

dependencies {
    // :app 只做组装（RULES §5.5），所以这里拿到的都是「模块」，不写业务实现
    implementation(project(":core:model"))
    implementation(project(":core:version"))
    implementation(project(":core:database"))
    // :core:data 是「抓取 → 解析 → 合并 → 落库」的编排层，:app 只调它的入口
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:player"))

    implementation(project(":feature:iptv"))
    implementation(project(":feature:vod"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    // 台标图片的内存 / 磁盘上限在 LeimaoTvApplication 里统一配置（RULES §7）
    implementation(libs.coil.compose)
    // EPG 定时刷新（LIVE-23）：系统级周期任务，进程被杀也能唤醒
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
