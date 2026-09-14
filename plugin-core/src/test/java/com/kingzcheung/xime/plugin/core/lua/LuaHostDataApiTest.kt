package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 宿主数据只读 API（host.quickSend / host.clipboard）桥接：
 * - 声明式注入：构造传 API → host 表挂出；不传 → host 表不存在（插件内为 nil）
 * - list() 数据映射（id/text/timestamp/isPinned）
 * - clipboard.get() 空文本返回 nil
 */
class LuaHostDataApiTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writePlugin(lua: String): File {
        val dir = tmp.newFolder("plugin")
        File(dir, "main.lua").writeText(lua)
        return dir
    }

    /** 探针插件：把 host API 的观察结果序列化为字符串返回。 */
    private val probeLua = """
        local plugin = {}
        function plugin.probeQuickSend()
          if host.quickSend == nil then return "nil" end
          local l = host.quickSend.list()
          local first = l[1]
          return #l .. "|" .. first.id .. "|" .. first.text .. "|" .. (first.code or "") .. "|" .. tostring(first.isPinned)
        end
        function plugin.probeClipboard()
          if host.clipboard == nil then return "nil" end
          local t = host.clipboard.get()
          return t == nil and "nil" or t
        end
        return plugin
    """.trimIndent()

    private fun newRuntime(
        quickSendApi: QuickSendHostApi? = null,
        clipboardApi: ClipboardHostApi? = null,
    ): LuaScriptRuntime = LuaScriptRuntime(
        "hostdata-test", writePlugin(probeLua), "main.lua", NoopPluginConfigStore,
        quickSendHostApi = quickSendApi,
        clipboardHostApi = clipboardApi,
    )

    @Test
    fun `注入 quickSendApi 后 list 数据映射正确`() {
        val rt = newRuntime(
            quickSendApi = object : QuickSendHostApi {
                override fun list() = listOf(
                    QuickSendItem(id = 7, text = "18500000000", code = "dh", timestamp = 1690000000000, isPinned = true),
                    QuickSendItem(id = 8, text = "北京市海淀区", code = "", timestamp = 1690000000001, isPinned = false),
                )
            }
        )
        assertTrue(rt.load())
        assertEquals("2|7|18500000000|dh|true", rt.call("probeQuickSend").tojstring())
        rt.close()
    }

    @Test
    fun `注入 clipboardApi 后 get 返回文本`() {
        val rt = newRuntime(
            clipboardApi = object : ClipboardHostApi {
                override fun getText(): String? = "剪贴板内容"
            }
        )
        assertTrue(rt.load())
        assertEquals("剪贴板内容", rt.call("probeClipboard").tojstring())
        rt.close()
    }

    @Test
    fun `剪贴板为空时 get 返回 nil`() {
        val rt = newRuntime(
            clipboardApi = object : ClipboardHostApi {
                override fun getText(): String? = ""
            }
        )
        assertTrue(rt.load())
        assertEquals("nil", rt.call("probeClipboard").tojstring())
        rt.close()
    }

    @Test
    fun `未声明能力的插件拿不到 host 表`() {
        val rt = newRuntime()
        assertTrue(rt.load())
        assertEquals("nil", rt.call("probeQuickSend").tojstring())
        assertEquals("nil", rt.call("probeClipboard").tojstring())
        rt.close()
    }
}
