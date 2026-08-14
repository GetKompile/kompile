#!/usr/bin/env bash
#
# Stage the side-loaded native libraries used by Kompile native-image distributions.
# Both build-dist.sh and the kompile-dist Maven assembly call this helper so they
# apply the same producer-owned backend, platform, flavor, collision, and layout rules.

set -euo pipefail

if [ "$#" -lt 3 ] || [ "$#" -gt 5 ]; then
    echo "usage: $0 <source-native-tree> <destination-lib-dir> <javacpp-platform> [platform-extension] [backend-artifact]" >&2
    exit 2
fi

SOURCE_DIR="${1%/}"
DEST_DIR="${2%/}"
PLATFORM="$3"
PLATFORM_EXTENSION="${4:-}"
BACKEND_ARTIFACT="${5:-nd4j-native}"
FLAVOR="${PLATFORM_EXTENSION#-}"
CLASSIFIER="${PLATFORM}${PLATFORM_EXTENSION}"
SHARED_RUNTIME_MANIFEST="shared-runtime-manifest.txt"
SHARED_RUNTIME_FORMAT="# nd4j-shared-runtime-manifest-v1"
RUNTIME_COUNT_PREFIX="# runtime-count="
JNI_ENTRYPOINT_MANIFEST="jni-entrypoint-manifest.txt"
JNI_ENTRYPOINT_FORMAT="# kompile-jni-entrypoint-manifest-v1"

case "${PLATFORM}" in
    linux-*)
        OS=linux
        ARCH="${PLATFORM#linux-}"
        ;;
    macosx-*)
        OS=macosx
        ARCH="${PLATFORM#macosx-}"
        ;;
    windows-*)
        OS=windows
        ARCH="${PLATFORM#windows-}"
        ;;
    *)
        echo "ERROR: unsupported JavaCPP platform '${PLATFORM}'." >&2
        exit 1
        ;;
esac

if [ ! -d "${SOURCE_DIR}" ]; then
    echo "ERROR: native-library source tree does not exist: ${SOURCE_DIR}" >&2
    echo "       Build kompile-app-main for ${CLASSIFIER} before assembling the dist." >&2
    exit 1
fi

mkdir -p "${DEST_DIR}"

is_native_name() {
    local name="$1"
    case "${OS}" in
        linux)
            case "${name}" in *.so|*.so.*) return 0 ;; esac
            ;;
        macosx)
            case "${name}" in *.dylib) return 0 ;; esac
            ;;
        windows)
            case "${name}" in *.dll) return 0 ;; esac
            ;;
    esac
    return 1
}

native_source_files() {
    case "${OS}" in
        linux)
            find "${SOURCE_DIR}" -type f \( -name '*.so' -o -name '*.so.*' \) -print
            ;;
        macosx)
            find "${SOURCE_DIR}" -type f -name '*.dylib' -print
            ;;
        windows)
            find "${SOURCE_DIR}" -type f -name '*.dll' -print
            ;;
    esac
}

is_selected_backend_manifest() {
    local manifest="$1"
    local relative_path="${manifest#${SOURCE_DIR}/}"
    case "${BACKEND_ARTIFACT}" in
        nd4j-native)
            case "/${relative_path}" in
                */org/nd4j/linalg/cpu/nativecpu/bindings/"${CLASSIFIER}"/"${SHARED_RUNTIME_MANIFEST}") return 0 ;;
            esac
            ;;
        nd4j-cuda-*|nd4j-zluda*)
            case "/${relative_path}" in
                */org/nd4j/linalg/jcublas/bindings/"${CLASSIFIER}"/"${SHARED_RUNTIME_MANIFEST}") return 0 ;;
            esac
            ;;
        nd4j-vulkan*)
            case "/${relative_path}" in
                */org/nd4j/linalg/vulkan/bindings/"${CLASSIFIER}"/"${SHARED_RUNTIME_MANIFEST}") return 0 ;;
            esac
            ;;
        *)
            echo "ERROR: unsupported ND4J backend artifact '${BACKEND_ARTIFACT}'." >&2
            exit 1
            ;;
    esac
    return 1
}

BACKEND_MANIFESTS=()
if [ "${BACKEND_ARTIFACT}" != "none" ]; then
    while IFS= read -r manifest; do
        if is_selected_backend_manifest "${manifest}"; then
            BACKEND_MANIFESTS+=("${manifest}")
        fi
    done < <(find "${SOURCE_DIR}" -type f -name "${SHARED_RUNTIME_MANIFEST}" -print | LC_ALL=C sort)

    if [ "${#BACKEND_MANIFESTS[@]}" -ne 1 ]; then
        echo "ERROR: expected exactly one producer-owned ${BACKEND_ARTIFACT} manifest for ${CLASSIFIER}," >&2
        echo "       found ${#BACKEND_MANIFESTS[@]} under ${SOURCE_DIR}." >&2
        echo "       Build and unpack the matching ND4J classifier before assembling the dist." >&2
        exit 1
    fi
