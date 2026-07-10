#!/usr/bin/env bash
# Store a GitHub personal access token in Secrets Manager (create or update)
# and register it as the account's CodeBuild GitHub source credential.
# The token is read from stdin so it never lands in shell history or files:
#   printf '%s' "$TOKEN" | scripts/aws/set-github-token.sh CONFIG my-secret-id
set -euo pipefail
config="${1:?Usage: set-github-token.sh CONFIG SECRET_ID (token on stdin)}"
secret="${2:?Secret id required}"
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?}"

token="$(cat)"
[ -n "$token" ] || { echo "No token on stdin" >&2; exit 2; }

if aws secretsmanager describe-secret --region "$AWS_REGION" --secret-id "$secret" >/dev/null 2>&1; then
  aws secretsmanager put-secret-value --region "$AWS_REGION" --secret-id "$secret" \
    --secret-string "$token" >/dev/null
else
  aws secretsmanager create-secret --region "$AWS_REGION" --name "$secret" \
    --secret-string "$token" >/dev/null
fi
unset token

"$(dirname "$0")/configure-source.sh" "$config" "$secret"
echo "Stored token in '$secret' and registered CodeBuild GitHub credentials."
