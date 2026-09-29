// asrkb-bridge 插件测试：host.ipc 会话状态机、错误码映射、结果上报。
//
// 全程走 mock host 的 host.ipc（xipm 测试引擎注入，见 xime-plugin.d.ts 的 XimeIpc）：
//   setIpcConnect 模拟绑定结果、setIpcStartResult 模拟 startPcmSession 返回值、
//   ipcPartial/ipcFinal/ipcError/ipcState 模拟服务端主动推送。

const plugin = (globalThis as any).plugin as Record<string, any>;
const speech = plugin.speech;

/** 清空断言读侧（mock 状态在同一 QuickJS 上下文内跨用例累积）。 */
function reset(): void {
  (__ximeMock.asrEvents as unknown[]).length = 0;
  (__ximeMock.ipcWritten as unknown[]).length = 0;
  (__ximeMock.ipcFinished as unknown[]).length = 0;
  (__ximeMock.ipcCancelled as unknown[]).length = 0;
  __ximeMock.setIpcConnect(true, null);
  __ximeMock.setIpcStartResult(1);
}

function asrEvents(): Array<Record<string, unknown>> {
  return __ximeMock.asrEvents as Array<Record<string, unknown>>;
}

/** 按类型取最近一条 asr 事件。 */
function lastEvent(type: string): Record<string, unknown> | null {
  const all = asrEvents().filter((e) => e.type === type);
  return all.length > 0 ? all[all.length - 1] : null;
}

// ================= 元信息与绑定 =================

test('无独立配置项，始终视为已就绪', () => {
  assert.ok(speech.isConfigured(), 'isConfigured 应恒为 true');
});

test('configure 成功绑定外部语音服务', () => {
  reset();
  assert.ok(speech.configure(), 'configure 应返回 true');
});

test('绑定失败时上报 lastError 提供的可读原因', () => {
  reset();
  __ximeMock.setIpcConnect(false, '未检测到说点啥（asr-keyboard），请先安装');
  assert.ok(!speech.configure(), '绑定失败时 configure 应返回 false');
  const err = lastEvent('error');
  assert.ok(err !== null, '应上报 error 事件');
  assert.equal(err!.message, '未检测到说点啥（asr-keyboard），请先安装');
  __ximeMock.setIpcConnect(true, null);
});

test('宿主未集成 host.ipc 时 configure 失败并提示', () => {
  reset();
  const host = (globalThis as any).host;
  const saved = host.ipc;
  delete host.ipc;
  try {
    assert.ok(!speech.configure(), '无 ipc 能力时 configure 应返回 false');
    assert.ok(
      String(lastEvent('error')!.message).indexOf('host.ipc') >= 0,
      '应提示宿主未集成 host.ipc 桥',
    );
  } finally {
    host.ipc = saved;
  }
});

// ================= 会话启动与音频推送 =================

test('start 成功后可推送 PCM（16kHz/mono/sessionId）', () => {
  reset();
  __ximeMock.setIpcStartResult(7);
  speech.configure();
  assert.ok(speech.start(), 'start 应返回 true');

  speech.feed(new Uint8Array([1, 2, 3, 4]));
  const written = __ximeMock.ipcWritten as Array<Record<string, unknown>>;
  assert.equal(written.length, 1);
  assert.equal(written[0].sessionId, 7);
  assert.equal(written[0].sampleRate, 16000);
  assert.equal(written[0].channels, 1);
  assert.equal((written[0].bytes as Uint8Array).length, 4);
});

test('startPcmSession 返回错误码时映射为可读提示且不再推送', () => {
  reset();
  // -3：说点啥未开启「外部输入法联动」
  __ximeMock.setIpcStartResult(-3);
  speech.configure();
  assert.ok(!speech.start(), '错误码时 start 应返回 false');
  assert.equal(lastEvent('error')!.message, '请在说点啥中启用「外部输入法联动」');

  speech.feed(new Uint8Array([9]));
  assert.equal((__ximeMock.ipcWritten as unknown[]).length, 0, '未启动成功时不应推送 PCM');
});

test('未知错误码回退到通用提示', () => {
  reset();
  __ximeMock.setIpcStartResult(-99);
  speech.configure();
  speech.start();
  assert.equal(lastEvent('error')!.message, '启动识别失败');
});

// ================= 结果与状态上报 =================

test('中间结果与最终结果分别上报，空文本不上报', () => {
  reset();
  speech.configure();
  speech.start();

  __ximeMock.ipcPartial('你好');
  assert.equal(lastEvent('partial')!.text, '你好');

  __ximeMock.ipcPartial('');
  assert.equal(asrEvents().filter((e) => e.type === 'partial').length, 1, '空 partial 不应上报');

  __ximeMock.ipcFinal('你好世界');
  assert.equal(lastEvent('final')!.text, '你好世界');

  __ximeMock.ipcFinal('');
  assert.equal(asrEvents().filter((e) => e.type === 'final').length, 1, '空 final 不应上报');
});

test('服务端状态码映射到宿主 emitState（越界收敛为 IDLE）', () => {
  reset();
  speech.configure();
  speech.start();

  __ximeMock.ipcState(1, '');
  __ximeMock.ipcState(2, '');
  assert.equal(lastEvent('state')!.state, 2);

  __ximeMock.ipcState(9, '');
  assert.equal(lastEvent('state')!.state, 0, '越界状态码应收敛为 IDLE');
});

test('识别侧错误上报为 error 事件', () => {
  reset();
  speech.configure();
  speech.start();

  __ximeMock.ipcError(-5, '当前识别供应商不支持外部推送 PCM');
  assert.equal(lastEvent('error')!.message, '当前识别供应商不支持外部推送 PCM');
});

// ================= 结束与清理 =================

test('stop 结束音频输入并等待最终结果', () => {
  reset();
  __ximeMock.setIpcStartResult(11);
  speech.configure();
  speech.start();
  speech.stop();

  assert.equal((__ximeMock.ipcFinished as number[])[0], 11);

  // 会话已结束：后续音频帧不再推送
  speech.feed(new Uint8Array([1]));
  assert.equal((__ximeMock.ipcWritten as unknown[]).length, 0);
});

test('cancel 取消会话且不上报结果', () => {
  reset();
  __ximeMock.setIpcStartResult(12);
  speech.configure();
  speech.start();
  speech.cancel();

  assert.equal((__ximeMock.ipcCancelled as number[])[0], 12);
  speech.feed(new Uint8Array([1]));
  assert.equal((__ximeMock.ipcWritten as unknown[]).length, 0);
});

test('onUnload 解绑外部服务', () => {
  reset();
  speech.configure();
  const before = __ximeMock.ipcClosed as number;
  plugin.onUnload();
  assert.equal((__ximeMock.ipcClosed as number), before + 1, 'onUnload 应调用 host.ipc.close');
});
