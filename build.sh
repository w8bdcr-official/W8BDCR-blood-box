#!/bin/bash
# W8BDCR Blood Box v3.0 — manual APK pipeline (single pass, no gradle).
# Stack: native Java WebView shell + JS bridge (ACTION_DIAL, HttpURLConnection,
#        share sheet) around a bundled Bangla HTML UI.
# Donor registry + blood requests are ONLINE and SHARED via Firebase
# Realtime Database (public /donors and /requests nodes, no secret, no
# auth). INTERNET is the only permission.
set -e
P=~/workspace/w8-rebrands/bloodbox
BT=~/workspace/build-tools/bt/android-16
JH=~/workspace/build-tools/jdk-17.0.20.1+1
export PATH=$JH/bin:$PATH
export JAVA_HOME=$JH
# android.jar (API 28) — use the project's own copy if present, else the shared one.
if [ -f "$P/android-28.jar" ]; then AJAR=$P/android-28.jar;
elif [ -f ~/workspace/phantom-guard-app/android-28.jar ]; then AJAR=~/workspace/phantom-guard-app/android-28.jar;
else echo "FATAL: android-28.jar not found — place an Android SDK android.jar (API 28) at $P/android-28.jar"; exit 1; fi
B=$P/build
KS=~/workspace/w8bdcr-call-ai/w8bdcr.keystore
KSPASS=$(grep -oP 'KSPASS="\K[^"]+' ~/workspace/w8bdcr-iptv/build.sh)

rm -rf $B
mkdir -p $B/gen $B/classes $B/dex

echo "== aapt2 compile =="
$BT/aapt2 compile --dir $P/res -o $B/compiled_res.zip
echo "== aapt2 link =="
$BT/aapt2 link -o $B/base.apk -I $AJAR --manifest $P/AndroidManifest.xml \
  --java $B/gen $B/compiled_res.zip -A $P/assets \
  --min-sdk-version 24 --target-sdk-version 28
echo "== javac =="
find $P/src $B/gen -name "*.java" > $B/sources.txt
$JH/bin/javac -source 8 -target 8 -nowarn -encoding UTF-8 -cp $AJAR \
  -d $B/classes @$B/sources.txt > $B/javac.log 2>&1 || true
grep -v "bootstrap\|deprecat\|warning" $B/javac.log | head -30 || true
if grep -q "error:" $B/javac.log; then
  echo "FATAL: javac errors — aborting build"
  exit 1
fi
echo "javac clean"
echo "== d8 =="
$BT/d8 --lib $AJAR --min-api 24 --output $B/dex \
  $(find $B/classes -name "*.class")
echo "== assemble =="
cp $B/base.apk $B/unsigned.apk
(cd $B/dex && zip -q -j $B/unsigned.apk classes.dex)
echo "== zipalign =="
$BT/zipalign -f 4 $B/unsigned.apk $B/aligned.apk
echo "== apksigner =="
$BT/apksigner sign --ks $KS --ks-pass pass:$KSPASS \
  --out $B/W8BDCR-BloodBox-v3.0.apk $B/aligned.apk
echo "== verify =="
$BT/apksigner verify --print-certs $B/W8BDCR-BloodBox-v3.0.apk | head -4
$BT/apksigner verify $B/W8BDCR-BloodBox-v3.0.apk && echo "SIGNATURE OK"
$BT/zipalign -c 4 $B/W8BDCR-BloodBox-v3.0.apk && echo "ALIGN OK"
$BT/aapt2 dump badging $B/W8BDCR-BloodBox-v3.0.apk | grep -E "^package|launchable-activity" | head -4
ls -la $B/W8BDCR-BloodBox-v3.0.apk
echo "BUILD OK"
