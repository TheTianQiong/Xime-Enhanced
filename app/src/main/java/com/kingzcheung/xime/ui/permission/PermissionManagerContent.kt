package com.kingzcheung.xime.ui.permission

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Mic
import androidx.compose.material.icons.twotone.Sms
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.sms.SmsCodePluginConfig
import com.kingzcheung.xime.util.PermissionHelper

/** 一条可管理权限的元信息。 */
data class PermissionEntry(
    val permission: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

/** 需要管理权限的清单。 */
val XIME_PERMISSIONS = listOf(
    PermissionEntry(
        permission = PermissionHelper.PERMISSION_RECORD_AUDIO,
        title = "麦克风",
        description = "用于语音输入、语音转文字",
        icon = Icons.TwoTone.Mic,
    ),
    PermissionEntry(
        permission = PermissionHelper.PERMISSION_RECEIVE_SMS,
        title = "短信",
        description = "读取短信中的验证码，本地解析、不上传",
        icon = Icons.TwoTone.Sms,
    ),
)

/**
 * 权限管理共享内容：权限列表（状态 + 去授权）与短信验证码相关开关。
 *
 * @param refreshKey   外部（Activity 授权回调 / IME 窗口重新聚焦）触发重算状态的信号；
 *                      改变时重新查询各权限的当前状态。
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
    // 重算权限状态：授权回调 / 窗口聚焦变化后 refreshKey++ 即刷新
    val granted = remember(refreshKey) {
        XIME_PERMISSIONS.associate { entry ->
            entry.permission to PermissionHelper.hasPermission(context, entry.permission)
        }
    }

    var smsEnabled by remember { mutableStateOf(SettingsPreferences.isSmsCodeEnabled(context)) }
    var autoCopy by remember { mutableStateOf(SettingsPreferences.isSmsAutoCopyEnabled(context)) }
    var smsTtlSeconds by remember { mutableStateOf(SettingsPreferences.getSmsCodeTtlSeconds(context).toString()) }
    var smsRegex by remember { mutableStateOf(SmsCodePluginConfig.getRegex(context) ?: "") }

    Column(modifier = modifier) {
        // 权限列表
        XIME_PERMISSIONS.forEachIndexed { index, entry ->
            PermissionRow(
                entry = entry,
                granted = granted[entry.permission] == true,
                onRequest = { requestPermission(entry.permission) },
            )
            if (index < XIME_PERMISSIONS.size - 1) {
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

        // 短信验证码开关
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

@Composable
private fun PermissionRow(
    entry: PermissionEntry,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onRequest)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = entry.title,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRequest, enabled = !granted) {
            Text(if (granted) "已授权" else "去授权")
        }
    }
}

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
