#!/usr/bin/env bash
# Publication-only recovery. Never source the builder: its setup owns cleanup.
set -euo pipefail

fail() { printf 'publish-retained-tensor-g3: %s\n' "$*" >&2; exit 1; }
usage() {
  printf '%s\n' 'Usage: bash publish-retained-tensor-g3.sh \
  --retained-root DIR --build-id ORIGINAL_ID --version-code ORIGINAL_N \
  --expected-apk-sha256 SHA256 \
  --tensor-g3-aar FILE --expected-aar-sha256 SHA256 \
  --expected-aar-receipt-sha256 SHA256 \
  --sdx-llm-sdk DIR --expected-aot-receipt-sha256 SHA256 \
  --android-sdk DIR --android-ndk DIR --output DIR --staging-root DIR
All arguments are required; output/staging directories must already exist.
Publishes a candidate only. No build, source refresh, pruning, or stable promotion.'
}

# No environment defaults: every identity/artifact/layout selection is explicit.
APP_BUILD_ROOT= APK_BUILD_ID= APK_VERSION_CODE= EXPECTED_APK_SHA256=
TENSOR_G3_AAR= TENSOR_G3_SOURCE_SHA256= TENSOR_G3_PROVENANCE_SHA256=
SDX_LLM_SDK= SDX_AOT_PROVENANCE_SHA256= ANDROID_SDK= ANDROID_NDK_ARG=
OUTPUT_DIR= APK_STAGING_ROOT=
declare -A seen=()
while (( $# )); do
  [[ "$1" != --help && "$1" != -h ]] || { usage; exit 0; }
  [[ $# -ge 2 && -n "$2" ]] || fail "missing value for $1"
  [[ ! -v 'seen[$1]' ]] || fail "duplicate option: $1"
  seen["$1"]=1
  case "$1" in
    --retained-root) APP_BUILD_ROOT=$2 ;;
    --build-id) APK_BUILD_ID=$2 ;;
    --version-code) APK_VERSION_CODE=$2 ;;
    --expected-apk-sha256) EXPECTED_APK_SHA256=$2 ;;
    --tensor-g3-aar) TENSOR_G3_AAR=$2 ;;
    --expected-aar-sha256) TENSOR_G3_SOURCE_SHA256=$2 ;;
    --expected-aar-receipt-sha256) TENSOR_G3_PROVENANCE_SHA256=$2 ;;
    --sdx-llm-sdk) SDX_LLM_SDK=$2 ;;
    --expected-aot-receipt-sha256) SDX_AOT_PROVENANCE_SHA256=$2 ;;
    --android-sdk) ANDROID_SDK=$2 ;;
    --android-ndk) ANDROID_NDK_ARG=$2 ;;
    --output) OUTPUT_DIR=$2 ;;
    --staging-root) APK_STAGING_ROOT=$2 ;;
    *) fail "unknown option: $1" ;;
  esac
  shift 2
