#!/usr/bin/env bash
# Tear down GCP TPU-lane resources.
#   teardown.sh CONFIG [--tpus] [--images] [--buckets] [--iam] [--secrets]
#                      [--pool] [--all] [--yes]
# Default (no flags): --tpus — deletes STRAY kompile-tpu-* TPU VMs, the
# expensive leak if a smoke run died before its trap. Buckets and secrets
# require --yes (GCP secret deletion has no recovery window).
set -euo pipefail
config="${1:?Usage: teardown.sh CONFIG [--tpus] [--images] [--buckets] [--iam] [--secrets] [--pool] [--all] [--yes]}"
shift
generated="$(dirname "$config")/generated-cloudbuild-gcp.env"
[ -f "$generated" ] && config="$generated"
# shellcheck disable=SC1090
source "$config"
: "${GCP_PROJECT_ID:?}"
proj=(--project "$GCP_PROJECT_ID")

do_tpus=0; do_images=0; do_buckets=0; do_iam=0; do_secrets=0; do_pool=0; yes=0
[ $# -eq 0 ] && do_tpus=1
for arg in "$@"; do
  case "$arg" in
    --tpus) do_tpus=1 ;;
    --images) do_images=1 ;;
    --buckets) do_buckets=1 ;;
    --iam) do_iam=1 ;;
    --secrets) do_secrets=1 ;;
    --pool) do_pool=1 ;;
    --all) do_tpus=1; do_images=1; do_buckets=1; do_iam=1; do_secrets=1; do_pool=1 ;;
    --yes) yes=1 ;;
    *) echo "Unknown flag: $arg" >&2; exit 2 ;;
  esac
done

if [ "$do_tpus" = 1 ] && [ -n "${TPU_ZONE:-}" ]; then
  for name in $(gcloud compute tpus tpu-vm list "${proj[@]}" --zone "$TPU_ZONE" \
      --filter='name~^kompile-tpu-' --format='value(name)' 2>/dev/null); do
    echo "deleting stray TPU VM $name"
    gcloud compute tpus tpu-vm delete "$name" "${proj[@]}" --zone "$TPU_ZONE" --quiet
  done
fi

if [ "$do_pool" = 1 ] && [ -n "${WORKER_POOL:-}" ]; then
  echo "deleting worker pool $WORKER_POOL"
  gcloud builds worker-pools delete "$WORKER_POOL" "${proj[@]}" \
    --region "${GCP_REGION:?}" --quiet 2>/dev/null || true
fi

if [ "$do_images" = 1 ] && [ -n "${AR_REPOSITORY:-}" ]; then
  if gcloud artifacts repositories describe "$AR_REPOSITORY" "${proj[@]}" \
       --location "${GCP_REGION:?}" >/dev/null 2>&1; then
    echo "deleting Artifact Registry repository $AR_REPOSITORY"
    gcloud artifacts repositories delete "$AR_REPOSITORY" "${proj[@]}" \
      --location "$GCP_REGION" --quiet
  fi
fi

if [ "$do_secrets" = 1 ] && [ -n "${GITHUB_RELEASE_TOKEN_SECRET:-}" ]; then
  if [ "$yes" = 1 ]; then
    if gcloud secrets describe "$GITHUB_RELEASE_TOKEN_SECRET" "${proj[@]}" >/dev/null 2>&1; then
      echo "deleting secret $GITHUB_RELEASE_TOKEN_SECRET (GCP secrets have NO recovery window)"
      gcloud secrets delete "$GITHUB_RELEASE_TOKEN_SECRET" "${proj[@]}" --quiet
    fi
  else
    echo "skipping secret deletion (GCP secret deletion is unrecoverable; add --yes to confirm)"
  fi
fi

if [ "$do_buckets" = 1 ] && [ -n "${ARTIFACT_BUCKET:-}" ]; then
  if [ "$yes" = 1 ]; then
    if gcloud storage buckets describe "gs://$ARTIFACT_BUCKET" "${proj[@]}" >/dev/null 2>&1; then
      echo "deleting bucket gs://$ARTIFACT_BUCKET"
      gcloud storage rm --recursive "gs://$ARTIFACT_BUCKET" "${proj[@]}"
    fi
  else
    echo "skipping bucket deletion (deletes all releases; add --yes to confirm)"
  fi
fi

if [ "$do_iam" = 1 ] && [ -n "${SERVICE_ACCOUNT_EMAIL:-}" ]; then
  if gcloud iam service-accounts describe "$SERVICE_ACCOUNT_EMAIL" "${proj[@]}" >/dev/null 2>&1; then
    echo "deleting service account $SERVICE_ACCOUNT_EMAIL"
    gcloud iam service-accounts delete "$SERVICE_ACCOUNT_EMAIL" "${proj[@]}" --quiet
  fi
fi
echo "teardown: done"
