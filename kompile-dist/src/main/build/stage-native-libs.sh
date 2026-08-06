#!/usr/bin/env bash
#
# Stage the side-loaded native libraries used by Kompile native-image distributions.
# Both build-dist.sh and the kompile-dist Maven assembly call this helper so they
# apply the same platform, CPU-flavor, collision, and layout rules.

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
    echo "       Build kompile-app-main for ${PLATFORM}${PLATFORM_EXTENSION} before assembling the dist." >&2
    exit 1
fi

mkdir -p "${DEST_DIR}"

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
                */linux-x86_64/*|*/linux-x86-64/*|*/linux/x86_64/*|*/linux/x86-64/*|*/linux/amd64/*|*/linux/x64/*)
                    return 0
                    ;;
            esac
            ;;
        linux:arm64)
            case "${normalized_path}" in
                */linux-arm64/*|*/linux-aarch64/*|*/linux/arm64/*|*/linux/aarch64/*)
                    return 0
                    ;;
            esac
            ;;
        macosx:x86_64)
            case "${normalized_path}" in
                */macosx-x86_64/*|*/darwin-x86_64/*|*/darwin/x86_64/*|*/darwin/x86-64/*|*/macos/x86_64/*|*/osx/x86_64/*)
                    return 0
                    ;;
            esac
            ;;
        macosx:arm64)
            case "${normalized_path}" in
                */macosx-arm64/*|*/darwin-arm64/*|*/darwin-aarch64/*|*/darwin/arm64/*|*/darwin/aarch64/*|*/macos/arm64/*|*/macos/aarch64/*|*/osx/arm64/*)
                    return 0
                    ;;
            esac
            ;;
        windows:x86_64)
            case "${normalized_path}" in
                */windows-x86_64/*|*/windows/x86_64/*|*/windows/amd64/*|*/win32-x86-64/*|*/win/x86_64/*|*/win/x64/*)
                    return 0
                    ;;
            esac
            ;;
        windows:arm64)
            case "${normalized_path}" in
                */windows-arm64/*|*/windows-aarch64/*|*/windows/arm64/*|*/windows/aarch64/*|*/win32-arm64/*|*/win/arm64/*)
                    return 0
                    ;;
            esac
            ;;
        *)
            case "${normalized_path}" in
                */"${platform_lower}"/*)
                    return 0
                    ;;
            esac
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
    case "${normalized_path}" in
        */"${flavor_platform}"/*)
            return 0
            ;;
    esac
    return 1
}

copy_flat_native() {
    local mode="$1"
    local source_path="$2"
    local soname
    local destination
    soname=$(basename "${source_path}")
    destination="${DEST_DIR}/${soname}"

    if [ -e "${destination}" ]; then
        if cmp -s "${source_path}" "${destination}"; then
            return 0
        fi
        if [ "${mode}" != "flavor" ]; then
            echo "ERROR: conflicting ${PLATFORM} native libraries flatten to ${soname}:" >&2
            echo "       incoming: ${source_path}" >&2
            echo "       existing: ${destination}" >&2
            echo "       Refusing a traversal-order-dependent distribution." >&2
            exit 1
        fi
        echo "  flavor override: ${soname} <- ${PLATFORM}-${FLAVOR}"
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
        left_name=$(basename "${sources[$i]}")
        for ((j = i + 1; j < ${#sources[@]}; j++)); do
            right_name=$(basename "${sources[$j]}")
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

PLATFORM_NATIVE_SOURCES=()
FLAVOR_NATIVE_SOURCES=()
while IFS= read -r native_source; do
    if native_matches_requested_flavor "${native_source}"; then
        FLAVOR_NATIVE_SOURCES+=("${native_source}")
    elif native_matches_base_platform "${native_source}"; then
        PLATFORM_NATIVE_SOURCES+=("${native_source}")
    fi
done < <(native_source_files | LC_ALL=C sort)

validate_source_collisions baseline "${PLATFORM_NATIVE_SOURCES[@]}"
validate_source_collisions flavor "${FLAVOR_NATIVE_SOURCES[@]}"

if [ "${#PLATFORM_NATIVE_SOURCES[@]}" -eq 0 ]; then
    echo "ERROR: ${SOURCE_DIR} contains no native libraries for ${PLATFORM}." >&2
    echo "       Refusing to populate lib/ from another OS or architecture." >&2
    exit 1
fi

if [ -n "${FLAVOR}" ] && [ "${BACKEND_ARTIFACT}" = nd4j-native ]; then
    flavor_nd4j=false
    flavor_jni=false
    for native_source in "${FLAVOR_NATIVE_SOURCES[@]}"; do
        case "$(basename "${native_source}")" in
            libnd4jcpu.so|libnd4jcpu.dylib|libnd4jcpu.dll|nd4jcpu.dll)
                flavor_nd4j=true
                ;;
            libjnind4jcpu.so|libjnind4jcpu.dylib|libjnind4jcpu.dll|jnind4jcpu.dll)
                flavor_jni=true
                ;;
        esac
    done
    if [ "${flavor_nd4j}" != true ] || [ "${flavor_jni}" != true ]; then
        echo "ERROR: ${PLATFORM}-${FLAVOR} does not contain both the ND4J CPU runtime" >&2
        echo "       and JNI bridge. Refusing to label a baseline backend as '${FLAVOR}'." >&2
        exit 1
    fi
fi

if [ -n "${FLAVOR}" ] && [ "${BACKEND_ARTIFACT}" != nd4j-native ] && [ "${#FLAVOR_NATIVE_SOURCES[@]}" -eq 0 ]; then
    echo "ERROR: ${PLATFORM}-${FLAVOR} contains no native libraries for ${BACKEND_ARTIFACT}." >&2
    echo "       Refusing to label a baseline backend as '${FLAVOR}'." >&2
    exit 1
fi

echo "  Copying ${#PLATFORM_NATIVE_SOURCES[@]} ${PLATFORM} native sources to ${DEST_DIR}..."
for native_source in "${PLATFORM_NATIVE_SOURCES[@]}"; do
    copy_flat_native base "${native_source}"
done

if [ "${#FLAVOR_NATIVE_SOURCES[@]}" -gt 0 ]; then
    echo "  Applying ${#FLAVOR_NATIVE_SOURCES[@]} ${PLATFORM}-${FLAVOR} flavor sources..."
    for native_source in "${FLAVOR_NATIVE_SOURCES[@]}"; do
        copy_flat_native flavor "${native_source}"
    done
fi

if [ "${OS}" != windows ]; then
    find "${DEST_DIR}" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' \) -exec chmod +x {} +
fi

# JavaCPP fabricates this alias during cache extraction. A dist bypasses that
# cache, so materialize the same soname as a regular file in the canonical flat
# layout used by both packaging routes.
if [ -f "${DEST_DIR}/libopenblas.so.0" ] && [ ! -e "${DEST_DIR}/libopenblas_nolapack.so.0" ]; then
    cp -p "${DEST_DIR}/libopenblas.so.0" "${DEST_DIR}/libopenblas_nolapack.so.0"
fi

echo "  Staged target natives: base=${#PLATFORM_NATIVE_SOURCES[@]}, flavor=${#FLAVOR_NATIVE_SOURCES[@]}"
