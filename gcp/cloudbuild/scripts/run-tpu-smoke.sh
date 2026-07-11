#!/usr/bin/env bash
# TPU hardware smoke, run inside Cloud Build (cloud-sdk image): create a TPU
# VM, install the toolchain on it, build the checked-out DL4J TPU backend and
# run the platform tests against real TPU hardware via libtpu/PJRT, upload
# the log, and ALWAYS delete the TPU VM (trap on EXIT — TPU time is money).
set -euo pipefail
: "${GCP_PROJECT_ID:?}" "${TPU_ZONE:?}" "${TPU_ACCELERATOR_TYPE:?}" "${TPU_RUNTIME_VERSION:?}"
: "${DL4J_REPOSITORY:?}" "${DL4J_REF:?}"

name="kompile-tpu-$(date +%s)-$$"
log() { echo "tpu-smoke: $*" >&2; }

cleanup() {
  log "deleting TPU VM $name"
  gcloud compute tpus tpu-vm delete "$name" --project "$GCP_PROJECT_ID" \
    --zone "$TPU_ZONE" --quiet 2>/dev/null || true
}
trap cleanup EXIT

log "creating TPU VM $name ($TPU_ACCELERATOR_TYPE, $TPU_RUNTIME_VERSION, $TPU_ZONE)"
gcloud compute tpus tpu-vm create "$name" \
  --project "$GCP_PROJECT_ID" --zone "$TPU_ZONE" \
  --accelerator-type "$TPU_ACCELERATOR_TYPE" \
  --version "$TPU_RUNTIME_VERSION" --quiet

for _ in $(seq 1 60); do
  state="$(gcloud compute tpus tpu-vm describe "$name" --project "$GCP_PROJECT_ID" \
    --zone "$TPU_ZONE" --format='value(state)' 2>/dev/null || true)"
  [ "$state" = READY ] && break
  sleep 10
done
[ "$state" = READY ] || { log "TPU VM never reached READY (state: ${state:-unknown})"; exit 1; }

payload="$(mktemp)"
{
  printf 'export DL4J_REPOSITORY=%q DL4J_REF=%q\n' "$DL4J_REPOSITORY" "$DL4J_REF"
  printf 'export DL4J_BUILD_THREADS=%q MAVEN_TEST_ARGS=%q\n' \
    "${DL4J_BUILD_THREADS:-12}" "${MAVEN_TEST_ARGS:-}"
  cat <<'PAYLOAD'
set -euo pipefail
sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  openjdk-11-jdk maven git build-essential gfortran cmake protobuf-compiler \
  python3-pip zlib1g-dev

# Locate libtpu: preinstalled on TPU runtime images, else pip-installable.
libtpu=""
for candidate in /usr/lib/libtpu.so /lib/libtpu.so; do
  [ -f "$candidate" ] && { libtpu="$candidate"; break; }
done
if [ -z "$libtpu" ]; then
  pip3 install --quiet libtpu -f https://storage.googleapis.com/libtpu-releases/index.html
  libtpu="$(python3 -c 'import libtpu, os; print(os.path.join(os.path.dirname(libtpu.__file__), "libtpu.so"))')"
fi
[ -f "$libtpu" ] || { echo "libtpu.so not found"; exit 2; }
# DL4J reads PJRT_PATH / PJRT_PLUGIN_LIBRARY_PATH / TPU_LIBRARY_PATH.
export PJRT_PATH="$(dirname "$libtpu")" PJRT_PLUGIN_LIBRARY_PATH="$libtpu" \
       TPU_LIBRARY_PATH="$libtpu" PJRT_LIBRARY_PATH="$libtpu"
echo "using libtpu: $libtpu"

rm -rf ~/deeplearning4j
git clone --filter=blob:none --no-checkout "$DL4J_REPOSITORY" ~/deeplearning4j
cd ~/deeplearning4j
git fetch --depth 1 origin "$DL4J_REF"
git checkout --detach FETCH_HEAD

extra=()
[ -z "$MAVEN_TEST_ARGS" ] || read -r -a extra <<< "$MAVEN_TEST_ARGS"
mvn -Ptpu -pl :nd4j-tpu,platform-tests --also-make test -Dlibnd4j.tpu \
  --batch-mode --no-transfer-progress -DskipTestResourceEnforcement=true \
  "-Dlibnd4j.buildthreads=$DL4J_BUILD_THREADS" ${extra[@]+"${extra[@]}"}
PAYLOAD
} > "$payload"

log "running smoke payload on $name (timeout ${TPU_SMOKE_TIMEOUT:-7200}s)"
gcloud compute tpus tpu-vm scp "$payload" "$name:/tmp/tpu-smoke.sh" \
  --project "$GCP_PROJECT_ID" --zone "$TPU_ZONE" --quiet
rc=0
timeout "${TPU_SMOKE_TIMEOUT:-7200}" gcloud compute tpus tpu-vm ssh "$name" \
  --project "$GCP_PROJECT_ID" --zone "$TPU_ZONE" --quiet \
  --command 'bash /tmp/tpu-smoke.sh 2>&1 | tee /tmp/tpu-smoke.log; exit "${PIPESTATUS[0]}"' \
  || rc=$?

gcloud compute tpus tpu-vm scp "$name:/tmp/tpu-smoke.log" /tmp/tpu-smoke.log \
  --project "$GCP_PROJECT_ID" --zone "$TPU_ZONE" --quiet 2>/dev/null || true
if [ -f /tmp/tpu-smoke.log ] && [ -n "${ARTIFACT_BUCKET:-}" ]; then
  version="${RELEASE_TAG:-adhoc}"
  dest="gs://${ARTIFACT_BUCKET}/${RELEASE_PREFIX:-releases}/${version}/tpu-smoke/tpu-smoke.log"
  gcloud storage cp /tmp/tpu-smoke.log "$dest" || log "WARNING: log upload failed"
  log "log uploaded to $dest"
fi

if [ "$rc" = 124 ]; then
  log "smoke timed out after ${TPU_SMOKE_TIMEOUT:-7200}s"
elif [ "$rc" != 0 ]; then
  log "smoke FAILED (exit $rc)"
else
  log "smoke PASSED"
fi
exit "$rc"
