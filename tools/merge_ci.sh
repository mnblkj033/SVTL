#!/usr/bin/env bash
# 在 CI（x86_64）上把启动器合并进游戏 APK：smali + 资源一起合，避免资源 ID 找不到。
# 用法: merge_ci.sh <game.apk> <launcher.apk> <keystore> <out.apk>
set -euo pipefail

GAME="$1"
LAUNCHER="$2"
KEYSTORE="$3"
OUT="$4"

APKTOOL=/tmp/apktool.jar
if [ ! -f "$APKTOOL" ]; then
  curl -fL -o "$APKTOOL" \
    https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar
fi

rm -rf g l
echo "== 反编译游戏 =="
java -jar "$APKTOOL" d -f "$GAME" -o g >/dev/null
echo "== 反编译启动器 =="
java -jar "$APKTOOL" d -f "$LAUNCHER" -o l >/dev/null

echo "== 合并 smali =="
i=2
for d in l/smali l/smali_classes*; do
  [ -d "$d" ] || continue
  cp -r "$d" "g/smali_classes$i"
  i=$((i + 1))
done

echo "== 合并资源（不覆盖同名） =="
cp -rn l/res/. g/res/ 2>/dev/null || true

echo "== 改清单 =="
python3 tools/patch_manifest_text.py g/AndroidManifest.xml

echo "== 回编 =="
java -jar "$APKTOOL" b g -o unsigned.apk >/dev/null

echo "== 对齐 + 签名 =="
BT="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools"
ZIPALIGN="$(ls -d "$BT"/*/zipalign 2>/dev/null | sort -r | head -1)"
APKSIGNER="$(ls -d "$BT"/*/apksigner 2>/dev/null | sort -r | head -1)"
"$ZIPALIGN" -f -p 4 unsigned.apk aligned.apk
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias svtl \
  --ks-pass pass:svtl1234 --key-pass pass:svtl1234 --out "$OUT" aligned.apk

ls -lh "$OUT"
