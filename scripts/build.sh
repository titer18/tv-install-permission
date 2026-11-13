#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ANDROID_JAR="${ANDROID_JAR:-$ROOT/sdk/android-13/android.jar}"
R8_JAR="${R8_JAR:-$ROOT/sdk/r8.jar}"
KEYSTORE="${KEYSTORE:-$ROOT/build/debug.keystore}"
STORE_PASS="${STORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-hz}"
OUT_APK="$ROOT/dist/adbperm.apk"

for cmd in java javac apktool keytool jarsigner zip; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "缺少命令：$cmd" >&2
    exit 1
  fi
done

if [ ! -f "$ANDROID_JAR" ]; then
  echo "找不到 android.jar：$ANDROID_JAR" >&2
  echo "可以设置 ANDROID_JAR 环境变量，或参考 README.md 准备 SDK 文件。" >&2
  exit 1
fi

if [ ! -f "$R8_JAR" ]; then
  echo "找不到 r8.jar：$R8_JAR" >&2
  echo "可以设置 R8_JAR 环境变量，或参考 README.md 下载 R8。" >&2
  exit 1
fi

mkdir -p build/classes build/dex dist
find build/classes -type f -delete 2>/dev/null || true
find build/dex -type f -delete 2>/dev/null || true

echo "[1/6] 编译 Java 源码"
javac -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -d build/classes \
  src/com/hz/adbperm/*.java

echo "[2/6] 生成 classes.dex"
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 21 \
  --lib "$ANDROID_JAR" \
  --output build/dex \
  $(find build/classes -name '*.class')

echo "[3/6] 使用 apktool 打包资源"
apktool b . -o build/raw.apk

echo "[4/6] 写入 classes.dex"
cp build/raw.apk build/final.apk
(cd build/dex && zip -q ../final.apk classes.dex)

if [ ! -f "$KEYSTORE" ]; then
  echo "[5/6] 生成本地 debug 签名"
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" \
    >/dev/null 2>&1
else
  echo "[5/6] 使用已有签名：$KEYSTORE"
fi

jarsigner \
  -keystore "$KEYSTORE" \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -sigalg SHA256withRSA \
  -digestalg SHA-256 \
  build/final.apk \
  "$KEY_ALIAS"

echo "[6/6] 输出安装包"
cp build/final.apk "$OUT_APK"
echo "完成：$OUT_APK"
