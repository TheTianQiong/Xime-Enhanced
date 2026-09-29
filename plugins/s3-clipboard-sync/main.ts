// S3 剪贴板同步插件（TypeScript 脚本插件）
//
// 通过 S3 兼容对象存储上的单个 JSON 对象实现剪贴板多设备双向同步。
// 兼容 AWS S3 / Cloudflare R2 / MinIO / Backblaze B2 等 S3 兼容服务。
//
// 职责划分：
//   插件  = 协议逻辑（SigV4 签名、PUT/GET、ETag 条件拉取、Profile JSON）
//   宿主  = 同步引擎（轮询/去重/回声抑制）+ 通用原语：
//     host.http        请求原语（PUT/GET，async await，域名经用户授权）
//     host.crypto      SigV4 原语（sha256/hmacSha256/hex/utcTime）
//     host.config      配置存储（含 ETag 缓存）
//
// 对象地址：
//   path-style      {endpoint}/{bucket}/{objectKey}      （推荐：MinIO/R2/B2 及自建）
//   virtual-hosted  https://{bucket}.{host}/{objectKey}  （AWS 默认）
// 认证：AWS Signature Version 4（Authorization 头签名，签名头集合随请求动态生成）
//   正文哈希 x-amz-content-sha256 与实际发送字节一致；
//   Content-Type 只发送不参与签名（SigV4 仅要求 host 与 x-amz-* 必须签名），
//   使签名与请求体类型解耦。
//
// JS 约定：JSON 用原生 JSON（宿主不再提供 host.json；JSON.parse 非法输入抛异常，
// 用 try/catch 保持原 decode 失败返回 null 的语义）；body 一律传 Uint8Array。

const KEY_ENDPOINT = 'endpoint';
const KEY_REGION = 'region';
const KEY_BUCKET = 'bucket';
const KEY_ACCESS_KEY = 'accessKeyId';
const KEY_SECRET_KEY = 'secretAccessKey';
const KEY_OBJECT_KEY = 'objectKey';
const KEY_PATH_STYLE = 'pathStyle';
const KEY_SESSION_TOKEN = 'sessionToken';
const KEY_LAST_ETAG = 'lastEtag';

const DEFAULT_OBJECT_KEY = 'xime/clipboard/current.json';
const ALGORITHM = 'AWS4-HMAC-SHA256';
const SERVICE = 's3';

// ================= 工具函数 =================

function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

function trim(value: string): string {
  return value.replace(/^\s+/, '').replace(/\s+$/, '');
}

/** 读取配置项：null / 空串一律回退默认值。 */
function configOr(key: string, fallback: string): string {
  const v = host.config.get(key);
  if (v === null || v === undefined) return fallback;
  const t = trim(v);
  return t === '' ? fallback : t;
}

/**
 * RFC3986 编码：非保留字符（A-Za-z0-9-_.~）不编码。
 * `encodeSlash=false` 时保留 '/'，用于 CanonicalURI 与请求路径。
 */
function uriEncode(value: string, encodeSlash: boolean): string {
  let out = '';
  for (let i = 0; i < value.length; i++) {
    const c = value.charAt(i);
    if (/[A-Za-z0-9\-_.~]/.test(c)) {
      out += c;
    } else if (c === '/' && !encodeSlash) {
      out += c;
    } else {
      // UTF-8 字节逐个百分号编码（中文对象名等非 ASCII 字符必须按字节而非码元编码）
      const bytes = utf8(c);
      for (let b = 0; b < bytes.length; b++) {
        const hex = bytes[b].toString(16).toUpperCase();
        out += '%' + (hex.length < 2 ? '0' + hex : hex);
      }
    }
  }
  return out;
}

function isPathStyle(): boolean {
  return configOr(KEY_PATH_STYLE, 'path') !== 'virtual';
}

/** 对象地址；未配置完整时返回 null。 */
interface S3Target {
  url: string;
  host: string;
  canonicalPath: string;
}

