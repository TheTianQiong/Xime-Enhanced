package com.kingzcheung.xime.ui.settings

object SettingsRoutes {
    const val Main = "main"
    const val Schema = "schema"
    const val Market = "market"
    const val MarketModel = "market_model"
    const val MarketPlugins = "market_plugins"
    const val SchemaLocal = "schema_local"
    const val ModelLocal = "model_local"
    const val SchemaMarketDetail = "schema_market_detail/{schemeId}"
    const val ModelMarketDetail = "model_market_detail/{modelId}"
    const val PluginMarketDetail = "plugin_market_detail/{pluginId}"
    const val LayoutMarketDetail = "layout_market_detail/{layoutId}"
    const val Theme = "theme"
    const val KeyEffect = "key_effect"
    const val LayoutDisplay = "layout_display"

    /** 布局插件管理页（布局与显示 → 布局插件）。 */
    const val LayoutPlugins = "layout_plugins"

    /** 直达市场「布局」页签（布局插件页的「浏览布局市场」入口）。 */
    const val MarketLayouts = "market_layouts"
    const val ChineseSymbol = "chinese_symbol"
    const val Dictionary = "dictionary"
    const val Plugins = "plugins"
    const val PluginSettings = "plugin_settings"
    const val SmartPrediction = "smart_prediction"
    const val SpeechToText = "speech_to_text"
    const val About = "about"
    const val Developer = "developer"
    const val StorageSpace = "storage_space"
    const val Privacy = "privacy"
    const val Licenses = "licenses"
    const val LogViewer = "log_viewer"
    const val HandwritingCapture = "handwriting_capture"
    const val Clipboard = "clipboard"
    const val ClipboardSync = "clipboard_sync"
    const val Backup = "backup"
    const val SchemaDictBrowser = "schema_dict_browser"
    const val RimeFileBrowser = "rime_file_browser"
    const val PermissionManager = "permission_manager"
    const val ExtensionStoreSettings = "extension_store_settings"
}
