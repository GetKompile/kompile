#!/usr/bin/env bash
# Normalize staged Linux ELF executables for distribution portability.
#
# This helper must only be called on copies staged for publication. It deliberately
# fails closed for ELF inputs when patchelf is unavailable: an unpatched native
# archive can boot on the build host and fail immediately on another glibc system.

set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "usage: $0 <staged-binary> [staged-binary ...]" >&2
    exit 2
fi

HOST_OS="$(uname -s)"
HOST_ARCH="$(uname -m)"
CANONICAL_RPATH='$ORIGIN/../lib'

find_patchelf() {
    local candidate=""

    # PATCHELF_BIN is an explicit override and is intentionally fail-closed. It
    # also makes the missing-tool contract testable without modifying PATH.
    if [ "${PATCHELF_BIN+x}" = x ]; then
        if [ -x "${PATCHELF_BIN}" ]; then
            printf '%s\n' "${PATCHELF_BIN}"
            return 0
        fi
        echo "ERROR: PATCHELF_BIN is not executable: ${PATCHELF_BIN:-<empty>}" >&2
        return 1
    fi

    for candidate in \
            "$(command -v patchelf 2>/dev/null || true)" \
            "/home/linuxbrew/.linuxbrew/bin/patchelf" \
            "${HOME}/.local/bin/patchelf"; do
        if [ -x "${candidate}" ]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    done

    echo "ERROR: patchelf is required to publish Linux native distributions." >&2
    echo "       Install patchelf or set PATCHELF_BIN to an executable path." >&2
    return 1
}

PATCHELF_PATH=""

for binary in "$@"; do
    if [ ! -f "${binary}" ]; then
        echo "ERROR: staged binary does not exist: ${binary}" >&2
        exit 1
    fi

    if ! command -v file >/dev/null 2>&1; then
        echo "ERROR: the 'file' command is required to inspect staged binaries." >&2
        exit 1
    fi

    description="$(file -b "${binary}" 2>/dev/null || true)"
    case "${description}" in
        *ELF*) ;;
        *)
            echo "  portability SKIP: ${binary} is not ELF"
            continue
            ;;
    esac

    if [ "${HOST_OS}" != "Linux" ]; then
        echo "  portability SKIP: ${binary} is ELF but host is ${HOST_OS}"
        continue
    fi

    if [ -z "${PATCHELF_PATH}" ]; then
        PATCHELF_PATH="$(find_patchelf)" || exit 1
    fi

    changed=0
    current_interpreter="$("${PATCHELF_PATH}" --print-interpreter "${binary}" 2>/dev/null || true)"
    canonical_interpreter=""
    case "${HOST_ARCH}" in
        x86_64|amd64)
            canonical_interpreter="/lib64/ld-linux-x86-64.so.2"
            ;;
    esac

    if [ -n "${canonical_interpreter}" ] \
            && [ -n "${current_interpreter}" ] \
            && [ "${current_interpreter}" != "${canonical_interpreter}" ]; then
        echo "  patchelf: ${binary}: interpreter ${current_interpreter} -> ${canonical_interpreter}"
        "${PATCHELF_PATH}" --set-interpreter "${canonical_interpreter}" "${binary}"
        changed=1
    fi

    current_rpath="$("${PATCHELF_PATH}" --print-rpath "${binary}" 2>/dev/null || true)"
    if [ "${current_rpath}" != "${CANONICAL_RPATH}" ]; then
        if [ -n "${current_rpath}" ]; then
            echo "  patchelf: ${binary}: RPATH ${current_rpath} -> ${CANONICAL_RPATH}"
        else
            echo "  patchelf: ${binary}: setting RPATH -> ${CANONICAL_RPATH}"
        fi
        "${PATCHELF_PATH}" --set-rpath "${CANONICAL_RPATH}" "${binary}"
        changed=1
    fi

    if [ "${changed}" -eq 0 ]; then
        echo "  portability OK: ${binary}"
    fi
done
