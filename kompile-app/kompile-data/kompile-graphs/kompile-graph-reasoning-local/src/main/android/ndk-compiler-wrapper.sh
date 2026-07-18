#!/usr/bin/env bash
# Compiler adapter used only by stock GraalVM's C ABI probing/CAP-cache phase.
# Compile steps go straight to NDK clang. Executable probes are linked static
# for AArch64 and replaced with a host launcher that runs them through QEMU.
set -euo pipefail

: "${ANDROID_NDK:?Set ANDROID_NDK to the Android NDK root}"
ANDROID_API="${ANDROID_API:-28}"
NDK_HOST_TAG="${NDK_HOST_TAG:-linux-x86_64}"
QEMU_AARCH64="${QEMU_AARCH64:-qemu-aarch64-static}"
CLANG="${ANDROID_NDK}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/bin/aarch64-linux-android${ANDROID_API}-clang"

[[ -x "$CLANG" ]] || { echo "NDK clang not found: $CLANG" >&2; exit 2; }

link_step=true
output=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[$i]}" in
        -c|-E|-S) link_step=false ;;
        -o)
            if ((i + 1 < ${#args[@]})); then
                output="${args[$((i + 1))]}"
            fi
            ;;
    esac
done

if [[ "$link_step" != true || -z "$output" ]]; then
    exec "$CLANG" "$@"
fi

command -v "$QEMU_AARCH64" >/dev/null 2>&1 || {
    echo "AArch64 QEMU runner not found: $QEMU_AARCH64" >&2
    exit 3
}

target_output="${output}.aarch64"
rewritten=()
for ((i = 0; i < ${#args[@]}; i++)); do
    if [[ "${args[$i]}" == "-o" ]] && ((i + 1 < ${#args[@]})); then
        rewritten+=("-o" "$target_output")
        ((i += 1))
    else
        rewritten+=("${args[$i]}")
    fi
done

"$CLANG" -static "${rewritten[@]}"
printf '#!/usr/bin/env bash\nexec %q %q "$@"\n' "$QEMU_AARCH64" "$target_output" > "$output"
chmod +x "$output"
