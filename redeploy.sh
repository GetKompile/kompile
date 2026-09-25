#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Kompile Redeploy — one entry point
#
#   ./redeploy.sh                          # installed lane, CLI jars only
#   ./redeploy.sh cuda-12.9                # switch lane, CLI jars only
#   ./redeploy.sh --chat --serving         # installed lane, add chat/serving
#   ./redeploy.sh --all                    # cli + chat + serving
#   ./redeploy.sh --check                  # dry-run: show plan, build nothing
#   ./redeploy.sh --skip-build             # deploy from existing target/ jars
#   ./redeploy.sh -Dtest=FooTest           # run focused tests instead of skipping them
#
# WHAT IT DOES
# 1. Selects the lane: explicit backend argument, else reads
#    ~/.kompile/.dist-info.json (backendProfile), else cpu.
# 2. Builds ONLY the reactor modules that back the requested jars (-pl … -am),
#    producing shaded/exec artifacts in target/ — no dist archive, no ~/.m2
#    classified-zip accumulation, no native images, no jlink runtime.
# 3. Validates each artifact (zip integrity, Main-Class + third-party deps for
#    the CLI uber jar, exact backend lane inside the jar) and refuses to touch
#    the install on any mismatch.
# 4. Atomically swaps each jar (same-dir temp + rename(2)) with a timestamped
#    /tmp backup, so running sessions keep the old inode and stay alive.
# 5. Purges ~/.kompile/lib/.boot-inf-extracted (stale subprocess classpaths go
#    silently stale after a jar swap — AGENTS.md runtime rule).
# 6. Runs `kompile doctor` for a post-install health summary.
#
# SCOPE
# Jar tier only. Native images and full-distribution builds stay with
# build-dist.sh + install.sh (see /kompile-dist-setup).
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "${SCRIPT_DIR}" && pwd)"

# Platform classifier (same convention as build-dist.sh).
case "$(uname -s)" in
    Linux*)  OS="linux" ;;
    Darwin*) OS="macosx" ;;
    *)       OS="windows" ;;
esac
case "$(uname -m)" in
    x86_64|amd64)   ARCH="x86_64" ;;
    aarch64|arm64)  ARCH="arm64" ;;
    *)              ARCH="$(uname -m)" ;;
esac
PLATFORM="${OS}-${ARCH}"

# ── Defaults ─────────────────────────────────────────────────────────────────

MVN="${MVN:-${HOME}/dev-apps/mvn/bin/mvn}"
INSTALL_DIR="${KOMPILE_INSTALL_DIR:-${HOME}/.kompile}"
BACKEND_ARG=""
DO_CLI=true
DO_CHAT=false
DO_SERVING=false
SKIP_BUILD=false
DRY_RUN=false
ALLOW_LANE_SWITCH=false
EXTRA_MVN_ARGS=()
DIST_INFO="${INSTALL_DIR}/.dist-info.json"

usage() {
    grep '^#   ' "$0" | cut -c6- || true
    exit "${1:-0}"
}

# ── Argument parsing ─────────────────────────────────────────────────────────

while [ $# -gt 0 ]; do
    case "$1" in
        --cli)          DO_CLI=true; shift ;;
        --chat)         DO_CHAT=true; shift ;;
        --serving)      DO_SERVING=true; shift ;;
        --all)          DO_CLI=true; DO_CHAT=true; DO_SERVING=true; shift ;;
        --skip-build)   SKIP_BUILD=true; shift ;;
        --check)        DRY_RUN=true; shift ;;
        --allow-lane-switch) ALLOW_LANE_SWITCH=true; shift ;;
        -D*)            EXTRA_MVN_ARGS+=("$1"); shift ;;
        -h|--help)      usage 0 ;;
        --*)            echo "Unknown option: $1" >&2; usage 1 ;;
        -*)             echo "Unknown option: $1" >&2; usage 1 ;;
        *)
            if [ -z "${BACKEND_ARG}" ]; then
                BACKEND_ARG="$1"
            else
                echo "Unexpected positional argument: $1" >&2
                usage 1
            fi
            shift
            ;;
    esac
done

if [ ! -x "${MVN}" ]; then
    echo "ERROR: maven not found at ${MVN} (override with MVN=…)" >&2
    exit 1
fi

