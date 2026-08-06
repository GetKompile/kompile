#!/usr/bin/env bash
# Stage native-image executables and GraalVM shim libraries for Maven assembly.
# Source module target directories are never modified; ELF normalization is
# applied only to the copies in the destination directory.

set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: $0 <repository-root> <destination-bin-dir> [normalizer]" >&2
    exit 2
fi

REPO_ROOT="$(cd "$1" && pwd)"
DEST_DIR="$2"
NORMALIZER="${3:-${REPO_ROOT}/kompile-dist/src/main/build/normalize-elf-portability.sh}"

case "${DEST_DIR}" in
    ''|/|"${REPO_ROOT}")
        echo "ERROR: refusing unsafe portable-bin destination: ${DEST_DIR:-<empty>}" >&2
        exit 1
        ;;
esac

if [ ! -f "${NORMALIZER}" ]; then
    echo "ERROR: ELF portability normalizer does not exist: ${NORMALIZER}" >&2
    exit 1
fi

rm -rf "${DEST_DIR}"
mkdir -p "${DEST_DIR}"

STAGED_BINARIES=()

stage_binary() {
    local relative_source="$1"
    local destination_name="$2"
    local required="$3"
    local source="${REPO_ROOT}/${relative_source}"
    local destination="${DEST_DIR}/${destination_name}"

    if [ ! -f "${source}" ]; then
        if [ "${required}" = true ]; then
            echo "ERROR: required native binary is missing: ${source}" >&2
            exit 1
        fi
        return 0
    fi

    cp "${source}" "${destination}"
    chmod +x "${destination}"
    STAGED_BINARIES+=("${destination}")
    echo "  staged ${destination_name}"
}

stage_shims() {
    local relative_target="$1"
    local source=""
    local destination=""

    for source in \
            "${REPO_ROOT}/${relative_target}"/lib*.so \
            "${REPO_ROOT}/${relative_target}"/lib*.dylib \
            "${REPO_ROOT}/${relative_target}"/lib*.dll; do
        [ -f "${source}" ] || continue
        destination="${DEST_DIR}/$(basename "${source}")"
        if [ -f "${destination}" ]; then
            if ! cmp -s "${source}" "${destination}"; then
                echo "ERROR: conflicting GraalVM shim libraries share $(basename "${source}"):" >&2
                echo "       ${source}" >&2
                echo "       ${destination}" >&2
                exit 1
            fi
            continue
        fi
        cp -a "${source}" "${destination}"
        echo "  staged $(basename "${source}")"
    done
}

# These were required inputs in dist.xml before staging was introduced; keep the
# same contract while ensuring the archive receives normalized copies.
stage_binary "kompile-cli/kompile-cli-main/target/kompile-cli-main" "kompile" true
stage_binary "kompile-cli/kompile-agent-cli/target/kompile-agent" "kompile-agent" true
stage_binary "kompile-cli/kompile-app-cli/target/kompile-app-cli" "kompile-app-cli" true
stage_binary "kompile-cli/kompile-model-cli/target/kompile-model" "kompile-model" true
stage_binary "kompile-cli/kompile-component-cli/target/kompile-component" "kompile-component" true
stage_binary "kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app" "kompile-server" true

# Optional native images are copied only when their opt-in profiles produced them.
stage_binary "kompile-app/kompile-app-parent/kompile-app-main/target/kompile-vlm-test" "kompile-vlm-test" false
stage_binary "kompile-app/kompile-app-parent/kompile-app-main/target/kompile-training" "kompile-training" false
stage_binary "kompile-app/kompile-models/kompile-model-staging/target/kompile-model-staging" "kompile-model-staging" false
stage_binary "kompile-app/kompile-app-parent/kompile-app-chat/target/kompile-chat" "kompile-chat" false
stage_binary "kompile-app/kompile-app-parent/kompile-app-crawl-manager/target/kompile-crawl-manager" "kompile-crawl-manager" false
stage_binary "kompile-app/kompile-app-parent/kompile-app-lite/target/kompile-app-lite" "kompile-app-lite" false

stage_shims "kompile-app/kompile-app-parent/kompile-app-main/target"
stage_shims "kompile-app/kompile-models/kompile-model-staging/target"
stage_shims "kompile-app/kompile-app-parent/kompile-app-chat/target"
stage_shims "kompile-app/kompile-app-parent/kompile-app-crawl-manager/target"

if [ "${#STAGED_BINARIES[@]}" -gt 0 ]; then
    bash "${NORMALIZER}" "${STAGED_BINARIES[@]}"
fi
