#!/usr/bin/env bash
# Host contracts: map a host name (the `host:` field in targets.yml) onto a
# concrete CodeBuild environment. configure_host sets:
#   ENVIRONMENT_TYPE  CodeBuild environment type
#   BUILD_SPEC        buildspec path inside this repository
#   BUILD_IMAGE       image URI / curated identifier
#   FLEET_ARN         reserved fleet ARN ("" = on-demand)
#   COMPUTE_TYPE      on-demand compute type (fleet compute wins when attached)
#   IMAGE_PULL_CREDS  CODEBUILD for curated images, else SERVICE_ROLE
#   JAVA11_HOME_EFFECTIVE / GRAALVM_HOME_EFFECTIVE  platform-correct tool paths
#   SKIP_REASON       nonempty = target cannot deploy with current config;
#                     callers must skip it (exit 3 in deploy-target) not fail.
# TARGET_IMAGE_KEY, when set by the caller (targets.yml `image:` field),
# overrides the host's default image key.
#
# Hardware notes:
# - linux-cuda / linux-amd (ZLUDA) / tpu / hexagon BUILD hosts compile on plain
#   LINUX_CONTAINER: nvcc/hipcc do not need a physical device.
# - *-hw hosts run device smoke tests: they require a LINUX_EC2 reserved fleet
#   with a custom AMI carrying the vendor driver stack (custom AMIs are only
#   supported on MAC_ARM / LINUX_EC2 / ARM_EC2 / WINDOWS_EC2 fleets).
# - TPU and Hexagon smoke targets need hardware AWS does not sell; they stay
#   skipped unless you point them at a self-managed fleet.

