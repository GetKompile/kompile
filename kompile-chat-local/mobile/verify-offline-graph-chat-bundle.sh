#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

die() {
  printf 'verify-offline-graph-chat-bundle: %s\n' "$*" >&2
  exit 1
}

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
bundle="$script_dir/build/kompile-offline-graph-chat-full.zip"
sidecar=""

while (($#)); do
  case "$1" in
    --bundle)
      (($# >= 2)) || die "missing value for --bundle"
      bundle=$2
      shift 2
      ;;
    --sha256)
      (($# >= 2)) || die "missing value for --sha256"
      sidecar=$2
      shift 2
      ;;
    -h|--help)
      printf 'Usage: %s [--bundle ZIP] [--sha256 SIDECAR]\n' "$0"
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

command -v cmake >/dev/null 2>&1 || die "required command not found: cmake"
[[ -n "$sidecar" ]] || sidecar="$bundle.sha256"
exec cmake \
  -DMODE:STRING=VERIFY_ARCHIVE \
  "-DBUNDLE:FILEPATH=$bundle" \
  "-DBUNDLE_SHA256:FILEPATH=$sidecar" \
  -P "$script_dir/cmake/FinalOfflineDistribution.cmake"
