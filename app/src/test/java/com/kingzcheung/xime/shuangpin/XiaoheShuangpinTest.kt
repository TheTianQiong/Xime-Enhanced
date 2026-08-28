package com.kingzcheung.xime.shuangpin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoheShuangpinTest {

    @Test
    fun `小鹤双拼方案识别`() {
        assertTrue(XiaoheShuangpin.isFlypySchema("double_pinyin_flypy"))
        assertTrue(XiaoheShuangpin.isFlypySchema("DOUBLE_PINYIN_FLYPY"))
        assertFalse(XiaoheShuangpin.isFlypySchema("wubi86"))
        assertFalse(XiaoheShuangpin.isFlypySchema(""))
    }

    @Test
    fun `分解声母加韵母`() {
        // vc = zh + ao
        assertEquals(listOf("zh + ao"), XiaoheShuangpin.decompose("vc"))
        // sc = s + ao
        assertEquals(listOf("s + ao"), XiaoheShuangpin.decompose("sc"))
        // wg = w + eng
        assertEquals(listOf("w + eng"), XiaoheShuangpin.decompose("wg"))
    }

    @Test
    fun `分解多音节`() {
        // vc sc = zhao sao
        assertEquals(listOf("zh + ao", "s + ao"), XiaoheShuangpin.decompose("vcsc"))
    }

    @Test
    fun `奇数键时最后一个键按单键处理`() {
        // v 单独（zh）
        assertEquals(listOf("zh"), XiaoheShuangpin.decompose("v"))
        // vc + v
        assertEquals(listOf("zh + ao", "zh"), XiaoheShuangpin.decompose("vcv"))
    }

    @Test
    fun `z加ou键位映射为声母加韵母`() {
        // zz = z + ou
        assertEquals(listOf("z + ou"), XiaoheShuangpin.decompose("zz"))
    }

    @Test
    fun `无法映射的字符原样显示`() {
        // 数字键不属于双拼键位，走兜底原样显示
        assertEquals(listOf("11"), XiaoheShuangpin.decompose("11"))
        assertEquals(listOf("zh + ao", "11"), XiaoheShuangpin.decompose("vc11"))
    }

    @Test
    fun `空输入返回空列表`() {
        assertEquals(emptyList<String>(), XiaoheShuangpin.decompose(""))
    }

    @Test
    fun `声母模式键面标签`() {
        // 未输入时显示声母映射
        assertEquals("q", XiaoheShuangpin.keyLabel("q", showYunmu = false))
        assertEquals("zh", XiaoheShuangpin.keyLabel("v", showYunmu = false))
        assertEquals("ch", XiaoheShuangpin.keyLabel("i", showYunmu = false))
        assertEquals("sh", XiaoheShuangpin.keyLabel("u", showYunmu = false))
    }

    @Test
    fun `韵母模式键面标签`() {
        // 已输声母后显示韵母映射
        assertEquals("iu", XiaoheShuangpin.keyLabel("q", showYunmu = true))
        assertEquals("ao", XiaoheShuangpin.keyLabel("c", showYunmu = true))
        assertEquals("ai", XiaoheShuangpin.keyLabel("d", showYunmu = true))
    }

    @Test
    fun `双韵母键上下两排显示`() {
        assertEquals("ue\nve", XiaoheShuangpin.keyLabel("t", showYunmu = true))
        assertEquals("o\nuo", XiaoheShuangpin.keyLabel("o", showYunmu = true))
        assertEquals("ong\niong", XiaoheShuangpin.keyLabel("s", showYunmu = true))
        assertEquals("uai\ning", XiaoheShuangpin.keyLabel("k", showYunmu = true))
        assertEquals("iang\nuang", XiaoheShuangpin.keyLabel("l", showYunmu = true))
        assertEquals("ia\nua", XiaoheShuangpin.keyLabel("x", showYunmu = true))
        assertEquals("ui\nv", XiaoheShuangpin.keyLabel("v", showYunmu = true))
    }

    @Test
    fun `单韵母键列表`() {
        assertEquals(listOf("iang", "uang"), XiaoheShuangpin.yunmuListForKey("l"))
        assertEquals(listOf("ue", "ve"), XiaoheShuangpin.yunmuListForKey("t"))
        // r 键仅 uan（非双韵母）
        assertEquals(listOf("uan"), XiaoheShuangpin.yunmuListForKey("r"))
        assertEquals(listOf("ai"), XiaoheShuangpin.yunmuListForKey("d"))
        assertEquals(emptyList<String>(), XiaoheShuangpin.yunmuListForKey("1"))
    }

    @Test
    fun `无映射键回退原字符`() {
        assertEquals("1", XiaoheShuangpin.keyLabel("1", showYunmu = true))
        assertEquals("1", XiaoheShuangpin.keyLabel("1", showYunmu = false))
    }

    @Test
    fun `奇偶键位决定是否显示韵母`() {
        assertFalse(XiaoheShuangpin.shouldShowYunmu(""))       // 0 键 → 声母
        assertTrue(XiaoheShuangpin.shouldShowYunmu("v"))       // 1 键（已输声母）→ 韵母
        assertFalse(XiaoheShuangpin.shouldShowYunmu("vc"))     // 2 键（音节完成）→ 声母
        assertTrue(XiaoheShuangpin.shouldShowYunmu("vcv"))     // 3 键（下个音节声母已输）→ 韵母
    }
}
