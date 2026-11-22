#!/usr/bin/env bash
# 一键构建修复版 APK：反编译 → 打 smali 补丁 → 编译补丁类 → d8 → 注入 classes2.dex → 对齐 → 签名
#
# 需要：
#   - JDK 8（javac，编译 target 1.8）         export JAVA8_HOME=/path/to/jdk8
#   - JDK 11+（运行 apktool / d8 / apksigner）export JAVA_HOME=/path/to/jdk11+
#   - Android build-tools（需含 d8.jar / zipalign / lib/apksigner.jar）
#   - apktool 2.x                             export APKTOOL_JAR=/path/to/apktool.jar
#   - python3（打包 dex 用）
#   - 原始 v1.0.3 APK                          export SRC_APK=/path/to/ACAC_API19_v1.0.3.apk
#
# 用法：
#   SRC_APK=~/ACAC_API19_v1.0.3.apk APKTOOL_JAR=~/apktool.jar BUILD_TOOLS=~/build-tools/34.0.0 \
#       bash scripts/build.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

: "${SRC_APK:?请设置 SRC_APK 指向原始 ACAC_API19_v1.0.3.apk}"
: "${APKTOOL_JAR:?请设置 APKTOOL_JAR 指向 apktool_2.x.jar}"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC="${JAVA8_HOME:+$JAVA8_HOME/bin/}javac"
PY="${PYTHON:-python3}"

if [ -z "${BUILD_TOOLS:-}" ]; then
    BUILD_TOOLS=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/* 2>/dev/null | sort -V | tail -1)
fi
[ -n "${BUILD_TOOLS:-}" ] || { echo "找不到 build-tools，请设置 BUILD_TOOLS"; exit 1; }

VERSION_CODE="${VERSION_CODE:-6}"
VERSION_NAME="${VERSION_NAME:-1.0.4-fix}"
KEYSTORE="${KEYSTORE:-$ROOT/build/acac.keystore}"
KS_PASS="${KS_PASS:-acac123}"
KS_ALIAS="${KS_ALIAS:-acac}"

WORK="$ROOT/build"
rm -rf "$WORK"
mkdir -p "$WORK"

echo "==> 1/8 反编译 $SRC_APK"
"$JAVA" -jar "$APKTOOL_JAR" d -f -o "$WORK/decoded" "$SRC_APK" >/dev/null

echo "==> 2/8 打 smali 补丁（NetworkManager 两个方法转发到 Patch）"
"$PY" scripts/patch_smali.py "$WORK/decoded"

echo "==> 3/8 写入版本号 $VERSION_NAME ($VERSION_CODE)"
"$PY" - "$WORK/decoded" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import sys, re
d, code, name = sys.argv[1], sys.argv[2], sys.argv[3]
p = d + "/apktool.yml"
s = open(p, encoding="utf-8").read()
s = re.sub(r"(\n\s*versionCode:\s*)\S+", r"\g<1>" + code, s, count=1)
s = re.sub(r"(\n\s*versionName:\s*)\S+", r"\g<1>" + name, s, count=1)
open(p, "w", encoding="utf-8", newline="\n").write(s)

p = d + "/AndroidManifest.xml"
s = open(p, encoding="utf-8").read()
s = re.sub(r'android:versionCode="[^"]*"', 'android:versionCode="%s"' % code, s, count=1)
s = re.sub(r'android:versionName="[^"]*"', 'android:versionName="%s"' % name, s, count=1)
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("    versionCode=%s versionName=%s" % (code, name))
PY

echo "==> 4/8 回编译 APK"
"$JAVA" -jar "$APKTOOL_JAR" b "$WORK/decoded" -o "$WORK/built.apk" >/dev/null

echo "==> 5/8 编译补丁类（javac 1.8 + 桩代码）"
mkdir -p "$WORK/classes" "$WORK/dex"
# shellcheck disable=SC2046
"$JAVAC" -source 1.8 -target 1.8 -encoding UTF-8 -nowarn \
    -d "$WORK/classes" $(find patch/stub patch/src -name '*.java')

echo "==> 6/8 d8 → classes2.dex (min-api 19)"
"$JAVA" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 19 --output "$WORK/dex" \
    $(find "$WORK/classes/com/acac/fix" -name '*.class')

echo "==> 7/8 注入 classes2.dex（保留 apktool 生成的 classes.dex）"
"$PY" - "$WORK" <<'PY'
import sys, os, zipfile
w = sys.argv[1]
dex = open(os.path.join(w, "dex", "classes.dex"), "rb").read()
src = zipfile.ZipFile(os.path.join(w, "built.apk"), "r")
dst = zipfile.ZipFile(os.path.join(w, "built_patch.apk"), "w", zipfile.ZIP_DEFLATED)
for it in src.infolist():
    zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = it.external_attr
    dst.writestr(zi, src.read(it.filename))
zi = zipfile.ZipInfo("classes2.dex")
zi.compress_type = zipfile.ZIP_DEFLATED
dst.writestr(zi, dex)
dst.close(); src.close()
print("    classes2.dex = %d bytes" % len(dex))
PY

echo "==> 8/8 对齐 + 签名"
if [ ! -f "$KEYSTORE" ]; then
    echo "    生成自签证书 $KEYSTORE（以后升级必须用同一份）"
    "${JAVA8_HOME:+$JAVA8_HOME/bin/}keytool" -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=ACAC Fix, OU=Home, O=ACAC, L=CN, C=CN" >/dev/null 2>&1
fi

"$BUILD_TOOLS/zipalign" -f -p 4 "$WORK/built_patch.apk" "$WORK/aligned.apk"
mkdir -p "$ROOT/dist"
OUT="${OUT:-$ROOT/dist/ACAC_API19_${VERSION_NAME}.apk}"
"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --min-sdk-version 19 \
    --out "$OUT" "$WORK/aligned.apk"
"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" verify "$OUT"

echo
echo "完成：$OUT ($(wc -c < "$OUT") bytes)"
echo "安装：adb install -r \"$OUT\""
echo "注意：与官方版签名不同，覆盖安装会失败，需先 adb uninstall com.xx.afaf"
