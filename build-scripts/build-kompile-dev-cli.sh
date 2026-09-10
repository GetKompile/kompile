#!/usr/bin/env bash
###############################################################################
# build-kompile-dev-cli.sh
#
# Developer CLI distribution lane. Builds only the kompile CLI modules against
# the ALREADY INSTALLED DL4J artifacts (Maven local repository — DL4J is never
# rebuilt), then installs a developer distribution into a Kompile home.
#
# Modes:
#   --jars    (default) build CLI shaded JARs -> lib/*.jar + JVM launcher
#   --native  build the same JARs, then GraalVM native images -> bin/*
#             (launchers prefer the binary and fall back to the jar)
#
# Platform: the first positional argument (or KOMPILE_PLATFORM). When omitted
# the platform is read from the installed distribution's .dist-info.json so the
# build resolves the SAME ND4J backend that is already installed; with no
# install present it falls back to host detection.
#
# Usage:
#   ./build-scripts/build-kompile-dev-cli.sh                       # jars, installed platform
#   ./build-scripts/build-kompile-dev-cli.sh linux-x86_64-cuda-12.9
#   ./build-scripts/build-kompile-dev-cli.sh --native macosx-arm64
#   ./build-scripts/build-kompile-dev-cli.sh --plan                # show resolution, build nothing
#   ./build-scripts/build-kompile-dev-cli.sh --install-only        # re-copy latest artifacts
#
# Options:
#   --jars           JAR-only developer distribution (default)
#   --native         JARs + native images (uses build-kompile-platform.sh
#                    with --skip-dl4j --skip-dist for the image lane)
#   --platform P     Platform as a flag (alternative to positional arg)
#   --plan           Print resolved platform/backend/artifacts and exit
#   --install-only   Skip Maven; install newest artifacts already in target/
#   --skip-install   Build only, do not touch the install directory
#   --dir DIR        Install target (default: KOMPILE_INSTALL_DIR or ~/.kompile)
#
# Scope (fixed for this lane):
#   kompile-cli-main, kompile-agent-cli, kompile-app-cli,
#   kompile-model-cli, kompile-component-cli
#
# Installed developer distribution:
#   --jars:    lib/kompile{,-agent,-app-cli,-model,-component}.jar + bin/kompile launcher
#   --native:  bin/kompile{,-model,-agent,-app-cli,-component} binaries
#              + the same lib/ jars as JVM fallback
###############################################################################
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="jars"
INSTALL_ONLY=0
SKIP_INSTALL=0
PLAN_ONLY=0
INSTALL_DIR="${KOMPILE_INSTALL_DIR:-${HOME}/.kompile}"
PLATFORM_ARG="${KOMPILE_PLATFORM:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --jars)         MODE="jars"; shift ;;
    --native)       MODE="native"; shift ;;
    --platform)     PLATFORM_ARG="$2"; shift 2 ;;
    --plan)         PLAN_ONLY=1; shift ;;
    --install-only) INSTALL_ONLY=1; shift ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    --dir|-d)       INSTALL_DIR="$2"; shift 2 ;;
    --help|-h)      sed -n '3,63p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)             echo "Unknown option: $1" >&2; exit 1 ;;
    *)
      if [ -z "${PLATFORM_ARG}" ]; then PLATFORM_ARG="$1"; else echo "Unexpected argument: $1" >&2; exit 1; fi
      shift ;;
  esac
done

# Shared build library: MVN/GraalVM resolution, platform + backend helpers.
# shellcheck source=build-common.sh
source "${SCRIPT_DIR}/build-common.sh"

# ── Locate the checkout (KOMPILE_ROOT comes from build-common.sh; the
#    environment variable wins, otherwise the repo owning these scripts) ────
[ -f "${KOMPILE_ROOT}/pom.xml" ] || { echo "ERROR: no pom.xml in ${KOMPILE_ROOT}" >&2; exit 1; }

# ── Platform resolution ──────────────────────────────────────────────────────
# 1. explicit argument   2. installed dist's classifier   3. host detection
detect_installed_platform() {
  local info="${INSTALL_DIR}/.dist-info.json"
  [ -f "${info}" ] || return 1
  local base prof
  base="$(sed -n 's/.*"platform": *"\([^"]*\)".*/\1/p' "${info}" | head -1)"
  prof="$(sed -n 's/.*"backendProfile": *"\([^"]*\)".*/\1/p' "${info}" | head -1)"
  [ -n "${base}" ] || return 1
  case "${prof}" in
    ""|cpu)      echo "${base}" ;;
    cpu-*)       echo "${base}-${prof#cpu-}" ;;
    zluda*)      echo "${base}-cuda-12.9-${prof}" ;;
    *)           echo "${base}-${prof}" ;;
  esac
}

