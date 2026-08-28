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
}
