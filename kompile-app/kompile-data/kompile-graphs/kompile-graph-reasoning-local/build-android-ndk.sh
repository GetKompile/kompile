#!/usr/bin/env bash
# Build libkompile_reasoning_android.so with stock GraalVM Native Image and the
# Android NDK. No Gluon/GluonFX component participates in this pipeline.
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage: build-android-ndk.sh [options]

Required environment or options:
  --android-ndk <dir>       Android NDK r28b root
  --graalvm-home <dir>      Oracle GraalVM 21.0.10 / Native Image 23.1.10

Options:
  --maven <command>         Maven executable (default: mvn)
  --jobs <n>                Parallel native build jobs (default: host CPUs)
  --work-dir <dir>          Disposable/cached build root under target/
  --output-dir <dir>        SDK output root (default: target/android-aot)
  --classes-dir <dir>       Compiled project classes supplied by Maven
  --classpath-file <file>   Runtime dependency classpath supplied by Maven
  --strict-classpath        Reject reachable classes with missing dependencies
  --reuse-object <file>     Skip Native Image and relink this AArch64 object
  --object-output <file>    Publish the verified relocatable object and stop
  --reuse-jdk-libs <dir>    Reuse the verified JDK 21.0.10 native closure
  --reuse-svm-libs <dir>    Reuse libjvm/liblibchelper archives
  --support-libraries-only  Build/verify support archives and stop
  --offline                 Do not fetch upstream sources; Maven runs offline
  --clean                   Remove this script's work directory first
  -h, --help                Show this help

The default path is a full source build. Upstream sources are pinned to exact
commits and cached below the work directory. Reuse options exist for fast,
deterministic relinking and CI audits.
USAGE
}

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_SUPPORT="$MODULE_DIR/src/main/android"
ANDROID_NDK="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}}"
GRAALVM_HOME="${GRAALVM_HOME:-${JAVA_HOME:-}}"
MAVEN="${MAVEN:-$MODULE_DIR/../../../../mvnw}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 8)}"
WORK_DIR="$MODULE_DIR/target/android-ndk-aot"
OUTPUT_DIR="$MODULE_DIR/target/android-aot"
CLASSES_DIR=""
CLASSPATH_FILE=""
REUSE_OBJECT=""
OBJECT_OUTPUT=""
REUSE_JDK_LIBS=""
REUSE_SVM_LIBS=""
SUPPORT_LIBRARIES_ONLY=false
STRICT_CLASSPATH=false
OFFLINE=false
CLEAN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --android-ndk) ANDROID_NDK="${2:?missing value for --android-ndk}"; shift 2 ;;
        --graalvm-home) GRAALVM_HOME="${2:?missing value for --graalvm-home}"; shift 2 ;;
        --maven) MAVEN="${2:?missing value for --maven}"; shift 2 ;;
        --jobs) JOBS="${2:?missing value for --jobs}"; shift 2 ;;
        --work-dir) WORK_DIR="${2:?missing value for --work-dir}"; shift 2 ;;
        --output-dir) OUTPUT_DIR="${2:?missing value for --output-dir}"; shift 2 ;;
        --classes-dir) CLASSES_DIR="${2:?missing value for --classes-dir}"; shift 2 ;;
        --classpath-file) CLASSPATH_FILE="${2:?missing value for --classpath-file}"; shift 2 ;;
        --strict-classpath) STRICT_CLASSPATH=true; shift ;;
        --reuse-object) REUSE_OBJECT="${2:?missing value for --reuse-object}"; shift 2 ;;
        --object-output) OBJECT_OUTPUT="${2:?missing value for --object-output}"; shift 2 ;;
        --reuse-jdk-libs) REUSE_JDK_LIBS="${2:?missing value for --reuse-jdk-libs}"; shift 2 ;;
        --reuse-svm-libs) REUSE_SVM_LIBS="${2:?missing value for --reuse-svm-libs}"; shift 2 ;;
        --support-libraries-only) SUPPORT_LIBRARIES_ONLY=true; shift ;;
        --offline) OFFLINE=true; shift ;;
        --clean) CLEAN=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

ANDROID_API=28
EXPECTED_NDK_REVISION=28.1.13356709
EXPECTED_GRAAL_JAVA=21.0.10
EXPECTED_NATIVE_IMAGE=jvmci-23.1-b84
NDK_HOST_TAG="${NDK_HOST_TAG:-linux-x86_64}"
LABSJDK_URL=https://github.com/graalvm/labs-openjdk-21.git
LABSJDK_REF=jvmci-23.1-b33
LABSJDK_COMMIT=ef9d66c6808536e7029680f6f4d965359f8f20c8
EXPECTED_JDK_SOURCE_VERSION=21.0.10
GRAAL_URL=https://github.com/oracle/graal.git
GRAAL_REF=vm-23.1.5
GRAAL_COMMIT=67b6384f4502ffd46aef357d6bcfaf249b68d7d3
EXPECTED_UNIX_FILE_ATTRIBUTES_FIELD=st_birthtime_sec
FORBIDDEN_UNIX_FILE_ATTRIBUTES_FIELD=st_birthtime_nsec
LABSJDK_UNIX_FILE_ATTRIBUTES_PATCH_SHA256=309179be37326ce1f0384836bb89c5f598cac6586e0f4d1f0dd3fd9f6ac704a4
LABSJDK_SHUTDOWN_WRITE_PATCH_SHA256=5b6c08466a2dd41c666108cc62ed9ce42b0daa36ecfe5d56a46a48c8b6f5dde4

fail() {
    echo "ERROR: $*" >&2
    exit 3
}

