// sms-code 插件测试：设置表单契约。
//
// 本插件不含运行时逻辑（提取由宿主 SmsCodeReceiver 完成），因此测试聚焦
// 「表单字段与宿主读取契约一致」——字段 key、控件类型与默认值一旦漂移，
// 宿主 SmsCodePluginConfig 就读不到用户配置（表现为设置页保存无效），
// 这类回归不会报错、只会静默失效，必须由测试兜住。

const plugin = (globalThis as any).plugin as Record<string, any>;

/** 宿主 SmsCodePluginConfig 使用的 key（改这里等于改契约，须同步改 Java 常量）。 */
const HOST_KEYS = ['enabled', 'autoCopy', 'ttlSeconds', 'regex'];

function schema(): Array<Record<string, any>> {
  return plugin.settings.schema() as Array<Record<string, any>>;
}

function field(key: string): Record<string, any> {
  const found = schema().filter((n) => n.key === key);
  assert.equal(found.length, 1, '字段 ' + key + ' 应恰好出现一次');
  return found[0];
}

test('表单字段与宿主读取的 key 一一对应', () => {
  const keys = schema().map((n) => n.key);
  assert.deepEqual(keys, HOST_KEYS, '字段 key 或顺序发生变化');
});

test('每个字段都是可绑定的表单控件且带说明', () => {
  schema().forEach((node) => {
    assert.ok(node.key, '字段必须有 key 才能绑定配置存储');
    assert.ok(
      ['switch', 'number', 'text', 'input'].indexOf(node.type) >= 0,
      '未知控件类型: ' + node.type,
    );
    assert.ok(node.label, node.key + ' 缺少 label');
    assert.ok(node.helpText, node.key + ' 缺少 helpText');
  });
});

test('开关默认关闭（与宿主默认值一致）', () => {
  assert.equal(field('enabled').type, 'switch');
  assert.equal(field('enabled').defaultValue, 'false');
  assert.equal(field('autoCopy').type, 'switch');
  assert.equal(field('autoCopy').defaultValue, 'false');
});

test('有效期默认 60 秒，与宿主 DEFAULT_TTL_SECONDS 一致', () => {
  const ttl = field('ttlSeconds');
  assert.equal(ttl.type, 'number');
  assert.equal(ttl.defaultValue, '60');
  assert.ok(String(ttl.helpText).indexOf('10') >= 0, 'helpText 应说明取值下限');
  assert.ok(String(ttl.helpText).indexOf('600') >= 0, 'helpText 应说明取值上限');
});

test('提取正则默认留空（回退内置智能提取）', () => {
  const regex = field('regex');
  assert.equal(regex.type, 'text');
  assert.equal(regex.defaultValue, '');
  assert.ok(String(regex.helpText).indexOf('留空') >= 0, 'helpText 应说明留空即用内置提取');
});

test('表单不含必填项（留空或不改动也能正常工作）', () => {
  schema().forEach((node) => {
    assert.ok(!node.required, node.key + ' 不应标记为必填');
  });
});
