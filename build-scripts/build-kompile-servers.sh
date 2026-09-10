#!/bin/bash
# Build the web/server native images only: app (kompile-server), chat,
# crawl-manager, and model staging. Distribution assembly is skipped unless
# --dist is passed.
#
# Usage:
#   ./build-scripts/build-kompile-servers.sh
#   ./build-scripts/build-kompile-servers.sh linux-x86_64-cuda-12.9
#   ./build-scripts/build-kompile-servers.sh --skip-dl4j --dist
#
# Arguments:
#   $1       DL4J platform classifier (default: detected host)
#   remaining flags forward to build-kompile-platform.sh
#
# Environment:
#   KOMPILE_SKIP_DL4J     1 resolves DL4J from the local Maven repo instead of
#                         building it (same as --skip-dl4j)
#   KOMPILE_SERVER_TARGETS  Override the server image set
#                         (default: app,chat,crawl-manager,staging)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PLATFORM_ARG=""
FORWARD=()
ASSEMBLE_DIST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dist) ASSEMBLE_DIST=1; shift ;;
    -*)     FORWARD+=("$1"); shift ;;
    *)
      if [ -z "${PLATFORM_ARG}" ]; then PLATFORM_ARG="$1"; else FORWARD+=("$1"); fi
      shift ;;
  esac
done
if [ -z "${PLATFORM_ARG}" ]; then
  case "$(uname -s)" in
    Linux*)  os="linux" ;;
    Darwin*) os="macosx" ;;
    *)       os="windows" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64)  arch="x86_64" ;;
    aarch64|arm64) arch="arm64" ;;
    *)             arch="$(uname -m)" ;;
  esac
  PLATFORM_ARG="${os}-${arch}"
fi

CMD=(bash "${SCRIPT_DIR}/build-kompile-platform.sh" "${PLATFORM_ARG}")
# Platform script assembles the distribution by default; only suppress it for
# image-only builds.
[ "${ASSEMBLE_DIST}" -eq 0 ] && CMD+=(--skip-dist)
[ "${KOMPILE_SKIP_DL4J:-0}" = "1" ] && CMD+=(--skip-dl4j)
CMD+=("${FORWARD[@]}")

NATIVE_TARGETS="${KOMPILE_SERVER_TARGETS:-app,chat,crawl-manager,staging}" exec "${CMD[@]}"
