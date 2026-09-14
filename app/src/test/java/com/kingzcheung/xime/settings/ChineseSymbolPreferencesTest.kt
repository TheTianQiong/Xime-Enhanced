package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 中文符号自定义的序列化与脏数据保护。
 *
 * 只覆盖纯逻辑（encode/decode 与默认值不变量）；带 Context 的读写属于
 * SharedPreferences 薄封装，不在此重复验证。
 */
class ChineseSymbolPreferencesTest {

    @Test
    fun `默认值与英文半角表数量一致`() {
        // symbolKeys 用 zip 组键，数量不一致会被静默截断导致缺键
        assertEquals(
            ChineseSymbolPreferences.ASCII_ROW2.size,
            ChineseSymbolPreferences.DEFAULT_ROW2.size,
        )
        assertEquals(
            ChineseSymbolPreferences.ASCII_ROW3.size,
            ChineseSymbolPreferences.DEFAULT_ROW3.size,
        )
    }

    @Test
    fun `默认值均为单个全角字符`() {
        (ChineseSymbolPreferences.DEFAULT_ROW2 + ChineseSymbolPreferences.DEFAULT_ROW3).forEach { ch ->
            assertEquals("每个符号位应为单个字符: $ch", 1, ch.length)
            assertNotEquals("中文默认值不应是半角字符: $ch", ChineseSymbolPreferences.ASCII_ROW2.contains(ch), true)
        }
    }

    @Test
    fun `编码后可完整还原`() {
        val custom = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
        val encoded = ChineseSymbolPreferences.encode(custom)

        assertEquals(custom, ChineseSymbolPreferences.decode(encoded, ChineseSymbolPreferences.DEFAULT_ROW2))
    }

    @Test
    fun `未存储时回退默认`() {
        assertEquals(
            ChineseSymbolPreferences.DEFAULT_ROW2,
            ChineseSymbolPreferences.decode(null, ChineseSymbolPreferences.DEFAULT_ROW2),
        )
        assertEquals(
            ChineseSymbolPreferences.DEFAULT_ROW3,
            ChineseSymbolPreferences.decode("", ChineseSymbolPreferences.DEFAULT_ROW3),
        )
    }

    @Test
    fun `条目数不符时回退默认`() {
        // 少于默认（历史脏数据）与多于默认（符号被拆开）都应整体回退，
        // 避免键位错位
        val short = ChineseSymbolPreferences.encode(listOf("＠", "＃"))
        val long = ChineseSymbolPreferences.encode(
            ChineseSymbolPreferences.DEFAULT_ROW2 + listOf("＋")
        )

        assertEquals(
            ChineseSymbolPreferences.DEFAULT_ROW2,
            ChineseSymbolPreferences.decode(short, ChineseSymbolPreferences.DEFAULT_ROW2),
        )
        assertEquals(
            ChineseSymbolPreferences.DEFAULT_ROW2,
            ChineseSymbolPreferences.decode(long, ChineseSymbolPreferences.DEFAULT_ROW2),
        )
    }

    @Test
    fun `存在空项时回退默认`() {
        val withEmpty = ChineseSymbolPreferences.encode(
            ChineseSymbolPreferences.DEFAULT_ROW2.toMutableList().also { it[3] = "" }
        )

        assertEquals(
            ChineseSymbolPreferences.DEFAULT_ROW2,
            ChineseSymbolPreferences.decode(withEmpty, ChineseSymbolPreferences.DEFAULT_ROW2),
        )
    }

    @Test
    fun `自定义值可含常见全角与兼容字符`() {
        // 用户可能填 CJK 兼容形式（如 ﹟﹪﹡）或任意单字符
        val custom = listOf("﹫", "﹟", "＄", "＆", "＿", "－", "＋", "（", "）", "／")
        assertEquals(
            custom,
            ChineseSymbolPreferences.decode(
                ChineseSymbolPreferences.encode(custom),
                ChineseSymbolPreferences.DEFAULT_ROW2,
            ),
        )
    }

    // ── 上滑手势覆盖表 ──

    @Test
    fun `上滑按键与默认字符数量一致`() {
        // 设置页按下标对齐展示「按键 · 默认字符」，数量不一致会错位
        assertEquals(
            ChineseSymbolPreferences.SWIPE_KEYS.size,
            ChineseSymbolPreferences.DEFAULT_SWIPE.size,
        )
    }

    @Test
    fun `上滑覆盖表编码后可完整还原`() {
        val overrides = mapOf("a" to "～", "s" to "／", "m" to "＃")
        assertEquals(
            overrides,
            ChineseSymbolPreferences.decodePairs(ChineseSymbolPreferences.encodePairs(overrides)),
        )
    }

    @Test
    fun `上滑覆盖表未存储时为空`() {
        assertEquals(emptyMap<String, String>(), ChineseSymbolPreferences.decodePairs(null))
        assertEquals(emptyMap<String, String>(), ChineseSymbolPreferences.decodePairs(""))
    }

    @Test
    fun `上滑覆盖表忽略脏项`() {
        // 缺 '='、空键、空值都应丢弃，只保留合法项
        val raw = listOf("a=～", "broken", "=x", "s=", "m=＃").joinToString("")
        assertEquals(
            mapOf("a" to "～", "m" to "＃"),
            ChineseSymbolPreferences.decodePairs(raw),
        )
    }

    @Test
    fun `上滑覆盖表丢弃空值项`() {
        // 空值等价于「未覆盖」，不应写入存储
        assertEquals("a=～", ChineseSymbolPreferences.encodePairs(mapOf("a" to "～", "s" to "")))
        assertEquals("", ChineseSymbolPreferences.encodePairs(emptyMap()))
    }
}
