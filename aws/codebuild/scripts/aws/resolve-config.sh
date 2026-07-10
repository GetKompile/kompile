#!/usr/bin/env bash
# Emit (to stdout) derived values for every key the config leaves empty:
# account id, ECR registry/repository, bucket names, and image URIs.
# provision.sh appends the output to the merged generated config, so
# user-provided values always win (we only emit keys that are empty).
set -euo pipefail
config="${1:?Usage: resolve-config.sh CONFIG}"
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?AWS_REGION is required}"

account="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --query Account --output text)}"
prefix="${PROJECT_PREFIX:-kompile}"

emit() { # emit KEY VALUE — only when the key is currently empty
  local key="$1" value="$2"
  [ -n "${!key:-}" ] || printf '%s=%s\n' "$key" "$value"
}

emit AWS_ACCOUNT_ID "$account"
registry="${ECR_REGISTRY:-${account}.dkr.ecr.${AWS_REGION}.amazonaws.com}"
repo="${ECR_REPOSITORY:-${prefix}-build}"
emit ECR_REGISTRY "$registry"
emit ECR_REPOSITORY "$repo"
emit ARTIFACT_BUCKET "${prefix}-artifacts-${account}-${AWS_REGION}"
emit CACHE_BUCKET "${prefix}-cache-${account}-${AWS_REGION}"

base="${registry}/${repo}"
emit LINUX_IMAGE "${base}:linux"
emit LINUX_ARM_IMAGE "${base}:linux-arm64"
emit ANDROID_IMAGE "${base}:android"
emit CUDA_12_6_IMAGE "${base}:cuda-12.6"
emit CUDA_12_9_IMAGE "${base}:cuda-12.9"
emit AMD_IMAGE "${base}:amd"
emit TPU_IMAGE "${base}:tpu"
emit HEXAGON_IMAGE "${base}:hexagon"
emit MACOS_IMAGE "aws/codebuild/macos-arm-base:14"
