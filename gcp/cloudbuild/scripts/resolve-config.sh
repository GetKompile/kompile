#!/usr/bin/env bash
# Emit derived values for every key the config leaves empty (mirrors the AWS
# kit's resolve-config.sh). Only empty keys are emitted, so user values win.
set -euo pipefail
config="${1:?Usage: resolve-config.sh CONFIG}"
# shellcheck disable=SC1090
source "$config"
: "${GCP_PROJECT_ID:?GCP_PROJECT_ID is required}"
: "${GCP_REGION:?GCP_REGION is required}"
prefix="${NAME_PREFIX:-kompile}"

emit() { local key="$1" value="$2"; [ -n "${!key:-}" ] || printf '%s=%s\n' "$key" "$value"; }

repo="${AR_REPOSITORY:-${prefix}-build}"
emit AR_REPOSITORY "$repo"
emit ARTIFACT_BUCKET "${prefix}-artifacts-${GCP_PROJECT_ID}"
emit BUILDER_IMAGE "${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/${repo}/tpu:latest"
emit SERVICE_ACCOUNT_EMAIL "${prefix}-cloudbuild@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
