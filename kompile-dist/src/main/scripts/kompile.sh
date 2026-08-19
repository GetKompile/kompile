#!/usr/bin/env bash
#
# Launch the Kompile CLI uber JAR in JVM mode. Prefer an explicitly configured
# or system Java runtime; retain the bundled runtime only for self-contained
# installations without a usable JVM.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")")" && pwd)"
DIST_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"
CLI_JAR="${DIST_HOME}/lib/kompile-cli.jar"

if [ ! -f "${CLI_JAR}" ]; then
    echo "error: Kompile CLI uber JAR not found: ${CLI_JAR}" >&2
    exit 1
fi

# Delegated JVM CLI components use this root to find sibling JARs in lib/.
export KOMPILE_INSTALL_DIR="${KOMPILE_INSTALL_DIR:-${DIST_HOME}}"
export KOMPILE_DIST_HOME="${KOMPILE_DIST_HOME:-${DIST_HOME}}"
export LD_LIBRARY_PATH="${DIST_HOME}/bin:${DIST_HOME}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export DYLD_LIBRARY_PATH="${DIST_HOME}/bin:${DIST_HOME}/lib${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}"

if [ -x "${KOMPILE_JAVA:-}" ]; then
    JAVA_BIN="${KOMPILE_JAVA}"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
elif [ -x "${DIST_HOME}/runtime/bin/java" ]; then
    JAVA_BIN="${DIST_HOME}/runtime/bin/java"
else
    echo "error: no Java runtime found. Install Java 17+, set JAVA_HOME, or rebuild the dist" >&2
    exit 1
fi

# Child JVM launchers inherit this selection (for example, the local chat server
# started while resuming a standard conversation). Prefer a working system JVM and
# retain the bundled runtime only as the final fallback for self-contained installs.
export KOMPILE_JAVA="${KOMPILE_JAVA:-${JAVA_BIN}}"

exec "${JAVA_BIN}" \
    -Dfile.encoding=UTF-8 \
    -Dkompile.dist.home="${DIST_HOME}" \
    -jar "${CLI_JAR}" \
    "$@"
