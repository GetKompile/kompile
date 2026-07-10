#!/usr/bin/env bash
# Offline regression check for the GCP TPU-lane glue: fakes the gcloud CLI
# and exercises resolve/preflight/provision/start-build/teardown/wizard.
# Run after touching anything under gcp/cloudbuild:
#   gcp/cloudbuild/scripts/dryrun-test.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
here="$root/gcp/cloudbuild/scripts"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export DRYRUN_LOG="$work/gcloud-calls.log"
: > "$DRYRUN_LOG"

mkdir -p "$work/bin"
cat > "$work/bin/gcloud" <<'FAKE'
#!/usr/bin/env bash
case "$1 ${2:-}" in
  "auth list") echo tester@example.com ;;
  "config get-value") echo test-project ;;
  "projects describe") : ;;
  "services list") echo cloudbuild.googleapis.com ;;
  "secrets describe") exit 1 ;;
  "builds submit") printf 'SUBMIT %s\n' "$*" >> "${DRYRUN_LOG:?}"; echo fake-build-id ;;
  "compute tpus")
    case "${3:-}" in
      accelerator-types) : ;;
      tpu-vm)
        if [ "${4:-}" = list ]; then echo kompile-tpu-123
        else printf 'TPU %s\n' "$*" >> "${DRYRUN_LOG:?}"; fi ;;
    esac ;;
  *) printf 'GCLOUD %s\n' "$*" >> "${DRYRUN_LOG:?}" ;;
esac
FAKE
printf '#!/usr/bin/env bash\nexit 0\n' > "$work/bin/curl"
chmod +x "$work/bin/gcloud" "$work/bin/curl"
export PATH="$work/bin:$PATH"

fail() { echo "DRYRUN FAIL: $*" >&2; exit 1; }

config="$work/config.env"
cat > "$config" <<EOF
GCP_PROJECT_ID=test-project
GCP_REGION=us-central1
TPU_ZONE=us-central1-a
DL4J_REPOSITORY=https://example.invalid/deeplearning4j.git
DL4J_REF=master
GRAALVM_ARCHIVE_URL=https://example.invalid/graalvm.tgz
JDK11_ARCHIVE_URL=https://example.invalid/jdk11.tgz
TPU_ACCELERATOR_TYPE=v5litepod-1
TPU_RUNTIME_VERSION=tpu-ubuntu2204-base
EOF

merged="$work/merged.env"
cp "$config" "$merged"
"$here/resolve-config.sh" "$merged" >> "$merged"
grep -q 'BUILDER_IMAGE=us-central1-docker.pkg.dev/test-project/kompile-build/tpu:latest' "$merged" \
  || fail "resolve-config did not derive BUILDER_IMAGE"

"$here/preflight-check.sh" "$merged" > "$work/preflight.out" \
  || fail "preflight errored on a valid config: $(cat "$work/preflight.out")"

"$here/provision.sh" "$config" > "$work/provision.out" 2>&1 \
  || fail "provision failed: $(tail -20 "$work/provision.out")"
grep -q 'GCLOUD services enable' "$DRYRUN_LOG" || fail "provision did not enable services"
grep -q 'SUBMIT builds submit' "$DRYRUN_LOG" || fail "provision did not submit the builder image build"

# --- provision --plan: read-only, nothing submitted ---------------------------
: > "$DRYRUN_LOG"
"$here/provision.sh" "$config" --plan > "$work/plan.out" 2>&1 \
  || fail "provision --plan failed: $(tail -20 "$work/plan.out")"
grep -q 'PLAN COMPLETE' "$work/plan.out" || fail "gcp plan did not complete"
if grep -q 'SUBMIT' "$DRYRUN_LOG"; then fail "gcp plan submitted a build"; fi
if grep -q 'GCLOUD services enable' "$DRYRUN_LOG"; then fail "gcp plan enabled services"; fi

for lane in build smoke; do
  "$here/start-build.sh" "$config" "$lane" > "$work/start-$lane.out" 2>&1 \
    || fail "start-build $lane failed: $(cat "$work/start-$lane.out")"
  grep -q 'Submitted '"$lane"' build: fake-build-id' "$work/start-$lane.out" \
    || fail "start-build $lane did not report a build id"
done
grep -q -- '_TPU_ZONE=us-central1-a' "$DRYRUN_LOG" || fail "smoke submit missing TPU zone substitution"

: > "$DRYRUN_LOG"
"$here/teardown.sh" "$config" --all --yes > "$work/teardown.out" 2>&1 \
  || fail "teardown --all --yes failed: $(tail -20 "$work/teardown.out")"
grep -q 'TPU compute tpus tpu-vm delete kompile-tpu-123' "$DRYRUN_LOG" \
  || fail "teardown did not delete the stray TPU VM"
grep -q 'GCLOUD storage rm' "$DRYRUN_LOG" || fail "teardown did not remove the bucket"
grep -q 'GCLOUD iam service-accounts delete' "$DRYRUN_LOG" || fail "teardown did not delete the SA"

# --- wizard, scripted (defaults, no GH releases, decline provision) -----------
wizard_config="$work/wizard.env"
# answers: project, region, tpu zone, accelerator, runtime, dl4j url, dl4j
# ref, graal, jdk, build-kompile?, gh-releases?, provision?
printf '%s\n' "" "" "us-central1-a" "" "" "" "" "" "" "" "" "n" \
  | "$here/setup-wizard.sh" "$wizard_config" > "$work/wizard.out" 2>&1 \
  || fail "setup-wizard failed: $(tail -20 "$work/wizard.out")"
grep -q '^GCP_PROJECT_ID=test-project$' "$wizard_config" || fail "wizard did not set the project"
grep -q '^TPU_ZONE=us-central1-a$' "$wizard_config" || fail "wizard did not set the TPU zone"
grep -q '^TPU_ACCELERATOR_TYPE=v5litepod-1$' "$wizard_config" || fail "wizard did not set the accelerator"

# --- teardown wizard, scripted (tpus only, proceed) ---------------------------
: > "$DRYRUN_LOG"
# answers: tpus(y), images(n), pool(n), secrets(n), buckets(n), iam(n), proceed(y)
printf '%s\n' "" "" "" "" "" "" "y" \
  | "$here/setup-wizard.sh" --teardown "$wizard_config" > "$work/teardown-wizard.out" 2>&1 \
  || fail "teardown wizard failed: $(tail -20 "$work/teardown-wizard.out")"
grep -q 'TPU compute tpus tpu-vm delete kompile-tpu-123' "$DRYRUN_LOG" \
  || fail "teardown wizard did not delete the stray TPU VM"

echo "DRYRUN OK: gcp provision/start/teardown/wizard flows verified"
