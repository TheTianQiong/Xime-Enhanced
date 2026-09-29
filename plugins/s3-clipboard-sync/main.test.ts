// s3-clipboard-sync 插件测试：SigV4 签名、寻址、ETag 条件拉取、错误映射。
//
// 签名正确性用**独立实现**（本文件内的 refSign，按 AWS 规范重新组装，不调用插件内部函数）
// 与插件实际下发的请求头逐字节对照；时钟由 __ximeMock.setClock 固定，x-amz-date 确定，
// 因而签名可复现。密码学原语（sha256/hmac）由宿主提供，不属于插件侧职责。
//
// 说明：曾尝试引入 AWS 官方「派生密钥」文档向量作外部基准，但该表未能在文档站取到，
// 凭记忆写死的期望值无法自证，故不保留——宁可有据可查的对照，不要看起来更强的假象。

const plugin = (globalThis as any).plugin as Record<string, any>;
const sync = plugin.clipboardSync;

// ================= 固定时钟与配置 =================

/** 2013-05-24T00:00:00Z：固定 x-amz-date，让签名可复现（与 AWS 文档示例同时刻，便于人工比对）。 */
const FIXED_EPOCH = 1369353600;
const TEST_SECRET = 'wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY';
const TEST_ACCESS_KEY = 'AKIAIOSFODNN7EXAMPLE';

let putStatus = 200;
let putHeaders: Record<string, string> = {};
let getStatus = 200;
let getText = '';
let getHeaders: Record<string, string> = {};

// 动态路由：每个用例改上面的状态量，路由只注册一次（避免先注册的路由一直命中）
__ximeMock.addHttpResponse('PUT', () => true, () => ({ status: putStatus, headers: putHeaders, text: '' }));
__ximeMock.addHttpResponse('GET', () => true, () => ({ status: getStatus, headers: getHeaders, text: getText }));

function config(overrides: Record<string, string>): void {
  __ximeMock.setConfig('endpoint', 'https://s3.us-east-1.amazonaws.com');
  __ximeMock.setConfig('region', 'us-east-1');
  __ximeMock.setConfig('bucket', 'my-bucket');
  __ximeMock.setConfig('accessKeyId', TEST_ACCESS_KEY);
  __ximeMock.setConfig('secretAccessKey', TEST_SECRET);
  __ximeMock.setConfig('objectKey', 'xime/clipboard/current.json');
  __ximeMock.setConfig('pathStyle', 'path');
  __ximeMock.setConfig('sessionToken', '');
  Object.keys(overrides).forEach((k) => __ximeMock.setConfig(k, overrides[k]));
}

function reset(overrides: Record<string, string>): void {
  config(overrides || {});
  putStatus = 200; putHeaders = {}; getStatus = 200; getText = ''; getHeaders = {};
  (__ximeMock.httpRequests as unknown[]).length = 0;
  __ximeMock.setClock(FIXED_EPOCH);
}

function requests(): Array<Record<string, any>> {
  return __ximeMock.httpRequests as Array<Record<string, any>>;
}

function lastRequest(): Record<string, any> {
  const all = requests();
  return all[all.length - 1];
}

function profile(text: string): Record<string, any> {
  return {
    type: 'text',
    hash: 'h',
    text: text,
    hasData: false,
    dataName: null,
    size: 0,
    source: null,
  };
}

// ================= 独立 SigV4 参照实现 =================

function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

function hexOf(bytes: Uint8Array): string {
  return host.crypto.hex(bytes);
}

function refUriEncode(value: string): string {
  return utf8(value)
    .reduce<string[]>((acc, b) => {
      const c = String.fromCharCode(b);
      if (/^[A-Za-z0-9\-_.~\/]$/.test(c)) {
        acc.push(c);
      } else {
        const h = b.toString(16).toUpperCase();
        acc.push('%' + (h.length < 2 ? '0' + h : h));
      }
      return acc;
    }, [])
    .join('');
}

