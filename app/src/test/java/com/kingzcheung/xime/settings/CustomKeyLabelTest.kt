package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自定义键面标签是否该让双拼提示让位」的判定。
 *
 * 这是双拼提示覆盖用户布局标签那个缺陷的判据核心：内置 xime.yaml 给每个字母键
 * 都写了 tap（标签就是字母本身），所以不能按「有 tap.label」判定——
 * 判据是标签**另有内容**。
 */
class CustomKeyLabelTest {

    @Test
    fun `标签与按键字母相同不算另有内容`() {
        assertFalse(KeysConfigHelper.isInformativeCustomLabel("q", "q"))
        // 取值时会把含字母的标签整体大写，比较需忽略大小写
        assertFalse(KeysConfigHelper.isInformativeCustomLabel("q", "Q"))
        assertFalse(KeysConfigHelper.isInformativeCustomLabel("q", "  q  "))
    }

    @Test
    fun `空标签不算另有内容`() {
        assertFalse(KeysConfigHelper.isInformativeCustomLabel("q", null))
        assertFalse(KeysConfigHelper.isInformativeCustomLabel("q", ""))
        assertFalse(KeysConfigHelper.isInformativeCustomLabel("q", "   "))
    }

    @Test
    fun `与字母不同的标签才算另有内容`() {
        assertTrue(KeysConfigHelper.isInformativeCustomLabel("q", "七"))
        assertTrue(KeysConfigHelper.isInformativeCustomLabel("v", "zh"))
        // 多行标签（labels 数组）必然与单字母不同
        assertTrue(KeysConfigHelper.isInformativeCustomLabel("q", "q\n七"))
    }

    @Test
    fun `只挑出另有内容标签的键`() {
        val config = mapOf(
            // 布局只为覆盖手势而重写了 tap，标签仍是字母本身 → 不应让提示让位
            "q" to KeyBinding(tap = KeyAction(value = "q", label = "q"), swipeUp = KeyAction(value = "!")),
            // 明确改了键面 → 提示让位
            "w" to KeyBinding(tap = KeyAction(value = "w", label = "七")),
            // 只有手势、没有 tap → 不算
            "e" to KeyBinding(swipeUp = KeyAction(value = "~")),
            // tap 存在但 label 为空（默认动作）→ 不算
            "r" to KeyBinding(tap = KeyAction()),
        )

        assertEquals(setOf("w"), KeysConfigHelper.customKeyLabelKeysOf(config))
    }

    @Test
    fun `空配置不产生任何键`() {
        assertTrue(KeysConfigHelper.customKeyLabelKeysOf(emptyMap()).isEmpty())
    }
}
