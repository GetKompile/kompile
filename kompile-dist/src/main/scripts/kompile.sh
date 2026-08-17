#!/usr/bin/env bash
#
# Launch the Kompile CLI uber JAR. JBang is preferred when installed; the
# bundled/system Java fallback keeps the same wrapper usable in CI and minimal
# installations.

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

if command -v jbang >/dev/null 2>&1; then
    exec jbang --java=17 "${CLI_JAR}" "$@"
fi

if [ -x "${KOMPILE_JAVA:-}" ]; then
    JAVA_BIN="${KOMPILE_JAVA}"
elif [ -x "${DIST_HOME}/runtime/bin/java" ]; then
    JAVA_BIN="${DIST_HOME}/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="java"
else
    echo "error: no Java runtime found. Install JBang or Java 17+, set JAVA_HOME, or rebuild the dist" >&2
    exit 1
fi

exec "${JAVA_BIN}" \
    -Dfile.encoding=UTF-8 \
    -Dkompile.dist.home="${DIST_HOME}" \
    -jar "${CLI_JAR}" \
    "$@"
