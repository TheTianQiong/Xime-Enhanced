package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.Storefront
import androidx.compose.material.icons.twotone.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.MarketLayoutItem
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.LayoutMarketViewModel

/**
 * 布局插件管理页（布局与显示 → 布局插件）。
 *
 * 与市场「布局」页签共用 [LayoutMarketViewModel]（`viewModel()` 按 NavBackStackEntry 作用域，
 * 实例互不干扰），但只做**管理**：列出当前已应用的布局、快捷应用/恢复默认、查看释放了哪些文件，
 * 完整浏览（分类、版本、详情、截图）仍走市场页，避免两处重复维护。
 *
 * 布局包只会把文件释放到 rime 用户目录，宿主不执行其中任何代码；
 * 「应用」= 覆盖 xime.custom.yaml 等配置补丁（不保留备份，沿用市场既有约定）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutPluginsContent(
    onBack: () -> Unit,
    onNavigateToMarket: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: LayoutMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var confirmReset by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf<MarketLayoutItem?>(null) }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val appliedFiles = remember(uiState.appliedLayoutId, uiState.appliedVersion) {
        SettingsPreferences.getAppliedLayoutFiles(context)
    }
    val appliedName = uiState.layouts
        .firstOrNull { it.layout.id == uiState.appliedLayoutId }
        ?.layout?.name
        ?.ifBlank { uiState.appliedLayoutId }
        ?: uiState.appliedLayoutId

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("布局插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSection(title = "当前布局") {
                    AppliedLayoutRow(
                        hasApplied = uiState.appliedLayoutId.isNotBlank(),
                        name = appliedName,
                        version = uiState.appliedVersion,
                        files = appliedFiles,
                    )
                    if (uiState.appliedLayoutId.isNotBlank()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val busy = uiState.installingId != null
                            OutlinedButton(
                                onClick = { confirmReset = true },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("恢复默认") }
                            val current = uiState.layouts.firstOrNull { it.layout.id == uiState.appliedLayoutId }
                            if (current != null) {
                                OutlinedButton(
                                    onClick = { confirmApply = current },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("重新应用") }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "可用布局") {
                    when {
                        uiState.isLoading && uiState.layouts.isEmpty() -> LoadingRow()
                        uiState.layouts.isEmpty() -> HintRow(uiState.errorMessage ?: "暂无可用布局，可到布局市场浏览")
                        else -> uiState.filteredLayouts.forEachIndexed { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                            LayoutPluginRow(
                                item = item,
                                appliedLayoutId = uiState.appliedLayoutId,
                                appliedVersion = uiState.appliedVersion,
                                busy = uiState.installingId == item.layout.id,
                                progress = uiState.installProgress,
                                onClick = { confirmApply = item },
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "更多") {
                    SettingsItem(
                        icon = Icons.TwoTone.Storefront,
                        title = "浏览布局市场",
                        subtitle = "分类浏览、查看历史版本与截图",
                        onClick = onNavigateToMarket,
                        showArrow = true,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    SettingsItem(
                        icon = Icons.TwoTone.Refresh,
                        title = "刷新列表",
                        subtitle = uiState.source.ifBlank { "重新获取布局索引" },
                        onClick = { viewModel.loadLayouts(manual = true) },
                        showArrow = false,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }

    confirmApply?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmApply = null },
            title = { Text("应用布局") },
            text = {
                Text(
                    "将把「${target.layout.name.ifBlank { target.layout.id }}」的配置文件释放到 rime 目录，"
                        + "覆盖当前的 xime.custom.yaml，且不保留备份。是否继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = null
                    viewModel.applyLayout(target)
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { confirmApply = null }) { Text("取消") }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认布局") },
            text = {
                Text(
                    "将删除该布局释放的文件（共 ${appliedFiles.size} 项），恢复到内置默认布局。" +
                        "提示表等独立文件不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetLayout()
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            },
        )
    }
}

/** 当前布局块：名称/版本/释放的文件清单；未应用时给出引导。 */
@Composable
private fun AppliedLayoutRow(
    hasApplied: Boolean,
    name: String,
    version: String,
    files: List<String>,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = if (hasApplied) name else "未应用任何布局",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = when {
                !hasApplied -> "当前使用内置默认布局"
                version.isBlank() -> "已应用"
                else -> "版本 $version"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasApplied && files.isNotEmpty()) {
            Text(
                text = "释放文件（${files.size}）：${files.joinToString("、")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 单个布局行：名称 + 状态徽章 + 行尾操作。 */
@Composable
private fun LayoutPluginRow(
    item: MarketLayoutItem,
    appliedLayoutId: String,
    appliedVersion: String,
    busy: Boolean,
    progress: Float,
    onClick: () -> Unit,
) {
    val state = layoutItemStateOf(item, appliedLayoutId, appliedVersion)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = state.canApply && !busy, onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.TwoTone.ViewModule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.layout.name.ifBlank { item.layout.id },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = state.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.canApply) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                state.canApply -> Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = state.actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (busy && progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = "正在加载布局列表…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
    )
}

/** 布局行的派生状态（纯数据，便于单测）。 */
internal data class LayoutItemState(
    val label: String,
    /** 是否允许点「应用」：已应用的允许「重新应用」，缺依赖/不兼容的不允许。 */
    val canApply: Boolean,
    val actionLabel: String,
)

/**
 * 由 [MarketLayoutItem] 的派生量推出展示文案与可操作性。
 *
 * 判定顺序即优先级：已应用 > 不兼容 > 缺依赖方案 > 可更新 > 可应用。
 */
internal fun layoutItemStateOf(
    item: MarketLayoutItem,
    appliedLayoutId: String,
    appliedVersion: String,
): LayoutItemState = when {
    item.layout.id == appliedLayoutId ->
        LayoutItemState(
            label = "已应用${if (appliedVersion.isBlank()) "" else "（$appliedVersion）"}",
            canApply = true,
            actionLabel = "重新应用",
        )

    !item.compatible ->
        LayoutItemState(
            label = "需要更高版本 App${if (item.minAppVersion.isBlank()) "" else "（≥${item.minAppVersion}）"}",
            canApply = false,
            actionLabel = "",
        )

    !item.schemeReady ->
        LayoutItemState(
            label = "缺依赖方案：${item.layout.requiresSchemes.joinToString("、")}",
            canApply = false,
            actionLabel = "",
        )

    item.hasUpdate ->
        LayoutItemState(
            label = "可更新到 ${item.layout.currentVersion}",
            canApply = true,
            actionLabel = "更新",
        )

    else ->
        LayoutItemState(label = "可应用", canApply = true, actionLabel = "应用")
}
