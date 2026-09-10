#!/usr/bin/env bash
# Validate the exact DL4J SDK lane embedded in a Kompile backend distribution.
set -euo pipefail

if [ "$#" -lt 5 ] || [ "$#" -gt 7 ]; then
    echo "Usage: $0 SDK_ROOT VARIANT JAVACPP_PLATFORM ND4J_VERSION CUDA_VERSION [BACKEND_ARTIFACT] [SDK_CLASSIFIER]" >&2
    exit 2
fi

SDK_ROOT="$1"
VARIANT="$2"
PLATFORM="$3"
ND4J_VERSION="$4"
CUDA_VERSION="$5"
BACKEND_ARTIFACT="${6:-}"
EXPECTED_CLASSIFIER="${7:-}"

case "${VARIANT}" in
    cli-only|hosted)
        exit 0
        ;;
    full|local|cpu-intel|cpu-arm|cuda|zluda|amd-zluda|android|compat|vulkan|vulkan-compile|hexagon|tpu)
        # Backend-specific requirements are selected below after optional defaults.
        ;;
    *)
        echo "ERROR: unsupported Kompile distribution variant for SDK validation: ${VARIANT}" >&2
        exit 2
        ;;
esac

# Existing Maven/direct callers pass only the first five arguments. Keep those
# lanes compatible while allowing release packaging to name a non-canonical
# backend and classifier explicitly.
if [ -z "${BACKEND_ARTIFACT}" ]; then
    case "${VARIANT}" in
        full|cpu-intel|cpu-arm|android|compat) BACKEND_ARTIFACT=nd4j-native ;;
        cuda) BACKEND_ARTIFACT="nd4j-cuda-${CUDA_VERSION}" ;;
        zluda) BACKEND_ARTIFACT=nd4j-zluda ;;
        amd-zluda) BACKEND_ARTIFACT=nd4j-zluda-12.9 ;;
        vulkan|vulkan-compile) BACKEND_ARTIFACT=nd4j-vulkan ;;
        hexagon) BACKEND_ARTIFACT=nd4j-hexagon ;;
        tpu) BACKEND_ARTIFACT=nd4j-tpu ;;
        *)
            echo "ERROR: ${VARIANT} requires an explicit backend artifact" >&2
            exit 2
            ;;
    esac
fi

if [ -z "${EXPECTED_CLASSIFIER}" ]; then
    case "${VARIANT}" in
        cpu-intel) EXPECTED_CLASSIFIER="${PLATFORM}-avx2" ;;
        compat) EXPECTED_CLASSIFIER="${PLATFORM}-compat" ;;
        vulkan-compile) EXPECTED_CLASSIFIER="${PLATFORM}-compile" ;;
        zluda) EXPECTED_CLASSIFIER="${PLATFORM}-zluda" ;;
        amd-zluda) EXPECTED_CLASSIFIER="${PLATFORM}-zluda-rocm-7.2.4" ;;
        *) EXPECTED_CLASSIFIER="${PLATFORM}" ;;
    esac
fi