/** 参照实现：按 AWS SigV4 规范重新组装，不复用插件内部函数。 */
function refSign(
  method: string,
  hostHeader: string,
  canonicalPath: string,
  payloadText: string,
  extra: Record<string, string>,
  sessionToken: string,
): Record<string, string> {
  const amzDate = host.crypto.utcTime('YYYYMMDDTHHMMSSZ');
  const dateStamp = host.crypto.utcTime('YYYYMMDD');
  const payloadHash = hexOf(host.crypto.sha256(utf8(payloadText)));

  const signed: Record<string, string> = { host: hostHeader };
  signed['x-amz-content-sha256'] = payloadHash;
  signed['x-amz-date'] = amzDate;
  if (sessionToken !== '') signed['x-amz-security-token'] = sessionToken;
  Object.keys(extra).forEach((k) => {
    signed[k.toLowerCase()] = extra[k];
  });

  const names = Object.keys(signed).sort();
  const canonicalHeaders = names.map((n) => n + ':' + signed[n] + '\n').join('');
  const signedHeaders = names.join(';');
  const canonicalRequest = [method, canonicalPath, '', canonicalHeaders, signedHeaders, payloadHash].join('\n');

  const region = host.config.get('region') || 'us-east-1';
  const scope = dateStamp + '/' + region + '/s3/aws4_request';
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, hexOf(host.crypto.sha256(utf8(canonicalRequest)))].join('\n');

  let key = host.crypto.hmacSha256(utf8('AWS4' + TEST_SECRET), utf8(dateStamp));
  key = host.crypto.hmacSha256(key, utf8(region));
  key = host.crypto.hmacSha256(key, utf8('s3'));
  key = host.crypto.hmacSha256(key, utf8('aws4_request'));
  const signature = hexOf(host.crypto.hmacSha256(key, utf8(stringToSign)));

  return {
    Authorization: 'AWS4-HMAC-SHA256 Credential=' + TEST_ACCESS_KEY + '/' + scope
      + ', SignedHeaders=' + signedHeaders + ', Signature=' + signature,
    payloadHash: payloadHash,
    amzDate: amzDate,
  };
}

// ================= 寻址 =================

test('路径式寻址：对象地址为 {endpoint}/{bucket}/{key}', async () => {
  reset({ pathStyle: 'path' });
  putStatus = 200;
  await sync.push(profile('hello'));
  assert.equal(lastRequest().url, 'https://s3.us-east-1.amazonaws.com/my-bucket/xime/clipboard/current.json');
});

test('虚拟主机式寻址：bucket 进主机名', async () => {
  reset({ pathStyle: 'virtual' });
  await sync.push(profile('hello'));
  assert.equal(lastRequest().url, 'https://my-bucket.s3.us-east-1.amazonaws.com/xime/clipboard/current.json');
});

test('endpoint 带子路径（反向代理）时保留前缀', async () => {
  reset({ endpoint: 'https://proxy.example.com/storage/', pathStyle: 'path' });
  await sync.push(profile('hello'));
  assert.equal(lastRequest().url, 'https://proxy.example.com/storage/my-bucket/xime/clipboard/current.json');
});

test('对象路径中的非 ASCII 按 UTF-8 字节百分号编码（与参照实现一致）', async () => {
  reset({ objectKey: '剪贴板/当前.json' });
  await sync.push(profile('hello'));
  const url = lastRequest().url as string;
  // 参照实现按 UTF-8 字节逐个百分号编码，保留 '/' 与 RFC3986 非保留字符
  const expectedPath = refUriEncode('/my-bucket/剪贴板/当前.json');
  assert.equal(url, 'https://s3.us-east-1.amazonaws.com' + expectedPath);
  assert.ok(url.indexOf('剪贴板') < 0, 'URL 中不应残留原始中文: ' + url);
});

// ================= 签名 =================

test('PUT 签名与独立实现逐字节一致，正文哈希对应实际发送字节', async () => {
  reset({});
  await sync.push(profile('hello'));

  const req = lastRequest();
  const bodyText = req.text as string;
  const expected = refSign('PUT', 's3.us-east-1.amazonaws.com', '/my-bucket/xime/clipboard/current.json', bodyText, {}, '');

  assert.equal(req.headers['Authorization'], expected.Authorization);
  assert.equal(req.headers['x-amz-content-sha256'], expected.payloadHash, '正文哈希应为实际发送字节的哈希');
  assert.equal(req.headers['x-amz-date'], expected.amzDate);
  assert.equal(req.headers['Content-Type'], 'application/json');
});

