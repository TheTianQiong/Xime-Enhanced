package com.kingzcheung.xime.settings

import android.content.Context

/**
 * 中文环境符号自定义。
 *
 * 只改中文（全角）一侧的字符，英文（半角）一侧固定不变：
 * 符号键盘在中文模式显示/上屏 [DEFAULT_ROW2] / [DEFAULT_ROW3] 中对应位置的字符，
 * 用户可在「外观与交互 → 布局与显示 → 按键手势 → 中文符号自定义」中逐位修改。
 *
 * 存储：SharedPreferences 按行保存覆盖值；未覆盖或数据不合法时回退默认，
 * 保证键盘不会因脏数据出现空键。
 */
object ChineseSymbolPreferences {

    /** 符号键盘第二行默认中文（全角）字符，与 [ASCII_ROW2] 一一对应。 */
    val DEFAULT_ROW2 = listOf("＠", "＃", "＄", "＆", "＿", "－", "＋", "（", "）", "／")

    /** 符号键盘第三行默认中文（全角）字符，与 [ASCII_ROW3] 一一对应。 */
    val DEFAULT_ROW3 = listOf("＊", "，", "“", "’", "。", "！", "？")

    /** 英文（半角）字符：不随中文自定义变化。 */
    val ASCII_ROW2 = listOf("@", "#", "$", "&", "_", "-", "+", "(", ")", "/")
    val ASCII_ROW3 = listOf("*", ",", "\"", "'", ".", "!", "?")

    /** 中文模式上滑手势可自定义的按键（对应 xime.yaml 中文 qwerty 的上滑定义）。 */
    val SWIPE_KEYS = listOf(
        "a", "s", "d", "f", "g", "h", "j", "k", "l",
        "z", "x", "c", "v", "b", "n", "m", "'",
    )

    /** 上滑提示的中文默认字符，仅用于设置页展示「当前默认」。 */
    val DEFAULT_SWIPE = listOf(
        "～", "／", "：", "；", "“", "”", "－", "（", "）",
        "＊", "＠", "、", "？", "！", "％", "＃", "。",
    )

    /** 可自定义的方案中文标点：ASCII 输入键，与 [DEFAULT_PUNCT] 一一对应。 */
    val PUNCT_KEYS = listOf(
        "~", "/", "-", "*", "%", "#", "!", "@", "$", "&",
        "_", "+", "(", ")", ":", ";", ",", ".", "?", "^",
    )

    /** 方案 full_shape 的默认中文标点，仅用于设置页展示「当前默认」。 */
    val DEFAULT_PUNCT = listOf(
        "～", "／", "－", "＊", "％", "＃", "！", "＠", "￥", "＆",
        "——", "＋", "（", "）", "：", "；", "，", "。", "？", "……",
    )

    private const val KEY_ROW2 = "cn_symbol_row2"
    private const val KEY_ROW3 = "cn_symbol_row3"
    private const val KEY_SWIPE = "cn_symbol_swipe"
    private const val KEY_PUNCT = "cn_symbol_punct"

    /** 单元分隔符（U+001F）：正常符号不会包含，避免与符号本身冲突。 */
    private val SEPARATOR = 0x1F.toChar().toString()

    fun getRow2(context: Context): List<String> = load(context, KEY_ROW2, DEFAULT_ROW2)

    fun getRow3(context: Context): List<String> = load(context, KEY_ROW3, DEFAULT_ROW3)

    fun setRow2(context: Context, chars: List<String>) = save(context, KEY_ROW2, chars)

    fun setRow3(context: Context, chars: List<String>) = save(context, KEY_ROW3, chars)

    /** 修改单个位置（越界忽略）。 */
    fun setRow2Char(context: Context, index: Int, char: String) {
        update(context, KEY_ROW2, DEFAULT_ROW2, index, char)
    }

    fun setRow3Char(context: Context, index: Int, char: String) {
        update(context, KEY_ROW3, DEFAULT_ROW3, index, char)
    }

    // ── 中文模式上滑手势字符覆盖 ──
    // 方案的 swipe_up 提示用全角（如 ～）、实际上屏值却是半角（如 ~），中文模式下
    // 依赖方案标点转换，雾凇拼音等方案下二者不一致。此处允许用户按个人习惯覆盖：
    // 覆盖后「键面提示」与「上屏字符」统一为同一字符。留空表示走方案默认。

    /** 上滑覆盖表：按键 → 字符（仅含已覆盖项）。 */
    fun getSwipeOverrides(context: Context): Map<String, String> =
        decodePairs(SettingsPreferences.getPrefsPublic(context).getString(KEY_SWIPE, null))

