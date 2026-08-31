package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.api.EmojiQuery
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** emoji 类型 Lua 插件的宿主侧适配器：实现 EmojiPlugin 接口。 */
class LuaEmojiPluginAdapter(
    runtime: LuaScriptRuntime,
    pluginContext: PluginContext
) : LuaPluginAdapter(runtime, pluginContext), EmojiPlugin {

    /**
     * 统一的候选项解析（emoji 与 tool 的 items 共用同一协议 schema `{id, text, insertText?, imageUrl?}`）。
     */
    override suspend fun getEmojis(query: EmojiQuery): List<PluginResultItem> =
        withContext(Dispatchers.IO) {
            val args = LuaTable()
            args.set("category", LuaValue.valueOf(query.category ?: ""))
            args.set("keyword", LuaValue.valueOf(query.keyword ?: ""))
            args.set("topK", LuaValue.valueOf(query.topK))
            val result = runtime.call(
                LuaPluginContract.FN_GET_EMOJIS,
                args
            )
            parseResultItems(result, "getEmojis 返回")
        }

    override suspend fun getCategories(): List<String> = withContext(Dispatchers.IO) {
        LuaScriptRuntime.tableToList(runtime.call(LuaPluginContract.FN_GET_CATEGORIES))
            .mapNotNull { it.tojstring() }
    }
}