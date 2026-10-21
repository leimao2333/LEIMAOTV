pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 仓库只在settings里声明一次：各模块自己加仓库会让「同一个依赖两个来源」的坑无解
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LEIMAOTV"

include(":app")

// core 是「能力」，feature 是「页面」。依赖方向只能是 :app → :feature:* → :core:*（RULES §5）
include(":core:model")
// RULES §10.3 把 CURRENT_VERSION 的唯一来源钉在 core/version/AppVersion.kt，所以它必须是独立模块
include(":core:version")
include(":core:database")
include(":core:network")
// 「抓取 → 解析 → 合并 → 落库」的编排只该有一份：源管理页的刷新与启动/定时刷新都调它，
// 放在 feature 里会变成两个 feature 各写一遍（RULES §5.1 禁止 feature 互相依赖）
include(":core:data")
include(":core:player")
include(":core:designsystem")

include(":feature:iptv")
include(":feature:vod")
include(":feature:search")
include(":feature:settings")
