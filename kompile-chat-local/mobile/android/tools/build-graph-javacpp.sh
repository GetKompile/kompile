#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$ANDROID_ROOT/../../.." && pwd)"
GRAPH_MODULE="$REPO_ROOT/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local"
ANDROID_NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
ANDROID_API=28
JAVACPP_VERSION=1.5.13
JAVACPP_JAR=""
GRAPH_LIBRARY=""
GRAPH_HEADER=""
GRAPH_VERIFIER=""
WORK_DIR="$ANDROID_ROOT/build/graph-javacpp"
OUTPUT_DIR="$ANDROID_ROOT/app/src/main/jniLibs/arm64-v8a"

usage() {
    printf '%s\n' \
        "Build the Android ARM64 JavaCPP transport for the Kompile graph AOT ABI." \
        "" \
        "Usage: build-graph-javacpp.sh [options]" \
        "  --android-ndk <path>    Android NDK r28b root (required)" \
        "  --android-api <level>   Android API level (default: 28, minimum: 28)" \
        "  --graph-module <path>   Graph AOT module root" \
        "  --graph-library <path>  Prebuilt libkompile_reasoning_android.so" \
        "  --graph-header <path>   Packaged kompile_reasoning.h" \
        "  --graph-verifier <path> Packaged verify-android-ndk.sh" \
        "  --javacpp-jar <path>    JavaCPP ${JAVACPP_VERSION} build JAR" \
        "  --work-dir <path>       Generated-source/build directory" \
        "  --output-dir <path>     arm64-v8a jniLibs output directory"
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --android-ndk)
            ANDROID_NDK="$2"
            shift 2
            ;;
        --android-api)
            ANDROID_API="$2"
            shift 2
            ;;
        --graph-module)
            GRAPH_MODULE="$2"
            shift 2
            ;;
        --graph-library)
            GRAPH_LIBRARY="$2"
            shift 2
            ;;
        --graph-header)
            GRAPH_HEADER="$2"
            shift 2
            ;;
        --graph-verifier)
            GRAPH_VERIFIER="$2"
            shift 2
            ;;
        --javacpp-jar)
            JAVACPP_JAR="$2"
            shift 2
            ;;
        --work-dir)
            WORK_DIR="$2"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
done

[[ -n "$ANDROID_NDK" ]] || fail "--android-ndk is required"
[[ -d "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64" ]] ||
    fail "Unsupported or incomplete Android NDK: $ANDROID_NDK"
[[ "$(basename "$ANDROID_NDK")" == "28.1.13356709" ]] ||
    fail "Android NDK r28b (28.1.13356709) is required"
[[ "$ANDROID_API" =~ ^[0-9]+$ && "$ANDROID_API" -ge 28 ]] ||
    fail "Android API must be an integer >= 28"

if [[ -z "$GRAPH_HEADER" ]]; then
    GRAPH_HEADER="$GRAPH_MODULE/include/kompile_reasoning.h"
fi
if [[ -z "$GRAPH_LIBRARY" ]]; then
    GRAPH_LIBRARY="$GRAPH_MODULE/target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so"
fi
if [[ -z "$GRAPH_VERIFIER" ]]; then
    GRAPH_VERIFIER="$GRAPH_MODULE/verify-android-ndk.sh"
fi
[[ -f "$GRAPH_HEADER" ]] || fail "Graph C header is missing: $GRAPH_HEADER"
[[ -f "$GRAPH_LIBRARY" ]] || fail "Graph Android AOT library is missing: $GRAPH_LIBRARY"
[[ -f "$GRAPH_VERIFIER" ]] || fail "Graph Android verifier is missing: $GRAPH_VERIFIER"

if [[ -z "$JAVACPP_JAR" ]]; then
    LOCAL_REPOSITORY="${MAVEN_REPO_LOCAL:-${HOME:?HOME is required to locate Maven local}/.m2/repository}"
    JAVACPP_JAR="$LOCAL_REPOSITORY/org/bytedeco/javacpp/$JAVACPP_VERSION/javacpp-$JAVACPP_VERSION.jar"
fi
[[ -f "$JAVACPP_JAR" ]] || fail "JavaCPP $JAVACPP_VERSION JAR is missing: $JAVACPP_JAR"
[[ "$(basename "$JAVACPP_JAR")" == "javacpp-$JAVACPP_VERSION.jar" ]] ||
    fail "Expected JavaCPP $JAVACPP_VERSION, got: $JAVACPP_JAR"

BINDING_SOURCE="$ANDROID_ROOT/app/src/main/java/ai/kompile/chat/local/android/graph/KompileGraphNative.java"
[[ -f "$BINDING_SOURCE" ]] || fail "JavaCPP graph binding source is missing: $BINDING_SOURCE"

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64"
CLANGXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
LLVM_READELF="$TOOLCHAIN/bin/llvm-readelf"
LLVM_NM="$TOOLCHAIN/bin/llvm-nm"
LLVM_STRIP="$TOOLCHAIN/bin/llvm-strip"
[[ -x "$CLANGXX" ]] || fail "NDK compiler is missing: $CLANGXX"

CLASSES_DIR="$WORK_DIR/classes"
GENERATED_DIR="$WORK_DIR/generated"
NATIVE_DIR="$WORK_DIR/native"
rm -rf "$CLASSES_DIR" "$GENERATED_DIR" "$NATIVE_DIR"
mkdir -p "$CLASSES_DIR" "$GENERATED_DIR" "$NATIVE_DIR" "$OUTPUT_DIR"

