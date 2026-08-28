package com.kingzcheung.xime.shuangpin

/**
 * 小鹤双拼（double_pinyin_flypy / xiaohe）键位数据与分解逻辑。
 *
 * 用于「先显示声母后显示韵母」：把用户键入的双拼键位分解成 声母 + 韵母。
 * 例如键入 `vc` → `zh + ao`。
 */
object XiaoheShuangpin {

    /** 一组对照项：拼音 → 按键。 */
    data class Group(val title: String, val items: List<Pair<String, String>>)

    /** 声母键位（第一键）：按键 → 声母。 */
    private val SHENGMU = mapOf(
        "b" to "b", "p" to "p", "m" to "m", "f" to "f", "d" to "d", "t" to "t",
        "n" to "n", "l" to "l", "g" to "g", "k" to "k", "h" to "h",
        "j" to "j", "q" to "q", "x" to "x", "r" to "r", "z" to "z",
        "c" to "c", "s" to "s", "y" to "y", "w" to "w",
        "v" to "zh", "i" to "ch", "u" to "sh",
    )

    /** 韵母键位（第二键）：按键 → 主韵母（用于分解）。 */
    private val YUNMU = mapOf(
        "a" to "a", "o" to "uo", "e" to "e", "i" to "i", "u" to "u", "v" to "ü",
        "d" to "ai", "w" to "ei", "c" to "ao", "z" to "ou", "q" to "iu",
        "x" to "ia", "t" to "üe", "r" to "uan", "j" to "an", "f" to "en",
        "b" to "in", "y" to "un", "h" to "ang", "g" to "eng", "k" to "ing",
        "s" to "ong", "m" to "ian", "l" to "uang", "n" to "iao", "p" to "ie",
    )

    /** 上下文相关的双韵母键：同一按键可对应两个韵母（键面上下两排显示）。 */
    private val YUNMU_DUAL = mapOf(
        "l" to listOf("iang", "uang"),
        "k" to listOf("uai", "ing"),
        "s" to listOf("iong", "ong"),
        "x" to listOf("ua", "ia"),
        "o" to listOf("uo", "o"),
        "r" to listOf("uan", "er"),
        "v" to listOf("ui", "ü"),
    )

    /** 用于「双拼对照」面板展示的分组数据。 */
    val groups: List<Group> = listOf(
        Group(
            title = "声母",
            items = SHENGMU.entries
                .sortedBy { it.key }
                .map { it.value to it.key },
        ),
        Group(
            title = "韵母",
            items = ('a'..'z')
                .filter { yunmuListForKey(it.toString()).isNotEmpty() }
                .map { yunmuListForKey(it.toString()).joinToString("/") to it.toString() },
        ),
    )

    /**
     * 当前方案是否为小鹤双拼（double_pinyin_flypy / 含 flypy）。
     * 试验阶段仅对小鹤启用分解提示，避免其它双拼方案键位不一致导致误显示。
     */
    fun isFlypySchema(schemaId: String): Boolean {
        if (schemaId.isEmpty()) return false
        return schemaId.lowercase().contains("flypy")
    }

    /** 第一键 → 声母（如 v → zh、b → b）。 */
    fun shengmuForKey(key: String): String? = SHENGMU[key]

    /** 第二键 → 韵母（如 c → ao、d → ai）。 */
    fun yunmuForKey(key: String): String? = YUNMU[key]

    /** 第二键 → 韵母列表（双韵母键返回两个，如 l → [iang, uang]）。 */
    fun yunmuListForKey(key: String): List<String> =
        YUNMU_DUAL[key] ?: YUNMU[key]?.let { listOf(it) } ?: emptyList()

    /**
     * 动态键面标签。
     *
     * 小鹤双拼一个音节 = 声母键 + 韵母键：
     * - 输入偶数个键（含 0，即还没输入或已完成一个音节）→ 键面显示声母映射（q→q、v→zh、i→ch、u→sh）；
     * - 输入奇数个键（已输声母，等待韵母）→ 键面切换显示韵母映射（q→iu、c→ao…）。
     * 双韵母键（l/k/s/x/o/r/v）在韵母态用 `\n` 上下两排显示两个韵母。
     *
     * @param showYunmu 是否显示韵母映射
     */
    fun keyLabel(key: String, showYunmu: Boolean): String {
        if (!showYunmu) return SHENGMU[key] ?: key
        return yunmuListForKey(key).joinToString("\n") { it } .ifEmpty { key }
    }

    /** 是否处于"等待韵母"状态：当前输入键数（不含分隔符）为奇数。 */
    fun shouldShowYunmu(keys: String): Boolean {
        var n = 0
        for (c in keys) {
            if (c in 'a'..'z' || c in 'A'..'Z') n++
        }
        return n % 2 == 1
    }

    /**
     * 把键入键位分解为「声母 + 韵母」列表。
     *
     * - 按 2 键一个音节切分（小鹤双拼声母+韵母）；
     * - 奇数键时最后一个键按单键处理（可能为零声母韵母键，仅显示声母映射）；
     * - 无法映射的键原样显示。
     */
    fun decompose(keys: String): List<String> {
        if (keys.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var i = 0
        val n = keys.length
        while (i < n) {
            if (i + 1 < n) {
                val s = SHENGMU[keys[i].toString()]
                val y = YUNMU[keys[i + 1].toString()]
                result.add(
                    if (s != null && y != null) "$s + $y"
                    else "${keys[i]}${keys[i + 1]}"
                )
                i += 2
            } else {
                val s = SHENGMU[keys[i].toString()]
                result.add(s ?: keys[i].toString())
                i += 1
            }
        }
        return result
    }
}
