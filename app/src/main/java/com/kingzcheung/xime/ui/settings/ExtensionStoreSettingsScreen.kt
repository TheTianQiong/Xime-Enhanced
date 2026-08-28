package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 拓展商店设置页：仓库地址（官方/自定义切换）与 GitHub 加速链接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionStoreSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var preset by remember { mutableStateOf(SettingsPreferences.getStoreRepoPreset(context)) }
    var repoUrl by remember { mutableStateOf(SettingsPreferences.getStoreRepoUrl(context)) }
    var accel by remember { mutableStateOf(SettingsPreferences.getGithubAccelPrefix(context)) }
    var jsonMapping by remember { mutableStateOf(SettingsPreferences.getStoreJsonMapping(context)) }

    fun selectPreset(p: String) {
        preset = p
        SettingsPreferences.setStoreRepoPreset(context, p)
        if (p == SettingsPreferences.STORE_REPO_OFFICIAL) {
            repoUrl = SettingsPreferences.DEFAULT_STORE_REPO_URL
            SettingsPreferences.setStoreRepoUrl(context, repoUrl)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("拓展商店设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "仓库选择",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectPreset(SettingsPreferences.STORE_REPO_OFFICIAL) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = preset == SettingsPreferences.STORE_REPO_OFFICIAL,
                    onClick = { selectPreset(SettingsPreferences.STORE_REPO_OFFICIAL) },
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("官方仓库", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Xime 官方索引（index.ximei.me）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectPreset(SettingsPreferences.STORE_REPO_CUSTOM) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = preset == SettingsPreferences.STORE_REPO_CUSTOM,
                    onClick = { selectPreset(SettingsPreferences.STORE_REPO_CUSTOM) },
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("自定义仓库", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "第三方仓库（如 JSON 文件夹索引）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = repoUrl,
                onValueChange = {
                    repoUrl = it
                    SettingsPreferences.setStoreRepoUrl(context, it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                label = { Text("仓库地址") },
                placeholder = { Text("https://index.ximei.me/") },
                enabled = preset == SettingsPreferences.STORE_REPO_CUSTOM,
                singleLine = true,
            )
            Text(
                text = "自定义仓库示例：https://lib.878624.xyz/xime-enhanced/?format=json",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )

            OutlinedTextField(
                value = accel,
                onValueChange = {
                    accel = it
                    SettingsPreferences.setGithubAccelPrefix(context, it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                label = { Text("GitHub 加速链接（可选）") },
                placeholder = { Text("如 https://ghfast.top/") },
                supportingText = {
                    Text("仅对 github.com 下载链接生效；留空则直连。")
                },
                singleLine = true,
            )

            Text(
                text = "第三方 JSON 字段映射（高级）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
            )
            OutlinedTextField(
                value = jsonMapping,
                onValueChange = {
                    jsonMapping = it
                    SettingsPreferences.setStoreJsonMapping(context, it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                label = { Text("JSON 字段映射") },
                supportingText = {
                    Text("路径用 . 分隔，如 extra.DisplayName。适配不同第三方仓库格式。")
                },
                minLines = 5,
                maxLines = 9,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "默认：{\"files\":\"files\",\"name\":\"name\",\"version\":\"version\",\"url\":\"url\",\"description\":\"description\",\"displayName\":\"extra.DisplayName\",\"tags\":\"extra.Tag\"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
