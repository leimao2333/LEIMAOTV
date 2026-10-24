import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 发版签名材料（SYS-07）。
 *
 * `keystore.properties` 与密钥文件都不进仓库（`.gitignore` 已排），首次配置看 `docs/发布流程.md`；
 * CI 里由工作流从 secrets 还原成同一个文件，所以本地与流水线走的是**同一条**代码路径，
 * 不存在「本地能签、CI 签不出来」这种只在发版当天才发现的差异。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.isFile) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }

fun signingValue(key: String): String? = keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile")?.let { rootProject.file(it) }

// 写了配置却指不到密钥，是最坏的一种「静默」：构建会愉快地产出一个 unsigned 包，
// 直到 CI 的 apksigner 那一步才炸，或者更糟 —— 被人手动传上去当正式包发。
if (keystorePropertiesFile.isFile && releaseStoreFile?.isFile != true) {
    throw GradleException(
        "keystore.properties 存在，但 storeFile 指向的密钥文件不存在（${signingValue("storeFile")}）。" +
            "要么补齐密钥，要么删掉 keystore.properties 再构建未签名包；别让它悄悄产出一个装不上的 APK。",
    )
}

android {
    namespace = "com.leimao.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leimao.tv"
        minSdk = 21
        targetSdk = 35

        // versionCode 按 RULES §10.2 公式算：0×1000000 + 2×10000 + 9×100 + 8 = 20908
        // versionName 必须与 AppVersion.CURRENT_VERSION 逐字符相等（SYS-10，单测钉住）
        versionCode = 20908
        versionName = "0.2.9.8"
    }

    signingConfigs {
        // 只有拿到密钥才声明 release 签名；没有就是「未签名构建」，CI 会拒发（RULES §10.4 第 4 条）
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
                // minSdk 21 意味着还要覆盖 Android 5/6 那些只认 v1 的老盒子；
                // v2 是 RULES §10.4 第 4 条要求 apksigner 必须看到的
                enableV1Signing = true
                enableV2Signing = true
            }
        }
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
            signingConfig = signingConfigs.findByName("release")
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