if [ -z "${PLATFORM_ARG}" ]; then
  PLATFORM_ARG="$(detect_installed_platform || true)"
fi
[ -n "${PLATFORM_ARG}" ] || PLATFORM_ARG="$(kompile_detect_platform)"
kompile_validate_platform "${PLATFORM_ARG}"

# Resolve the backend the installed DL4J artifacts actually satisfy.
backend_type=""; cuda_version=""; backend_alias=""; javacpp_platform=""
IFS='|' read -r backend_type cuda_version backend_alias < <(_resolve_backend_from_platform "${PLATFORM_ARG}")
javacpp_platform="$(_resolve_javacpp_platform "${PLATFORM_ARG}")"

BACKEND_ARGS=(-Dkompile.backend="${backend_alias}" -Djavacpp.platform="${javacpp_platform}")
if [ "${backend_type}" = "cuda" ]; then
  BACKEND_ARGS+=(-Dkompile.cuda=true)
fi

VERSION="$(grep -m1 '<version>' "${KOMPILE_ROOT}/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')"
CLI_MODULES=":kompile-cli-main,:kompile-agent-cli,:kompile-app-cli,:kompile-model-cli,:kompile-component-cli"
NATIVE_TARGETS_SCOPE="cli,model,agent,app-cli,component"

echo "==> Dev CLI lane"
echo "    Mode:      ${MODE}"
echo "    Platform:  ${PLATFORM_ARG}"
echo "    Backend:   ${backend_alias} (javacpp ${javacpp_platform})"
echo "    Install:   ${INSTALL_DIR}"
echo "    Version:   ${VERSION}"

if [ "${PLAN_ONLY}" -eq 1 ]; then
  echo "    Modules:   ${CLI_MODULES}"
  echo "    Native:    ${NATIVE_TARGETS_SCOPE}"
  exit 0
fi

# ── Build ────────────────────────────────────────────────────────────────────
if [ "${MODE}" = "native" ]; then
  # One delegated pipeline: java modules (correct backend) -> native images.
  # --skip-dl4j consumes the already-installed DL4J; --skip-dist keeps this a
  # developer install, not an archive build.
  if [ "${INSTALL_ONLY}" -ne 1 ]; then
    NATIVE_TARGETS="${NATIVE_TARGETS_SCOPE}" \
      bash "${SCRIPT_DIR}/build-kompile-platform.sh" "${PLATFORM_ARG}" --skip-dl4j --skip-dist
  fi
else
  if [ "${INSTALL_ONLY}" -ne 1 ]; then
    echo "==> Building CLI modules (${CLI_MODULES})"
    (
      cd "${KOMPILE_ROOT}"
      "${MVN}" clean install -pl "${CLI_MODULES}" -am \
        --batch-mode --no-transfer-progress --no-snapshot-updates \
        -DskipTests "-Dnd4j.version=${ND4J_VERSION}" "${BACKEND_ARGS[@]}"
    )
    echo "==> CLI modules built"
  fi
fi

[ "${SKIP_INSTALL}" -eq 1 ] && { echo "==> --skip-install: done (nothing installed)"; exit 0; }

