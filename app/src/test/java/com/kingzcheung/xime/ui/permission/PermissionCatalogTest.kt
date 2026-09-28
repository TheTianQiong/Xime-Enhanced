package com.kingzcheung.xime.ui.permission

import com.kingzcheung.xime.plugin.core.model.PluginCapabilities
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.util.PermissionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限目录的纯逻辑：插件 → 所需权限的推导、`isNeeded` 的灰色态判定。
 *
 * 不涉及 Context / 偏好，插件启用状态由 lambda 注入。
 */
class PermissionCatalogTest {

    private fun plugin(
        id: String = "com.example.p",
        name: String = "示例插件",
        type: String = "unknown",
        declaredHosts: List<String> = emptyList(),
        allowCustomHosts: Boolean = false,
        declaredPermissions: List<String> = emptyList(),
        capabilities: PluginCapabilities? = null,
        enabled: Boolean = true,
    ) = PluginInfo(
        id = id,
        name = name,
        iconResId = 0,
        versionCode = 1,
        versionName = "1.0.0",
        path = "/tmp/$id",
        description = "",
        type = type,
        declaredHosts = declaredHosts,
        allowCustomHosts = allowCustomHosts,
        declaredPermissions = declaredPermissions,
        capabilities = capabilities,
        enabled = enabled,
    )

    private fun permissionsOf(p: PluginInfo) = PermissionCatalog.permissionsOf(p)

    // ── 能力推导 ──

    @Test
    fun `语音插件需要麦克风`() {
        val asr = plugin(type = "speech", declaredHosts = listOf("asr.example.com"))

        val perms = permissionsOf(asr)

        assertTrue(perms.contains(PermissionHelper.PERMISSION_RECORD_AUDIO))
    }

    @Test
    fun `声明域名的插件需要网络与网络状态`() {
        val p = plugin(declaredHosts = listOf("api.example.com"))

        val perms = permissionsOf(p)

        assertTrue(perms.contains(PermissionHelper.PERMISSION_INTERNET))
        assertTrue(perms.contains(PermissionHelper.PERMISSION_ACCESS_NETWORK_STATE))
    }

    @Test
    fun `接受自定义服务器地址的插件需要网络`() {
        val p = plugin(allowCustomHosts = true)

        assertTrue(permissionsOf(p).contains(PermissionHelper.PERMISSION_INTERNET))
    }

    @Test
    fun `同步与备份类插件即使未声明域名也需要网络`() {
        // 同步/备份协议（webdav/s3/ximed）的传输经网络，域名由用户配置
        val sync = plugin(type = "clipboard_sync")
        val backup = plugin(type = "backup")

        assertTrue(permissionsOf(sync).contains(PermissionHelper.PERMISSION_INTERNET))
        assertTrue(permissionsOf(backup).contains(PermissionHelper.PERMISSION_INTERNET))
    }

    @Test
    fun `离线语音插件不需要网络`() {
        val offlineAsr = plugin(
            type = "speech",
            capabilities = PluginCapabilities(
                speech = PluginCapabilities.SpeechCapabilities(requiresNetwork = false),
            ),
        )

        val perms = permissionsOf(offlineAsr)

        assertTrue("仍需麦克风", perms.contains(PermissionHelper.PERMISSION_RECORD_AUDIO))
        assertFalse("离线不应要求网络", perms.contains(PermissionHelper.PERMISSION_INTERNET))
    }

    @Test
    fun `纯本地工具插件不需要任何权限`() {
        // 如打字统计：无网络声明、有 events 但不涉及权限
        val local = plugin(type = "tool", capabilities = PluginCapabilities(events = listOf("input_changed")))

        assertTrue(permissionsOf(local).isEmpty())
    }

    @Test
    fun `表情插件不需要权限`() {
        assertTrue(permissionsOf(plugin(type = "emoji")).isEmpty())
    }

    // ── manifest 显式声明 ──

    @Test
    fun `显式声明短名可匹配`() {
        val p = plugin(declaredPermissions = listOf("RECEIVE_SMS"))

        assertTrue(permissionsOf(p).contains(PermissionHelper.PERMISSION_RECEIVE_SMS))
    }

    @Test
    fun `显式声明全名可匹配`() {
        val p = plugin(declaredPermissions = listOf("android.permission.RECEIVE_SMS"))

        assertTrue(permissionsOf(p).contains(PermissionHelper.PERMISSION_RECEIVE_SMS))
    }

    @Test
    fun `声明未知权限名被忽略`() {
        val p = plugin(declaredPermissions = listOf("NOT_A_REAL_PERMISSION", "CAMERA"))

        assertTrue("未在目录中的权限不参与展示", permissionsOf(p).isEmpty())
    }

    @Test
    fun `声明与推导可叠加`() {
        val p = plugin(
            type = "speech",
            declaredHosts = listOf("asr.example.com"),
            declaredPermissions = listOf("RECEIVE_SMS"),
        )

        val perms = permissionsOf(p)

        assertTrue(perms.contains(PermissionHelper.PERMISSION_RECORD_AUDIO))
        assertTrue(perms.contains(PermissionHelper.PERMISSION_INTERNET))
        assertTrue(perms.contains(PermissionHelper.PERMISSION_RECEIVE_SMS))
    }

    // ── 插件清单与排序 ──

