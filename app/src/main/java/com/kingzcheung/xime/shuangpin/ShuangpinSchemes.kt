package com.kingzcheung.xime.shuangpin

/**
 * 双拼方案数据与通用逻辑。
 *
 * 支持雾凇拼音（rime-ice）内置的全部双拼方案：小鹤 / 通用 / 自然码 / 微软 / 智能ABC / 搜狗 / 加加。
 * 每个方案的键位映射依据 rime-ice 对应 `double_pinyin_*.schema.yaml` 的 speller 规则整理。
 */
class ShuangpinScheme(
    val id: String,
    val displayName: String,
    /** 声母键位：按键 → 声母（zh/ch/sh 等）。 */
    val shengmu: Map<String, String>,
    /** 韵母键位：按键 → 韵母列表（上下文相关的双韵母返回两个）。 */
    val yunmu: Map<String, List<String>>,
    /** 对应 Rime schema_id 的关键词（用于检测）。 */
    val schemaKeys: List<String>,
) {

    /** 第一键 → 声母。 */
    fun shengmuForKey(key: String): String? = shengmu[key]

    /** 第二键 → 韵母列表。 */
    fun yunmuListForKey(key: String): List<String> = yunmu[key] ?: emptyList()

    /** 动态键面标签：偶数键显示声母映射，奇数键（已输声母）显示韵母映射（双韵母键用 \n 两行显示，三韵母键用括号合并为两行）。 */
    fun keyLabel(key: String, showYunmu: Boolean): String {
        if (!showYunmu) return shengmu[key] ?: key
        val yunmuList = yunmuListForKey(key)
        if (yunmuList.isEmpty()) return key
        if (yunmuList.size <= 2) return yunmuList.joinToString("\n")
        // 键面最多两行：把仅差一字的相近韵母合并为 u(v)e，多余项并入第二行括号
        val groups = compactYunmu(yunmuList)
        val first = groups.first()
        return when (groups.size) {
            1 -> first
            2 -> "$first\n${groups[1]}"
            else -> "$first\n${groups.drop(1).joinToString("/") { "($it)" }}"
        }
    }

    /** 把仅相差一个字符的相邻韵母合并为「u(v)e」紧凑写法（如 ue/ve → u(v)e）。 */
    private fun compactYunmu(yunmuList: List<String>): List<String> {
        val groups = mutableListOf<String>()
        var i = 0
        while (i < yunmuList.size) {
            val cur = yunmuList[i]
            if (i + 1 < yunmuList.size && differByOneChar(cur, yunmuList[i + 1])) {
                groups.add(mergeTwo(cur, yunmuList[i + 1]))
                i += 2
            } else {
                groups.add(cur)
                i += 1
            }
        }
        return groups
    }

    private fun differByOneChar(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (j in a.indices) {
            if (a[j] != b[j]) diff++
        }
        return diff == 1
    }

    private fun mergeTwo(a: String, b: String): String {
        for (j in a.indices) {
            if (a[j] != b[j]) {
                return a.substring(0, j) + a[j] + "(" + b[j] + ")" + a.substring(j + 1)
            }
        }
        return a
    }

    /** 把键入键位分解为「声母 + 韵母」列表。 */
    fun decompose(keys: String): List<String> {
        if (keys.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var i = 0
        val n = keys.length
        while (i < n) {
            if (i + 1 < n) {
                val s = shengmu[keys[i].toString()]
                val y = yunmu[keys[i + 1].toString()]?.firstOrNull()
                result.add(
                    if (s != null && y != null) "$s + $y"
                    else "${keys[i]}${keys[i + 1]}"
                )
                i += 2
            } else {
                val s = shengmu[keys[i].toString()]
                result.add(s ?: keys[i].toString())
                i += 1
            }
        }
        return result
    }

    /** 用于「双拼对照」面板的分组数据。 */
    fun groups(): List<ShuangpinScheme.Group> = listOf(
        ShuangpinScheme.Group(
            title = "声母",
            items = shengmu.entries
                .sortedBy { it.key }
                .map { it.value to it.key },
        ),
        ShuangpinScheme.Group(
            title = "韵母",
            items = yunmu.entries
                .sortedBy { it.key }
                .map { it.value.joinToString("/") to it.key },
        ),
    )

    data class Group(val title: String, val items: List<Pair<String, String>>)
}

/** 全部支持的双拼方案注册表。 */
object ShuangpinSchemes {

    /** 小鹤双拼。 */
    val FLYPY = ShuangpinScheme(
        id = "flypy",
        displayName = "小鹤双拼",
        shengmu = mapOf(
            "b" to "b", "p" to "p", "m" to "m", "f" to "f", "d" to "d", "t" to "t",
            "n" to "n", "l" to "l", "g" to "g", "k" to "k", "h" to "h",
            "j" to "j", "q" to "q", "x" to "x", "r" to "r", "z" to "z",
            "c" to "c", "s" to "s", "y" to "y", "w" to "w",
            "v" to "zh", "i" to "ch", "u" to "sh",
        ),
        yunmu = mapOf(
            "a" to listOf("a"), "b" to listOf("in"), "c" to listOf("ao"), "d" to listOf("ai"),
            "e" to listOf("e"), "f" to listOf("en"), "g" to listOf("eng"), "h" to listOf("ang"),
            "i" to listOf("i"), "j" to listOf("an"), "k" to listOf("uai", "ing"), "l" to listOf("iang", "uang"),
            "m" to listOf("ian"), "n" to listOf("iao"), "o" to listOf("o", "uo"), "p" to listOf("ie"),
            "q" to listOf("iu"), "r" to listOf("uan"), "s" to listOf("ong", "iong"), "t" to listOf("ue", "ve"),
            "u" to listOf("u"), "v" to listOf("ui", "v"), "w" to listOf("ei"), "x" to listOf("ia", "ua"),
            "y" to listOf("un"), "z" to listOf("ou"),
        ),
        schemaKeys = listOf("flypy"),
    )

    /** 通用双拼（double_pinyin）。 */
    val TONGYONG = ShuangpinScheme(
        id = "tongyong",
        displayName = "通用双拼",
        shengmu = mapOf("v" to "zh", "i" to "ch", "u" to "sh"),
        yunmu = mapOf(
            "a" to listOf("a"), "b" to listOf("ou"), "c" to listOf("iao"), "d" to listOf("iang", "uang"),
            "e" to listOf("e"), "f" to listOf("en"), "g" to listOf("eng"), "h" to listOf("ang"),
            "i" to listOf("i"), "j" to listOf("an"), "k" to listOf("ao"), "l" to listOf("ai"),
            "m" to listOf("ian"), "n" to listOf("in"), "o" to listOf("uo"), "p" to listOf("un"),
            "q" to listOf("iu"), "r" to listOf("uan"), "s" to listOf("ong", "iong"), "t" to listOf("ue", "ve"),
            "u" to listOf("u"), "v" to listOf("ui"), "w" to listOf("ia", "ua"), "x" to listOf("ie"),
            "y" to listOf("ing", "uai"), "z" to listOf("ei"),
        ),
        schemaKeys = listOf("double_pinyin"),
    )

    /** 自然码双拼。 */
    val ZIRANMA = ShuangpinScheme(
        id = "ziranma",
        displayName = "自然码双拼",
        shengmu = mapOf("v" to "zh", "i" to "ch", "u" to "sh"),
        yunmu = mapOf(
            "a" to listOf("a"), "b" to listOf("iao"), "c" to listOf("uai"), "d" to listOf("ie"),
            "e" to listOf("e"), "f" to listOf("ian"), "g" to listOf("iang", "uang"), "h" to listOf("ong", "iong"),
            "i" to listOf("i"), "j" to listOf("er", "iu"), "k" to listOf("ei"), "l" to listOf("uan"),
            "m" to listOf("un"), "n" to listOf("ue", "ve"), "o" to listOf("o", "uo"), "p" to listOf("ai"),
            "q" to listOf("ao"), "r" to listOf("an"), "s" to listOf("ang"), "t" to listOf("eng"),
            "u" to listOf("u"), "v" to listOf("ui", "v"), "w" to listOf("en"), "x" to listOf("ia", "ua"),
            "y" to listOf("in", "uai"), "z" to listOf("ou"),
        ),
        schemaKeys = listOf("ziranma", "zrm"),
    )

    /** 紫光双拼。 */
    val ZIGUANG = ShuangpinScheme(
        id = "ziguang",
        displayName = "紫光双拼",
        shengmu = mapOf("u" to "zh", "i" to "sh", "a" to "ch"),
        yunmu = mapOf(
            "a" to listOf("a"), "b" to listOf("iao"), "d" to listOf("ie"),
            "e" to listOf("e"), "f" to listOf("ian"), "g" to listOf("iang", "uang"), "h" to listOf("ong", "iong"),
            "i" to listOf("i"), "j" to listOf("er", "iu"), "k" to listOf("ei"), "l" to listOf("uan"),
            "m" to listOf("un"), "n" to listOf("ue", "ve", "ui"), "o" to listOf("o", "uo"), "p" to listOf("ai"),
            "q" to listOf("ao"), "r" to listOf("an"), "s" to listOf("ang"), "t" to listOf("eng"),
            "u" to listOf("u"), "v" to listOf("v"), "w" to listOf("en"), "x" to listOf("ia", "ua"),
            "y" to listOf("in", "uai"), "z" to listOf("ou"),
        ),
        schemaKeys = listOf("ziguang"),
    )

    /** 微软双拼。 */
    val MSPY = ShuangpinScheme(
        id = "mspy",
        displayName = "微软双拼",
        shengmu = mapOf("v" to "zh", "i" to "ch", "u" to "sh"),
        yunmu = mapOf(
            "a" to listOf("a"), "b" to listOf("ou"), "c" to listOf("iao"), "d" to listOf("iang", "uang"),
            "e" to listOf("e"), "f" to listOf("en"), "g" to listOf("eng"), "h" to listOf("ang"),
            "i" to listOf("i"), "j" to listOf("an"), "k" to listOf("ao"), "l" to listOf("ai"),
            "m" to listOf("ian"), "n" to listOf("in"), "o" to listOf("o", "uo"), "p" to listOf("un"),
            "q" to listOf("iu"), "r" to listOf("er", "uan"), "s" to listOf("ong", "iong"), "t" to listOf("ue", "ve"),
            "u" to listOf("u"), "v" to listOf("ui"), "w" to listOf("ia", "ua"), "x" to listOf("ie"),
            "y" to listOf("v", "uai"), "z" to listOf("ei"),
        ),
        schemaKeys = listOf("mspy"),
    )

    /** 智能ABC双拼。 */
    val ABC = ShuangpinScheme(
        id = "abc",
        displayName = "智能ABC双拼",
        shengmu = mapOf("a" to "zh", "e" to "ch", "v" to "sh"),
        yunmu = mapOf(
            "a" to listOf("a"), "b" to listOf("ou"), "c" to listOf("in", "uai"), "d" to listOf("ia", "ua"),
            "e" to listOf("e"), "f" to listOf("en"), "g" to listOf("eng"), "h" to listOf("ang"),
            "i" to listOf("i"), "j" to listOf("an"), "k" to listOf("ao"), "l" to listOf("ai"),
            "m" to listOf("ue", "ve", "ui"), "n" to listOf("un"), "o" to listOf("uo"), "p" to listOf("uan"),
            "q" to listOf("ei"), "r" to listOf("er", "iu"), "s" to listOf("ong", "iong"), "t" to listOf("iang", "uang"),
            "u" to listOf("u"), "v" to listOf("v"), "w" to listOf("ian"), "x" to listOf("ie"),
            "y" to listOf("ing"), "z" to listOf("iao"),
        ),
        schemaKeys = listOf("abc"),
    )

    /** 搜狗双拼。 */
    val SOGOU = ShuangpinScheme(
        id = "sogou",
        displayName = "搜狗双拼",
        shengmu = mapOf("v" to "zh", "i" to "ch", "u" to "sh"),
        yunmu = mapOf(
            "a" to listOf("a"), "b" to listOf("ou"), "c" to listOf("iao"), "d" to listOf("iang", "uang"),
            "e" to listOf("e"), "f" to listOf("en"), "g" to listOf("eng"), "h" to listOf("ang"),
            "i" to listOf("i"), "j" to listOf("an"), "k" to listOf("ao"), "l" to listOf("ai"),
            "m" to listOf("ian"), "n" to listOf("in"), "o" to listOf("o", "uo"), "p" to listOf("un"),
            "q" to listOf("iu"), "r" to listOf("er", "uan"), "s" to listOf("ong", "iong"), "t" to listOf("ue", "ve"),
            "u" to listOf("u"), "v" to listOf("ui"), "w" to listOf("ia", "ua"), "x" to listOf("ie"),
            "y" to listOf("v", "uai"), "z" to listOf("ei"),
        ),
        schemaKeys = listOf("sogou"),
    )

    /** 加加双拼。 */
    val JIAJIA = ShuangpinScheme(
        id = "jiajia",
        displayName = "加加双拼",
        shengmu = mapOf("v" to "zh", "u" to "ch", "i" to "sh"),
        yunmu = mapOf(
            "a" to listOf("a"), "b" to listOf("ia", "ua"), "c" to listOf("uan"), "d" to listOf("ao"),
            "e" to listOf("e"), "f" to listOf("an"), "g" to listOf("ang"), "h" to listOf("iang", "uang"),
            "i" to listOf("i"), "j" to listOf("ian"), "k" to listOf("iao"), "l" to listOf("in"),
            "m" to listOf("ie"), "n" to listOf("iu"), "o" to listOf("uo"), "p" to listOf("ou"),
            "q" to listOf("er", "ing"), "r" to listOf("en"), "s" to listOf("ai"), "t" to listOf("eng"),
            "u" to listOf("u"), "v" to listOf("ui", "v"), "w" to listOf("ei"), "x" to listOf("ue", "ve", "uai"),
            "y" to listOf("ong", "iong"), "z" to listOf("un"),
        ),
        schemaKeys = listOf("jiajia"),
    )

    val all: List<ShuangpinScheme> = listOf(FLYPY, TONGYONG, ZIRANMA, ZIGUANG, MSPY, ABC, SOGOU, JIAJIA)

    /** 根据 Rime schema_id 检测双拼方案；非双拼返回 null。 */
    fun detect(schemaId: String): ShuangpinScheme? {
        if (schemaId.isEmpty()) return null
        val id = schemaId.lowercase()
        // 通用双拼 schema 名为 double_pinyin，需精确匹配（它是其它方案的子串）
        for (scheme in all) {
            for (key in scheme.schemaKeys) {
                val k = key.lowercase()
                if (k == "double_pinyin") {
                    if (id == "double_pinyin") return scheme
                } else if (id.contains(k)) {
                    return scheme
                }
            }
        }
        return null
    }

    /** 当前方案是否为双拼方案。 */
    fun isShuangpinSchema(schemaId: String): Boolean = detect(schemaId) != null

    /** 是否处于"等待韵母"状态：当前输入键数（不含分隔符）为奇数。 */
    fun shouldShowYunmu(keys: String): Boolean {
        var n = 0
        for (c in keys) {
            if (c in 'a'..'z' || c in 'A'..'Z') n++
        }
        return n % 2 == 1
    }
}