assert_unix_file_attributes_abi() {
    local binary="$1"
    [[ -s "$binary" ]] || fail "UnixFileAttributes ABI verification input is missing: $binary"
    LC_ALL=C grep -a -F -q "$EXPECTED_UNIX_FILE_ATTRIBUTES_FIELD" "$binary" ||
        fail "$binary does not reference required field $EXPECTED_UNIX_FILE_ATTRIBUTES_FIELD"
    if LC_ALL=C grep -a -F -q "$FORBIDDEN_UNIX_FILE_ATTRIBUTES_FIELD" "$binary"; then
        fail "$binary references forbidden GraalVM field $FORBIDDEN_UNIX_FILE_ATTRIBUTES_FIELD"
    fi
}

[[ -d "$ANDROID_NDK" ]] || fail "Android NDK not found: $ANDROID_NDK"
[[ -x "$GRAALVM_HOME/bin/native-image" ]] || fail "native-image not found: $GRAALVM_HOME/bin/native-image"
[[ -f "$ANDROID_NDK/source.properties" ]] || fail "NDK source.properties is missing"

NDK_REVISION="$(awk -F= '/Pkg.Revision/ {gsub(/[[:space:]]/, "", $2); print $2}' "$ANDROID_NDK/source.properties")"
[[ "$NDK_REVISION" == "$EXPECTED_NDK_REVISION" ]] ||     fail "Expected NDK $EXPECTED_NDK_REVISION, found $NDK_REVISION"

NATIVE_IMAGE_VERSION="$("$GRAALVM_HOME/bin/native-image" --version 2>&1)"
[[ "$NATIVE_IMAGE_VERSION" == *"$EXPECTED_GRAAL_JAVA"* ]] ||     fail "Expected GraalVM Java $EXPECTED_GRAAL_JAVA: $NATIVE_IMAGE_VERSION"
[[ "$NATIVE_IMAGE_VERSION" == *"$EXPECTED_NATIVE_IMAGE"* ]] ||     fail "Expected Native Image build $EXPECTED_NATIVE_IMAGE: $NATIVE_IMAGE_VERSION"
[[ -x "$GRAALVM_HOME/bin/javap" ]] || fail "GraalVM javap is required to verify the private java.base ABI"
UNIX_FILE_ATTRIBUTES_LAYOUT="$("$GRAALVM_HOME/bin/javap" -private sun.nio.fs.UnixFileAttributes 2>&1)" ||
    fail "Could not inspect GraalVM sun.nio.fs.UnixFileAttributes: $UNIX_FILE_ATTRIBUTES_LAYOUT"
[[ "$UNIX_FILE_ATTRIBUTES_LAYOUT" == *"long $EXPECTED_UNIX_FILE_ATTRIBUTES_FIELD;"* ]] ||
    fail "GraalVM UnixFileAttributes is missing $EXPECTED_UNIX_FILE_ATTRIBUTES_FIELD: $UNIX_FILE_ATTRIBUTES_LAYOUT"
[[ "$UNIX_FILE_ATTRIBUTES_LAYOUT" != *"long $FORBIDDEN_UNIX_FILE_ATTRIBUTES_FIELD;"* ]] ||
    fail "GraalVM UnixFileAttributes unexpectedly exposes $FORBIDDEN_UNIX_FILE_ATTRIBUTES_FIELD: $UNIX_FILE_ATTRIBUTES_LAYOUT"

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
CLANG="$TOOLCHAIN/aarch64-linux-android${ANDROID_API}-clang"
CLANGXX="$TOOLCHAIN/aarch64-linux-android${ANDROID_API}-clang++"
LLVM_AR="$TOOLCHAIN/llvm-ar"
LLVM_NM="$TOOLCHAIN/llvm-nm"
LLVM_STRIP="$TOOLCHAIN/llvm-strip"
LLVM_OBJCOPY="$TOOLCHAIN/llvm-objcopy"
LLVM_OBJDUMP="$TOOLCHAIN/llvm-objdump"
LLVM_READELF="$TOOLCHAIN/llvm-readelf"
for tool in "$CLANG" "$CLANGXX" "$LLVM_AR" "$LLVM_NM" "$LLVM_STRIP" "$LLVM_READELF"; do
    [[ -x "$tool" ]] || fail "Required NDK tool not found: $tool"
done

if [[ "$CLEAN" == true ]]; then
    rm -rf "$WORK_DIR"
fi
mkdir -p "$WORK_DIR" "$OUTPUT_DIR/jni/arm64-v8a" "$OUTPUT_DIR/include" "$OUTPUT_DIR/metadata"

echo "Stock GraalVM + NDK Android graph build"
echo "  module:       $MODULE_DIR"
echo "  GraalVM:      $NATIVE_IMAGE_VERSION"
echo "  NDK:          $NDK_REVISION"
echo "  Android API:  $ANDROID_API"
echo "  work:         $WORK_DIR"
echo "  output:       $OUTPUT_DIR"

checkout_pinned() {
    local directory="$1"
    local url="$2"
    local ref="$3"
    local commit="$4"
    shift 4

    if [[ ! -d "$directory/.git" ]]; then
        [[ "$OFFLINE" == false ]] || fail "Offline mode requires cached checkout: $directory"
        git clone --depth 1 --filter=blob:none --no-checkout --branch "$ref" "$url" "$directory"
    fi

    local actual
    actual="$(git -C "$directory" rev-parse HEAD)"
    if [[ "$actual" != "$commit" ]]; then
        [[ "$OFFLINE" == false ]] || fail "Pinned checkout mismatch in offline mode: $actual"
        git -C "$directory" fetch --depth 1 origin "$commit"
        git -C "$directory" checkout --detach "$commit"
    fi
    [[ "$(git -C "$directory" rev-parse HEAD)" == "$commit" ]] || fail "Could not pin $directory"
}

SVM_LIB_DIR="$WORK_DIR/clibraries/bionic"
if [[ -n "$REUSE_SVM_LIBS" ]]; then
    SVM_LIB_DIR="$REUSE_SVM_LIBS"
