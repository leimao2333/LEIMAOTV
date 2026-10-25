# 磊毛TV（LEIMAOTV）

> 安卓电视端的 **IPTV 直播 + 影视点播** 二合一播放器：一个遥控器，一套界面，直播和影视不用再装两个 App。
> **打开就是画面**（直接起播上次的台），直播板块的搜索**只认六类内容**（直播频道、电影、动漫、电视剧、体育、综艺）。

<p align="left">
<img alt="platform" src="https://img.shields.io/badge/platform-Android%20TV-3DDC84?style=flat-square">
<img alt="minSdk" src="https://img.shields.io/badge/minSdk-21-blue?style=flat-square">
<img alt="license" src="https://img.shields.io/badge/license-Apache--2.0-4A90D9?style=flat-square">
<img alt="lang" src="https://img.shields.io/badge/Kotlin-Compose%20for%20TV-7F52FF?style=flat-square">
<img alt="status" src="https://img.shields.io/badge/status-M0%20skeleton%20done-3DDC84?style=flat-square">
</p>

---

## 为什么做这个

电视盒子上的现状是割裂的：

- **直播软件只管直播** —— 换台很顺，但想找一部片子就得退出去开另一个 App；
- **影视软件只管点播** —— 海报墙做得漂亮，但家里老人想看新闻联播得另找入口；
- 两边的**搜索互不相通** —— 在直播软件里搜「繁花」，什么都搜不到；在影视软件里搜「CCTV」，同样一片空白；
- **打开 App 先看到一屏主页** —— 电视最常见的使用是「坐下就看新闻」，结果要先过主页、再过分类、再过频道列表，
  三步才出画面；老人往往在这一步就放弃了；
- 遥控器操作普遍是「手机界面缩放到电视上」——焦点看不见、按钮点不中、返回键不知道会退到哪。

磊毛TV 想解决的就是这几件事：**打开即播、一个 App 两种模式、一套搜索两种结果、交互从头就按遥控器设计**。

## 三条设计主线

1. **打开就是画面，不是主页**
   冷启动直接起播上一次看的那个台，不经过主页、不经过列表、不经过「继续观看」。
   落点优先级：上次观看的频道 → 收藏第一个 → 频道列表第一个；只有「首次启动 / 没有源 / 频道还没解析出来」
   这三种情况才会看到别的页面（见 [RULES.md](RULES.md) §6.6）。
   主页不是入口，是**枢纽** —— 按返回才到，用来切模式、搜索、管源。

2. **模式切换是一个按键，不是一次退出**
   主页左侧导航栏顶部就是「直播 / 影视」模式开关，遥控器按 OK 切换。切换只换内容区，不动播放状态 ——
   正在看的直播可以挂着，切到影视里找片子，回来接着看（播放器实例在应用层，不随页面销毁）。

3. **直播模式下的搜索是「跨库」的，但有明确边界**
   在直播板块按搜索键，同时搜：本地频道库 + 影视库的**电影 / 动漫 / 电视剧 / 体育 / 综艺**五类。
   其余类型（短剧、纪录片、资讯、预告片等）**在直播板块搜不到** —— 这条是硬规则，写在代码层的过滤器里，
   UI 无法绕过，且有单测钉住（见 [RULES.md](RULES.md) §3）。

> 遥控器是唯一输入设备：所有功能必须能在只有方向键 / OK / 返回 / 菜单 / 数字键的情况下完成。
> 触摸只作为「顺手能点」的补充，不作为任何功能的唯一入口。

## 主页与交互参考

主页布局参考了四个开源项目的长处，各取一部分：