# ── Install helpers ──────────────────────────────────────────────────────────
pick_jar() {  # pick_jar <target-dir> <kind: shaded|exec> -> newest distributable jar
  local dir="$1" kind="$2" best="" f
  if [ "${kind}" = "shaded" ]; then
    for f in "${dir}"/*-shaded.jar; do
      [ -f "$f" ] || continue
      if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best="$f"; fi
    done
  else
    for f in "${dir}"/*-exec.jar; do
      [ -f "$f" ] || continue
      if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best="$f"; fi
    done
  fi
  if [ -z "$best" ]; then
    for f in "${dir}"/*.jar; do
      [ -f "$f" ] || continue
      case "$f" in *original-*|*-sources.jar|*-javadoc.jar) continue ;; esac
      if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best="$f"; fi
    done
  fi
  printf '%s' "$best"
}

install_jar() {  # install_jar <target-dir> <canonical-name> <required:0|1>
  local src; src="$(pick_jar "$1" shaded)"
  if [ -z "${src}" ]; then
    if [ "${3:-1}" = "1" ]; then
      echo "  SKIP $2 (no jar in $1)" >&2
      return 1
    fi
    echo "  (no jar yet for $2 — fine in native mode, binary is primary)"
    return 0
  fi
  mkdir -p "${INSTALL_DIR}/lib"
  cp -f "${src}" "${INSTALL_DIR}/lib/$2"
  echo "  lib/$2  <-  $src ($(du -h "$src" | cut -f1))"
}

install_binary() {  # install_binary <binary-path> <bin-name> <required:0|1>
  if [ ! -x "$1" ]; then
    if [ "${3:-1}" = "1" ]; then
      echo "  SKIP $2 (missing $1 — build the native lane first)" >&2
      return 1
    fi
    return 0
  fi
  mkdir -p "${INSTALL_DIR}/bin"
  cp -f "$1" "${INSTALL_DIR}/bin/$2"
  chmod +x "${INSTALL_DIR}/bin/$2"
  echo "  bin/$2  <-  $1 ($(du -h "$1" | cut -f1))"
}

CLI_TARGETS=(
  "kompile-cli/kompile-cli-main/target/kompile-cli-main:kompile"
  "kompile-cli/kompile-model-cli/target/kompile-model:kompile-model"
  "kompile-cli/kompile-agent-cli/target/kompile-agent:kompile-agent"
  "kompile-cli/kompile-app-cli/target/kompile-app-cli:kompile-app-cli"
  "kompile-cli/kompile-component-cli/target/kompile-component:kompile-component"
)
CLI_JARS=(
  "kompile-cli/kompile-cli-main/target:kompile-cli.jar"
  "kompile-cli/kompile-agent-cli/target:kompile-agent.jar"
  "kompile-cli/kompile-app-cli/target:kompile-app-cli.jar"
  "kompile-cli/kompile-model-cli/target:kompile-model.jar"
  "kompile-cli/kompile-component-cli/target:kompile-component.jar"
)

echo "==> Installing developer CLI distribution to ${INSTALL_DIR}"
FAILED=0

if [ "${MODE}" = "native" ]; then
  for entry in "${CLI_TARGETS[@]}"; do
    install_binary "${KOMPILE_ROOT}/${entry%%:*}" "${entry##*:}" 1 || FAILED=1
  done
  # Jars are the launchers' JVM fallback tier in a native dev install.
  for entry in "${CLI_JARS[@]}"; do
    install_jar "${KOMPILE_ROOT}/${entry%%:*}" "${entry##*:}" 0 || true
  done
else
  for entry in "${CLI_JARS[@]}"; do
    install_jar "${KOMPILE_ROOT}/${entry%%:*}" "${entry##*:}" 1 || FAILED=1
  done
  # Launcher: the jars-only wrapper from kompile-dist (same source build-dist.sh uses).
  LAUNCHER_SRC="${KOMPILE_ROOT}/kompile-dist/src/main/scripts/kompile.sh"
  if [ -f "${LAUNCHER_SRC}" ]; then
    mkdir -p "${INSTALL_DIR}/bin"
    cp -f "${LAUNCHER_SRC}" "${INSTALL_DIR}/bin/kompile"
    cp -f "${LAUNCHER_SRC}" "${INSTALL_DIR}/bin/kompile.sh"
    chmod +x "${INSTALL_DIR}/bin/kompile" "${INSTALL_DIR}/bin/kompile.sh"
    echo "  bin/kompile  <-  ${LAUNCHER_SRC}"
  else
    echo "  WARN: launcher not found at ${LAUNCHER_SRC}; leaving existing bin/kompile" >&2
  fi
fi

# Swapped jars/binaries invalidate any extracted subprocess classpath.
if [ -d "${INSTALL_DIR}/lib/.boot-inf-extracted" ]; then
  rm -rf "${INSTALL_DIR}/lib/.boot-inf-extracted"
  echo "  removed stale ${INSTALL_DIR}/lib/.boot-inf-extracted"
fi

echo "==> Dev CLI distribution install complete (${INSTALL_DIR}, ${MODE})"
if [ "${MODE}" = "jars" ] && [ -x "${INSTALL_DIR}/bin/kompile" ]; then
  KOMPILE_INSTALL_DIR="${INSTALL_DIR}" "${INSTALL_DIR}/bin/kompile" doctor --no-color || true
fi
exit "${FAILED}"
