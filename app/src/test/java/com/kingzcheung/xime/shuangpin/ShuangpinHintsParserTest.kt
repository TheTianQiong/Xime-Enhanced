package com.kingzcheung.xime.shuangpin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示表解析与合并的单测。
 *
 * 这些输入全部来自用户手写或第三方布局包，因此重点覆盖「坏输入绝不抛异常、
 * 只影响自身条目」——提示表坏掉必须退回内置表，不能让键盘不可用。
 */
class ShuangpinHintsParserTest {

    @Test
    fun `合法提示表解析出全部字段`() {
        val text = """
            version: 1
            schemes:
              - id: flypy
                name: 小鹤双拼（改）
                schema_keys: ["flypy", "double_pinyin_flypy"]
                shengmu:
                  v: zh
                  i: ch
                  u: sh
                yunmu:
                  a: [a]
                  k: [uai, ing]
        """.trimIndent()

        val schemes = ShuangpinHintsParser.parse(text)
        assertEquals(1, schemes.size)
        val s = schemes.single()
        assertEquals("flypy", s.id)
        assertEquals("小鹤双拼（改）", s.displayName)
        assertEquals(listOf("flypy", "double_pinyin_flypy"), s.schemaKeys)
        assertEquals("zh", s.shengmuForKey("v"))
        assertEquals(listOf("uai", "ing"), s.yunmuListForKey("k"))
    }

    @Test
    fun `name 与 schema_keys 缺省时回退到 id`() {
        val text = """
            schemes:
              - id: my_sp
                shengmu:
                  z: zh
        """.trimIndent()

        val s = ShuangpinHintsParser.parse(text).single()
        assertEquals("my_sp", s.displayName)
        assertEquals(listOf("my_sp"), s.schemaKeys)
    }

    @Test
    fun `韵母支持标量写法（视为单元素列表）`() {
        val text = """
            schemes:
              - id: demo
                yunmu:
                  a: a
                  b: [ou, uo]
        """.trimIndent()

        val s = ShuangpinHintsParser.parse(text).single()
        assertEquals(listOf("a"), s.yunmuListForKey("a"))
        assertEquals(listOf("ou", "uo"), s.yunmuListForKey("b"))
    }

    @Test
    fun `三韵母键沿用内置的两行括号合并`() {
        // 配置表构造出的实例必须与内置实例共用同一套 keyLabel 逻辑
        val text = """
            schemes:
              - id: demo
                yunmu:
                  n: [ue, ve, ui]
        """.trimIndent()

        val s = ShuangpinHintsParser.parse(text).single()
        assertEquals("u(v)e\nui", s.keyLabel("n", showYunmu = true))
    }

    @Test
    fun `非法 YAML 整表返回空列表`() {
        assertEquals(emptyList<ShuangpinScheme>(), ShuangpinHintsParser.parse("schemes: ["))
        assertEquals(emptyList<ShuangpinScheme>(), ShuangpinHintsParser.parse(""))
    }

    @Test
    fun `schemes 缺失或非列表时返回空列表`() {
        assertEquals(emptyList<ShuangpinScheme>(), ShuangpinHintsParser.parse("version: 1"))
        assertEquals(emptyList<ShuangpinScheme>(), ShuangpinHintsParser.parse("schemes: {id: a}"))
    }

    @Test
    fun `单条非法只丢弃该条，其余保留`() {
        val text = """
            schemes:
              - id: ""
                shengmu: { v: zh }
              - id: no_content
                name: 空表
              - id: good
                shengmu: { v: zh }
        """.trimIndent()

        val schemes = ShuangpinHintsParser.parse(text)
        assertEquals(1, schemes.size)
        assertEquals("good", schemes.single().id)
    }

    @Test
    fun `重复 id 只保留首条`() {
        val text = """
            schemes:
              - id: dup
                shengmu: { v: zh }
              - id: dup
                shengmu: { v: sh }
        """.trimIndent()

        val schemes = ShuangpinHintsParser.parse(text)
        assertEquals(1, schemes.size)
        assertEquals("zh", schemes.single().shengmuForKey("v"))
    }

