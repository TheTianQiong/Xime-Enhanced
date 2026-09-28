package com.kingzcheung.xime.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框背景环境的收集与联想策略推导。
 *
 * 覆盖：类型分类、联想门禁（密码/数字/邮箱等不适配）、自适应上下文选择。
 */
class EditorEnvironmentTest {

    private fun info(inputType: Int, packageName: String = "com.example.app"): EditorInfo =
        EditorInfo().apply {
            this.inputType = inputType
            this.packageName = packageName
        }

    private fun env(inputType: Int) = EditorEnvironment.from(info(inputType))

    // ── 分类 ──

    @Test
    fun `普通文本识别为 TEXT`() {
        assertEquals(EditorEnvironment.FieldKind.TEXT, env(InputType.TYPE_CLASS_TEXT).fieldKind)
    }

    @Test
    fun `多行文本按 flag 识别`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

        assertEquals(EditorEnvironment.FieldKind.MULTILINE, env(inputType).fieldKind)
    }

    @Test
    fun `短信与网页编辑框识别为多行`() {
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
        ).forEach { inputType ->
            assertEquals(EditorEnvironment.FieldKind.MULTILINE, env(inputType).fieldKind)
        }
    }

    @Test
    fun `数字电话日期时间分类`() {
        assertEquals(EditorEnvironment.FieldKind.NUMBER, env(InputType.TYPE_CLASS_NUMBER).fieldKind)
        assertEquals(EditorEnvironment.FieldKind.PHONE, env(InputType.TYPE_CLASS_PHONE).fieldKind)
        assertEquals(EditorEnvironment.FieldKind.DATETIME, env(InputType.TYPE_CLASS_DATETIME).fieldKind)
    }

    @Test
    fun `邮箱与网址分类`() {
        assertEquals(
            EditorEnvironment.FieldKind.EMAIL,
            env(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).fieldKind,
        )
        assertEquals(
            EditorEnvironment.FieldKind.URL,
            env(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).fieldKind,
        )
    }

    @Test
    fun `密码框识别为 SECRET 而非普通文本`() {
        // 先判密码：密码 variation 属于 TEXT 大类，若不先判会被归为 TEXT
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        assertEquals(EditorEnvironment.FieldKind.SECRET, env(inputType).fieldKind)
    }

    @Test
    fun `TYPE_NULL 识别为 SECRET`() {
        assertEquals(EditorEnvironment.FieldKind.SECRET, env(InputType.TYPE_NULL).fieldKind)
    }

    @Test
    fun `未知 inputType 归入 OTHER`() {
        // 未定义的大类（TYPE_MASK_CLASS 内的非标准值，且非 TYPE_NULL）
        val unknownClass = 0x0000000E

        assertEquals(EditorEnvironment.FieldKind.OTHER, env(unknownClass).fieldKind)
        assertTrue("未知类型不阻断联想", env(unknownClass).associationAllowed)
    }

    // ── 环境收集 ──

    @Test
    fun `收集宿主应用包名`() {
        val environment = EditorEnvironment.from(info(InputType.TYPE_CLASS_TEXT, "com.tencent.mm"))

        assertEquals("com.tencent.mm", environment.packageName)
    }

    @Test
    fun `EditorInfo 为空时返回保守默认值`() {
        val environment = EditorEnvironment.from(null)

        assertEquals("", environment.packageName)
        assertTrue("无信息时不阻断联想", environment.associationAllowed)
    }

    @Test
    fun `包名为 null 时降级为空串`() {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = null
        }

        assertEquals("", EditorEnvironment.from(editorInfo).packageName)
    }

    // ── 联想门禁 ──

    @Test
    fun `文本与多行框允许联想`() {
        assertTrue(env(InputType.TYPE_CLASS_TEXT).associationAllowed)
        assertTrue(
            env(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).associationAllowed
        )
    }

    @Test
    fun `密码与终端框禁止联想`() {
        assertFalse(env(InputType.TYPE_NULL).associationAllowed)
        assertFalse(
            env(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).associationAllowed
        )
    }

    @Test
    fun `数字类框禁止联想`() {
        listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
        ).forEach { inputType ->
            assertFalse(
                "数字类框无中文上下文，应禁用联想",
                env(inputType).associationAllowed,
            )
        }
    }

    @Test
    fun `邮箱与网址框禁止联想`() {
        assertFalse(
            env(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).associationAllowed
        )
        assertFalse(
            env(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).associationAllowed
        )
    }

    @Test
    fun `NO_SUGGESTIONS 的搜索框仍允许联想`() {
        // 与英文联想口径一致：该 flag 只表示宿主不要系统内联补全
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        assertTrue(env(inputType).associationAllowed)
    }

    // ── 自适应上下文 ──

    @Test
    fun `优先采用输入框前文`() {
        val context = EditorEnvironment.adaptiveContext(
            committedText = "刚刚上屏",
            fieldTextBeforeCursor = "尊敬的客户，感谢您",
            maxLength = 25,
        )

        assertEquals("尊敬的客户，感谢您", context)
    }

    @Test
    fun `输入框前文不可读时回退已上屏文本`() {
        val context = EditorEnvironment.adaptiveContext(
            committedText = "已上屏文本",
            fieldTextBeforeCursor = null,
            maxLength = 25,
        )

        assertEquals("已上屏文本", context)
    }

    @Test
    fun `输入框前文为空白时回退已上屏文本`() {
        val context = EditorEnvironment.adaptiveContext(
            committedText = "已上屏文本",
            fieldTextBeforeCursor = "   ",
            maxLength = 25,
        )

        assertEquals("已上屏文本", context)
    }

    @Test
    fun `超长上下文按上限截尾`() {
        val long = "一二三四五六七八九十"

        val context = EditorEnvironment.adaptiveContext(
            committedText = "",
            fieldTextBeforeCursor = long,
            maxLength = 4,
        )

        assertEquals("七八九十", context)
    }

    @Test
    fun `上限为零时返回空`() {
        assertEquals(
            "",
            EditorEnvironment.adaptiveContext("abc", "def", maxLength = 0),
        )
    }

    @Test
    fun `两端都为空时返回空`() {
        assertEquals("", EditorEnvironment.adaptiveContext("", null, 25))
    }
}
