package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.lua.http.SseHostApi
import com.kingzcheung.xime.plugin.core.lua.http.SseHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 验证宿主 SSE 流式原语在 Lua 侧的注入与桥接：
 * - host.http.stream 发起会话（URL/headers 透传、回调表注册）
 * - 流事件（onData/onDone/onError）从宿主回调 Lua 函数
 * - host.http.closeStream 主动关停会话
 * - host.http.request 透传 timeoutMillis
 */
class LuaSseHostTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockHttpHostApi : HttpHostApi {
        var lastTimeoutMillis: Int? = null
        var lastUrl: String? = null
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            lastTimeoutMillis = timeoutMillis
            lastUrl = url
            return null
        }
        override fun lastError(): String? = null
    }

    private class MockSseHostApi : SseHostApi {
        var connectedUrl: String? = null
        var connectedHeaders: Map<String, String> = emptyMap()
        var listener: SseHostListener? = null
        var closedIds = mutableListOf<Int>()
        var returnId = 100

        override fun connect(
            url: String,
            headers: Map<String, String>,
            listener: SseHostListener,
            timeoutMillis: Int?,
            method: String,
            body: ByteArray?
        ): Int {
            connectedUrl = url
            connectedHeaders = headers
            this.listener = listener
            return returnId
        }
        override fun close(sessionId: Int) { closedIds.add(sessionId) }
        override fun lastError(): String? = null
    }

    private fun buildRuntime(sseMock: MockSseHostApi, httpMock: MockHttpHostApi): LuaScriptRuntime {
        val dir = java.nio.file.Files.createTempDirectory("sse_test").toFile()
        dir.mkdirs()
        File(dir, "main.lua").writeText(
            """
            local M = {}
            local received = {}
            local doneText = ""
            local errorMsg = ""
            local sid = -1

            function M.open_stream()
              sid = host.http.stream("https://api.example.com/v1/chat/completions", {
                Authorization = "Bearer test-key",
                ["Content-Type"] = "application/json",
                Accept = "text/event-stream"
              }, {
                onData = function(text) received[#received + 1] = text end,
                onDone = function(full) doneText = full end,
                onError = function(msg) errorMsg = msg end
              })
              return sid
            end

            function M.get_received()
              local out = {}
              for i, v in ipairs(received) do out[i] = v end
              return out
            end

            function M.get_done() return doneText end
            function M.get_error() return errorMsg end

            function M.close_stream() host.http.closeStream(sid) end

            function M.sync_request()
              return host.http.request("POST", "https://api.example.com/v1/chat", {
                ["Content-Type"] = "application/json"
              }, "{}", 120000)
            end

            return M
            """.trimIndent()
        )
        return LuaScriptRuntime(
            pluginId = "com.kingzcheung.xime.plugin.sse_test",
            pluginDir = dir,
            entryScript = "main.lua",
            configStore = InMemoryConfigStore(),
            httpHostApi = httpMock,
            sseHostApi = sseMock
        )
    }

    @Test
    fun `stream 回调桥接与关停`() {
        val sse = MockSseHostApi()
        val runtime = buildRuntime(sse, MockHttpHostApi())
        assertTrue(runtime.load())

        val sid = runtime.call("open_stream").toint()
        assertEquals("会话 id 透传", 100, sid)
        assertEquals("URL 透传", "https://api.example.com/v1/chat/completions", sse.connectedUrl)
        assertEquals("Authorization 透传", "Bearer test-key", sse.connectedHeaders["Authorization"])
        assertEquals("Accept 透传", "text/event-stream", sse.connectedHeaders["Accept"])

        // 流事件 → Lua 回调
        sse.listener?.onData("你好")
        sse.listener?.onData("世界")
        val received = LuaScriptRuntime.tableToList(runtime.call("get_received"))
            .map { it.tojstring() }
        assertEquals(listOf("你好", "世界"), received)

        sse.listener?.onDone("你好世界")
        assertEquals("onDone 交付拼接文本", "你好世界", runtime.call("get_done").tojstring())

        sse.listener?.onError("服务端限流")
        assertEquals("onError 交付错误", "服务端限流", runtime.call("get_error").tojstring())

        // 主动关停
        runtime.call("close_stream")
        assertEquals("closeStream 携带会话 id", listOf(100), sse.closedIds)
    }

    @Test
    fun `stream 失败返回 -1`() {
        val sse = MockSseHostApi().apply { returnId = -1 }
        val runtime = buildRuntime(sse, MockHttpHostApi())
        assertTrue(runtime.load())
        assertEquals("失败返回 -1", -1, runtime.call("open_stream").toint())
    }

    @Test
    fun `request 透传 timeoutMillis 覆盖默认超时`() {
        val http = MockHttpHostApi()
        val runtime = buildRuntime(MockSseHostApi(), http)
        assertTrue(runtime.load())

        assertNull("未传 timeout 时为 null", http.lastTimeoutMillis)
        runtime.call("sync_request")
        assertEquals("timeoutMillis 透传", 120000, http.lastTimeoutMillis)
        assertEquals("URL 透传", "https://api.example.com/v1/chat", http.lastUrl)
    }

    @Test
    fun `回调表缺失时宿主不崩溃`() {
        val sse = MockSseHostApi()
        val dir = java.nio.file.Files.createTempDirectory("sse_no_callbacks").toFile()
        File(dir, "main.lua").writeText(
            """
            local M = {}
            function M.open_no_cb()
              return host.http.stream("https://api.example.com/stream", {}, {})
            end
            return M
            """.trimIndent()
        )
        val debugHost = object : com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApi {
            override val sdkVersion = "0.1.0"
            override fun log(message: String) { System.out.println("LUA_LOG: $message") }
            override fun logError(message: String) { System.err.println("LUA_ERR: $message") }
            override fun configGet(key: String) = null
            override fun configSet(key: String, value: String) {}
            override fun configRemove(key: String) {}
            override fun configKeys() = emptySet<String>()
            override fun resourcePath(name: String) = null
            override fun resourceList(dir: String) = emptyList<String>()
            override fun jsonEncode(obj: Any?) = "{}"
            override fun jsonDecode(json: String) = null
            override fun uuid() = "uuid"
        }
        val runtime = LuaScriptRuntime(
            pluginId = "no_cb",
            pluginDir = dir,
            entryScript = "main.lua",
            configStore = InMemoryConfigStore(),
            hostApi = debugHost,
            httpHostApi = MockHttpHostApi(),
            sseHostApi = sse
        )
        assertTrue(runtime.load())
        val ret = runtime.call("open_no_cb")
        System.out.println("open_no_cb -> isNil=${ret.isnil()} value=${ret.tojstring()}")
        assertEquals("空回调表仍返回会话 id", 100, ret.toint())

        // 无回调函数时，宿主流事件不应崩溃
        sse.listener?.onData("x")
        sse.listener?.onDone("x")
        sse.listener?.onError("x")
    }
}