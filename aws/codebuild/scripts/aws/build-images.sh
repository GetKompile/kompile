#!/usr/bin/env bash
# Build and push the CodeBuild container images.
#   build-images.sh CONFIG [auto|family ...]
# Families: linux linux-arm64 android cuda-12.6 cuda-12.9 amd tpu hexagon
# "auto" (default) builds every family whose inputs are satisfied and skips
# the rest with a warning, so one-go provisioning never dies here.
# Windows is intentionally NOT a container family on a Linux workstation:
# use scripts/aws/bake-ami.sh windows (WINDOWS_EC2 fleet AMI) or build
# images/windows/Dockerfile on a Windows Docker host and set WINDOWS_IMAGE.
# Local simulation hooks: BUILD_IMAGES_PUSH=false builds without pushing (no
# ECR login/credentials needed) and BUILD_IMAGES_TAG_BASE overrides the tag
# base (simulate-build.sh uses kompile-sim).
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
config="${1:?Usage: build-images.sh CONFIG [auto|family ...]}"
shift || true
# shellcheck disable=SC1090
source "$config"
push="${BUILD_IMAGES_PUSH:-true}"
if [ "$push" = true ]; then
  : "${AWS_REGION:?}" "${ECR_REGISTRY:?}" "${ECR_REPOSITORY:?}"
  tag_base="${BUILD_IMAGES_TAG_BASE:-$ECR_REGISTRY/$ECR_REPOSITORY}"
else
  tag_base="${BUILD_IMAGES_TAG_BASE:-kompile-sim}"
fi
: "${GRAALVM_ARCHIVE_URL:?}" "${JDK11_ARCHIVE_URL:?}"

families=("${@:-auto}")
[ "${families[0]}" = auto ] && {
  families=(linux cuda-12.9 cuda-13.1 amd tpu)
  if [ -n "${GRAALVM_ARM_ARCHIVE_URL:-}" ] && [ -n "${JDK11_ARM_ARCHIVE_URL:-}" ]; then
    families+=(linux-arm64)
  else
    echo "build-images: skipping linux-arm64 (set GRAALVM_ARM_ARCHIVE_URL + JDK11_ARM_ARCHIVE_URL)" >&2
  fi
  families+=(android)
  if [ -n "${HEXAGON_SDK_ARCHIVE_URL:-}" ]; then
    families+=(hexagon)
  else
    echo "build-images: skipping hexagon (set HEXAGON_SDK_ARCHIVE_URL)" >&2
  fi
}

if [ "$push" = true ]; then
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$ECR_REGISTRY"
fi

uri() { printf '%s:%s' "$tag_base" "$1"; }
jdk_args=(--build-arg "GRAALVM_ARCHIVE_URL=$GRAALVM_ARCHIVE_URL" --build-arg "JDK11_ARCHIVE_URL=$JDK11_ARCHIVE_URL")

build() { # build TAG DIR [extra docker args...]
  local tag="$1" dir="$2"; shift 2
  echo "build-images: building $(uri "$tag")" >&2
  docker build -t "$(uri "$tag")" "$@" "$root/aws/codebuild/images/$dir"
  [ "$push" = true ] && docker push "$(uri "$tag")"
  return 0
}

for family in "${families[@]}"; do
  case "$family" in
    linux)
      build linux linux "${jdk_args[@]}" ;;
    linux-arm64)
      : "${GRAALVM_ARM_ARCHIVE_URL:?}" "${JDK11_ARM_ARCHIVE_URL:?}"
      # Cross-build via QEMU user emulation; idempotent binfmt registration.
      docker run --privileged --rm tonistiigi/binfmt --install arm64 >/dev/null
      docker buildx build --platform linux/arm64 --load -t "$(uri linux-arm64)" \
        --build-arg "GRAALVM_ARCHIVE_URL=$GRAALVM_ARM_ARCHIVE_URL" \
        --build-arg "JDK11_ARCHIVE_URL=$JDK11_ARM_ARCHIVE_URL" \
        "$root/aws/codebuild/images/linux"
      [ "$push" = true ] && docker push "$(uri linux-arm64)"
      true ;;
    android)
      build android android "${jdk_args[@]}" \
        --build-arg "ANDROID_NDK_VERSION=${ANDROID_NDK_VERSION:-r27d}" ;;
    cuda-13.1)
      build cuda-13.1 cuda "${jdk_args[@]}" \
        --build-arg "BASE_IMAGE=${CUDA_13_1_BASE_IMAGE:-nvidia/cuda:13.1.0-cudnn-devel-ubuntu24.04}" ;;
    cuda-12.9)
      build cuda-12.9 cuda "${jdk_args[@]}" \
        --build-arg "BASE_IMAGE=${CUDA_12_9_BASE_IMAGE:-nvidia/cuda:12.9.1-cudnn-devel-ubuntu22.04}" ;;
    amd)
      build amd rocm "${jdk_args[@]}" \
        --build-arg "BASE_IMAGE=${ROCM_BASE_IMAGE:-rocm/dev-ubuntu-22.04:6.4-complete}" \
        --build-arg "CUDA_TOOLKIT_VERSION=${CUDA_VERSION:-12.9}" \
        --build-arg "ZLUDA_VERSION=${ZLUDA_VERSION:-v6}" \
        --build-arg "ZLUDA_ARCHIVE_URL=${ZLUDA_ARCHIVE_URL:-}" ;;
    tpu)
      build tpu tpu "${jdk_args[@]}" \
        --build-arg "PJRT_PLUGIN_URL=${PJRT_PLUGIN_URL:-}" ;;
    hexagon)
      build hexagon hexagon "${jdk_args[@]}" \
        --build-arg "HEXAGON_SDK_ARCHIVE_URL=${HEXAGON_SDK_ARCHIVE_URL:?}" ;;
    windows)
      echo "build-images: windows images cannot be built from a Linux Docker daemon." >&2
      echo "  Preferred: scripts/aws/bake-ami.sh $config windows   (WINDOWS_EC2 fleet AMI)" >&2
      echo "  Alternative: docker build aws/codebuild/images/windows on a Windows host, push, set WINDOWS_IMAGE" >&2
      exit 2 ;;
    *) echo "Unknown image family: $family" >&2; exit 2 ;;
  esac
done
echo "build-images: done (${families[*]})"