done
[[ ${#seen[@]} -eq 13 ]] || { usage >&2; fail 'all explicit arguments are required'; }
[[ "$APK_BUILD_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || fail 'invalid original build ID'
[[ "$APK_VERSION_CODE" =~ ^[1-9][0-9]{0,9}$ ]] &&
  (( APK_VERSION_CODE <= 2100000000 )) || fail 'invalid original version code'

sha256_file() { sha256sum "$1" | cut -d ' ' -f 1; }
# Freeze canonical paths. Retained files/parents cannot be symlinks; the AOT
# SDK's explicit current alias is resolved once, then only its frozen target is used.
safe_path() {
  local path="$1" real lexical
  [[ -n "$path" && "$path" != *$'\n'* && "$path" != *$'\r'* ]] || fail 'invalid path'
  real="$(realpath -e -- "$path")" || fail "missing path: $path"
  lexical="$(realpath -ms -- "$path")" || return 1
  [[ "$real" == "$lexical" ]] || fail "symlink path is not allowed: $path"
  printf '%s\n' "$real"
}
require_hash() {
  local file="$1" expected="$2" actual
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fail "invalid expected SHA-256 for $file"
  [[ -f "$file" && -s "$file" ]] || fail "missing regular input: $file"
  actual="$(sha256_file "$file")" || fail "cannot hash $file"
  [[ "$actual" == "$expected" ]] || fail "expected SHA-256 mismatch: $file"
}
receipt_field() {
  local receipt="$1" field="$2" expected="$3" line count=0 value=
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == "$field="* ]]; then
      count=$((count + 1)); value="${line#*=}"
    fi
  done <"$receipt"
  [[ "$count" == 1 && "$value" == "$expected" ]] || fail "receipt mismatch: $receipt field=$field"
}
APP_BUILD_ROOT="$(safe_path "$APP_BUILD_ROOT")"
[[ -d "$APP_BUILD_ROOT" && "${APP_BUILD_ROOT##*/}" == ".kompile-android-app-build.${APK_BUILD_ID}."?????? ]] ||
  fail 'retained root does not match the explicit original build ID'
TENSOR_G3_AAR="$(safe_path "$TENSOR_G3_AAR")"
TENSOR_G3_FULL_RECEIPT="$(safe_path "$TENSOR_G3_AAR.build-receipt")"
[[ "$SDX_LLM_SDK" != *$'\n'* && "$SDX_LLM_SDK" != *$'\r'* ]] || fail 'invalid SDK path'
SDX_LLM_SDK="$(realpath -e -- "$SDX_LLM_SDK")"
SDX_AOT_RECEIPT="$(safe_path "$SDX_LLM_SDK/metadata/build-receipt")"
ANDROID_SDK="$(safe_path "$ANDROID_SDK")"
ANDROID_NDK_ARG="$(safe_path "$ANDROID_NDK_ARG")"
OUTPUT_DIR="$(safe_path "$OUTPUT_DIR")"
APK_STAGING_ROOT="$(safe_path "$APK_STAGING_ROOT")"
for directory in "$SDX_LLM_SDK" "$ANDROID_SDK" "$ANDROID_NDK_ARG" "$OUTPUT_DIR" "$APK_STAGING_ROOT"; do
  [[ -d "$directory" ]] || fail "not a directory: $directory"
done
[[ -w "$OUTPUT_DIR" && -w "$APK_STAGING_ROOT" ]] || fail 'output/staging must be writable'
# Reject both containment directions; a cleanup/output root must never alias input.
for destination in "$OUTPUT_DIR" "$APK_STAGING_ROOT"; do
  for input in "$APP_BUILD_ROOT" "$SDX_LLM_SDK" "${TENSOR_G3_AAR%/*}" "$ANDROID_SDK" "$ANDROID_NDK_ARG"; do
    [[ "$destination/" != "$input/"* && "$input/" != "$destination/"* ]] || fail "input/output overlap: $destination and $input"
  done
done
[[ "$OUTPUT_DIR/" != "$APK_STAGING_ROOT/"* && "$APK_STAGING_ROOT/" != "$OUTPUT_DIR/"* ]] || fail 'output/staging overlap'
APP_APK="$(safe_path "$APP_BUILD_ROOT/outputs/apk/tensorG3/debug/app-tensorG3-debug.apk")"
TEST_APK="$(safe_path "$APP_BUILD_ROOT/outputs/apk/androidTest/tensorG3/debug/app-tensorG3-debug-androidTest.apk")"
NORMALIZED_AAR="$(safe_path "$APP_BUILD_ROOT/sdx-normalized-aar/tensor-g3/sdx-runtime-tensor-g3.aar")"
require_hash "$APP_APK" "$EXPECTED_APK_SHA256"
require_hash "$TENSOR_G3_AAR" "$TENSOR_G3_SOURCE_SHA256"
require_hash "$NORMALIZED_AAR" "$TENSOR_G3_SOURCE_SHA256"
require_hash "$TENSOR_G3_FULL_RECEIPT" "$TENSOR_G3_PROVENANCE_SHA256"
require_hash "$SDX_AOT_RECEIPT" "$SDX_AOT_PROVENANCE_SHA256"
[[ -f "$TEST_APK" && -s "$TEST_APK" ]] || fail 'missing instrumentation APK'
unzip -tq "$TEST_APK" >/dev/null || fail 'instrumentation APK ZIP failed'
receipt_field "$TENSOR_G3_FULL_RECEIPT" format 3
receipt_field "$TENSOR_G3_FULL_RECEIPT" stage full
receipt_field "$TENSOR_G3_FULL_RECEIPT" variant tensor-g3
receipt_field "$TENSOR_G3_FULL_RECEIPT" artifact "$TENSOR_G3_AAR"
receipt_field "$TENSOR_G3_FULL_RECEIPT" sha256 "$TENSOR_G3_SOURCE_SHA256"
NDK_PROPERTIES="$(safe_path "$ANDROID_NDK_ARG/source.properties")"
NDK_HASH="$(sha256_file "$NDK_PROPERTIES")"
receipt_field "$TENSOR_G3_FULL_RECEIPT" ndk_revision_sha256 "$NDK_HASH"
receipt_field "$SDX_AOT_RECEIPT" ndk_revision_sha256 "$NDK_HASH"
receipt_field "$SDX_AOT_RECEIPT" stage android-aot-sdk

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
VARIANT=tensor-g3
source "$SCRIPT_DIR/tools/apk-publication.sh"
verify_packaging_provenance_anchors
# Do not overwrite an earlier attempt or follow pre-existing candidate symlinks.
# Use a fresh output directory for a retry; retained sources always survive.
for existing in "$OUTPUT_DIR/kompile-offline-graph-chat-tensor-g3-pixel-8a-${APK_BUILD_ID}-"*; do
  [[ ! -e "$existing" && ! -L "$existing" ]] || fail "candidate generation already exists: $existing"
done

TEMPORARY_FILES=()
OWNED_VERIFY_ROOT="$(mktemp -d "$APK_STAGING_ROOT/.retained-apk-verify.XXXXXX")"
cleanup_retained_publication() {
  local file
  for file in "${TEMPORARY_FILES[@]}"; do rm -f -- "$file"; done
  rm -rf -- "$OWNED_VERIFY_ROOT"
}
trap cleanup_retained_publication EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
# The full verifier may create only its own temporary subtree, never retained state.
export VERIFY_OFFLINE_APK_TMPDIR="$OWNED_VERIFY_ROOT"
publish_candidate "$APP_APK" 'kompile-offline-graph-chat-tensor-g3-pixel-8a.apk' \
  tensorG3 retained "$EXPECTED_APK_SHA256"
