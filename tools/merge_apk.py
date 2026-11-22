#!/usr/bin/env python3
"""把启动器合并进游戏 APK，产出一个"游戏 + 启动器"的单体 APK。

用法：
  python3 tools/merge_apk.py \
      --game StardewValley.apk \
      --launcher app-debug.apk \
      --lib libsvtl.so \
      --out SVTL-merged.apk \
      --keystore app/svtl.jks

需要：zipalign、apksigner（Android SDK build-tools 里）。
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from patch_manifest import patch  # noqa: E402


def find_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        bt = os.path.join(sdk, "build-tools")
        if os.path.isdir(bt):
            for ver in sorted(os.listdir(bt), reverse=True):
                p = os.path.join(bt, ver, name)
                if os.path.isfile(p):
                    return p
    p = shutil.which(name)
    if not p:
        raise SystemExit(f"找不到 {name}，请设置 ANDROID_HOME")
    return p


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--game", required=True)
    ap.add_argument("--launcher", required=True)
    ap.add_argument("--lib", default="")
    ap.add_argument("--out", required=True)
    ap.add_argument("--keystore", required=True)
    ap.add_argument("--ks-pass", default="svtl1234")
    ap.add_argument("--key-alias", default="svtl")
    args = ap.parse_args()

    work = tempfile.mkdtemp(prefix="svtl-merge-")
    try:
        # 1) 取出游戏清单并改写
        with zipfile.ZipFile(args.game) as gz:
            manifest = gz.read("AndroidManifest.xml")
        patched = patch(manifest)
        print(f"清单改写完成：{len(manifest)} -> {len(patched)} 字节")

        # 2) 合并 zip
        merged = os.path.join(work, "merged-unsigned.apk")
        with zipfile.ZipFile(args.game) as gz, zipfile.ZipFile(
            merged, "w", zipfile.ZIP_DEFLATED
        ) as out:
            for item in gz.infolist():
                if item.filename == "AndroidManifest.xml":
                    continue
                out.writestr(item, gz.read(item.filename))
            out.writestr("AndroidManifest.xml", patched)

            with zipfile.ZipFile(args.launcher) as lz:
                dexes = sorted(
                    n for n in lz.namelist()
                    if n.startswith("classes") and n.endswith(".dex")
                )
                for i, name in enumerate(dexes, start=2):
                    out.writestr(f"classes{i}.dex", lz.read(name))
            print(f"合并 dex：{len(dexes)} 个")

            if args.lib:
                out.writestr("lib/arm64-v8a/libsvtl.so", open(args.lib, "rb").read())
                print("已放入核心库")

        # 3) 对齐 + 签名
        aligned = os.path.join(work, "aligned.apk")
        subprocess.run(
            [find_tool("zipalign"), "-f", "-p", "4", merged, aligned], check=True
        )
        subprocess.run(
            [
                find_tool("apksigner"), "sign",
                "--ks", args.keystore,
                "--ks-key-alias", args.key_alias,
                "--ks-pass", f"pass:{args.ks_pass}",
                "--key-pass", f"pass:{args.ks_pass}",
                "--out", args.out,
                aligned,
            ],
            check=True,
        )
        print("已签名：", args.out, os.path.getsize(args.out) // 1024 // 1024, "MB")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
