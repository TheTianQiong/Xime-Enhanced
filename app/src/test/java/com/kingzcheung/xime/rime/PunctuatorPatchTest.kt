package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * default.custom.yaml 的 punctuator.full_shape 覆盖。
 *
 * 文本片段按 assets/default.custom.yaml 的真实结构构造：full_shape 条目缩进 6 空格，
 * 含带引号键、裸键、键本身是冒号的 `":"`、列表值与映射值；其后跟随 half_shape 块。
 */
class PunctuatorPatchTest {

    private val yaml = """
        patch:
          menu:
            page_size: 20
          punctuator:
            full_shape:
              " ": {commit: "　"}
              "!": {commit: "！"}
              "#": ["＃", "⌘"]
              "*": ["＊", "・", "×"]
              "-": "－"
              _: "——"
              .: {commit: "。"}
              ":": {commit: "："}
              "~": "～"
            half_shape:
              "#": "#"
              "~": "~"
    """.trimIndent() + "\n"

    @Test
    fun `覆盖字符串值且不动其余条目`() {
        val out = PunctuatorPatch.apply(yaml, mapOf("~" to "﹏"))

        assertEquals("      \"~\": \"﹏\"", out.lines().first { it.contains("\"~\"") })
        assertTrue("其余条目不应受影响", out.contains("      \"!\": {commit: \"！\"}"))
        assertTrue("page_size 不应被改动", out.contains("page_size: 20"))
    }

    @Test
    fun `列表值收敛为用户字符`() {
        val out = PunctuatorPatch.apply(yaml, mapOf("*" to "※"))

        assertTrue(out.contains("      \"*\": \"※\""))
        assertFalse("原多候选列表应被替换", out.contains("・"))
    }

    @Test
    fun `映射值收敛为用户字符`() {
        val out = PunctuatorPatch.apply(yaml, mapOf("." to "﹒"))

        assertTrue(out.contains("      .: \"﹒\""))
        assertFalse(out.contains(".: {commit: \"。\"}"))
    }

    @Test
    fun `键本身是冒号时仍能正确解析`() {
        // 该行形如 `":": {commit: "："}`，按首个冒号切分会解析出错误的键
        val out = PunctuatorPatch.apply(yaml, mapOf(":" to "∶"))

        assertTrue(out.contains("      \":\": \"∶\""))
    }

    @Test
    fun `带引号的键可被覆盖且保留引号写法`() {
        // 资产里减号键写作 "-"，替换后应保持带引号形式
        val out = PunctuatorPatch.apply(yaml, mapOf("-" to "﹣"))

        assertTrue(out.contains("      \"-\": \"﹣\""))
        assertFalse(out.contains("\"-\": \"－\""))
    }

    @Test
    fun `裸键可被覆盖且保留裸键写法`() {
        // 资产里下划线键写作裸键 _
        val out = PunctuatorPatch.apply(yaml, mapOf("_" to "＿＿"))

        assertTrue(out.contains("      _: \"＿＿\""))
        assertFalse(out.contains("_: \"——\""))
    }

    @Test
    fun `不影响 half_shape 内的同名键`() {
        val out = PunctuatorPatch.apply(yaml, mapOf("~" to "﹏", "#" to "＃"))

        assertTrue("half_shape 的 ~ 应保持半角", out.contains("      \"~\": \"~\""))
        assertTrue("half_shape 的 # 应保持半角", out.contains("      \"#\": \"#\""))
        assertEquals("half_shape 的 # 只应出现一次", 1, out.lines().count { it == "      \"#\": \"#\"" })
    }

    @Test
    fun `未命中的键原样保留`() {
        assertEquals(yaml, PunctuatorPatch.apply(yaml, mapOf("@" to "＠")))
    }

    @Test
    fun `空覆盖表返回原文`() {
        assertEquals(yaml, PunctuatorPatch.apply(yaml, emptyMap()))
    }

    @Test
    fun `保持结尾换行`() {
        assertTrue(PunctuatorPatch.apply(yaml, mapOf("~" to "﹏")).endsWith("\n"))
    }

    @Test
    fun `无 punctuator 块时返回原文`() {
        val noPunct = "patch:\n  menu:\n    page_size: 20\n"

        assertEquals(noPunct, PunctuatorPatch.apply(noPunct, mapOf("~" to "﹏")))
    }

    @Test
    fun `注解行不被当作条目`() {
        val withComment = """
            patch:
              punctuator:
                full_shape:
                  # "~": "～"
                  "~": "～"
        """.trimIndent() + "\n"

        val out = PunctuatorPatch.apply(withComment, mapOf("~" to "﹏"))

        assertTrue("注释行应保留", out.contains("# \"~\": \"～\""))
        assertTrue("真实条目应被覆盖", out.contains("      \"~\": \"﹏\""))
    }
}
