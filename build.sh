#!/usr/bin/env bash
# Baut Soundboard-vX.apk ohne Gradle: aapt2 -> javac -> dx -> jarsigner
set -euo pipefail

# >>> Version hier pflegen: landet in App und Dateiname <<<
VERSION_NAME="0.13.1"
VERSION_CODE=21

cd "$(dirname "$0")"
SDK=${SDK:-/tmp/claude-0/sdk}
AJ=$SDK/ap/android-33/android.jar
OUT=build
rm -rf $OUT && mkdir -p $OUT/gen $OUT/classes

echo "== Ressourcen kompilieren"
$SDK/aapt2 compile --dir res -o $OUT/res.zip

echo "== Manifest + Ressourcen linken"
$SDK/aapt2 link -o $OUT/base.apk -I $AJ \
  --manifest AndroidManifest.xml --java $OUT/gen \
  --min-sdk-version 26 --target-sdk-version 29 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  $OUT/res.zip

echo "== Java kompilieren"
find src $OUT/gen -name '*.java' > $OUT/sources.txt
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath $AJ -classpath $AJ -d $OUT/classes @$OUT/sources.txt

echo "== DEX erzeugen"
java -jar $SDK/dx.jar --dex --min-sdk-version=26 --output=$OUT/classes.dex $OUT/classes

echo "== APK zusammensetzen"
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT && zip -q unsigned.apk classes.dex)

echo "== Signieren (v1, gleicher Schluessel fuer alle Versionen -> Updates ueberinstallierbar)"
cp $OUT/unsigned.apk "$OUT/Soundboard-v$VERSION_NAME.apk"
# Eigener Schluessel: SB_KEYSTORE, SB_STOREPASS, SB_KEYPASS, SB_ALIAS setzen (nicht im Repo!)
: "${SB_KEYSTORE:?SB_KEYSTORE fehlt (Pfad zur .jks-Datei)}"; : "${SB_STOREPASS:?SB_STOREPASS fehlt}"
jarsigner -keystore "$SB_KEYSTORE" -storepass "$SB_STOREPASS" -keypass "${SB_KEYPASS:-$SB_STOREPASS}" \
  -sigalg SHA256withRSA -digestalg SHA-256 "$OUT/Soundboard-v$VERSION_NAME.apk" "${SB_ALIAS:-soundboard}" >/dev/null
jarsigner -verify "$OUT/Soundboard-v$VERSION_NAME.apk" | tail -1
echo "== Fertig: $OUT/Soundboard-v$VERSION_NAME.apk"
