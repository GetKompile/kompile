#!/usr/bin/env bash
# Validate the (merged) config against the selected target kind BEFORE any
# AWS mutation. Reports every problem at once: hard errors stop provisioning,
# warnings describe lanes that will be skipped or later fail.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
config="${1:?Usage: preflight-check.sh CONFIG [build|validation|all]}"
kind="${2:-build}"
targets_file="$root/aws/codebuild/targets.yml"
# shellcheck disable=SC1090
source "$config"
# shellcheck disable=SC1091
source "$root/aws/codebuild/hosts.sh"
# shellcheck disable=SC1091
source "$root/aws/codebuild/scripts/aws/targets-lib.sh"

errors=(); warns=()
err()  { errors+=("$1"); }
warn() { warns+=("$1"); }

for tool in aws git awk python3; do
  command -v "$tool" >/dev/null 2>&1 || err "missing required tool: $tool"
done
command -v docker >/dev/null 2>&1 || warn "docker not found: image builds will fail (deploy-only reruns are fine)"

for key in AWS_REGION SOURCE_LOCATION KOMPILE_REF DL4J_REPOSITORY DL4J_REF GRAALVM_ARCHIVE_URL JDK11_ARCHIVE_URL; do
  [ -n "${!key:-}" ] || err "required config value is empty: $key"
done

if command -v aws >/dev/null 2>&1 && [ -n "${AWS_REGION:-}" ]; then
  aws sts get-caller-identity >/dev/null 2>&1 || err "AWS credentials unusable: aws sts get-caller-identity failed"
fi

if [ -n "${ENABLED_TARGETS:-}" ]; then
  for t in ${ENABLED_TARGETS//,/ }; do
    [ -n "$(target_field "$targets_file" "$t" host)" ] || err "ENABLED_TARGETS contains unknown target: $t"
  done
fi

selected=0 skipped=0 needs_arm=0 needs_hexagon=0
while read -r target _; do
  selected=$((selected + 1))
  host="$(target_field "$targets_file" "$target" host)"
  TARGET_IMAGE_KEY="$(target_field "$targets_file" "$target" image)"
  if ! configure_host "$host"; then
    err "$target references unknown host contract: $host"
    continue
  fi
  if [ -n "$SKIP_REASON" ]; then
    skipped=$((skipped + 1))
    warn "$target will be SKIPPED: $SKIP_REASON"
  fi
  case "$host" in
    linux-arm64) needs_arm=1 ;;
    linux-hexagon-x86_64) needs_hexagon=1 ;;
  esac
done < <(targets_matching "$targets_file" "$kind" "${ENABLED_TARGETS:-}")
[ "$selected" -gt 0 ] || err "no targets selected for kind '$kind' (check ENABLED_TARGETS)"

if [ "$needs_arm" = 1 ] && { [ -z "${GRAALVM_ARM_ARCHIVE_URL:-}" ] || [ -z "${JDK11_ARM_ARCHIVE_URL:-}" ]; }; then
  warn "linux-arm64 targets selected but GRAALVM_ARM_ARCHIVE_URL/JDK11_ARM_ARCHIVE_URL are empty: the arm64 image will not be built and those builds will fail at image pull"
fi
if [ "$needs_hexagon" = 1 ] && [ -z "${HEXAGON_SDK_ARCHIVE_URL:-}" ]; then
  warn "hexagon build target selected but HEXAGON_SDK_ARCHIVE_URL is empty: the image builds without the SDK and compilation will fail"
fi
if [ -n "${MACOS_FLEET_COMPUTE:-}" ]; then
  case "${AWS_REGION:-}" in
    us-east-1|us-east-2|us-west-2|ap-southeast-2) : ;;
    eu-central-1)
      [ "${MACOS_FLEET_COMPUTE}" = BUILD_GENERAL1_MEDIUM ] || err "macOS LARGE fleets are not offered in eu-central-1 (MEDIUM only)" ;;
    *) err "MAC_ARM fleets are only offered in us-east-1, us-east-2, us-west-2, ap-southeast-2 (and eu-central-1 for MEDIUM); region '${AWS_REGION:-}' cannot host the macOS lane" ;;
  esac
fi

if [ -z "$(git -C "$root" ls-files aws/codebuild | head -n 1)" ]; then
  warn "aws/codebuild is not committed to git: CodeBuild clones ${SOURCE_LOCATION:-<source>} and will not find the buildspec until this tree is committed and pushed to ${KOMPILE_REF:-<ref>}"
elif [ -n "${SOURCE_LOCATION:-}" ] && [ -n "${KOMPILE_REF:-}" ]; then
  if ! GIT_TERMINAL_PROMPT=0 timeout 15 git ls-remote --exit-code "$SOURCE_LOCATION" "$KOMPILE_REF" >/dev/null 2>&1; then
    warn "could not resolve ref '$KOMPILE_REF' at $SOURCE_LOCATION (private repo or unpushed ref?); ensure the ref exists and contains aws/codebuild"
  fi
fi

echo "Preflight: $selected target(s) selected for kind '$kind', $skipped will skip."
for w in ${warns[@]+"${warns[@]}"}; do echo "WARN: $w"; done
for e in ${errors[@]+"${errors[@]}"}; do echo "ERROR: $e"; done
[ "${#errors[@]}" -eq 0 ]
