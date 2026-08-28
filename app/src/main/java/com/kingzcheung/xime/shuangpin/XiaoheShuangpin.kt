package com.kingzcheung.xime.shuangpin

/**
 * 小鹤双拼（double_pinyin_flypy）兼容入口。
 *
 * 数据与逻辑已泛化到 [ShuangpinSchemes.FLYPY]，本对象保留原 API 以便测试与面板复用。
 */
object XiaoheShuangpin {

    val scheme: ShuangpinScheme
        get() = ShuangpinSchemes.FLYPY

    val groups: List<ShuangpinScheme.Group>
        get() = scheme.groups()

    fun isFlypySchema(schemaId: String): Boolean {
        if (schemaId.isEmpty()) return false
        return schemaId.lowercase().contains("flypy")
    }

    fun shengmuForKey(key: String): String? = scheme.shengmuForKey(key)

    fun yunmuForKey(key: String): String? = scheme.yunmuListForKey(key).firstOrNull()

    fun yunmuListForKey(key: String): List<String> = scheme.yunmuListForKey(key)

    fun keyLabel(key: String, showYunmu: Boolean): String = scheme.keyLabel(key, showYunmu)

    fun shouldShowYunmu(keys: String): Boolean = ShuangpinSchemes.shouldShowYunmu(keys)

    fun decompose(keys: String): List<String> = scheme.decompose(keys)
}
