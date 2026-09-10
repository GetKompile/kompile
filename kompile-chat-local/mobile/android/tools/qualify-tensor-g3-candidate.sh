#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

fail() {
  printf 'qualify-tensor-g3-candidate: %s\n' "$*" >&2
  exit 1
}

need() {
  [[ $# -ge 2 && -n "$2" ]] || fail "missing value for $1"
}

CANDIDATE_MANIFEST=""
MODEL=""
TOKENIZER=""
TOKENIZER_CONFIG=""
ADB="${ANDROID_ADB:-adb}"
DEVICE_RECEIPT=""
ALLOW_WIFI_TRANSPORT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidate-manifest) need "$@"; CANDIDATE_MANIFEST="$2"; shift 2 ;;
    --model) need "$@"; MODEL="$2"; shift 2 ;;
    --tokenizer) need "$@"; TOKENIZER="$2"; shift 2 ;;
    --tokenizer-config) need "$@"; TOKENIZER_CONFIG="$2"; shift 2 ;;
    --adb) need "$@"; ADB="$2"; shift 2 ;;
    --device-receipt) need "$@"; DEVICE_RECEIPT="$2"; shift 2 ;;
    --allow-wifi-transport) ALLOW_WIFI_TRANSPORT=1; shift ;;
    -h|--help)
      echo "Usage: qualify-tensor-g3-candidate.sh --candidate-manifest FILE --model QWEN.gguf --tokenizer tokenizer.json --tokenizer-config tokenizer_config.json [--adb FILE] [--device-receipt FILE] [--allow-wifi-transport]"
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ -n "$CANDIDATE_MANIFEST" && -n "$MODEL" && -n "$TOKENIZER" && -n "$TOKENIZER_CONFIG" ]] ||
  fail "--candidate-manifest, --model, --tokenizer, and --tokenizer-config are required"
[[ -x "$ADB" ]] || fail "adb is not executable: $ADB"
[[ -s "$CANDIDATE_MANIFEST" && -f "$CANDIDATE_MANIFEST" ]] ||
  fail "candidate manifest not found or empty: $CANDIDATE_MANIFEST"
[[ -s "$MODEL" && -f "$MODEL" ]] || fail "Qwen GGUF not found or empty: $MODEL"
[[ "$MODEL" == *.gguf ]] || fail "qualification model must end in .gguf"
[[ -s "$TOKENIZER" && -f "$TOKENIZER" ]] ||
  fail "qualification tokenizer.json not found or empty: $TOKENIZER"
[[ -s "$TOKENIZER_CONFIG" && -f "$TOKENIZER_CONFIG" ]] ||
  fail "qualification tokenizer_config.json not found or empty: $TOKENIZER_CONFIG"

