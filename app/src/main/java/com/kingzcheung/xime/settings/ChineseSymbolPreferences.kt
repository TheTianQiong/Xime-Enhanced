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

    private const val KEY_ROW2 = "cn_symbol_row2"
    private const val KEY_ROW3 = "cn_symbol_row3"

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

    /** 恢复默认。 */
    fun reset(context: Context) {
        SettingsPreferences.getPrefsPublic(context).edit()
            .remove(KEY_ROW2)
            .remove(KEY_ROW3)
            .apply()
    }

    /** 是否已偏离默认值（用于设置页展示「恢复默认」可用性）。 */
    fun isCustomized(context: Context): Boolean =
        getRow2(context) != DEFAULT_ROW2 || getRow3(context) != DEFAULT_ROW3

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
}
