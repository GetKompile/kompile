#!/usr/bin/env bash
# Tear down what provision.sh created.
#   teardown.sh CONFIG [--stacks] [--fleets] [--images] [--buckets] [--iam] [--all] [--yes]
# Default (no flags): --stacks. Buckets require --yes (their contents are
# deleted). Reserved fleets bill while they exist — delete them when idle.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
config="${1:?Usage: teardown.sh CONFIG [--stacks] [--fleets] [--images] [--buckets] [--iam] [--all] [--yes]}"
shift
generated="$(dirname "$config")/generated-codebuild.env"
[ -f "$generated" ] && config="$generated"
# shellcheck disable=SC1090
source "$config"
# shellcheck disable=SC1091
source "$root/aws/codebuild/scripts/aws/targets-lib.sh"
: "${AWS_REGION:?}"
prefix="${PROJECT_PREFIX:-kompile}"

do_stacks=0; do_fleets=0; do_images=0; do_buckets=0; do_iam=0; do_seed=0; yes=0
[ $# -eq 0 ] && do_stacks=1
for arg in "$@"; do
  case "$arg" in
    --stacks) do_stacks=1 ;;
    --fleets) do_fleets=1 ;;
    --images) do_images=1 ;;
    --buckets) do_buckets=1 ;;
    --iam) do_iam=1 ;;
    --seed) do_seed=1 ;;
    --all) do_stacks=1; do_fleets=1; do_images=1; do_buckets=1; do_iam=1; do_seed=1 ;;
    --yes) yes=1 ;;
    *) echo "Unknown flag: $arg" >&2; exit 2 ;;
  esac
done

if [ "$do_stacks" = 1 ]; then
  while read -r target _; do
    stack="${STACK_PREFIX:-kompile}-$target"
    if aws cloudformation describe-stacks --region "$AWS_REGION" --stack-name "$stack" >/dev/null 2>&1; then
      echo "deleting stack $stack"
      aws cloudformation delete-stack --region "$AWS_REGION" --stack-name "$stack"
    fi
  done < <(list_targets "$root/aws/codebuild/targets.yml" all)
fi

if [ "$do_fleets" = 1 ]; then
  for name in macos windows windows-cuda amd-gpu gpu linux arm; do
    arn="$(aws codebuild batch-get-fleets --region "$AWS_REGION" --names "$prefix-$name" \
      --query 'fleets[0].arn' --output text 2>/dev/null | grep -v '^None$' || true)"
    if [ -n "$arn" ]; then
      echo "deleting fleet $prefix-$name"
      aws codebuild delete-fleet --region "$AWS_REGION" --arn "$arn"
    fi
  done
fi

if [ "$do_images" = 1 ] && [ -n "${ECR_REPOSITORY:-}" ]; then
  if aws ecr describe-repositories --region "$AWS_REGION" --repository-names "$ECR_REPOSITORY" >/dev/null 2>&1; then
    echo "deleting ECR repository $ECR_REPOSITORY"
    aws ecr delete-repository --region "$AWS_REGION" --repository-name "$ECR_REPOSITORY" --force >/dev/null
  fi
fi

if [ "$do_buckets" = 1 ]; then
  [ "$yes" = 1 ] || { echo "--buckets deletes all artifacts; add --yes to confirm" >&2; exit 2; }
  for bucket in "${ARTIFACT_BUCKET:-}" "${CACHE_BUCKET:-}"; do
    [ -n "$bucket" ] || continue
    if aws s3api head-bucket --bucket "$bucket" 2>/dev/null; then
      echo "deleting bucket $bucket"
      aws s3 rb "s3://$bucket" --force
    fi
  done
fi

if [ "$do_seed" = 1 ]; then
  seed_stack="$prefix-seed"
  if aws cloudformation describe-stacks --region "$AWS_REGION" --stack-name "$seed_stack" >/dev/null 2>&1; then
    seed_bucket="$(aws cloudformation describe-stacks --region "$AWS_REGION" --stack-name "$seed_stack" \
      --query 'Stacks[0].Outputs[?OutputKey==`ConfigBucket`].OutputValue' --output text 2>/dev/null | grep -v '^None$' || true)"
    if [ -n "$seed_bucket" ]; then
      echo "emptying seed config bucket $seed_bucket"
      aws s3 rm --recursive "s3://$seed_bucket" >/dev/null 2>&1 || true
    fi
    echo "deleting seed stack $seed_stack"
    aws cloudformation delete-stack --region "$AWS_REGION" --stack-name "$seed_stack"
  fi
fi

if [ "$do_iam" = 1 ]; then
  for role in "$prefix-codebuild-fleet-role" "$prefix-ami-baker"; do
    if aws iam get-role --role-name "$role" >/dev/null 2>&1; then
      echo "deleting role $role"
      for p in $(aws iam list-role-policies --role-name "$role" --query 'PolicyNames[]' --output text); do
        aws iam delete-role-policy --role-name "$role" --policy-name "$p"
      done
      for p in $(aws iam list-attached-role-policies --role-name "$role" --query 'AttachedPolicies[].PolicyArn' --output text); do
        aws iam detach-role-policy --role-name "$role" --policy-arn "$p"
      done
      if aws iam get-instance-profile --instance-profile-name "$role" >/dev/null 2>&1; then
        aws iam remove-role-from-instance-profile --instance-profile-name "$role" --role-name "$role" 2>/dev/null || true
        aws iam delete-instance-profile --instance-profile-name "$role"
      fi
      aws iam delete-role --role-name "$role"
    fi
  done
fi
echo "teardown: done"
