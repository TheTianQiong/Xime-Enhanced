package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 验证 sms-code 插件暴露的设置契约：全部验证码设置（开关、自动复制、有效期、
 * 提取正则）都由该插件承载，宿主「插件管理 → 短信验证码（增强）→ 设置」据此渲染。
 *
 * 宿主的读取端（SmsCodePluginConfig）按这些 key / 类型取名，命名不一致会导致
 * 插件页设置保存后宿主机能读不到——因此在此锁死契约。
 */
class LuaSmsCodePluginTest {

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class DebugHostApi(private val store: PluginConfigStore) : LuaHostApi {
        override val sdkVersion = "0.1.0"
        override fun log(message: String) { System.out.println("LUA_LOG: $message") }
        override fun logError(message: String) { System.err.println("LUA_ERROR: $message") }
        override fun configGet(key: String) = store.get(key)
        override fun configSet(key: String, value: String) { store.set(key, value) }
        override fun configRemove(key: String) { store.remove(key) }
        override fun configKeys() = store.keys()
        override fun resourcePath(name: String) = null
        override fun resourceList(dir: String) = emptyList<String>()
        override fun jsonEncode(obj: Any?) = com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.encode(obj)
        override fun jsonDecode(json: String) = try {
            com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.decode(json)
        } catch (_: Exception) {
            null
        }
        override fun uuid() = "uuid"
    }

    private fun newRuntime(store: PluginConfigStore = InMemoryConfigStore()): LuaScriptRuntime {
        val dir = File("../plugins/sms-code")
        assertTrue("插件目录应存在: ${dir.absolutePath}", dir.exists())
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.sms_code",
            dir,
            "main.lua",
            store,
            hostApi = DebugHostApi(store),
        )
        assertTrue("main.lua 应能加载", runtime.load())
        return runtime
    }

    /** 取 schema 中每个节点的 key → type 映射。 */
    private fun schemaTypes(runtime: LuaScriptRuntime): Map<String, String> =
        LuaScriptRuntime.tableToList(runtime.call("getSettingsSchema"))
            .map { it as org.luaj.vm2.LuaTable }
            .associate { node ->
                node.get("key").tojstring() to node.get("type").tojstring()
            }

    @Test
    fun `设置项契约：键名与类型`() {
        val types = schemaTypes(newRuntime())

        // 宿主 SmsCodePluginConfig 按这些 key 读取，改名即为破坏性变更
        assertEquals("switch", types["enabled"])
        assertEquals("switch", types["autoCopy"])
        assertEquals("number", types["ttlSeconds"])
        assertEquals("text", types["regex"])
        assertEquals(4, types.size)
    }

    @Test
    fun `有效期默认值为 60 秒`() {
        val schema = LuaScriptRuntime.tableToList(newRuntime().call("getSettingsSchema"))
            .map { it as org.luaj.vm2.LuaTable }
        val ttl = schema.first { it.get("key").tojstring() == "ttlSeconds" }

        assertEquals("60", ttl.get("defaultValue").tojstring())
    }

    @Test
    fun `开关默认关闭`() {
        val schema = LuaScriptRuntime.tableToList(newRuntime().call("getSettingsSchema"))
            .map { it as org.luaj.vm2.LuaTable }
        listOf("enabled", "autoCopy").forEach { key ->
            val node = schema.first { it.get("key").tojstring() == key }
            assertEquals("$key 应默认关闭", "false", node.get("defaultValue").tojstring())
        }
    }

    @Test
    fun `每个设置项都有标签与说明`() {
        val schema = LuaScriptRuntime.tableToList(newRuntime().call("getSettingsSchema"))
            .map { it as org.luaj.vm2.LuaTable }

        schema.forEach { node ->
            val key = node.get("key").tojstring()
            assertTrue("$key 缺 label", node.get("label").tojstring().isNotBlank())
            assertTrue("$key 缺 helpText", node.get("helpText").tojstring().isNotBlank())
        }
    }
}
