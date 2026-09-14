package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.ChineseSymbolPreferences

/**
 * 中文符号自定义页。
 *
 * 「外观与交互 → 布局与显示 → 按键手势 → 中文符号自定义」。
 * 只改中文环境（全角）字符，英文环境（半角）保持系统默认、不受影响。
 *
 * 当前覆盖：符号键盘的中文全角字符（与英文半角一一对应）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChineseSymbolSettingsContent(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var row2 by remember { mutableStateOf(ChineseSymbolPreferences.getRow2(context)) }
    var row3 by remember { mutableStateOf(ChineseSymbolPreferences.getRow3(context)) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("中文符号自定义") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        ChineseSymbolPreferences.reset(context)
                        row2 = ChineseSymbolPreferences.DEFAULT_ROW2
                        row3 = ChineseSymbolPreferences.DEFAULT_ROW3
                    }) {
                        Text("恢复默认")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item {
                Text(
                    text = "自定义中文环境下使用的全角符号；英文环境使用半角符号，不受此处影响。" +
                        "修改后返回键盘立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                SettingsSection(title = "符号键盘 · 第二行") {
                    SymbolSlots(
                        asciiChars = ChineseSymbolPreferences.ASCII_ROW2,
                        defaults = ChineseSymbolPreferences.DEFAULT_ROW2,
                        values = row2,
                        onValueChange = { index, char ->
                            row2 = row2.toMutableList().also { it[index] = char }
                            ChineseSymbolPreferences.setRow2Char(context, index, char)
                        },
                    )
                }
            }

            item {
                SettingsSection(title = "符号键盘 · 第三行") {
                    SymbolSlots(
                        asciiChars = ChineseSymbolPreferences.ASCII_ROW3,
                        defaults = ChineseSymbolPreferences.DEFAULT_ROW3,
                        values = row3,
                        onValueChange = { index, char ->
                            row3 = row3.toMutableList().also { it[index] = char }
                            ChineseSymbolPreferences.setRow3Char(context, index, char)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 符号位网格：每行 3 个，上方标出对应的英文（半角）字符作为对照。
 * 输入清空时回退该位默认字符，避免出现空键。
 */
@Composable
private fun SymbolSlots(
    asciiChars: List<String>,
    defaults: List<String>,
    values: List<String>,
    onValueChange: (Int, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        values.indices.chunked(3).forEach { indices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                indices.forEach { index ->
                    SymbolSlot(
                        ascii = asciiChars.getOrElse(index) { "" },
                        defaultValue = defaults.getOrElse(index) { "" },
                        value = values.getOrElse(index) { "" },
                        onValueChange = { input -> onValueChange(index, input) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 补齐最后一行，保持列宽一致
                repeat(3 - indices.size) {
                    Column(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}

/** 单个符号位：英文半角对照 + 中文全角输入框（限 1 个字符）。 */
@Composable
private fun SymbolSlot(
    ascii: String,
    defaultValue: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "英文 $ascii",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                // 只取 1 个字符；清空则回退该位默认字符，避免键面出现空键
                val char = input.take(1)
                onValueChange(char.ifEmpty { defaultValue })
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
        )
    }
}