CANDIDATE_MANIFEST="$(realpath -e -- "$CANDIDATE_MANIFEST")"
MODEL="$(realpath -e -- "$MODEL")"
TOKENIZER="$(realpath -e -- "$TOKENIZER")"
TOKENIZER_CONFIG="$(realpath -e -- "$TOKENIZER_CONFIG")"
DEVICE_RECEIPT="${DEVICE_RECEIPT:-$CANDIDATE_MANIFEST.device-receipt}"
[[ "$DEVICE_RECEIPT" == /* ]] || DEVICE_RECEIPT="$(realpath -m -- "$DEVICE_RECEIPT")"
mkdir -p -- "$(dirname "$DEVICE_RECEIPT")"

sha256_file() {
  local value
  value="$(sha256sum "$1")" || fail "could not hash $1"
  printf '%s\n' "${value%% *}"
}

declare -A ALLOWED=()
declare -A SEEN=()
declare -A VALUE=()
for key in   format variant package build_id version_code candidate_apk candidate_sha256 stable_apk   test_apk test_apk_sha256 source_runtime_aar source_runtime_aar_sha256   normalized_runtime_aar normalized_runtime_aar_sha256 runtime_provenance   runtime_provenance_sha256 sdx_aot_sdk sdx_aot_provenance   sdx_aot_provenance_sha256 host_verification; do
  ALLOWED["$key"]=1
done

line_number=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line_number=$((line_number + 1))
  [[ "$line" == *=* ]] || fail "malformed candidate manifest line $line_number"
  key="${line%%=*}"
  value="${line#*=}"
  [[ -n "${ALLOWED[$key]:-}" ]] || fail "unknown candidate manifest field: $key"
  [[ -z "${SEEN[$key]:-}" ]] || fail "duplicate candidate manifest field: $key"
  [[ -n "$value" ]] || fail "empty candidate manifest field: $key"
  SEEN["$key"]=1
  VALUE["$key"]="$value"
done < "$CANDIDATE_MANIFEST"

for key in "${!ALLOWED[@]}"; do
  [[ -n "${SEEN[$key]:-}" ]] || fail "missing candidate manifest field: $key"
done
[[ "${VALUE[format]}" == 3 ]] || fail "unsupported candidate manifest format: ${VALUE[format]}"
[[ "${VALUE[variant]}" == tensorG3 ]] || fail "candidate is not Tensor G3"
[[ "${VALUE[package]}" == ai.kompile.chat.local.android.tensorg3.debug ]] ||
  fail "unexpected Tensor G3 package: ${VALUE[package]}"
[[ "${VALUE[host_verification]}" == PASS ]] || fail "candidate did not pass host verification"
[[ "${VALUE[build_id]}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || fail "invalid build ID"
[[ "${VALUE[version_code]}" =~ ^[0-9]+$ ]] || fail "invalid version code"

for key in candidate_sha256 test_apk_sha256 source_runtime_aar_sha256   normalized_runtime_aar_sha256 runtime_provenance_sha256 sdx_aot_provenance_sha256; do
  [[ "${VALUE[$key]}" =~ ^[0-9a-f]{64}$ ]] || fail "invalid SHA-256 field: $key"
done

for pair in   "candidate_apk:candidate_sha256"   "test_apk:test_apk_sha256"   "source_runtime_aar:source_runtime_aar_sha256"   "normalized_runtime_aar:normalized_runtime_aar_sha256"   "runtime_provenance:runtime_provenance_sha256"   "sdx_aot_provenance:sdx_aot_provenance_sha256"; do
  file_key="${pair%%:*}"
  sha_key="${pair#*:}"
  path="${VALUE[$file_key]}"
  [[ -s "$path" && -f "$path" ]] || fail "candidate input is missing: $file_key=$path"
  canonical="$(realpath -e -- "$path")"
  [[ "$path" == "$canonical" ]] || fail "$file_key is not canonical: $path"
  actual="$(sha256_file "$path")"
  [[ "$actual" == "${VALUE[$sha_key]}" ]] ||
    fail "$file_key digest mismatch: expected=${VALUE[$sha_key]} actual=$actual"
done
[[ -d "${VALUE[sdx_aot_sdk]}" && ! -w "$(realpath -e -- "${VALUE[sdx_aot_sdk]}")" ]] ||
  fail "candidate SDX AOT SDK is absent or not an immutable generation"
[[ "$(realpath -e -- "${VALUE[sdx_aot_provenance]}")" == "$(realpath -e -- "${VALUE[sdx_aot_sdk]}")/metadata/build-receipt" ]] ||
  fail "candidate SDX AOT provenance is not owned by the selected SDK generation"

[[ "${VALUE[stable_apk]}" == /* ]] || fail "stable_apk must be absolute"
[[ "${VALUE[stable_apk]}" == "$(realpath -m -- "${VALUE[stable_apk]}")" ]] ||
  fail "stable_apk is not canonical"

manifest_sha256="$(sha256_file "$CANDIDATE_MANIFEST")"
checksum_file="$CANDIDATE_MANIFEST.sha256"
[[ -s "$checksum_file" ]] || fail "candidate manifest checksum is missing: $checksum_file"
mapfile -t checksum_lines < "$checksum_file"
[[ "${#checksum_lines[@]}" -eq 1 ]] || fail "candidate manifest checksum must contain exactly one line"
if [[ ! "${checksum_lines[0]}" =~ ^([0-9a-f]{64})[[:space:]][[:space:]](.+)$ ]]; then
  fail "candidate manifest checksum is malformed"
fi
[[ "${BASH_REMATCH[1]}" == "$manifest_sha256" ]] || fail "candidate manifest checksum digest mismatch"
[[ "${BASH_REMATCH[2]}" == "$CANDIDATE_MANIFEST" ]] || fail "candidate manifest checksum path mismatch"

mapfile -t device_rows < <("$ADB" devices -l | awk 'NR > 1 && NF')
[[ "${#device_rows[@]}" -eq 1 ]] ||
  fail "exactly one adb device must be attached; found ${#device_rows[@]}"
IFS=' ' read -r serial device_state device_details <<<"${device_rows[0]}"
[[ "$device_state" == device ]] || fail "adb device is not ready: serial=$serial state=$device_state"
if [[ "$serial" != emulator-* && "$device_details" == *"usb:"* ]]; then
  transport_kind=usb
elif [[ $ALLOW_WIFI_TRANSPORT -eq 1 && "$serial" == *._adb-tls-connect._tcp ]]; then
  # Wi-Fi debug is an explicit opt-in: mDNS TLS transport to a physical device.
  # The chosen transport is recorded in the device receipt for provenance.
  transport_kind=wifi_tls
else
  fail "qualification requires one physical USB adb transport (or pass --allow-wifi-transport with an _adb-tls-connect transport): ${device_rows[0]}"
fi

getprop_exact() {
  "$ADB" -s "$serial" shell getprop "$1" | tr -d '\r'
}

device_model="$(getprop_exact ro.product.model)"
device_code="$(getprop_exact ro.product.device)"
board_platform="$(getprop_exact ro.board.platform)"
soc_model="$(getprop_exact ro.soc.model)"
device_abi="$(getprop_exact ro.product.cpu.abi)"
device_fingerprint="$(getprop_exact ro.build.fingerprint)"
device_sdk="$(getprop_exact ro.build.version.sdk)"
kernel_qemu="$(getprop_exact ro.kernel.qemu)"
boot_qemu="$(getprop_exact ro.boot.qemu)"

[[ "$device_model" == "Pixel 8a" ]] || fail "physical device is not Pixel 8a: $device_model"
[[ "$device_code" == akita ]] || fail "physical device codename is not akita: $device_code"
# Pixel 8a (akita) is Tensor G3: ro.board.platform=zuma (zumapro is Tensor G4 on
# Pixel 9 devices). ro.soc.model is the definitive SoC identity.
[[ "$board_platform" == zuma ]] || fail "physical device platform is not Tensor G3/zuma: $board_platform"
[[ "$soc_model" == "Tensor G3" ]] || fail "physical device SoC is not Tensor G3: $soc_model"
[[ "$device_abi" == arm64-v8a ]] || fail "physical device ABI is not arm64-v8a: $device_abi"
[[ "$device_sdk" =~ ^[0-9]+$ ]] || fail "device SDK is invalid: $device_sdk"
[[ -n "$device_fingerprint" ]] || fail "device fingerprint is empty"
[[ "$kernel_qemu" != 1 && "$boot_qemu" != 1 ]] ||
  fail "emulated Android targets cannot qualify a Tensor G3 candidate"

model_sha256="$(sha256_file "$MODEL")"
model_bytes="$(stat -c %s "$MODEL")"
[[ "$model_sha256" =~ ^[0-9a-f]{64}$ && "$model_bytes" =~ ^[0-9]+$ && "$model_bytes" -gt 0 ]] ||
  fail "invalid model attestation"
tokenizer_sha256="$(sha256_file "$TOKENIZER")"
tokenizer_bytes="$(stat -c %s "$TOKENIZER")"
tokenizer_config_sha256="$(sha256_file "$TOKENIZER_CONFIG")"
tokenizer_config_bytes="$(stat -c %s "$TOKENIZER_CONFIG")"
[[ "$tokenizer_sha256" =~ ^[0-9a-f]{64}$ && "$tokenizer_bytes" =~ ^[0-9]+$ && "$tokenizer_bytes" -gt 0 ]] ||
  fail "invalid tokenizer.json attestation"
[[ "$tokenizer_config_sha256" =~ ^[0-9a-f]{64}$ && "$tokenizer_config_bytes" =~ ^[0-9]+$ && "$tokenizer_config_bytes" -gt 0 ]] ||
  fail "invalid tokenizer_config.json attestation"

package="${VALUE[package]}"
test_package="$package.test"
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"
test_class_cold="ai.kompile.chat.local.android.model.TensorG3QualificationTest#coldQwenDecodeUsesExactPackagedRuntime"
test_class_warm="ai.kompile.chat.local.android.model.TensorG3QualificationTest#warmQwenDecodeUsesExactPackagedRuntime"
remote_root="/data/local/tmp/tensor-g3-qualification-$model_sha256"
remote_model="$remote_root/model.gguf"
remote_tokenizer="$remote_root/tokenizer.json"
remote_tokenizer_config="$remote_root/tokenizer_config.json"
output_tmp="$(mktemp "$(dirname "$DEVICE_RECEIPT")/.tensor-g3-instrumentation.XXXXXX")"
receipt_tmp="$(mktemp "$(dirname "$DEVICE_RECEIPT")/.tensor-g3-device-receipt.XXXXXX")"
receipt_sha_tmp="$(mktemp "$(dirname "$DEVICE_RECEIPT")/.tensor-g3-device-receipt-sha.XXXXXX")"
output_final="$DEVICE_RECEIPT.instrumentation.log"

cleanup() {
  rm -f -- "$output_tmp" "$receipt_tmp" "$receipt_sha_tmp"
  if [[ -n "$remote_root" ]]; then
    "$ADB" -s "$serial" shell rm -rf "$remote_root" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT HUP INT TERM

install_apk() {
  local label="$1"
  local apk="$2"
  local install_output
  if ! install_output="$("$ADB" -s "$serial" install -r -t "$apk" 2>&1)"; then
    fail "could not install $label: $install_output"
  fi
}

device_sha256() {
  local relative_path="$1"
  local value
  value="$("$ADB" -s "$serial" shell run-as "$package" sha256sum "$relative_path" | tr -d '\r')" ||
    fail "could not hash app-private qualification input: $relative_path"
  [[ "$value" =~ ^([0-9a-f]{64})[[:space:]]+.+$ ]] ||
    fail "malformed app-private qualification digest: $relative_path"
  printf '%s\n' "${BASH_REMATCH[1]}"
}

# Shell-level digest over the shared /data/local/tmp staging area. Digest verification is still
# enforced twice (host expectation here, app-private device_sha256 after the copy), so skipping a
# re-push never admits different bytes — it only avoids forcing ~3 GB through the page cache on
# repeat runs, which alone pushes enough app pages into zram to sink the qualification preflight.
remote_sha256() {
  local remote_path="$1"
  local value
  value="$("$ADB" -s "$serial" shell sha256sum "$remote_path" 2>/dev/null | tr -d '\r')" || return 1
  [[ "$value" =~ ^([0-9a-f]{64})[[:space:]] ]] || return 1
  printf '%s\n' "${BASH_REMATCH[1]}"
}

send_status_quiet() {
  printf 'qualify-tensor-g3-candidate: %s\n' "$*"
}

install_apk "candidate APK" "${VALUE[candidate_apk]}"
install_apk "qualification test APK" "${VALUE[test_apk]}"
[[ "$("$ADB" -s "$serial" shell pm clear "$package" | tr -d '\r')" == Success ]] ||
  fail "could not clear candidate package data for a cold import"

"$ADB" -s "$serial" shell mkdir -p "$remote_root" ||
  fail "could not create the device qualification staging directory"
if [[ "$(remote_sha256 "$remote_model")" == "$model_sha256" ]]; then
  send_status_quiet "staged model already matches $model_sha256; skipping re-push"
else
  "$ADB" -s "$serial" shell rm -f "$remote_model"
  "$ADB" -s "$serial" push "$MODEL" "$remote_model" >/dev/null ||
    fail "could not stage qualification model on device"
  [[ "$(remote_sha256 "$remote_model")" == "$model_sha256" ]] ||
    fail "staged qualification model digest mismatch"
fi
if [[ "$(remote_sha256 "$remote_tokenizer")" == "$tokenizer_sha256" ]]; then
  send_status_quiet "staged tokenizer.json already matches; skipping re-push"
else
  "$ADB" -s "$serial" shell rm -f "$remote_tokenizer"
  "$ADB" -s "$serial" push "$TOKENIZER" "$remote_tokenizer" >/dev/null ||
    fail "could not stage qualification tokenizer.json on device"
  [[ "$(remote_sha256 "$remote_tokenizer")" == "$tokenizer_sha256" ]] ||
    fail "staged qualification tokenizer.json digest mismatch"
fi
if [[ "$(remote_sha256 "$remote_tokenizer_config")" == "$tokenizer_config_sha256" ]]; then
  send_status_quiet "staged tokenizer_config.json already matches; skipping re-push"
else
  "$ADB" -s "$serial" shell rm -f "$remote_tokenizer_config"
  "$ADB" -s "$serial" push "$TOKENIZER_CONFIG" "$remote_tokenizer_config" >/dev/null ||
    fail "could not stage qualification tokenizer_config.json on device"
  [[ "$(remote_sha256 "$remote_tokenizer_config")" == "$tokenizer_config_sha256" ]] ||
    fail "staged qualification tokenizer_config.json digest mismatch"
fi
"$ADB" -s "$serial" shell chmod 0644 "$remote_model" "$remote_tokenizer" "$remote_tokenizer_config" ||
  fail "could not make staged qualification inputs readable"
"$ADB" -s "$serial" shell run-as "$package" mkdir -p files/qualification ||
  fail "could not create app-private qualification directory"
"$ADB" -s "$serial" shell run-as "$package" cp "$remote_model" files/qualification/model.gguf ||
  fail "could not copy qualification model into app-private storage"
"$ADB" -s "$serial" shell run-as "$package" cp "$remote_tokenizer" files/qualification/tokenizer.json ||
  fail "could not copy qualification tokenizer.json into app-private storage"
"$ADB" -s "$serial" shell run-as "$package" cp "$remote_tokenizer_config" files/qualification/tokenizer_config.json ||
  fail "could not copy qualification tokenizer_config.json into app-private storage"
[[ "$(device_sha256 files/qualification/model.gguf)" == "$model_sha256" ]] ||
  fail "app-private qualification model digest mismatch"
[[ "$(device_sha256 files/qualification/tokenizer.json)" == "$tokenizer_sha256" ]] ||
  fail "app-private qualification tokenizer.json digest mismatch"
[[ "$(device_sha256 files/qualification/tokenizer_config.json)" == "$tokenizer_config_sha256" ]] ||
  fail "app-private qualification tokenizer_config.json digest mismatch"
"$ADB" -s "$serial" shell rm -rf "$remote_root"
remote_root=""

instrumentation_args=(
  -e expected_build_id "${VALUE[build_id]}"
  -e expected_source_runtime_aar_sha256 "${VALUE[source_runtime_aar_sha256]}"
  -e expected_runtime_provenance_sha256 "${VALUE[runtime_provenance_sha256]}"
  -e expected_sdx_aot_provenance_sha256 "${VALUE[sdx_aot_provenance_sha256]}"
  -e model_sha256 "$model_sha256"
  -e model_bytes "$model_bytes"
)

printf '%s\n' '=== COLD PROCESS ===' >"$output_tmp"
set +e
"$ADB" -s "$serial" shell am instrument -w -r -e class "$test_class_cold" "${instrumentation_args[@]}" "$runner" >>"$output_tmp" 2>&1
cold_instrumentation_status=$?
set -e
if [[ "$cold_instrumentation_status" -ne 0 ]]; then
  sed -n '1,240p' "$output_tmp" >&2
  fail "cold instrumentation command failed with status $cold_instrumentation_status"
fi

# Do not start the warm process unless cold qualification really passed. In
# particular, a DEVICE_UNDER_LOAD preflight is a retryable device-state result,
# not permission to wait another 90 seconds and then report a misleading warm
# cache failure.
if grep -Fq 'tensor_g3_qualification=DEVICE_UNDER_LOAD:' "$output_tmp"; then
  sed -n '1,240p' "$output_tmp" >&2
  fail "cold qualification refused to run because the device is under load"
fi
[[ "$(grep -Fc 'tensor_g3_qualification=MEMORY_PREFLIGHT:' "$output_tmp" || true)" -eq 1 ]] || {
  sed -n '1,240p' "$output_tmp" >&2
  fail "cold qualification did not publish one memory preflight result"
}
grep -Fq 'thresholdMet=true' "$output_tmp" || {
  sed -n '1,240p' "$output_tmp" >&2
  fail "cold qualification memory floor was not met"
}
[[ "$(grep -Fc 'tensor_g3_qualification=COLD_DECODE_PASS' "$output_tmp" || true)" -eq 1 ]] || {
  sed -n '1,240p' "$output_tmp" >&2
  fail "cold qualification did not pass"
}
[[ "$(grep -Fc 'OK (1 test)' "$output_tmp" || true)" -eq 1 ]] || {
  sed -n '1,240p' "$output_tmp" >&2
  fail "cold AndroidJUnitRunner result was not one passing test"
}
[[ "$(grep -Fc 'INSTRUMENTATION_CODE: -1' "$output_tmp" || true)" -eq 1 ]] || {
  sed -n '1,240p' "$output_tmp" >&2
  fail "cold instrumentation did not finish successfully"
}

"$ADB" -s "$serial" shell am force-stop "$package" || fail "could not stop target process between cold and warm runs"
"$ADB" -s "$serial" shell am force-stop "$test_package" || fail "could not stop test process between cold and warm runs"
printf '%s\n' '=== WARM PROCESS ===' >>"$output_tmp"
set +e
"$ADB" -s "$serial" shell am instrument -w -r -e class "$test_class_warm" "${instrumentation_args[@]}" "$runner" >>"$output_tmp" 2>&1
warm_instrumentation_status=$?
set -e
if [[ "$warm_instrumentation_status" -ne 0 ]]; then
  sed -n '1,480p' "$output_tmp" >&2
  fail "warm instrumentation command failed with status $warm_instrumentation_status"
fi

for marker in COLD_DECODE_PASS WARM_DECODE_PASS QUALIFICATION_PASS; do
  count="$(grep -Fc "tensor_g3_qualification=$marker" "$output_tmp" || true)"
  [[ "$count" -eq 1 ]] || {
    sed -n '1,240p' "$output_tmp" >&2
    fail "qualification marker $marker occurred $count times"
  }
done
[[ "$(grep -Fc 'tensor_g3_qualification=INPUT_VERIFIED' "$output_tmp" || true)" -eq 2 ]] ||
  fail "each qualification process must independently verify its inputs"
[[ "$(grep -Fc 'OK (1 test)' "$output_tmp" || true)" -eq 2 ]] || {
  sed -n '1,240p' "$output_tmp" >&2
  fail "AndroidJUnitRunner did not report one passing test for each process"
}
[[ "$(grep -Fc 'INSTRUMENTATION_CODE: -1' "$output_tmp" || true)" -eq 2 ]] || {
  sed -n '1,240p' "$output_tmp" >&2
  fail "both instrumentation processes did not finish successfully"
}
! grep -Fq 'FAILURES!!!' "$output_tmp" || fail "instrumentation reported a test failure"
mapfile -t qualification_pids < <(grep -oE 'tensor_g3_qualification=PROCESS_PID:[0-9]+' "$output_tmp" | cut -d: -f2)
[[ "${#qualification_pids[@]}" -eq 2 && "${qualification_pids[0]}" != "${qualification_pids[1]}" ]] ||
  fail "cold and warm qualification did not execute in distinct Android processes"

mv -f -- "$output_tmp" "$output_final"
instrumentation_sha256="$(sha256_file "$output_final")"
qualified_at_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  printf 'format=4\n'
  printf 'candidate_manifest=%s\n' "$CANDIDATE_MANIFEST"
  printf 'candidate_manifest_sha256=%s\n' "$manifest_sha256"
  printf 'candidate_apk=%s\n' "${VALUE[candidate_apk]}"
  printf 'candidate_apk_sha256=%s\n' "${VALUE[candidate_sha256]}"
  printf 'stable_apk=%s\n' "${VALUE[stable_apk]}"
  printf 'test_apk=%s\n' "${VALUE[test_apk]}"
  printf 'test_apk_sha256=%s\n' "${VALUE[test_apk_sha256]}"
  printf 'source_runtime_aar_sha256=%s\n' "${VALUE[source_runtime_aar_sha256]}"
  printf 'runtime_provenance_sha256=%s\n' "${VALUE[runtime_provenance_sha256]}"
  printf 'sdx_aot_provenance_sha256=%s\n' "${VALUE[sdx_aot_provenance_sha256]}"
  printf 'build_id=%s\n' "${VALUE[build_id]}"
  printf 'package=%s\n' "$package"
  printf 'model=%s\n' "$MODEL"
  printf 'model_sha256=%s\n' "$model_sha256"
  printf 'model_bytes=%s\n' "$model_bytes"
  printf 'tokenizer=%s\n' "$TOKENIZER"
  printf 'tokenizer_sha256=%s\n' "$tokenizer_sha256"
  printf 'tokenizer_bytes=%s\n' "$tokenizer_bytes"
  printf 'tokenizer_config=%s\n' "$TOKENIZER_CONFIG"
  printf 'tokenizer_config_sha256=%s\n' "$tokenizer_config_sha256"
  printf 'tokenizer_config_bytes=%s\n' "$tokenizer_config_bytes"
  printf 'device_serial=%s\n' "$serial"
  printf 'device_model=%s\n' "$device_model"
  printf 'device_code=%s\n' "$device_code"
  printf 'board_platform=%s\n' "$board_platform"
  printf 'device_abi=%s\n' "$device_abi"
  printf 'device_fingerprint=%s\n' "$device_fingerprint"
  printf 'device_sdk=%s\n' "$device_sdk"
  printf 'soc_model=%s\n' "$soc_model"
  printf 'physical_transport=%s\n' "$transport_kind"
  printf 'adb_serial=%s\n' "$serial"
  printf 'cold_process_pid=%s\n' "${qualification_pids[0]}"
  printf 'warm_process_pid=%s\n' "${qualification_pids[1]}"
  printf 'instrumentation_output=%s\n' "$output_final"
  printf 'instrumentation_output_sha256=%s\n' "$instrumentation_sha256"
  printf 'cold_decode=PASS\n'
  printf 'warm_decode=PASS\n'
  printf 'qualification=PASS\n'
  printf 'qualified_at_utc=%s\n' "$qualified_at_utc"
} >"$receipt_tmp"
sync "$receipt_tmp" "$output_final"
mv -f -- "$receipt_tmp" "$DEVICE_RECEIPT"
printf '%s  %s\n' "$(sha256_file "$DEVICE_RECEIPT")" "$DEVICE_RECEIPT" >"$receipt_sha_tmp"
sync "$DEVICE_RECEIPT" "$receipt_sha_tmp"
mv -f -- "$receipt_sha_tmp" "$DEVICE_RECEIPT.sha256"

trap - EXIT HUP INT TERM
cleanup
printf 'Tensor G3 candidate qualified on physical Pixel 8a: candidate=%s receipt=%s modelSha256=%s\n'   "${VALUE[candidate_apk]}" "$DEVICE_RECEIPT" "$model_sha256"
