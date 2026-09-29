package com.kingzcheung.xime.settings

import com.kingzcheung.xime.shuangpin.ShuangpinHintRegistry

/**
 * 布局包的内容白名单与有效性判定（纯函数，便于单测）。
 *
 * 布局包只会把文件释放到 rime 用户目录，宿主**不执行**包内任何代码；因此这里
 * 是按「哪些路径允许落盘」做的硬白名单，其余条目一律丢弃（防 zip-slip 之外的第二道闸）。
 *
 * 允许的三类内容：
 * - `xime.custom.yaml`：键面布局/主题配置补丁
 * - `shuangpin_hints.custom.yaml`：双拼提示表（见 [ShuangpinHintRegistry]）
 * - `themes/`、`fonts/`：背景图与字体资源
 *
 * 两个 `*.custom.yaml` 都必须落在**根级**：它们由 `KeysConfigHelper` 从固定路径读取，
 * 放进子目录等于写了个永远不会被读到文件。
 */
object LayoutPackagePolicy {

    /** 键面布局配置补丁。 */
    const val XIME_CUSTOM = "xime.custom.yaml"

    /** 双拼提示表（与运行时读取的文件名保持同一来源）。 */
    const val SHUANGPIN_HINTS = ShuangpinHintRegistry.FILE_NAME

    private const val THEMES_PREFIX = "themes/"
    private const val FONTS_PREFIX = "fonts/"

    /** 相对路径长度上限（与 InstallerManager 的插件资源路径同量级）。 */
    private const val MAX_PATH_LENGTH = 256

    /**
     * 该相对路径是否允许释放到 rime 用户目录。
     *
     * 除名字白名单外还自带路径形态校验（绝对路径、反斜杠、`.`/`..`/空段一律拒绝）——
     * 调用方另有一道 canonical 越界检查，但白名单自身也不该放行穿越路径。
     */
    fun isAllowedEntry(relPath: String): Boolean {
        if (relPath.isEmpty() || relPath.length > MAX_PATH_LENGTH) return false
        if (relPath.startsWith("/") || relPath.contains('\\')) return false
        if (relPath.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        return relPath == XIME_CUSTOM ||
            relPath == SHUANGPIN_HINTS ||
            relPath.startsWith(THEMES_PREFIX) ||
            relPath.startsWith(FONTS_PREFIX)
    }

    /** 是否为「配置补丁」条目（决定包是否有安装意义）。 */
    fun isConfigPatch(relPath: String): Boolean =
        relPath == XIME_CUSTOM || relPath == SHUANGPIN_HINTS

    /**
     * 包是否有效：至少要释放一个配置补丁。
     *
     * 只有主题/字体资源、或只有被白名单丢弃的文件的包安装后不改变任何行为，
     * 视为无效包拒绝，避免「装了个寂寞」。
     */
    fun isMeaningfulPackage(written: List<String>): Boolean = written.any { isConfigPatch(it) }
}
