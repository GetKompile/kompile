#!/usr/bin/env bash
# Validate the (merged) config before any GCP mutation; reports every problem
# at once. Hard errors stop provisioning, warnings describe degraded lanes.
set -euo pipefail
config="${1:?Usage: preflight-check.sh CONFIG}"
# shellcheck disable=SC1090
source "$config"

errors=(); warns=()
err()  { errors+=("$1"); }
warn() { warns+=("$1"); }

for tool in gcloud git python3; do
  command -v "$tool" >/dev/null 2>&1 || err "missing required tool: $tool"
done

for key in GCP_PROJECT_ID GCP_REGION TPU_ZONE DL4J_REPOSITORY DL4J_REF GRAALVM_ARCHIVE_URL JDK11_ARCHIVE_URL; do
  [ -n "${!key:-}" ] || err "required config value is empty: $key"
done

if command -v gcloud >/dev/null 2>&1; then
  account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -n1)"
  [ -n "$account" ] || err "no active gcloud account (run: gcloud auth login)"
  if [ -n "${GCP_PROJECT_ID:-}" ]; then
    gcloud projects describe "$GCP_PROJECT_ID" >/dev/null 2>&1 \
      || err "project '$GCP_PROJECT_ID' is not accessible with the active credentials"
  fi
  if [ -n "${TPU_ZONE:-}" ] && [ -n "${TPU_ACCELERATOR_TYPE:-}" ]; then
    gcloud compute tpus accelerator-types describe "${TPU_ACCELERATOR_TYPE}" \
        --zone "$TPU_ZONE" --project "${GCP_PROJECT_ID:-}" >/dev/null 2>&1 \
      || warn "accelerator '${TPU_ACCELERATOR_TYPE}' not describable in zone '$TPU_ZONE' — smoke runs may fail (check 'gcloud compute tpus accelerator-types list --zone $TPU_ZONE' and your TPU quota)"
  fi
fi

if [ -n "${GITHUB_RELEASE_REPO:-}" ] && [ -z "${GITHUB_RELEASE_TOKEN_SECRET:-}" ]; then
  warn "GITHUB_RELEASE_REPO is set without GITHUB_RELEASE_TOKEN_SECRET: GitHub Release uploads will be skipped"
fi

echo "Preflight: config for project '${GCP_PROJECT_ID:-?}', TPU ${TPU_ACCELERATOR_TYPE:-?} in ${TPU_ZONE:-?}"
for w in ${warns[@]+"${warns[@]}"}; do echo "WARN: $w"; done
for e in ${errors[@]+"${errors[@]}"}; do echo "ERROR: $e"; done
[ "${#errors[@]}" -eq 0 ]
