#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STAGER="${SCRIPT_DIR}/../../main/build/stage-portable-binaries.sh"
NORMALIZER="${SCRIPT_DIR}/../../main/build/normalize-elf-portability.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT
REPO="${TMP_DIR}/repo"
DEST="${TMP_DIR}/portable-bin"

make_source() {
    local relative="$1"
    local content="$2"
    mkdir -p "$(dirname "${REPO}/${relative}")"
    printf '%s\n' "${content}" > "${REPO}/${relative}"
}

make_source "kompile-cli/kompile-cli-main/target/kompile-cli-main" cli
make_source "kompile-cli/kompile-agent-cli/target/kompile-agent" agent
make_source "kompile-cli/kompile-app-cli/target/kompile-app-cli" app-cli
make_source "kompile-cli/kompile-model-cli/target/kompile-model" model
make_source "kompile-cli/kompile-component-cli/target/kompile-component" component
make_source "kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app" server
make_source "kompile-app/kompile-app-parent/kompile-app-main/target/kompile-vlm-test" vlm
make_source "kompile-app/kompile-models/kompile-model-staging/target/kompile-model-staging" staging
make_source "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving/target/kompile-model-serving" model-serving
make_source "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target/kompile-pipeline-serving" pipeline-serving
make_source "kompile-app/kompile-app-parent/kompile-app-chat/target/kompile-chat" chat
make_source "kompile-app/kompile-app-parent/kompile-app-main/target/libjava.so" identical-shim
make_source "kompile-app/kompile-app-parent/kompile-app-chat/target/libjava.so" identical-shim

bash "${STAGER}" "${REPO}" "${DEST}" "${NORMALIZER}"

for staged in kompile kompile-agent kompile-app-cli kompile-model kompile-component kompile-server \
        kompile-vlm-test kompile-model-staging kompile-model-serving kompile-pipeline-serving kompile-chat; do
    [ -x "${DEST}/${staged}" ] || {
        echo "ERROR: expected staged executable is missing: ${staged}" >&2
        exit 1
    }
done
[ -f "${DEST}/libjava.so" ]
[ "$(sed -n '1p' "${REPO}/kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app")" = server ]
[ "$(sed -n '1p' "${DEST}/kompile-server")" = server ]

printf '%s\n' conflicting-shim > "${REPO}/kompile-app/kompile-app-parent/kompile-app-chat/target/libjava.so"
if bash "${STAGER}" "${REPO}" "${DEST}" "${NORMALIZER}" >"${TMP_DIR}/conflict.out" 2>&1; then
    echo "ERROR: conflicting shim libraries were accepted" >&2
    exit 1
fi
if ! grep -q 'conflicting GraalVM shim libraries' "${TMP_DIR}/conflict.out"; then
    echo "ERROR: shim collision failure did not explain the conflict" >&2
    exit 1
fi

echo "stage-portable-binaries tests passed"
