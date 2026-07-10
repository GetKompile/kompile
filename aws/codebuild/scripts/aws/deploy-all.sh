#!/usr/bin/env bash
# Deploy every selected target of the requested kind, honoring
# ENABLED_TARGETS. Targets the current config cannot host are skipped (not
# fatal); real failures are collected and reported at the end.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
config="${1:?Usage: deploy-all.sh CONFIG [build|validation|all]}"
wanted="${2:-build}"
targets_file="$root/aws/codebuild/targets.yml"
# shellcheck disable=SC1090
source "$config"
# shellcheck disable=SC1091
source "$root/aws/codebuild/scripts/aws/targets-lib.sh"

deployed=(); skipped=(); failed=()
while read -r target _; do
  rc=0
  "$root/aws/codebuild/scripts/aws/deploy-target.sh" "$config" "$target" || rc=$?
  case "$rc" in
    0) deployed+=("$target") ;;
    3) skipped+=("$target") ;;
    *) failed+=("$target"); echo "FAILED $target (exit $rc)" >&2 ;;
  esac
done < <(targets_matching "$targets_file" "$wanted" "${ENABLED_TARGETS:-}")

echo
echo "deploy-all summary (kind: $wanted)"
echo "  deployed: ${#deployed[@]}${deployed[0]:+ — ${deployed[*]}}"
echo "  skipped:  ${#skipped[@]}${skipped[0]:+ — ${skipped[*]}}"
echo "  failed:   ${#failed[@]}${failed[0]:+ — ${failed[*]}}"
[ "${#failed[@]}" -eq 0 ]