fi

BACKEND_MANIFEST=""
BACKEND_DIR=""
MANIFEST_RUNTIME_NAMES=()
if [ "${BACKEND_ARTIFACT}" != "none" ]; then
    BACKEND_MANIFEST="${BACKEND_MANIFESTS[0]}"
    BACKEND_DIR="$(dirname "${BACKEND_MANIFEST}")"
fi

validate_backend_manifest() {
    local format
    local count_line
    local declared_count
    local runtime_name
    local actual_count=0

    exec 3<"${BACKEND_MANIFEST}"
    IFS= read -r format <&3 || true
    IFS= read -r count_line <&3 || true

    if [ "${format}" != "${SHARED_RUNTIME_FORMAT}" ]; then
        echo "ERROR: unsupported shared-runtime manifest format in ${BACKEND_MANIFEST}." >&2
        exec 3<&-
        exit 1
    fi
    case "${count_line}" in
        "${RUNTIME_COUNT_PREFIX}"*)
            declared_count="${count_line#${RUNTIME_COUNT_PREFIX}}"
            ;;
        *)
            echo "ERROR: missing runtime-count in ${BACKEND_MANIFEST}." >&2
            exec 3<&-
            exit 1
            ;;
    esac
    case "${declared_count}" in
        ''|*[!0-9]*)
            echo "ERROR: invalid runtime-count '${declared_count}' in ${BACKEND_MANIFEST}." >&2
            exec 3<&-
            exit 1
            ;;
    esac

    while IFS= read -r runtime_name <&3 || [ -n "${runtime_name}" ]; do
        [ -n "${runtime_name}" ] || continue
        case "${runtime_name}" in
            \#*|.|..|*/*|*\\*)
                echo "ERROR: unsafe runtime entry '${runtime_name}' in ${BACKEND_MANIFEST}." >&2
                exec 3<&-
                exit 1
                ;;
        esac
        if ! is_native_name "${runtime_name}"; then
            echo "ERROR: non-native runtime entry '${runtime_name}' in ${BACKEND_MANIFEST}." >&2
            exec 3<&-
            exit 1
        fi
        for existing_name in "${MANIFEST_RUNTIME_NAMES[@]}"; do
            if [ "${existing_name}" = "${runtime_name}" ]; then
                echo "ERROR: duplicate runtime entry '${runtime_name}' in ${BACKEND_MANIFEST}." >&2
                exec 3<&-
                exit 1
            fi
        done
        if [ ! -f "${BACKEND_DIR}/${runtime_name}" ]; then
            echo "ERROR: manifest-declared runtime is missing: ${BACKEND_DIR}/${runtime_name}" >&2
            exec 3<&-
            exit 1
        fi
        MANIFEST_RUNTIME_NAMES+=("${runtime_name}")
        actual_count=$((actual_count + 1))
    done
    exec 3<&-

    if [ "${actual_count}" -ne "${declared_count}" ]; then
        echo "ERROR: shared-runtime manifest count mismatch in ${BACKEND_MANIFEST}:" >&2
        echo "       declared ${declared_count}, found ${actual_count} entries." >&2
        exit 1
    fi
}

if [ "${BACKEND_ARTIFACT}" != "none" ]; then
    validate_backend_manifest
fi

