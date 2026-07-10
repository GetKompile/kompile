#!/usr/bin/env bash
# Idempotently create the account-level resources every lane shares:
# artifact/cache buckets (encrypted, private, cache auto-expiring), the single
# ECR repository, and the fleet service role custom-AMI fleets require.
# Emits derived values (FLEET_SERVICE_ROLE_ARN) to stdout for the merged config.
set -euo pipefail
config="${1:?Usage: bootstrap-account.sh CONFIG}"
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?}" "${ARTIFACT_BUCKET:?}" "${CACHE_BUCKET:?}" "${ECR_REPOSITORY:?}"

log() { echo "bootstrap: $*" >&2; }

for bucket in "$ARTIFACT_BUCKET" "$CACHE_BUCKET"; do
  if ! aws s3api head-bucket --bucket "$bucket" 2>/dev/null; then
    log "creating bucket $bucket"
    if [ "$AWS_REGION" = us-east-1 ]; then
      aws s3api create-bucket --bucket "$bucket" --region "$AWS_REGION" >/dev/null
    else
      aws s3api create-bucket --bucket "$bucket" --region "$AWS_REGION" \
        --create-bucket-configuration "LocationConstraint=$AWS_REGION" >/dev/null
    fi
  fi
  aws s3api put-bucket-encryption --bucket "$bucket" --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
  aws s3api put-public-access-block --bucket "$bucket" --public-access-block-configuration \
    'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'
done
aws s3api put-bucket-lifecycle-configuration --bucket "$CACHE_BUCKET" --lifecycle-configuration '{
  "Rules": [{"ID": "expire-build-cache", "Status": "Enabled", "Filter": {"Prefix": ""},
             "Expiration": {"Days": 60},
             "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}}]}'

aws ecr describe-repositories --region "$AWS_REGION" --repository-names "$ECR_REPOSITORY" >/dev/null 2>&1 \
  || { log "creating ECR repository $ECR_REPOSITORY"; \
       aws ecr create-repository --region "$AWS_REGION" --repository-name "$ECR_REPOSITORY" >/dev/null; }

# Fleet service role: required when a reserved fleet uses a custom AMI
# (ec2:DescribeImages) and when fleets attach to a VPC (network-interface set).
role_name="${PROJECT_PREFIX:-kompile}-codebuild-fleet-role"
if ! aws iam get-role --role-name "$role_name" >/dev/null 2>&1; then
  log "creating fleet service role $role_name"
  aws iam create-role --role-name "$role_name" --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{"Effect": "Allow", "Principal": {"Service": "codebuild.amazonaws.com"},
                   "Action": "sts:AssumeRole"}]}' >/dev/null
fi
aws iam put-role-policy --role-name "$role_name" --policy-name fleet-permissions --policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow",
     "Action": ["ec2:DescribeImages", "ec2:DescribeDhcpOptions", "ec2:DescribeNetworkInterfaces",
                "ec2:DescribeSecurityGroups", "ec2:DescribeSubnets", "ec2:DescribeVpcs",
                "ec2:CreateNetworkInterface", "ec2:DeleteNetworkInterface",
                "ec2:ModifyNetworkInterfaceAttribute"],
     "Resource": "*"},
    {"Effect": "Allow", "Action": "ec2:CreateNetworkInterfacePermission", "Resource": "*",
     "Condition": {"StringEquals": {"ec2:AuthorizedService": "codebuild.amazonaws.com"}}}
  ]}'
role_arn="$(aws iam get-role --role-name "$role_name" --query Role.Arn --output text)"
[ -n "${FLEET_SERVICE_ROLE_ARN:-}" ] || printf 'FLEET_SERVICE_ROLE_ARN=%s\n' "$role_arn"
