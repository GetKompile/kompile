#!/usr/bin/env bash
# Build (and optionally publish) the kompile spin images — cli, app-main,
# model-staging — from a kompile distribution. Runs locally and inside the
# kompile-spins CodeBuild lane (env-driven; config file optional).
#
#   build-spins.sh [--config FILE] (--dist DIR_OR_TARBALL | --from-s3 VERSION [SOURCE_TARGET])
#                  [--products "cli app-main model-staging"] [--version V] [--push]
#
# Registries: ECR ($ECR_REGISTRY/$SPIN_REPOSITORY:<product>-<version>) and,
# when GITHUB_RELEASE_REPO + GITHUB_RELEASE_TOKEN are present, GHCR
# (ghcr.io/<owner>/kompile-<product>:<version>) — the release token needs
# write:packages for that. Products whose artifacts are absent from the dist
# are skipped with a warning (a cli-only dist yields just the cli spin).
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
dist_src="" s3_version="" s3_target="${SPIN_SOURCE_TARGET:-linux-x86_64}"
products="${SPIN_PRODUCTS:-cli app-main model-staging}"
version="${SPIN_VERSION:-${RELEASE_TAG:-}}" push=0
while [ $# -gt 0 ]; do
  case "$1" in
    --config) # shellcheck disable=SC1090
      source "$2"; shift 2 ;;
    --dist) dist_src="$2"; shift 2 ;;
    --from-s3) s3_version="$2"; shift 2
      [ $# -gt 0 ] && [ "${1#--}" = "$1" ] && { s3_target="$1"; shift; } ;;
    --products) products="$2"; shift 2 ;;
    --version) version="$2"; shift 2 ;;
    --push) push=1; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
log() { echo "build-spins: $*" >&2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if [ -n "$s3_version" ]; then
  : "${ARTIFACT_BUCKET:?--from-s3 needs ARTIFACT_BUCKET}"
  src="s3://${ARTIFACT_BUCKET}/${RELEASE_PREFIX:-releases}/${s3_version}/${s3_target}/"
  log "fetching dist from $src"
  aws s3 cp --recursive --only-show-errors --exclude '*' --include 'kompile-dist-*.tar.gz' "$src" "$work/fetch/"
  dist_src="$(ls "$work"/fetch/kompile-dist-*.tar.gz 2>/dev/null | head -n 1)"
  [ -n "$dist_src" ] || { log "no kompile-dist-*.tar.gz under $src"; exit 2; }
  version="${version:-$s3_version}"
fi
[ -n "$dist_src" ] || { log "need --dist or --from-s3"; exit 2; }

ctx="$work/ctx"
mkdir -p "$ctx/dist"
if [ -f "$dist_src" ]; then
  tar -xzf "$dist_src" -C "$work"
  dist_dir="$(find "$work" -maxdepth 1 -type d -name 'kompile-dist-*' | head -n 1)"
  [ -n "$dist_dir" ] || { log "tarball did not contain a kompile-dist-* directory"; exit 2; }
else
  dist_dir="$dist_src"
  d="$(find "$dist_dir" -maxdepth 1 -type d -name 'kompile-dist-*' | head -n 1)"
  [ -n "$d" ] && dist_dir="$d"
fi
cp -a "$dist_dir/." "$ctx/dist/"
cp "$root/aws/codebuild/images/spins/spin-entrypoint.sh" "$ctx/"
if [ -z "$version" ]; then
  version="$(basename "$dist_dir" | sed -n 's/^kompile-dist-\([^-]*\)-.*/\1/p')"
  version="${version:-adhoc}"
fi
log "dist: $(basename "$dist_dir"); version: $version"

has_artifact() {
  case "$1" in
    cli) [ -x "$ctx/dist/bin/kompile" ] || find "$ctx/dist" -maxdepth 3 -name 'kompile-cli-main-*-shaded.jar' | grep -q . ;;
    app-main) [ -e "$ctx/dist/bin/kompile-server" ] || find "$ctx/dist" -maxdepth 3 -name 'kompile-app-main-*-exec.jar' | grep -q . ;;
    model-staging) [ -e "$ctx/dist/bin/kompile-model-staging" ] || find "$ctx/dist" -maxdepth 3 -name 'kompile-model-staging-*-exec.jar' | grep -q . ;;
  esac
}

ecr_base=""
[ -n "${ECR_REGISTRY:-}" ] && [ -n "${SPIN_REPOSITORY:-}" ] && ecr_base="$ECR_REGISTRY/$SPIN_REPOSITORY"
ghcr_owner=""
[ -n "${GITHUB_RELEASE_REPO:-}" ] && ghcr_owner="$(echo "${GITHUB_RELEASE_REPO%%/*}" | tr '[:upper:]' '[:lower:]')"
if [ "$push" = 1 ]; then
  [ -n "$ecr_base" ] && aws ecr get-login-password --region "${AWS_REGION:?}" \
    | docker login --username AWS --password-stdin "$ECR_REGISTRY"
  if [ -n "$ghcr_owner" ] && [ -n "${GITHUB_RELEASE_TOKEN:-}" ]; then
    printf '%s' "$GITHUB_RELEASE_TOKEN" | docker login ghcr.io --username "$ghcr_owner" --password-stdin
  else
    ghcr_owner=""
  fi
fi

built=() skipped=()
for product in $products; do
  if ! has_artifact "$product"; then
    log "SKIP $product: no artifact in this dist"
    skipped+=("$product")
    continue
  fi
  tags=()
  [ -n "$ecr_base" ] && tags+=("$ecr_base:$product-$version" "$ecr_base:$product-latest")
  [ -n "$ghcr_owner" ] && tags+=("ghcr.io/$ghcr_owner/kompile-$product:$version" "ghcr.io/$ghcr_owner/kompile-$product:latest")
  [ "${#tags[@]}" -gt 0 ] || tags=("kompile-$product:$version")
  tag_args=()
  for t in "${tags[@]}"; do tag_args+=(-t "$t"); done
  log "building $product (${tags[0]})"
  docker build -f "$root/aws/codebuild/images/spins/Dockerfile" \
    --build-arg "PRODUCT=$product" "${tag_args[@]}" "$ctx"
  if [ "$push" = 1 ]; then
    for t in "${tags[@]}"; do
      case "$t" in kompile-*) continue ;; esac
      docker push "$t"
    done
  fi
  built+=("$product")
done
log "done — built: ${built[*]:-none}; skipped: ${skipped[*]:-none}${push:+; pushed=$push}"
[ "${#built[@]}" -gt 0 ]
