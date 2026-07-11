#!/usr/bin/env bash
# Package the LOCAL tree (tracked files, minus SOURCE_EXCLUDES prefixes) and
# upload it as the S3 build source — for branches that cannot be pushed
# (WIP features, private data on a public repo). Projects deployed with
# SOURCE_MODE=s3 fetch s3://$ARTIFACT_BUCKET/source/kompile.zip; re-run this
# (provision.sh and release.sh do it automatically) to ship the latest tree.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
config="${1:?Usage: sync-source.sh CONFIG}"
generated="$(dirname "$config")/generated-codebuild.env"
[ -f "$generated" ] && config="$generated"
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?}" "${ARTIFACT_BUCKET:?}"
excludes="${SOURCE_EXCLUDES:-kompile-fpna- dist/ .codebuild/}"
zipfile="$(mktemp --suffix=.zip)"
trap 'rm -f "$zipfile"' EXIT
pattern="$(printf '%s\n' $excludes | paste -sd'|')"
( cd "$root" && git ls-files -z | grep -zEv "^($pattern)" | xargs -0 zip -q "$zipfile" )
echo "sync-source: $(du -h "$zipfile" | cut -f1) tracked source (excluded: $excludes)"
aws s3 cp --only-show-errors "$zipfile" "s3://$ARTIFACT_BUCKET/source/kompile.zip"
echo "sync-source: uploaded s3://$ARTIFACT_BUCKET/source/kompile.zip"
