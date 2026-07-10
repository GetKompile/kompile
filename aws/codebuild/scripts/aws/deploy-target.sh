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
privileged="$(target_field "$targets_file" "$target" privileged)"
buildspec_override="$(target_field "$targets_file" "$target" buildspec)"
TARGET_IMAGE_KEY="$(target_field "$targets_file" "$target" image)"

configure_host "$host"
[ -n "$buildspec_override" ] && BUILD_SPEC="$buildspec_override"
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
  "PrivilegedMode=${privileged:-false}"
  "EcrRegistry=${ECR_REGISTRY:-}"
  "SpinRepository=${SPIN_REPOSITORY:-}"
)
# DEPLOY_PLAN=1 turns this into a server-validated dry run: the change set is
# created (CloudFormation validates parameters/properties) but never executed,
# then cleaned up — including the REVIEW_IN_PROGRESS shell of a new stack.
stack="${STACK_PREFIX:-kompile}-$target"
plan="${DEPLOY_PLAN:-0}"
deploy_flags=(--no-fail-on-empty-changeset)
pre_exists=1
if [ "$plan" = 1 ]; then
  deploy_flags+=(--no-execute-changeset)
  aws cloudformation describe-stacks --region "$AWS_REGION" --stack-name "$stack" >/dev/null 2>&1 || pre_exists=0
fi
deploy_out=/dev/stdout
[ "$plan" = 1 ] && deploy_out=/dev/null
aws cloudformation deploy --region "$AWS_REGION" \
  --stack-name "$stack" \
  --template-file "$root/aws/codebuild/template.yml" \
  --capabilities CAPABILITY_IAM \
  "${deploy_flags[@]}" \
  --parameter-overrides "${args[@]}" > "$deploy_out"
if [ "$plan" = 1 ]; then
  cs="$(aws cloudformation list-change-sets --region "$AWS_REGION" --stack-name "$stack" \
    --query 'reverse(sort_by(Summaries,&CreationTime))[0].ChangeSetId' --output text 2>/dev/null \
    | grep -v '^None$' || true)"
  changes="?"
  if [ -n "$cs" ]; then
    changes="$(aws cloudformation describe-change-set --region "$AWS_REGION" --stack-name "$stack" \
      --change-set-name "$cs" --query 'length(Changes)' --output text 2>/dev/null || echo '?')"
    aws cloudformation delete-change-set --region "$AWS_REGION" --stack-name "$stack" \
      --change-set-name "$cs" 2>/dev/null || true
  fi
  if [ "$pre_exists" = 0 ]; then
    aws cloudformation delete-stack --region "$AWS_REGION" --stack-name "$stack" 2>/dev/null || true
    echo "PLANNED $target: would CREATE (${changes} resource changes, server-validated; nothing kept)"
  else
    echo "PLANNED $target: ${changes} resource change(s) to the existing stack"
  fi
else
  echo "DEPLOYED $target (${ENVIRONMENT_TYPE}, ${COMPUTE_TYPE}${FLEET_ARN:+, fleet})"
fi
