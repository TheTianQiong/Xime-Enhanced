#!/usr/bin/env python3
"""把布局包目录打包成可上架布局市场的 zip，并输出 sha256 与索引条目。

用法：
    python3 scripts/pack-layout.py --dir <布局目录> --out build/layout-release \
        --id shuangpin-hints --name "双拼提示表" --version 1.0.0 --author 天穹 \
        --date 2026-09-29

规则与产物：
- 打包前按宿主实际放行规则做**预检**：只允许根级 xime.custom.yaml、
  根级 shuangpin_hints.custom.yaml、themes/ 与 fonts/ 下的文件；至少要有一个
  配置补丁（否则宿主会判为无效包）。预检名单与
  app/src/main/java/com/kingzcheung/xime/settings/LayoutPackagePolicy.kt 必须保持一致——
  宿主改规则时这里要同步，否则会出现「本地打包成功、装到机器上被拒」。
- zip 内条目时间戳固定取自 --date（缺省用当天），因此相同输入 + 相同日期打出的
  sha256 稳定可复现——索引里的校验值不会每次打包都变。
- 产物：<out>/<id>-<version>.zip；stdout 打印 sha256、体积与可粘贴的
  layouts/index.yaml 条目。

格式说明见 docs/layout-package-format.md。
"""

import argparse
import hashlib
import os
import sys
import zipfile
from datetime import date as date_cls

# 诊断信息含中文：Windows 默认按本地代码页（GBK）输出，重定向到日志文件后
# 就会变成乱码。统一成 UTF-8，与仓库内其它文本文件一致。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except Exception:
        pass

XIME_CUSTOM = "xime.custom.yaml"
SHUANGPIN_HINTS = "shuangpin_hints.custom.yaml"
CONFIG_PATCHES = (XIME_CUSTOM, SHUANGPIN_HINTS)
RESOURCE_PREFIXES = ("themes/", "fonts/")

# 与 LayoutPackagePolicy 一致的路径形态约束
MAX_PATH_LENGTH = 256


def rel_files(root):
    """列出目录下所有文件（相对路径，正斜杠，跳过点文件与 __MACOSX）。"""
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for name in filenames:
            if name.startswith("."):
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), root).replace(os.sep, "/")
            out.append(rel)
    return sorted(out)


def is_allowed(rel):
    if not rel or len(rel) > MAX_PATH_LENGTH:
        return False
    if rel.startswith("/") or "\\" in rel:
        return False
    if any(seg in ("", ".", "..") for seg in rel.split("/")):
        return False
    return rel in CONFIG_PATCHES or rel.startswith(RESOURCE_PREFIXES)


def is_config_patch(rel):
    return rel in CONFIG_PATCHES


def preflight(root):
    """返回 (错误列表, 通过白名单的文件列表)。"""
    errors = []
    if not os.path.isdir(root):
        return ["布局目录不存在：%s" % root], []

    files = rel_files(root)
    if not files:
        return ["布局目录为空：%s" % root], []

    allowed = [f for f in files if is_allowed(f)]
    rejected = [f for f in files if not is_allowed(f)]
    if rejected:
        errors.append("以下文件不在宿主放行名单内，会被静默丢弃（请移出或改名）：\n  " + "\n  ".join(rejected))
    if not any(is_config_patch(f) for f in allowed):
        errors.append(
            "缺少配置补丁：至少要有根级 %s 或 %s，否则宿主判为无效包" % CONFIG_PATCHES
        )

    # 轻量内容体检：宿主解析失败会静默回退内置表，这里提前给出提示
    if SHUANGPIN_HINTS in allowed:
        text = open(os.path.join(root, SHUANGPIN_HINTS), encoding="utf-8").read()
        if "schemes:" not in text:
            errors.append("%s 里没有 schemes: 段，宿主会回退内置表" % SHUANGPIN_HINTS)

    return errors, allowed


def pack(root, files, out_path, stamp):
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for rel in files:
            info = zipfile.ZipInfo(filename=rel, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(os.path.join(root, rel), "rb") as src:
                zf.writestr(info, src.read())


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def human_size(num_bytes):
    if num_bytes >= 1024 * 1024:
        return "%.1f MB" % (num_bytes / 1024.0 / 1024.0)
    return "%.1f KB" % (num_bytes / 1024.0)


def index_entry(args, sha, size_text):
    return """layouts:
  - id: {id}
    name: {name}
    author: {author}
    description: "{desc}"
    tags: {tags}
    repo: {repo}
    license: {license}
    appVersion: "{app_version}"
    currentVersion: "{version}"
    versions:
      - version: "{version}"
        date: "{date}"
        changelog: "{changelog}"
        downloadUrl:
          - url: {url}
            sha256: {sha}
        size: "{size}"
""".format(
        id=args.id,
        name=args.name,
        author=args.author,
        desc=args.description,
        tags="[%s]" % ", ".join('"%s"' % t for t in args.tags) if args.tags else "[]",
        repo=args.repo,
        license=args.license,
        app_version=args.app_version,
        version=args.version,
        date=args.date,
        changelog=args.changelog,
        url=args.url,
        sha=sha,
        size=size_text,
    )


def main():
    parser = argparse.ArgumentParser(description="打包布局包并输出索引条目")
    parser.add_argument("--dir", required=True, help="布局包源目录")
    parser.add_argument("--out", default="build/layout-release", help="产物目录")
    parser.add_argument("--id", required=True, help="布局 id（索引里的主键）")
    parser.add_argument("--name", required=True, help="展示名")
    parser.add_argument("--version", required=True, help="版本号")
    parser.add_argument("--author", default="", help="作者")
    parser.add_argument("--description", default="", help="描述")
    parser.add_argument("--tags", nargs="*", default=[], help="分类标签")
    parser.add_argument("--repo", default="", help="作者仓库地址")
    parser.add_argument("--license", default="", help="许可证")
    parser.add_argument("--app-version", default="3.0.0", help="最低 App 版本")
    parser.add_argument("--changelog", default="", help="本版更新说明")
    parser.add_argument("--url", default="<上传后的 zip 直链>", help="zip 直链（可后填）")
    parser.add_argument("--date", default=date_cls.today().isoformat(),
                        help="版本日期（同时决定 zip 内条目时间戳，影响 sha256）")
    args = parser.parse_args()

    errors, allowed = preflight(args.dir)
    if errors:
        print("预检未通过：", file=sys.stderr)
        for err in errors:
            print("  - " + err, file=sys.stderr)
        return 1

    year, month, day = (int(x) for x in args.date.split("-"))
    stamp = (year, month, day, 0, 0, 0)

    out_path = os.path.join(args.out, "%s-%s.zip" % (args.id, args.version))
    pack(args.dir, allowed, out_path, stamp)

    size_bytes = os.path.getsize(out_path)
    size_text = human_size(size_bytes)
    sha = sha256_of(out_path)

    print("已打包：%s（%s，%d 个文件）" % (out_path, size_text, len(allowed)))
    for rel in allowed:
        print("  + " + rel)
    print("\nsha256: %s" % sha)
    print("\n把下面这段并入 xime-index 的 layouts/index.yaml：\n")
    print(index_entry(args, sha, size_text))
    return 0


if __name__ == "__main__":
    sys.exit(main())
