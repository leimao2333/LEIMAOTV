package com.leimao.tv.core.version.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端 tag 与本地版本的比较（SET-07）。
 *
 * 关键用例是 `0.2.10.0 > 0.2.9.9` 与 `1.0.0.0 > 0.9.9.9`：**按数字比、不按字符串比**。
 * 字符串比较会让「0.2.10.0 比 0.2.9.9 小」，更新器于是永远劝不动用户升级 —— 这类 bug 不会报错，
 * 只会安静地不工作。
 */
class ReleaseVersionTest {
    @Test
    fun `同一版本相等`() {
        assertEquals(0, ReleaseVersion.compare("0.2.9.7", "0.2.9.7"))
    }

    @Test
    fun `修订位更大的更新`() {
        assertTrue(ReleaseVersion.compare("0.2.9.7", "0.2.9.6")!! > 0)
        assertTrue(ReleaseVersion.compare("0.2.9.6", "0.2.9.7")!! < 0)
    }

    @Test
    fun `按数值而不是按字典序比较`() {
        // 字典序会判成 "10" < "9"
        assertTrue(ReleaseVersion.compare("0.2.10.0", "0.2.9.9")!! > 0)
        assertTrue(ReleaseVersion.compare("1.0.0.0", "0.9.9.9")!! > 0)
        assertTrue(ReleaseVersion.compare("0.10.0.0", "0.9.9.9")!! > 0)
    }

    @Test
    fun `v 前缀大小写都不影响`() {
        assertEquals(0, ReleaseVersion.compare("v0.2.9.7", "0.2.9.7"))
        assertEquals(0, ReleaseVersion.compare("V0.2.9.7", "v0.2.9.7"))
    }

    @Test
    fun `段数不足时补零`() {
        assertEquals(0, ReleaseVersion.compare("1.0.0", "1.0.0.0"))
        assertEquals(0, ReleaseVersion.compare("v2", "2.0.0.0"))
    }

    @Test
    fun `读不懂的版本号给 null 而不是猜`() {
        assertNull(ReleaseVersion.compare("0.2.9.7-beta", "0.2.9.6"))
        assertNull(ReleaseVersion.compare("latest", "0.2.9.6"))
        assertNull(ReleaseVersion.compare("0.2.9.7", ""))
        assertNull(ReleaseVersion.compare("0.2.9.7.1.2", "0.2.9.6"))
        assertNull(ReleaseVersion.compare("0..9.7", "0.2.9.6"))
        assertNull(ReleaseVersion.compare("0.-1.9.7", "0.2.9.6"))
        assertNull(ReleaseVersion.compare("v", "0.2.9.6"))
    }

    @Test
    fun `isNewer 把读不懂的情况也如实传出去`() {
        assertEquals(true, ReleaseVersion.isNewer("0.2.9.7", "0.2.9.6"))
        assertEquals(false, ReleaseVersion.isNewer("0.2.9.6", "0.2.9.7"))
        assertNull(ReleaseVersion.isNewer("whatever", "0.2.9.7"))
    }

    @Test
    fun `允许自己超出段位纪律`() {
        // AppVersionNumber 的 init 会拒绝 minor = 10；比较器必须能处理这种手滑打出来的 tag
        assertEquals(10, ReleaseVersion.parse("0.2.10.0")!![2])
        assertEquals(200, ReleaseVersion.parse("0.2.9.200")!![3])
    }
}
