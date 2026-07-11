#!/usr/bin/env bash
# End-to-end release: setup -> run -> publish, one command.
#   release.sh CONFIG VERSION [--skip-provision] [--kind build|all]
# 1) provision.sh (idempotent; ensures stacks/images/fleets match the tree)
# 2) starts every deployed target of the kind EXCEPT kompile-spins with
#    RELEASE_TAG=VERSION (dists publish to S3 + GitHub Releases per build)
# 3) waits for those builds, then runs kompile-spins for the same VERSION
#    (container images to ECR + GHCR)
# 4) prints where everything landed.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
here="$root/aws/codebuild/scripts/aws"
config="${1:?Usage: release.sh CONFIG VERSION [--skip-provision] [--kind build|all]}"
version="${2:?VERSION (release tag) required}"
shift 2
provision=1 kind=build
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-provision) provision=0; shift ;;
    --kind) kind="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$provision" = 1 ]; then
  "$here/provision.sh" "$config" "$kind"
fi
generated="$(dirname "$config")/generated-codebuild.env"
env_file="$config"; [ -f "$generated" ] && env_file="$generated"
# shellcheck disable=SC1090
source "$env_file"
# shellcheck disable=SC1091
source "$here/targets-lib.sh"
: "${AWS_REGION:?}"
prefix="${PROJECT_PREFIX:-kompile}"

wait_builds() { # wait_builds ID[,ID...] — poll until all terminal; echo failures
  local ids="$1" line failures
  while :; do
    local out pending=0
    out="$(aws codebuild batch-get-builds --region "$AWS_REGION" --ids $ids \
      --query 'builds[].[id,buildStatus]' --output text 2>/dev/null || true)"
    failures=""
    while read -r line status; do
      [ -n "$line" ] || continue
      case "$status" in
        IN_PROGRESS) pending=$((pending + 1)) ;;
        SUCCEEDED) : ;;
        *) failures="$failures $line($status)" ;;
      esac
    done <<< "$out"
    [ "$pending" -eq 0 ] && { echo "$failures"; return 0; }
    echo "release: $pending build(s) still running..." >&2
    sleep 30
  done
}

if [ "${SOURCE_MODE:-github}" = s3 ]; then
  echo "== release $version: uploading local tree (SOURCE_MODE=s3) =="
  "$here/sync-source.sh" "$config"
fi

echo "== release $version: starting build matrix =="
ids=() started=() missing=()
while read -r target _; do
  [ "$target" = kompile-spins ] && continue
  project="$prefix-$target"
  exists="$(aws codebuild batch-get-projects --region "$AWS_REGION" --names "$project" \
    --query 'projects[0].name' --output text 2>/dev/null | grep -v '^None$' || true)"
  [ -n "$exists" ] || { missing+=("$target"); continue; }
  id="$("$here/start-build.sh" "$config" "$target" "" "" "$version" | sed -n 's/^Started //p')"
  [ -n "$id" ] && ids+=("$id")
  started+=("$target")
done < <(targets_matching "$root/aws/codebuild/targets.yml" "$kind" "${ENABLED_TARGETS:-}")
echo "release: started ${#started[@]} target(s)${missing[0]:+; no project (skipped): ${missing[*]}}"
[ "${#ids[@]}" -gt 0 ] || { echo "release: nothing started" >&2; exit 2; }

echo "== release $version: waiting for the matrix =="
failures="$(wait_builds "$(IFS=' '; echo "${ids[*]}")")"
[ -z "${failures// /}" ] || echo "release: FAILED builds:$failures" >&2

echo "== release $version: publishing spins =="
spins_project="$prefix-kompile-spins"
if [ -n "$(aws codebuild batch-get-projects --region "$AWS_REGION" --names "$spins_project" \
      --query 'projects[0].name' --output text 2>/dev/null | grep -v '^None$' || true)" ]; then
  sids=()
  for st in ${RELEASE_SPIN_TARGETS:-linux-x86_64}; do
    sid="$(SPIN_SOURCE_TARGET=$st "$here/start-build.sh" "$config" kompile-spins "" "" "$version" | sed -n 's/^Started //p')"
    [ -n "$sid" ] && sids+=("$sid")
  done
  spin_failures="$(wait_builds "$(IFS=' '; echo "${sids[*]}")")"
  [ -z "${spin_failures// /}" ] || echo "release: spins build FAILED:$spin_failures" >&2
else
  echo "release: kompile-spins project not deployed; skipping container publish" >&2
fi

echo
echo "== release $version: outputs =="
echo "  dists + logs:  s3://${ARTIFACT_BUCKET:?}/${RELEASE_PREFIX:-releases}/$version/<target>/"
[ -n "${GITHUB_RELEASE_REPO:-}" ] \
  && echo "  gh release:    https://github.com/${GITHUB_RELEASE_REPO}/releases/tag/$version"
[ -n "${SPIN_REPOSITORY:-}" ] \
  && echo "  ecr spins:     ${ECR_REGISTRY:-}/${SPIN_REPOSITORY}:<product>-$version"
[ -n "${GITHUB_RELEASE_REPO:-}" ] \
  && echo "  ghcr spins:    ghcr.io/${GITHUB_RELEASE_REPO%%/*}/kompile-<product>:$version"
[ -z "${failures// /}" ]
