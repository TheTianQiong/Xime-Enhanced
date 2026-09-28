package com.kingzcheung.xime.ui.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.sms.SmsCodePluginConfig
import com.kingzcheung.xime.util.PermissionHelper

/**
 * 权限管理共享内容：逐条列出输入法使用的全部权限，并标注
 * **哪些功能**与**哪些已安装插件（含未启用）**需要它。
 *
 * 两者都不需要的权限显示为灰色「未使用」状态，说明当前环境下该权限不被使用
 * （例如未安装任何联网插件时，网络访问与网络状态即为未使用）。
 *
 * @param refreshKey   外部（Activity 授权回调 / IME 窗口重新聚焦）触发重算状态的信号；
 *                      改变时重新查询各权限状态与插件列表。
 * @param requestPermission 发起单个权限请求的动作（设置页直接用 Activity 的 launcher；
 *                      IME 内通过 [PermissionHelper.requestPermission] 拉起 MainActivity）。
 */
@Composable
fun PermissionManagerContent(
    refreshKey: Int = 0,
    requestPermission: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 重算权限状态与插件列表：授权回调 / 窗口聚焦变化 / 插件装卸后 refreshKey++ 即刷新
    val granted = remember(refreshKey) {
        PermissionCatalog.ALL.associate { spec ->
            spec.permission to PermissionHelper.hasPermission(context, spec.permission)
        }
    }
    val installedPlugins: List<PluginInfo> = remember(refreshKey) {
        runCatching { ExtensionManager.getAllInstalledPlugins() }.getOrDefault(emptyList())
    }
    val isEnabled: (PluginInfo) -> Boolean = remember(refreshKey) {
        { plugin -> SettingsPreferences.isPluginEnabled(context, plugin.id) }
    }
    val usages = remember(refreshKey, installedPlugins) {
        PermissionCatalog.ALL.associate { spec ->
            spec.permission to PermissionCatalog.pluginUsages(
                installedPlugins, spec.permission, isEnabled
            )
        }
    }

    // 权限条目本身带功能/插件说明，整体较高；由本组件统一提供垂直滚动，
    // 设置页与 IME 目录菜单两处调用方都无需各自处理。
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "以下为输入法用到的全部权限。每个权限标注了使用它的功能与已安装插件" +
                "（含已安装但未启用的插件）；两者都不需要的权限显示为灰色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        PermissionCatalog.ALL.forEachIndexed { index, spec ->
            val pluginList = usages[spec.permission].orEmpty()
            PermissionRow(
                spec = spec,
                granted = granted[spec.permission] == true,
                features = spec.features,
                plugins = pluginList,
                needed = spec.features.isNotEmpty() || pluginList.isNotEmpty(),
                onRequest = { requestPermission(spec.permission) },
            )
            if (index < PermissionCatalog.ALL.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // ── 短信验证码设置 ──
        // 注：这部分将迁移到「插件管理 → 短信验证码（增强）」插件的设置内。
        var smsEnabled by remember { mutableStateOf(SettingsPreferences.isSmsCodeEnabled(context)) }
        var autoCopy by remember { mutableStateOf(SettingsPreferences.isSmsAutoCopyEnabled(context)) }
        var smsTtlSeconds by remember {
            mutableStateOf(SettingsPreferences.getSmsCodeTtlSeconds(context).toString())
        }
        var smsRegex by remember { mutableStateOf(SmsCodePluginConfig.getRegex(context) ?: "") }

        ToggleRow(
            title = "短信验证码获取",
            subtitle = "收到验证码短信后在候选栏快捷插入",
            checked = smsEnabled,
            onCheckedChange = {
                smsEnabled = it
                SettingsPreferences.setSmsCodeEnabled(context, it)
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        ToggleRow(
            title = "自动复制到剪贴板",
            subtitle = "收到验证码后自动复制，方便直接粘贴",
            checked = autoCopy,
            enabled = smsEnabled,
            onCheckedChange = {
                autoCopy = it
                SettingsPreferences.setSmsAutoCopyEnabled(context, it)
            },
        )

        OutlinedTextField(
            value = smsTtlSeconds,
            onValueChange = { input ->
                smsTtlSeconds = input.filter { it.isDigit() }.take(3)
                smsTtlSeconds.toLongOrNull()?.let { SettingsPreferences.setSmsCodeTtlSeconds(context, it) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = { Text("验证码有效期（秒）") },
            placeholder = { Text("60") },
            supportingText = {
                Text("仅显示最近该秒数内收到的验证码（10–600 秒），超时自动消失。")
            },
            singleLine = true,
            enabled = smsEnabled,
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = smsRegex,
            onValueChange = {
                smsRegex = it
                SmsCodePluginConfig.setRegex(context, it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = { Text("提取正则（可选）") },
            placeholder = { Text("如 (?<!\\d)\\d{4,6}(?!\\d)") },
            supportingText = {
                Text("留空使用内置智能提取；含捕获组时取第一个非空分组。可在插件「短信验证码（增强）」设置页同步修改。")
            },
            singleLine = false,
            minLines = 1,
            maxLines = 3,
            enabled = smsEnabled,
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = "短信内容仅在设备本地解析用于提取验证码，不会上传或对外发送。授权短信权限前请确认来源可信。",
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 带开关的设置行。 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * 一条权限：图标 + 标题/用途 + 授权状态与操作按钮，下方列出需要它的功能与插件。
 * [needed] 为 false 时整行降低不透明度（灰色未使用态）。
 */
@Composable
private fun PermissionRow(
    spec: PermissionSpec,
    granted: Boolean,
    features: List<String>,
    plugins: List<PluginUsage>,
    needed: Boolean,
    onRequest: () -> Unit,
) {
    val runtime = PermissionHelper.isRuntimePermission(spec.permission)
    // 未使用的权限降低不透明度；同时用 variant 色弱化文字，形成明确的灰色态
    val contentAlpha = if (needed) 1f else 0.45f
    val titleColor = if (needed) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(contentAlpha)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = spec.icon,
                contentDescription = spec.title,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = spec.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 普通权限安装即授予，不提供操作按钮，仅标注状态
            if (runtime) {
                TextButton(onClick = onRequest, enabled = !granted) {
                    Text(if (granted) "已授权" else "去授权")
                }
            } else {
                Text(
                    text = "系统授予",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (!needed) {
            Text(
                text = "当前无插件或功能使用此权限",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp, top = 6.dp),
            )
        }

        if (features.isNotEmpty()) {
            UsageLine(label = "功能", modifier = Modifier.padding(start = 40.dp, top = 6.dp)) {
                features.forEach { Chip(text = it) }
            }
        }

        if (plugins.isNotEmpty()) {
            UsageLine(
                label = "插件",
                modifier = Modifier.padding(start = 40.dp, top = if (features.isEmpty()) 6.dp else 4.dp),
            ) {
                plugins.forEach { usage ->
                    Chip(
                        text = if (usage.enabled) usage.name else "${usage.name}（未启用）",
                    )
                }
            }
        }
    }
}

/** 「功能 / 插件」标签行：左侧固定标签，右侧横向排列若干条目。 */
@Composable
private fun UsageLine(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

/** 条目小标签：用于展示功能名或插件名。 */
@Composable
private fun Chip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
