#!/usr/bin/env bash
# Fail-closed audit for the Android AArch64 graph-reasoning shared library.
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: verify-android-ndk.sh --library <lib.so> --android-ndk <ndk-root>

Checks the AArch64/bionic ELF identity, exact public ABI, exact Android
dependency closure, 16 KiB load alignment, RELRO/BIND_NOW, and forbidden host
or BLAS markers.
USAGE
}

LIBRARY=""
ANDROID_NDK="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}}"
NDK_HOST_TAG="${NDK_HOST_TAG:-linux-x86_64}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --library) LIBRARY="${2:?missing value for --library}"; shift 2 ;;
        --android-ndk) ANDROID_NDK="${2:?missing value for --android-ndk}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[[ -f "$LIBRARY" ]] || { echo "Android graph library not found: $LIBRARY" >&2; exit 3; }
[[ -d "$ANDROID_NDK" ]] || { echo "Android NDK not found: $ANDROID_NDK" >&2; exit 3; }

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
READELF="$TOOLCHAIN/llvm-readelf"
NM="$TOOLCHAIN/llvm-nm"
STRINGS="$TOOLCHAIN/llvm-strings"
for tool in "$READELF" "$NM" "$STRINGS"; do
    [[ -x "$tool" ]] || { echo "Required NDK tool not found: $tool" >&2; exit 3; }
done

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

HEADER="$TMP_DIR/header.txt"
DYNAMIC="$TMP_DIR/dynamic.txt"
NOTES="$TMP_DIR/notes.txt"
PROGRAMS="$TMP_DIR/programs.txt"
ACTUAL_EXPORTS="$TMP_DIR/actual-exports.txt"
EXPECTED_EXPORTS="$TMP_DIR/expected-exports.txt"
ACTUAL_NEEDED="$TMP_DIR/actual-needed.txt"
EXPECTED_NEEDED="$TMP_DIR/expected-needed.txt"
UNDEFINED_SYMBOLS="$TMP_DIR/undefined-symbols.txt"
BINARY_STRINGS="$TMP_DIR/strings.txt"

"$READELF" -h "$LIBRARY" > "$HEADER"
"$READELF" -d "$LIBRARY" > "$DYNAMIC"
"$READELF" -n "$LIBRARY" > "$NOTES"
"$READELF" -l "$LIBRARY" > "$PROGRAMS"
"$NM" -D --defined-only --format=posix "$LIBRARY" | awk '{print $1}' | sort -u > "$ACTUAL_EXPORTS"
"$NM" -D --undefined-only --format=posix "$LIBRARY" > "$UNDEFINED_SYMBOLS"
"$STRINGS" -a "$LIBRARY" > "$BINARY_STRINGS"

require_match() {
    local pattern="$1"
    local file="$2"
    local message="$3"
    if ! grep -Eq "$pattern" "$file"; then
        echo "$message" >&2
        exit 4
    fi
}

reject_match() {
    local pattern="$1"
    local file="$2"
    local message="$3"
    if grep -Eiq "$pattern" "$file"; then
        echo "$message" >&2
        exit 4
    fi
}

require_match 'Class:[[:space:]]+ELF64' "$HEADER" "Library is not ELF64"
require_match 'Type:[[:space:]]+DYN' "$HEADER" "Library is not a shared object"
require_match 'Machine:[[:space:]]+AArch64' "$HEADER" "Library is not AArch64"
require_match 'Android[[:space:]]+0x[0-9a-f]+[[:space:]]+NT_ANDROID_TYPE_IDENT' "$NOTES"     "Library has no Android NDK identity note"
require_match '\(SONAME\).*\[libkompile_reasoning_android\.so\]' "$DYNAMIC"     "Unexpected or missing Android graph SONAME"
require_match 'BIND_NOW' "$DYNAMIC" "Library was not linked with immediate binding"
require_match 'GNU_RELRO' "$PROGRAMS" "Library has no GNU RELRO segment"
reject_match '\(RPATH\)|\(RUNPATH\)|TEXTREL' "$DYNAMIC"     "Library contains an RPATH, RUNPATH, or text relocation"

awk '/LOAD/ {print $NF}' "$PROGRAMS" | sort -u > "$TMP_DIR/load-alignments.txt"
if [[ "$(cat "$TMP_DIR/load-alignments.txt")" != "0x4000" ]]; then
    echo "Every Android LOAD segment must use 16 KiB alignment; found:" >&2
    cat "$TMP_DIR/load-alignments.txt" >&2
    exit 4
fi

cat > "$EXPECTED_EXPORTS" <<'EXPORTS'
kgr_abi_version
kgr_attach_thread
kgr_close
kgr_create_isolate
kgr_detach_thread
kgr_dispatch
kgr_free
kgr_open
kgr_save
kgr_tear_down_isolate
kgr_tools
EXPORTS
sort -o "$EXPECTED_EXPORTS" "$EXPECTED_EXPORTS"
if ! cmp -s "$EXPECTED_EXPORTS" "$ACTUAL_EXPORTS"; then
    echo "Android graph public ABI mismatch:" >&2
    diff -u "$EXPECTED_EXPORTS" "$ACTUAL_EXPORTS" >&2 || true
    exit 4
fi

awk '/\(NEEDED\)/ {name=$NF; gsub(/^\[/, "", name); gsub(/\]$/, "", name); print name}'     "$DYNAMIC" | sort -u > "$ACTUAL_NEEDED"
cat > "$EXPECTED_NEEDED" <<'NEEDED'
libc.so
libdl.so
liblog.so
libm.so
libz.so
NEEDED
if ! cmp -s "$EXPECTED_NEEDED" "$ACTUAL_NEEDED"; then
    echo "Android graph dependency closure mismatch:" >&2
    diff -u "$EXPECTED_NEEDED" "$ACTUAL_NEEDED" >&2 || true
    exit 4
fi

reject_match 'openblas|cblas_|libgfortran|libquadmath|GLIBC_|GLIBCXX_' \
    "$UNDEFINED_SYMBOLS" "Forbidden BLAS, Fortran, glibc, or host C++ symbol in Android dependency closure"
reject_match 'linuxbrew|x86_64-linux-gnu' \
    "$BINARY_STRINGS" "Forbidden host build path embedded in Android graph library"

SHA256="$(sha256sum "$LIBRARY" | awk '{print $1}')"
SIZE="$(stat -c '%s' "$LIBRARY")"
BUILD_ID="$(awk '/Build ID:/ {print $3}' "$NOTES")"
printf 'Verified Android graph AOT library\n'
printf '  file: %s\n' "$LIBRARY"
printf '  sha256: %s\n' "$SHA256"
printf '  bytes: %s\n' "$SIZE"
printf '  build-id: %s\n' "$BUILD_ID"
printf '  exports: 11 exact kgr_* symbols\n'
printf '  needed: libc, libdl, liblog, libm, libz\n'
