package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.PluginFieldType
import com.kingzcheung.xime.plugin.core.config.PluginSettingField
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import org.luaj.vm2.LuaValue

/**
 * Lua 脚本插件的宿主侧适配器基类：实现通用接口（入口生命周期 + 设置项 + 图标），
 * 具体能力接口（EmojiPlugin / AsrPlugin）由按插件类别派生的子类实现，
 * 保证 `instance is AsrPlugin` 只对 speech 类型插件成立。
 */
open class LuaPluginAdapter(
    protected val runtime: LuaScriptRuntime,
    protected val pluginContext: PluginContext
) : IPluginEntryClass, IPluginConfigurable {

    override fun getSettingsSchema(): List<PluginSettingField> {
        val result = runtime.call("getSettingsSchema")
        if (!result.istable()) return emptyList()
        return LuaScriptRuntime.tableToList(result).mapNotNull { field ->
            val map = LuaScriptRuntime.tableToMap(field)
            val key = map["key"]?.tojstring() ?: return@mapNotNull null
            PluginSettingField(
                key = key,
                label = map["label"]?.tojstring() ?: key,
                type = parseFieldType(map["type"]?.tojstring()),
                placeholder = map["placeholder"]?.tojstring(),
                defaultValue = map["defaultValue"]?.tojstring(),
                options = stringList(map["options"] ?: LuaValue.NIL),
                helpText = map["helpText"]?.tojstring(),
                section = map["section"]?.tojstring(),
                required = map["required"]?.toboolean() ?: true,
                action = map["action"]?.tojstring()
            )
        }
    }

    override suspend fun onAction(action: String): String? {
        if (action.isBlank()) return "未知操作"
        val result = runtime.call(action)
        if (result.isnil()) return null
        val msg = result.tojstring()
        return if (msg.isBlank()) null else msg
    }

    override fun getOptions(key: String): List<String>? {
        val result = runtime.call("getOptions", LuaValue.valueOf(key))
        if (!result.istable()) return null
        return stringList(result)
    }

    private fun parseFieldType(type: String?): PluginFieldType = when (type) {
        "textarea" -> PluginFieldType.TEXTAREA
        "secret" -> PluginFieldType.SECRET
        "select" -> PluginFieldType.SELECT
        "multi_select" -> PluginFieldType.MULTI_SELECT
        "switch" -> PluginFieldType.SWITCH
        "number" -> PluginFieldType.NUMBER
        "button" -> PluginFieldType.BUTTON
        else -> PluginFieldType.TEXT
    }

    private fun stringList(value: LuaValue): List<String> {
        if (!value.istable()) return emptyList()
        return LuaScriptRuntime.tableToList(value).mapNotNull { it.tojstring() }
    }

    override fun getIcon(): PluginIcon? {
        val result = runtime.call("getIcon")
        if (!result.istable()) return null
        val map = LuaScriptRuntime.tableToMap(result)
        val text = map["text"]?.tojstring()?.takeIf { it.isNotBlank() }
        if (text != null) return PluginIcon(text = text)
        val assetName = map["assetName"]?.tojstring()
            ?.takeIf {
                it.isNotBlank() && com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.isValidResourcePath(it)
            }
        if (assetName != null) return PluginIcon(assetName = assetName)
        return null
    }

    override fun onLoad(context: PluginContext) {
        if (runtime.load()) {
            runtime.callOnLoad()
        }
    }

    override fun onUnload() {
        runtime.close()
    }

    /**
     * 通用配置就绪判定：所有 required 配置字段均已有值。
     * Lua 插件不再需要实现 isConfigured——配置状态由宿主统一判定。
     */
    open fun isConfigured(): Boolean {
        val schema = getSettingsSchema()
        if (schema.isEmpty()) return true
        return schema.none { it.required && pluginContext.configStore.get(it.key).isNullOrBlank() }
    }

    /**
     * 统一的候选项解析（emoji 与 tool 的 items 共用同一协议 schema `{id, text, insertText?, imageUrl?}`）。
     * 不符合协议的条目丢弃并输出协议警告，宿主 UI 只消费合规数据。
     */
    protected fun parseResultItems(value: LuaValue, what: String): List<com.kingzcheung.xime.plugin.core.api.PluginResultItem> {
        if (!value.istable()) {
            if (!value.isnil()) protocolWarn("$what 必须是数组（当前为 ${value.typename()}）")
            return emptyList()
        }
        val items = ArrayList<com.kingzcheung.xime.plugin.core.api.PluginResultItem>()
        val seenIds = HashSet<String>()
        for (entry in LuaScriptRuntime.tableToList(value)) {
            if (!entry.istable()) {
                protocolWarn("$what 元素必须是 table（当前为 ${entry.typename()}），已丢弃")
                continue
            }
            val m = LuaScriptRuntime.tableToMap(entry)
            val id = m[LuaPluginContract.FIELD_ID]?.tojstring()?.takeIf { it.isNotBlank() }
            val text = m[LuaPluginContract.FIELD_TEXT]?.tojstring()?.takeIf { it.isNotBlank() }
            if (id == null || text == null) {
                protocolWarn("$what 元素缺少非空 id/text（协议要求 { id: string, text: string }），已丢弃")
                continue
            }
            if (!seenIds.add(id)) {
                protocolWarn("$what 元素 id 重复（'$id'），已丢弃重复项")
                continue
            }
            items += com.kingzcheung.xime.plugin.core.api.PluginResultItem(
                id = id,
                text = text,
                insertText = m["insertText"]?.tojstring()?.takeIf { it.isNotBlank() },
                imageUrl = m[LuaPluginContract.FIELD_IMAGE_URL]?.tojstring()?.takeIf { it.isNotBlank() },
            )
        }
        return items
    }

    /** 协议违规统一告警：Log.w 级别（不随调用链抛出，避免拖垮宿主轮询），tag 含插件 id。 */
    protected fun protocolWarn(message: String) {
        android.util.Log.w("PluginProtocol", "[${pluginContext.pluginId}] $message")
    }

    companion object {
        const val FN_GET_EMOJIS = LuaPluginContract.FN_GET_EMOJIS
        const val FN_GET_CATEGORIES = LuaPluginContract.FN_GET_CATEGORIES
    }
}
