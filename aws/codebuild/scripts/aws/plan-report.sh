#!/usr/bin/env bash
# Read-only account-resource report for provision.sh --plan: for everything
# bootstrap/build-images/create-fleets would touch, print exists / would
# create. Makes zero mutations.
set -euo pipefail
config="${1:?Usage: plan-report.sh CONFIG}"
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?}"
prefix="${PROJECT_PREFIX:-kompile}"

line() { printf '  %-58s %s\n' "$1" "$2"; }
check() { # check LABEL CMD... — prints exists / would-create
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then line "$label" "exists"; else line "$label" "WOULD CREATE"; fi
}

check "bucket s3://${ARTIFACT_BUCKET:?}" aws s3api head-bucket --bucket "$ARTIFACT_BUCKET"
check "bucket s3://${CACHE_BUCKET:?}" aws s3api head-bucket --bucket "$CACHE_BUCKET"
check "ECR repository ${ECR_REPOSITORY:?}" \
  aws ecr describe-repositories --region "$AWS_REGION" --repository-names "$ECR_REPOSITORY"
check "IAM fleet service role ${prefix}-codebuild-fleet-role" \
  aws iam get-role --role-name "${prefix}-codebuild-fleet-role"

seen=" "
for secret in "${GITHUB_TOKEN_SECRET:-}" "${DL4J_TOKEN_SECRET:-}" "${GITHUB_RELEASE_TOKEN_SECRET:-}"; do
  [ -n "$secret" ] || continue
  case "$seen" in *" $secret "*) continue ;; esac
  seen="$seen$secret "
  if aws secretsmanager describe-secret --region "$AWS_REGION" --secret-id "$secret" >/dev/null 2>&1; then
    line "secret $secret" "exists"
  else
    line "secret $secret" "MISSING (set-github-token.sh)"
  fi
done

families=(linux cuda-12.9 cuda-13.1 amd tpu)
{ [ -n "${GRAALVM_ARM_ARCHIVE_URL:-}" ] && [ -n "${JDK11_ARM_ARCHIVE_URL:-}" ]; } && families+=(linux-arm64)
families+=(android)
families+=(hexagon)
for family in "${families[@]}"; do
  check "image ${ECR_REPOSITORY}:${family}" \
    aws ecr describe-images --region "$AWS_REGION" --repository-name "$ECR_REPOSITORY" \
      --image-ids "imageTag=$family"
done

fleet() { # fleet LABEL NAME TRIGGER_VALUE
  local label="$1" name="$2" trigger="$3"
  if [ -z "$trigger" ]; then
    line "fleet $label" "lane disabled"
    return 0
  fi
  local arn
  arn="$(aws codebuild batch-get-fleets --region "$AWS_REGION" --names "$name" \
    --query 'fleets[0].arn' --output text 2>/dev/null | grep -v '^None$' || true)"
  if [ -n "$arn" ]; then line "fleet $label" "exists"; else line "fleet $label" "WOULD CREATE"; fi
}
fleet macos        "$prefix-macos"        "${MACOS_FLEET_COMPUTE:-}"
fleet windows      "$prefix-windows"      "${WINDOWS_INSTANCE_TYPE:-}"
fleet windows-cuda "$prefix-windows-cuda" "${WINDOWS_CUDA_INSTANCE_TYPE:-}"
fleet amd-gpu      "$prefix-amd-gpu"      "${AMD_GPU_INSTANCE_TYPE:-}"
fleet gpu          "$prefix-gpu"          "${GPU_FLEET_COMPUTE:-}"
fleet linux        "$prefix-linux"        "${LINUX_FLEET_COMPUTE:-}"
fleet arm          "$prefix-arm"          "${ARM_FLEET_COMPUTE:-}"
