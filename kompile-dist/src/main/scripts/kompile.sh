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

# A transcript-scoped MCP child must leave its HotSpot fatal-error report beside the
# rest of that transcript's diagnostics. Resolve the ID from the explicit MCP argument
# first (the normal injected-chat path), with the environment as a compatibility fallback.
TRANSCRIPT_ID="${KOMPILE_TRANSCRIPT_UUID:-}"
CLI_ARGS=("$@")
for ((i = 0; i < ${#CLI_ARGS[@]}; i++)); do
    if [ "${CLI_ARGS[$i]}" = "--transcript-id" ] && [ $((i + 1)) -lt ${#CLI_ARGS[@]} ]; then
        TRANSCRIPT_ID="${CLI_ARGS[$((i + 1))]}"
        break
    fi
    if [[ "${CLI_ARGS[$i]}" == --transcript-id=* ]]; then
        TRANSCRIPT_ID="${CLI_ARGS[$i]#--transcript-id=}"
        break
    fi
done

if [ -z "${TRANSCRIPT_ID}" ] && [ "${CLI_ARGS[0]:-}" = "mcp-stdio" ]; then
    if command -v uuidgen >/dev/null 2>&1; then
        TRANSCRIPT_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
    elif [ -r /proc/sys/kernel/random/uuid ]; then
        IFS= read -r TRANSCRIPT_ID < /proc/sys/kernel/random/uuid
    else
        printf -v TRANSCRIPT_ID '%08x-%04x-4%03x-a%03x-%04x%04x%04x' \
            "$(((RANDOM << 16) | RANDOM))" "$RANDOM" "$RANDOM" \
            "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM"
    fi
    CLI_ARGS+=("--transcript-id" "${TRANSCRIPT_ID}")
fi

JAVA_ERROR_ARGS=()
if [ -n "${TRANSCRIPT_ID}" ]; then
    if [[ "${TRANSCRIPT_ID}" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]]; then
        SAFE_TRANSCRIPT_ID="${TRANSCRIPT_ID,,}"
    else
        TRANSCRIPT_HEX="$(printf '%s' "${TRANSCRIPT_ID}" \
            | od -An -v -tx1 | tr -d ' \n')"
        SAFE_TRANSCRIPT_ID="~${TRANSCRIPT_HEX}"
    fi
    TRANSCRIPT_MCP_LOG_DIR="${HOME}/.kompile/logs/transcripts/${SAFE_TRANSCRIPT_ID}/mcp"
    mkdir -p "${TRANSCRIPT_MCP_LOG_DIR}"
    JAVA_ERROR_ARGS+=("-XX:ErrorFile=${TRANSCRIPT_MCP_LOG_DIR}/hs_err_pid%p.log")
    JVM_STDERR_LOG="${TRANSCRIPT_MCP_LOG_DIR}/jvm-stderr.log"
    if [ -f "${JVM_STDERR_LOG}" ] \
            && [ "$(wc -c < "${JVM_STDERR_LOG}")" -gt 5242880 ]; then
        mv -f "${JVM_STDERR_LOG}" "${JVM_STDERR_LOG}.1"
    fi
fi

if [ -n "${TRANSCRIPT_ID}" ]; then
    # Capture failures that happen before McpStdioCommand can install its Java-level
    # stderr sink (for example NoClassDefFoundError while loading the command itself).
    exec "${JAVA_BIN}" \
        "${JAVA_ERROR_ARGS[@]}" \
        -Dfile.encoding=UTF-8 \
        -Dkompile.dist.home="${DIST_HOME}" \
        -jar "${CLI_JAR}" \
        "${CLI_ARGS[@]}" \
        2>>"${JVM_STDERR_LOG}"
fi

exec "${JAVA_BIN}" \
    "${JAVA_ERROR_ARGS[@]}" \
    -Dfile.encoding=UTF-8 \
    -Dkompile.dist.home="${DIST_HOME}" \
    -jar "${CLI_JAR}" \
    "${CLI_ARGS[@]}"
