#!/usr/bin/env bash
# Publish dist/ for distribution.
# 1) Always: stable S3 layout
#      s3://$ARTIFACT_BUCKET/$RELEASE_PREFIX/<RELEASE_TAG|short-sha>/<target>/
#    (the zipped per-build CodeBuild artifact exists regardless).
# 2) When GITHUB_RELEASE_REPO + GITHUB_RELEASE_TOKEN + RELEASE_TAG are set:
#    upload non-log files as assets on the release for RELEASE_TAG, creating
#    the release if needed. Asset names are prefixed with the build target so
#    every platform can attach to one release.
set -euo pipefail
dist="${1:?Usage: publish-dist.sh DIST_DIR}"
target="${BUILD_TARGET:?}"

version="${RELEASE_TAG:-}"
if [ -z "$version" ]; then
  version="$(printf '%.12s' "${CODEBUILD_RESOLVED_SOURCE_VERSION:-adhoc}")"
fi

if [ -z "$(ls -A "$dist" 2>/dev/null)" ]; then
  echo "publish-dist: $dist is empty; nothing to publish" >&2
  exit 0
fi

if [ -n "${ARTIFACT_BUCKET:-}" ] && command -v aws >/dev/null 2>&1; then
  dest="s3://${ARTIFACT_BUCKET}/${RELEASE_PREFIX:-releases}/${version}/${target}/"
  echo "publish-dist: uploading $dist to $dest"
  aws s3 cp --recursive --only-show-errors "$dist" "$dest"
else
  echo "publish-dist: skipping S3 layout (no ARTIFACT_BUCKET or aws cli)" >&2
fi

repo="${GITHUB_RELEASE_REPO:-}"
token="${GITHUB_RELEASE_TOKEN:-}"
if [ -z "$repo" ] || [ -z "$token" ] || [ -z "${RELEASE_TAG:-}" ]; then
  echo "publish-dist: GitHub Release upload disabled (needs GITHUB_RELEASE_REPO, GITHUB_RELEASE_TOKEN and a RELEASE_TAG)"
  exit 0
fi

api="https://api.github.com/repos/${repo}"
uploads="https://uploads.github.com/repos/${repo}"
gh() {
  curl -fsS -H "Authorization: Bearer ${token}" \
    -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "$@"
}
release_id() {
  gh "${api}/releases/tags/${RELEASE_TAG}" 2>/dev/null \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null || true
}

id="$(release_id)"
if [ -z "$id" ]; then
  echo "publish-dist: creating GitHub release ${RELEASE_TAG} in ${repo}"
  payload="$(python3 - "$RELEASE_TAG" <<'PY'
import json, sys
tag = sys.argv[1]
print(json.dumps({"tag_name": tag, "name": tag, "prerelease": True,
                  "body": "Automated kompile/DL4J build artifacts."}))
PY
)"
  gh -X POST "${api}/releases" -d "$payload" >/dev/null 2>&1 || true
  id="$(release_id)"
fi
[ -n "$id" ] || { echo "publish-dist: could not create or find release ${RELEASE_TAG} in ${repo}" >&2; exit 1; }

find "$dist" -type f ! -name '*.log' | while read -r f; do
  name="${target}-$(basename "$f")"
  echo "publish-dist: uploading asset ${name}"
  if ! gh -X POST -H "Content-Type: application/octet-stream" \
       --data-binary "@${f}" "${uploads}/releases/${id}/assets?name=${name}" >/dev/null; then
    echo "publish-dist: WARNING: asset ${name} failed to upload (already exists?)" >&2
  fi
done
echo "publish-dist: done (release ${RELEASE_TAG}, ${repo})"
