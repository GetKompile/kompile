#!/usr/bin/env bash
# Start one target's build with optional per-run overrides.
#   start-build.sh CONFIG TARGET [KOMPILE_REF] [DL4J_REF] [RELEASE_TAG]
# RELEASE_TAG makes publish-dist upload dist files to that GitHub Release
# (when GITHUB_RELEASE_REPO/token are configured) and names the S3 layout.
set -euo pipefail
config="${1:?Usage: start-build.sh CONFIG TARGET [KOMPILE_REF] [DL4J_REF] [RELEASE_TAG]}"
target="${2:?}"
# Prefer the generated config so fleet/image derivations are visible here too.
generated="$(dirname "$config")/generated-codebuild.env"
[ -f "$generated" ] && config="$generated"
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?}"

kompile_ref="${3:-${KOMPILE_REF:-}}"
dl4j_ref="${4:-${DL4J_REF:-}}"
release_tag="${5:-${RELEASE_TAG:-}}"

overrides=("name=BUILD_TARGET,value=$target,type=PLAINTEXT")
[ -n "$dl4j_ref" ] && overrides+=("name=DL4J_REF,value=$dl4j_ref,type=PLAINTEXT")
[ -n "$release_tag" ] && overrides+=("name=RELEASE_TAG,value=$release_tag,type=PLAINTEXT")
[ -n "${SPIN_SOURCE_TARGET:-}" ] && overrides+=("name=SPIN_SOURCE_TARGET,value=$SPIN_SOURCE_TARGET,type=PLAINTEXT")

args=(--region "$AWS_REGION" --project-name "${PROJECT_PREFIX:-kompile}-$target"
      --environment-variables-override "${overrides[@]}")
[ -n "$kompile_ref" ] && args+=(--source-version "$kompile_ref")

build_id="$(aws codebuild start-build "${args[@]}" --query 'build.id' --output text)"
echo "Started $build_id"
echo "https://${AWS_REGION}.console.aws.amazon.com/codesuite/codebuild/projects/${PROJECT_PREFIX:-kompile}-$target/build/${build_id}"
