#!/usr/bin/env bash
# Start the three packaged JVM personas against one temporary project and exercise their shared
# readiness, routing, and H2 state contract. This is intentionally a Linux/JVM smoke; native and
# browser/CORS qualification belong to the platform and nightly lanes.
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "usage: $0 <distribution-root> [java-executable]" >&2
    exit 2
fi

DIST_ROOT="$(cd "$1" && pwd)"
JAVA_BIN="${2:-java}"
TMP_DIR="$(mktemp -d)"
PROJECT_DIR="${TMP_DIR}/project"
LOG_DIR="${TMP_DIR}/logs"
mkdir -p "${PROJECT_DIR}/config" "${LOG_DIR}"

PIDS=()
cleanup() {
    for pid in "${PIDS[@]:-}"; do
        kill "${pid}" 2>/dev/null || true
    done
    for pid in "${PIDS[@]:-}"; do
        wait "${pid}" 2>/dev/null || true
    done
    if [ "${KEEP_PERSONA_SMOKE_DIR:-false}" = true ]; then
        echo "Preserving persona smoke directory: ${TMP_DIR}" >&2
    else
        rm -rf "${TMP_DIR}"
    fi
}
trap cleanup EXIT

for required in \
        "${DIST_ROOT}/lib/kompile-server.jar" \
        "${DIST_ROOT}/lib/kompile-chat.jar" \
        "${DIST_ROOT}/lib/kompile-crawl-manager.jar"; do
    [ -f "${required}" ] || {
        echo "ERROR: packaged persona JAR is missing: ${required}" >&2
        exit 1
    }
done

# Boot 3.2's default loader fails on CUDA JARs above 2 GiB (spring-boot#42012).
# Check the packaged loader even on smaller CPU fixtures, before starting any JVM.
python3 - "${DIST_ROOT}" <<'PY'
import pathlib
import sys
import zipfile

for name in ("kompile-server", "kompile-chat", "kompile-crawl-manager"):
    path = pathlib.Path(sys.argv[1]) / "lib" / (name + ".jar")
    with zipfile.ZipFile(path) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        manifest = manifest.replace("\r\n", "\n").replace("\n ", "")
        attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
        launcher = "org.springframework.boot.loader.JarLauncher"
        if attributes.get("Main-Class") != launcher:
            raise SystemExit(f"ERROR: {name} must use the classic Boot loader for large JARs")
        if launcher.replace(".", "/") + ".class" not in jar.namelist():
            raise SystemExit(f"ERROR: {name} is missing its classic Boot launcher class")
    print(f"{name}: classic Boot loader verified")
PY

# Keep this smoke self-contained. The graph subprocess is a separate child per persona and is
# qualified in its own lifecycle test; disabling it here avoids three large native children while
# still exercising all Spring/MVC/JPA wiring in the packaged applications.
cat > "${PROJECT_DIR}/config/subprocess-ingest-config.json" <<'JSON'
{
  "enabled": false,
  "subprocessTypes": {
    "graph-matrix": {"enabled": false},
    "ingest": {"enabled": false},
    "vector-population": {"enabled": false},
    "embedding": {"enabled": false},
    "model-init": {"enabled": false}
  }
}
JSON

start_persona() {
    local name="$1"
    local jar="$2"
    local port="$3"
    local log_file="${LOG_DIR}/${name}.log"
    echo "Starting ${name} on port ${port}"
    KOMPILE_DIST_HOME="${DIST_ROOT}" \
    KOMPILE_PROJECT_ROOT="${PROJECT_DIR}" \
    LD_LIBRARY_PATH="${DIST_ROOT}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}" \
    "${JAVA_BIN}" -Xmx1g -jar "${jar}" \
        --server.port="${port}" \
        --kompile.data.dir="${PROJECT_DIR}" \
        --kompile.project.root="${PROJECT_DIR}" \
        --kompile.runtime.force-halt-on-shutdown=false \
        --kompile.staging.auto-start=false \
        --kompile.models.auto-init.enabled=false \
        --spring.quartz.auto-startup=false \
        --spring.main.banner-mode=off \
        >"${log_file}" 2>&1 &
    PIDS+=("$!")
}

wait_for_status() {
    local name="$1"
    local port="$2"
    local path="$3"
    local deadline=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt "${deadline}" ]; do
        local status
        status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
            "http://127.0.0.1:${port}${path}" 2>/dev/null || true)"
        if [ "${status}" = "200" ]; then
            echo "${name} ready: ${path}"
            return 0
        fi
        local pid_index
        case "${name}" in
            admin) pid_index=0 ;;
            chat) pid_index=1 ;;
            crawl) pid_index=2 ;;
        esac
        if ! kill -0 "${PIDS[${pid_index}]}" 2>/dev/null; then
            echo "ERROR: ${name} exited before readiness" >&2
            cat "${LOG_DIR}/${name}.log" >&2 || true
            return 1
        fi
        sleep 2
    done
    echo "ERROR: ${name} did not become ready: ${path}" >&2
    cat "${LOG_DIR}/${name}.log" >&2 || true
    return 1
}

assert_status() {
    local name="$1"
    local port="$2"
    local path="$3"
    local expected="$4"
    local actual
    actual="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
        "http://127.0.0.1:${port}${path}" 2>/dev/null || true)"
    if [ "${actual}" != "${expected}" ]; then
        echo "ERROR: ${name} ${path}: expected HTTP ${expected}, got ${actual}" >&2
        exit 1
    fi
    echo "${name} ${path}: HTTP ${actual}"
}

start_persona admin "${DIST_ROOT}/lib/kompile-server.jar" 19080
wait_for_status admin 19080 /api/setup/status
start_persona chat "${DIST_ROOT}/lib/kompile-chat.jar" 19081
wait_for_status chat 19081 /api/agents/chat/health
start_persona crawl "${DIST_ROOT}/lib/kompile-crawl-manager.jar" 19082
wait_for_status crawl 19082 /api/unified-crawl/jobs/active

# Shared readiness/config contract.
assert_status admin 19080 /api/setup/status 200
assert_status chat 19081 /api/setup/status 200
assert_status crawl 19082 /api/setup/status 200
assert_status admin 19080 /api/agents/chat/health 404
assert_status admin 19080 /api/unified-crawl/jobs/active 404
assert_status chat 19081 /api/unified-crawl/jobs/active 404
assert_status chat 19081 /api/agents/chat/health 200
assert_status crawl 19082 /api/agents/chat/health 404
assert_status crawl 19082 /api/unified-crawl/jobs/active 200

# Write through Crawl and read the same H2-backed fact-sheet state through the other personas.
FACT_SHEET="persona-stack-$$"
CREATE_RESPONSE="${TMP_DIR}/create-fact-sheet.json"
CREATE_STATUS="$(curl -sS -o "${CREATE_RESPONSE}" -w '%{http_code}' --max-time 20 \
    -X POST "http://127.0.0.1:19082/api/fact-sheets" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"${FACT_SHEET}\",\"description\":\"packaged persona stack smoke\"}" || true)"
if [ "${CREATE_STATUS}" != "201" ]; then
    echo "ERROR: Crawl fact-sheet creation returned HTTP ${CREATE_STATUS}" >&2
    cat "${CREATE_RESPONSE}" >&2 || true
    exit 1
fi

for port in 19081 19080; do
    if ! curl -sS --fail --max-time 20 "http://127.0.0.1:${port}/api/fact-sheets" \
            | grep -Fq "${FACT_SHEET}"; then
        echo "ERROR: fact sheet ${FACT_SHEET} was not visible through port ${port}" >&2
        exit 1
    fi
done

echo "persona JAR stack tests passed"
