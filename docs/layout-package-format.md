# 布局包格式与双拼提示表

> 日期：2026-09-29
> 背景：把「双拼提示」从写死在宿主里的方案表改为可由配置提供，从而支持热更与布局市场分发。本文档定义布局包（`layouts/index.yaml` 分发物）的内容格式，重点说明新增的双拼提示表文件。
> 关系：布局市场机制本身见 `XimeIndexSource` / `LayoutMarketViewModel`；插件体系是另一套东西（见 `plugin-system-audit.md`），**布局包不执行任何代码**。

## 1. 布局包是什么

布局市场（设置 → 扩展商店 → 布局）下载的是一个 zip，解压后**只往 rime 用户目录释放资源**，宿主不执行包内任何代码。允许的内容只有三类：

| 路径 | 用途 |
|---|---|
| 根级 `xime.custom.yaml` | 键面布局/主题配置补丁（会**覆盖**当前同文件，不保留备份） |
| 根级 `shuangpin_hints.custom.yaml` | 双拼提示表（本次新增） |
| `themes/`、`fonts/` | 背景图与字体资源（放子目录，写进 `xime.custom.yaml` 引用） |

其余路径一律被丢弃，且必须至少包含一个配置补丁（只带主题/字体的包会被判无效）。判定实现在 `app/src/main/java/com/kingzcheung/xime/settings/LayoutPackagePolicy.kt`，打包脚本 `scripts/pack-layout.py` 会用同一套规则做预检。

两个 `*.custom.yaml` 有额外保护，升级与市场清单都不会碰它们：

- `RimeConfigHelper.updateBuiltinAssets` 显式排除 `*.custom.yaml`（APK 升级不覆盖用户配置）
- `SchemaManifestManager.isUserDataFile` 同样按后缀排除（不被市场清单追踪/回滚）

**提示表刻意与 `xime.custom.yaml` 分开**：后者会被「应用布局」整体覆盖，提示表混在里面会被连带冲掉。想让提示表与键面布局互不影响，就分成两个包（或一个包同时带两个文件，但要清楚换布局会覆盖键面部分）。

## 2. 双拼提示表 `shuangpin_hints.custom.yaml`

### 2.1 格式

```yaml
version: 1
schemes:
  # id 与内置方案相同 → 整条覆盖内置；新 id → 追加到末尾
  - id: flypy
    name: 小鹤双拼            # 可省略，缺省用 id
    schema_keys: ["flypy"]    # 可省略，缺省用 [id]；用于按当前 rime 方案自动匹配
    shengmu:                  # 键 → 声母；未列出的键按单字母原样显示
      v: zh
      i: ch
      u: sh
    yunmu:                    # 键 → 韵母；标量与列表两种写法都接受
      a: [a]
      k: [uai, ing]           # 多韵母：键面自动分行 / 三韵母走括号合并
      n: [ue, ve, ui]
```

内置 8 套方案（小鹤 `flypy`、通用 `tongyong`、自然码 `ziranma`、紫光 `ziguang`、微软 `mspy`、智能ABC `abc`、搜狗 `sogou`、加加 `jiajia`）的键位见 `app/src/main/java/com/kingzcheung/xime/shuangpin/ShuangpinSchemes.kt`，可照抄后修改——**覆盖是整条替换，不是字段级合并**，所以自定义某个内置方案时要把声母表与韵母表给全，只写一半会丢掉另一半。

### 2.2 匹配规则

宿主按当前 rime 方案 id 自动匹配：命中 `schema_keys` 中任一项即视为该方案（子串匹配）；其中 `double_pinyin` 采用**精确匹配**（它是其它子方案名的子串，注意不要把 `double_pinyin` 与 `double_pinyin_flypy` 混写）。匹配不到内置表时提示功能自动关闭（非双拼方案不受影响）。

### 2.3 生效与容错

- 改文件后由既有重载路径生效（键盘弹起、手动「部署方案」、应用布局等都会触发；纯显示层，**不需要** rime 重新部署）。
- 文件缺失、YAML 非法、单条方案非法（id 为空/重复、声母韵母都空）→ 对应部分**回退内置表**，键盘不会因此不可用。因此坏配置的最坏后果是「提示没变」，而不是崩溃。
- 键数奇偶决定键面显示声母还是韵母（奇数键显示韵母），这一条与具体方案无关，不受配置影响。

### 2.4 与键面布局标签的优先级

若包内（或用户自己的）`xime.custom.yaml` 为某个字母键写了**另有内容**的键面标签（例如 `q: { tap: { label: "七" } }`），该键以配置为准，双拼提示让位——布局作者明确指定了键面就不该被提示顶掉。

判定是「标签与按键字母是否不同」：只写 `q: { tap: "q", swipe_up: "!" }`（为了覆盖同一键的其它手势而重述 tap）不会让提示让位。想整体关掉提示，用设置里的「双拼提示」开关。

## 3. 打包与发布

```bash
python3 scripts/pack-layout.py --dir <布局目录> --out build/layout-release \
    --id shuangpin-hints --name "双拼提示表" --version 1.0.0 --author <作者> \
    --date 2026-09-29
```

脚本会做白名单预检、产出 `<id>-<version>.zip`，并打印 sha256 与可直接粘贴的索引条目。zip 内的条目时间戳取自 `--date`，因此**同样的输入与日期打包出的 sha256 稳定可复现**（索引里的 sha256 才不至于每次打包都变）。

索引条目加到 xime-index 仓库的 `layouts/index.yaml`：

```yaml
layouts:
  - id: shuangpin-hints
    name: 双拼提示表
    author: <作者>
    description: 为内置之外的方案补充双拼键位提示
    tags: ["输入方案"]
    repo: <作者仓库地址>
    license: MIT
    appVersion: "3.0.0"          # 低于此版本的 App 标记为不兼容
    currentVersion: "1.0.0"
    versions:
      - version: "1.0.0"
        date: "2026-09-29"
        changelog: ""
        downloadUrl:
          - url: <zip 的直链>
            sha256: <脚本输出的 sha256>
        size: "<脚本输出的体积>"
```

`requiresSchemes` 可留空：提示表只影响键面显示，不依赖具体输入方案是否已安装。

## 4. 用户侧行为

- **应用**：写入包内文件 → 重载键盘配置；应用另一个布局时，上一次布局释放、本次未复用的文件会被自动清理（避免旧提示表残留）。
- **恢复默认**：删除该布局释放的文件清单并重载；提示表这类独立文件只有在它属于该布局清单里时才会被删。
- 管理入口：设置 → 布局与显示 → **布局插件**；完整浏览（分类/版本/截图/详情）在设置 → 扩展商店 → 布局。
