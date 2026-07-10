#!/usr/bin/env bash
# Create one reserved-capacity fleet and print its JSON.
#   create-fleet.sh CONFIG NAME ENV_TYPE COMPUTE CAPACITY [IMAGE_ID]
# COMPUTE is either a guided size (BUILD_GENERAL1_MEDIUM, ...) or an EC2
# instance type (mac2.metal is NOT valid here — macOS uses guided sizes).
# A custom IMAGE_ID requires the fleet service role from bootstrap-account.sh.
set -euo pipefail
config="${1:?Usage: create-fleet.sh CONFIG NAME ENV_TYPE COMPUTE CAPACITY [IMAGE_ID]}"
name="${2:?}"; environment="${3:?}"; compute="${4:?}"; capacity="${5:?}"; image_id="${6:-}"
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?}"

args=(--region "$AWS_REGION" --name "$name" --base-capacity "$capacity"
      --environment-type "$environment" --overflow-behavior QUEUE)
case "$compute" in
  BUILD_GENERAL1_*|ATTRIBUTE_BASED_COMPUTE)
    args+=(--compute-type "$compute") ;;
  *)
    args+=(--compute-type CUSTOM_INSTANCE_TYPE --compute-configuration "instanceType=$compute") ;;
esac
if [ -n "$image_id" ]; then
  : "${FLEET_SERVICE_ROLE_ARN:?custom AMI fleets need FLEET_SERVICE_ROLE_ARN (run bootstrap-account.sh)}"
  args+=(--image-id "$image_id" --fleet-service-role "$FLEET_SERVICE_ROLE_ARN")
fi
aws codebuild create-fleet "${args[@]}"
