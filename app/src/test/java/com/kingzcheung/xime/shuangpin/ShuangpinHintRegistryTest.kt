package com.kingzcheung.xime.shuangpin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表的默认态与注入行为。
 *
 * 磁盘加载路径（[ShuangpinHintRegistry.load]）依赖 Android Context，不在此覆盖；
 * 它只是「读文件 → parse → merge」的薄壳，解析与合并已在 ShuangpinHintsParserTest 覆盖。
 */
class ShuangpinHintRegistryTest {

    @After
    fun restore() {
        // 同一 JVM 内共享单例，用例之间必须复位，避免相互污染
        ShuangpinHintRegistry.setForTest(ShuangpinSchemes.builtIn)
    }

    @Test
    fun `默认使用内置表`() {
        ShuangpinHintRegistry.setForTest(ShuangpinSchemes.builtIn)
        assertEquals(8, ShuangpinHintRegistry.schemes().size)
        assertSame(ShuangpinSchemes.builtIn, ShuangpinHintRegistry.schemes())
        assertEquals("flypy", ShuangpinHintRegistry.detect("double_pinyin_flypy")?.id)
        assertNull(ShuangpinHintRegistry.detect("wubi86"))
    }

    @Test
    fun `注入配置表后检测走新表且版本递增`() {
        val before = ShuangpinHintRegistry.version
        val custom = ShuangpinHintsParser.parse(
            """
            schemes:
              - id: my_sp
                schema_keys: ["my_sp"]
                shengmu: { z: zh }
                yunmu: { a: [a] }
            """.trimIndent()
        )

        ShuangpinHintRegistry.setForTest(ShuangpinHintsParser.merge(ShuangpinSchemes.builtIn, custom))

        assertEquals(9, ShuangpinHintRegistry.schemes().size)
        assertEquals("my_sp", ShuangpinHintRegistry.detect("double_pinyin_my_sp")?.id)
        // 内置方案不受影响
        assertEquals("ziranma", ShuangpinHintRegistry.detect("double_pinyin_zrm")?.id)
        assertNotEquals(before, ShuangpinHintRegistry.version)
    }

    @Test
    fun `配置表整条覆盖内置同名方案`() {
        val custom = ShuangpinHintsParser.parse(
            """
            schemes:
              - id: flypy
                yunmu: { a: [a], b: [in, ing] }
            """.trimIndent()
        )

        ShuangpinHintRegistry.setForTest(ShuangpinHintsParser.merge(ShuangpinSchemes.builtIn, custom))

        val flypy = ShuangpinHintRegistry.detect("double_pinyin_flypy")
        assertEquals("flypy", flypy?.id)
        assertEquals(listOf("in", "ing"), flypy?.yunmuListForKey("b"))
        // 覆盖是整条替换而非字段级合并：只写韵母就意味着声母表为空，
        // 作者需自行给全（与文档口径一致，避免「一半新一半旧」的隐式拼接）
        assertNull(flypy?.shengmuForKey("v"))
        assertEquals("覆盖不改变方案数量", 8, ShuangpinHintRegistry.schemes().size)
    }

    @Test
    fun `ShuangpinSchemes all 仍为内置 8 套`() {
        ShuangpinHintRegistry.setForTest(ShuangpinSchemes.builtIn)
        assertEquals(8, ShuangpinSchemes.all.size)
        assertTrue(ShuangpinSchemes.all.all { it.id.isNotEmpty() })
    }
}
