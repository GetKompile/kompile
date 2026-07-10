#!/usr/bin/env bash
set -euo pipefail
config="${1:?Usage: configure-source.sh CONFIG GITHUB_TOKEN_SECRET_ID}"
secret="${2:?Secret id required}"
source "$config"
token="$(aws secretsmanager get-secret-value --region "$AWS_REGION" --secret-id "$secret" --query SecretString --output text)"
aws codebuild import-source-credentials --region "$AWS_REGION" --server-type GITHUB --auth-type PERSONAL_ACCESS_TOKEN --token "$token"
unset token
