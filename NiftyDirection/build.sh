#!/bin/bash
# Offline build: ecj -> d8 -> aapt2 -> align -> apksigner. Usage: ./build.sh
set -e
: "${KS_PASS:?set KS_PASS to the keystore password}"
export KS_PASS
cd "$(dirname "$0")"
# Tool paths can be overridden from the environment (e.g. an Android SDK's build-tools + platforms/android-34).
T=${ANDROID_TOOLS:-/root/androidtools/node_modules/@drxiaozhi/minapk/tools}
ANDROID_JAR=${ANDROID_JAR:-$T/android.jar}
AAPT2=${AAPT2:-/root/androidtools/node_modules/aaptjs3/bin/x64/linux/aapt2}
STUB=${LAMBDA_STUBS:-/root/androidtools/lambda-stubs.jar}
D8_JAR=${D8_JAR:-$T/d8.jar}
APKSIGNER_JAR=${APKSIGNER_JAR:-$T/apksigner.jar}
ECJ_JAR=${ECJ_JAR:-$T/ecj-3.45.0.jar}
KEYSTORE=${KEYSTORE:-keystore/release.jks}
VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
B=build; rm -rf $B; mkdir -p $B/cls $B/dex $B/res $B/gen
sed -i "s/VERSION = \"[^\"]*\"/VERSION = \"$VER\"/" src/com/krish/niftydirection/BuildInfo.java
sed -i "s/<string name=\"app_name\">[^<]*</<string name=\"app_name\">Nifty Direction $VER</" res/values/strings.xml
$AAPT2 compile --dir res -o $B/res/res.zip
$AAPT2 link -I $ANDROID_JAR --manifest AndroidManifest.xml --rename-manifest-package com.krish.niftydirection.pure -o $B/unsigned.apk $B/res/res.zip --java $B/gen --min-sdk-version 26 --target-sdk-version 34 --version-name "$VER"
if [ -f "$ECJ_JAR" ]; then
  java -jar $ECJ_JAR -1.8 -nowarn -encoding UTF-8 -bootclasspath $ANDROID_JAR -cp $STUB -d $B/cls $(find src $B/gen -name "*.java") 2>&1 | grep -v JAVA_TOOL || true
else   # no ecj: javac targeting Java 8 against android.jar
  javac -source 8 -target 8 -nowarn -encoding UTF-8 -bootclasspath $ANDROID_JAR -cp $STUB -d $B/cls $(find src $B/gen -name "*.java") 2>&1 | grep -v -E "JAVA_TOOL|warning|^Note|^1 warning" || true
fi
[ -n "$(find $B/cls -name MainActivity.class)" ] || { echo "compile failed"; exit 1; }
java -cp $D8_JAR com.android.tools.r8.D8 --release --min-api 26 --lib $ANDROID_JAR --output $B/dex $(find $B/cls -name "*.class") 2>&1 | grep -v JAVA_TOOL || true
(cd $B/dex && zip -q ../unsigned.apk classes.dex)
python3 tools/zipalign.py $B/unsigned.apk $B/aligned.apk
OUT="NiftyDirection_${VER// /_}_$(TZ=Asia/Kolkata date +%Y%m%d_%H%M).apk"
java -jar $APKSIGNER_JAR sign --ks $KEYSTORE --ks-pass env:KS_PASS --key-pass env:KS_PASS --out $B/$OUT $B/aligned.apk 2>&1 | grep -v JAVA_TOOL || true
java -jar $APKSIGNER_JAR verify $B/$OUT 2>&1 | grep -v JAVA_TOOL || true
echo "BUILT $B/$OUT"
