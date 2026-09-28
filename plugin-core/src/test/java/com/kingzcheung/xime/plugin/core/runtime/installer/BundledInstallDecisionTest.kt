package com.kingzcheung.xime.plugin.core.runtime.installer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内置插件的安装决策。
 *
 * 内置插件随应用每次启动都会尝试安装，因此这里锁死「非破坏性」契约：
 * 只有内置版本**严格更高**才覆盖升级，否则一律跳过——
 * 否则每次启动都会把用户已装的插件（及其配置）重置回随包版本。
 */
class BundledInstallDecisionTest {

    private fun decide(bundled: String, installed: String?) =
        InstallerManager.decideBundledInstall(bundled, installed)

    @Test
    fun `未安装时执行安装`() {
        assertEquals(
            InstallerManager.BundledInstallAction.INSTALL,
            decide(bundled = "0.1.0", installed = null),
        )
    }

    @Test
    fun `内置版本更高时升级`() {
        assertEquals(
            InstallerManager.BundledInstallAction.UPGRADE,
            decide(bundled = "0.2.0", installed = "0.1.0"),
        )
        assertEquals(
            InstallerManager.BundledInstallAction.UPGRADE,
            decide(bundled = "1.0.0", installed = "0.9.9"),
        )
        assertEquals(
            InstallerManager.BundledInstallAction.UPGRADE,
            decide(bundled = "0.1.1", installed = "0.1.0"),
        )
    }

    @Test
    fun `版本相同时跳过`() {
        assertEquals(
            InstallerManager.BundledInstallAction.SKIP,
            decide(bundled = "0.1.0", installed = "0.1.0"),
        )
    }

    @Test
    fun `用户自装版本更高时跳过`() {
        // 用户可能从商店装了更新的版本，启动时不能被随包版本降级覆盖
        assertEquals(
            InstallerManager.BundledInstallAction.SKIP,
            decide(bundled = "0.1.0", installed = "0.2.0"),
        )
        assertEquals(
            InstallerManager.BundledInstallAction.SKIP,
            decide(bundled = "0.1.0", installed = "1.0.0"),
        )
    }

    @Test
    fun `预发布后缀按数字段比较`() {
        // VersionUtil 忽略预发布后缀，仅比较数字段
        assertEquals(
            InstallerManager.BundledInstallAction.SKIP,
            decide(bundled = "0.1.0", installed = "0.1.0-beta3"),
        )
        assertEquals(
            InstallerManager.BundledInstallAction.UPGRADE,
            decide(bundled = "0.1.1", installed = "0.1.0-beta3"),
        )
    }

    @Test
    fun `段数不同时按缺位补零比较`() {
        assertEquals(
            InstallerManager.BundledInstallAction.SKIP,
            decide(bundled = "1.0", installed = "1.0.0"),
        )
        assertEquals(
            InstallerManager.BundledInstallAction.UPGRADE,
            decide(bundled = "1.0.1", installed = "1.0"),
        )
    }

    @Test
    fun `无法解析的版本号不触发升级`() {
        // VersionUtil.compare 解析失败时返回 0 → 视为相同 → 跳过，
        // 宁可不动磁盘也不做无依据的覆盖
        assertEquals(
            InstallerManager.BundledInstallAction.SKIP,
            decide(bundled = "abc", installed = "0.1.0"),
        )
    }
}