else
    GRAAL_SOURCE="$WORK_DIR/upstream/graal-$GRAAL_COMMIT"
    checkout_pinned "$GRAAL_SOURCE" "$GRAAL_URL" "$GRAAL_REF" "$GRAAL_COMMIT"
    git -C "$GRAAL_SOURCE" sparse-checkout init --cone
    git -C "$GRAAL_SOURCE" sparse-checkout set         substratevm/src/com.oracle.svm.native.libchelper         substratevm/src/com.oracle.svm.native.jvm.posix
    git -C "$GRAAL_SOURCE" checkout --detach "$GRAAL_COMMIT"

    SVM_OBJECTS="$WORK_DIR/objects/svm"
    mkdir -p "$SVM_OBJECTS/libjvm" "$SVM_OBJECTS/libchelper" "$SVM_LIB_DIR"
    SVM_ROOT="$GRAAL_SOURCE/substratevm/src"
    LIBJVM_SOURCE="$SVM_ROOT/com.oracle.svm.native.jvm.posix/src/JvmFuncs.c"
    LIBCHELPER_ROOT="$SVM_ROOT/com.oracle.svm.native.libchelper"
    COMMON_SVM_FLAGS=(
        -O2 -fPIC -ffunction-sections -fdata-sections
        -D_GNU_SOURCE
        -I"$GRAALVM_HOME/include"
        -I"$GRAALVM_HOME/include/linux"
        -I"$LIBCHELPER_ROOT/include"
        -include "$ANDROID_SUPPORT/android_compat.h"
    )

    "$CLANG" "${COMMON_SVM_FLAGS[@]}" -c "$LIBJVM_SOURCE"         -o "$SVM_OBJECTS/libjvm/JvmFuncs.o"
    "$LLVM_AR" rcsD "$SVM_LIB_DIR/libjvm.a" "$SVM_OBJECTS/libjvm/JvmFuncs.o"

    LIBCHELPER_OBJECTS=()
    for source in "$LIBCHELPER_ROOT"/src/*.c; do
        object="$SVM_OBJECTS/libchelper/$(basename "${source%.c}").o"
        "$CLANG" "${COMMON_SVM_FLAGS[@]}" -c "$source" -o "$object"
        LIBCHELPER_OBJECTS+=("$object")
    done
    COMPAT_OBJECT="$SVM_OBJECTS/libchelper/android_compat.o"
    "$CLANG" "${COMMON_SVM_FLAGS[@]}" -c "$ANDROID_SUPPORT/android_compat.c"         -o "$COMPAT_OBJECT"
    LIBCHELPER_OBJECTS+=("$COMPAT_OBJECT")
    "$LLVM_AR" rcsD "$SVM_LIB_DIR/liblibchelper.a" "${LIBCHELPER_OBJECTS[@]}"
fi

for archive in libjvm.a liblibchelper.a; do
    [[ -s "$SVM_LIB_DIR/$archive" ]] || fail "Missing SVM support archive: $SVM_LIB_DIR/$archive"
done

PATCH_FILE="$ANDROID_SUPPORT/labsjdk-net-util-md.patch"
UNIX_FILE_ATTRIBUTES_PATCH="$ANDROID_SUPPORT/labsjdk-unix-file-attributes-abi.patch"
SHUTDOWN_WRITE_PATCH="$ANDROID_SUPPORT/labsjdk-net-shutdown-write-abi.patch"
[[ -f "$PATCH_FILE" ]] || fail "Pinned bionic compatibility patch is missing: $PATCH_FILE"
[[ -f "$UNIX_FILE_ATTRIBUTES_PATCH" ]] ||
    fail "Pinned UnixFileAttributes ABI patch is missing: $UNIX_FILE_ATTRIBUTES_PATCH"
[[ -f "$SHUTDOWN_WRITE_PATCH" ]] ||
    fail "Pinned Net shutdown-write ABI patch is missing: $SHUTDOWN_WRITE_PATCH"
UNIX_FILE_ATTRIBUTES_PATCH_SHA256="$(sha256sum "$UNIX_FILE_ATTRIBUTES_PATCH" | awk '{print $1}')"
[[ "$UNIX_FILE_ATTRIBUTES_PATCH_SHA256" == "$LABSJDK_UNIX_FILE_ATTRIBUTES_PATCH_SHA256" ]] ||
    fail "UnixFileAttributes ABI patch digest mismatch: expected $LABSJDK_UNIX_FILE_ATTRIBUTES_PATCH_SHA256, found $UNIX_FILE_ATTRIBUTES_PATCH_SHA256"
SHUTDOWN_WRITE_PATCH_SHA256="$(sha256sum "$SHUTDOWN_WRITE_PATCH" | awk '{print $1}')"
[[ "$SHUTDOWN_WRITE_PATCH_SHA256" == "$LABSJDK_SHUTDOWN_WRITE_PATCH_SHA256" ]] ||
    fail "Net shutdown-write ABI patch digest mismatch: expected $LABSJDK_SHUTDOWN_WRITE_PATCH_SHA256, found $SHUTDOWN_WRITE_PATCH_SHA256"

JDK_LIB_DIR="$WORK_DIR/clibraries/bionic"
if [[ -n "$REUSE_JDK_LIBS" ]]; then
    JDK_LIB_DIR="$REUSE_JDK_LIBS"
else
    JDK_SOURCE="$WORK_DIR/upstream/labs-openjdk-21-$LABSJDK_COMMIT"
    checkout_pinned "$JDK_SOURCE" "$LABSJDK_URL" "$LABSJDK_REF" "$LABSJDK_COMMIT"
    # Oracle GraalVM's reachable java.base ABI includes LabsJDK-only native
    # methods (for example UnixNativeDispatcher.exists0). Upstream OpenJDK
    # 21.0.10 has the same feature version but is not this native ABI.
    # Do not reset it on retries: the bionic compatibility patch below is intentionally tracked.
    if [[ ! -f "$JDK_SOURCE/configure" ]]; then
        git -C "$JDK_SOURCE" checkout --detach "$LABSJDK_COMMIT"
    fi

    if git -C "$JDK_SOURCE" apply --check "$PATCH_FILE" >/dev/null 2>&1; then
        git -C "$JDK_SOURCE" apply "$PATCH_FILE"
    elif ! git -C "$JDK_SOURCE" apply --reverse --check "$PATCH_FILE" >/dev/null 2>&1; then
        fail "OpenJDK bionic patch neither applies nor is already present"
    fi

    if git -C "$JDK_SOURCE" apply --check "$UNIX_FILE_ATTRIBUTES_PATCH" >/dev/null 2>&1; then
        git -C "$JDK_SOURCE" apply "$UNIX_FILE_ATTRIBUTES_PATCH"
    elif ! git -C "$JDK_SOURCE" apply --reverse --check "$UNIX_FILE_ATTRIBUTES_PATCH" >/dev/null 2>&1; then
        fail "LabsJDK UnixFileAttributes ABI patch neither applies nor is already present"
    fi

    if git -C "$JDK_SOURCE" apply --check "$SHUTDOWN_WRITE_PATCH" >/dev/null 2>&1; then
        git -C "$JDK_SOURCE" apply "$SHUTDOWN_WRITE_PATCH"
    elif ! git -C "$JDK_SOURCE" apply --reverse --check "$SHUTDOWN_WRITE_PATCH" >/dev/null 2>&1; then
        fail "LabsJDK Net shutdown-write ABI patch neither applies nor is already present"
    fi

    FAKE_DEPS="$WORK_DIR/fake-deps"
    for header in         cups/cups.h fontconfig/fontconfig.h         X11/Xlib.h X11/Xutil.h X11/Intrinsic.h         X11/extensions/Xrandr.h X11/extensions/XTest.h         X11/extensions/Xrender.h X11/extensions/shape.h; do
        mkdir -p "$FAKE_DEPS/include/$(dirname "$header")"
        printf '/* Configure-only sentinel; this component is not linked into the Android graph runtime. */\n'             > "$FAKE_DEPS/include/$header"
    done

    CONF=android-jni28
    X11_LIB_DIR="${X11_LIB_DIR:-/usr/lib/x86_64-linux-gnu}"
    if [[ ! -f "$JDK_SOURCE/build/$CONF/spec.gmk" ]]; then
        (
            cd "$JDK_SOURCE"
            CC="$CLANG"             CXX="$CLANGXX"             AR="$LLVM_AR"             NM="$LLVM_NM"             STRIP="$LLVM_STRIP"             OBJCOPY="$LLVM_OBJCOPY"             OBJDUMP="$LLVM_OBJDUMP"             READELF="$LLVM_READELF"             bash configure                 --openjdk-target=aarch64-linux-android                 --with-toolchain-type=clang                 --with-conf-name="$CONF"                 --with-boot-jdk="$GRAALVM_HOME"                 --with-debug-level=release                 --with-native-debug-symbols=none                 --disable-warnings-as-errors                 --with-jvm-variants=server                 --enable-headless-only                 --with-alsa=/nonexistent/unused                 --with-cups="$FAKE_DEPS"                 --with-fontconfig="$FAKE_DEPS"                 --with-freetype=bundled                 --x-includes="$FAKE_DEPS/include"                 --x-libraries="$X11_LIB_DIR"                 BUILD_CC="${BUILD_CC:-/usr/bin/clang}"                 BUILD_CXX="${BUILD_CXX:-/usr/bin/clang++}"
        )
    fi

    BOOT_JDK_LIBRARY_PATH="${BOOT_JDK_LIBRARY_PATH:-${LIBRARY_PATH:-}}"
    env LIBRARY_PATH="$BOOT_JDK_LIBRARY_PATH"         make -C "$JDK_SOURCE" CONF="$CONF" JOBS="$JOBS" java.base-copy-only
    # Static JNI objects include javac-generated headers. Keep dependencies enabled so
    # the host-side langtools/depend generators are built, without requesting a JDK image.
    env LIBRARY_PATH="$BOOT_JDK_LIBRARY_PATH"         make -C "$JDK_SOURCE" CONF="$CONF" JOBS="$JOBS" java.base-java java.prefs-java jdk.net-java

    # OpenJDK's module target stops after java.base when its unused static-archive
    # symbol aggregation trips over the host nm implementation. Invoke each module
    # separately so a known post-object failure in java.base cannot prevent the
    # java.prefs and jdk.net JNI objects from being compiled.
    for jdk_module_target in java.base-libs-only java.prefs-libs-only jdk.net-libs-only; do
        JDK_BUILD_LOG="$WORK_DIR/openjdk-static-${jdk_module_target}.log"
        set +e
        env LIBRARY_PATH="$BOOT_JDK_LIBRARY_PATH" \
            make -C "$JDK_SOURCE" CONF="$CONF" JOBS="$JOBS" \
            STATIC_BUILD=true STATIC_LIBS=true \
            "$jdk_module_target" >"$JDK_BUILD_LOG" 2>&1
        JDK_BUILD_STATUS=$?
        set -e
        # The requested JNI objects are compiled before OpenJDK attempts to
        # aggregate symbol files that static Native Image linking never consumes.
        # Accept only known post-object failures; exact object counts and required
        # JNI symbols are validated below before anything is linked.
        if [[ $JDK_BUILD_STATUS -ne 0 ]] && \
           ! grep -Eq 'libsyslookup|invalid option.*m|no symbols|modules_libs/(java\.base/(server/)?lib(jli|java|net|nio|verify|zip|jimage|jvm)|java\.prefs/libprefs|jdk\.net/libextnet)\.symbols: No such file or directory' "$JDK_BUILD_LOG"; then
            tail -n 120 "$JDK_BUILD_LOG" >&2
            fail "OpenJDK native-library compilation failed for $jdk_module_target"
        fi
        if [[ $JDK_BUILD_STATUS -ne 0 ]]; then
            echo "OpenJDK $jdk_module_target objects completed; ignoring the known unused symbol-aggregation failure"
        fi
    done

    JDK_OBJECT_ROOT="$JDK_SOURCE/build/$CONF/support/native"
    declare -A EXPECTED_COUNTS=(
        [libjava]=67
        [libnet]=13
        [libnio]=23
        [libzip]=5
        [libprefs]=1
        [libextnet]=1
    )
    declare -A LIBRARY_MODULES=(
        [libjava]=java.base
        [libnet]=java.base
        [libnio]=java.base
        [libzip]=java.base
        [libprefs]=java.prefs
        [libextnet]=jdk.net
    )
    mkdir -p "$JDK_LIB_DIR"
    for library in libjava libnet libnio libzip libprefs libextnet; do
        object_dir="$JDK_OBJECT_ROOT/${LIBRARY_MODULES[$library]}/$library/static"
        objects=("$object_dir"/*.o)
        [[ -e "${objects[0]}" ]] || fail "No static objects produced for $library"
        if [[ ${#objects[@]} -ne ${EXPECTED_COUNTS[$library]} ]]; then
            fail "Expected ${EXPECTED_COUNTS[$library]} $library objects, found ${#objects[@]}"
        fi
        "$LLVM_AR" rcsD "$JDK_LIB_DIR/$library.a" "${objects[@]}"
    done
fi

assert_aarch64_archive() {
    local archive="$1"
    local archive_name
    local inspect_dir
    local member
    local member_header
    archive_name="$(basename "$archive")"
    inspect_dir="$WORK_DIR/archive-inspect/${archive_name%.a}"
    rm -rf "$inspect_dir"
    mkdir -p "$inspect_dir"
    (
        cd "$inspect_dir"
        "$LLVM_AR" x "$archive"
    )
    shopt -s nullglob
    local members=("$inspect_dir"/*.o)
    shopt -u nullglob
    [[ ${#members[@]} -gt 0 ]] || fail "OpenJDK archive has no object members: $archive"
    for member in "${members[@]}"; do
        member_header="$member.elf-header"
        "$LLVM_READELF" -h "$member" > "$member_header"
        grep -q 'Machine:.*AArch64' "$member_header" ||
            fail "OpenJDK archive member is not AArch64: $archive($(basename "$member"))"
        rm -f "$member_header"
    done
    rm -rf "$inspect_dir"
}

for archive in libjava.a libnet.a libnio.a libzip.a libprefs.a libextnet.a; do
    [[ -s "$JDK_LIB_DIR/$archive" ]] || fail "Missing OpenJDK archive: $JDK_LIB_DIR/$archive"
    assert_aarch64_archive "$JDK_LIB_DIR/$archive"
done
assert_unix_file_attributes_abi "$JDK_LIB_DIR/libnio.a"
for onload in java net nio zip prefs extnet; do
    ONLOAD_SYMBOLS="$WORK_DIR/JNI_OnLoad_$onload.symbols"
    "$LLVM_NM" --defined-only "$JDK_LIB_DIR/lib$onload.a" > "$ONLOAD_SYMBOLS"
    grep -q "JNI_OnLoad_$onload" "$ONLOAD_SYMBOLS" || \
        fail "Static JNI entry point missing: JNI_OnLoad_$onload"
done

require_static_symbol() {
    local archive="$1"
    local symbol="$2"
    local symbols_file="$WORK_DIR/$(basename "$archive").required.symbols"
    "$LLVM_NM" --defined-only "$archive" > "$symbols_file"
    grep -q "[[:space:]]$symbol$" "$symbols_file" ||
        fail "Static JNI entry point missing from $(basename "$archive"): $symbol"
}
require_static_symbol "$JDK_LIB_DIR/libnio.a" Java_sun_nio_ch_Net_shouldShutdownWriteBeforeClose0
require_static_symbol "$JDK_LIB_DIR/libnio.a" Java_sun_nio_fs_UnixNativeDispatcher_exists0
require_static_symbol "$JDK_LIB_DIR/libprefs.a" Java_java_util_prefs_FileSystemPreferences_lockFile0
require_static_symbol "$JDK_LIB_DIR/libextnet.a" Java_jdk_net_LinuxSocketOptions_setQuickAck0

JDK_SUPPORT_RECEIPT="$JDK_LIB_DIR/jdk-support-receipt"
NET_PATCH_SHA256="$(sha256sum "$PATCH_FILE" | awk '{print $1}')"
PRODUCER_SHA256="$(sha256sum "${BASH_SOURCE[0]}" | awk '{print $1}')"
NDK_SOURCE_PROPERTIES_SHA256="$(sha256sum "$ANDROID_NDK/source.properties" | awk '{print $1}')"
GRAALVM_VERSION_SHA256="$(printf '%s' "$NATIVE_IMAGE_VERSION" | sha256sum | awk '{print $1}')"
if [[ -z "$REUSE_JDK_LIBS" ]]; then
    JDK_SOURCE_PATCHED_SHA256="$(git -C "$JDK_SOURCE" diff --binary | sha256sum | awk '{print $1}')"
    {
        printf '%s\n' \
            'format=1' \
            'stage=android-jdk-support' \
            "java_version=$EXPECTED_JDK_SOURCE_VERSION" \
            "labsjdk_source_url=$LABSJDK_URL" \
            "labsjdk_source_ref=$LABSJDK_REF" \
            "labsjdk_source_commit=$LABSJDK_COMMIT" \
            "labsjdk_source_patched_sha256=$JDK_SOURCE_PATCHED_SHA256" \
            "net_patch_sha256=$NET_PATCH_SHA256" \
            "unix_file_attributes_patch_sha256=$UNIX_FILE_ATTRIBUTES_PATCH_SHA256" \
            "shutdown_write_patch_sha256=$SHUTDOWN_WRITE_PATCH_SHA256" \
            "producer_sha256=$PRODUCER_SHA256" \
            "android_ndk_revision=$NDK_REVISION" \
            "android_ndk_source_properties_sha256=$NDK_SOURCE_PROPERTIES_SHA256" \
            "graalvm_version_sha256=$GRAALVM_VERSION_SHA256"
        for archive in libjava.a libnet.a libnio.a libzip.a libprefs.a libextnet.a; do
            printf '%s_sha256=%s\n' "${archive%.a}" "$(sha256sum "$JDK_LIB_DIR/$archive" | awk '{print $1}')"
        done
    } > "$JDK_SUPPORT_RECEIPT"
else
    [[ -s "$JDK_SUPPORT_RECEIPT" ]] ||
        fail "Reused JDK support closure has no receipt: $JDK_SUPPORT_RECEIPT"
    for expected in \
        "format=1" \
        "stage=android-jdk-support" \
        "java_version=$EXPECTED_JDK_SOURCE_VERSION" \
        "labsjdk_source_url=$LABSJDK_URL" \
        "labsjdk_source_ref=$LABSJDK_REF" \
        "labsjdk_source_commit=$LABSJDK_COMMIT" \
        "net_patch_sha256=$NET_PATCH_SHA256" \
        "unix_file_attributes_patch_sha256=$UNIX_FILE_ATTRIBUTES_PATCH_SHA256" \
        "shutdown_write_patch_sha256=$SHUTDOWN_WRITE_PATCH_SHA256" \
        "android_ndk_revision=$NDK_REVISION" \
        "android_ndk_source_properties_sha256=$NDK_SOURCE_PROPERTIES_SHA256" \
        "graalvm_version_sha256=$GRAALVM_VERSION_SHA256"; do
        [[ "$(grep -Fxc "$expected" "$JDK_SUPPORT_RECEIPT")" -eq 1 ]] ||
            fail "Reused JDK support receipt is missing or duplicates: $expected"
    done
    # This script also owns Native Image/relink behavior. Its whole-file hash is
    # provenance only: unrelated edits must not invalidate the independently
    # pinned and byte-verified JDK support archive closure.
    [[ "$(grep -Ec '^producer_sha256=[0-9a-f]{64}$' "$JDK_SUPPORT_RECEIPT")" -eq 1 ]] ||
        fail "Reused JDK support receipt has invalid producer provenance"
    for archive in libjava.a libnet.a libnio.a libzip.a libprefs.a libextnet.a; do
        expected="${archive%.a}_sha256=$(sha256sum "$JDK_LIB_DIR/$archive" | awk '{print $1}')"
        [[ "$(grep -Fxc "$expected" "$JDK_SUPPORT_RECEIPT")" -eq 1 ]] ||
            fail "Reused JDK support receipt does not bind $archive"
    done
fi

if [[ "$SUPPORT_LIBRARIES_ONLY" == true ]]; then
    echo "Verified Android JDK support closure: $JDK_LIB_DIR"
    exit 0
fi

GRAPH_OBJECT="$REUSE_OBJECT"
GENERATED_DIR="$WORK_DIR/generated"
if [[ -z "$GRAPH_OBJECT" ]]; then
    if [[ -n "$CLASSES_DIR" || -n "$CLASSPATH_FILE" ]]; then
        [[ -n "$CLASSES_DIR" && -n "$CLASSPATH_FILE" ]] || \
            fail "--classes-dir and --classpath-file must be supplied together"
        [[ -d "$CLASSES_DIR" ]] || fail "Compiled classes directory not found: $CLASSES_DIR"
        [[ -s "$CLASSPATH_FILE" ]] || fail "Runtime dependency classpath not found: $CLASSPATH_FILE"
        GRAPH_CLASSPATH="$CLASSES_DIR:$(<"$CLASSPATH_FILE")"
    else
        MAVEN_FLAGS=(-q -DskipTests)
        [[ "$OFFLINE" == false ]] || MAVEN_FLAGS+=(-o)
        "$MAVEN" "${MAVEN_FLAGS[@]}" -f "$MODULE_DIR/pom.xml" package \
            dependency:build-classpath \
            -Dmdep.includeScope=runtime \
            -Dmdep.outputFile="$WORK_DIR/dependency-classpath.txt"
        [[ -s "$WORK_DIR/dependency-classpath.txt" ]] || fail "Maven dependency classpath was not generated"
        GRAPH_CLASSPATH="$MODULE_DIR/target/classes:$(<"$WORK_DIR/dependency-classpath.txt")"
    fi

    STAGED_GRAAL="$WORK_DIR/graalvm-android"
    if [[ ! -x "$STAGED_GRAAL/bin/native-image" ]]; then
        mkdir -p "$STAGED_GRAAL"
        cp -as "$GRAALVM_HOME"/. "$STAGED_GRAAL"/
        rm -f "$STAGED_GRAAL/bin/native-image"
        cp -L "$GRAALVM_HOME/bin/native-image" "$STAGED_GRAAL/bin/native-image"
        chmod +x "$STAGED_GRAAL/bin/native-image"
    fi

    # Native Image defers target-library enumeration until after whole-program
    # analysis. A symlink-farm clibraries tree can therefore leave that late
    # phase observing a missing/broken linux-aarch64/bionic path even though
    # initialization succeeded. Materialize the small target-library closure
    # and the two verified Android archives inside this invocation's private
    # GraalVM tree. This is deterministic, independent of the source support
    # directory's lifetime, and never mutates the installed GraalVM.
    STAGED_CLIB_ROOT="$STAGED_GRAAL/lib/svm/clibraries"
    rm -rf -- "$STAGED_CLIB_ROOT"
    mkdir -p -- "$(dirname -- "$STAGED_CLIB_ROOT")"
    cp -a -- "$GRAALVM_HOME/lib/svm/clibraries" "$STAGED_CLIB_ROOT"
    STAGED_CLIB="$STAGED_CLIB_ROOT/linux-aarch64/bionic"
    mkdir -p -- "$STAGED_CLIB"
    for archive in libjvm.a liblibchelper.a; do
        cp --reflink=auto -- "$SVM_LIB_DIR/$archive" "$STAGED_CLIB/$archive"
        [[ "$(stat -c '%d:%i' "$SVM_LIB_DIR/$archive")" != "$(stat -c '%d:%i' "$STAGED_CLIB/$archive")" ]] ||
            fail "staged SVM archive was hard-linked: $archive"
        [[ "$(sha256sum "$SVM_LIB_DIR/$archive" | awk '{print $1}')" == "$(sha256sum "$STAGED_CLIB/$archive" | awk '{print $1}')" ]] ||
            fail "staged SVM archive changed while copying: $archive"
    done
    chmod -R a-w -- "$STAGED_CLIB_ROOT"
    [[ -d "$STAGED_CLIB" && ! -L "$STAGED_CLIB" ]] ||
        fail "Android Native Image target library directory is not materialized: $STAGED_CLIB"

    COMPILER_WRAPPER="$WORK_DIR/ndk-compiler/gcc"
    mkdir -p "$(dirname "$COMPILER_WRAPPER")"
    # GraalVM's toolchain detector intentionally sanitizes child environments.
    # Bake the Maven-selected toolchain into this target-local wrapper so both
    # detection and compilation use the same validated NDK/API deterministically.
    {
        printf '#!/usr/bin/env bash\n'
        printf 'ANDROID_NDK=%q\n' "$ANDROID_NDK"
        printf 'ANDROID_API=%q\n' "$ANDROID_API"
        printf 'NDK_HOST_TAG=%q\n' "$NDK_HOST_TAG"
        printf 'QEMU_AARCH64=%q\n' "${QEMU_AARCH64:-qemu-aarch64-static}"
        tail -n +2 "$ANDROID_SUPPORT/ndk-compiler-wrapper.sh"
    } > "$COMPILER_WRAPPER"
    chmod +x "$COMPILER_WRAPPER"
    ln -sfn gcc "$(dirname "$COMPILER_WRAPPER")/cc"

    CAP_CACHE="$WORK_DIR/capcache"
    if [[ ! -s "$CAP_CACHE/AArch64LibCHelperDirectives.cap" ]]; then
        command -v "${QEMU_AARCH64:-qemu-aarch64-static}" >/dev/null 2>&1 ||             fail "qemu-aarch64-static is required to generate the bionic CAP cache"
        mkdir -p "$CAP_CACHE"
        ANDROID_NDK="$ANDROID_NDK" ANDROID_API="$ANDROID_API"         QEMU_AARCH64="${QEMU_AARCH64:-qemu-aarch64-static}"         "$GRAALVM_HOME/bin/native-image"             -H:+UnlockExperimentalVMOptions             --shared --no-fallback             -H:Name="$WORK_DIR/cap-probe"             -H:CCompilerPath="$COMPILER_WRAPPER"             -H:+NewCAPCache -H:+UseCAPCache -H:+QueryIfNotInCAPCache             -H:+ExitAfterCAPCache             -H:CAPCacheDir="$CAP_CACHE"             -H:-SpawnIsolates             -Dsvm.targetArch=aarch64             -H:TargetPlatform=linux-aarch64             --libc=bionic             -H:CompilerBackend=lir             -cp "$GRAPH_CLASSPATH"
    fi

    IMAGE_CAP_CACHE="$WORK_DIR/image-capcache"
    rm -rf "$IMAGE_CAP_CACHE"
    cp -a "$CAP_CACHE" "$IMAGE_CAP_CACHE"
    IMAGE_TMP="$WORK_DIR/native-image-tmp"
    rm -rf "$IMAGE_TMP" "$GENERATED_DIR"
    mkdir -p "$IMAGE_TMP" "$GENERATED_DIR"

    classpath_completeness_args=(--allow-incomplete-classpath)
    [[ "$STRICT_CLASSPATH" == false ]] || classpath_completeness_args=()

    ANDROID_NDK="$ANDROID_NDK" ANDROID_API="$ANDROID_API"     QEMU_AARCH64="${QEMU_AARCH64:-qemu-aarch64-static}"     "$STAGED_GRAAL/bin/native-image"         -H:+UnlockExperimentalVMOptions         --shared --no-fallback         -H:Name="$GENERATED_DIR/libkompile_reasoning_android"         -H:CCompilerPath="$COMPILER_WRAPPER"         -H:CLibraryPath="$SVM_LIB_DIR"         -H:+AddAllCharsets         -H:+ReportExceptionStackTraces         -H:+RemoveSaturatedTypeFlows         -H:+ExitAfterRelocatableImageWrite         -H:TempDirectory="$IMAGE_TMP"         -H:IncludeResources=META-INF/services/.*         -H:IncludeResources=META-INF/native-image/.*         --initialize-at-build-time=org.slf4j         "${classpath_completeness_args[@]}"         -H:-SpawnIsolates         -Dsvm.targetArch=aarch64         -H:TargetPlatform=linux-aarch64         -H:+ForceNoROSectionRelocations         --libc=bionic         -H:+UseCAPCache         -H:CAPCacheDir="$IMAGE_CAP_CACHE"         -H:CompilerBackend=lir         -cp "$GRAPH_CLASSPATH"

    shopt -s nullglob
    GRAPH_OBJECTS=("$IMAGE_TMP"/SVM-*/libkompile_reasoning_android.o)
    shopt -u nullglob
    [[ ${#GRAPH_OBJECTS[@]} -eq 1 ]] || fail "Expected one Graal relocatable image, found ${#GRAPH_OBJECTS[@]}"
    GRAPH_OBJECT="${GRAPH_OBJECTS[0]}"
fi

[[ -s "$GRAPH_OBJECT" ]] || fail "Graal graph object not found: $GRAPH_OBJECT"
GRAPH_OBJECT_HEADER="$WORK_DIR/graph-object.elf-header"
"$LLVM_READELF" -h "$GRAPH_OBJECT" >"$GRAPH_OBJECT_HEADER"
grep -q 'Machine:.*AArch64' "$GRAPH_OBJECT_HEADER" ||
    fail "Graal graph object is not AArch64: $GRAPH_OBJECT"

if [[ -n "$OBJECT_OUTPUT" ]]; then
    mkdir -p "$(dirname "$OBJECT_OUTPUT")"
    OBJECT_OUTPUT_TMP="$OBJECT_OUTPUT.tmp.$$"
    cp "$GRAPH_OBJECT" "$OBJECT_OUTPUT_TMP"
    [[ "$(sha256sum "$OBJECT_OUTPUT_TMP" | awk '{print $1}')" == "$(sha256sum "$GRAPH_OBJECT" | awk '{print $1}')" ]] ||
        fail "Relocatable object changed during publication: $OBJECT_OUTPUT"
    mv -f "$OBJECT_OUTPUT_TMP" "$OBJECT_OUTPUT"
    echo "Verified Android AArch64 relocatable object: $OBJECT_OUTPUT"
    exit 0
fi

UNSTRIPPED="$WORK_DIR/libkompile_reasoning_android.unstripped.so"
FINAL_LIBRARY="$OUTPUT_DIR/jni/arm64-v8a/libkompile_reasoning_android.so"
"$CLANG" -shared     -o "$UNSTRIPPED"     "$GRAPH_OBJECT"     -Wl,--start-group         "$SVM_LIB_DIR/libjvm.a"         "$SVM_LIB_DIR/liblibchelper.a"         "$JDK_LIB_DIR/libjava.a"         "$JDK_LIB_DIR/libnet.a"         "$JDK_LIB_DIR/libnio.a"         "$JDK_LIB_DIR/libzip.a"         "$JDK_LIB_DIR/libprefs.a"         "$JDK_LIB_DIR/libextnet.a"     -Wl,--end-group     -ldl -lz -lm -llog     -Wl,--no-undefined     -Wl,--gc-sections     -Wl,--build-id=sha1     -Wl,-z,relro,-z,now     -Wl,-z,max-page-size=16384     -Wl,-z,common-page-size=16384     -Wl,-soname,libkompile_reasoning_android.so     -Wl,--version-script="$ANDROID_SUPPORT/kgr_android_exports.map"

cp "$UNSTRIPPED" "$FINAL_LIBRARY"
"$LLVM_STRIP" --strip-unneeded "$FINAL_LIBRARY"
assert_unix_file_attributes_abi "$FINAL_LIBRARY"

cp "$MODULE_DIR/include/kompile_reasoning.h" "$OUTPUT_DIR/include/kompile_reasoning.h"
if [[ -f "$GENERATED_DIR/libkompile_reasoning_android.h" ]]; then
    cp "$GENERATED_DIR/libkompile_reasoning_android.h" "$OUTPUT_DIR/include/"
fi
if [[ -f "$GENERATED_DIR/graal_isolate.h" ]]; then
    cp "$GENERATED_DIR/graal_isolate.h" "$OUTPUT_DIR/include/"
fi

cat > "$OUTPUT_DIR/metadata/build.properties" <<PROPERTIES
abi.version=1
android.abi=arm64-v8a
android.api=$ANDROID_API
android.ndk=$NDK_REVISION
graalvm.java=$EXPECTED_GRAAL_JAVA
native.image=$EXPECTED_NATIVE_IMAGE
graal.source.commit=$GRAAL_COMMIT
labsjdk.source.commit=$LABSJDK_COMMIT
labsjdk.source.version=$EXPECTED_JDK_SOURCE_VERSION
labsjdk.unixFileAttributes.required=$EXPECTED_UNIX_FILE_ATTRIBUTES_FIELD
labsjdk.unixFileAttributes.forbidden=$FORBIDDEN_UNIX_FILE_ATTRIBUTES_FIELD
labsjdk.unixFileAttributes.patch.sha256=$LABSJDK_UNIX_FILE_ATTRIBUTES_PATCH_SHA256
labsjdk.shutdownWrite.patch.sha256=$LABSJDK_SHUTDOWN_WRITE_PATCH_SHA256
jdk.support.receipt.sha256=$(sha256sum "$JDK_SUPPORT_RECEIPT" | awk '{print $1}')
graph.object.sha256=$(sha256sum "$GRAPH_OBJECT" | awk '{print $1}')
library.sha256=$(sha256sum "$FINAL_LIBRARY" | awk '{print $1}')
openblas=false
gluon=false
PROPERTIES

"$MODULE_DIR/verify-android-ndk.sh"     --library "$FINAL_LIBRARY"     --android-ndk "$ANDROID_NDK"

echo "Android graph AOT SDK: $OUTPUT_DIR"
