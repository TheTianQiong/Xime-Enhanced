package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局包白名单与有效性判定。
 *
 * 这些输入直接来自第三方布局包，是「什么能落盘」的唯一闸门；`XimeIndexSource`
 * 本身是 object + Android Context 无法单测，判定逻辑因此抽到这里。
 */
class LayoutPackagePolicyTest {

    @Test
    fun `放行根级两个配置补丁与资源目录`() {
        assertTrue(LayoutPackagePolicy.isAllowedEntry("xime.custom.yaml"))
        assertTrue(LayoutPackagePolicy.isAllowedEntry("shuangpin_hints.custom.yaml"))
        assertTrue(LayoutPackagePolicy.isAllowedEntry("themes/bg.png"))
        assertTrue(LayoutPackagePolicy.isAllowedEntry("fonts/my-font.ttf"))
        assertTrue(LayoutPackagePolicy.isAllowedEntry("themes/sub/dir/img.webp"))
    }

    @Test
    fun `拒绝越界路径与未知文件`() {
        // 路径穿越：白名单自身就拦，不依赖调用方的 canonical 校验
        assertFalse(LayoutPackagePolicy.isAllowedEntry("../evil.yaml"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("themes/../../evil.yaml"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("themes/./a.png"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("/etc/passwd"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("themes\\..\\evil.yaml"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry(""))
        // 常见但不该由布局包提供的文件
        assertFalse(LayoutPackagePolicy.isAllowedEntry("default.custom.yaml"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("custom_phrase.txt"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("build/x.bin"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("installation.yaml"))
        // 前缀相似但不同目录
        assertFalse(LayoutPackagePolicy.isAllowedEntry("themes_extra/a.png"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("xime.custom.yaml.bak"))
    }

    @Test
    fun `配置文件必须在根级`() {
        // 子目录下的同名文件宿主读不到，等同无效包
        assertFalse(LayoutPackagePolicy.isAllowedEntry("config/xime.custom.yaml"))
        assertFalse(LayoutPackagePolicy.isAllowedEntry("sub/shuangpin_hints.custom.yaml"))
    }

    @Test
    fun `配置补丁判定只认两个根级文件`() {
        assertTrue(LayoutPackagePolicy.isConfigPatch("xime.custom.yaml"))
        assertTrue(LayoutPackagePolicy.isConfigPatch("shuangpin_hints.custom.yaml"))
        assertFalse(LayoutPackagePolicy.isConfigPatch("themes/bg.png"))
        assertFalse(LayoutPackagePolicy.isConfigPatch("fonts/a.ttf"))
    }

    @Test
    fun `纯主题字体包与空包视为无意义`() {
        assertFalse(LayoutPackagePolicy.isMeaningfulPackage(emptyList()))
        assertFalse(LayoutPackagePolicy.isMeaningfulPackage(listOf("themes/bg.png", "fonts/a.ttf")))
    }

    @Test
    fun `切换布局时算出未复用的残留文件`() {
        assertEquals(
            listOf("themes/old.png"),
            LayoutPackagePolicy.staleEntries(
                previous = listOf("xime.custom.yaml", "themes/old.png"),
                current = listOf("xime.custom.yaml", "shuangpin_hints.custom.yaml"),
            ),
        )
        // 从带提示表的布局切到不带提示表的布局：旧提示表必须被清掉
        assertEquals(
            listOf("shuangpin_hints.custom.yaml"),
            LayoutPackagePolicy.staleEntries(
                previous = listOf("xime.custom.yaml", "shuangpin_hints.custom.yaml"),
                current = listOf("xime.custom.yaml"),
            ),
        )
    }

    @Test
    fun `残留计算不误伤与去重`() {
        assertEquals(
            emptyList<String>(),
            LayoutPackagePolicy.staleEntries(emptyList(), listOf("xime.custom.yaml")),
        )
        assertEquals(
            emptyList<String>(),
            LayoutPackagePolicy.staleEntries(listOf("xime.custom.yaml"), listOf("xime.custom.yaml")),
        )
        // 清单里出现重复项时只报一次
        assertEquals(
            listOf("fonts/a.ttf"),
            LayoutPackagePolicy.staleEntries(
                previous = listOf("fonts/a.ttf", "fonts/a.ttf", "xime.custom.yaml"),
                current = listOf("xime.custom.yaml"),
            ),
        )
    }

    @Test
    fun `含任一配置补丁即为有效包`() {
        assertTrue(LayoutPackagePolicy.isMeaningfulPackage(listOf("xime.custom.yaml")))
        // 纯提示包：布局包可以只带双拼提示表，不碰用户的键面布局
        assertTrue(LayoutPackagePolicy.isMeaningfulPackage(listOf("shuangpin_hints.custom.yaml")))
        assertTrue(
            LayoutPackagePolicy.isMeaningfulPackage(
                listOf("themes/bg.png", "shuangpin_hints.custom.yaml")
            )
        )
    }
}
