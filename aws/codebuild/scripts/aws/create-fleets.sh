#!/usr/bin/env bash
# Create every configured reserved fleet (idempotent by exact name) and emit
# the resulting *_FLEET_ARN lines to stdout for the merged generated config.
# A lane is enabled by setting its compute/instance-type key; lanes whose
# required AMI is missing are skipped with a warning so one-go provisioning
# always completes.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
config="${1:?Usage: create-fleets.sh CONFIG}"
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?}"
prefix="${PROJECT_PREFIX:-kompile}"

log() { echo "create-fleets: $*" >&2; }

existing_arn() { # exact name match
  aws codebuild batch-get-fleets --region "$AWS_REGION" --names "$1" \
    --query 'fleets[0].arn' --output text 2>/dev/null | grep -v '^None$' || true
}

create() { # create VAR NAME ENV_TYPE COMPUTE CAPACITY [IMAGE_ID] [needs_ami]
  local var="$1" name="$2" env="$3" compute="$4" capacity="$5" image_id="${6:-}" needs_ami="${7:-no}"
  if [ -n "${!var:-}" ]; then
    log "$var already set; keeping ${!var}"
    return 0
  fi
  [ -n "$compute" ] || return 0
  if [ "$needs_ami" = yes ] && [ -z "$image_id" ]; then
    log "WARNING: $name requested but its toolchain AMI is missing — skipping (bake one with bake-ami.sh)"
    return 0
  fi
  local arn
  arn="$(existing_arn "$name")"
  if [ -z "$arn" ]; then
    log "creating fleet $name ($env, $compute, capacity $capacity${image_id:+, AMI $image_id})"
    arn="$("$here/create-fleet.sh" "$config" "$name" "$env" "$compute" "$capacity" "$image_id" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["fleet"]["arn"])')"
  else
    log "reusing fleet $name ($arn)"
  fi
  printf '%s=%s\n' "$var" "$arn"
}

create MACOS_FLEET_ARN        "$prefix-macos"        MAC_ARM              "${MACOS_FLEET_COMPUTE:-}"       "${MACOS_CAPACITY:-1}"        "${MACOS_AMI_ID:-}"        no
create WINDOWS_FLEET_ARN      "$prefix-windows"      WINDOWS_EC2          "${WINDOWS_INSTANCE_TYPE:-}"     "${WINDOWS_CAPACITY:-1}"      "${WINDOWS_AMI_ID:-}"      yes
create WINDOWS_CUDA_FLEET_ARN "$prefix-windows-cuda" WINDOWS_EC2          "${WINDOWS_CUDA_INSTANCE_TYPE:-}" "${WINDOWS_CUDA_CAPACITY:-1}" "${WINDOWS_CUDA_AMI_ID:-}" yes
create AMD_GPU_FLEET_ARN      "$prefix-amd-gpu"      LINUX_EC2            "${AMD_GPU_INSTANCE_TYPE:-}"     "${AMD_GPU_CAPACITY:-1}"      "${AMD_GPU_AMI_ID:-}"      yes
create GPU_FLEET_ARN          "$prefix-gpu"          LINUX_GPU_CONTAINER  "${GPU_FLEET_COMPUTE:-}"         "${GPU_CAPACITY:-1}"          ""                          no
create LINUX_FLEET_ARN        "$prefix-linux"        LINUX_CONTAINER      "${LINUX_FLEET_COMPUTE:-}"       "${LINUX_FLEET_CAPACITY:-1}"  ""                          no
create ARM_FLEET_ARN          "$prefix-arm"          ARM_CONTAINER        "${ARM_FLEET_COMPUTE:-}"         "${ARM_FLEET_CAPACITY:-1}"    ""                          no
