#!/usr/bin/env bash
# Publish already-built Kompile Maven artifacts to the repository used by DL4J.
#
# Credentials are resolved by Maven from ~/.m2/settings.xml using --repository-id.
# The source repository is filtered to ai/kompile so DL4J dependencies are never
# redeployed as though they were Kompile outputs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=path-normalization.sh
source "${SCRIPT_DIR}/path-normalization.sh"
REPOSITORY_TOOL="${SCRIPT_DIR}/../release/central/repository.py"
SOURCE_REPOSITORY="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}"
REPOSITORY_URL="${KOMPILE_DEPLOY_REPOSITORY_URL:-${DL4J_MAVEN_REPOSITORY_URL:-}}"
REPOSITORY_ID="${KOMPILE_DEPLOY_REPOSITORY_ID:-${DL4J_MAVEN_REPOSITORY_ID:-dl4j-release}}"
RELEASE_VERSION="${KOMPILE_VERSION:-}"
MAVEN_EXECUTABLE="${MVN:-mvn}"

usage() {
    cat <<'USAGE'
Usage: build-scripts/publish-maven.sh [options]

Options:
  --source-repository DIR  Local Maven repository containing ai/kompile
  --repository-url URL     Deployment URL (defaults to KOMPILE_DEPLOY_REPOSITORY_URL,
                           then DL4J_MAVEN_REPOSITORY_URL)
  --repository-id ID       Maven settings.xml server id (default: dl4j-release)
  --version VERSION        Kompile snapshot version (default: root project version)
  --maven EXECUTABLE       Maven executable (default: MVN or mvn)
  -h, --help               Show this help

Example:
  DL4J_MAVEN_REPOSITORY_URL=https://repo.example/snapshots \
    build-scripts/publish-maven.sh --repository-id dl4j-release
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --source-repository) SOURCE_REPOSITORY="$2"; shift 2 ;;
        --repository-url) REPOSITORY_URL="$2"; shift 2 ;;
        --repository-id) REPOSITORY_ID="$2"; shift 2 ;;
        --version) RELEASE_VERSION="$2"; shift 2 ;;
        --maven) MAVEN_EXECUTABLE="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

# This script performs shell filesystem operations on the local repository and
# staging tree, but repository.py is a native Python process on Azure Windows.
SOURCE_REPOSITORY_NATIVE="$(kompile_path_to_native "${SOURCE_REPOSITORY}")"
SOURCE_REPOSITORY="$(kompile_path_to_posix "${SOURCE_REPOSITORY_NATIVE}")"
REPOSITORY_TOOL_NATIVE="$(kompile_path_to_native "${REPOSITORY_TOOL}")"

if [ -z "${REPOSITORY_URL}" ]; then
    echo "A target repository is required via --repository-url, KOMPILE_DEPLOY_REPOSITORY_URL, or DL4J_MAVEN_REPOSITORY_URL." >&2
    exit 2
fi
if [ ! -d "${SOURCE_REPOSITORY}/ai/kompile" ]; then
    echo "No Kompile artifacts found under ${SOURCE_REPOSITORY}/ai/kompile; run a Kompile Maven install first." >&2
    exit 1
fi
if [ -z "${RELEASE_VERSION}" ]; then
    RELEASE_VERSION="$("${MAVEN_EXECUTABLE}" -q -N -DforceStdout help:evaluate -Dexpression=project.version)"
fi

STAGING_REPOSITORY="$(mktemp -d -t kompile-maven-publish.XXXXXXXX)"
cleanup() {
    rm -rf "${STAGING_REPOSITORY}"
}
trap cleanup EXIT

mkdir -p "${STAGING_REPOSITORY}/ai"
cp -R "${SOURCE_REPOSITORY}/ai/kompile" "${STAGING_REPOSITORY}/ai/kompile"

python3 "${REPOSITORY_TOOL_NATIVE}" deploy-snapshot \
    --repository "$(kompile_path_to_native "${STAGING_REPOSITORY}")" \
    --release-version "${RELEASE_VERSION}" \
    --repository-id "${REPOSITORY_ID}" \
    --url "${REPOSITORY_URL}" \
    --maven-executable "${MAVEN_EXECUTABLE}"
