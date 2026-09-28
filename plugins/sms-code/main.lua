-- 短信验证码（增强）插件
--
-- 职责：作为「短信验证码获取」功能的配置提供方。
-- 通过 getSettingsSchema 暴露全部验证码相关设置，保存后写入宿主
-- plugin_cfg_com.kingzcheung.xime.plugin.sms_code（与 Java 侧 SmsCodePluginConfig 同一份 prefs）。
--
-- 实际提取由宿主 SmsCodeReceiver 完成：
--   - 配置了正则 → 用自定义正则提取
--   - 未配置（留空）→ 使用内置智能提取（SmsCodeExtractor）
-- 宿主读取本插件配置决定功能开关、自动复制与验证码有效期。

local plugin = {}

function plugin.getDisplayName()
    return "短信验证码（增强）"
end

function plugin.getIcon()
    return { text = "📩" }
end

-- 配置表单：功能开关、自动复制、有效期、提取正则
function plugin.getSettingsSchema()
    return {
        {
            key = "enabled",
            label = "启用短信验证码获取",
            type = "switch",
            defaultValue = "false",
            required = false,
            helpText = "开启并授予短信权限后，收到验证码短信会在候选栏快捷插入。",
        },
        {
            key = "autoCopy",
            label = "自动复制到剪贴板",
            type = "switch",
            defaultValue = "false",
            required = false,
            helpText = "收到验证码后自动复制，方便直接粘贴。",
        },
        {
            key = "ttlSeconds",
            label = "验证码有效期（秒）",
            type = "number",
            placeholder = "60",
            defaultValue = "60",
            required = false,
            helpText = "仅显示最近该秒数内收到的验证码（10–600 秒），超时自动消失。",
        },
        {
            key = "regex",
            label = "提取正则",
            type = "text",
            placeholder = "如 (?<!\\d)\\d{4,6}(?!\\d)",
            defaultValue = "",
            helpText = "留空使用内置智能提取。正则需能匹配到验证码数字组；含捕获组时取第一个非空分组。",
            required = false,
        },
    }
end

return plugin