function objectUrl(): S3Target | null {
  const endpoint = configOr(KEY_ENDPOINT, '');
  const bucket = configOr(KEY_BUCKET, '');
  if (endpoint === '' || bucket === '') return null;

  const schemeMatch = endpoint.match(/^(https?):\/\/(.+)$/);
  if (schemeMatch === null) return null;
  const scheme = schemeMatch[1];
  let authority = schemeMatch[2].replace(/\/+$/, '');

  // 允许 endpoint 携带子路径（如反向代理到 MinIO：https://host/storage）
  let basePath = '';
  const slash = authority.indexOf('/');
  if (slash >= 0) {
    basePath = authority.substring(slash).replace(/\/+$/, '');
    authority = authority.substring(0, slash);
  }

  const objectKey = configOr(KEY_OBJECT_KEY, DEFAULT_OBJECT_KEY).replace(/^\/+/, '');
  let hostHeader: string;
  let rawPath: string;
  if (isPathStyle()) {
    hostHeader = authority;
    rawPath = basePath + '/' + bucket + '/' + objectKey;
  } else {
    hostHeader = bucket + '.' + authority;
    rawPath = basePath + '/' + objectKey;
  }
  if (rawPath.charAt(0) !== '/') rawPath = '/' + rawPath;

  // Host 值统一小写：HTTP 客户端按 URL 的规范化主机名发 Host 头（HttpUrl 会把主机名小写），
  // 签名里的 host 必须与实际发出的那一份逐字节一致
  hostHeader = hostHeader.toLowerCase();

  const canonicalPath = uriEncode(rawPath, false);
  return {
    url: scheme + '://' + hostHeader + canonicalPath,
    host: hostHeader,
    canonicalPath: canonicalPath,
  };
}

/** 从 S3 错误 XML 中提取 <Code>xxx</Code>，便于定位 SignatureDoesNotMatch 之类问题。 */
function s3ErrorHint(resp: XimeHttpResponse | null): string {
  if (resp === null || resp === undefined) return '';
  const text = resp.text;
  if (text === null || text === undefined) return '';
  const m = text.match(/<Code>([^<]+)<\/Code>/);
  return m === null ? '' : '（' + m[1] + '）';
}

/**
 * 构造 SigV4 签名头，返回可直接交给 host.http.request 的 headers。
 *
 * @param extra 额外参与签名的头（如 `{ 'If-None-Match': etag }`），可为 null
 *
 * 不发送显式 Host 头：HTTP 客户端按 URL 权威段自动补齐，其值与我们签的 `host`
 * 相同（见 [objectUrl] 的小写规范化），重复下发反而会产生两个 Host 头。
 */
function signedHeaders(
  method: string,
  hostHeader: string,
  canonicalPath: string,
  payload: Uint8Array,
  contentType: string | null,
  extra: Record<string, string> | null,
): Record<string, string> {
  const amzDate = host.crypto.utcTime('YYYYMMDDTHHMMSSZ');
  const dateStamp = host.crypto.utcTime('YYYYMMDD');
  const payloadHash = host.crypto.hex(host.crypto.sha256(payload));

  // 参与签名的头集合（排序后逐行拼进 CanonicalHeaders；未签名的头不参与）
  const entries: Array<[string, string]> = [
    ['host', hostHeader],
    ['x-amz-content-sha256', payloadHash],
    ['x-amz-date', amzDate],
  ];
  const token = configOr(KEY_SESSION_TOKEN, '');
  if (token !== '') {
    entries.push(['x-amz-security-token', token]);
  }
  if (extra !== null) {
    Object.keys(extra).forEach((k) => {
      entries.push([k.toLowerCase(), extra[k]]);
    });
  }
  entries.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));

  let canonicalHeaders = '';
  const names: string[] = [];
  entries.forEach((h) => {
    canonicalHeaders += h[0] + ':' + h[1] + '\n';
    names.push(h[0]);
  });
  const signedNames = names.join(';');

  // CanonicalRequest：CanonicalHeaders 末尾已带 \n，其后还需一个空行分隔
  const canonicalRequest = method + '\n' + canonicalPath + '\n' + '\n'
    + canonicalHeaders + '\n' + signedNames + '\n' + payloadHash;

  const region = configOr(KEY_REGION, 'us-east-1');
  const scope = dateStamp + '/' + region + '/' + SERVICE + '/aws4_request';
  const stringToSign = ALGORITHM + '\n' + amzDate + '\n' + scope + '\n'
    + host.crypto.hex(host.crypto.sha256(utf8(canonicalRequest)));

  // 派生签名密钥：kDate → kRegion → kService → kSigning
  const secretKey = configOr(KEY_SECRET_KEY, '');
  const kDate = host.crypto.hmacSha256(utf8('AWS4' + secretKey), utf8(dateStamp));
  const kRegion = host.crypto.hmacSha256(kDate, utf8(region));
  const kService = host.crypto.hmacSha256(kRegion, utf8(SERVICE));
  const kSigning = host.crypto.hmacSha256(kService, utf8('aws4_request'));
  const signature = host.crypto.hex(host.crypto.hmacSha256(kSigning, utf8(stringToSign)));

  const accessKey = configOr(KEY_ACCESS_KEY, '');
  const headers: Record<string, string> = {
    'x-amz-content-sha256': payloadHash,
    'x-amz-date': amzDate,
    'Authorization': ALGORITHM + ' Credential=' + accessKey + '/' + scope
      + ', SignedHeaders=' + signedNames + ', Signature=' + signature,
  };
  if (contentType !== null) headers['Content-Type'] = contentType;
  if (token !== '') headers['x-amz-security-token'] = token;
  if (extra !== null) {
    Object.keys(extra).forEach((k) => {
      headers[k] = extra[k];
    });
  }
  return headers;
}