javac -encoding UTF-8 -source 17 -target 17 \
    -cp "$JAVACPP_JAR" \
    -d "$CLASSES_DIR" \
    "$BINDING_SOURCE"

java \
    -Dorg.bytedeco.javacpp.platform=android-arm64 \
    -cp "$JAVACPP_JAR:$CLASSES_DIR" \
    org.bytedeco.javacpp.tools.Builder \
    -classpath "$CLASSES_DIR:$JAVACPP_JAR" \
    -d "$GENERATED_DIR" \
    -o jnikompile_graph \
    -nocompile \
    ai.kompile.chat.local.android.graph.KompileGraphNative

JAVACPP_RUNTIME_CPP="$GENERATED_DIR/jnijavacpp.cpp"
GENERATED_CPP="$GENERATED_DIR/jnikompile_graph.cpp"
[[ -f "$JAVACPP_RUNTIME_CPP" ]] ||
    fail "JavaCPP did not generate $JAVACPP_RUNTIME_CPP"
[[ -f "$GENERATED_CPP" ]] || fail "JavaCPP did not generate $GENERATED_CPP"

WRAPPER_UNSTRIPPED="$NATIVE_DIR/libjnikompile_graph.unstripped.so"
WRAPPER_LIBRARY="$NATIVE_DIR/libjnikompile_graph.so"
"$CLANGXX" \
    -std=gnu++17 \
    -O2 \
    -fPIC \
    -fvisibility=hidden \
    -fstack-protector-strong \
    -D_FORTIFY_SOURCE=2 \
    -I"$(dirname "$GRAPH_HEADER")" \
    -shared \
    -static-libstdc++ \
    -Wl,--no-undefined \
    -Wl,--as-needed \
    -Wl,-soname,libjnikompile_graph.so \
    -Wl,-z,relro \
    -Wl,-z,now \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384 \
    "$JAVACPP_RUNTIME_CPP" \
    "$GENERATED_CPP" \
    -L"$(dirname "$GRAPH_LIBRARY")" \
    -lkompile_reasoning_android \
    -llog \
    -ldl \
    -o "$WRAPPER_UNSTRIPPED"
"$LLVM_STRIP" --strip-unneeded "$WRAPPER_UNSTRIPPED" -o "$WRAPPER_LIBRARY"

bash "$GRAPH_VERIFIER" \
    --library "$GRAPH_LIBRARY" \
    --android-ndk "$ANDROID_NDK"

mapfile -t wrapper_needed < <(
    "$LLVM_READELF" -d "$WRAPPER_LIBRARY" |
        awk '/NEEDED/ {gsub(/[][]/, "", $5); print $5}' |
        LC_ALL=C sort -u
)
allowed_needed=(
    libc.so
    libdl.so
    libkompile_reasoning_android.so
    liblog.so
    libm.so
)
for needed in "${wrapper_needed[@]}"; do
    allowed=false
    for candidate in "${allowed_needed[@]}"; do
        if [[ "$needed" == "$candidate" ]]; then
            allowed=true
            break
        fi
    done
    [[ "$allowed" == true ]] || fail "Forbidden JavaCPP wrapper dependency: $needed"
done
printf '%s\n' "${wrapper_needed[@]}" |
    grep -qx 'libkompile_reasoning_android.so' ||
    fail "JavaCPP wrapper does not link the graph AOT library"

wrapper_elf_header="$("$LLVM_READELF" -h "$WRAPPER_LIBRARY")"
wrapper_dynamic_section="$("$LLVM_READELF" -d "$WRAPPER_LIBRARY")"
wrapper_program_headers="$("$LLVM_READELF" -l "$WRAPPER_LIBRARY")"
wrapper_dynamic_symbols="$("$LLVM_NM" -D "$WRAPPER_LIBRARY")"

grep -q 'Machine:.*AArch64' <<< "$wrapper_elf_header" ||
    fail "JavaCPP wrapper is not AArch64"
grep -q 'BIND_NOW' <<< "$wrapper_dynamic_section" ||
    fail "JavaCPP wrapper is not linked with BIND_NOW"
grep -q 'GNU_RELRO' <<< "$wrapper_program_headers" ||
    fail "JavaCPP wrapper is not linked with RELRO"
if grep -Eq 'RPATH|RUNPATH|TEXTREL' <<< "$wrapper_dynamic_section"; then
    fail "JavaCPP wrapper contains RPATH, RUNPATH, or TEXTREL"
fi
if grep -Eiq 'openblas|gfortran|mkl|libnd4j|sdx_cpu' <<< "$wrapper_dynamic_symbols"; then
    fail "JavaCPP wrapper contains a forbidden CPU/BLAS symbol"
fi

install -m 0755 "$WRAPPER_LIBRARY" "$OUTPUT_DIR/libjnikompile_graph.so"
install -m 0755 "$GRAPH_LIBRARY" "$OUTPUT_DIR/libkompile_reasoning_android.so"

printf '%s\n' \
    "Android graph JavaCPP transport built" \
    "  wrapper: $OUTPUT_DIR/libjnikompile_graph.so" \
    "  graph:   $OUTPUT_DIR/libkompile_reasoning_android.so"
sha256sum \
    "$OUTPUT_DIR/libjnikompile_graph.so" \
    "$OUTPUT_DIR/libkompile_reasoning_android.so"
