#!/bin/bash
# Build ONLY kompile native images (skip DL4J backend + Java module build)
# Assumes nd4j-* JARs and kompile JARs are already in the local Maven repo.
#
# Usage:
#   ./build-kompile-native-only.sh [platform]
#   NATIVE_TARGETS=cli,app ./build-kompile-native-only.sh [platform]
#
# Arguments:
#   platform   Optional DL4J classifier (default: KOMPILE_NATIVE_PLATFORM,
#              otherwise linux-x86_64). e.g. linux-x86_64-cuda-12.9,
#              macosx-arm64, windows-x86_64
#
# Environment:
#   NATIVE_TARGETS           Comma-separated native image targets
#                            (default: all — cli, agent, app-cli, model,
#                            component, app, chat, crawl-manager, sample,
#                            app-lite, staging, model-serving,
#                            pipeline-serving, ingest, vector, embedding,
#                            model-init, training)
#   KOMPILE_NATIVE_PLATFORM  Platform when no positional argument is given
#   KOMPILE_OUTPUT_DIR       Build log/output directory (default: <repo>/dist)
#
# Options:
#   --help, -h    Show this help and exit (before any build library loads)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PLATFORM_ARG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)
      sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    -*)
      echo "Unknown option: $1 (run with --help)" >&2
      exit 1 ;;
    *)
      if [ -z "${PLATFORM_ARG}" ]; then
        PLATFORM_ARG="$1"
      else
        echo "Unexpected argument: $1 (run with --help)" >&2
        exit 1
      fi
      shift ;;
  esac
done

source "${SCRIPT_DIR}/build-common.sh"
kompile_build_for_platform "${PLATFORM_ARG:-${KOMPILE_NATIVE_PLATFORM:-linux-x86_64}}" 1 1 0 1  # skip dl4j, skip java, build native, skip dist
