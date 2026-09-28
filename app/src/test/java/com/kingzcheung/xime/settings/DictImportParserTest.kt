package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词库导入解析与合并。
 *
 * 覆盖 rime `.dict.yaml` 数据段、纯文本词表、自定义短语 stabledb 格式，
 * 以及「统一频次」与去重规则。
 */
class DictImportParserTest {

    private fun entries(vararg pairs: Pair<String, String>) =
        pairs.map { DictEntry(it.first, it.second, null) }

    // ── 解析 ──

    @Test
    fun `解析制表符分隔的词表`() {
        val text = "心肌梗死\txjgs\n高血压\tgxy\n"

        val parsed = DictImportParser.parse(text)

        assertEquals(2, parsed.entries.size)
        assertEquals(DictEntry("心肌梗死", "xjgs", null), parsed.entries[0])
        assertEquals(DictEntry("高血压", "gxy", null), parsed.entries[1])
        assertEquals(0, parsed.skippedNoCode)
    }

    @Test
    fun `解析空格分隔的词表`() {
        val text = "心肌梗死 xjgs\n高血压 gxy\n"

        val parsed = DictImportParser.parse(text)

        assertEquals(listOf("心肌梗死", "高血压"), parsed.entries.map { it.word })
        assertEquals(listOf("xjgs", "gxy"), parsed.entries.map { it.code })
    }

    @Test
    fun `解析词条自带的频次`() {
        val text = "心肌梗死\txjgs\t120\n高血压\tgxy\n"

        val parsed = DictImportParser.parse(text)

        assertEquals(120, parsed.entries[0].weight)
        assertEquals(null, parsed.entries[1].weight)
    }

    @Test
    fun `缺省频次时套用统一频次`() {
        val text = "心肌梗死\txjgs\n高血压\tgxy\t120\n"

        val parsed = DictImportParser.parse(text, defaultWeight = 99)

        assertEquals("未带频次者套用默认", 99, parsed.entries[0].weight)
        assertEquals("自带频次优先于默认", 120, parsed.entries[1].weight)
    }

    @Test
    fun `频次非数字时视为未设置`() {
        val text = "心肌梗死\txjgs\tabc\n"

        val parsed = DictImportParser.parse(text, defaultWeight = 50)

        assertEquals(50, parsed.entries[0].weight)
    }

    @Test
    fun `dict yaml 跳过元数据段`() {
        // `...` 之前是方案元数据（name/version/sort/columns），不应被当作词条
        val text = """
            # 医学词库
            name: medical
            version: "1.0"
            sort: by_weight
            columns:
              - text
              - code
              - weight
            ...
            心肌梗死	xjgs	100
            高血压	gxy	90
        """.trimIndent()

        val parsed = DictImportParser.parse(text)

        assertEquals(2, parsed.entries.size)
        assertEquals(DictEntry("心肌梗死", "xjgs", 100), parsed.entries[0])
    }

    @Test
    fun `跳过注释空行与文档分隔`() {
        val text = "\n# 注释\n---\n心肌梗死\txjgs\n\n"

        val parsed = DictImportParser.parse(text)

        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun `缺少编码的行被跳过并计数`() {
        // rime 需编码才能命中：只有词的行无法导入
        val text = "心肌梗死\txjgs\n只有词没有码\n另一条\t \n"

        val parsed = DictImportParser.parse(text)

        assertEquals(1, parsed.entries.size)
        assertEquals(2, parsed.skippedNoCode)
    }

    @Test
    fun `同一词同码重复时保留频次较大者`() {
        val text = "心肌梗死\txjgs\t100\n心肌梗死\txjgs\t150\n"

        val parsed = DictImportParser.parse(text)

        assertEquals(1, parsed.entries.size)
        assertEquals(150, parsed.entries[0].weight)
        assertEquals(1, parsed.duplicatesMerged)
    }

    @Test
    fun `同词不同码视为不同条目`() {
        val text = "心肌梗死\txjgs\n心肌梗死\txinfarction\n"

        val parsed = DictImportParser.parse(text)

        assertEquals(2, parsed.entries.size)
    }

    @Test
    fun `兼容 CRLF 与多余列`() {
        val text = "心肌梗死\txjgs\t100\t多余列\r\n"

        val parsed = DictImportParser.parse(text)

        assertEquals(1, parsed.entries.size)
        assertEquals(100, parsed.entries[0].weight)
    }

    @Test
    fun `空文本解析为空`() {
        val parsed = DictImportParser.parse("")

        assertTrue(parsed.entries.isEmpty())
        assertEquals(0, parsed.skippedNoCode)
    }

    // ── 合并 ──

    @Test
    fun `合并时新增条目追加在末尾`() {
        val existing = entries("已有" to "yy")
        val imported = entries("心肌梗死" to "xjgs")

        val result = DictImportParser.merge(existing, imported)

        assertEquals(1, result.added)
        assertEquals(listOf("已有", "心肌梗死"), result.entries.map { it.word })
    }

    @Test
    fun `导入频次更大时覆盖既有条目`() {
        val existing = listOf(DictEntry("心肌梗死", "xjgs", 10))
        val imported = listOf(DictEntry("心肌梗死", "xjgs", 200))

        val result = DictImportParser.merge(existing, imported)

        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(200, result.entries.single().weight)
    }

    @Test
    fun `导入频次更小时保留既有条目`() {
        val existing = listOf(DictEntry("心肌梗死", "xjgs", 200))
        val imported = listOf(DictEntry("心肌梗死", "xjgs", 10))

        val result = DictImportParser.merge(existing, imported)

        assertEquals(1, result.unchanged)
        assertEquals(0, result.updated)
        assertEquals(200, result.entries.single().weight)
    }

    @Test
    fun `既有条目无频次时导入频次可补上`() {
        val existing = listOf(DictEntry("心肌梗死", "xjgs", null))
        val imported = listOf(DictEntry("心肌梗死", "xjgs", 99))

        val result = DictImportParser.merge(existing, imported)

        assertEquals(1, result.updated)
        assertEquals(99, result.entries.single().weight)
    }

    @Test
    fun `合并保持既有顺序且不改变既有条目数`() {
        val existing = entries("A" to "a", "B" to "b", "C" to "c")
        val imported = entries("B" to "b", "D" to "d")

        val result = DictImportParser.merge(existing, imported)

        assertEquals(listOf("A", "B", "C", "D"), result.entries.map { it.word })
        assertEquals(1, result.added)
    }

    @Test
    fun `导入为空时结果与既有一致`() {
        val existing = entries("A" to "a")

        val result = DictImportParser.merge(existing, emptyList())

        assertEquals(existing, result.entries)
        assertEquals(0, result.added)
    }
}
