#!/usr/bin/env bash
set -euo pipefail

# Allocate Android version codes for normal builds. State lives outside disposable
# Gradle/output directories and is updated atomically under a process lock.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_ROOT="${KOMPILE_ANDROID_VERSION_STATE_DIR:-$SCRIPT_DIR/../.kompile-state}"
STATE_FILE="$STATE_ROOT/android-version-code"
SCAN_ROOT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scan-root)
      [[ $# -ge 2 ]] || { echo "missing value for --scan-root" >&2; exit 2; }
      SCAN_ROOT="$2"
      shift 2
      ;;
    --state-root)
      [[ $# -ge 2 ]] || { echo "missing value for --state-root" >&2; exit 2; }
      STATE_ROOT="$2"
      STATE_FILE="$STATE_ROOT/android-version-code"
      shift 2
      ;;
    -h|--help)
      echo "Usage: allocate-apk-version-code.sh [--scan-root DIR] [--state-root DIR]"
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      exit 2
      ;;
  esac
done

mkdir -p "$STATE_ROOT"
exec 9>"$STATE_FILE.lock"
flock -x 9

previous=0
if [[ -f "$STATE_FILE" ]]; then
  read -r previous < "$STATE_FILE" || true
  [[ "$previous" =~ ^[0-9]+$ ]] || {
    echo "invalid persisted Android version code: $STATE_FILE" >&2
    exit 1
  }
fi

highest_receipted=0
if [[ -n "$SCAN_ROOT" && -d "$SCAN_ROOT" ]]; then
  while IFS= read -r -d '' receipt; do
    value="$(awk -F= '$1 == "version_code" {print $2; exit}' "$receipt")"
    if [[ "$value" =~ ^[0-9]+$ ]] && (( value > highest_receipted )); then
      highest_receipted="$value"
    fi
  done < <(find "$SCAN_ROOT" -type f \( -name '*.build-receipt' -o -name '*.receipt' \) -print0)
fi

now="$(date -u +%s)"
next="$now"
(( previous + 1 > next )) && next="$((previous + 1))"
(( highest_receipted + 1 > next )) && next="$((highest_receipted + 1))"
(( next >= 1 && next <= 2100000000 )) || {
  echo "allocated Android version code outside Android's valid range: $next" >&2
  exit 1
}

printf '%s\n' "$next" > "$STATE_FILE.tmp"
mv -f "$STATE_FILE.tmp" "$STATE_FILE"
printf '%s\n' "$next"
