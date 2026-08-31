package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.lua.http.SseHostApi
import com.kingzcheung.xime.plugin.core.lua.http.SseHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 验证 AI 插件端到端数据链路（Lua 侧）：
 * - ai-reply：同步 host.http.request 生成候选列表 → getPanelState 解析
 * - ai-write：host.http.stream 流式累积 → getPanelState 实时文本 → closeStream 中断
 */
class LuaAiPluginTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockHttpHostApi(var response: HttpResponse? = null) : HttpHostApi {
        var lastUrl: String? = null
        var lastTimeout: Int? = null
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            lastUrl = url
            lastTimeout = timeoutMillis
            return response
        }
        override fun lastError(): String? = null
    }

    private class MockSseHostApi : SseHostApi {
        var listener: SseHostListener? = null
        var closedIds = mutableListOf<Int>()
        var returnId = 100
        var connectedMethod: String? = null
        var connectedBody: ByteArray? = null
        var connectedCount = 0
        override fun connect(
            url: String,
            headers: Map<String, String>,
            listener: SseHostListener,
            timeoutMillis: Int?,
            method: String,
            body: ByteArray?
        ): Int {
            connectedCount++
            connectedMethod = method
            connectedBody = body
            this.listener = listener
            return returnId
        }
        override fun close(sessionId: Int) { closedIds.add(sessionId) }
        override fun lastError(): String? = null
    }

    @Test
    fun `ai-reply 同步生成候选并解析到面板状态`() {
        val dir = File("../plugins/ai-reply")
        assertTrue("ai-reply 插件目录应存在: ${dir.absolutePath}", dir.exists())
        val store = InMemoryConfigStore()
        store.set("apiKey", "test-key")
        store.set("baseUrl", "https://api.example.com/v1")
        store.set("model", "gpt-test")
        val mockHttp = MockHttpHostApi(
            HttpResponse(
                status = 200,
                body = """
                    {"choices":[{"message":{"content":"好的！\n好的呀！\n没问题！"}}]}
                """.trimIndent().toByteArray()
            )
        )
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_reply",
            dir,
            "main.lua",
            store,
            httpHostApi = mockHttp
        )
        assertTrue("main.lua 应能加载", runtime.load())
        val adapter = LuaToolPluginAdapter(runtime, com.kingzcheung.xime.plugin.core.model.PluginContext(
            application = android.app.Application(),
            pluginInfo = com.kingzcheung.xime.plugin.core.model.PluginInfo(
                id = "com.kingzcheung.xime.plugin.ai_reply", name = "AI 智能回复", description = "测试",
                iconResId = 0, versionCode = 1, versionName = "0.1.0",
                path = File(dir, "main.lua").absolutePath, type = "tool"
            ),
            configStore = store,
        ))

        adapter.onPanelInput("明天有空吗")
        adapter.onPanelAction("generate")

        val state = adapter.getPanelState("明天有空吗")
        assertTrue("应生成 3 条候选", state.items.size == 3)
        assertEquals("好的！", state.items[0].text)
        assertEquals("好的呀！", state.items[1].text)
        assertEquals("没问题！", state.items[2].text)
        assertFalse("同步生成后不在加载中", state.loading)
        assertTrue("请求应带长超时", (mockHttp.lastTimeout ?: 0) >= 60000)
        assertTrue("请求 URL 指向 chat/completions", mockHttp.lastUrl?.contains("/chat/completions") == true)
    }

    @Test
    fun `ai-reply 未配置 API Key 时生成失败`() {
        val dir = File("../plugins/ai-reply")
        assertTrue(dir.exists())
        val store = InMemoryConfigStore()
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_reply", dir, "main.lua", store,
            httpHostApi = MockHttpHostApi()
        )
        assertTrue(runtime.load())
        val adapter = LuaToolPluginAdapter(runtime, com.kingzcheung.xime.plugin.core.model.PluginContext(
            application = android.app.Application(),
            pluginInfo = com.kingzcheung.xime.plugin.core.model.PluginInfo(
                id = "com.kingzcheung.xime.plugin.ai_reply", name = "AI 智能回复", description = "测试",
                iconResId = 0, versionCode = 1, versionName = "0.1.0",
                path = File(dir, "main.lua").absolutePath, type = "tool"
            ),
            configStore = store,
        ))

        adapter.onPanelInput("你好")
        adapter.onPanelAction("generate")
        assertTrue("未配置 Key 不应产生候选", adapter.getPanelState("你好").items.isEmpty())
    }

    @Test
    fun `ai-write 流式累积文本并支持中断`() {
        val dir = File("../plugins/ai-write")
        assertTrue("ai-write 插件目录应存在: ${dir.absolutePath}", dir.exists())
        val store = InMemoryConfigStore()
        store.set("apiKey", "test-key")
        store.set("baseUrl", "https://api.example.com/v1")
        val sse = MockSseHostApi()
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_write",
            dir,
            "main.lua",
            store,
            httpHostApi = MockHttpHostApi(),
            sseHostApi = sse
        )
        assertTrue("main.lua 应能加载", runtime.load())
        val adapter = LuaToolPluginAdapter(runtime, com.kingzcheung.xime.plugin.core.model.PluginContext(
            application = android.app.Application(),
            pluginInfo = com.kingzcheung.xime.plugin.core.model.PluginInfo(
                id = "com.kingzcheung.xime.plugin.ai_write", name = "AI 帮写", description = "测试",
                iconResId = 0, versionCode = 1, versionName = "0.1.0",
                path = File(dir, "main.lua").absolutePath, type = "tool"
            ),
            configStore = store,
        ))

        adapter.onPanelInput("帮我写一条好评")
        adapter.onPanelAction("generate")

        var state = adapter.getPanelState("帮我写一条好评")
        assertTrue("生成中 loading=true", state.loading)

        sse.listener?.onData("""{"choices":[{"delta":{"content":"这家"}}]}""")
        sse.listener?.onData("""{"choices":[{"delta":{"content":"店很好"}}]}""")
        sse.listener?.onData("""{"choices":[{"delta":{"content":"，值得推荐"}}]}""")

        state = adapter.getPanelState("帮我写一条好评")
        assertEquals("流式增量累积", "这家店很好，值得推荐", state.items.single().text)
        assertTrue("仍在加载中", state.loading)

        // 生成中途可关停：closeStream 中断会话（此后宿主不再回调）
        adapter.onPanelAction("stop")
        assertEquals("closeStream 中断会话", listOf(100), sse.closedIds)
        state = adapter.getPanelState("帮我写一条好评")
        assertFalse("中断后不再加载", state.loading)

        // 正常流结束路径：重新生成 → 累积 → onDone 收尾
        adapter.onPanelAction("generate")
        sse.listener?.onData("""{"choices":[{"delta":{"content":"新"}}]}""")
        sse.listener?.onData("""{"choices":[{"delta":{"content":"结果"}}]}""")
        sse.listener?.onDone("新结果")
        state = adapter.getPanelState("帮我写一条好评")
        assertFalse("onDone 后不再加载", state.loading)
        assertEquals("onDone 收尾文本", "新结果", state.items.single().text)
    }

    @Test
    fun `ai-translate 流式翻译生成译文`() {
        val dir = File("../plugins/ai-translate")
        assertTrue("ai-translate 插件目录应存在: ${dir.absolutePath}", dir.exists())
        val store = InMemoryConfigStore()
        store.set("apiKey", "test-key")
        store.set("baseUrl", "https://api.example.com/v1")
        store.set("targetLang", "English")
        val sse = MockSseHostApi()
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.ai_translate",
            dir,
            "main.lua",
            store,
            sseHostApi = sse
        )
        assertTrue("main.lua 应能加载", runtime.load())

        val adapter = LuaToolPluginAdapter(runtime, com.kingzcheung.xime.plugin.core.model.PluginContext(
            application = android.app.Application(),
            pluginInfo = com.kingzcheung.xime.plugin.core.model.PluginInfo(
                id = "com.kingzcheung.xime.plugin.ai_translate", name = "AI 翻译", description = "测试",
                iconResId = 0, versionCode = 1, versionName = "0.1.0",
                path = File(dir, "main.lua").absolutePath, type = "tool"
            ),
            configStore = store,
        ))
        adapter.onPanelInput("你好世界")
        adapter.onPanelAction("generate")

        assertEquals("应发起 SSE 流式连接", 1, sse.connectedCount)
        assertEquals("SSE 应使用 POST", "POST", sse.connectedMethod)
        assertNotNull("SSE 应携带 JSON body", sse.connectedBody)

        var state = adapter.getPanelState("你好世界")
        assertTrue("生成中应 loading", state.loading)

        // 流式增量累积
        sse.listener?.onData("""{"choices":[{"delta":{"content":"Hello"}}]}""")
        state = adapter.getPanelState("你好世界")
        assertTrue("流式期间应 loading", state.loading)
        assertEquals("增量累积", "Hello", state.items.single().text)

        sse.listener?.onData("""{"choices":[{"delta":{"content":" world"}}]}""")
        sse.listener?.onDone("Hello world")

        state = adapter.getPanelState("你好世界")
        assertFalse("onDone 后不再加载", state.loading)
        assertEquals("译文解析", "Hello world", state.items.single().text)
    }

    @Test
    fun `AI 插件均导出配置 schema（插件中心设置入口依据）`() {
        for (name in listOf("ai-reply", "ai-write", "ai-translate")) {
            val dir = File("../plugins/$name")
            assertTrue("$name 插件目录应存在: ${dir.absolutePath}", dir.exists())
            val runtime = LuaScriptRuntime(
                "com.kingzcheung.xime.plugin.$name", dir, "main.lua", InMemoryConfigStore()
            )
            assertTrue("$name main.lua 应能加载", runtime.load())
            val adapter = LuaToolPluginAdapter(runtime, com.kingzcheung.xime.plugin.core.model.PluginContext(
                application = android.app.Application(),
                pluginInfo = com.kingzcheung.xime.plugin.core.model.PluginInfo(
                    id = "com.kingzcheung.xime.plugin.$name", name = name, description = "测试",
                    iconResId = 0, versionCode = 1, versionName = "0.1.0",
                    path = File(dir, "main.lua").absolutePath, type = "tool"
                ),
                configStore = InMemoryConfigStore(),
            ))
            val fields = adapter.getSettingsSchema()
            assertTrue("$name 应至少 4 个配置字段（apiKey/baseUrl/model/prompt）", fields.size >= 4)
            val keyToField = fields.associateBy { it.key }
            assertTrue("$name 应包含 apiKey", keyToField.containsKey("apiKey"))
            assertTrue("$name 应包含 baseUrl", keyToField.containsKey("baseUrl"))
            val prompt = keyToField["prompt"]
            assertNotNull("$name 应包含 prompt", prompt)
            assertEquals(
                "$name prompt 应为 textarea 长文本编辑",
                com.kingzcheung.xime.plugin.core.config.PluginFieldType.TEXTAREA,
                prompt!!.type
            )
        }
    }
}