    @Test
    fun `未启用的插件也计入使用列表`() {
        val installed = plugin(name = "网络插件", declaredHosts = listOf("a.example.com"), enabled = false)

        val usages = PermissionCatalog.pluginUsages(
            listOf(installed),
            PermissionHelper.PERMISSION_INTERNET,
            isEnabled = { false },
        )

        assertEquals(1, usages.size)
        assertFalse("应标记为未启用", usages[0].enabled)
    }

    @Test
    fun `已启用的插件排在前面`() {
        val a = plugin(id = "a", name = "AAA", declaredHosts = listOf("a.com"))
        val b = plugin(id = "b", name = "BBB", declaredHosts = listOf("b.com"))

        val usages = PermissionCatalog.pluginUsages(
            listOf(a, b),
            PermissionHelper.PERMISSION_INTERNET,
            isEnabled = { it.id == "b" },
        )

        assertEquals(listOf("BBB", "AAA"), usages.map { it.name })
    }

    @Test
    fun `插件名为空时回退到 id`() {
        val p = plugin(id = "com.example.anon", name = "", declaredHosts = listOf("a.com"))

        val usages = PermissionCatalog.pluginUsages(listOf(p), PermissionHelper.PERMISSION_INTERNET)

        assertEquals("com.example.anon", usages[0].name)
    }

    // ── 灰色态判定 ──

    @Test
    fun `无插件且无功能时该权限未被使用`() {
        val internet = PermissionCatalog.specOf(PermissionHelper.PERMISSION_INTERNET)!!

        assertFalse(
            "无联网插件时网络访问应为未使用（灰色）",
            PermissionCatalog.isNeeded(emptyList(), internet),
        )
    }

    @Test
    fun `有联网插件时网络访问被使用`() {
        val internet = PermissionCatalog.specOf(PermissionHelper.PERMISSION_INTERNET)!!
        val p = plugin(declaredHosts = listOf("a.com"))

        assertTrue(PermissionCatalog.isNeeded(listOf(p), internet))
    }

    @Test
    fun `有内置功能的权限始终被使用`() {
        // 麦克风/短信等有宿主功能，与是否安装插件无关
        listOf(
            PermissionHelper.PERMISSION_RECORD_AUDIO,
            PermissionHelper.PERMISSION_RECEIVE_SMS,
            PermissionHelper.PERMISSION_VIBRATE,
            PermissionHelper.PERMISSION_WAKE_LOCK,
            PermissionHelper.PERMISSION_ACCESS_WIFI_STATE,
        ).forEach { permission ->
            val spec = PermissionCatalog.specOf(permission)!!
            assertTrue("$permission 应有内置功能", PermissionCatalog.isNeeded(emptyList(), spec))
        }
    }

    // ── 目录自身不变量 ──

    @Test
    fun `目录覆盖清单中的全部权限且无重复`() {
        val listed = PermissionCatalog.ALL.map { it.permission }

        assertEquals("权限不应重复", listed.size, listed.distinct().size)
        // 与 AndroidManifest.xml 的 uses-permission 一致
        assertTrue(listed.contains(PermissionHelper.PERMISSION_RECORD_AUDIO))
        assertTrue(listed.contains(PermissionHelper.PERMISSION_RECEIVE_SMS))
        assertTrue(listed.contains(PermissionHelper.PERMISSION_VIBRATE))
        assertTrue(listed.contains(PermissionHelper.PERMISSION_INTERNET))
        assertTrue(listed.contains(PermissionHelper.PERMISSION_ACCESS_NETWORK_STATE))
        assertTrue(listed.contains(PermissionHelper.PERMISSION_ACCESS_WIFI_STATE))
        assertTrue(listed.contains(PermissionHelper.PERMISSION_WAKE_LOCK))
        assertEquals(7, listed.size)
    }

    @Test
    fun `每条权限都有标题与用途说明`() {
        PermissionCatalog.ALL.forEach { spec ->
            assertTrue("${spec.permission} 缺标题", spec.title.isNotBlank())
            assertTrue("${spec.permission} 缺说明", spec.description.isNotBlank())
        }
    }

    @Test
    fun `运行时权限判定`() {
        assertTrue(PermissionHelper.isRuntimePermission(PermissionHelper.PERMISSION_RECORD_AUDIO))
        assertTrue(PermissionHelper.isRuntimePermission(PermissionHelper.PERMISSION_RECEIVE_SMS))
        assertFalse(PermissionHelper.isRuntimePermission(PermissionHelper.PERMISSION_INTERNET))
        assertFalse(PermissionHelper.isRuntimePermission(PermissionHelper.PERMISSION_VIBRATE))
    }

    @Test
    fun `权限名匹配支持短名与全名`() {
        assertTrue(PermissionHelper.permissionMatches("RECEIVE_SMS", "android.permission.RECEIVE_SMS"))
        assertTrue(PermissionHelper.permissionMatches("android.permission.RECEIVE_SMS", "android.permission.RECEIVE_SMS"))
        assertTrue(PermissionHelper.permissionMatches("RECORD_AUDIO", "android.permission.RECORD_AUDIO"))
        assertFalse(PermissionHelper.permissionMatches("CAMERA", "android.permission.RECORD_AUDIO"))
        assertFalse(PermissionHelper.permissionMatches("", "android.permission.RECORD_AUDIO"))
    }
}
