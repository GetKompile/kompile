#!/bin/bash
# Build ONLY the named kompile native images for one platform. Distribution
# assembly is skipped unless --dist is passed.
#
# Usage:
#   ./build-scripts/build-kompile-target.sh cli,app            # host CPU
#   ./build-scripts/build-kompile-target.sh model-serving linux-x86_64-cuda-12.9
#   ./build-scripts/build-kompile-target.sh staging --skip-dl4j --dist
#
# Arguments:
#   $1       Comma-separated native target(s). Valid: cli, agent, app-cli, model,
#            component, app, chat, crawl-manager, sample, app-lite, staging,
#            model-serving, pipeline-serving, ingest, vector, embedding,
#            model-init, training, or all
#   $2       DL4J platform classifier (default: detected host; pass the full
#            string, e.g. linux-x86_64-cuda-12.9, macosx-arm64)
#
# Remaining flags are forwarded to build-kompile-platform.sh, including
#   --skip-dl4j  --skip-java  --setup  --dl4j-repository U  --nd4j-version V
# and, when --dist is present, distribution assembly runs for the variant
# auto-detected from the platform (override with --variant).
#
# Environment:
#   KOMPILE_SKIP_DL4J  1 resolves DL4J from the local Maven repo instead of
#                      building it (same as --skip-dl4j)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TARGETS="${1:-}"; [ -n "${TARGETS}" ] || { echo "Usage: $0 <targets> [platform] [flags...]" >&2; exit 1; }
shift

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

NATIVE_TARGETS="${TARGETS}" exec "${CMD[@]}"
