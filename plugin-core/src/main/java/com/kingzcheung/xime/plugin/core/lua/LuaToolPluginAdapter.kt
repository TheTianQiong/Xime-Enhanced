package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.plugin.core.api.ToolPanelState
import com.kingzcheung.xime.plugin.core.api.ToolPlugin
import com.kingzcheung.xime.plugin.core.model.PluginContext
import org.luaj.vm2.LuaValue

/**
 * tool 类型 Lua 插件的宿主侧适配器：实现 [ToolPlugin] 接口。
 *
 * 这里是宿主强制协议（见 [ToolPlugin] 契约）的校验点：
 * 插件返回的 items 不符合协议时，非法数据被丢弃并输出协议错误日志，
 * 不再静默忽略——宿主 UI（ToolPanel/InfoPanel）消费的永远是协议合规数据。
 * 单条（SINGLE）与列表（MULTIPLE）结果统一为 [PluginResultItem] 列表，
 * 宿主不感知传输方式（HTTP/SSE）；结果呈现由元数据（manifest.capabilities.tool）声明。
 */
class LuaToolPluginAdapter(
    runtime: LuaScriptRuntime,
    pluginContext: PluginContext
) : LuaPluginAdapter(runtime, pluginContext), ToolPlugin {

    override fun getPanelState(inputText: String): ToolPanelState {
        val result = runtime.call("getPanelState", LuaValue.valueOf(inputText))
        if (!result.istable()) {
            protocolWarn("getPanelState 必须返回 table（当前为 ${result.typename()}），已按空状态处理")
            return ToolPanelState()
        }
        val map = LuaScriptRuntime.tableToMap(result)
        val input = map["inputText"]?.tojstring()?.takeIf { it.isNotBlank() } ?: inputText
        val uiRaw = map["ui"]
        val ui = if (uiRaw != null && uiRaw.istable()) {
            @Suppress("UNCHECKED_CAST")
            (LuaScriptRuntime.tableToJava(uiRaw) as? List<Map<*, *>>)
        } else null
        return ToolPanelState(
            inputText = input,
            items = parseResultItems(map["items"] ?: LuaValue.NIL, "getPanelState.items"),
            loading = map["loading"]?.toboolean() ?: false,
            ui = ui,
        )
    }

    override fun onPanelInput(text: String) {
        runtime.call("onPanelInput", LuaValue.valueOf(text))
    }

    override fun onPanelAction(actionId: String) {
        runtime.call("onPanelAction", LuaValue.valueOf(actionId))
    }

    override fun onPanelItemClick(itemId: String) {
        runtime.call("onPanelItemClick", LuaValue.valueOf(itemId))
    }
}