# ── Lane selection ───────────────────────────────────────────────────────────

# Explicit backend wins. Otherwise the installed dist tells us the truth: this
# script must NEVER build a lane the launcher would refuse to run.
if [ -n "${BACKEND_ARG}" ]; then
    BACKEND_PROFILE="${BACKEND_ARG}"
elif [ -f "${DIST_INFO}" ]; then
    BACKEND_PROFILE="$(sed -n 's/.*"backendProfile"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${DIST_INFO}" | head -1)"
    if [ "${BACKEND_PROFILE}" = "none" ]; then
        BACKEND_PROFILE=""
    fi
    if [ -z "${BACKEND_PROFILE}" ]; then
        echo "ERROR: ${DIST_INFO} has backendProfile=none and no backend argument was given." >&2
        echo "       Pass one explicitly: ./redeploy.sh cpu|cuda-12.9|cuda-13.1|… [--all]" >&2
        exit 1
    fi
else
    BACKEND_PROFILE=""
    echo "NOTE: no backend argument and no ${DIST_INFO} — defaulting to cpu (nd4j-native)." >&2
fi

case "${BACKEND_PROFILE}" in
    ""|cpu)            ND4J_BACKEND="nd4j-native" ;;
    cpu-*)             ND4J_BACKEND="nd4j-native" ;;
    cuda-12.6)         ND4J_BACKEND="nd4j-cuda-12.6" ;;
    cuda-12.9)         ND4J_BACKEND="nd4j-cuda-12.9" ;;
    cuda-13.1)         ND4J_BACKEND="nd4j-cuda-13.1" ;;
    zluda)             ND4J_BACKEND="nd4j-zluda" ;;
    zluda-rocm-*)      ND4J_BACKEND="nd4j-zluda-12.9" ;;
    vulkan*)           ND4J_BACKEND="nd4j-vulkan" ;;
    hexagon)           ND4J_BACKEND="nd4j-hexagon" ;;
    tpu)               ND4J_BACKEND="nd4j-tpu" ;;
    *)
        echo "ERROR: unsupported backend profile: ${BACKEND_PROFILE}" >&2
        echo "       Valid: cpu[-<flavor>], cuda-12.6, cuda-12.9, cuda-13.1, zluda, zluda-rocm-7.2.4, vulkan, hexagon, tpu" >&2
        exit 1
        ;;
esac

# When the caller picks an explicit lane, refuse to overlay a differently-laned
# installation by default (mixed lanes in ~/.kompile/lib are how CUDA jars end
# up under a CPU launcher). Use --allow-lane-switch to accept it.
LANE_SWITCH=false
if [ -f "${DIST_INFO}" ] && [ -n "${BACKEND_ARG}" ]; then
    INSTALLED_PROFILE="$(sed -n 's/.*"backendProfile"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${DIST_INFO}" | head -1)"
    if [ -n "${INSTALLED_PROFILE}" ] && [ "${INSTALLED_PROFILE}" != "none" ] \
            && [ "${INSTALLED_PROFILE}" != "${BACKEND_PROFILE}" ]; then
        if [ "${ALLOW_LANE_SWITCH}" = true ]; then
            echo "NOTE: switching lane ${INSTALLED_PROFILE} → ${BACKEND_PROFILE} (--allow-lane-switch); module target/ dirs will be rebuilt from scratch." >&2
        else
            LANE_SWITCH=true
        fi
    fi
fi

# ── Scope selection ──────────────────────────────────────────────────────────

# Jar manifest: module → target artifact pattern → installed lib name → needs backend closure.
# CLI jars are shaded; chat/serving are Spring Boot exec jars.
declare -a JAR_SPECS=()
if [ "${DO_CLI}" = true ]; then
    JAR_SPECS+=(
        "kompile-cli/kompile-cli-main|*-shaded.jar|kompile-cli.jar|cli"
        "kompile-cli/kompile-agent-cli/target|*-shaded.jar|kompile-agent.jar|cli"
        "kompile-cli/kompile-app-cli/target|*-shaded.jar|kompile-app-cli.jar|cli"
        "kompile-cli/kompile-model-cli/target|*-shaded.jar|kompile-model.jar|cli"
        "kompile-cli/kompile-component-cli/target|*-shaded.jar|kompile-component.jar|cli"
    )
