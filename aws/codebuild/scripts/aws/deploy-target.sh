#!/usr/bin/env bash
# Deploy one target's CodeBuild project stack.
# Exit codes: 0 deployed, 3 skipped (config cannot host it — e.g. no macOS
# fleet), anything else is a real failure. deploy-all.sh aggregates these.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
config="${1:?Usage: deploy-target.sh CONFIG TARGET}"
target="${2:?Usage: deploy-target.sh CONFIG TARGET}"
targets_file="$root/aws/codebuild/targets.yml"
# shellcheck disable=SC1090
source "$config"
# shellcheck disable=SC1091
source "$root/aws/codebuild/hosts.sh"
# shellcheck disable=SC1091
source "$root/aws/codebuild/scripts/aws/targets-lib.sh"

host="$(target_field "$targets_file" "$target" host)"
[ -n "$host" ] || { echo "Unknown target: $target" >&2; exit 2; }
variant="$(target_field "$targets_file" "$target" variant)"
kompile="$(target_field "$targets_file" "$target" kompile)"
TARGET_IMAGE_KEY="$(target_field "$targets_file" "$target" image)"

configure_host "$host"
if [ -n "$SKIP_REASON" ]; then
  echo "SKIP $target: $SKIP_REASON"
  exit 3
fi

# A project attached to a fleet must carry the fleet's compute type.
if [ -n "$FLEET_ARN" ]; then
  fleet_compute="$(aws codebuild batch-get-fleets --region "$AWS_REGION" --names "$FLEET_ARN" \
    --query 'fleets[0].computeType' --output text 2>/dev/null | grep -v '^None$' || true)"
  [ -n "$fleet_compute" ] && COMPUTE_TYPE="$fleet_compute"
fi

args=(
  "ProjectName=${PROJECT_PREFIX:-kompile}-$target"
  "SourceLocation=$SOURCE_LOCATION"
  "KompileSourceVersion=$KOMPILE_REF"
  "BuildspecPath=$BUILD_SPEC"
  "BuildTarget=$target"
  "BuildImage=$BUILD_IMAGE"
  "ImagePullCredentialsType=$IMAGE_PULL_CREDS"
  "EnvironmentType=$ENVIRONMENT_TYPE"
  "ComputeType=$COMPUTE_TYPE"
  "FleetArn=$FLEET_ARN"
  "ArtifactBucket=$ARTIFACT_BUCKET"
  "CacheBucket=$CACHE_BUCKET"
  "Dl4jRepository=$DL4J_REPOSITORY"
  "Dl4jRef=$DL4J_REF"
  "Dl4jTokenSecret=${DL4J_TOKEN_SECRET:-}"
  "Dl4jBuildThreads=${DL4J_BUILD_THREADS:-12}"
  "Dl4jExtraMavenArgs=${DL4J_EXTRA_MAVEN_ARGS:-}"
  "Dl4jMavenOpts=${DL4J_MAVEN_OPTS:-}"
  "MavenTestArgs=${MAVEN_TEST_ARGS:-}"
  "RunTestsProfiles=${RUN_TESTS_PROFILES:-}"
  "RunTestsModules=${RUN_TESTS_MODULES:-}"
  "RocmHome=${ROCM_HOME:-/opt/rocm}"
  "ZludaHome=${ZLUDA_HOME:-/opt/zluda}"
  "PjrtLibraryPath=${PJRT_LIBRARY_PATH:-}"
  "HexagonSdkRoot=${HEXAGON_SDK_ROOT:-/opt/hexagon}"
  "Java11Home=$JAVA11_HOME_EFFECTIVE"
  "GraalVmHome=$GRAALVM_HOME_EFFECTIVE"
  "AndroidNdkHome=${ANDROID_NDK_HOME:-/opt/android-ndk}"
  "CudaComputeCapabilities=${CUDA_COMPUTE_CAPABILITIES:-8.6 9.0}"
  "CudaVersion=${CUDA_VERSION:-12.9}"
  "ZludaTarget=${ZLUDA_TARGET:-rdna3}"
  "BuildKompile=${kompile:-true}"
  "KompileVariant=${variant:-cli-only}"
  "NativeParallelism=${NATIVE_PARALLELISM:-4}"
  "KompileMavenOpts=${KOMPILE_MAVEN_OPTS:-}"
  "ReleasePrefix=${RELEASE_PREFIX:-releases}"
  "GithubReleaseRepo=${GITHUB_RELEASE_REPO:-}"
  "GithubReleaseTokenSecret=${GITHUB_RELEASE_TOKEN_SECRET:-}"
  "WebhookBranch=${WEBHOOK_BRANCH:-}"
)
aws cloudformation deploy --region "$AWS_REGION" \
  --stack-name "${STACK_PREFIX:-kompile}-$target" \
  --template-file "$root/aws/codebuild/template.yml" \
  --capabilities CAPABILITY_IAM \
  --no-fail-on-empty-changeset \
  --parameter-overrides "${args[@]}"
echo "DEPLOYED $target (${ENVIRONMENT_TYPE}, ${COMPUTE_TYPE}${FLEET_ARN:+, fleet})"
