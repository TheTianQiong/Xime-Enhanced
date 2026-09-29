// 短信验证码（增强）插件（TypeScript 脚本插件）
//
// 职责：作为「短信验证码获取」功能的**配置提供方**——通过 settings.schema 暴露
// 全部验证码相关设置，宿主渲染设置页，值由宿主按 key 存入本插件专属配置
// （SharedPreferences `plugin_cfg_com.kingzcheung.xime.plugin.sms_code`，
// 与 Java 侧 SmsCodePluginConfig 读写的是同一份存储）。
//
// 实际提取由宿主完成，本插件不参与运行时逻辑：
//   - 配置了正则 → 宿主 SmsCodeReceiver 用自定义正则提取
//   - 留空       → 回退内置智能提取（SmsCodeExtractor）
// 宿主读取本插件配置决定功能开关、自动复制与验证码有效期。
//
// 字段 key 与宿主 SmsCodePluginConfig 的常量一一对应，改名会让已有配置读不到。

const KEY_ENABLED = 'enabled';
const KEY_AUTO_COPY = 'autoCopy';
const KEY_TTL_SECONDS = 'ttlSeconds';
const KEY_REGEX = 'regex';

/** 有效期默认值（秒），与宿主 SmsCodePluginConfig.DEFAULT_TTL_SECONDS 一致。 */
const DEFAULT_TTL_SECONDS = '60';

/** 配置表单：功能开关、自动复制、有效期、提取正则（顺序即渲染顺序）。 */
function getSettingsSchema(): XimeUiNode[] {
  return [
    {
      key: KEY_ENABLED,
      label: '启用短信验证码获取',
      type: 'switch',
      defaultValue: 'false',
      helpText: '开启并授予短信权限后，收到验证码短信会在候选栏快捷插入。',
    },
    {
      key: KEY_AUTO_COPY,
      label: '自动复制到剪贴板',
      type: 'switch',
      defaultValue: 'false',
      helpText: '收到验证码后自动复制，方便直接粘贴。',
    },
    {
      key: KEY_TTL_SECONDS,
      label: '验证码有效期（秒）',
      type: 'number',
      placeholder: DEFAULT_TTL_SECONDS,
      defaultValue: DEFAULT_TTL_SECONDS,
      helpText: '仅显示最近该秒数内收到的验证码（10–600 秒），超时自动消失。',
    },
    {
      key: KEY_REGEX,
      label: '提取正则',
      type: 'text',
      placeholder: '如 (?<!\\d)\\d{4,6}(?!\\d)',
      defaultValue: '',
      helpText: '留空使用内置智能提取。正则需能匹配到验证码数字组；含捕获组时取第一个非空分组。',
    },
  ];
}

const plugin = definePlugin({
  settings: {
    schema: getSettingsSchema,
  },
});

export default plugin;