case "${BACKEND_ARTIFACT}" in
    nd4j-native)
        case "${EXPECTED_CLASSIFIER}:${PLATFORM}" in
            *-compat:*) REQUIRED_ARTIFACTS=(nd4j-native nd4j-native-preset) ;;
            *:android-*) REQUIRED_ARTIFACTS=(nd4j-native nd4j-native-preset) ;;
            *:linux-x86_64|*:windows-x86_64)
                REQUIRED_ARTIFACTS=(nd4j-native nd4j-native-preset nd4j-native-platform libtokenizers tokenizers-native-preset tokenizers-native)
                ;;
            *:linux-arm64|*:macosx-*)
                REQUIRED_ARTIFACTS=(nd4j-native nd4j-native-preset libtokenizers tokenizers-native-preset tokenizers-native)
                ;;
            *)
                echo "ERROR: unsupported nd4j-native SDK platform: ${PLATFORM}" >&2
                exit 2
                ;;
        esac
        ;;
    nd4j-cuda-12.6|nd4j-cuda-12.9)
        CUDA_LINE="${BACKEND_ARTIFACT#nd4j-cuda-}"
        REQUIRED_ARTIFACTS=("${BACKEND_ARTIFACT}" "${BACKEND_ARTIFACT}-preset" "${BACKEND_ARTIFACT}-platform")
        ;;
    nd4j-zluda)
        if [[ "${PLATFORM}" == linux-* ]]; then
            REQUIRED_ARTIFACTS=(nd4j-cuda-12.9 nd4j-cuda-12.9-preset nd4j-zluda nd4j-zluda-platform)
        elif [[ "${PLATFORM}" == windows-* ]]; then
            REQUIRED_ARTIFACTS=(nd4j-cuda-12.9 nd4j-cuda-12.9-preset)
        else
            echo "ERROR: unsupported nd4j-zluda SDK platform: ${PLATFORM}" >&2
            exit 2
        fi
        ;;
    nd4j-zluda-12.9)
        case "${EXPECTED_CLASSIFIER}" in
            linux-x86_64-zluda-rocm-10.0.0)
                if [ "${PLATFORM}" != "linux-x86_64" ]; then
                    echo "ERROR: ROCm 10 ZLUDA SDK is Linux x86_64-only (got ${PLATFORM})" >&2
                    exit 2
                fi
                ;;
            linux-x86_64-zluda-rocm-7.2.4|windows-x86_64-zluda-rocm-7.2.4)
                if [ "${EXPECTED_CLASSIFIER}" != "${PLATFORM}-zluda-rocm-7.2.4" ]; then
                    echo "ERROR: ROCm 7.2.4 classifier ${EXPECTED_CLASSIFIER} does not match ${PLATFORM}" >&2
                    exit 2
                fi
                ;;
            *)
                echo "ERROR: unsupported version-qualified ZLUDA classifier: ${EXPECTED_CLASSIFIER}" >&2
                exit 2
                ;;
        esac
        REQUIRED_ARTIFACTS=(nd4j-zluda-12.9 nd4j-zluda-12.9-platform nd4j-cuda-12.9-preset nd4j-cuda-backend-common nd4j-presets-common)
        ;;
    nd4j-vulkan|nd4j-hexagon|nd4j-tpu)
        REQUIRED_ARTIFACTS=("${BACKEND_ARTIFACT}" "${BACKEND_ARTIFACT}-preset")
        ;;
    *)
        echo "ERROR: unsupported SDK backend artifact: ${BACKEND_ARTIFACT}" >&2
        exit 2
        ;;
esac

if [ ! -d "${SDK_ROOT}" ]; then
    echo "ERROR: DL4J SDK root does not exist: ${SDK_ROOT}" >&2
    exit 1
fi

RUNTIME_REQUIRED=false
case "${BACKEND_ARTIFACT}:${EXPECTED_CLASSIFIER}" in
    nd4j-native:*-compat|nd4j-vulkan:*|nd4j-hexagon:*|nd4j-tpu:*|nd4j-zluda:*|nd4j-zluda-12.9:*|nd4j-cuda-12.9:*-zluda) ;;
    nd4j-native:*|nd4j-cuda-12.6:*|nd4j-cuda-12.9:*) RUNTIME_REQUIRED=true ;;
esac
RUNTIME_MATCH="$(find "${SDK_ROOT}" -type f \( -name '*.zip' -o -name '*.aar' \) -print -quit 2>/dev/null || true)"
if [ "${RUNTIME_REQUIRED}" = true ] && [ -z "${RUNTIME_MATCH}" ]; then
    echo "ERROR: DL4J SDK shard has no runtime .zip/.aar: ${SDK_ROOT}" >&2
    exit 1
fi

JARS_ROOT="${SDK_ROOT}/jars"
if [ ! -d "${JARS_ROOT}" ]; then
    echo "ERROR: DL4J SDK shard has no jars/ directory: ${SDK_ROOT}" >&2
    exit 1
