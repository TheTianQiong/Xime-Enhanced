-- 短信验证码（增强）插件
--
-- 职责：作为「短信验证码获取」功能的配置提供方。
-- 通过 getSettingsSchema 暴露「提取正则」配置项，保存后写入宿主
-- plugin_cfg_com.kingzcheung.xime.plugin.sms_code（与 Java 侧 SmsCodePluginConfig 同一份 prefs）。
--
-- 实际提取由宿主 SmsCodeReceiver 完成：
--   - 配置了正则 → 用自定义正则提取
--   - 未配置（留空）→ 使用内置智能提取（SmsCodeExtractor）

local plugin = {}

function plugin.getDisplayName()
    return "短信验证码（增强）"
end

function plugin.getIcon()
    return { text = "📩" }
end

-- 配置表单：提取正则
function plugin.getSettingsSchema()
    return {
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
