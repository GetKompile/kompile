#!/usr/bin/env bash
# Submit a Cloud Build for a TPU lane.
#   start-build.sh CONFIG build|smoke [DL4J_REF] [RELEASE_TAG]
# The repo working tree is the build source (no GitHub App connection
# needed — anyone with a clone can submit). serviceAccount, machine type /
# worker pool, and the optional GitHub release secret are injected into the
# checked-in config at submit time.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
config="${1:?Usage: start-build.sh CONFIG build|smoke [DL4J_REF] [RELEASE_TAG]}"
lane="${2:?Lane required: build | smoke}"
case "$lane" in build|smoke) : ;; *) echo "Unknown lane: $lane" >&2; exit 2 ;; esac
generated="$(dirname "$config")/generated-cloudbuild-gcp.env"
[ -f "$generated" ] && config="$generated"
# shellcheck disable=SC1090
source "$config"
: "${GCP_PROJECT_ID:?}" "${BUILDER_IMAGE:?}" "${SERVICE_ACCOUNT_EMAIL:?}" "${ARTIFACT_BUCKET:?}"

dl4j_ref="${3:-${DL4J_REF:?}}"
release_tag="${4:-${RELEASE_TAG:-}}"
kompile_sha="$(git -C "$root" rev-parse HEAD 2>/dev/null || echo "")"

effective="$(mktemp --suffix=.yaml)"
trap 'rm -f "$effective"' EXIT
CONFIG_IN="$root/gcp/cloudbuild/cloudbuild-$lane.yaml" CONFIG_OUT="$effective" \
LANE="$lane" GCP_PROJECT_ID="$GCP_PROJECT_ID" SERVICE_ACCOUNT_EMAIL="$SERVICE_ACCOUNT_EMAIL" \
MACHINE_TYPE="${MACHINE_TYPE:-}" WORKER_POOL="${WORKER_POOL:-}" \
GITHUB_RELEASE_TOKEN_SECRET="${GITHUB_RELEASE_TOKEN_SECRET:-}" \
python3 - <<'PY'
import os, yaml
doc = yaml.safe_load(open(os.environ["CONFIG_IN"]))
project = os.environ["GCP_PROJECT_ID"]
doc["serviceAccount"] = f"projects/{project}/serviceAccounts/{os.environ['SERVICE_ACCOUNT_EMAIL']}"
options = doc.setdefault("options", {})
if os.environ["LANE"] == "build":
    pool = os.environ.get("WORKER_POOL", "")
    machine = os.environ.get("MACHINE_TYPE", "")
    if pool:
        options["pool"] = {"name": pool}
    elif machine:
        options["machineType"] = machine
    secret = os.environ.get("GITHUB_RELEASE_TOKEN_SECRET", "")
    if secret:
        doc["availableSecrets"] = {"secretManager": [{
            "versionName": f"projects/{project}/secrets/{secret}/versions/latest",
            "env": "GITHUB_RELEASE_TOKEN"}]}
        doc["steps"][0]["secretEnv"] = ["GITHUB_RELEASE_TOKEN"]
yaml.safe_dump(doc, open(os.environ["CONFIG_OUT"], "w"), default_flow_style=False)
PY

if [ "$lane" = build ]; then
  subs="_BUILDER_IMAGE=${BUILDER_IMAGE}~_KOMPILE_SHA=${kompile_sha}~_DL4J_REPOSITORY=${DL4J_REPOSITORY:?}~_DL4J_REF=${dl4j_ref}"
  subs="$subs~_DL4J_BUILD_THREADS=${DL4J_BUILD_THREADS:-12}~_DL4J_MAVEN_OPTS=${DL4J_MAVEN_OPTS:--Xmx8g}"
  subs="$subs~_BUILD_KOMPILE=${BUILD_KOMPILE:-true}~_KOMPILE_VARIANT=${KOMPILE_VARIANT:-cli-only}"
  subs="$subs~_NATIVE_PARALLELISM=${NATIVE_PARALLELISM:-4}~_KOMPILE_MAVEN_OPTS=${KOMPILE_MAVEN_OPTS:--Xmx16g}"
  subs="$subs~_RELEASE_TAG=${release_tag}~_GITHUB_RELEASE_REPO=${GITHUB_RELEASE_REPO:-}"
  subs="$subs~_ARTIFACT_BUCKET=${ARTIFACT_BUCKET}~_RELEASE_PREFIX=${RELEASE_PREFIX:-releases}"
else
  subs="_TPU_ZONE=${TPU_ZONE:?}~_TPU_ACCELERATOR_TYPE=${TPU_ACCELERATOR_TYPE:-v5litepod-1}"
  subs="$subs~_TPU_RUNTIME_VERSION=${TPU_RUNTIME_VERSION:-tpu-ubuntu2204-base}~_TPU_SMOKE_TIMEOUT=${TPU_SMOKE_TIMEOUT:-7200}"
  subs="$subs~_DL4J_REPOSITORY=${DL4J_REPOSITORY:?}~_DL4J_REF=${dl4j_ref}~_DL4J_BUILD_THREADS=${DL4J_BUILD_THREADS:-12}"
  subs="$subs~_MAVEN_TEST_ARGS=${MAVEN_TEST_ARGS:-}~_ARTIFACT_BUCKET=${ARTIFACT_BUCKET}"
  subs="$subs~_RELEASE_PREFIX=${RELEASE_PREFIX:-releases}~_RELEASE_TAG=${release_tag}"
fi

build_id="$(cd "$root" && gcloud builds submit . \
  --project "$GCP_PROJECT_ID" --config "$effective" \
  --substitutions "^~^${subs}" --async --format 'value(id)')"
echo "Submitted $lane build: $build_id"
echo "Stream logs: gcloud builds log --stream $build_id --project $GCP_PROJECT_ID"