function cacheEtag(resp: XimeHttpResponse | null): void {
  if (resp === null || resp === undefined || !resp.headers) return;
  const etag = resp.headers['ETag'] || resp.headers['etag'];
  if (etag !== null && etag !== undefined && etag !== '') {
    host.config.set(KEY_LAST_ETAG, etag);
  }
}

// ================= 同步接口 =================

/** 推送本地 profile 到远端对象（宿主剪贴板变化时调用）。 */
async function push(profile: XimeClipboardProfile): Promise<boolean> {
  const target = objectUrl();
  if (target === null) {
    host.logError('push failed: 未配置服务地址或存储桶');
    return false;
  }
  // 本插件只同步文本（未声明 clipboard_sync.attachments，宿主不会推图片）。
  // 万一带附件字节进来，拒绝而不是只写元数据——否则对端会拉到指向缺失附件的悬空引用。
  if (profile.hasData) {
    host.logError('push failed: 本插件仅同步文本，跳过带附件的剪贴板');
    return false;
  }
  let body: string;
  try {
    // wire 协议为 snake_case（与 ximed Profile 同构），SDK profile 为 camelCase：显式映射
    body = JSON.stringify({
      type: profile.type,
      hash: profile.hash,
      text: profile.text,
      has_data: profile.hasData,
      data_name: profile.dataName,
      size: profile.size,
      source: profile.source,
    });
  } catch (e) {
    host.logError('push failed: Profile JSON 序列化失败');
    return false;
  }
  const payload = utf8(body);
  const headers = signedHeaders('PUT', target.host, target.canonicalPath, payload, 'application/json', null);
  let resp: XimeHttpResponse;
  try {
    resp = await host.http.request('PUT', target.url, headers, payload);
  } catch (e) {
    host.logError('push failed: 请求失败 ' + ((e as Error).message || ''));
    return false;
  }
  if (resp.status >= 200 && resp.status < 300) {
    cacheEtag(resp);
    host.log('push ok: PUT ' + target.url + ' -> ' + resp.status);
    return true;
  }
  host.logError('push failed: PUT ' + target.url + ' -> HTTP ' + resp.status + s3ErrorHint(resp));
  return false;
}

/** 拉取远端 profile（宿主轮询调用）；无变更/无对象返回 null。 */
async function pull(): Promise<XimeClipboardProfile | null> {
  const target = objectUrl();
  if (target === null) {
    host.log('pull: 未配置服务地址或存储桶');
    return null;
  }
  // ETag 条件拉取：未变更时服务端返回 304，省流量
  let extra: Record<string, string> | null = null;
  const etag = host.config.get(KEY_LAST_ETAG);
  if (etag !== null && etag !== undefined && etag !== '') {
    extra = { 'If-None-Match': etag };
  }
  const headers = signedHeaders('GET', target.host, target.canonicalPath, new Uint8Array(0), null, extra);
  let resp: XimeHttpResponse;
  try {
    resp = await host.http.request('GET', target.url, headers, null);
  } catch (e) {
    host.logError('pull failed: 请求失败 ' + ((e as Error).message || ''));
    return null;
  }
  if (resp.status === 304) {
    // Not Modified，无变更
    return null;
  }
  if (resp.status === 404) {
    // 远端尚无对象，视为无变更
    return null;
  }
  if (resp.status >= 200 && resp.status < 300) {
    cacheEtag(resp);
    const text = resp.text;
    if (text === null || text === undefined || text === '') {
      host.log('pull: 远端对象为空');
      return null;
    }
    let decoded: unknown = null;
    try {
      decoded = JSON.parse(text);
    } catch (e) {
      decoded = null;
    }
    if (decoded !== null && decoded !== undefined && typeof decoded === 'object'
      && !Array.isArray(decoded)) {
      const w = decoded as Record<string, unknown>;
      // 图片记录没有 text 键（只有 has_data）：不能掉进下面的纯文本兼容分支，
      // 否则会把整段 JSON 当剪贴板文本写回本地
      if (w.has_data === true) {
        host.log('pull: 远端为图片记录，本插件仅同步文本，忽略');
        return null;
      }
      if (w.text !== null && w.text !== undefined) {
        // JSON Profile（与 ximed Profile 同构）→ SDK profile（camelCase）
        return {
          type: w.type === undefined || w.type === null ? 'text' : String(w.type),
          hash: w.hash === undefined || w.hash === null ? '' : String(w.hash),
          text: String(w.text),
          hasData: false,
          dataName: null,
          size: typeof w.size === 'number' ? w.size : 0,
          source: w.source === undefined || w.source === null ? null : String(w.source),
        };
      }
    }
    // 远端为纯文本：构造 profile，hash 留空让宿主计算
    host.log('pull: 远端非 JSON，按纯文本兼容处理');
    return {
      type: 'text',
      hash: '',
      text,
      hasData: false,
      dataName: null,
      size: utf8(text).length,
      source: null,
    };
  }
  host.logError('pull failed: GET ' + target.url + ' -> HTTP ' + resp.status + s3ErrorHint(resp));
  return null;
}

