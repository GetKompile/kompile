#!/usr/bin/env bash
#
# 00-generate-app.sh
#
# Regenerate the demo RAG project under generated/vlm-demo/project using the kompile
# CLI. This runs `kompile build app` with the SAMEDIFF_RAG preset and the
# nd4j-cuda-12.9 backend, then stops short of the Maven build itself
# (--skipMavenBuild) — step 03 builds and launches the jar.
#
# This is the SAME generator used by kompile-demo/ — the VLM variant differs only in
# how the input documents are prepared before ingest (PDF -> SmolDocling VLM -> markdown),
# not in the generated app itself.

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${DEMO_DIR}/.." && pwd)"
GEN_BASE="${DEMO_DIR}/generated"
PROJECT_NAME="vlm-demo"
PROJECT_DIR="${GEN_BASE}/${PROJECT_NAME}/project"
CLI_JAR="${REPO_ROOT}/kompile-cli/target/kompile-cli-0.1.0-SNAPSHOT-shaded.jar"

JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

log() { printf '[00-generate-app] %s\n' "$*"; }

if [[ ! -f "${CLI_JAR}" ]]; then
    log "ERROR: kompile-cli shaded jar not found at: ${CLI_JAR}"
    log "       Build it first:"
    log "       (cd ${REPO_ROOT}/kompile-cli && /home/agibsonccc/dev-apps/mvn/bin/mvn -o -DskipTests install)"
    exit 1
fi

mkdir -p "${GEN_BASE}"

log "regenerating project under ${PROJECT_DIR}"
log "  preset:  samediff-rag"
log "  backend: nd4j-cuda-12.9"
log "  native:  false (jvm jar)"

"${JAVA_BIN}" -jar "${CLI_JAR}" build app \
    --configName="${PROJECT_NAME}" \
    --preset=samediff-rag \
    --outputDir="${GEN_BASE}" \
    --backend=nd4j-cuda-12.9 \
    --javacppPlatform=linux-x86_64 \
    --no-native \
    --cleanBuild \
    --skipMavenBuild

if [[ ! -f "${PROJECT_DIR}/pom.xml" ]]; then
    log "ERROR: generation finished but no pom.xml at ${PROJECT_DIR}/pom.xml"
    exit 1
fi
if [[ ! -f "${PROJECT_DIR}/src/main/resources/application.properties" ]]; then
    log "ERROR: no application.properties under ${PROJECT_DIR}/src/main/resources/"
    exit 1
fi

log "generated:"
log "  ${PROJECT_DIR}/pom.xml"
log "  ${PROJECT_DIR}/src/main/resources/application.properties"