| 参考项目 | 借鉴的点 |
|---|---|
| [easy_tv_live](https://github.com/aiyakuaile/easy_tv_live)（极简TV） | 极简的播放页交互：上下键直接换台、数字键输入频道号、左右键切线路；远程配置一个 URL 拉全部设置 |
| [iptvnator](https://github.com/4gray/iptvnator) | 播放列表 / EPG / 收藏 / 观看历史的信息架构；M3U 与 Xtream 多源并存；全局搜索把直播与点播结果分区呈现 |
| [mytv-android](https://github.com/mytv-android/mytv-android) | 面向国内电视盒子的实用细节：多播放内核兜底、同名频道合并、台标与节目单的独立订阅、Material 3 的电视端观感 |
| [vbskycn/iptv](https://github.com/vbskycn/iptv) | 订阅源的组织与自更新思路（只借鉴工程做法，**本项目不内置任何源**） |

> 影视板块的搜索、详情、播放、AI 字幕等能力，直接迁自已发布的 [leimao-reader](https://github.com/leimao2333/leimao-reader)（同作者的手机端项目），保证两边内容库口径一致。

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| UI | **Jetpack Compose for TV**（`androidx.tv:tv-material`） | 电视端焦点体系原生支持；与手机端 Compose 代码可复用 |
| 播放 | **Media3 / ExoPlayer** | HLS(m3u8)、TS、MP4、FLV(可选) 一把梭；硬解优先 |
| 兜底内核 | **VLC(libvlc) 内置**（LGPL，动态链接、可替换）；IJK 只留接口不实现 | 部分盒子硬解不全，需要软解兜底（D-02 已定） |
| 数据 | Room + DataStore | 频道、分组、EPG、收藏、观看记录；设置走 DataStore |
| 网络 | OkHttp（沿用手机端 `HttpClient`） | 统一超时 / 重试 / UA / Referer 注入 |
| 后台 | WorkManager | 订阅源与 EPG 的定时刷新 |
| 语言 | Kotlin（协程 + Flow） | 与手机端一致 |
| 最低版本 | **minSdk 21（Android 5.0）** | 覆盖 5.0 以上全部设备；21~23 的通知 / 存储 / TV 清单差异单独写兼容分支（D-01 已定） |

## 模块结构（计划）

```
:app                    组装层：Activity / 导航 / 主题注入
:core:model             纯 Kotlin：Channel / ContentType / SearchScope / 白名单 / 启动落点判定
:core:version           版本号单一来源（AppVersion / AppVersionNumber）
:core:database          Room 实体与 DAO
:core:network           HTTP、M3U/XMLTV 解析、源适配器
:core:player            播放内核封装（Media3 统一接口，可换实现）
:core:designsystem      TV 主题、焦点组件（FocusableCard / FocusRing / 安全区）
:feature:iptv           频道列表、EPG、回看、订阅源管理
:feature:vod            影视：首页分区、详情、选集、播放、AI 字幕
:feature:search         统一搜索（含直播板块的类型白名单过滤）
:feature:settings       设置
```

依赖方向：`feature:* → core:*`，**feature 之间不互相依赖**（跨模块协作走 `core:model` + 导航事件）。

## 遥控器交互地图（目标态）

| 场景 | 按键 | 行为 |
|---|---|---|
| **启动** | —— | **直接起播上次的频道**（无源时才进引导页） |
| 主页 | ← → | 在左侧导航与内容区之间移动焦点 |
| 主页 | ↑ ↓ | 同列内移动；到顶/底不循环（避免误触） |
| 主页 | OK（导航栏「模式」） | 切换 直播 / 影视 |
| 主页 | 菜单键 | 呼出全局菜单（搜索 / 设置 / 源管理 / 退出） |
| 主页 | 返回 | 退出确认（不回到播放页，避免来回打转） |
| 直播播放 | ↑ ↓ | **直接换台**（不弹控制条） |
| 直播播放 | 数字键 | 输入频道号，2 秒无输入即跳转 |
| 直播播放 | ← → | 切换线路（多线路时）；单线路时快退/快进 |
| 直播播放 | OK | 呼出控制条（节目单 / 清晰度 / 收藏） |
| 直播播放 | 返回 | 控制条可见时先收起；否则进主页 |
| 影视播放 | ← → | 快退 / 快进 10 秒 |
| 影视播放 | 长按 ← → | 2× / 3× 倍速快进 |
| 影视播放 | 返回 | 逐级回退（选集 → 详情 → 主页） |

## 路线图

| 里程碑 | 内容 | 版本 |
|---|---|---|
| **M0** | 工程骨架：Compose for TV、启动路由（先落播放页）、导航与返回栈、主题与焦点组件、CI 可构建 | `0.1.1.0` |
| **M1** | IPTV 主链路：订阅源 → 频道列表 → 播放 → **冷启动直达** → 换台 → EPG | `0.2.0.0` |
| **M2** | 影视主链路：源适配器迁移、首页分区、详情、选集、播放 | `0.3.0.0` |
| **M3** | 统一搜索：直播板块跨库搜索 + 类型白名单过滤 + 单测 | `0.4.0.0` |
| **M4** | 打磨：回看、多线路合并、远程配置、性能与稳定性 | `1.0.0.0` |

详细条目见 [docs/功能清单.md](docs/功能清单.md)（需求台账），
逐条执行项见 [TODO.md](TODO.md)（按里程碑拆好的动手清单），
架构决策见 [docs/架构设计.md](docs/架构设计.md)，
发版与签名配置见 [docs/发布流程.md](docs/发布流程.md)。

## 版本规范

版本号为**四段式**：`顶端版本.大版本.小版本.修订`（如 `0.2.1.0`）。

| 段位 | 何时 +1 | 说明 |
|------|---------|------|
| 顶端版本 | 大版本走满十个数 | 进位时大版本归零 |
| 大版本 | 进下一个里程碑 | 进位时小版本归零 |
| **小版本** | **每发一版** | 里程碑内日常发版只动这一位（`0~9`） |
| 修订 | 同一小版本内打补丁 | 日常保持 `0` |

```
0.1.0.0 → 0.1.1.0 → 0.1.2.0 → … → 0.1.9.0     （同一里程碑内）
        → 0.2.0.0                               （进下一个里程碑）
        → … → 1.0.0.0                           （首个正式版）
```

`versionCode = 顶端版本 × 1000000 + 大版本 × 10000 + 小版本 × 100 + 修订`

发版时四处同步：`app/build.gradle.kts`（`versionCode` / `versionName`）、`AppVersion.CURRENT_VERSION`、
`CHANGELOG.md` 与 `AppVersion.CHANGELOG` 顶部条目、git tag `v顶端.大.小.修订`。
规则细节与算例见 [RULES.md §10](RULES.md)。**当前版本以 `AppVersion.CURRENT_VERSION` 为准**，
本文件与其它文档不硬编码版本号（必然过期）。

## 开发与构建

工程骨架已落地（M0）：冷启动直达播放页（占位）、主页枢纽、模式切换、返回栈、
焦点组件库、版本号单一来源都已跑通；**播放内核与真实内容仍待 M1**。

```bash
./gradlew :app:assembleDebug          # 调试包
./gradlew :app:assembleRelease        # 发布包（密钥就在仓库里，签得出；见 docs/发布流程.md）
./gradlew test                        # 全部单测（含纯 Kotlin 模块）
./gradlew ktlintCheck                 # 代码风格
```

发版不手工打 APK：推一个 `v<版本>` tag，GitHub Actions 会跑测试、签名、按
RULES §10.4 命名并把包挂到 Release 上（`.github/workflows/release.yml`）。

- 开发环境：JDK 17 + Android SDK 35（compileSdk 35 / minSdk 21 / targetSdk 35）+ Gradle 8.7
- 运行目标：Android TV 模拟器（1080p）或真机盒子（Android 5.0+）
- 调试包会种一条聚合测试源（`leimao2333/leimaotv-sources` 的 `dist/live.txt`）并走真实
  「抓取 → 解析 → 落库」链路拉取真实频道，用来走通「启动直达播放」这条链路；
  其代码只在 `app/src/debug`，**正式包不含任何演示数据**

### 模拟器联调

- **屏幕**：按 1920×1080 横屏跑。本机 AVD 是手机镜像，启动时要带 `-skin`，否则窗口是竖的：

  ```bash
  emulator -avd <avd名> -skin 1920x1080 -no-audio
  adb shell "wm size 1920x1080; wm density 320"
  adb shell "settings put system accelerometer_rotation 0; settings put system user_rotation 0"
  ```

- **键盘当遥控器**：双击 `scripts/启动按键映射.cmd`（NumLock 要打开）——
  小键盘 `4/8/2/6` = 左/上/下/右，`5` = OK，`0` = 菜单（全局菜单），`Esc` = 返回。
  只在模拟器窗口在前台时接管（按前台窗口的进程名判断），关掉那个黑窗口即停止；
  按键走 adb server 的本地 TCP，单键约 1ms，连按不发涩。

- **图标与横幅**：`scripts/gen-icons.py` 从一张正方形人物图生成各密度图标 + TV 横幅，
  换图重跑即可（源图要求写在脚本头部注释里）。

## 项目规则（迭代必读）

**所有改动都必须先读 [RULES.md](RULES.md)。** 它定义了内容合规红线、搜索白名单硬规则、模块边界、
TV 交互规范、性能红线、提交与发版流程 —— 与本文件冲突时，以 RULES.md 为准。

## 许可证

本项目以 **Apache-2.0** 授权，全文见 [LICENSE](LICENSE)。可以自由使用、修改、分发（含商用），
但需保留版权与许可声明，并在修改过的文件里注明变更。

唯一需要单独对待的是内置的 **VLC(libvlc)**：它是 **LGPL-2.1+**，本项目以**动态链接**方式使用其 `.so`，
不静态合并、不修改其源码，使用者可自行替换该库文件 —— 这样既满足 LGPL 的可替换要求，
也不影响主工程整体按 Apache-2.0 分发（见 [RULES.md](RULES.md) §4）。

## 免责声明

- 本项目是**播放器**，**不提供、不内置、不分发**任何直播源、影视源或内容；所有源均由用户自行添加。
- 项目不破解任何付费内容，不提供任何账号或订阅。
- 请仅用于学习与个人合法用途，遵守当地法律法规与内容版权。因使用第三方源产生的任何问题由使用者自行承担。
