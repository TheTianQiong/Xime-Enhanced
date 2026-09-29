// bibi（asr-keyboard / 说点啥）AIDL 桥语音识别插件（TypeScript 脚本插件）
//
// 职责划分：
//   插件（本文件） = 会话状态机、host.ipc 调度、错误码映射、结果上报
//   宿主           = 录音（固定 16kHz/mono/PCM16LE，100ms/帧）+ host.ipc AIDL 桥原语
//   说点啥         = 实际识别引擎（外部调用跟随说点啥内部设置，本插件不选择供应商）
//
// 数据流：
//   宿主录音 → speech.feed(pcm) → host.ipc.writePcm(sessionId, pcm, 16000, 1)
//   → 说点啥识别 → 回调 onPartial/onFinal → host.asr.emitPartial/emitFinal → 上屏
//
// 依赖宿主集成 host.ipc 桥（v3 起随宿主发布，无需 manifest 声明）：
// 未集成时 host.has('ipc') 为 false，configure 直接失败并给出可读错误。

const SAMPLE_RATE = 16000;
const CHANNELS = 1;

// startPcmSession 错误码 → 用户提示（-2/-3/-5 由说点啥返回；-100~-102 为宿主本地错误）
const ERROR_MSGS: Record<number, string> = {
  [-2]: '系统忙碌，请稍后再试',
  [-3]: '请在说点啥中启用「外部输入法联动」',
  [-5]: '当前识别供应商不支持外部推送 PCM',
  [-100]: '未检测到说点啥（asr-keyboard），请先安装',
  [-101]: '绑定说点啥服务超时',
  [-102]: '说点啥服务未连接',
};

/** 当前推送会话 id；0 表示无活动会话。 */
let sessionId = 0;

/** 与宿主 host.asr.emitState 取值一致：0=IDLE 1=LISTENING 2=PROCESSING 3=ERROR。 */
let currentState: 0 | 1 | 2 | 3 = 0;

/** 外部服务状态码 → 宿主 emitState 取值（越界视为 IDLE，避免非法值破坏宿主状态机）。 */
function normalizeState(state: number): 0 | 1 | 2 | 3 {
  return state === 1 || state === 2 || state === 3 ? state : 0;
}

// ================= 元信息 =================

/** 无独立配置项：识别供应商与设置完全跟随说点啥内部配置，始终视为已就绪。 */
function isConfigured(): boolean {
  return true;
}

// ================= 会话生命周期 =================

/**
 * 绑定说点啥外部语音服务（Pro → 开源顺序）并注册会话回调。
 * 宿主在每次开始识别前调用；已绑定时宿主侧仅刷新监听（幂等）。
 */
function configure(): boolean {
  if (!host.has('ipc')) {
    host.asr.emitError('当前宿主未集成 host.ipc 桥，无法联动说点啥');
    return false;
  }

  const ok = host.ipc.connect({
    onState: (_sessionId, state) => {
      // 0=IDLE 1=Recording 2=Processing 3=Error
      currentState = normalizeState(state);
      host.asr.emitState(currentState);
    },
    onPartial: (_sessionId, text) => {
      if (text) host.asr.emitPartial(text);
    },
    onFinal: (_sessionId, text) => {
      if (text) host.asr.emitFinal(text);
      currentState = 0;
    },
    onError: (_sessionId, _code, message) => {
      currentState = 3;
      host.asr.emitError(message);
    },
    // onAmplitude 供波形动画，本插件不消费
  });

  if (!ok) {
    host.asr.emitError(host.ipc.lastError() || '绑定说点啥服务失败');
    return false;
  }
  return true;
}

function start(): boolean {
  // 阻塞等待绑定完成（宿主 ≤5s 超时），返回 sessionId(>0) 或错误码
  sessionId = host.ipc.startPcmSession();
  if (sessionId <= 0) {
    host.asr.emitError(ERROR_MSGS[sessionId] || host.ipc.lastError() || '启动识别失败');
    sessionId = 0;
    return false;
  }
  return true;
}

function feed(chunk: Uint8Array): void {
  // 宿主每帧 0.1s（3200B）；如需贴合说点啥建议的 ~200ms 一包，可在此攒两帧再推
  if (sessionId > 0) {
    host.ipc.writePcm(sessionId, chunk, SAMPLE_RATE, CHANNELS);
  }
}

/** 正常结束：音频输入结束，等待最终结果（经 onFinal 回传）。 */
function stop(): void {
  if (sessionId > 0) {
    host.ipc.finishPcm(sessionId);
  }
  sessionId = 0;
}

/** 取消：丢弃未出结果并清理会话。 */
function cancel(): void {
  if (sessionId > 0) {
    host.ipc.cancelSession(sessionId);
  }
  sessionId = 0;
  currentState = 0;
}

/** 插件卸载/停用：解绑服务并清理资源（宿主未集成 ipc 时空转）。 */
function onUnload(): void {
  sessionId = 0;
  currentState = 0;
  if (host.has('ipc')) host.ipc.close();
}

const plugin = definePlugin({
  speech: {
    isConfigured,
    configure,
    feed,
    start,
    stop,
    cancel,
  },

  onUnload,
});

export default plugin;
