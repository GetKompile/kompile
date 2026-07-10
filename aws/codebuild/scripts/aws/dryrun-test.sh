#!/usr/bin/env bash
# Offline regression check for the provisioning glue: runs resolve-config,
# preflight, and deploy-target for EVERY target against a fake `aws` CLI,
# asserting that each target either deploys (with sane parameters) or skips
# cleanly. No AWS calls are made. Run it after touching targets.yml,
# hosts.sh, or the deploy scripts:
#   aws/codebuild/scripts/aws/dryrun-test.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
here="$root/aws/codebuild/scripts/aws"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export DRYRUN_LOG="$work/cfn-calls.log"
: > "$DRYRUN_LOG"

# --- fake aws + curl ----------------------------------------------------------
mkdir -p "$work/bin"
cat > "$work/bin/aws" <<'FAKE'
#!/usr/bin/env bash
case "$1 $2" in
  "sts get-caller-identity") echo 123456789012 ;;
  "configure get") echo us-east-1 ;;
  "codebuild batch-get-fleets"|"codebuild batch-get-projects") echo None ;;
  "cloudformation deploy") printf '%s\n' "$*" >> "${DRYRUN_LOG:?}" ;;
  "secretsmanager describe-secret") exit 255 ;;
  *) : ;;
esac
FAKE
printf '#!/usr/bin/env bash\nexit 0\n' > "$work/bin/curl"
chmod +x "$work/bin/aws" "$work/bin/curl"
export PATH="$work/bin:$PATH"

# --- synthetic config ----------------------------------------------------------
config="$work/config.env"
cat > "$config" <<EOF
AWS_REGION=us-east-1
SOURCE_LOCATION=$root
KOMPILE_REF=HEAD
DL4J_REPOSITORY=https://example.invalid/deeplearning4j.git
DL4J_REF=master
GRAALVM_ARCHIVE_URL=https://example.invalid/graalvm.tgz
JDK11_ARCHIVE_URL=https://example.invalid/jdk11.tgz
EOF

fail() { echo "DRYRUN FAIL: $*" >&2; exit 1; }

merged="$work/generated.env"
cp "$config" "$merged"
"$here/resolve-config.sh" "$merged" >> "$merged"
grep -q 'LINUX_IMAGE=123456789012.dkr.ecr.us-east-1.amazonaws.com/kompile-build:linux' "$merged" \
  || fail "resolve-config did not derive LINUX_IMAGE"

"$here/preflight-check.sh" "$merged" all > "$work/preflight.out" \
  || fail "preflight errored on a valid config: $(cat "$work/preflight.out")"

# --- every target must deploy or skip cleanly ---------------------------------
# shellcheck disable=SC1091
source "$here/targets-lib.sh"
deployed=0 skipped=0
while read -r target _; do
  rc=0
  out="$("$here/deploy-target.sh" "$merged" "$target" 2>&1)" || rc=$?
  case "$rc" in
    0) deployed=$((deployed + 1)) ;;
    3) skipped=$((skipped + 1)) ;;
    *) fail "deploy-target $target exited $rc: $out" ;;
  esac
done < <(list_targets "$root/aws/codebuild/targets.yml" all)

expect() { # expect TARGET REGEX
  local line
  line="$(grep -- "ProjectName=kompile-$1 " "$DRYRUN_LOG" || true)"
  [ -n "$line" ] || fail "$1 was not deployed"
  echo "$line" | grep -q -- "$2" || fail "$1 missing expected parameter: $2"
}
expect linux-x86_64 'KompileVariant=cpu-intel'
expect linux-x86_64 'ComputeType=BUILD_GENERAL1_2XLARGE'
expect linux-x86_64 'BuildKompile=true'
expect linux-arm64 'EnvironmentType=ARM_CONTAINER'
expect linux-arm64 'KompileVariant=cpu-arm'
expect linux-cuda-12.6 'BuildImage=[^ ]*:cuda-12.6'
expect linux-cuda-12.9 'BuildImage=[^ ]*:cuda-12.9'
expect linux-cuda-12.9 'KompileVariant=cuda'
expect linux-cuda-12.9 'EnvironmentType=LINUX_CONTAINER'
expect linux-zluda 'KompileVariant=amd-zluda'
expect linux-zluda 'BuildImage=[^ ]*:amd'
expect android-arm64 'BuildKompile=false'
expect gpu-tests-linux 'EnvironmentType=LINUX_GPU_CONTAINER'
expect gpu-tests-linux 'ComputeType=BUILD_GENERAL1_SMALL'
expect cpu-sanity-linux 'BuildKompile=false'
grep -q -- 'ProjectName=kompile-macos-arm64 ' "$DRYRUN_LOG" && fail "macos-arm64 deployed without a fleet"
grep -q -- 'no-fail-on-empty-changeset' "$DRYRUN_LOG" || fail "deploys are not idempotent"

# With a macOS fleet configured, macos-arm64 must deploy on MAC_ARM.
printf 'MACOS_FLEET_ARN=arn:aws:codebuild:us-east-1:123456789012:fleet/kompile-macos:uuid\n' >> "$merged"
rc=0; "$here/deploy-target.sh" "$merged" macos-arm64 >/dev/null 2>&1 || rc=$?
[ "$rc" = 0 ] || fail "macos-arm64 with fleet exited $rc"
expect macos-arm64 'EnvironmentType=MAC_ARM'
expect macos-arm64 'ImagePullCredentialsType=CODEBUILD'

# --- setup wizard, scripted (public repos, no publishing, decline provision) --
wizard_config="$work/wizard.env"
# answers: region, kompile url, kompile ref, dl4j url, dl4j ref, graal url,
# jdk url, arm?, private?, gh releases?, targets, compute, [macos?], provision?
printf '%s\n' "" "" "" "" "" "" "" "" "" "" "" "" "" "n" \
  | "$here/setup-wizard.sh" "$wizard_config" > "$work/wizard.out" 2>&1 \
  || fail "setup-wizard failed: $(tail -20 "$work/wizard.out")"
[ -f "$wizard_config" ] || fail "wizard did not write $wizard_config"
grep -q '^AWS_REGION=us-east-1$' "$wizard_config" || fail "wizard did not set AWS_REGION"
grep -q '^GRAALVM_ARCHIVE_URL=https://github.com/graalvm/' "$wizard_config" \
  || fail "wizard did not set the default GraalVM archive"
grep -q '^LINUX_COMPUTE_TYPE=BUILD_GENERAL1_2XLARGE$' "$wizard_config" \
  || fail "wizard did not set the default compute type"
grep -q '^SOURCE_LOCATION=..*$' "$wizard_config" || fail "wizard did not set SOURCE_LOCATION"
"$here/preflight-check.sh" <(cat "$wizard_config"; "$here/resolve-config.sh" "$wizard_config") build \
  > /dev/null || fail "wizard-produced config does not pass preflight"

total=$((deployed + skipped))
echo "DRYRUN OK: $total targets ($deployed deployed, $skipped skipped cleanly); wizard config OK"
