#!/bin/bash
# Hyperion 1.0 — pipeline de compilación
set -e
cd "$(dirname "$0")"
export PATH="$HOME/workspace/jdk17/bin:$PATH"

SDK=~/workspace/android-sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34
AP=$PLAT/android.jar

OUT=out
rm -rf $OUT
mkdir -p $OUT

echo "== AAPT2: recursos =="
$BT/aapt2 compile --dir res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/app.apk \
  -I $AP \
  --manifest AndroidManifest.xml \
  --java $OUT/gen \
  --min-sdk-version 26 --target-sdk-version 28 \
  $OUT/res.zip

echo "== JavaC =="
mkdir -p $OUT/classes
find src $OUT/gen -name "*.java" > $OUT/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp $AP \
  -d $OUT/classes \
  @$OUT/sources.txt

echo "== D8 =="
mkdir -p $OUT/dex
$BT/d8 --lib $AP \
  --min-api 26 \
  --output $OUT/dex \
  $(find $OUT/classes -name "*.class")

echo "== APK =="
cp $OUT/app.apk $OUT/unsigned.apk
cd $OUT/dex && zip -q -0 -X ../unsigned.apk classes.dex && cd ../..
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk

echo "== Firma =="
$BT/apksigner sign --ks keystore/hyperion.keystore --ks-pass pass:android \
  --ks-key-alias hyperion --key-pass pass:android \
  --out Hyperion-2.1-unsigned.apk $OUT/aligned.apk

echo "== Verificación =="
$BT/apksigner verify --print-certs Hyperion-2.1-unsigned.apk | head -5
$BT/aapt dump badging Hyperion-2.1-unsigned.apk | head -8

mkdir -p ~/workspace/your_files
cp Hyperion-2.1-unsigned.apk ~/workspace/your_files/Hyperion-2.1.apk
ls -lh ~/workspace/your_files/Hyperion-2.1.apk
echo "LISTO"
