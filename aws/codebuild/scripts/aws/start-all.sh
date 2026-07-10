#!/usr/bin/env bash
# Launch builds for every deployed target of a kind (honors ENABLED_TARGETS).
#   start-all.sh CONFIG [build|validation|all] [KOMPILE_REF] [DL4J_REF] [RELEASE_TAG]
# Targets whose project does not exist (skipped during deploy) are reported
# and skipped here as well.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
here="$root/aws/codebuild/scripts/aws"
config="${1:?Usage: start-all.sh CONFIG [build|validation|all] [KOMPILE_REF] [DL4J_REF] [RELEASE_TAG]}"
kind="${2:-build}"
kompile_ref="${3:-}"
dl4j_ref="${4:-}"
release_tag="${5:-}"
targets_file="$root/aws/codebuild/targets.yml"
generated="$(dirname "$config")/generated-codebuild.env"
env_file="$config"; [ -f "$generated" ] && env_file="$generated"
# shellcheck disable=SC1090
source "$env_file"
# shellcheck disable=SC1091
source "$root/aws/codebuild/scripts/aws/targets-lib.sh"
: "${AWS_REGION:?}"

started=(); missing=(); failed=()
while read -r target _; do
  project="${PROJECT_PREFIX:-kompile}-$target"
  exists="$(aws codebuild batch-get-projects --region "$AWS_REGION" --names "$project" \
    --query 'projects[0].name' --output text 2>/dev/null | grep -v '^None$' || true)"
  if [ -z "$exists" ]; then
    missing+=("$target")
    continue
  fi
  if "$here/start-build.sh" "$config" "$target" "$kompile_ref" "$dl4j_ref" "$release_tag"; then
    started+=("$target")
  else
    failed+=("$target")
  fi
done < <(targets_matching "$targets_file" "$kind" "${ENABLED_TARGETS:-}")

echo
echo "start-all summary (kind: $kind)"
echo "  started:     ${#started[@]}${started[0]:+ — ${started[*]}}"
echo "  no project:  ${#missing[@]}${missing[0]:+ — ${missing[*]}}"
echo "  failed:      ${#failed[@]}${failed[0]:+ — ${failed[*]}}"
[ "${#failed[@]}" -eq 0 ]