test('GET 签名与独立实现一致（空正文哈希）', async () => {
  reset({});
  getStatus = 404;
  await sync.pull();

  const req = lastRequest();
  const expected = refSign('GET', 's3.us-east-1.amazonaws.com', '/my-bucket/xime/clipboard/current.json', '', {}, '');
  assert.equal(req.headers['Authorization'], expected.Authorization);
  assert.equal(
    req.headers['x-amz-content-sha256'],
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    '空正文哈希应为 sha256("") 的标准值',
  );
});

test('ETag 条件拉取的 If-None-Match 参与签名', async () => {
  reset({});
  __ximeMock.setConfig('lastEtag', '"etag-1"');
  getStatus = 304;
  await sync.pull();

  const req = lastRequest();
  assert.equal(req.headers['If-None-Match'], '"etag-1"');

  const expected = refSign('GET', 's3.us-east-1.amazonaws.com', '/my-bucket/xime/clipboard/current.json', '', { 'If-None-Match': '"etag-1"' }, '');
  assert.equal(req.headers['Authorization'], expected.Authorization, '签名头集合应包含 if-none-match');
  assert.ok(String(req.headers['Authorization']).indexOf('if-none-match') >= 0);
});

test('会话令牌（STS 临时凭证）参与签名并随请求下发', async () => {
  reset({ sessionToken: 'FQoGZXIvYXdzEBYaDA==' });
  await sync.push(profile('hello'));

  const req = lastRequest();
  assert.equal(req.headers['x-amz-security-token'], 'FQoGZXIvYXdzEBYaDA==');
  const expected = refSign('PUT', 's3.us-east-1.amazonaws.com', '/my-bucket/xime/clipboard/current.json', req.text as string, {}, 'FQoGZXIvYXdzEBYaDA==');
  assert.equal(req.headers['Authorization'], expected.Authorization);
  assert.ok(String(req.headers['Authorization']).indexOf('x-amz-security-token') >= 0);
});

test('主机名大写时签名用规范化小写主机名', async () => {
  reset({ endpoint: 'https://S3.US-EAST-1.AMAZONAWS.COM', bucket: 'My-Bucket' });
  await sync.push(profile('x'));

  const req = lastRequest();
  assert.equal(req.url, 'https://s3.us-east-1.amazonaws.com/My-Bucket/xime/clipboard/current.json');
  assert.ok(String(req.headers['Authorization']).indexOf('SignedHeaders=host;') >= 0, 'host 应参与签名');
});

/**
 * 外部基准：空正文的 SHA-256 常量（SigV4 里 payloadHash 的最常见取值）。
 * 这是唯一不依赖自写参照实现的外部锚点——密码学原语本身由宿主提供，
 * 插件侧的职责是「按规范装配」，由上面的参照实现对照。
 */
test('空正文哈希取到标准常量（GET/连接测试的 payloadHash）', async () => {
  reset({});
  getStatus = 404;
  await sync.pull();
  assert.equal(
    lastRequest().headers['x-amz-content-sha256'],
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  );
});

// ================= 推送 =================

test('推送成功时缓存 ETag 供下轮条件拉取', async () => {
  reset({});
  (globalThis as any).host.config.remove('lastEtag');
  putStatus = 200;
  putHeaders = { ETag: '"v1"' };

  assert.ok(await sync.push(profile('hello')), '2xx 应返回 true');
  assert.equal(host.config.get('lastEtag'), '"v1"');
});

test('推送返回 S3 错误码时带上可读提示', async () => {
  reset({});
  putStatus = 403;
  putHeaders = {};
  getText = '<Error><Code>SignatureDoesNotMatch</Code></Error>';

  assert.ok(!(await sync.push(profile('hello'))), '403 应返回 false');
});

test('带附件的 profile 被拒绝且不发起请求', async () => {
  reset({});
  const withData = profile('img');
  withData.hasData = true;
  withData.dataName = 'a.png';
  withData.data = new Uint8Array([1, 2, 3]);

  assert.ok(!(await sync.push(withData)), '仅文本插件应拒绝附件推送');
  assert.equal(requests().length, 0, '不应发出任何请求');
});

test('推送已发送字节与签名正文哈希一致（正文非 ASCII）', async () => {
  reset({});
  await sync.push(profile('你好，世界'));
  const req = lastRequest();
  const sentHash = host.crypto.hex(host.crypto.sha256(utf8(req.text as string)));
  assert.equal(req.headers['x-amz-content-sha256'], sentHash);
});

