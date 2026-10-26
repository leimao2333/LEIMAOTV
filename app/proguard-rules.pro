# R8 keep 规则（RULES §4：每条都要写清为什么）

# libVLC（PLAY-02 软解兜底）：JNI 侧按**类名 + 方法签名**反射调用 Java 对象
# （`org.videolan.libvlc.LibVLC` / `MediaPlayer` / `IVLCVout$Callback` 等）。
# 一旦被 R8 改名或剥离，`libvlcjni.so` 的 JNI_OnLoad 会返回 JNI_ERR，
# libVLC 库自身随即 `System.exit(1)` —— 表现是**正式包启动即闪退**，
# 而 debug 包（不混淆）完全正常，所以只会在发版之后才暴露。
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.interfaces.** { *; }
-keep class org.videolan.libvlc.util.** { *; }

# VLC 的可选依赖（OpenGL / 媒体数据库等）未参与构建，缺类告警不是问题
-dontwarn org.videolan.**

# 一旦接入 Room 的反射路径或序列化库，把 keep 规则写在这里并注明原因。

# RULES §8.6：release 构建关闭 Log.d / Log.v。
#
# 不裁的话，播放诊断（`PlaybackDiagnostics`）这类常开日志会在正式包里持续刷 logcat：
# 既拖慢主线程（Log 是同步 IO），又可能把源地址带进用户日志（§2.5 要求打码）。
# Log.w / Log.e 保留 —— 真出问题时那些是唯一线索。
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
