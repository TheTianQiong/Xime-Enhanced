package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 候选词变换（capabilities.candidate_transform + transformCandidates）桥接：
 * - Success：engine_index / text 混合项解析、comment 覆盖透传
 * - NoResponse：未导出函数 / 返回 nil / 空列表
 * - Failed：超时（hotPath 15ms 不中毒）/ 报错 / 格式错误
 * - 校验：非法项丢弃、总数上限截断
 */
class LuaCandidateTransformTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writePlugin(lua: String): File {
        val dir = tmp.newFolder("plugin")
        File(dir, "main.lua").writeText(lua)
        return dir
    }

    private fun runtime(lua: String): LuaScriptRuntime =
        LuaScriptRuntime("cand-test", writePlugin(lua), "main.lua", NoopPluginConfigStore)

    private fun request(
        inputText: String = "dh",
        candidates: List<CandidateTransformCandidate> = listOf(
            CandidateTransformCandidate("的", ""),
            CandidateTransformCandidate("到", ""),
        ),
    ) = CandidateTransformRequest(
        inputText = inputText,
        preedit = inputText,
        candidates = candidates,
        asciiMode = false,
    )

    @Test
    fun `engine_index 与 text 混合解析 comment 透传`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              return { candidates = {
                { engine_index = 0, comment = "改注释" },
                { engine_index = 1 },
                { text = "18500000000", comment = "手机号" },
              } }
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        val outcome = rt.transformCandidates(request())
        val items = (outcome as CandidateTransformOutcome.Success).items
        assertEquals(3, items.size)
        assertEquals(0, items[0].engineIndex)
        assertEquals("改注释", items[0].comment)
        assertEquals(1, items[1].engineIndex)
        assertEquals(null, items[1].comment)
        assertEquals(null, items[2].engineIndex)
        assertEquals("18500000000", items[2].text)
        assertEquals("手机号", items[2].comment)
        rt.close()
    }

    @Test
    fun `请求字段传递 input_text 与 candidates`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              return { candidates = {
                { text = req.input_text .. ":" .. #req.candidates .. ":" .. tostring(req.ascii_mode) },
              } }
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        val outcome = rt.transformCandidates(request(inputText = "dh"))
        val text = (outcome as CandidateTransformOutcome.Success).items[0].text
        assertEquals("dh:2:false", text)
        rt.close()
    }

    @Test
    fun `未导出函数返回 NoResponse`() {
        val rt = runtime("local plugin = {}\nreturn plugin")
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.NoResponse, rt.transformCandidates(request()))
        rt.close()
    }

    @Test
    fun `插件返回 nil 表示不干预`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              return nil
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.NoResponse, rt.transformCandidates(request()))
        rt.close()
    }

    @Test
    fun `空候选列表视为不干预`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              return { candidates = {} }
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.NoResponse, rt.transformCandidates(request()))
        rt.close()
    }

    @Test
    fun `格式错误返回 Failed`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              return { wrong_field = 1 }
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.Failed, rt.transformCandidates(request()))
        rt.close()
    }

    @Test
    fun `非法项丢弃（无 text 的非引用项）`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              return { candidates = {
                { comment = "孤立注释" },
                { text = "有效" },
              } }
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        val items = (rt.transformCandidates(request()) as CandidateTransformOutcome.Success).items
        assertEquals(1, items.size)
        assertEquals("有效", items[0].text)
        rt.close()
    }

    @Test
    fun `空 text 项丢弃`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              return { candidates = {
                { text = "" },
                { text = "有效" },
              } }
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        val items = (rt.transformCandidates(request()) as CandidateTransformOutcome.Success).items
        assertEquals(1, items.size)
        rt.close()
    }

    @Test
    fun `总数上限截断到 20`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              local out = {}
              for i = 1, 30 do
                table.insert(out, { text = "c" .. i })
              end
              return { candidates = out }
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        val items = (rt.transformCandidates(request()) as CandidateTransformOutcome.Success).items
        assertEquals(20, items.size)
        assertEquals("c20", items[19].text)
        rt.close()
    }

    @Test
    fun `超时返回 Failed（hotPath 15ms 不中毒）`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              local x = 0
              while true do x = x + 1 end
              return nil
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        val outcome = rt.transformCandidates(request())
        assertEquals(CandidateTransformOutcome.Failed, outcome)
        // 不调用 close()：死循环线程不响应中断，close 的 unload 会等满 180s 业务超时
    }

    @Test
    fun `Lua 报错返回 Failed`() {
        val rt = runtime(
            """
            local plugin = {}
            function plugin.transformCandidates(req)
              error("boom")
            end
            return plugin
            """.trimIndent()
        )
        assertTrue(rt.load())
        assertEquals(CandidateTransformOutcome.Failed, rt.transformCandidates(request()))
        rt.close()
    }
}