fi
if [ "${DO_CHAT}" = true ]; then
    JAR_SPECS+=(
        "kompile-app/kompile-app-parent/kompile-app-chat/target|*-exec.jar|kompile-chat.jar|server"
    )
fi
if [ "${DO_SERVING}" = true ]; then
    JAR_SPECS+=(
        "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving/target|*-exec.jar|kompile-model-serving.jar|serving"
        "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target|*-exec.jar|kompile-pipeline-serving.jar|serving"
    )
fi

if [ "${#JAR_SPECS[@]}" -eq 0 ]; then
    echo "Nothing selected to redeploy (scope flags empty after parsing)." >&2
    exit 1
fi

echo "Redeploy plan"
echo "  Repo:        ${REPO}"
echo "  Install:     ${INSTALL_DIR}"
echo "  Backend:     ${BACKEND_PROFILE:-cpu (none)}  →  ${ND4J_BACKEND:-<no nd4j>}"
[ "${LANE_SWITCH}" = true ] && echo "  ⚠ lane switch: installed lane is ${INSTALLED_PROFILE}; pass --allow-lane-switch if intended" && exit 1
echo "  Scope:       cli=$DO_CLI chat=$DO_CHAT serving=$DO_SERVING"
echo ""

# ── Module derivation ────────────────────────────────────────────────────────

# build-only-what's-needed: -pl each backing module, -am for its closure. The
# CLI uber jar is a -pl + shade build; sibling CLIs and exec jars come along
# because their backing modules are listed explicitly.
MODULES=""
for spec in "${JAR_SPECS[@]}"; do
    IFS='|' read -r module_dir pattern lib_name kind <<<"${spec}"
    module_dir="${module_dir%/target}"
    if [ -n "${MODULES}" ]; then MODULES+=","; fi
    MODULES+="${module_dir}"
done

# ── Build ────────────────────────────────────────────────────────────────────

MAVEN_LANE_ARGS=(
    "-Dnd4j.backend=${ND4J_BACKEND}"
    "-Dkompile.backend=${BACKEND_PROFILE}"
    "-Djavacpp.platform=${PLATFORM}"
)

# A lane change (explicit backend ≠ installed backend) must not reuse target/
# artifacts from the old lane — that is exactly the stale-CUDA-under-CPU trap.
# Same-lane refreshes keep target/ for Maven's incremental compile; we never run
# `mvn clean` implicitly (memory rule: iterative native builds must not clean).
if [ "${LANE_SWITCH}" = true ] || [ "${ALLOW_LANE_SWITCH}" = true ]; then
    for spec in "${JAR_SPECS[@]}"; do
        IFS='|' read -r module_dir pattern lib_name kind <<<"${spec}"
        module_dir="${module_dir%/target}"
        if [ -d "${REPO}/${module_dir}/target" ]; then
            echo "  lane change: removing ${module_dir}/target"
            rm -rf "${REPO}/${module_dir}/target"
        fi
    done
fi

if [ "${SKIP_BUILD}" = false ]; then
    echo "── Building (only the modules backing the requested jars) ────────"
    # install (not package): the -am closure must land in ~/.m2 so future scoped
    # module tests resolve fresh artifacts (stale-m2 NoSuchMethodError trap).
    BUILD_CMD=("${MVN}" -B -pl "${MODULES}" -am install "${MAVEN_LANE_ARGS[@]}" "${EXTRA_MVN_ARGS[@]}")
    if [ "${#EXTRA_MVN_ARGS[@]}" -eq 0 ]; then
        BUILD_CMD+=(-DskipTests)
    else
        echo "  NOTE: user-supplied -D flags override default -DskipTests — tests will run."
    fi
    printf '  '; printf ' %q' "${BUILD_CMD[@]}"; printf '\n\n'
    if [ "${DRY_RUN}" = true ]; then
        echo "  (--check: plan only, nothing built)"
        exit 0
    fi
    (cd "${REPO}" && "${BUILD_CMD[@]}")
    echo ""
    echo "  ✓ Build complete"
else
    echo "── Build skipped (--skip-build) — deploying from existing target/ ──"
    if [ "${DRY_RUN}" = true ]; then
        echo "  (--check: plan only, nothing deployed)"
        exit 0
    fi
fi

# ── Artifact resolution + validation ────────────────────────────────────────

