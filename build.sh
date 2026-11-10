#!/bin/bash
# PulseCashbook APK 构建脚本（aapt2 + javac + d8 + apksigner，无 Gradle）
set -e

ROOT="$(cd "$(dirname "$0")" && pwd -W)"
TC="C:/Users/GAFA2279/WorkBuddy/2026-11-09-12-47-17/android-toolchain"
export JAVA_HOME="$TC/jdk-extract/jdk-17.0.13+11"
export PATH="$JAVA_HOME/bin:$PATH"
ANDROID_JAR="$TC/sdk/platforms/android-35/android.jar"
BT="$TC/sdk/build-tools/35.0.0"
AAPT2="$BT/aapt2.exe"

OUT="$ROOT/build"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAVA="$JAVA_HOME/bin/java.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1/7 aapt2 compile resources =="
"$AAPT2" compile --dir "$ROOT/res" -o "$OUT/compiled/"

echo "== 2/7 aapt2 link =="
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 24 \
  --version-name 2.2.1 \
  --auto-add-overlay \
  "$OUT/compiled"/*.flat

echo "== 3/7 javac =="
SRC_FILES=$(find "$ROOT/src" "$OUT/gen" -name '*.java')
"$JAVAC" --release 11 \
  -J-Duser.language=en -J-Duser.country=US \
  -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  $SRC_FILES

echo "== 4/7 d8 dex =="
CLASS_FILES=$(find "$OUT/classes" -name '*.class')
"$JAVA" -Duser.language=en -Duser.country=US -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$OUT/dex" \
  $CLASS_FILES

echo "== 5/7 add classes.dex to apk =="
OUT_DIR="$OUT" python - <<'PYEOF'
import zipfile, os, shutil
out = os.path.join(os.environ.get("OUT_DIR", "build"), "base.apk")
dex = os.path.join(os.environ.get("OUT_DIR", "build"), "dex", "classes.dex")
final = os.path.join(os.environ.get("OUT_DIR", "build"), "unsigned.apk")
with zipfile.ZipFile(out, "r") as zin, zipfile.ZipFile(final, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, "classes.dex")
print("added classes.dex ->", final)
PYEOF

echo "== 6/7 zipalign =="
"$BT/zipalign.exe" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 7/7 apksigner =="
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$JAVA" -Duser.language=en -Duser.country=US -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/PulseCashbookPro.apk" "$OUT/aligned.apk"

"$JAVA" -Duser.language=en -Duser.country=US -jar "$BT/lib/apksigner.jar" verify \
  --print-certs "$ROOT/PulseCashbookPro.apk"

echo "DONE: $ROOT/PulseCashbookPro.apk"
ls -la "$ROOT/PulseCashbookPro.apk"
