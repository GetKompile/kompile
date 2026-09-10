#!/usr/bin/env bash
###############################################################################
# build-kompile-dev-web.sh
#
# Developer web/server distribution lane. Builds only the kompile server
# modules against the ALREADY INSTALLED DL4J artifacts (Maven local repository
# — DL4J is never rebuilt), then installs a developer distribution into a
# Kompile home.
#
# Modes:
#   --jars    (default) build server exec JARs -> lib/*.jar
#   --native  build the same JARs, then GraalVM native images -> bin/*
#             (launchers prefer the binary and fall back to the jar)
#
# Platform: the first positional argument (or KOMPILE_PLATFORM). When omitted
# the platform is read from the installed distribution's .dist-info.json so the
# build resolves the SAME ND4J backend that is already installed; with no
# install present it falls back to host detection.
#
# Usage:
#   ./build-scripts/build-kompile-dev-web.sh                       # jars, installed platform
#   ./build-scripts/build-kompile-dev-web.sh linux-x86_64-cuda-12.9
#   ./build-scripts/build-kompile-dev-web.sh --native
#   ./build-scripts/build-kompile-dev-web.sh --with-local          # + model/pipeline serving jars
#   ./build-scripts/build-kompile-dev-web.sh --plan                # show resolution, build nothing
#   ./build-scripts/build-kompile-dev-web.sh --install-only        # re-copy latest artifacts
#
# Options:
#   --jars           JAR-only developer distribution (default)
#   --native         JARs + native images (uses build-kompile-platform.sh
#                    with --skip-dl4j --skip-dist for the image lane)
#   --with-local     Also include model-serving + pipeline-serving
#   --platform P     Platform as a flag (alternative to positional arg)
#   --plan           Print resolved platform/backend/artifacts and exit
#   --install-only   Skip Maven; install newest artifacts already in target/
#   --skip-install   Build only, do not touch the install directory
#   --dir DIR        Install target (default: KOMPILE_INSTALL_DIR or ~/.kompile)
#
# Scope (fixed for this lane):
#   kompile-app-main (admin console, exec jar needs -Dkompile.uber),
#   kompile-app-chat, kompile-app-crawl-manager, kompile-model-staging
#   [--with-local: kompile-app-subprocess-serving, kompile-pipeline-serving]
#
# Installed developer distribution:
#   --jars:    lib/kompile-server.jar, lib/kompile-chat.jar,
#              lib/kompile-crawl-manager.jar, lib/kompile-model-staging.jar
#              [--with-local: lib/kompile-model-serving.jar,
#                             lib/kompile-pipeline-serving.jar]
#   --native:  bin/kompile-server, bin/kompile-chat, bin/kompile-crawl-manager,
#              bin/kompile-model-staging + the same lib/ jars as fallback
###############################################################################
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="jars"
INSTALL_ONLY=0
SKIP_INSTALL=0
PLAN_ONLY=0
WITH_LOCAL=0
INSTALL_DIR="${KOMPILE_INSTALL_DIR:-${HOME}/.kompile}"
PLATFORM_ARG="${KOMPILE_PLATFORM:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --jars)         MODE="jars"; shift ;;
    --native)       MODE="native"; shift ;;
    --with-local)   WITH_LOCAL=1; shift ;;
    --platform)     PLATFORM_ARG="$2"; shift 2 ;;
    --plan)         PLAN_ONLY=1; shift ;;
    --install-only) INSTALL_ONLY=1; shift ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    --dir|-d)       INSTALL_DIR="$2"; shift 2 ;;
    --help|-h)      sed -n '3,74p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)             echo "Unknown option: $1" >&2; exit 1 ;;
    *)
      if [ -z "${PLATFORM_ARG}" ]; then PLATFORM_ARG="$1"; else echo "Unexpected argument: $1" >&2; exit 1; fi
      shift ;;
  esac
done

# Shared build library: MVN/GraalVM resolution, platform + backend helpers.
# shellcheck source=build-common.sh
source "${SCRIPT_DIR}/build-common.sh"

[ -f "${KOMPILE_ROOT}/pom.xml" ] || { echo "ERROR: no pom.xml in ${KOMPILE_ROOT}" >&2; exit 1; }

# ── Platform resolution ──────────────────────────────────────────────────────
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

backend_type=""; cuda_version=""; backend_alias=""; javacpp_platform=""
IFS='|' read -r backend_type cuda_version backend_alias < <(_resolve_backend_from_platform "${PLATFORM_ARG}")
javacpp_platform="$(_resolve_javacpp_platform "${PLATFORM_ARG}")"

BACKEND_ARGS=(-Dkompile.backend="${backend_alias}" -Djavacpp.platform="${javacpp_platform}")
if [ "${backend_type}" = "cuda" ]; then
  BACKEND_ARGS+=(-Dkompile.cuda=true)
fi

VERSION="$(grep -m1 '<version>' "${KOMPILE_ROOT}/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' | tr -d ' ')"

APP_MAIN="kompile-app/kompile-app-parent/kompile-app-main/target"
CHAT="kompile-app/kompile-app-parent/kompile-app-chat/target"
CRAWL="kompile-app/kompile-app-parent/kompile-app-crawl-manager/target"
STAGING="kompile-app/kompile-models/kompile-model-staging/target"
SERVING="kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving/target"
PIPELINE="kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target"