fi

matches_artifact_version() {
    local artifact_id="$1"
    local classifier="${2:-}"
    local jar_path
    local jar_name
    local exact_prefix="${artifact_id}-${ND4J_VERSION}"
    local snapshot_stem="${ND4J_VERSION%-SNAPSHOT}"
    local escaped_artifact_id
    local escaped_snapshot_stem="${snapshot_stem//./\\.}"
    local escaped_classifier
    escaped_artifact_id="$(printf '%s' "${artifact_id}" | sed 's/[][(){}.^$*+?|\\]/\\&/g')"
    escaped_classifier="$(printf '%s' "${classifier}" | sed 's/[][(){}.^$*+?|\\]/\\&/g')"

    for jar_path in "${JARS_ROOT}"/*.jar; do
        [ -f "${jar_path}" ] || continue
        jar_name="${jar_path##*/}"
        if [ -n "${classifier}" ]; then
            if [ "${jar_name}" = "${exact_prefix}-${classifier}.jar" ]; then
                printf '%s\n' "${jar_path}"
                return 0
            fi
            if [[ "${ND4J_VERSION}" == *-SNAPSHOT ]] \
                    && [[ "${jar_name}" =~ ^${escaped_artifact_id}-${escaped_snapshot_stem}-[0-9]{8}[.][0-9]{6}-[0-9]+-${escaped_classifier}[.]jar$ ]]; then
                printf '%s\n' "${jar_path}"
                return 0
            fi
        elif [ "${jar_name}" = "${exact_prefix}.jar" ]; then
            printf '%s\n' "${jar_path}"
            return 0
        elif [[ "${ND4J_VERSION}" == *-SNAPSHOT ]] \
                && [[ "${jar_name}" =~ ^${escaped_artifact_id}-${escaped_snapshot_stem}-[0-9]{8}[.][0-9]{6}-[0-9]+[.]jar$ ]]; then
            printf '%s\n' "${jar_path}"
            return 0
        fi
    done
    return 1
}

MISSING_ARTIFACTS=()
for artifact_id in "${REQUIRED_ARTIFACTS[@]}"; do
    MATCH="$(matches_artifact_version "${artifact_id}" \
        || matches_artifact_version "${artifact_id}" "${EXPECTED_CLASSIFIER}" || true)"
    if [ -z "${MATCH}" ]; then
        MISSING_ARTIFACTS+=("${artifact_id}")
    fi
done

CLASSIFIED_ARTIFACT="${REQUIRED_ARTIFACTS[0]}"
CLASSIFIED_MATCH="$(matches_artifact_version "${CLASSIFIED_ARTIFACT}" "${EXPECTED_CLASSIFIER}" || true)"

if [ "${#MISSING_ARTIFACTS[@]}" -ne 0 ] || [ -z "${CLASSIFIED_MATCH}" ]; then
    echo "ERROR: incomplete DL4J SDK shard for ${VARIANT}/${PLATFORM} (${ND4J_VERSION})" >&2
    if [ "${#MISSING_ARTIFACTS[@]}" -ne 0 ]; then
        printf '  missing artifactId: %s\n' "${MISSING_ARTIFACTS[@]}" >&2
    fi
    if [ -z "${CLASSIFIED_MATCH}" ]; then
        echo "  missing classifier: ${CLASSIFIED_ARTIFACT}:${EXPECTED_CLASSIFIER}" >&2
    fi
    echo "  staged JARs:" >&2
    find "${JARS_ROOT}" -maxdepth 1 -type f -name '*.jar' -printf '    %f\n' 2>/dev/null | LC_ALL=C sort >&2 || true
    exit 1
fi

printf 'Validated DL4J SDK shard: variant=%s backend=%s platform=%s version=%s artifacts=%s classifier=%s\n' "${VARIANT}" "${BACKEND_ARTIFACT}" "${PLATFORM}" "${ND4J_VERSION}" "${#REQUIRED_ARTIFACTS[@]}" "${EXPECTED_CLASSIFIER}"
