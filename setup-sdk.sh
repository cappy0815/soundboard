#!/usr/bin/env bash
# Holt die Build-Werkzeuge nach /tmp/claude-0/sdk (nur GitHub per git noetig):
#  - android.jar (API 33) aus Sable/android-platforms
#  - aapt2 (Linux) aus dem Apktool-Repo
#  - dx aus AOSP dalvik (android10-release), mit javac gebaut
set -euo pipefail
SDK=/tmp/claude-0/sdk; mkdir -p $SDK && cd $SDK
[ -f ap/android-33/android.jar ] || { git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms ap && (cd ap && git sparse-checkout set android-33); }
[ -x aapt2 ] || { git clone -q --depth 1 --filter=blob:none --sparse https://github.com/iBotPeaches/Apktool apktool && (cd apktool && git sparse-checkout set --no-cone '/brut.apktool/apktool-lib/src/main/resources/prebuilt/linux/*') && cp apktool/brut.apktool/apktool-lib/src/main/resources/prebuilt/linux/aapt2 . && chmod +x aapt2; }
if [ ! -f dx.jar ]; then
  git clone -q --depth 1 -b android10-release --filter=blob:none --sparse https://github.com/aosp-mirror/platform_dalvik dalvik && (cd dalvik && git sparse-checkout set dx/src)
  mkdir -p dxbuild && find dalvik/dx/src -name '*.java' > dxsrc.txt && javac -nowarn -encoding UTF-8 -d dxbuild @dxsrc.txt
  printf 'Main-Class: com.android.dx.command.Main\n' > m.txt && (cd dxbuild && jar cfm ../dx.jar ../m.txt .)
fi
echo "SDK bereit in $SDK"
