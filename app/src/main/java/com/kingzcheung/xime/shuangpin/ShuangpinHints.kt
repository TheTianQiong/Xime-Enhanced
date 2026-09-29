package com.kingzcheung.xime.shuangpin

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

/**
 * 双拼提示表的解析与合并（纯函数，无 Android 依赖，便于单测）。
 *
 * 提示表来自 `files/rime/shuangpin_hints.custom.yaml`，用于承载「各双拼方案的声母/韵母键位」
 * ——原先硬编码在 [ShuangpinSchemes] 里，配置化后用户与布局包都能自行增改：
 *
 * ```yaml
 * version: 1
 * schemes:
 *   - id: flypy                 # 与内置同 id 整条覆盖；新 id 追加
 *     name: 小鹤双拼             # 可省略，回退 id
 *     schema_keys: ["flypy"]    # 可省略，回退 [id]
 *     shengmu: { v: zh, i: ch } # 未列出的键按单字母原样显示
 *     yunmu: { a: [a], k: [uai, ing] }   # 支持标量与列表两种写法
 * ```
 *
 * 容错口径：任何解析失败都不抛异常——文件坏掉只会退回内置表，键盘不会因此不可用。
 */
object ShuangpinHintsParser {

    /** 与 KeysConfigHelper 同款宽松配置：未知键静默忽略（索引/配置演进友好）。 */
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    /**
     * 解析提示表文本。
     *
     * @return 有效方案列表；文本非法、`schemes` 缺失或全部条目无效时返回空列表（调用方回退内置表）
     */
    fun parse(text: String): List<ShuangpinScheme> {
        val root = runCatching { yaml.parseToYamlNode(text) as? YamlMap }.getOrNull() ?: return emptyList()
        val entries = root.opt<YamlList>("schemes") ?: return emptyList()

        val out = mutableListOf<ShuangpinScheme>()
        val seenIds = mutableSetOf<String>()
        for (node in entries.items) {
            val map = node as? YamlMap ?: continue
            val id = map.opt<YamlScalar>("id")?.content?.trim().orEmpty()
            // id 必填且唯一：重复 id 后者丢弃，避免检测顺序出现歧义
            if (id.isEmpty() || !seenIds.add(id)) continue

            val shengmu = parseShengmu(map.opt<YamlMap>("shengmu"))
            val yunmu = parseYunmu(map.opt<YamlMap>("yunmu"))
            // 声母与韵母都为空 = 该条没有任何信息，视为无效条目丢弃
            if (shengmu.isEmpty() && yunmu.isEmpty()) continue

            val name = map.opt<YamlScalar>("name")?.content?.trim().orEmpty().ifEmpty { id }
            val schemaKeys = map.opt<YamlList>("schema_keys")?.items
                ?.mapNotNull { (it as? YamlScalar)?.content?.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
                .ifEmpty { listOf(id) }

            out += ShuangpinScheme(
                id = id,
                displayName = name,
                shengmu = shengmu,
                yunmu = yunmu,
                schemaKeys = schemaKeys,
            )
        }
        return out
    }

    /**
     * 把配置表叠加到内置表上。
     *
     * 同 id 的条目**整条替换**并保留内置位置（检测按顺序首命中，位置变动会改变
     * `double_pinyin` 之类子方案的匹配优先级）；新 id 追加到末尾。
     */
    fun merge(
        builtIn: List<ShuangpinScheme>,
        custom: List<ShuangpinScheme>,
    ): List<ShuangpinScheme> {
        if (custom.isEmpty()) return builtIn
        val byId = custom.associateBy { it.id }
        val replaced = mutableSetOf<String>()
        val out = ArrayList<ShuangpinScheme>(builtIn.size + custom.size)
        for (scheme in builtIn) {
            val override = byId[scheme.id]
            if (override != null) {
                out += override
                replaced += scheme.id
            } else {
                out += scheme
            }
        }
        for (scheme in custom) {
            if (scheme.id !in replaced) out += scheme
        }
        return out
    }

    /**
     * 在给定方案表里按 Rime schema_id 检测方案；非双拼返回 null。
     *
     * 与内置口径一致：`double_pinyin` 需精确匹配（它是其它子方案名的子串），其余按子串包含。
     */
    fun detectIn(schemes: List<ShuangpinScheme>, schemaId: String): ShuangpinScheme? {
        if (schemaId.isEmpty()) return null
        val id = schemaId.lowercase()
        for (scheme in schemes) {
            for (key in scheme.schemaKeys) {
                val k = key.lowercase()
                if (k.isEmpty()) continue
                if (k == "double_pinyin") {
                    if (id == "double_pinyin") return scheme
                } else if (id.contains(k)) {
                    return scheme
                }
            }
        }
        return null
    }

    /** 声母表：键 → 声母（非标量节点忽略；kaml 的标量一律按字符串读入）。 */
    private fun parseShengmu(node: YamlMap?): Map<String, String> {
        if (node == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        for ((keyNode, value) in node.entries) {
            val key = (keyNode as? YamlScalar)?.content?.trim()?.lowercase().orEmpty()
            val text = (value as? YamlScalar)?.content?.trim().orEmpty()
            if (key.isNotEmpty() && text.isNotEmpty()) out[key] = text
        }
        return out
    }

    /** 韵母表：键 → 韵母列表（标量视为单元素列表；非字符串项过滤）。 */
    private fun parseYunmu(node: YamlMap?): Map<String, List<String>> {
        if (node == null) return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        for ((keyNode, value) in node.entries) {
            val key = (keyNode as? YamlScalar)?.content?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) continue
            val list = when (value) {
                is YamlScalar -> listOf(value.content.trim())
                is YamlList -> value.items.mapNotNull { (it as? YamlScalar)?.content?.trim() }
                else -> emptyList()
            }.filter { it.isNotEmpty() }
            if (list.isNotEmpty()) out[key] = list
        }
        return out
    }
}

private inline fun <reified T : YamlNode> YamlMap.opt(key: String): T? =
    get<YamlNode>(key) as? T