WEB_MODULES=":kompile-app-main,:kompile-app-chat,:kompile-app-crawl-manager,:kompile-model-staging"
WEB_JARS=(
  "${APP_MAIN}:kompile-server.jar"
  "${CHAT}:kompile-chat.jar"
  "${CRAWL}:kompile-crawl-manager.jar"
  "${STAGING}:kompile-model-staging.jar"
)
WEB_BINARIES=(
  "kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app:kompile-server"
  "kompile-app/kompile-app-parent/kompile-app-chat/target/kompile-chat:kompile-chat"
  "kompile-app/kompile-app-parent/kompile-app-crawl-manager/target/kompile-crawl-manager:kompile-crawl-manager"
  "kompile-app/kompile-models/kompile-model-staging/target/kompile-model-staging:kompile-model-staging"
)
if [ "${WITH_LOCAL}" -eq 1 ]; then
  WEB_MODULES+=",:kompile-app-subprocess-serving,:kompile-pipeline-serving"
  WEB_JARS+=(
    "${SERVING}:kompile-model-serving.jar"
    "${PIPELINE}:kompile-pipeline-serving.jar"
  )
  WEB_BINARIES+=(
    "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving/target/kompile-model-serving:kompile-model-serving"
    "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target/kompile-pipeline-serving:kompile-pipeline-serving"
  )
fi

echo "==> Dev web lane"
echo "    Mode:      ${MODE}"
echo "    Platform:  ${PLATFORM_ARG}"
echo "    Backend:   ${backend_alias} (javacpp ${javacpp_platform})"
echo "    Install:   ${INSTALL_DIR}"
echo "    Version:   ${VERSION}"

if [ "${PLAN_ONLY}" -eq 1 ]; then
  echo "    Modules:   ${WEB_MODULES}"
  echo "    Jars:      ${WEB_JARS[*]}"
  echo "    Binaries:  ${WEB_BINARIES[*]}"
  exit 0
fi

# ── Build ────────────────────────────────────────────────────────────────────
if [ "${MODE}" = "native" ]; then
  if [ "${INSTALL_ONLY}" -ne 1 ]; then
    SCOPE="app,chat,crawl-manager,staging"
    if [ "${WITH_LOCAL}" -eq 1 ]; then
      SCOPE+=",model-serving,pipeline-serving"
    fi
    NATIVE_TARGETS="${SCOPE}" \
      bash "${SCRIPT_DIR}/build-kompile-platform.sh" "${PLATFORM_ARG}" --skip-dl4j --skip-dist
  fi
else
  if [ "${INSTALL_ONLY}" -ne 1 ]; then
    echo "==> Building web modules (${WEB_MODULES})"
    (
      cd "${KOMPILE_ROOT}"
      "${MVN}" clean install -pl "${WEB_MODULES}" -am \
        --batch-mode --no-transfer-progress --no-snapshot-updates \
        -DskipTests -Dkompile.uber \
        "-Dnd4j.version=${ND4J_VERSION}" "${BACKEND_ARGS[@]}"
    )
    echo "==> Web modules built"
  fi
fi

[ "${SKIP_INSTALL}" -eq 1 ] && { echo "==> --skip-install: done (nothing installed)"; exit 0; }

# ── Install helpers ──────────────────────────────────────────────────────────
pick_exec() {  # pick_exec <target-dir> -> newest exec jar
  local best="" f
  for f in "$1"/*-exec.jar; do
    [ -f "$f" ] || continue
    if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best="$f"; fi
  done
  printf '%s' "$best"
}

install_jar() {  # install_jar <target-dir> <canonical-name> <required:0|1>
  local src; src="$(pick_exec "$1")"
  if [ -z "${src}" ]; then
    if [ "${3:-1}" = "1" ]; then
      echo "  SKIP $2 (no exec jar in $1 — build first)" >&2
      return 1
    fi
    echo "  (no exec jar yet for $2 — fine in native mode, binary is primary)"
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

echo "==> Installing developer web distribution to ${INSTALL_DIR}"
FAILED=0

if [ "${MODE}" = "native" ]; then
  for entry in "${WEB_BINARIES[@]}"; do
    install_binary "${KOMPILE_ROOT}/${entry%%:*}" "${entry##*:}" 1 || FAILED=1
  done
  for entry in "${WEB_JARS[@]}"; do
    install_jar "${KOMPILE_ROOT}/${entry%%:*}" "${entry##*:}" 0 || true
  done
else
  for entry in "${WEB_JARS[@]}"; do
    install_jar "${KOMPILE_ROOT}/${entry%%:*}" "${entry##*:}" 1 || FAILED=1
  done
fi

# Swapped jars/binaries invalidate any extracted subprocess classpath.
if [ -d "${INSTALL_DIR}/lib/.boot-inf-extracted" ]; then
  rm -rf "${INSTALL_DIR}/lib/.boot-inf-extracted"
  echo "  removed stale ${INSTALL_DIR}/lib/.boot-inf-extracted"
fi

echo "==> Dev web distribution install complete (${INSTALL_DIR}, ${MODE})"
exit "${FAILED}"
