#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOMPILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

# A deterministic cygpath shim lets Linux CI exercise the exact Windows/MSYS
# namespace conversions used by the Azure worker.
cygpath() {
  local mode="${1:-}" value suffix
  shift || true
  [ "${1:-}" = "--" ] && shift
  value="${1:-}"
  local win_output='C:\k\output'
  local win_cache='C:\k\cache'
  local win_repo='C:\k\m2'
  local win_assets='C:\Windows\TEMP\assets'
  local posix_output="${TEST_ROOT}/output"
  local posix_cache="${TEST_ROOT}/cache"
  local posix_repo="${TEST_ROOT}/m2"
  local posix_assets="${TEST_ROOT}/assets"
  case "${mode}" in
    -u)
      if [[ "${value}" == "${win_output}"* ]]; then
        suffix="${value#"${win_output}"}"; printf '%s/output%s\n' "${TEST_ROOT}" "${suffix//\\//}"
      elif [[ "${value}" == "${win_cache}"* ]]; then
        suffix="${value#"${win_cache}"}"; printf '%s/cache%s\n' "${TEST_ROOT}" "${suffix//\\//}"
      elif [[ "${value}" == "${win_repo}"* ]]; then
        suffix="${value#"${win_repo}"}"; printf '%s/m2%s\n' "${TEST_ROOT}" "${suffix//\\//}"
      elif [[ "${value}" == "${win_assets}"* ]]; then
        suffix="${value#"${win_assets}"}"; printf '%s/assets%s\n' "${TEST_ROOT}" "${suffix//\\//}"
      else
        printf '%s\n' "${value}"
      fi
      ;;
    -w)
      if [[ "${value}" == "${posix_output}"* ]]; then
        suffix="${value#"${posix_output}"}"; printf 'C:\\k\\output%s\n' "${suffix//\//\\}"
      elif [[ "${value}" == "${posix_cache}"* ]]; then
        suffix="${value#"${posix_cache}"}"; printf 'C:\\k\\cache%s\n' "${suffix//\//\\}"
      elif [[ "${value}" == "${posix_repo}"* ]]; then
        suffix="${value#"${posix_repo}"}"; printf 'C:\\k\\m2%s\n' "${suffix//\//\\}"
      elif [[ "${value}" == "${posix_assets}"* ]]; then
        suffix="${value#"${posix_assets}"}"; printf 'C:\\Windows\\TEMP\\assets%s\n' "${suffix//\//\\}"
      else
        printf '%s\n' "${value}"
      fi
      ;;
    *) return 1 ;;
  esac
}
export -f cygpath
OSTYPE=cygwin
MSYSTEM=MSYS

GRAALVM_HOME="${TEST_ROOT}/graalvm"
DL4J_PROJECT_ROOT="${TEST_ROOT}/not-a-dl4j-checkout"
KOMPILE_OUTPUT_DIR='C:\k\output\windows-x86_64-compile'
KOMPILE_SDX_OUTPUT_DIR='C:\k\output\windows-x86_64-compile\sdx-sdk'
KOMPILE_NATIVE_CACHE_DIR='C:\k\cache'
KOMPILE_NATIVE_DEPENDENCY_MANIFEST_CACHE_DIR='C:\k\cache\dependency-manifests'
DL4J_SDX_ASSETS_DIR='C:\Windows\TEMP\assets'
MAVEN_REPO_LOCAL='C:\k\m2'
MVN=true
export GRAALVM_HOME DL4J_PROJECT_ROOT KOMPILE_OUTPUT_DIR KOMPILE_SDX_OUTPUT_DIR \
  KOMPILE_NATIVE_CACHE_DIR KOMPILE_NATIVE_DEPENDENCY_MANIFEST_CACHE_DIR \
  DL4J_SDX_ASSETS_DIR MAVEN_REPO_LOCAL MVN KOMPILE_ROOT

# shellcheck source=build-common.sh
source "${SCRIPT_DIR}/build-common.sh"

[[ "${KOMPILE_OUTPUT_DIR}" == "${TEST_ROOT}/output/windows-x86_64-compile" ]] ||
  fail "output directory remained in the native Windows namespace"
[[ "${KOMPILE_SDX_OUTPUT_DIR}" == "${TEST_ROOT}/output/windows-x86_64-compile/sdx-sdk" ]] ||
  fail "SDX output directory remained in the native Windows namespace"
[[ "${KOMPILE_NATIVE_CACHE_DIR}" == "${TEST_ROOT}/cache" ]] ||
  fail "native cache directory remained in the native Windows namespace"
[[ "${KOMPILE_NATIVE_DEPENDENCY_MANIFEST_CACHE_DIR}" == "${TEST_ROOT}/cache/dependency-manifests" ]] ||
  fail "dependency-manifest cache directory remained in the native Windows namespace"
[[ "${DL4J_SDX_ASSETS_DIR}" == "${TEST_ROOT}/assets" ]] ||
  fail "DL4J SDK assets directory remained in the native Windows namespace"
[[ "${MAVEN_REPO_LOCAL}" == 'C:\k\m2' ]] ||
  fail "MAVEN_REPO_LOCAL was changed before a native Maven invocation"
[[ "$(kompile_maven_repository_posix)" == "${TEST_ROOT}/m2" ]] ||
  fail "Maven repository shell boundary did not convert the native path"
[[ "$(kompile_path_to_native "${KOMPILE_OUTPUT_DIR}")" == 'C:\k\output\windows-x86_64-compile' ]] ||
  fail "POSIX output path did not convert back for a native process"

printf 'PASS: Windows/MSYS path boundaries for output, cache, SDX, and Maven\n'
