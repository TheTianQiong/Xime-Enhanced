package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.settings.MarketLayout
import com.kingzcheung.xime.settings.MarketLayoutItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「布局插件」页行状态的判定。
 *
 * 页面本身要真机才看得到，但「什么状态能点应用、文案怎么说」是纯逻辑，
 * 且判定顺序直接影响可用性（例如已应用的布局即使不兼容也应允许重新应用）。
 */
class LayoutPluginsStateTest {

    @Test
    fun `默认可用状态`() {
        val state = layoutItemStateOf(item(), appliedLayoutId = "", appliedVersion = "")
        assertTrue(state.canApply)
        assertEquals("应用", state.actionLabel)
        assertEquals("可应用", state.label)
    }

    @Test
    fun `已应用的布局允许重新应用并显示版本`() {
        val state = layoutItemStateOf(
            item(id = "demo", appliedVersion = "1.0.0"),
            appliedLayoutId = "demo",
            appliedVersion = "1.2.0",
        )
        assertTrue(state.canApply)
        assertEquals("重新应用", state.actionLabel)
        assertEquals("已应用（1.2.0）", state.label)
    }

    @Test
    fun `已应用但版本为空时不显示空括号`() {
        val state = layoutItemStateOf(item(id = "demo"), appliedLayoutId = "demo", appliedVersion = "")
        assertEquals("已应用", state.label)
    }

    @Test
    fun `不兼容时禁用应用并提示所需版本`() {
        val state = layoutItemStateOf(
            item(compatible = false, minAppVersion = "3.2.0"),
            appliedLayoutId = "",
            appliedVersion = "",
        )
        assertFalse(state.canApply)
        assertTrue(state.label.contains("3.2.0"))
    }

    @Test
    fun `缺依赖方案时禁用应用并列出方案`() {
        val state = layoutItemStateOf(
            item(schemeReady = false, requiresSchemes = listOf("double_pinyin_flypy", "wubi86")),
            appliedLayoutId = "",
            appliedVersion = "",
        )
        assertFalse(state.canApply)
        assertTrue(state.label.contains("double_pinyin_flypy"))
        assertTrue(state.label.contains("wubi86"))
    }

    @Test
    fun `有新版本时提示更新`() {
        val state = layoutItemStateOf(
            item(installedVersion = "1.0.0", currentVersion = "1.1.0"),
            appliedLayoutId = "other", // 不是当前应用的那个布局
            appliedVersion = "0.9.0",
        )
        assertTrue(state.canApply)
        assertEquals("更新", state.actionLabel)
        assertEquals("可更新到 1.1.0", state.label)
    }

    @Test
    fun `已应用优先于不兼容与缺依赖`() {
        // 已应用的布局可能因 App 降级等原因显示不兼容，此时仍要允许重新应用/查看，
        // 否则用户在被破坏的状态下无法靠重装恢复
        val state = layoutItemStateOf(
            item(id = "demo", compatible = false, schemeReady = false, minAppVersion = "9.9.9"),
            appliedLayoutId = "demo",
            appliedVersion = "1.0.0",
        )
        assertTrue(state.canApply)
        assertEquals("重新应用", state.actionLabel)
    }

    @Test
    fun `不兼容优先于缺依赖`() {
        val state = layoutItemStateOf(
            item(compatible = false, minAppVersion = "3.2.0", schemeReady = false, requiresSchemes = listOf("x")),
            appliedLayoutId = "",
            appliedVersion = "",
        )
        assertTrue("版本不足应先于缺依赖提示", state.label.contains("3.2.0"))
    }

    private fun item(
        id: String = "demo",
        compatible: Boolean = true,
        minAppVersion: String = "3.0.0",
        installedVersion: String? = null,
        currentVersion: String = "1.0.0",
        schemeReady: Boolean = true,
        requiresSchemes: List<String> = emptyList(),
        appliedVersion: String = "",
    ): MarketLayoutItem = MarketLayoutItem(
        layout = MarketLayout(
            id = id,
            name = id,
            currentVersion = currentVersion,
            requiresSchemes = requiresSchemes,
        ),
        compatible = compatible,
        minAppVersion = minAppVersion,
        installedVersion = installedVersion ?: appliedVersion.ifEmpty { null },
        schemeReady = schemeReady,
    )
}
