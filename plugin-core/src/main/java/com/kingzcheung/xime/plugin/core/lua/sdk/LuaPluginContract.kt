package com.kingzcheung.xime.plugin.core.lua.sdk

/**
 * Lua 插件契约：入口脚本（main.lua）导出函数与数据格式的约定。
 *
 * ## 入口脚本
 * 插件包根目录的 main.lua（manifest.yaml 的 `entry` 字段指定）必须 `return` 一个
 * **导出表（table）**，宿主读取表中函数并按约定调用。
 *
 * ## 生命周期（可选）
 * - `onLoad()` 插件加载时调用（宿主已注入 host API）
 * - `onUnload()` 插件卸载时调用
 *
 * ## 分类能力（按 manifest.type 约定）
 * ### emoji 表情
 * - `getCategories()` -> string[]
 * - `getEmojis(query)` -> EmojiItem[]
 *   `query`: { keyword?: string, topK?: int }
 *   每项: { id: string, text: string, insertText?: string, imageUrl?: string }
 *   - text 同时作为显示文本与插入文本（可另给 insertText 区分上屏内容）
 *   - imageUrl 可通过 host.resource.path() 获得（图片渲染由宿主完成）
 *
 * ### tool 工具
 * - `getPanelState(inputText)` -> { inputText?, items: [], loading? }
 * - `onPanelInput(text)` 面板输入变化通知
 * - `onPanelAction(actionId)` 面板操作事件（宿主保留 `generate`）
 * - `onPanelItemClick(itemId)` 点候选上屏
 *
 * ### speech 语音（规划中）
 * - `getSettingsSchema()` 配置字段声明（与 manifest.configSchema 等价）
 * - `getOptions(key)` 动态选项（如 ASR 模型列表）
 * - `start()` / `pushPcm()` / `stop()` 音频流式识别（网络 API 由宿主白名单提供）
 *
 * ### 事件（可选，manifest 声明 `capabilities.events` 才投递）
 * - `onPluginEvent(eventType, payload)` 宿主下行事件回调
 *   - 事件类型与 payload 字段名均为 snake_case
 *   - `input_changed`: payload = { input_text: string }，用户正在输入的编码快照
 *   - `text_committed`: payload = { committed_text: string, session_total_chars: int,
 *     session_total_commits: int }，文本上屏；累计值为宿主进程生命周期计数，
 *     conflated 丢中间事件不影响统计（插件用差值做增量持久化）
 *   - 通道为"只保最新"（conflated）：消费慢时中间事件被合并丢弃
 *   - 敏感输入框（密码类）不产生上述任何事件
 *   - 函数未导出时事件被静默丢弃，不影响其他能力
 *
 * ## 数据格式
 * Lua 返回值一律使用 Lua table（数组或 map），宿主统一做 table -> Kotlin 转换；
 * 函数不存在或抛错时，宿主返回空结果（不崩溃）。
 *
 * ## 与元数据的分工（v0.2.0 起）
 * 静态能力一律由 manifest.capabilities 声明（宿主唯一来源）：
 * - 布局（columns/itemHeightDp）→ capabilities.emoji
 * - 结果显示（display: direct/select）→ capabilities.tool
 * - ASR 能力（inputMode 等）→ capabilities.speech
 * Lua 侧不再导出 getCategoryLayoutConfig / getCapabilities /
 * getProviderId / getDisplayName / getState 等元信息函数。
 */
object LuaPluginContract {

    /** SDK 版本（宿主注入的 host.sdkVersion）。插件 manifest 可声明 `sdkVersion` 声明所需 SDK 版本。 */
    const val SDK_VERSION = "0.2.0"

    // ---- 宿主注入的全局对象 ----
    const val GLOBAL_HOST = "host"

    // ---- 生命周期 ----
    const val FN_ON_LOAD = "onLoad"
    const val FN_ON_UNLOAD = "onUnload"

    // ---- 事件（可选：manifest capabilities.events 声明后才投递） ----
    const val FN_ON_PLUGIN_EVENT = "onPluginEvent"

    // ---- emoji ----
    const val FN_GET_CATEGORIES = "getCategories"
    const val FN_GET_EMOJIS = "getEmojis"

    // ---- tool ----
    const val FN_GET_PANEL_STATE = "getPanelState"
    const val FN_ON_PANEL_INPUT = "onPanelInput"
    const val FN_ON_PANEL_ACTION = "onPanelAction"
    const val FN_ON_PANEL_ITEM_CLICK = "onPanelItemClick"

    // ---- emoji item 字段 ----
    const val FIELD_ID = "id"
    const val FIELD_TEXT = "text"
    const val FIELD_INSERT_TEXT = "insertText"
    const val FIELD_IMAGE_URL = "imageUrl"
}