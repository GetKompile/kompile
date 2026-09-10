#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

fail() {
  printf 'promote-offline-apk: %s\n' "$*" >&2
  exit 1
}

need() {
  [[ $# -ge 2 && -n "$2" ]] || fail "missing value for $1"
}

CANDIDATE_MANIFEST=""
DEVICE_RECEIPT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidate-manifest) need "$@"; CANDIDATE_MANIFEST="$2"; shift 2 ;;
    --device-receipt) need "$@"; DEVICE_RECEIPT="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: promote-offline-apk.sh --candidate-manifest FILE --device-receipt FILE"
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$CANDIDATE_MANIFEST" && -n "$DEVICE_RECEIPT" ]] ||
  fail "--candidate-manifest and --device-receipt are required"
[[ -s "$CANDIDATE_MANIFEST" && -f "$CANDIDATE_MANIFEST" ]] ||
  fail "candidate manifest not found or empty: $CANDIDATE_MANIFEST"
[[ -s "$DEVICE_RECEIPT" && -f "$DEVICE_RECEIPT" ]] ||
  fail "device receipt not found or empty: $DEVICE_RECEIPT"
CANDIDATE_MANIFEST="$(realpath -e -- "$CANDIDATE_MANIFEST")"
DEVICE_RECEIPT="$(realpath -e -- "$DEVICE_RECEIPT")"

sha256_file() {
  local value
  value="$(sha256sum "$1")" || fail "could not hash $1"
  printf '%s\n' "${value%% *}"
}

verify_checksum_sidecar() {
  local file="$1"
  local sidecar="$file.sha256"
  local actual line
  [[ -s "$sidecar" ]] || fail "checksum sidecar is missing: $sidecar"
  mapfile -t lines < "$sidecar"
  [[ "${#lines[@]}" -eq 1 ]] || fail "checksum sidecar must contain exactly one line: $sidecar"
  line="${lines[0]}"
  [[ "$line" =~ ^([0-9a-f]{64})[[:space:]][[:space:]](.+)$ ]] ||
    fail "checksum sidecar is malformed: $sidecar"
  actual="$(sha256_file "$file")"
  [[ "${BASH_REMATCH[1]}" == "$actual" ]] || fail "checksum sidecar digest mismatch: $sidecar"
  [[ "${BASH_REMATCH[2]}" == "$file" ]] || fail "checksum sidecar path mismatch: $sidecar"
}

declare -A CANDIDATE_ALLOWED=()
declare -A CANDIDATE_SEEN=()
declare -A CANDIDATE=()
for key in   format variant package build_id version_code candidate_apk candidate_sha256 stable_apk   test_apk test_apk_sha256 source_runtime_aar source_runtime_aar_sha256   normalized_runtime_aar normalized_runtime_aar_sha256 runtime_provenance   runtime_provenance_sha256 sdx_aot_sdk sdx_aot_provenance   sdx_aot_provenance_sha256 host_verification; do
  CANDIDATE_ALLOWED["$key"]=1
done

line_number=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line_number=$((line_number + 1))
  [[ "$line" == *=* ]] || fail "malformed candidate manifest line $line_number"
  key="${line%%=*}"
  value="${line#*=}"
  [[ -n "${CANDIDATE_ALLOWED[$key]:-}" ]] || fail "unknown candidate manifest field: $key"
  [[ -z "${CANDIDATE_SEEN[$key]:-}" ]] || fail "duplicate candidate manifest field: $key"
  [[ -n "$value" ]] || fail "empty candidate manifest field: $key"
  CANDIDATE_SEEN["$key"]=1
  CANDIDATE["$key"]="$value"
done < "$CANDIDATE_MANIFEST"
for key in "${!CANDIDATE_ALLOWED[@]}"; do
  [[ -n "${CANDIDATE_SEEN[$key]:-}" ]] || fail "missing candidate manifest field: $key"
done

[[ "${CANDIDATE[format]}" == 3 ]] || fail "unsupported candidate manifest format"
[[ "${CANDIDATE[variant]}" == tensorG3 ]] || fail "only Tensor G3 candidates can use this promotion gate"
[[ "${CANDIDATE[package]}" == ai.kompile.chat.local.android.tensorg3.debug ]] ||
  fail "unexpected Tensor G3 package"
[[ "${CANDIDATE[host_verification]}" == PASS ]] || fail "candidate host verification did not pass"
verify_checksum_sidecar "$CANDIDATE_MANIFEST"
candidate_manifest_sha256="$(sha256_file "$CANDIDATE_MANIFEST")"

