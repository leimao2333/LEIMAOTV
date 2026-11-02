package com.leimao.tv.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「点了没反应」的两种形态（2026-11-02 修）。
 *
 * 背景：Android TV 上「导入本地文件」按下去毫无动静 —— 日志里能看到 Intent 被
 * `frameworkpackagestubs/.Stubs$DocumentsStub` 接走，那个 Activity 不显示界面、也不回调，
 * 于是 `launch()` 既不抛异常也没有结果，代码以为成功，用户以为坏了。
 */
class DocumentPickerAvailabilityTest {
    @Test
    fun `AOSP 的占位 stub 被判成没有选择器`() {
        assertTrue(documentPickerIsStub("frameworkpackagestubs", ".Stubs\$DocumentsStub"))
    }

    @Test
    fun `类名里带 Stub 的一律不认`() {
        assertTrue(documentPickerIsStub("com.vendor.rom", "com.vendor.rom.DocumentStub"))
    }

    @Test
    fun `真的 DocumentsUI 是可用的`() {
        assertFalse(documentPickerIsStub("com.google.android.documentsui", "com.android.documentsui.DocumentsActivity"))
        assertFalse(
            documentPickerIsStub(
                "com.android.externalstorage",
                "com.android.externalstorage.ExternalStorageProvider",
            ),
        )
    }

    @Test
    fun `第三方文件管理器不会被误判`() {
        assertFalse(documentPickerIsStub("com.mixplorer", "com.mixplorer.activities.BrowseActivity"))
    }

    @Test
    fun `空值不崩 也不当成 stub`() {
        assertFalse(documentPickerIsStub(null, null))
        assertFalse(documentPickerIsStub("", ""))
    }

    @Test
    fun `等够时间且窗口还有焦点 才判定静默失败`() {
        assertTrue(pickerStillSilent(awaitingSince = 1_000L, nowMs = 1_000L + PICKER_SILENT_MS, windowHasFocus = true))
    }

    @Test
    fun `窗口失焦说明选择器真的起来了 不算静默失败`() {
        assertFalse(pickerStillSilent(10_000L, 10_000L + PICKER_SILENT_MS, windowHasFocus = false))
    }

    @Test
    fun `还没等够时间就不下结论`() {
        assertFalse(pickerStillSilent(10_000L, 10_000L + 500L, windowHasFocus = true))
    }

    @Test
    fun `已经拿到结果 待确认标记被清 0 后不再报警`() {
        assertFalse(pickerStillSilent(awaitingSince = 0L, nowMs = 99_999L, windowHasFocus = true))
    }
}
