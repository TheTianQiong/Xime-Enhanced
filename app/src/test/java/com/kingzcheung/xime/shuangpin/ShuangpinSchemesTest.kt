package com.kingzcheung.xime.shuangpin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuangpinSchemesTest {

    @Test
    fun `检测各双拼方案`() {
        assertEquals("flypy", ShuangpinSchemes.detect("double_pinyin_flypy")?.id)
        assertEquals("tongyong", ShuangpinSchemes.detect("double_pinyin")?.id)
        assertEquals("ziranma", ShuangpinSchemes.detect("double_pinyin_ziguang")?.id)
        assertEquals("mspy", ShuangpinSchemes.detect("double_pinyin_mspy")?.id)
        assertEquals("abc", ShuangpinSchemes.detect("double_pinyin_abc")?.id)
        assertEquals("sogou", ShuangpinSchemes.detect("double_pinyin_sogou")?.id)
        assertEquals("jiajia", ShuangpinSchemes.detect("double_pinyin_jiajia")?.id)
        assertNull(ShuangpinSchemes.detect("wubi86"))
        assertNull(ShuangpinSchemes.detect(""))
        assertFalse(ShuangpinSchemes.isShuangpinSchema("wubi86"))
        assertTrue(ShuangpinSchemes.isShuangpinSchema("double_pinyin_mspy"))
    }

    @Test
    fun `自然码双拼对应关系`() {
        val ziranma = ShuangpinSchemes.detect("double_pinyin_ziguang")!!
        // 声母：zh→u、ch→a、sh→i
        assertEquals("zh", ziranma.shengmuForKey("u"))
        assertEquals("ch", ziranma.shengmuForKey("a"))
        assertEquals("sh", ziranma.shengmuForKey("i"))
        // 韵母：w→en、t→eng、m→un、j→er/iu
        assertEquals("en", ziranma.keyLabel("w", showYunmu = true))
        assertEquals("eng", ziranma.keyLabel("t", showYunmu = true))
        assertEquals("un", ziranma.keyLabel("m", showYunmu = true))
        assertEquals("er\niu", ziranma.keyLabel("j", showYunmu = true))
        // 三韵母键 n→ue/ve/ui 用括号合并为两行
        assertEquals("u(v)e\nui", ziranma.keyLabel("n", showYunmu = true))
    }

    @Test
    fun `微软双拼对应关系`() {
        val mspy = ShuangpinSchemes.detect("double_pinyin_mspy")!!
        assertEquals("zh", mspy.shengmuForKey("v"))
        assertEquals("ch", mspy.shengmuForKey("i"))
        assertEquals("sh", mspy.shengmuForKey("u"))
        assertEquals("iu", mspy.keyLabel("q", showYunmu = true))
        assertEquals("er\nuan", mspy.keyLabel("r", showYunmu = true))
        assertEquals("ong\niong", mspy.keyLabel("s", showYunmu = true))
    }

    @Test
    fun `智能ABC双拼对应关系`() {
        val abc = ShuangpinSchemes.detect("double_pinyin_abc")!!
        assertEquals("zh", abc.shengmuForKey("a"))
        assertEquals("ch", abc.shengmuForKey("e"))
        assertEquals("sh", abc.shengmuForKey("v"))
        assertEquals("ei", abc.keyLabel("q", showYunmu = true))
        assertEquals("ing", abc.keyLabel("y", showYunmu = true))
        // 三韵母键 m→ue/ve/ui 用括号合并为两行
        assertEquals("u(v)e\nui", abc.keyLabel("m", showYunmu = true))
    }

    @Test
    fun `加加双拼对应关系`() {
        val jiajia = ShuangpinSchemes.detect("double_pinyin_jiajia")!!
        assertEquals("zh", jiajia.shengmuForKey("v"))
        assertEquals("ch", jiajia.shengmuForKey("u"))
        assertEquals("sh", jiajia.shengmuForKey("i"))
        assertEquals("iu", jiajia.keyLabel("n", showYunmu = true))
        assertEquals("er\ning", jiajia.keyLabel("q", showYunmu = true))
        // 三韵母键 x→ue/ve/uai 用括号合并为两行
        assertEquals("u(v)e\nuai", jiajia.keyLabel("x", showYunmu = true))
    }

    @Test
    fun `通用双拼对应关系`() {
        val tongyong = ShuangpinSchemes.detect("double_pinyin")!!
        assertNotNull(tongyong)
        assertEquals("zh", tongyong.shengmuForKey("v"))
        assertEquals("ch", tongyong.shengmuForKey("i"))
        assertEquals("sh", tongyong.shengmuForKey("u"))
        assertEquals("iu", tongyong.keyLabel("q", showYunmu = true))
        assertEquals("ing\nuai", tongyong.keyLabel("y", showYunmu = true))
    }
}
