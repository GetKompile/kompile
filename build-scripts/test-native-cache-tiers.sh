#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOMPILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
GRAALVM_HOME="${GRAALVM_HOME:-${JAVA_HOME:-}}"
if [[ -z "${GRAALVM_HOME}" || ! -x "${GRAALVM_HOME}/bin/java" ]]; then
  printf 'GRAALVM_HOME or JAVA_HOME must provide bin/java\n' >&2
  exit 2
fi
if [[ ! -x "${GRAALVM_HOME}/bin/jar" ]]; then
  printf 'The selected Java runtime must provide bin/jar\n' >&2
  exit 2
fi

TEST_ROOT="$(mktemp -d)"
cleanup() {
  rm -rf -- "${TEST_ROOT}"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

HELPER="${SCRIPT_DIR}/NativeImageDependencyFingerprint.java"
CLASSPATH_FILE="${TEST_ROOT}/runtime.classpath"
FIXTURE_ROOT="${TEST_ROOT}/fixture"
FIXTURE_JAR="${TEST_ROOT}/dependency.jar"
MANIFEST_CACHE="${TEST_ROOT}/dependency-manifests"

make_fixture_jar() {
  local class_bytes="$1"
  local native_bytes="$2"
  local metadata_bytes="$3"
  rm -rf -- "${FIXTURE_ROOT}"
  mkdir -p     "${FIXTURE_ROOT}/org/example"     "${FIXTURE_ROOT}/META-INF/native-image/example"     "${FIXTURE_ROOT}/org/bytedeco/example/linux-x86_64"
  printf '%s' "${class_bytes}" > "${FIXTURE_ROOT}/org/example/Example.class"
  printf '%s' "${metadata_bytes}" > "${FIXTURE_ROOT}/META-INF/native-image/example/reflect-config.json"
  printf '%s' "${native_bytes}" > "${FIXTURE_ROOT}/org/bytedeco/example/linux-x86_64/libexample.so"
  rm -f -- "${FIXTURE_JAR}"
  "${GRAALVM_HOME}/bin/jar" --create --file "${FIXTURE_JAR}" -C "${FIXTURE_ROOT}" .
}

dependency_fingerprints() {
  local output schema="" aot="" runtime="" key value
  output="$("${GRAALVM_HOME}/bin/java" --source 17 "${HELPER}" \
    "${CLASSPATH_FILE}" "${MANIFEST_CACHE}")"
  while IFS='=' read -r key value; do
    case "${key}" in
      schema) schema="${value}" ;;
      aot) aot="${value}" ;;
      runtime) runtime="${value}" ;;
    esac
  done <<< "${output}"
  [[ "${schema}" == kompile-native-dependency-fingerprints-v1 ]] || fail "unexpected helper schema"
  [[ "${aot}" =~ ^[0-9a-f]{64}$ ]] || fail "missing AOT fingerprint"
  [[ "${runtime}" =~ ^[0-9a-f]{64}$ ]] || fail "missing runtime fingerprint"
  printf '%s %s\n' "${aot}" "${runtime}"
}

