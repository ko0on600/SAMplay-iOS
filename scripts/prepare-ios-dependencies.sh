#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
engine_version=0.1.6
engine_checksum=f556718ec0fd122a61bc5408307b19fcd54397595954c46c6ff8db1e5a179421
engine_root="${NUVIO_ENGINE_ROOT:-${repository_root}/../nuvio-engine}"
engine_framework="${engine_root}/platform/apple/NuvioEngine.xcframework"

if [[ ! -f "${repository_root}/MPVKit/Package.swift" ]]; then
    git -C "${repository_root}" submodule update --init --depth 1 MPVKit
fi


# SAMplay: whisper.cpp for AI listening (iosApp/SamplayNative).
whisper_build=b5907
whisper_checksum=2482ad38f3c8f25a0521d284c9ad3e5838aaa812f57c936f32624b9b7b1bc36c
whisper_target="${repository_root}/iosApp/SamplayNative/whisper.xcframework"
if [[ ! -f "${whisper_target}/Info.plist" ]]; then
    whisper_tmp="$(mktemp -d "${TMPDIR:-/tmp}/samplay-whisper.XXXXXX")"
    curl --fail --location --retry 5 --retry-all-errors --silent --show-error \
        --output "${whisper_tmp}/whisper.zip" \
        "https://github.com/ggml-org/whisper.cpp/releases/download/${whisper_build}/whisper-${whisper_build}-xcframework.zip"
    if [[ "$(shasum -a 256 "${whisper_tmp}/whisper.zip" | awk '{print $1}')" != "${whisper_checksum}" ]]; then
        echo "whisper.cpp package checksum mismatch." >&2
        exit 1
    fi
    unzip -q "${whisper_tmp}/whisper.zip" -d "${whisper_tmp}/x"
    rm -rf "${whisper_target}"
    cp -R "$(find "${whisper_tmp}/x" -type d -name whisper.xcframework -print -quit)" "${whisper_target}"
    rm -rf "${whisper_tmp}"
fi
if [[ -f "${engine_framework}/Info.plist" ]]; then
    exit 0
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-dependencies.XXXXXX")"
trap 'rm -rf "${temporary_directory}"' EXIT

archive="${temporary_directory}/nuvio-engine-apple-${engine_version}.zip"
curl --fail --location --retry 5 --retry-all-errors --silent --show-error \
    --output "${archive}" \
    "https://github.com/NuvioMedia/nuvio-engine/releases/download/v${engine_version}/nuvio-engine-apple-${engine_version}.zip"

actual_checksum="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [[ "${actual_checksum}" != "${engine_checksum}" ]]; then
    echo "Nuvio Engine Apple package checksum mismatch." >&2
    exit 1
fi

extraction_root="${temporary_directory}/extracted"
mkdir -p "${extraction_root}"
unzip -q "${archive}" -d "${extraction_root}"
source_framework="$(find "${extraction_root}" -type d -name NuvioEngine.xcframework -print -quit)"
if [[ -z "${source_framework}" || ! -f "${source_framework}/Info.plist" ]]; then
    echo "Nuvio Engine Apple package does not contain NuvioEngine.xcframework." >&2
    exit 1
fi
if [[ ! -f "${source_framework}/ios-arm64/libCNuvioEngine.a" ]]; then
    echo "Nuvio Engine Apple package does not contain the iOS arm64 library." >&2
    exit 1
fi

mkdir -p "$(dirname "${engine_framework}")"
ditto "${source_framework}" "${engine_framework}"