for pair in   "candidate_apk:candidate_sha256"   "test_apk:test_apk_sha256"   "source_runtime_aar:source_runtime_aar_sha256"   "normalized_runtime_aar:normalized_runtime_aar_sha256"   "runtime_provenance:runtime_provenance_sha256"   "sdx_aot_provenance:sdx_aot_provenance_sha256"; do
  file_key="${pair%%:*}"
  sha_key="${pair#*:}"
  path="${CANDIDATE[$file_key]}"
  [[ -s "$path" && -f "$path" ]] || fail "candidate input is missing: $file_key=$path"
  [[ "$path" == "$(realpath -e -- "$path")" ]] || fail "$file_key is not canonical"
  actual="$(sha256_file "$path")"
  [[ "$actual" == "${CANDIDATE[$sha_key]}" ]] || fail "$file_key changed after host verification"
done
[[ "${CANDIDATE[stable_apk]}" == /* ]] || fail "stable APK path must be absolute"
stable_parent="$(dirname -- "${CANDIDATE[stable_apk]}")"
stable_name="$(basename -- "${CANDIDATE[stable_apk]}")"
[[ -d "$stable_parent" ]] || fail "stable APK directory does not exist: $stable_parent"
stable_parent="$(realpath -e -- "$stable_parent")"
[[ "${CANDIDATE[stable_apk]}" == "$stable_parent/$stable_name" ]] ||
  fail "stable APK parent path is not canonical"

declare -A RECEIPT_ALLOWED=()
declare -A RECEIPT_SEEN=()
declare -A RECEIPT=()
for key in   format candidate_manifest candidate_manifest_sha256 candidate_apk candidate_apk_sha256   stable_apk test_apk test_apk_sha256 source_runtime_aar_sha256   runtime_provenance_sha256 sdx_aot_provenance_sha256 build_id package model model_sha256 model_bytes   tokenizer tokenizer_sha256 tokenizer_bytes tokenizer_config tokenizer_config_sha256 tokenizer_config_bytes   device_serial device_model device_code board_platform device_abi device_fingerprint   device_sdk physical_transport cold_process_pid warm_process_pid instrumentation_output instrumentation_output_sha256 cold_decode warm_decode   qualification qualified_at_utc; do
  RECEIPT_ALLOWED["$key"]=1
done

line_number=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line_number=$((line_number + 1))
  [[ "$line" == *=* ]] || fail "malformed device receipt line $line_number"
  key="${line%%=*}"
  value="${line#*=}"
  [[ -n "${RECEIPT_ALLOWED[$key]:-}" ]] || fail "unknown device receipt field: $key"
  [[ -z "${RECEIPT_SEEN[$key]:-}" ]] || fail "duplicate device receipt field: $key"
  [[ -n "$value" ]] || fail "empty device receipt field: $key"
  RECEIPT_SEEN["$key"]=1
  RECEIPT["$key"]="$value"
done < "$DEVICE_RECEIPT"
for key in "${!RECEIPT_ALLOWED[@]}"; do
  [[ -n "${RECEIPT_SEEN[$key]:-}" ]] || fail "missing device receipt field: $key"
done

verify_checksum_sidecar "$DEVICE_RECEIPT"
[[ "${RECEIPT[format]}" == 4 ]] || fail "unsupported device receipt format"
[[ "${RECEIPT[candidate_manifest]}" == "$CANDIDATE_MANIFEST" ]] ||
  fail "device receipt names a different candidate manifest"
[[ "${RECEIPT[candidate_manifest_sha256]}" == "$candidate_manifest_sha256" ]] ||
  fail "device receipt candidate manifest digest mismatch"
for mapping in   "candidate_apk:candidate_apk"   "candidate_apk_sha256:candidate_sha256"   "stable_apk:stable_apk"   "test_apk:test_apk"   "test_apk_sha256:test_apk_sha256"   "source_runtime_aar_sha256:source_runtime_aar_sha256"   "runtime_provenance_sha256:runtime_provenance_sha256"   "sdx_aot_provenance_sha256:sdx_aot_provenance_sha256"   "build_id:build_id"   "package:package"; do
  receipt_key="${mapping%%:*}"
  candidate_key="${mapping#*:}"
  [[ "${RECEIPT[$receipt_key]}" == "${CANDIDATE[$candidate_key]}" ]] ||
    fail "device receipt does not bind candidate field: $receipt_key"
done

for key in model_sha256 tokenizer_sha256 tokenizer_config_sha256 instrumentation_output_sha256; do
  [[ "${RECEIPT[$key]}" =~ ^[0-9a-f]{64}$ ]] || fail "invalid receipt SHA-256: $key"
done
for asset in model tokenizer tokenizer_config; do
  bytes_key="${asset}_bytes"
  sha_key="${asset}_sha256"
  [[ "${RECEIPT[$bytes_key]}" =~ ^[0-9]+$ && "${RECEIPT[$bytes_key]}" -gt 0 ]] ||
    fail "invalid receipt $asset size"
  [[ -s "${RECEIPT[$asset]}" && -f "${RECEIPT[$asset]}" ]] ||
    fail "qualification $asset named by receipt is unavailable"
  [[ "$(sha256_file "${RECEIPT[$asset]}")" == "${RECEIPT[$sha_key]}" ]] ||
    fail "qualification $asset changed after device validation"
  [[ "$(stat -c %s "${RECEIPT[$asset]}")" == "${RECEIPT[$bytes_key]}" ]] ||
    fail "qualification $asset size changed after device validation"
done
[[ -s "${RECEIPT[instrumentation_output]}" && -f "${RECEIPT[instrumentation_output]}" ]] ||
  fail "instrumentation output named by receipt is unavailable"
[[ "$(sha256_file "${RECEIPT[instrumentation_output]}")" == "${RECEIPT[instrumentation_output_sha256]}" ]] ||
  fail "instrumentation output changed after device validation"

[[ "${RECEIPT[device_model]}" == "Pixel 8a" ]] || fail "receipt device is not Pixel 8a"
[[ "${RECEIPT[device_code]}" == akita ]] || fail "receipt device codename is not akita"
[[ "${RECEIPT[board_platform]}" == zumapro ]] || fail "receipt platform is not Tensor G3/zumapro"
[[ "${RECEIPT[device_abi]}" == arm64-v8a ]] || fail "receipt ABI is not arm64-v8a"
[[ "${RECEIPT[device_sdk]}" =~ ^[0-9]+$ ]] || fail "receipt SDK is invalid"
[[ "${RECEIPT[physical_transport]}" == usb ]] || fail "receipt does not prove a physical USB transport"
[[ "${RECEIPT[cold_process_pid]}" =~ ^[0-9]+$ &&
   "${RECEIPT[warm_process_pid]}" =~ ^[0-9]+$ &&
   "${RECEIPT[cold_process_pid]}" != "${RECEIPT[warm_process_pid]}" ]] ||
  fail "receipt does not prove distinct cold and warm Android processes"
[[ "${RECEIPT[cold_decode]}" == PASS ]] || fail "cold decode did not pass"
[[ "${RECEIPT[warm_decode]}" == PASS ]] || fail "warm decode did not pass"
[[ "${RECEIPT[qualification]}" == PASS ]] || fail "physical qualification did not pass"
[[ "${RECEIPT[qualified_at_utc]}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] ||
  fail "qualification timestamp is invalid"

stable_apk="${CANDIDATE[stable_apk]}"
stable_dir="$(dirname "$stable_apk")"
[[ -d "$stable_dir" && -w "$stable_dir" ]] || fail "stable APK directory is not writable: $stable_dir"
stable_generations="$stable_dir/.tensor-g3-stable-generations"
mkdir -p -- "$stable_generations"
[[ ! -L "$stable_generations" ]] || fail "stable generations root must not be a symlink"
stage_generation="$(mktemp -d "$stable_generations/.generation.XXXXXX")"
stage_apk="$stage_generation/artifact.apk"
stage_sha="$stage_generation/artifact.apk.sha256"
stage_provenance="$stage_generation/artifact.apk.provenance"
stage_provenance_sha="$stage_generation/artifact.apk.provenance.sha256"
stable_current="$stable_dir/.tensor-g3-stable-current"
stage_pointer="$stable_dir/.tensor-g3-stable-current.$$"
declare -a staged_public_links=()
cleanup() {
  rm -f -- "$stage_pointer"
  if [[ ${#staged_public_links[@]} -gt 0 ]]; then
    rm -f -- "${staged_public_links[@]}"
  fi
  if [[ -n "${stage_generation:-}" && -d "$stage_generation" ]]; then
    chmod -R u+w -- "$stage_generation" 2>/dev/null || true
    rm -rf -- "$stage_generation"
  fi
}
trap cleanup EXIT HUP INT TERM

dd if="${CANDIDATE[candidate_apk]}" of="$stage_apk" bs=16M conv=fsync status=none
stable_digest="$(sha256_file "$stage_apk")"
[[ "$stable_digest" == "${CANDIDATE[candidate_sha256]}" ]] ||
  fail "candidate changed while staging stable APK"
unzip -tq "$stage_apk" >/dev/null || fail "staged stable APK failed Zip integrity"
printf '%s  %s\n' "$stable_digest" "artifact.apk" >"$stage_sha"
{
  printf 'format=1\n'
  printf 'stable_apk=%s\n' "$stable_apk"
  printf 'stable_apk_sha256=%s\n' "$stable_digest"
  printf 'candidate_manifest=%s\n' "$CANDIDATE_MANIFEST"
  printf 'candidate_manifest_sha256=%s\n' "$candidate_manifest_sha256"
  printf 'device_receipt=%s\n' "$DEVICE_RECEIPT"
  printf 'device_receipt_sha256=%s\n' "$(sha256_file "$DEVICE_RECEIPT")"
  printf 'source_runtime_aar_sha256=%s\n' "${CANDIDATE[source_runtime_aar_sha256]}"
  printf 'runtime_provenance_sha256=%s\n' "${CANDIDATE[runtime_provenance_sha256]}"
  printf 'sdx_aot_provenance_sha256=%s\n' "${CANDIDATE[sdx_aot_provenance_sha256]}"
  printf 'model_sha256=%s\n' "${RECEIPT[model_sha256]}"
  printf 'tokenizer_sha256=%s\n' "${RECEIPT[tokenizer_sha256]}"
  printf 'tokenizer_config_sha256=%s\n' "${RECEIPT[tokenizer_config_sha256]}"
  printf 'device_fingerprint=%s\n' "${RECEIPT[device_fingerprint]}"
  printf 'qualification=PASS\n'
} >"$stage_provenance"
sync "$stage_sha" "$stage_provenance"
printf '%s  %s\n' "$(sha256_file "$stage_provenance")" "artifact.apk.provenance" >"$stage_provenance_sha"
sync "$stage_apk" "$stage_sha" "$stage_provenance" "$stage_provenance_sha"

generation_key="$(sha256_file "$DEVICE_RECEIPT")-${stable_digest:0:16}"
generation_dir="$stable_generations/$generation_key"
chmod -R a-w "$stage_generation"
if [[ -e "$generation_dir" || -L "$generation_dir" ]]; then
  [[ -d "$generation_dir" && ! -L "$generation_dir" ]] ||
    fail "stable generation is not a real directory: $generation_dir"
  if find "$generation_dir" -type l -print -quit | grep -q .; then
    fail "stable generation contains a symlink: $generation_dir"
  fi
  if find "$generation_dir" -perm /0222 -print -quit | grep -q .; then
    fail "stable generation contains a writable member: $generation_dir"
  fi
  diff -qr --no-dereference "$stage_generation" "$generation_dir" >/dev/null ||
    fail "stable generation key collides with different content: $generation_dir"
  chmod -R u+w -- "$stage_generation"
  rm -rf -- "$stage_generation"
  stage_generation=""
else
  mv -T -- "$stage_generation" "$generation_dir"
  stage_generation=""
fi

ensure_public_link() {
  local public_path="$1"
  local member="$2"
  local desired=".tensor-g3-stable-current/$member"
  local staged="$stable_dir/.tensor-g3-public-link.$$.${#staged_public_links[@]}"
  if [[ -L "$public_path" && "$(readlink -- "$public_path")" == "$desired" ]]; then
    return
  fi
  ln -s "$desired" "$staged"
  staged_public_links+=("$staged")
  mv -Tf -- "$staged" "$public_path"
}

[[ ! -e "$stable_current" || -L "$stable_current" ]] ||
  fail "stable generation pointer is not a symlink: $stable_current"
ensure_public_link "$stable_apk" artifact.apk
ensure_public_link "$stable_apk.sha256" artifact.apk.sha256
ensure_public_link "$stable_apk.provenance" artifact.apk.provenance
ensure_public_link "$stable_apk.provenance.sha256" artifact.apk.provenance.sha256
ln -s ".tensor-g3-stable-generations/$generation_key" "$stage_pointer"
mv -Tf -- "$stage_pointer" "$stable_current"
sync "$stable_current" "$generation_dir" "$stable_dir"
[[ "$(sha256_file "$stable_apk")" == "$stable_digest" ]] ||
  fail "stable APK pointer does not resolve to the qualified generation"
[[ "$(realpath -e -- "$stable_apk")" == "$generation_dir/artifact.apk" ]] ||
  fail "stable APK pointer escaped its qualified generation"
for public_member in artifact.apk.sha256 artifact.apk.provenance artifact.apk.provenance.sha256; do
  case "$public_member" in
    artifact.apk.sha256) public_path="$stable_apk.sha256" ;;
    artifact.apk.provenance) public_path="$stable_apk.provenance" ;;
    artifact.apk.provenance.sha256) public_path="$stable_apk.provenance.sha256" ;;
  esac
  [[ "$(realpath -e -- "$public_path")" == "$generation_dir/$public_member" ]] ||
    fail "stable sidecar pointer escaped its qualified generation: $public_path"
done

trap - EXIT HUP INT TERM
cleanup
printf 'Promoted physically qualified Tensor G3 APK generation: pointer=%s generation=%s sha256=%s receipt=%s\n'   "$stable_apk" "$generation_dir" "$stable_digest" "$DEVICE_RECEIPT"