printf '%s' "${FIXTURE_JAR}" > "${CLASSPATH_FILE}"
make_fixture_jar class-v1 native-v1 metadata-v1
read -r AOT_BASE RUNTIME_BASE <<< "$(dependency_fingerprints)"
shopt -s nullglob
MANIFEST_RECEIPTS=("${MANIFEST_CACHE}"/*.receipt)
shopt -u nullglob
[[ "${#MANIFEST_RECEIPTS[@]}" -gt 0 ]] ||
  fail "dependency manifest cache did not publish an immutable snapshot receipt"
read -r AOT_BASE_CACHED RUNTIME_BASE_CACHED <<< "$(dependency_fingerprints)"
[[ "${AOT_BASE}" == "${AOT_BASE_CACHED}" && "${RUNTIME_BASE}" == "${RUNTIME_BASE_CACHED}" ]] ||
  fail "cached dependency manifest changed either fingerprint"

make_fixture_jar class-v1 native-v2 metadata-v1
read -r AOT_NATIVE_CHANGED RUNTIME_NATIVE_CHANGED <<< "$(dependency_fingerprints)"
[[ "${AOT_BASE}" == "${AOT_NATIVE_CHANGED}" ]] ||
  fail "native payload-only change invalidated the AOT fingerprint"
[[ "${RUNTIME_BASE}" != "${RUNTIME_NATIVE_CHANGED}" ]] ||
  fail "native payload-only change did not invalidate the runtime fingerprint"

make_fixture_jar class-v2 native-v2 metadata-v1
read -r AOT_CLASS_CHANGED RUNTIME_CLASS_CHANGED <<< "$(dependency_fingerprints)"
[[ "${AOT_NATIVE_CHANGED}" != "${AOT_CLASS_CHANGED}" ]] ||
  fail "class change did not invalidate the AOT fingerprint"
[[ "${RUNTIME_NATIVE_CHANGED}" == "${RUNTIME_CLASS_CHANGED}" ]] ||
  fail "class-only change invalidated the runtime fingerprint"

make_fixture_jar class-v2 native-v2 metadata-v2
read -r AOT_METADATA_CHANGED RUNTIME_METADATA_CHANGED <<< "$(dependency_fingerprints)"
[[ "${AOT_CLASS_CHANGED}" != "${AOT_METADATA_CHANGED}" ]] ||
  fail "Native Image metadata change did not invalidate the AOT fingerprint"
[[ "${RUNTIME_CLASS_CHANGED}" == "${RUNTIME_METADATA_CHANGED}" ]] ||
  fail "metadata-only change invalidated the runtime fingerprint"

KOMPILE_NATIVE_CACHE_DIR="${TEST_ROOT}/cache"
KOMPILE_OUTPUT_DIR="${TEST_ROOT}/output"
KOMPILE_NATIVE_QUICK_BUILD=1
KOMPILE_NATIVE_CACHE_REMOTE_ROOT="remote://kompile-native-cache"
KOMPILE_NATIVE_CACHE_REMOTE_TOOL="test-double"
export KOMPILE_ROOT GRAALVM_HOME KOMPILE_NATIVE_CACHE_DIR KOMPILE_OUTPUT_DIR \
  KOMPILE_NATIVE_QUICK_BUILD KOMPILE_NATIVE_CACHE_REMOTE_ROOT KOMPILE_NATIVE_CACHE_REMOTE_TOOL
# shellcheck source=build-common.sh
source "${SCRIPT_DIR}/build-common.sh"

# Exercise the Windows/MSYS conversion branch even when this test runs on
# Linux CI. The production worker supplies cygpath from MSYS2; this deterministic
# shim verifies that native JVM paths are converted in both directions.
_saved_ostype="${OSTYPE-}"
_saved_msystem="${MSYSTEM-}"
cygpath() {
  case "$1" in
    -u) printf '/c/k/output\n' ;;
    -w) printf '%s\n' 'C:\k\output' ;;
    *) return 1 ;;
  esac
}
OSTYPE=cygwin
MSYSTEM=MSYS
[[ "$(kompile_path_to_posix 'C:\\k\\output')" == '/c/k/output' ]] ||
  fail 'Windows path was not converted to the MSYS namespace'
[[ "$(kompile_path_to_native '/c/k/output')" == 'C:\k\output' ]] ||
  fail 'MSYS path was not converted to the native Windows namespace'
unset -f cygpath
OSTYPE="${_saved_ostype}"
MSYSTEM="${_saved_msystem}"

# GitHub release lanes keep this cache in Cloudflare R2 through the S3 API.
# Drive the real transport against a fake aws CLI that records its argv and
# credential environment and keeps objects under a local directory.
FAKE_AWS_BIN="${TEST_ROOT}/fake-aws-bin"
FAKE_AWS_LOG="${TEST_ROOT}/fake-aws"
FAKE_S3_ROOT="${TEST_ROOT}/fake-s3"
mkdir -p "${FAKE_AWS_BIN}" "${FAKE_S3_ROOT}"
: > "${FAKE_AWS_LOG}.argv"
: > "${FAKE_AWS_LOG}.env"
cat > "${FAKE_AWS_BIN}/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_AWS_LOG}.argv"
printf 'key=%s secret=%s region=%s profile=%s session=%s request=%s response=%s\n' \
  "${AWS_ACCESS_KEY_ID-unset}" "${AWS_SECRET_ACCESS_KEY-unset}" "${AWS_DEFAULT_REGION-unset}" \
  "${AWS_PROFILE-unset}" "${AWS_SESSION_TOKEN-unset}" \
  "${AWS_REQUEST_CHECKSUM_CALCULATION-unset}" "${AWS_RESPONSE_CHECKSUM_VALIDATION-unset}" \
  >> "${FAKE_AWS_LOG}.env"
[[ "$1 $2" == "s3 cp" ]] || exit 2
case "$3" in
  s3://*)
    object="${FAKE_S3_ROOT}/${3#s3://}"
    if [[ ! -f "${object}" ]]; then
      printf 'fatal error: An error occurred (404) when calling the HeadObject operation: Key "%s" does not exist\n' \
        "${3#s3://}" >&2
      exit 1
    fi
    cp "${object}" "$4"
    ;;
  *)
    object="${FAKE_S3_ROOT}/${4#s3://}"
    mkdir -p "$(dirname "${object}")"
    rm -f -- "${object}"
    cp "$3" "${object}"
    ;;
esac
FAKE_AWS
chmod +x "${FAKE_AWS_BIN}/aws"
fake_aws_calls() {
  printf '%s' "$(($(wc -l < "${FAKE_AWS_LOG}.argv")))"
}
(
  export PATH="${FAKE_AWS_BIN}:${PATH}" FAKE_AWS_LOG FAKE_S3_ROOT
  export AWS_PROFILE=ambient-profile AWS_SESSION_TOKEN=ambient-session
  KOMPILE_NATIVE_CACHE_DIR="${TEST_ROOT}/r2-cache"
  KOMPILE_NATIVE_CACHE_REMOTE_ROOT=deeplearning4j/releases/kompile-native-cache/v1
  KOMPILE_NATIVE_CACHE_REMOTE_TOOL=aws-cli
  KOMPILE_NATIVE_CACHE_REMOTE_CONTAINER=dl4j-cache
  KOMPILE_NATIVE_CACHE_REMOTE_ENDPOINT=https://r2.example.invalid
  KOMPILE_NATIVE_CACHE_REMOTE_ACCESS_KEY_ID=$' test-key-id\n'
  KOMPILE_NATIVE_CACHE_REMOTE_SECRET_ACCESS_KEY=$'test-secret-value\r\n'
  r2_image="${TEST_ROOT}/r2-target/native-worker"
  mkdir -p "$(dirname "${r2_image}")"
  printf 'r2-native-image' > "${r2_image}"
  chmod +x "${r2_image}"
  r2_aot="$(printf 'r2-aot' | kompile_sha256_stdin)"
  r2_runtime="$(printf 'r2-runtime' | kompile_sha256_stdin)"

  kompile_publish_cached_native_image fixture "${r2_image}" "${r2_aot}" "${r2_runtime}"
  r2_object="${FAKE_S3_ROOT}/dl4j-cache/${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/fixture/${r2_aot}/native-worker"
  [[ -f "${r2_object}" && -f "${r2_object}.native-cache" ]] ||
    fail 'R2 publish did not store the image and receipt under the logical cache key'
  r2_sha="$(kompile_sha256_file "${r2_image}")"
  printf 'corrupted-local-target' > "${r2_image}"
  rm -rf -- "${KOMPILE_NATIVE_CACHE_DIR}"
  kompile_restore_cached_native_image fixture "${r2_image}" "${r2_aot}" "${r2_runtime}" ||
    fail 'R2 cache did not restore into an empty local cache'
  [[ "$(kompile_sha256_file "${r2_image}")" == "${r2_sha}" ]] || fail 'R2 restored image checksum mismatch'
  [[ "$(fake_aws_calls)" -eq 4 ]] || fail 'R2 publish and restore should take two uploads and two downloads'

  while IFS= read -r call; do
    [[ "${call}" == *"--endpoint-url https://r2.example.invalid"* ]] ||
      fail "aws call did not target the configured endpoint: ${call}"
    [[ "${call}" != *test-key-id* && "${call}" != *test-secret-value* ]] ||
      fail 'R2 credentials leaked into aws argv'
  done < "${FAKE_AWS_LOG}.argv"
  while IFS= read -r call_env; do
    [[ "${call_env}" == 'key=test-key-id secret=test-secret-value region=auto profile=unset session=unset request=WHEN_REQUIRED response=WHEN_REQUIRED' ]] ||
      fail "aws saw unexpected credential environment: ${call_env/test-secret-value/<secret>}"
  done < "${FAKE_AWS_LOG}.env"

  # log may be DL4J's stderr logger, so capture both streams.
  if r2_miss="$(kompile_native_remote_copy "${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/fixture/missing/native-worker" \
      "${TEST_ROOT}/r2-miss" 2>&1)"; then
    fail 'missing R2 object was reported as a hit'
  fi
  [[ "${r2_miss}" == *"(404)"* ]] || fail 'R2 miss did not surface the aws error'
  [[ "$(fake_aws_calls)" -eq 5 ]] || fail 'R2 miss did not query the bucket exactly once'

  # Refused configurations never reach aws, so they cannot sign with bad input.
  KOMPILE_NATIVE_CACHE_REMOTE_SECRET_ACCESS_KEY='test secret'
  if kompile_native_remote_copy "${r2_object}" "${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/refused" >/dev/null 2>&1; then
    fail 'malformed R2 secret was accepted'
  fi
  KOMPILE_NATIVE_CACHE_REMOTE_SECRET_ACCESS_KEY=test-secret-value
  KOMPILE_NATIVE_CACHE_REMOTE_ACCESS_KEY_ID=$'\n'
  if kompile_native_remote_copy "${r2_object}" "${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/refused" >/dev/null 2>&1; then
    fail 'blank R2 access key id was accepted'
  fi
  KOMPILE_NATIVE_CACHE_REMOTE_ACCESS_KEY_ID=test-key-id
  KOMPILE_NATIVE_CACHE_REMOTE_ENDPOINT=
  if kompile_native_remote_copy "${r2_object}" "${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/refused" >/dev/null 2>&1; then
    fail 'R2 copy ran without an endpoint'
  fi
  [[ "$(fake_aws_calls)" -eq 5 ]] || fail 'a refused R2 configuration still invoked aws'
)

REMOTE_STORE="${TEST_ROOT}/remote-cache"
kompile_native_remote_copy() {
  local source="$1"
  local destination="$2"
  local relative
  if [[ "${source}" == "${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/"* ]]; then
    relative="${source#${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/}"
    mkdir -p "${destination}"
    cp "${REMOTE_STORE}/${relative}" "${destination}/$(basename "${source}")"
  elif [[ "${destination}" == "${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/"* ]]; then
    relative="${destination#${KOMPILE_NATIVE_CACHE_REMOTE_ROOT}/}"
    mkdir -p "${REMOTE_STORE}/$(dirname "${relative}")"
    # Blob overwrite replaces the object; it does not write through the old
    # local inode (which inherits read-only cache permissions in this fixture).
    rm -f -- "${REMOTE_STORE}/${relative}"
    cp "${source}" "${REMOTE_STORE}/${relative}"
  else
    return 1
  fi
}

TARGET_IMAGE="${TEST_ROOT}/target/native-worker"
mkdir -p "$(dirname "${TARGET_IMAGE}")"
printf 'verified-native-image-v1' > "${TARGET_IMAGE}"
chmod +x "${TARGET_IMAGE}"
AOT_RECEIPT_KEY="$(printf 'aot-receipt' | kompile_sha256_stdin)"
RUNTIME_RECEIPT_V1="$(printf 'runtime-receipt-v1' | kompile_sha256_stdin)"
RUNTIME_RECEIPT_V2="$(printf 'runtime-receipt-v2' | kompile_sha256_stdin)"

kompile_publish_cached_native_image   fixture "${TARGET_IMAGE}" "${AOT_RECEIPT_KEY}" "${RUNTIME_RECEIPT_V1}"
EXPECTED_IMAGE_SHA="$(kompile_sha256_file "${TARGET_IMAGE}")"
printf 'corrupted-local-target' > "${TARGET_IMAGE}"
chmod +x "${TARGET_IMAGE}"
# Force the restore through the remote backend to prove a fresh worker can
# recover an image after its local cache directory is gone.
rm -rf -- "${KOMPILE_NATIVE_CACHE_DIR}"
mkdir -p "${KOMPILE_NATIVE_CACHE_DIR}"

kompile_restore_cached_native_image   fixture "${TARGET_IMAGE}" "${AOT_RECEIPT_KEY}" "${RUNTIME_RECEIPT_V2}" ||
  fail "AOT cache did not restore across a runtime-only fingerprint change"
[[ "$(kompile_sha256_file "${TARGET_IMAGE}")" == "${EXPECTED_IMAGE_SHA}" ]] ||
  fail "restored AOT artifact checksum mismatch"
kompile_native_validate_cache_receipt   "${TARGET_IMAGE}.native-cache" "${AOT_RECEIPT_KEY}" ||
  fail "restored v3 cache receipt did not validate"
[[ "${KOMPILE_NATIVE_RECEIPT_RUNTIME}" == "${RUNTIME_RECEIPT_V2}" ]] ||
  fail "restored receipt did not advance to the current runtime payload identity"

LEGACY_IMAGE="${TEST_ROOT}/legacy-native-worker"
printf 'legacy-image' > "${LEGACY_IMAGE}"
chmod +x "${LEGACY_IMAGE}"
LEGACY_SHA="$(kompile_sha256_file "${LEGACY_IMAGE}")"
printf '%s %s\n' "${AOT_RECEIPT_KEY}" "${LEGACY_SHA}" > "${LEGACY_IMAGE}.native-cache"
if kompile_native_validate_cache_receipt     "${LEGACY_IMAGE}.native-cache" "${AOT_RECEIPT_KEY}"; then
  fail "legacy v2 sidecar was accepted as a v3 independent-stage receipt"
fi

# Bounded local retention must not change remote retention or target isolation.
KOMPILE_NATIVE_CACHE_RETENTION=2
key_b="$(printf key-b | kompile_sha256_stdin)"
key_c="$(printf key-c | kompile_sha256_stdin)"
kompile_publish_cached_native_image fixture "$TARGET_IMAGE" "$key_b" "$RUNTIME_RECEIPT_V2"
touch -t 202001010000 "$KOMPILE_NATIVE_CACHE_DIR/fixture/$AOT_RECEIPT_KEY/.last-used"
touch -t 202101010000 "$KOMPILE_NATIVE_CACHE_DIR/fixture/$key_b/.last-used"
kompile_restore_cached_native_image fixture "$TARGET_IMAGE" "$AOT_RECEIPT_KEY" "$RUNTIME_RECEIPT_V2"
kompile_publish_cached_native_image other "$TARGET_IMAGE" "$key_b" "$RUNTIME_RECEIPT_V2"
kompile_publish_cached_native_image fixture "$TARGET_IMAGE" "$key_c" "$RUNTIME_RECEIPT_V2"
[[ ! -e "$KOMPILE_NATIVE_CACHE_DIR/fixture/$key_b" &&
   -d "$KOMPILE_NATIVE_CACHE_DIR/fixture/$AOT_RECEIPT_KEY" &&
   -d "$KOMPILE_NATIVE_CACHE_DIR/other/$key_b" &&
   -f "$REMOTE_STORE/fixture/$key_b/native-worker" ]] || fail 'local retention crossed ownership or ignored hit usage'
# An unsuccessful remote lookup may leave an empty key directory. Publishing
# later must repair that empty state instead of permanently disabling the cache.
mkdir -p "$KOMPILE_NATIVE_CACHE_DIR/empty/$key_b"
kompile_publish_cached_native_image empty "$TARGET_IMAGE" "$key_b" "$RUNTIME_RECEIPT_V2" || fail 'empty entry cannot recover'
# Unexpected entry contents prevent pruning, not deletion of unknown data.
printf evidence > "$KOMPILE_NATIVE_CACHE_DIR/fixture/$AOT_RECEIPT_KEY/keep"
kompile_publish_cached_native_image fixture "$TARGET_IMAGE" "$key_b" "$RUNTIME_RECEIPT_V2"
[[ -f "$KOMPILE_NATIVE_CACHE_DIR/fixture/$AOT_RECEIPT_KEY/keep" &&
   -d "$KOMPILE_NATIVE_CACHE_DIR/fixture/$key_c" ]] || fail 'unsafe bucket pruned'
# A valid target remains usable even when its shared entry is unsafe.
kompile_restore_cached_native_image fixture "$TARGET_IMAGE" "$key_b" "$RUNTIME_RECEIPT_V2" || fail 'normal target hit lost'
printf 'PASS: independent Native Image AOT/runtime cache tiers, R2 transport and bounded local retention\n'
