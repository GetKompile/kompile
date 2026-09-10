#!/usr/bin/env bash
# Shared Windows/MSYS path boundary helpers.
#
# Source this file from Bash entrypoints that may be launched by native Windows
# Python/PowerShell. Native tools receive native paths; shell-owned operations
# stay in the POSIX namespace visible to MSYS2/Git Bash.

kompile_windows_shell() {
  case "${OSTYPE:-}:${MSYSTEM:-}" in
    cygwin*|msys*|win32*|*:MSYS*|*:MINGW*) return 0 ;;
    *) return 1 ;;
  esac
}

kompile_path_to_posix() {
  local path="${1:-}"
  if kompile_windows_shell && command -v cygpath >/dev/null 2>&1; then
    cygpath -u -- "${path}"
  else
    printf '%s\n' "${path}"
  fi
}

kompile_path_to_native() {
  local path="${1:-}"
  if kompile_windows_shell && command -v cygpath >/dev/null 2>&1; then
    cygpath -w -- "${path}"
  else
    printf '%s\n' "${path}"
  fi
}

# Convert variables used by shell operations in-place. Do not include
# MAVEN_REPO_LOCAL: it remains native because it is passed to Maven/Java.
kompile_normalize_shell_paths() {
  local variable value
  for variable in \
    GRAALVM_HOME \
    JAVA_HOME \
    KOMPILE_JAVA \
    KOMPILE_OUTPUT_DIR \
    KOMPILE_SDX_OUTPUT_DIR \
    KOMPILE_NATIVE_CACHE_DIR \
    KOMPILE_NATIVE_DEPENDENCY_MANIFEST_CACHE_DIR \
    DL4J_SDX_ASSETS_DIR \
    KOMPILE_ACTIVE_SDX_ASSETS_DIR; do
    value="${!variable-}"
    [ -n "${value}" ] || continue
    value="$(kompile_path_to_posix "${value}")" || return 1
    printf -v "${variable}" '%s' "${value}"
  done
}

# Return the local Maven repository in the shell namespace. Native Maven
# invocations must continue using MAVEN_REPO_LOCAL unchanged.
kompile_maven_repository_posix() {
  local repository="${1:-${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}}"
  kompile_path_to_posix "${repository}"
}
