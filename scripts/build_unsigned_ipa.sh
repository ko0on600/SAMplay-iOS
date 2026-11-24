#!/usr/bin/env bash
# Builds SAMplay.ipa for real iPhones and iPads (arm64, iphoneos, universal) from the Kotlin
# Multiplatform + SwiftUI project, then ad-hoc signs it ("-", no team, no entitlements) so on-device
# signers (ESign, Scarlet, Feather…), Sideloadly or AltStore can re-sign it with Sami's certificate.
# Same packaging as the KoonPlay iOS IPA that installed fine. Needs macOS + Xcode + JDK 21.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd -P)"
VERSION="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' iosApp/Configuration/Version.xcconfig | head -n1)"
CONFIG="${IOS_CONFIGURATION:-Release}"
# Kotlin/Native's Xcode integration reads CONFIGURATION first and never falls back to
# KOTLIN_FRAMEWORK_BUILD_TYPE. A Release xcodebuild would therefore re-link a Release
# framework from scratch (~50–90 min) even if we just linked Debug. Match the Xcode
# configuration to the pre-linked Kotlin build type.
if [ "${KOTLIN_FRAMEWORK_BUILD_TYPE:-}" = "debug" ]; then
  CONFIG=Debug
fi
./scripts/prepare-ios-dependencies.sh
mkdir -p build
DD="$ROOT/build/dd"
set +e
env NUVIO_IOS_DISTRIBUTION=full \
  "ORG_GRADLE_PROJECT_org.gradle.jvmargs=${NUVIO_GRADLE_JVMARGS:--Xmx4608M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=768M}" \
  "ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS:--Xmx4608M}" \
  xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration "$CONFIG" \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath "$DD" \
  -jobs "$(sysctl -n hw.ncpu 2>/dev/null || echo 4)" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=NO \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" CODE_SIGN_ENTITLEMENTS="" \
  DEVELOPMENT_TEAM="" PROVISIONING_PROFILE_SPECIFIER="" \
  build > build/xcodebuild.log 2>&1
rc=$?
set -e
# Keep the cinterop klibs (lets a Linux box type-check iosMain without a Mac).
mkdir -p build/cinterop
find composeApp/build -path "*cinterop*" -maxdepth 9 > build/cinterop/listing.txt 2>/dev/null || true
if [ -d composeApp/build/classes/kotlin/iosArm64/main/cinterop ]; then
  cp -R composeApp/build/classes/kotlin/iosArm64/main/cinterop build/cinterop/iosArm64 || true
fi
if [ $rc -ne 0 ]; then
  echo "::group::compile errors"
  grep -E "error:|: error|^e: |FAILURE|What went wrong" -A3 build/xcodebuild.log | head -300 || true
  echo "::endgroup::"
  tail -80 build/xcodebuild.log
  exit 1
fi
SRC="$(ls -d "$DD/Build/Products/$CONFIG-iphoneos/"*.app | head -n1)"
test -d "$SRC"
NAME="$(basename "$SRC")"
rm -rf build/ipa && mkdir -p build/ipa/Payload
ditto "$SRC" "build/ipa/Payload/$NAME"
APP="build/ipa/Payload/$NAME"
# Drop anything that is not part of an installable app.
find "$APP" \( -name "*.swiftmodule" -o -name "*.xcframework" -o -name "*.dSYM" -o -name "Headers" -o -name "PrivateHeaders" -o -name "Modules" -o -name "_CodeSignature" -o -name "embedded.mobileprovision" -o -name ".DS_Store" -o -name "*.swiftdoc" -o -name "*.abi.json" \) -prune -exec rm -rf {} +
# Device slices only (a simulator slice in a framework breaks signing/installation).
while IFS= read -r -d '' bin; do
  file "$bin" | grep -q "Mach-O" || continue
  for a in $(lipo -archs "$bin" 2>/dev/null); do
    if [ "$a" != "arm64" ]; then lipo -remove "$a" "$bin" -output "$bin.thin" && mv "$bin.thin" "$bin"; fi
  done
done < <(find "$APP" -type f -perm -u+x -print0)
# Ad-hoc sign inside-out: nested frameworks/dylibs, then extensions, then the app.
sign() { codesign --force --sign - --timestamp=none --generate-entitlement-der "$1"; }
find "$APP" -depth \( -name "*.framework" -o -name "*.dylib" \) -print0 | while IFS= read -r -d '' fw; do sign "$fw"; done
for ext in "$APP"/PlugIns/*.appex; do [ -e "$ext" ] && sign "$ext"; done
sign "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"
OUT="build/SAMplay-iOS-$VERSION-unsigned.ipa"
(cd build/ipa && zip -qry -X "../$(basename "$OUT")" Payload)
unzip -tq "$OUT"
./scripts/verify_ipa.sh "$OUT" | tee build/verify.txt
echo "Created $OUT"