configure_host() {
  local host="$1" image_key="" fleet_key=""
  ENVIRONMENT_TYPE=""; BUILD_SPEC=aws/codebuild/buildspec.yml
  BUILD_IMAGE=""; FLEET_ARN=""; COMPUTE_TYPE=""; SKIP_REASON=""
  IMAGE_PULL_CREDS="${IMAGE_PULL_CREDENTIALS_TYPE:-SERVICE_ROLE}"
  JAVA11_HOME_EFFECTIVE="${JAVA11_HOME:-/opt/jdk11}"
  GRAALVM_HOME_EFFECTIVE="${GRAALVM_HOME:-/opt/graalvm}"
  local linux_compute="${LINUX_COMPUTE_TYPE:-BUILD_GENERAL1_2XLARGE}"

  case "$host" in
    linux-x86_64)
      ENVIRONMENT_TYPE=LINUX_CONTAINER; image_key=LINUX_IMAGE; fleet_key=LINUX_FLEET_ARN
      COMPUTE_TYPE="$linux_compute" ;;
    linux-arm64)
      ENVIRONMENT_TYPE=ARM_CONTAINER; image_key=LINUX_ARM_IMAGE; fleet_key=ARM_FLEET_ARN
      COMPUTE_TYPE="${ARM_COMPUTE_TYPE:-BUILD_GENERAL1_2XLARGE}" ;;
    linux-android-x86_64)
      ENVIRONMENT_TYPE=LINUX_CONTAINER; image_key=ANDROID_IMAGE; fleet_key=LINUX_FLEET_ARN
      COMPUTE_TYPE="${ANDROID_COMPUTE_TYPE:-$linux_compute}" ;;
    linux-cuda-x86_64)
      ENVIRONMENT_TYPE=LINUX_CONTAINER; image_key=CUDA_12_9_IMAGE; fleet_key=LINUX_FLEET_ARN
      COMPUTE_TYPE="${CUDA_BUILD_COMPUTE_TYPE:-$linux_compute}" ;;
    linux-amd-x86_64)
      ENVIRONMENT_TYPE=LINUX_CONTAINER; image_key=AMD_IMAGE; fleet_key=LINUX_FLEET_ARN
      COMPUTE_TYPE="${AMD_BUILD_COMPUTE_TYPE:-$linux_compute}" ;;
    linux-tpu-x86_64)
      ENVIRONMENT_TYPE=LINUX_CONTAINER; image_key=TPU_IMAGE; fleet_key=LINUX_FLEET_ARN
      COMPUTE_TYPE="${TPU_COMPUTE_TYPE:-$linux_compute}" ;;
    linux-hexagon-x86_64)
      ENVIRONMENT_TYPE=LINUX_CONTAINER; image_key=HEXAGON_IMAGE; fleet_key=LINUX_FLEET_ARN
      COMPUTE_TYPE="${HEXAGON_COMPUTE_TYPE:-$linux_compute}" ;;
    linux-gpu-x86_64)
      ENVIRONMENT_TYPE=LINUX_GPU_CONTAINER; image_key=CUDA_12_9_IMAGE; fleet_key=GPU_FLEET_ARN
      COMPUTE_TYPE="${GPU_COMPUTE_TYPE:-BUILD_GENERAL1_SMALL}" ;;
    linux-amd-gpu-x86_64)
      ENVIRONMENT_TYPE=LINUX_EC2; image_key=LINUX_IMAGE; fleet_key=AMD_GPU_FLEET_ARN
      COMPUTE_TYPE="${LINUX_EC2_COMPUTE_TYPE:-BUILD_GENERAL1_LARGE}"
      [ -n "${AMD_GPU_FLEET_ARN:-}" ] || SKIP_REASON="needs a LINUX_EC2 fleet with an AMD GPU AMI (set AMD_GPU_INSTANCE_TYPE + AMD_GPU_AMI_ID)" ;;
    linux-tpu-hw-x86_64)
      ENVIRONMENT_TYPE=LINUX_EC2; image_key=LINUX_IMAGE; fleet_key=TPU_HW_FLEET_ARN
      COMPUTE_TYPE="${LINUX_EC2_COMPUTE_TYPE:-BUILD_GENERAL1_LARGE}"
      [ -n "${TPU_HW_FLEET_ARN:-}" ] || SKIP_REASON="needs TPU hardware AWS does not provide — use gcp/cloudbuild (real Cloud TPU smokes) or set TPU_HW_FLEET_ARN" ;;
    linux-hexagon-hw-x86_64)
      ENVIRONMENT_TYPE=LINUX_EC2; image_key=LINUX_IMAGE; fleet_key=HEXAGON_HW_FLEET_ARN
      COMPUTE_TYPE="${LINUX_EC2_COMPUTE_TYPE:-BUILD_GENERAL1_LARGE}"
      [ -n "${HEXAGON_HW_FLEET_ARN:-}" ] || SKIP_REASON="needs Hexagon device hardware (set HEXAGON_HW_FLEET_ARN to a self-managed fleet to enable)" ;;
    windows-x86_64|windows-gpu-x86_64)
      BUILD_SPEC=aws/codebuild/buildspec-windows.yml
      JAVA11_HOME_EFFECTIVE="${WINDOWS_JAVA11_HOME:-C:\\opt\\jdk11}"
      GRAALVM_HOME_EFFECTIVE="${WINDOWS_GRAALVM_HOME:-C:\\opt\\graalvm}"
      local wfleet wimage
      if [ "$host" = windows-x86_64 ]; then
        wfleet="${WINDOWS_FLEET_ARN:-}"; wimage="${WINDOWS_IMAGE:-}"; fleet_key=WINDOWS_FLEET_ARN; image_key=WINDOWS_IMAGE
      else
        wfleet="${WINDOWS_CUDA_FLEET_ARN:-}"; wimage="${WINDOWS_CUDA_IMAGE:-}"; fleet_key=WINDOWS_CUDA_FLEET_ARN; image_key=WINDOWS_CUDA_IMAGE
      fi
      COMPUTE_TYPE="${WINDOWS_COMPUTE_TYPE:-BUILD_GENERAL1_LARGE}"
      if [ -n "$wfleet" ]; then
        # Reserved WINDOWS_EC2 fleet with a toolchain AMI; project image is a
        # required-but-unused CFN property, so a curated identifier suffices.
        ENVIRONMENT_TYPE=WINDOWS_EC2
        BUILD_IMAGE="${wimage:-aws/codebuild/windows-base:2022-1.0}"
      elif [ -n "$wimage" ]; then
        # A Windows container image was supplied (built on a Windows Docker host).
        ENVIRONMENT_TYPE=WINDOWS_SERVER_2022_CONTAINER
        BUILD_IMAGE="$wimage"
      else
        SKIP_REASON="needs a WINDOWS_EC2 fleet AMI (bake-ami.sh windows) or a prebuilt WINDOWS(_CUDA)_IMAGE container image"
      fi ;;
    macos-arm64)
      ENVIRONMENT_TYPE=MAC_ARM; image_key=MACOS_IMAGE; fleet_key=MACOS_FLEET_ARN
      COMPUTE_TYPE="${MACOS_COMPUTE_TYPE:-BUILD_GENERAL1_MEDIUM}"
      BUILD_IMAGE="${MACOS_IMAGE:-aws/codebuild/macos-arm-base:14}"
      [ -n "${MACOS_FLEET_ARN:-}" ] || SKIP_REASON="macOS runs only on a reserved MAC_ARM fleet (set MACOS_FLEET_COMPUTE to create one)" ;;
    configurable)
      ENVIRONMENT_TYPE="${CONFIGURABLE_ENVIRONMENT_TYPE:-}"
      BUILD_SPEC="${CONFIGURABLE_BUILD_SPEC:-aws/codebuild/buildspec.yml}"
      image_key=CONFIGURABLE_IMAGE; fleet_key=CONFIGURABLE_FLEET_ARN
      COMPUTE_TYPE="${CONFIGURABLE_COMPUTE_TYPE:-$linux_compute}"
      [ -n "$ENVIRONMENT_TYPE" ] || SKIP_REASON="set CONFIGURABLE_ENVIRONMENT_TYPE/CONFIGURABLE_IMAGE to enable the configurable host" ;;
    *) echo "Unknown host contract: $host" >&2; return 2 ;;
  esac

  [ -n "${TARGET_IMAGE_KEY:-}" ] && image_key="$TARGET_IMAGE_KEY"
  if [ -z "$BUILD_IMAGE" ] && [ -n "$image_key" ]; then
    BUILD_IMAGE="${!image_key:-}"
  fi
  if [ -z "$FLEET_ARN" ] && [ -n "$fleet_key" ]; then
    FLEET_ARN="${!fleet_key:-}"
  fi
  if [ -z "$SKIP_REASON" ] && [ -z "$BUILD_IMAGE" ]; then
    SKIP_REASON="no build image configured (${image_key:-unset})"
  fi
  case "$BUILD_IMAGE" in aws/codebuild/*) IMAGE_PULL_CREDS=CODEBUILD ;; esac
  return 0
}