    fun setSwipeOverride(context: Context, key: String, char: String) {
        val map = getSwipeOverrides(context).toMutableMap()
        map[key.lowercase()] = char
        savePairs(context, KEY_SWIPE, map)
    }

    /** 清除某个键的覆盖，回到方案默认。 */
    fun removeSwipeOverride(context: Context, key: String) {
        val map = getSwipeOverrides(context).toMutableMap()
        map.remove(key.lowercase())
        savePairs(context, KEY_SWIPE, map)
    }

    /**
     * 中文模式下该键的上滑覆盖字符。
     * 英文模式（[isAsciiMode] = true）或未覆盖时返回 null，由调用方走方案默认值。
     */
    fun swipeUpOverride(context: Context, key: String, isAsciiMode: Boolean): String? =
        if (isAsciiMode) null else getSwipeOverrides(context)[key.lowercase()]

    // ── 方案中文标点覆盖（写入 default.custom.yaml 的 punctuator.full_shape）──
    // 方案的 full_shape 决定中文模式下 ASCII 标点转成哪个全角标点，
    // 影响所有 import_preset: default 的方案（含雾凇拼音）。

    /** ASCII 输入键 → 覆盖后的中文标点（仅含已覆盖项）。 */
    fun getPunctuationOverrides(context: Context): Map<String, String> =
        decodePairs(SettingsPreferences.getPrefsPublic(context).getString(KEY_PUNCT, null))

    fun setPunctuationOverride(context: Context, key: String, text: String) {
        val map = getPunctuationOverrides(context).toMutableMap()
        map[key] = text
        savePairs(context, KEY_PUNCT, map)
    }

    fun removePunctuationOverride(context: Context, key: String) {
        val map = getPunctuationOverrides(context).toMutableMap()
        map.remove(key)
        savePairs(context, KEY_PUNCT, map)
    }

    private fun savePairs(context: Context, prefKey: String, map: Map<String, String>) {
        val text = encodePairs(map)
        SettingsPreferences.getPrefsPublic(context).edit().apply {
            if (text.isEmpty()) remove(prefKey) else putString(prefKey, text)
        }.apply()
    }

    /** 恢复默认（含上滑与方案标点覆盖）。 */
    fun reset(context: Context) {
        SettingsPreferences.getPrefsPublic(context).edit()
            .remove(KEY_ROW2)
            .remove(KEY_ROW3)
            .remove(KEY_SWIPE)
            .remove(KEY_PUNCT)
            .apply()
    }

    /** 是否已偏离默认值（用于设置页展示「恢复默认」可用性）。 */
    fun isCustomized(context: Context): Boolean =
        getRow2(context) != DEFAULT_ROW2 ||
            getRow3(context) != DEFAULT_ROW3 ||
            getSwipeOverrides(context).isNotEmpty() ||
            getPunctuationOverrides(context).isNotEmpty()

    private fun update(
        context: Context,
        key: String,
        defaults: List<String>,
        index: Int,
        char: String,
    ) {
        if (index !in defaults.indices) return
        val current = load(context, key, defaults).toMutableList()
        current[index] = char
        save(context, key, current)
    }

    private fun load(context: Context, key: String, defaults: List<String>): List<String> =
        decode(SettingsPreferences.getPrefsPublic(context).getString(key, null), defaults)

    private fun save(context: Context, key: String, chars: List<String>) {
        SettingsPreferences.getPrefsPublic(context).edit().putString(key, encode(chars)).apply()
    }

    /** 反序列化：条目数与默认不一致、或存在空项时回退默认（脏数据保护）。 */
    internal fun decode(raw: String?, defaults: List<String>): List<String> {
        if (raw.isNullOrEmpty()) return defaults
        val parts = raw.split(SEPARATOR)
        if (parts.size != defaults.size) return defaults
        if (parts.any { it.isEmpty() }) return defaults
        return parts
    }

    internal fun encode(chars: List<String>): String = chars.joinToString(SEPARATOR)

    /** 序列化「按键=字符」覆盖表；空值项直接丢弃（等价于未覆盖）。 */
    internal fun encodePairs(map: Map<String, String>): String =
        map.entries
            .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
            .joinToString(SEPARATOR) { "${it.key}=${it.value}" }

    /** 反序列化覆盖表：忽略缺少 '='、键或值为空的脏项。 */
    internal fun decodePairs(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split(SEPARATOR)
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0 || idx == entry.length - 1) return@mapNotNull null
                entry.substring(0, idx) to entry.substring(idx + 1)
            }
            .toMap()
    }
}
