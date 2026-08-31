package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 下行事件（capabilities.events + onPluginEvent）：
 * - 声明订阅的插件收到 input_changed（payload 快照）
 * - conflated 通道：连发只保最新
 * - 未订阅类型 / 未启用通道（旧插件兼容）：静默丢弃不炸
 */
class LuaScriptRuntimeEventTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writePlugin(lua: String): File {
        val dir = tmp.newFolder("plugin")
        File(dir, "main.lua").writeText(lua)
        return dir
    }

    private val recorderLua = """
        local plugin = {}
        local events = {}

        function plugin.onPluginEvent(eventType, payload)
          table.insert(events, {
            type = eventType,
            input_text = payload and payload.input_text or nil,
          })
        end

        function plugin.eventCount()
          return #events
        end

        function plugin.lastEvent()
          local e = events[#events]
          if e == nil then return "" end
          return (e.type or "") .. "|" .. (e.input_text or "")
        end

        return plugin
    """.trimIndent()

    private fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("等待条件超时", condition())
    }

    @Test
    fun `订阅插件收到 input_changed 事件与 payload`() {
        val runtime = LuaScriptRuntime("evt-test", writePlugin(recorderLua), "main.lua", NoopPluginConfigStore)
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED))
        assertTrue(runtime.load())

        val delivered = runtime.dispatchEvent(
            PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "niha"))
        )
        assertTrue("应投递成功", delivered)
        awaitUntil { runtime.call("eventCount").toint() == 1 }
        assertEquals("input_changed|niha", runtime.call("lastEvent").tojstring())
        runtime.close()
    }

    @Test
    fun `conflated 连发多事件只保最新`() {
        val runtime = LuaScriptRuntime("evt-conflated", writePlugin(recorderLua), "main.lua", NoopPluginConfigStore)
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED))
        assertTrue(runtime.load())

        for (i in 1..20) {
            runtime.dispatchEvent(
                PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "k$i"))
            )
        }
        awaitUntil { runtime.call("eventCount").toint() >= 1 }
        assertEquals("最终必须是最后一个事件", "input_changed|k20", runtime.call("lastEvent").tojstring())
        // 消费远慢于同步连发，收到的次数应远小于 20（允许并发穿插）
        assertTrue("conflated 应合并中间事件: count=${runtime.call("eventCount").toint()}",
            runtime.call("eventCount").toint() <= 6)
        runtime.close()
    }

    @Test
    fun `未订阅的事件类型不投递`() {
        val runtime = LuaScriptRuntime("evt-filter", writePlugin(recorderLua), "main.lua", NoopPluginConfigStore)
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED))
        assertTrue(runtime.load())

        val delivered = runtime.dispatchEvent(PluginEvent("other_event", mapOf("x" to "1")))
        assertFalse("未订阅类型应被过滤", delivered)
        Thread.sleep(300)
        assertEquals(0, runtime.call("eventCount").toint())
        runtime.close()
    }

    @Test
    fun `未启用通道（旧插件）dispatchEvent 返回 false 不炸`() {
        val runtime = LuaScriptRuntime("evt-legacy", writePlugin(recorderLua), "main.lua", NoopPluginConfigStore)
        assertTrue(runtime.load())

        val delivered = runtime.dispatchEvent(
            PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "x"))
        )
        assertFalse("未声明 events 的旧插件不应建立通道", delivered)
        Thread.sleep(200)
        assertEquals(0, runtime.call("eventCount").toint())
        runtime.close()
    }

    @Test
    fun `插件未导出 onPluginEvent 时事件静默丢弃`() {
        val noHandlerLua = "local plugin = {}\nreturn plugin"
        val runtime = LuaScriptRuntime("evt-nohandler", writePlugin(noHandlerLua), "main.lua", NoopPluginConfigStore)
        runtime.initEvents(setOf(PluginEvent.TYPE_INPUT_CHANGED))
        assertTrue(runtime.load())

        val delivered = runtime.dispatchEvent(
            PluginEvent(PluginEvent.TYPE_INPUT_CHANGED, mapOf(PluginEvent.FIELD_INPUT_TEXT to "x"))
        )
        assertTrue("进入通道（投递与导出无关）", delivered)
        Thread.sleep(300)
        runtime.close()
    }
}
