package com.kingzcheung.xime.ui.permission

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material.icons.twotone.Mic
import androidx.compose.material.icons.twotone.Sms
import androidx.compose.material.icons.twotone.Vibration
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.material.icons.twotone.WifiTethering
import androidx.compose.ui.graphics.vector.ImageVector
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.util.PermissionHelper

/**
 * 一条权限的元信息：标题、用途说明、是否需要运行时授权，以及使用它的内置功能。
 *
 * @param permission Android 权限名（`android.permission.*`）
 * @param title      展示名（如「麦克风」）
 * @param description 该权限在输入法中的用途
 * @param icon       展示图标
 * @param features   使用该权限的**内置功能**（宿主自身行为，静态）
 */
data class PermissionSpec(
    val permission: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val features: List<String>,
)

/** 一个使用某权限的已安装插件（[enabled] 为 false 表示已安装但未启用）。 */
data class PluginUsage(val name: String, val enabled: Boolean)

/**
 * 权限目录：输入法用到的全部权限 + 「谁需要它」的推导。
 *
 * 插件侧的权限来源有两类：
 * 1. **显式声明**：manifest.yaml 的 `permissions:`（见 [PluginInfo.declaredPermissions]）；
 * 2. **按能力推导**：语音类插件需要麦克风；声明了域名/自定义服务器地址、
 *    或属于同步/备份类的插件需要网络（含网络状态检测）。
 *
 * [permissionsOf] 为纯函数，便于单测。
 */
object PermissionCatalog {

    /** 输入法用到的全部权限，与 AndroidManifest.xml 的 uses-permission 一一对应。 */
    val ALL: List<PermissionSpec> = listOf(
        PermissionSpec(
            permission = PermissionHelper.PERMISSION_RECORD_AUDIO,
            title = "麦克风",
            description = "用于语音输入、语音转文字",
            icon = Icons.TwoTone.Mic,
            features = listOf("语音输入（工具栏麦克风、长按空格）"),
        ),
        PermissionSpec(
            permission = PermissionHelper.PERMISSION_RECEIVE_SMS,
            title = "短信",
            description = "读取短信中的验证码，本地解析、不上传",
            icon = Icons.TwoTone.Sms,
            features = listOf("短信验证码获取"),
        ),
        PermissionSpec(
            permission = PermissionHelper.PERMISSION_VIBRATE,
            title = "震动",
            description = "按键震动反馈",
            icon = Icons.TwoTone.Vibration,
            features = listOf("按键震动反馈"),
        ),
        PermissionSpec(
            permission = PermissionHelper.PERMISSION_INTERNET,
            title = "网络访问",
            description = "插件的网络请求、在线语音识别、无线导入",
            icon = Icons.TwoTone.Wifi,
            // 宿主自身的联网行为都依附于插件（在线 ASR / 同步 / AI 工具），
            // 因此不列无条件功能——无联网插件时该权限会显示为未使用
            features = emptyList(),
        ),
        PermissionSpec(
            permission = PermissionHelper.PERMISSION_ACCESS_NETWORK_STATE,
            title = "网络状态",
            description = "联网前检测网络可用性",
            icon = Icons.TwoTone.WifiTethering,
            features = emptyList(),
        ),
        PermissionSpec(
            permission = PermissionHelper.PERMISSION_ACCESS_WIFI_STATE,
            title = "WLAN 状态",
            description = "无线导入时读取局域网信息",
            icon = Icons.TwoTone.WifiTethering,
            features = listOf("无线导入（局域网导入方案与词库）"),
        ),
        PermissionSpec(
            permission = PermissionHelper.PERMISSION_WAKE_LOCK,
            title = "唤醒锁",
            description = "模型推理期间保持进程运行",
            icon = Icons.TwoTone.Bolt,
            features = listOf("语音识别 / 智能联想模型推理保活"),
        ),
    )

    /** 按权限名取元信息。 */
    fun specOf(permission: String): PermissionSpec? = ALL.firstOrNull { it.permission == permission }

    /**
     * 某个已安装插件需要的全部权限。
     *
     * 纯函数：不读磁盘、不依赖 Context。
     */
    fun permissionsOf(plugin: PluginInfo): Set<String> {
        val out = mutableSetOf<String>()

        // 1) manifest 显式声明（支持短名与全名）
        plugin.declaredPermissions.forEach { declared ->
            ALL.firstOrNull { PermissionHelper.permissionMatches(declared, it.permission) }
                ?.let { out += it.permission }
        }

        // 2) 语音类插件：录音由宿主键盘统一走 RECORD_AUDIO 门禁
        if (plugin.category == PluginCategory.ASR) {
            out += PermissionHelper.PERMISSION_RECORD_AUDIO
        }

        // 3) 联网：声明了域名/接受自定义地址，或同步/备份类插件（传输经网络），
        //    或语音插件声明需要网络
        val needsNetwork = plugin.declaredHosts.isNotEmpty() ||
            plugin.allowCustomHosts ||
            plugin.category == PluginCategory.CLIPBOARD_SYNC ||
            plugin.category == PluginCategory.BACKUP ||
            (plugin.category == PluginCategory.ASR && plugin.capabilities?.speech?.requiresNetwork == true)
        if (needsNetwork) {
            out += PermissionHelper.PERMISSION_INTERNET
            // 联网插件同时需要网络状态检测
            out += PermissionHelper.PERMISSION_ACCESS_NETWORK_STATE
        }

        return out
    }

    /**
     * 已安装插件（含未启用）中需要该权限的，已启用的排在前面。
     *
     * @param isEnabled 插件启用状态判定（由调用方注入偏好查询，保持本函数为纯函数）
     */
    fun pluginUsages(
        plugins: List<PluginInfo>,
        permission: String,
        isEnabled: (PluginInfo) -> Boolean = { it.enabled },
    ): List<PluginUsage> =
        plugins
            .filter { permission in permissionsOf(it) }
            .map { PluginUsage(name = it.name.ifBlank { it.id }, enabled = isEnabled(it)) }
            .sortedWith(compareByDescending<PluginUsage> { it.enabled }.thenBy { it.name })

    /**
     * 该权限当前是否被需要：有任何内置功能、或任何已安装插件（含未启用）需要它。
     * 都不需要时，权限管理页将其显示为灰色（未使用）状态。
     */
    fun isNeeded(
        plugins: List<PluginInfo>,
        spec: PermissionSpec,
        isEnabled: (PluginInfo) -> Boolean = { it.enabled },
    ): Boolean =
        spec.features.isNotEmpty() ||
            pluginUsages(plugins, spec.permission, isEnabled).isNotEmpty()
}