# Resolve the newest non-source jar matching a pattern; skip original-*
# (pre-shade copy) and -sources/-javadoc. Mirrors build-dist.sh/dev_pick_jar.
pick_newest_jar() {
    local dir="$1" pattern="$2" best="" f
    for f in "${dir}"/${pattern}; do
        [ -f "$f" ] || continue
        case "$f" in *original-*|*-sources.jar|*-javadoc.jar|*-tests.jar) continue ;; esac
        if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best="$f"; fi
    done
    # Always exit 0 (empty output = not found): under set -e a non-zero here
    # would abort the caller's assignment before its -z fallback can run.
    if [ -n "$best" ]; then printf '%s' "$best"; fi
    return 0
}

# The artifact must carry the lane's numerical backend. Layouts differ: Spring
# Boot exec jars nest the backend at BOOT-INF/lib/<backend>-<version>.jar;
# shaded CLI jars fuse it at org/nd4j/linalg/<backend-specific-package>/, so a
# versioned-path grep would reject a CORRECT shaded jar. Both probe the exact
# backend-identifying path (nd4j-cuda vs nativecpu nativeblas) — the stale-CUDA-
# under-CPU trap this guard exists for.
#
# Delegated sibling CLIs (agent/app-cli/model/component, ~500 entries) bundle NO
# nd4j at all — the main CLI uber jar carries the whole closure and dispatches
# to them — so they are lane-agnostic and skip the probe. A jar that DOES carry
# nd4j must carry the RIGHT lane.
jar_matches_backend() {
    local jar="$1"
    [ -n "${ND4J_BACKEND}" ] || return 0
    local nd4j_entries
    nd4j_entries=$(unzip -l "${jar}" 2>/dev/null | grep -c "nd4j" || true)
    [ "${nd4j_entries:-0}" -gt 0 ] || return 0
    case "${ND4J_BACKEND}" in
        nd4j-native)
            unzip -l "${jar}" 2>/dev/null | grep "org/nd4j/linalg/cpu/nativecpu/" >/dev/null ;;
        nd4j-cuda-*|nd4j-zluda*)
            unzip -l "${jar}" 2>/dev/null | grep -E "org/nd4j/linalg/jcublas/|BOOT-INF/lib/${ND4J_BACKEND}" >/dev/null ;;
        nd4j-vulkan)
            unzip -l "${jar}" 2>/dev/null | grep -E "org/nd4j/linalg/vulkan/|BOOT-INF/lib/${ND4J_BACKEND}" >/dev/null ;;
        *)
            unzip -l "${jar}" 2>/dev/null | grep "BOOT-INF/lib/${ND4J_BACKEND}" >/dev/null ;;
    esac
}

# Thin-jar guard (launcher execs `java -jar`): CLI uber jar must carry a
# Main-Class and third-party deps. Two independent signals, per the 2026-09-16
# thin-jar bricking incident.
jar_is_executable_uber() {
    local jar="$1" main_class third_party
    main_class=$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | grep -c '^Main-Class:' || true)
    third_party=$(unzip -l "$jar" 2>/dev/null | grep -cE 'info/picocli/|ch/qos/logback/' || true)
    [ "${main_class:-0}" -ge 1 ] && [ "${third_party:-0}" -ge 1 ]
}

echo "── Resolving + validating artifacts ──────────────────────────────"
declare -a PLAN=()
for spec in "${JAR_SPECS[@]}"; do
    IFS='|' read -r module_dir pattern lib_name kind <<<"${spec}"
    module_dir="${module_dir%/target}"
    jar="$(pick_newest_jar "${REPO}/${module_dir}/target" "${pattern}")"
    if [ -z "${jar}" ]; then
        # Fall back to any distributable jar (some modules shade in place).
        jar="$(pick_newest_jar "${REPO}/${module_dir}/target" "*.jar")"
    fi
    if [ -z "${jar}" ]; then
        echo "  ERROR: no jar found in ${module_dir}/target for ${lib_name} — build first" >&2
        exit 1
    fi

    if ! unzip -t -qq "${jar}" >/dev/null 2>&1; then
        echo "  ERROR: ${jar} failed zip integrity — refusing to deploy" >&2
        exit 1
    fi

    if [ "${kind}" = "cli" ]; then
        if ! jar_is_executable_uber "${jar}"; then
            echo "  ERROR: ${jar} looks like a THIN jar (no Main-Class / third-party deps)" >&2
            echo "         refusing to deploy — the launcher needs the SHADED uber-jar" >&2
            exit 1
        fi
    fi

    if ! jar_matches_backend "${jar}"; then
        echo "  ERROR: ${jar} does not contain ${ND4J_BACKEND} (built on another lane?)" >&2
        echo "         rerun without --skip-build so the lane flags reach Maven" >&2
        exit 1
    fi

    PLAN+=("${jar}|${lib_name}|${kind}")
    echo "  ${lib_name}  ←  $(basename "${jar}") ($(du -h "${jar}" | cut -f1))"
