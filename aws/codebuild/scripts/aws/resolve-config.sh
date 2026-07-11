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
emit CUDA_13_1_IMAGE "${base}:cuda-13.1"
emit CUDA_12_9_IMAGE "${base}:cuda-12.9"
emit AMD_IMAGE "${base}:amd"
emit TPU_IMAGE "${base}:tpu"
emit HEXAGON_IMAGE "${base}:hexagon"
emit MACOS_IMAGE "aws/codebuild/macos-arm-base:14"
emit ANDROID_NDK_VERSION "r27d"
emit SPIN_REPOSITORY "${prefix}-spins"
emit SPINS_IMAGE "aws/codebuild/amazonlinux-x86_64-standard:5.0"
# Verified toolchain defaults (same as the wizard offers) so headless
# provisioning works without hand-filling archive URLs.
emit GRAALVM_ARCHIVE_URL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz"
emit JDK11_ARCHIVE_URL "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.25%2B9/OpenJDK11U-jdk_x64_linux_hotspot_11.0.25_9.tar.gz"