/** 校验配置可用性（连接测试）；返回错误消息，null 表示成功。 */
async function testConnection(): Promise<string | null> {
  const target = objectUrl();
  if (target === null) return '未配置服务地址（Endpoint）或存储桶（Bucket）';
  if (configOr(KEY_ACCESS_KEY, '') === '') return '未配置 Access Key ID';
  if (configOr(KEY_SECRET_KEY, '') === '') return '未配置 Secret Access Key';

  const headers = signedHeaders('GET', target.host, target.canonicalPath, new Uint8Array(0), null, null);
  let resp: XimeHttpResponse;
  try {
    resp = await host.http.request('GET', target.url, headers, null);
  } catch (e) {
    return (e as Error).message || '连接失败';
  }
  if (resp.status >= 200 && resp.status < 300) return null;
  // 404：对象尚未创建（但签名已被服务端接受；签名错误会返回 403）
  if (resp.status === 404) return null;
  if (resp.status === 403) {
    return '认证失败（HTTP 403）' + s3ErrorHint(resp)
      + '：请检查 Access Key / Secret Key / 区域与时钟';
  }
  if (resp.status === 301 || resp.status === 307) {
    return '区域不匹配（HTTP ' + resp.status + '）：请填写存储桶实际所在区域';
  }
  return '连接失败（HTTP ' + resp.status + '）' + s3ErrorHint(resp);
}

// 配置页「测试连接」按钮 → 宿主 `onAction('testConnection')` 调用插件同名导出成员；
// 返回错误消息（null/空 → 设置页提示「成功」）。与 clipboardSync.test 同一份逻辑。
const actionHooks = { testConnection };

const plugin = definePlugin({
  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          key: KEY_ENDPOINT,
          label: '服务地址（Endpoint）',
          type: 'text',
          placeholder: 'https://s3.us-east-1.amazonaws.com',
          helpText: 'S3 兼容服务地址。AWS：https://s3.<区域>.amazonaws.com；'
            + 'R2：https://<账户ID>.r2.cloudflarestorage.com；MinIO：http://<主机>:9000',
        },
        {
          key: KEY_REGION,
          label: '区域（Region）',
          type: 'text',
          placeholder: 'us-east-1',
          helpText: 'AWS 填实际区域（如 us-east-1、ap-northeast-1）；Cloudflare R2 填 auto',
        },
        {
          key: KEY_BUCKET,
          label: '存储桶（Bucket）',
          type: 'text',
          placeholder: 'my-bucket',
        },
        {
          key: KEY_ACCESS_KEY,
          label: 'Access Key ID',
          type: 'text',
        },
        {
          key: KEY_SECRET_KEY,
          label: 'Secret Access Key',
          type: 'secret',
        },
        {
          key: KEY_OBJECT_KEY,
          label: '对象路径',
          type: 'text',
          placeholder: DEFAULT_OBJECT_KEY,
          required: false,
          helpText: '剪贴板对象在桶内的键名（留空为 ' + DEFAULT_OBJECT_KEY + '）',
        },
        {
          key: KEY_PATH_STYLE,
          label: '寻址方式',
          type: 'select',
          defaultValue: 'path',
          options: ['path', 'virtual'],
          helpText: 'path：路径式（兼容 MinIO/R2/B2 与自建，推荐）；virtual：虚拟主机式（AWS 默认）',
        },
        {
          key: KEY_SESSION_TOKEN,
          label: '会话令牌（可选）',
          type: 'secret',
          required: false,
          helpText: '仅使用 STS 临时凭证时需要填写',
        },
        {
          key: 'testConnection',
          label: '测试连接',
          type: 'button',
          required: false,
        },
      ];
    },
  },

  clipboardSync: {
    push,
    pull,
    test: testConnection,
  },

  ...actionHooks,
});

export default plugin;