done

# ── Atomic deploy ────────────────────────────────────────────────────────────

# replace via same-dir temp + rename(2): running JVMs keep the old inode and
# never observe a partially-written or size-changed jar.
deploy_one() {
    local jar="$1" name="$2" kind="$3" backup tmp dest_bytes src_bytes smoke
    src_bytes="$(wc -c < "${jar}" | tr -d '[:space:]')"

    # Smoke the CLI uber jar BEFORE touching the install: a jar that cannot
    # boot must never reach ~/.kompile/lib. (Same protection the deploy-local-cli
    # profile gives its .candidate.) Exec jars are heavy service bootstraps;
    # unzip -t above is the integrity signal there.
    if [ "${kind}" = "cli" ]; then
        smoke="$(mktemp)"
        if ! java -Djarmode=tools -jar "${jar}" --version >"${smoke}" 2>&1 \
                && ! java -jar "${jar}" --version >"${smoke}" 2>&1; then
            echo "  ERROR: ${jar} failed boot smoke (java -jar --version):" >&2
            grep -iE "error|exception" "${smoke}" | grep -v "at " >&2 || true
            rm -f "${smoke}"
            exit 1
        fi
        echo "  smoke:   $(basename "${jar}") boot --version OK"
        rm -f "${smoke}"
    fi

    if [ -f "${INSTALL_DIR}/lib/${name}" ]; then
        backup="/tmp/${name}.bak-$(date +%Y%m%d-%H%M%S)"
        cp "${INSTALL_DIR}/lib/${name}" "${backup}"
        echo "  backup:  ${backup}"
    fi

    tmp="${INSTALL_DIR}/lib/.${name}.tmp.$$"
    install -m 644 "${jar}" "${tmp}"
    mv -f "${tmp}" "${INSTALL_DIR}/lib/${name}"

    dest_bytes="$(wc -c < "${INSTALL_DIR}/lib/${name}" | tr -d '[:space:]')"
    if [ "${src_bytes}" != "${dest_bytes}" ]; then
        echo "  ERROR: ${name} copy truncated (${src_bytes} -> ${dest_bytes}); disk may be full" >&2
        rm -f "${INSTALL_DIR}/lib/${name}"
        exit 1
    fi
    echo "  deployed: ${INSTALL_DIR}/lib/${name} ($(du -h "${INSTALL_DIR}/lib/${name}" | cut -f1))"
}

echo ""
echo "── Deploying (atomic swap; running sessions keep the old inode) ──"
for entry in "${PLAN[@]}"; do
    IFS='|' read -r jar name kind <<<"${entry}"
    deploy_one "${jar}" "${name}" "${kind}"
done

# Swapped exec jars invalidate any previously extracted subprocess classpath
# (AGENTS.md: subprocess classpaths are extracted there and silently go stale).
if [ -d "${INSTALL_DIR}/lib/.boot-inf-extracted" ]; then
    rm -rf "${INSTALL_DIR}/lib/.boot-inf-extracted"
    echo "  removed stale ${INSTALL_DIR}/lib/.boot-inf-extracted"
fi

# ── Post-install diagnostics ────────────────────────────────────────────────

KOMPILE_BIN="${INSTALL_DIR}/bin/kompile"
if [ -x "${KOMPILE_BIN}" ]; then
    echo ""
    echo "── Post-install doctor ───────────────────────────────────────────"
    KOMPILE_INSTALL_DIR="${INSTALL_DIR}" "${KOMPILE_BIN}" doctor --no-color || true
fi

echo ""
echo "Done. Restart running kompile chat/agent sessions to load the new jars."

