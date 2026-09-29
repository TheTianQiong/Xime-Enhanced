package com.kingzcheung.xime.shuangpin

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * 双拼提示表的运行时注册表：内置表打底，用户/布局包提供的配置表叠加在其上。
 *
 * 配置文件为 rime 用户目录下的 `shuangpin_hints.custom.yaml`（见 [FILE_NAME]），
 * 刻意与 `xime.custom.yaml` 分开：后者会被布局市场整体覆盖（应用另一个布局即替换），
 * 提示表混在里面会被连带冲掉。`.custom.yaml` 后缀还带来两项既有保护：
 * `RimeConfigHelper.updateBuiltinAssets` 升级时不覆盖、`SchemaManifestManager` 不纳入市场清单回收。
 *
 * 热更：读文件按 mtime+size 门禁，内容变化时更新 [schemes] 并递增 [version]；
 * UI 侧读取两者即订阅重算（纯显示层，无需 rime 重新部署）。
 */
object ShuangpinHintRegistry {

    private const val TAG = "ShuangpinHintRegistry"

    /** 提示表文件（相对 rime 用户目录）。 */
    const val FILE_NAME = "shuangpin_hints.custom.yaml"

    /** 文件缓存戳（mtime, size）；null 表示尚未成功加载过。 */
    private var stamp: Pair<Long, Long>? = null

    /** 当前生效的方案表；默认即内置表，配置表只做叠加。 */
    private val schemesState = mutableStateOf(ShuangpinSchemes.builtIn)

    /** 每次生效方案表变化时自增，供 Compose 侧作 remember key 触发重算。 */
    private val versionState = mutableStateOf(0)

    val version: Int get() = versionState.value

    /** 当前生效的方案表（内置 + 配置覆盖）。 */
    fun schemes(): List<ShuangpinScheme> = schemesState.value

    /** 按 Rime schema_id 检测双拼方案；非双拼返回 null。 */
    fun detect(schemaId: String): ShuangpinScheme? =
        ShuangpinHintsParser.detectIn(schemesState.value, schemaId)

    /**
     * 从磁盘重新加载提示表（幂等、廉价；文件未变化时早返回）。
     *
     * 由 [com.kingzcheung.xime.settings.KeysConfigHelper.loadConfig] 调用，因而
     * 应用布局、部署方案、服务重建等既有重载路径都会顺带刷新提示表。
     */
    fun load(context: Context) {
        val file = File(context.filesDir, "rime/$FILE_NAME")
        val current = if (file.exists()) file.lastModified() to file.length() else 0L to 0L
        if (stamp == current) return

        val custom = try {
            if (file.exists()) ShuangpinHintsParser.parse(file.readText()) else emptyList()
        } catch (e: Exception) {
            // 读失败不更新戳：下次重载重试（与 KeysConfigHelper 同口径）
            Log.e(TAG, "读取 $FILE_NAME 失败：${e.message}", e)
            return
        }
        stamp = current
        apply(custom)
    }

    private fun apply(custom: List<ShuangpinScheme>) {
        val merged = ShuangpinHintsParser.merge(ShuangpinSchemes.builtIn, custom)
        // ShuangpinScheme 非 data class，此处即引用比较：配置表为空时 merge 直接返回内置表本身，
        // 于是「没有配置文件」这条最常见路径不会触发无谓的重组
        if (merged === schemesState.value) return
        schemesState.value = merged
        versionState.value++
        Log.i(TAG, "提示表已更新：${merged.size} 个方案（其中来自配置表 ${custom.size} 条）")
    }

    /** 测试缝隙：直接注入方案表，跳过磁盘与 Context。 */
    internal fun setForTest(schemes: List<ShuangpinScheme>) {
        stamp = null
        schemesState.value = schemes
        versionState.value++
    }
}