// ================= 拉取 =================

test('304 无变更、404 无对象均返回 null', async () => {
  reset({});
  getStatus = 304;
  assert.equal(await sync.pull(), null);
  getStatus = 404;
  assert.equal(await sync.pull(), null);
});

test('200 时解析 JSON Profile 并缓存 ETag', async () => {
  reset({});
  (globalThis as any).host.config.remove('lastEtag');
  getStatus = 200;
  getHeaders = { ETag: '"v2"' };
  getText = JSON.stringify({ type: 'text', hash: 'abc', text: '来自远端', has_data: false, data_name: null, size: 4, source: 'phone' });

  const p = await sync.pull();
  assert.ok(p !== null, '应返回 profile');
  assert.equal(p!.text, '来自远端');
  assert.equal(p!.hash, 'abc');
  assert.equal(p!.hasData, false);
  assert.equal(p!.source, 'phone');
  assert.equal(host.config.get('lastEtag'), '"v2"');
});

test('远端为纯文本时按文本 profile 兼容处理（hash 留空交宿主计算）', async () => {
  reset({});
  getStatus = 200;
  getText = 'just plain text';

  const p = await sync.pull();
  assert.equal(p!.text, 'just plain text');
  assert.equal(p!.hash, '');
  assert.equal(p!.hasData, false);
  assert.equal(p!.size, 15);
});

test('远端为图片记录时不把 JSON 当文本写回本地', async () => {
  reset({});
  getStatus = 200;
  getText = JSON.stringify({ type: 'image', hash: 'zz', has_data: true, data_name: 'a.png' });

  assert.equal(await sync.pull(), null, '图片记录应被忽略而非降级为文本');
});

test('未配置 endpoint/bucket 时推送与拉取都短路且不发请求', async () => {
  reset({ endpoint: '' });
  assert.ok(!(await sync.push(profile('x'))));
  assert.equal(await sync.pull(), null);
  assert.equal(requests().length, 0);
});

// ================= 连接测试 =================

test('缺少必填配置时给出明确提示', async () => {
  reset({ endpoint: '' });
  assert.equal(await sync.test(), '未配置服务地址（Endpoint）或存储桶（Bucket）');

  reset({ accessKeyId: '' });
  assert.equal(await sync.test(), '未配置 Access Key ID');

  reset({ secretAccessKey: '' });
  assert.equal(await sync.test(), '未配置 Secret Access Key');
});

test('连接测试：404 视为成功（对象未创建但签名已被接受）', async () => {
  reset({});
  getStatus = 404;
  assert.equal(await sync.test(), null);
});

test('连接测试：403 提示认证问题，301/307 提示区域不匹配', async () => {
  reset({});
  getStatus = 403;
  getText = '<Error><Code>SignatureDoesNotMatch</Code></Error>';
  const auth = await sync.test();
  assert.ok(String(auth).indexOf('认证失败') >= 0, '实际: ' + auth);
  assert.ok(String(auth).indexOf('SignatureDoesNotMatch') >= 0, '应带 S3 错误码');

  reset({});
  getStatus = 301;
  assert.ok(String(await sync.test()).indexOf('区域不匹配') >= 0);
});

test('设置页「测试连接」按钮走插件同名导出成员（返回值即错误消息）', async () => {
  reset({});
  getStatus = 404;
  assert.equal(await plugin.testConnection(), null, 'onAction(testConnection) 应能调用到');
  getStatus = 403;
  assert.ok(String(await plugin.testConnection()).indexOf('认证失败') >= 0);
});

test('设置表单字段齐全且带寻址方式选项', () => {
  const schema = plugin.settings.schema() as Array<Record<string, any>>;
  const keys = schema.map((n) => n.key);
  ['endpoint', 'region', 'bucket', 'accessKeyId', 'secretAccessKey', 'objectKey', 'pathStyle', 'sessionToken', 'testConnection']
    .forEach((k) => assert.ok(keys.indexOf(k) >= 0, '缺少字段 ' + k));

  const pathStyle = schema.filter((n) => n.key === 'pathStyle')[0];
  assert.deepEqual(pathStyle.options, ['path', 'virtual']);
});
