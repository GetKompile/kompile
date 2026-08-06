#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NORMALIZER="${SCRIPT_DIR}/../../main/build/normalize-elf-portability.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

PATCHELF_PATH="${PATCHELF_BIN:-$(command -v patchelf 2>/dev/null || true)}"
if [ -z "${PATCHELF_PATH}" ] && [ -x /home/linuxbrew/.linuxbrew/bin/patchelf ]; then
    PATCHELF_PATH=/home/linuxbrew/.linuxbrew/bin/patchelf
fi
if [ -z "${PATCHELF_PATH}" ] || [ ! -x "${PATCHELF_PATH}" ]; then
    echo "SKIP: patchelf is required for normalize-elf-portability-test.sh"
    exit 0
fi
if ! command -v cc >/dev/null 2>&1; then
    echo "SKIP: a C compiler is required for normalize-elf-portability-test.sh"
    exit 0
fi

printf '%s\n' 'int main(void) { return 0; }' > "${TMP_DIR}/tiny.c"
cc "${TMP_DIR}/tiny.c" -o "${TMP_DIR}/tiny"
"${PATCHELF_PATH}" --set-interpreter /home/linuxbrew/.linuxbrew/lib/ld.so "${TMP_DIR}/tiny"
"${PATCHELF_PATH}" --remove-rpath "${TMP_DIR}/tiny"

PATCHELF_BIN="${PATCHELF_PATH}" bash "${NORMALIZER}" "${TMP_DIR}/tiny"
[ "$("${PATCHELF_PATH}" --print-interpreter "${TMP_DIR}/tiny")" = /lib64/ld-linux-x86-64.so.2 ]
[ "$("${PATCHELF_PATH}" --print-rpath "${TMP_DIR}/tiny")" = '$ORIGIN/../lib' ]

cp "${TMP_DIR}/tiny" "${TMP_DIR}/tiny.before-second-pass"
PATCHELF_BIN="${PATCHELF_PATH}" bash "${NORMALIZER}" "${TMP_DIR}/tiny"
cmp -s "${TMP_DIR}/tiny.before-second-pass" "${TMP_DIR}/tiny"

printf '%s\n' 'not an ELF file' > "${TMP_DIR}/plain.txt"
cp "${TMP_DIR}/plain.txt" "${TMP_DIR}/plain.before"
PATCHELF_BIN="${TMP_DIR}/missing-patchelf" bash "${NORMALIZER}" "${TMP_DIR}/plain.txt"
cmp -s "${TMP_DIR}/plain.before" "${TMP_DIR}/plain.txt"

if PATCHELF_BIN="${TMP_DIR}/missing-patchelf" bash "${NORMALIZER}" "${TMP_DIR}/tiny" \
        >"${TMP_DIR}/missing.out" 2>&1; then
    echo "ERROR: ELF normalization unexpectedly succeeded without patchelf" >&2
    exit 1
fi
if ! grep -q 'PATCHELF_BIN is not executable' "${TMP_DIR}/missing.out"; then
    echo "ERROR: missing-patchelf failure did not explain the contract" >&2
    exit 1
fi

echo "normalize-elf-portability tests passed"
