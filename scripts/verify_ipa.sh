#!/usr/bin/env bash
# Checks an IPA is a proper device build: Payload/ at the root, names, icons, arm64 iphoneos,
# universal (iPhone + iPad), signatures, no junk. Exits non-zero on a hard failure.
set -euo pipefail
IPA="$1"; T=$(mktemp -d); unzip -q "$IPA" -d "$T"
echo "== top level"; ls "$T"
[ "$(ls "$T")" = "Payload" ] || { echo "FAIL: IPA root must hold only Payload/"; exit 1; }
APP=$(ls -d "$T"/Payload/*.app); echo "app: ${APP#$T/}"
P="$APP/Info.plist"
for k in CFBundleName CFBundleDisplayName CFBundleIdentifier CFBundleExecutable MinimumOSVersion DTPlatformName CFBundleShortVersionString CFBundleVersion; do
  echo "$k = $(/usr/libexec/PlistBuddy -c "Print :$k" "$P" 2>/dev/null || echo '<missing>')"
done
FAM=$(/usr/libexec/PlistBuddy -c "Print :UIDeviceFamily" "$P"); echo "UIDeviceFamily = $(echo $FAM | tr -d '\n')"
echo "$FAM" | grep -q " 1" && echo "$FAM" | grep -q " 2" || { echo "FAIL: not universal (needs iPhone 1 + iPad 2)"; exit 1; }
echo "iPad orientations: $(/usr/libexec/PlistBuddy -c 'Print :UISupportedInterfaceOrientations~ipad' "$P" 2>/dev/null | tr -d '\n' | tr -s ' ')"
/usr/libexec/PlistBuddy -c "Print :CFBundleIcons:CFBundlePrimaryIcon" "$P" || true
test -f "$APP/Assets.car" && echo "Assets.car present" || { echo "FAIL: no Assets.car"; exit 1; }
[ "$(/usr/libexec/PlistBuddy -c 'Print :DTPlatformName' "$P")" = "iphoneos" ] || { echo "FAIL: not iphoneos"; exit 1; }
EXE="$APP/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$P")"
echo "== binaries"
while IFS= read -r -d '' b; do
  file "$b" | grep -q Mach-O || continue
  archs=$(lipo -archs "$b"); echo "${b#$APP/}: $archs"
  [ "$archs" = "arm64" ] || { echo "FAIL: $b has $archs"; exit 1; }
done < <(find "$APP" -type f -perm -u+x -print0)
echo "== signature"; codesign -dv "$APP" 2>&1 | grep -E "Identifier|Signature|TeamIdentifier" || true
codesign --verify --deep --strict "$APP" && echo "signature OK"
echo "== junk"; J=$(find "$APP" \( -name "*.swiftmodule" -o -name "*.xcframework" -o -name "embedded.mobileprovision" -o -name "Headers" \) -print); echo "${J:-none}"
[ -z "$J" ] || { echo "FAIL: junk inside app"; exit 1; }
echo "== plugins"; ls "$APP/PlugIns" 2>/dev/null || echo none
echo "== frameworks"; ls "$APP/Frameworks" 2>/dev/null || echo none
echo "== size"; du -sh "$IPA"
rm -rf "$T"
echo "VERIFY OK"