    @Test
    fun `非标量项被忽略，未知键忽略`() {
        val text = """
            schemes:
              - id: demo
                unknown_field: whatever
                shengmu:
                  v: zh
                  bad: [a, b]
                yunmu:
                  a: [a, 1]
                  bad: { nested: x }
        """.trimIndent()

        val s = ShuangpinHintsParser.parse(text).single()
        assertEquals("zh", s.shengmuForKey("v"))
        assertNull("列表形态的声母值应被忽略", s.shengmuForKey("bad"))
        // kaml 的标量一律按字符串读入，数字无法与同形文本区分，原样保留
        assertEquals(listOf("a", "1"), s.yunmuListForKey("a"))
        assertTrue("映射形态的韵母值应被忽略", s.yunmuListForKey("bad").isEmpty())
    }

    @Test
    fun `键名统一小写且去空白`() {
        val text = """
            schemes:
              - id: demo
                shengmu:
                  " V ": zh
                yunmu:
                  " K ": [uai, ing]
        """.trimIndent()

        val s = ShuangpinHintsParser.parse(text).single()
        assertEquals("zh", s.shengmuForKey("v"))
        assertEquals(listOf("uai", "ing"), s.yunmuListForKey("k"))
    }

    // ── 合并 ──

    @Test
    fun `同 id 整条覆盖且保留内置位置`() {
        val builtIn = listOf(
            scheme("a", 1), scheme("b", 2), scheme("c", 3),
        )
        val custom = listOf(scheme("b", 99))

        val merged = ShuangpinHintsParser.merge(builtIn, custom)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals(99, merged[1].shengmu.size)
    }

    @Test
    fun `新 id 追加到末尾`() {
        val builtIn = listOf(scheme("a", 1))
        val custom = listOf(scheme("new", 2))

        val merged = ShuangpinHintsParser.merge(builtIn, custom)
        assertEquals(listOf("a", "new"), merged.map { it.id })
    }

    @Test
    fun `空配置表时内置表原样返回`() {
        val builtIn = listOf(scheme("a", 1))
        assertEquals(builtIn, ShuangpinHintsParser.merge(builtIn, emptyList()))
    }

    // ── 检测 ──

    @Test
    fun `检测规则与内置口径一致`() {
        val schemes = listOf(
            scheme("tongyong", 1, schemaKeys = listOf("double_pinyin")),
            scheme("flypy", 1, schemaKeys = listOf("flypy")),
        )
        assertEquals("tongyong", ShuangpinHintsParser.detectIn(schemes, "double_pinyin")?.id)
        // double_pinyin 精确匹配：不能被子方案名误命中
        assertEquals("flypy", ShuangpinHintsParser.detectIn(schemes, "double_pinyin_flypy")?.id)
        assertNull(ShuangpinHintsParser.detectIn(schemes, "wubi86"))
        assertNull(ShuangpinHintsParser.detectIn(schemes, ""))
    }

    @Test
    fun `覆盖内置后检测走新表`() {
        // 内置小鹤以 flypy 匹配；配置改用自己的 schema_keys 后应按新键命中
        val builtIn = listOf(scheme("flypy", 1, schemaKeys = listOf("flypy")))
        val custom = ShuangpinHintsParser.parse(
            """
            schemes:
              - id: flypy
                schema_keys: ["my_flypy"]
                shengmu: { v: zh }
            """.trimIndent()
        )

        val merged = ShuangpinHintsParser.merge(builtIn, custom)
        assertNull(ShuangpinHintsParser.detectIn(merged, "double_pinyin_flypy"))
        assertEquals("flypy", ShuangpinHintsParser.detectIn(merged, "double_pinyin_my_flypy")?.id)
    }

    /** 构造一个带 n 个声母条目的测试方案。 */
    private fun scheme(
        id: String,
        count: Int,
        schemaKeys: List<String> = listOf(id),
    ): ShuangpinScheme = ShuangpinScheme(
        id = id,
        displayName = id,
        shengmu = (1..count).associate { "k$it" to "v$it" },
        yunmu = emptyMap(),
        schemaKeys = schemaKeys,
    )
}