native_matches_base_platform() {
    local source_path="$1"
    local relative_path="${source_path#${SOURCE_DIR}/}"
    local normalized_path
    local platform_lower
    normalized_path="/$(printf '%s' "${relative_path}" | tr '[:upper:]' '[:lower:]')/"
    platform_lower="$(printf '%s' "${PLATFORM}" | tr '[:upper:]' '[:lower:]')"

    case "${OS}:${ARCH}" in
        linux:x86_64)
            case "${normalized_path}" in
                */linux-x86_64/*|*/linux-x86-64/*|*/linux/x86_64/*|*/linux/x86-64/*|*/linux/amd64/*|*/linux/x64/*) return 0 ;;
            esac
            ;;
        linux:arm64)
            case "${normalized_path}" in
                */linux-arm64/*|*/linux-aarch64/*|*/linux/arm64/*|*/linux/aarch64/*) return 0 ;;
            esac
            ;;
        macosx:x86_64)
            case "${normalized_path}" in
                */macosx-x86_64/*|*/darwin-x86_64/*|*/darwin/x86_64/*|*/darwin/x86-64/*|*/macos/x86_64/*|*/osx/x86_64/*) return 0 ;;
            esac
            ;;
        macosx:arm64)
            case "${normalized_path}" in
                */macosx-arm64/*|*/darwin-arm64/*|*/darwin-aarch64/*|*/darwin/arm64/*|*/darwin/aarch64/*|*/macos/arm64/*|*/macos/aarch64/*|*/osx/arm64/*) return 0 ;;
            esac
            ;;
        windows:x86_64)
            case "${normalized_path}" in
                */windows-x86_64/*|*/windows/x86_64/*|*/windows/amd64/*|*/win32-x86-64/*|*/win/x86_64/*|*/win/x64/*) return 0 ;;
            esac
            ;;
        windows:arm64)
            case "${normalized_path}" in
                */windows-arm64/*|*/windows-aarch64/*|*/windows/arm64/*|*/windows/aarch64/*|*/win32-arm64/*|*/win/arm64/*) return 0 ;;
            esac
            ;;
        *)
            case "${normalized_path}" in */"${platform_lower}"/*) return 0 ;; esac
            ;;
    esac
    return 1
}

native_matches_requested_flavor() {
    [ -n "${FLAVOR}" ] || return 1
    local source_path="$1"
    local relative_path="${source_path#${SOURCE_DIR}/}"
    local normalized_path
    local flavor_platform
    normalized_path="/$(printf '%s' "${relative_path}" | tr '[:upper:]' '[:lower:]')/"
    flavor_platform="$(printf '%s-%s' "${PLATFORM}" "${FLAVOR}" | tr '[:upper:]' '[:lower:]')"
    case "${normalized_path}" in */"${flavor_platform}"/*) return 0 ;; esac
    return 1
}

is_nd4j_binding_native() {
    local relative_path="${1#${SOURCE_DIR}/}"
    case "/${relative_path}" in
        */org/nd4j/linalg/*/bindings/*) return 0 ;;
    esac
    return 1
}

copy_flat_native() {
    local mode="$1"
    local source_path="$2"
    local loader_name
    local destination
    loader_name="$(basename "${source_path}")"
    destination="${DEST_DIR}/${loader_name}"

    if [ -e "${destination}" ]; then
        if cmp -s "${source_path}" "${destination}"; then
            return 0
        fi
        if [ "${mode}" != "flavor" ]; then
            echo "ERROR: conflicting ${PLATFORM} native libraries flatten to ${loader_name}:" >&2
            echo "       incoming: ${source_path}" >&2
            echo "       existing: ${destination}" >&2
            echo "       Refusing a traversal-order-dependent distribution." >&2
            exit 1
        fi
        echo "  flavor override: ${loader_name} <- ${CLASSIFIER}"
    fi
    cp -p "${source_path}" "${destination}"
}

validate_source_collisions() {
    local source_set="$1"
    shift
    local -a sources=("$@")
    local i
    local j
    local left_name
    local right_name

    for ((i = 0; i < ${#sources[@]}; i++)); do
        left_name="$(basename "${sources[$i]}")"
        for ((j = i + 1; j < ${#sources[@]}; j++)); do
            right_name="$(basename "${sources[$j]}")"
            if [ "${left_name}" = "${right_name}" ] && ! cmp -s "${sources[$i]}" "${sources[$j]}"; then
                echo "ERROR: conflicting ${source_set} native libraries flatten to ${left_name}:" >&2
                echo "       first:  ${sources[$i]}" >&2
                echo "       second: ${sources[$j]}" >&2
                echo "       Refusing a traversal-order-dependent distribution." >&2
                exit 1
            fi
        done
    done
}

SELECTED_BACKEND_SOURCES=()
backend_runtime=false
backend_jni=false
if [ "${BACKEND_ARTIFACT}" != "none" ]; then
    while IFS= read -r native_source; do
        SELECTED_BACKEND_SOURCES+=("${native_source}")
    done < <(find "${BACKEND_DIR}" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dll' \) -print | LC_ALL=C sort)

    if [ "${#SELECTED_BACKEND_SOURCES[@]}" -eq 0 ]; then
        echo "ERROR: selected backend classifier has no native libraries: ${BACKEND_DIR}" >&2
        exit 1
    fi

    for native_source in "${SELECTED_BACKEND_SOURCES[@]}"; do
        case "${BACKEND_ARTIFACT}:$(basename "${native_source}")" in
            nd4j-native:libnd4jcpu.so|nd4j-native:libnd4jcpu.dylib|nd4j-native:libnd4jcpu.dll|nd4j-native:nd4jcpu.dll)
                backend_runtime=true ;;
            nd4j-native:libjnind4jcpu.so|nd4j-native:libjnind4jcpu.dylib|nd4j-native:libjnind4jcpu.dll|nd4j-native:jnind4jcpu.dll)
                backend_jni=true ;;
            nd4j-cuda-*:*nd4jcuda.*|nd4j-zluda*:*nd4jcuda.*)
                case "$(basename "${native_source}")" in
                    libjnind4jcuda.*|jnind4jcuda.dll) backend_jni=true ;;
                    libnd4jcuda.*|nd4jcuda.dll) backend_runtime=true ;;
                esac
                ;;
            nd4j-vulkan*:*nd4jvulkan.*)
                case "$(basename "${native_source}")" in
                    libjnind4jvulkan.*|jnind4jvulkan.dll) backend_jni=true ;;
                    libnd4jvulkan.*|nd4jvulkan.dll) backend_runtime=true ;;
                esac
                ;;
        esac
    done
    if [ "${backend_runtime}" != true ] || [ "${backend_jni}" != true ]; then
        echo "ERROR: selected ${BACKEND_ARTIFACT} classifier ${CLASSIFIER} does not contain" >&2
        echo "       both its ND4J backend runtime and JNI bridge." >&2
        exit 1
    fi
fi

PLATFORM_NATIVE_SOURCES=()
FLAVOR_NATIVE_SOURCES=()
while IFS= read -r native_source; do
    if is_nd4j_binding_native "${native_source}"; then
        continue
    fi
    if native_matches_requested_flavor "${native_source}"; then
        FLAVOR_NATIVE_SOURCES+=("${native_source}")
    elif native_matches_base_platform "${native_source}"; then
        PLATFORM_NATIVE_SOURCES+=("${native_source}")
    fi
done < <(native_source_files | LC_ALL=C sort)

validate_source_collisions backend "${SELECTED_BACKEND_SOURCES[@]}"
validate_source_collisions baseline "${PLATFORM_NATIVE_SOURCES[@]}"
validate_source_collisions flavor "${FLAVOR_NATIVE_SOURCES[@]}"

MANIFEST_DESTINATION="${DEST_DIR}/${SHARED_RUNTIME_MANIFEST}"
if [ "${BACKEND_ARTIFACT}" != "none" ]; then
    if [ -e "${MANIFEST_DESTINATION}" ] && ! cmp -s "${BACKEND_MANIFEST}" "${MANIFEST_DESTINATION}"; then
        echo "ERROR: destination already contains a different ${SHARED_RUNTIME_MANIFEST}." >&2
        echo "       Refusing to mix backend runtime closures in ${DEST_DIR}." >&2
        exit 1
    fi

    echo "  Copying producer-owned ${BACKEND_ARTIFACT} classifier ${CLASSIFIER}..."
    for native_source in "${SELECTED_BACKEND_SOURCES[@]}"; do
        copy_flat_native backend "${native_source}"
    done
    cp -p "${BACKEND_MANIFEST}" "${MANIFEST_DESTINATION}"
fi

if [ "${#PLATFORM_NATIVE_SOURCES[@]}" -gt 0 ]; then
    echo "  Copying ${#PLATFORM_NATIVE_SOURCES[@]} additional ${PLATFORM} native sources..."
    for native_source in "${PLATFORM_NATIVE_SOURCES[@]}"; do
        copy_flat_native base "${native_source}"
    done
fi

if [ "${#FLAVOR_NATIVE_SOURCES[@]}" -gt 0 ]; then
    echo "  Applying ${#FLAVOR_NATIVE_SOURCES[@]} additional ${CLASSIFIER} flavor sources..."
    for native_source in "${FLAVOR_NATIVE_SOURCES[@]}"; do
        copy_flat_native flavor "${native_source}"
    done
fi

require_destination_native() {
    local dependency="$1"
    shift
    local pattern
    local candidate
    for pattern in "$@"; do
        for candidate in "${DEST_DIR}"/${pattern}; do
            if [ -f "${candidate}" ]; then
                return 0
            fi
        done
    done
    echo "ERROR: ${BACKEND_ARTIFACT} side-loaded runtime is missing ${dependency}." >&2
    echo "       Stage the matching JavaCPP CUDA redistributable classifier into ${DEST_DIR}." >&2
    exit 1
}

# The CUDA JNI classifier contains bridges, not the CUDA redistributable runtime.
# A native-image distribution cannot fall back to an installed CUDA toolkit, so
# validate the dynamic dependencies that libnd4jcuda requires from dist/lib.
if [ "${OS}" = linux ]; then
    case "${BACKEND_ARTIFACT}" in
        nd4j-cuda-*)
            require_destination_native CUDA-runtime 'libcudart.so.12*'
            require_destination_native cuBLAS 'libcublas.so.12*'
            require_destination_native cuBLAS-Lt 'libcublasLt.so.12*'
            require_destination_native cuSOLVER 'libcusolver.so.11*'
            require_destination_native cuSPARSE 'libcusparse.so.12*'
            require_destination_native NVRTC 'libnvrtc.so.12*'
            require_destination_native NVJitLink 'libnvJitLink.so.12*'
            ;;
    esac
fi

if [ "${OS}" != windows ]; then
    find "${DEST_DIR}" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' \) -exec chmod +x {} +
fi

# JavaCPP fabricates this alias during cache extraction. A dist bypasses that
# cache, so materialize the same loader name in the canonical flat layout.
if [ -f "${DEST_DIR}/libopenblas.so.0" ] && [ ! -e "${DEST_DIR}/libopenblas_nolapack.so.0" ]; then
    cp -p "${DEST_DIR}/libopenblas.so.0" "${DEST_DIR}/libopenblas_nolapack.so.0"
fi

dump_exported_symbols() {
    local native_file="$1"
    if command -v llvm-nm >/dev/null 2>&1; then
        case "${OS}" in
            linux) llvm-nm -D --defined-only "${native_file}" 2>/dev/null ;;
            macosx) llvm-nm -gU "${native_file}" 2>/dev/null ;;
            windows) llvm-nm -g --defined-only "${native_file}" 2>/dev/null ;;
        esac
        return
    fi
    if command -v nm >/dev/null 2>&1; then
        case "${OS}" in
            linux) nm -D --defined-only "${native_file}" 2>/dev/null ;;
            macosx) nm -gU "${native_file}" 2>/dev/null ;;
            windows) nm -g --defined-only "${native_file}" 2>/dev/null ;;
        esac
        return
    fi
    echo "ERROR: llvm-nm or nm is required to classify external JNI entrypoints." >&2
    return 127
}

generate_jni_entrypoint_manifest() {
    local manifest="${DEST_DIR}/${JNI_ENTRYPOINT_MANIFEST}"
    local temporary_manifest="${manifest}.tmp.$$"
    local native_file
    local symbols
    local -a entrypoints=()

    while IFS= read -r native_file; do
        if ! symbols="$(dump_exported_symbols "${native_file}")"; then
            echo "ERROR: cannot inspect exported symbols in ${native_file}." >&2
            exit 1
        fi
        case "$(basename "${native_file}")" in
            libjnijavacpp.*|jnijavacpp.dll) continue ;;
        esac
        # JavaCPP preset bridges export this forwarding entrypoint and must stay
        # lazy. Their generated Loader owns initialization after libjnijavacpp
        # and the selected ND4J backend have been established.
        if printf '%s\n' "${symbols}" | grep -Eq '(^|[[:space:]])JNI_OnLoad_jnijavacpp($|[[:space:]])'; then
            continue
        fi
        if printf '%s\n' "${symbols}" | grep -Eq '(^|[[:space:]])(JNI_OnLoad|Java_[^[:space:]]+)($|[[:space:]])'; then
            entrypoints+=("$(basename "${native_file}")")
        fi
    done < <(find "${DEST_DIR}" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dll' \) -print | LC_ALL=C sort)

    {
        printf '%s\n' "${JNI_ENTRYPOINT_FORMAT}"
        printf '# entry-count=%s\n' "${#entrypoints[@]}"
        if [ "${#entrypoints[@]}" -gt 0 ]; then
            printf '%s\n' "${entrypoints[@]}"
        fi
    } > "${temporary_manifest}"
    mv -f "${temporary_manifest}" "${manifest}"
    echo "  Classified ${#entrypoints[@]} direct JNI entrypoint libraries."
}

generate_jni_entrypoint_manifest

echo "  Staged target natives: backend=${#SELECTED_BACKEND_SOURCES[@]}, base=${#PLATFORM_NATIVE_SOURCES[@]}, flavor=${#FLAVOR_NATIVE_SOURCES[@]}, shared-runtimes=${#MANIFEST_RUNTIME_NAMES[@]}"
