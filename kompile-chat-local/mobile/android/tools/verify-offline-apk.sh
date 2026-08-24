#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
fail(){ printf 'verify-offline-apk: %s\n' "$*" >&2; exit 1; }
need(){ [[ $# -ge 2 && -n $2 ]] || fail "missing value for $1"; }
APK= VARIANT= CONFIG= ANDROID_SDK= ANDROID_NDK= RUNTIME_AAR_OVERRIDE=
SDX_SDK= EXPECTED_BUILD_ID= EXPECTED_VERSION_CODE=
EXPECTED_SOURCE_RUNTIME_AAR_SHA256= EXPECTED_RUNTIME_PROVENANCE_SHA256=
EXPECTED_SDX_AOT_PROVENANCE_SHA256=
while [[ $# -gt 0 ]]; do case "$1" in
 --apk) need "$@"; APK=$2; shift 2;; --variant) need "$@"; VARIANT=$2; shift 2;;
 --config) need "$@"; CONFIG=$2; shift 2;; --android-sdk) need "$@"; ANDROID_SDK=$2; shift 2;;
 --android-ndk) need "$@"; ANDROID_NDK=$2; shift 2;;
 --runtime-aar) need "$@"; RUNTIME_AAR_OVERRIDE=$2; shift 2;;
 --sdx-sdk) need "$@"; SDX_SDK=$2; shift 2;;
 --expected-build-id) need "$@"; EXPECTED_BUILD_ID=$2; shift 2;;
 --expected-version-code) need "$@"; EXPECTED_VERSION_CODE=$2; shift 2;;
 --expected-source-runtime-aar-sha256) need "$@"; EXPECTED_SOURCE_RUNTIME_AAR_SHA256=$2; shift 2;;
 --expected-runtime-provenance-sha256) need "$@"; EXPECTED_RUNTIME_PROVENANCE_SHA256=$2; shift 2;;
 --expected-sdx-aot-provenance-sha256) need "$@"; EXPECTED_SDX_AOT_PROVENANCE_SHA256=$2; shift 2;;
 -h|--help) echo "Usage: verify-offline-apk.sh --apk FILE --variant NAME --config FILE --android-sdk DIR --android-ndk DIR --sdx-sdk DIR --expected-build-id ID --expected-version-code N [--runtime-aar FILE] [--expected-source-runtime-aar-sha256 SHA256 --expected-runtime-provenance-sha256 SHA256 --expected-sdx-aot-provenance-sha256 SHA256]"; exit 0;;
 *) fail "unknown argument: $1";; esac; done
[[ -n $APK && -n $VARIANT && -n $CONFIG && -n $ANDROID_SDK && -n $ANDROID_NDK && -n $SDX_SDK && -n $EXPECTED_BUILD_ID && -n $EXPECTED_VERSION_CODE ]] ||
  fail "apk, variant, config, Android SDK/NDK, SDX SDK, build ID, and version code are required"
[[ $VARIANT =~ ^(vulkan|hexagon|tensorG3|tensorG5)$ ]] || fail "unsupported APK variant: $VARIANT"
[[ $EXPECTED_BUILD_ID =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || fail "invalid expected build ID"
[[ $EXPECTED_VERSION_CODE =~ ^[0-9]+$ ]] && (( EXPECTED_VERSION_CODE >= 1 && EXPECTED_VERSION_CODE <= 2100000000 )) ||
  fail "invalid expected Android version code"
if [[ $VARIANT == tensorG3 ]]; then
  [[ $EXPECTED_SOURCE_RUNTIME_AAR_SHA256 =~ ^[0-9a-f]{64}$ ]] ||
    fail "Tensor G3 requires a lowercase source runtime AAR SHA-256"
  [[ $EXPECTED_RUNTIME_PROVENANCE_SHA256 =~ ^[0-9a-f]{64}$ ]] ||
    fail "Tensor G3 requires a lowercase runtime provenance SHA-256"
  [[ $EXPECTED_SDX_AOT_PROVENANCE_SHA256 =~ ^[0-9a-f]{64}$ ]] ||
    fail "Tensor G3 requires a lowercase SDX AOT provenance SHA-256"
else
  [[ -z $EXPECTED_SOURCE_RUNTIME_AAR_SHA256 && -z $EXPECTED_RUNTIME_PROVENANCE_SHA256 && -z $EXPECTED_SDX_AOT_PROVENANCE_SHA256 ]] ||
    fail "runtime provenance digests are only valid for Tensor G3"
  EXPECTED_SOURCE_RUNTIME_AAR_SHA256=not-applicable
  EXPECTED_RUNTIME_PROVENANCE_SHA256=not-applicable
  EXPECTED_SDX_AOT_PROVENANCE_SHA256=not-applicable
fi
[[ -s $APK && -f $APK ]] || fail "APK not found or empty: $APK"
[[ -s $CONFIG && -f $CONFIG ]] || fail "config not found or empty: $CONFIG"
[[ -d $SDX_SDK ]] || fail "SDX SDK directory not found: $SDX_SDK"
[[ -s $SDX_SDK/metadata/build.properties ]] || fail "SDX SDK metadata not found: $SDX_SDK/metadata/build.properties"
grep -Fxq 'abi.version=2' "$SDX_SDK/metadata/build.properties" || fail "SDX SDK does not declare ABI v2"
grep -Fxq 'direct.gguf=true' "$SDX_SDK/metadata/build.properties" || fail "SDX SDK does not declare direct GGUF preparation"
[[ "$(uname -s)" == "Linux" ]] ||
  fail "this APK verifier requires Linux with GNU coreutils/findutils"
[[ -z $RUNTIME_AAR_OVERRIDE || ( -f $RUNTIME_AAR_OVERRIDE && -s $RUNTIME_AAR_OVERRIDE ) ]] ||
  fail "runtime AAR override not found or empty: $RUNTIME_AAR_OVERRIDE"
[[ -d $ANDROID_SDK/build-tools ]] || fail "Android SDK build-tools not found"
[[ -d $ANDROID_NDK/toolchains/llvm/prebuilt ]] || fail "Android NDK toolchain not found"
for t in cmake unzip zip sha256sum; do command -v "$t" >/dev/null || fail "required tool not found: $t"; done
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
VALIDATOR=$SCRIPT_DIR/verify-offline-apk-json.cmake
[[ -s $VALIDATOR ]] || fail "companion validator missing"
VERIFY_TMP_ROOT="${VERIFY_OFFLINE_APK_TMPDIR:-$SCRIPT_DIR/../build/verify-offline-apk-tmp}"
mkdir -p -- "$VERIFY_TMP_ROOT"
TMP=$(mktemp -d "$VERIFY_TMP_ROOT/verify-offline-apk.XXXXXXXX")
trap 'rm -rf -- "$TMP"' EXIT HUP INT TERM

mapfile -t BTDIRS < <(find "$ANDROID_SDK/build-tools" -mindepth 1 -maxdepth 1 -type d -print | sort -V -r)
AAPT2= APKSIGNER= DEXDUMP=
for d in "${BTDIRS[@]}"; do [[ -x $d/aapt2 && -x $d/apksigner ]] || continue
 AAPT2=$d/aapt2; APKSIGNER=$d/apksigner; [[ -x $d/dexdump ]] && DEXDUMP=$d/dexdump; break; done
[[ -n $AAPT2 && -n $APKSIGNER ]] || fail "aapt2/apksigner not found in one build-tools version"
READELF=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
STRIP=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
[[ -x $READELF ]] || fail "llvm-readelf not found or not executable: $READELF"
[[ -x $STRIP ]] || fail "llvm-strip not found or not executable: $STRIP"
APKANALYZER=$ANDROID_SDK/cmdline-tools/latest/bin/apkanalyzer
[[ -x $APKANALYZER ]] || fail "apkanalyzer not found or not executable: $APKANALYZER"

CONTRACT=$TMP/contract
cmake -DMODE=config -DINPUT="$CONFIG" -DVARIANT="$VARIANT" -DOUTPUT="$CONTRACT" -P "$VALIDATOR" >/dev/null
declare APPLICATION PACKAGE_SUFFIX FLAVOR RUNTIME_AAR BACKEND GPU_TARGET RELEASE_COMPONENT RELEASE_PACKAGE_ROLE RELEASE_PLATFORM RELEASE_VARIANT MIN_SDK PROVIDER TARGET_PROFILE DSP_SERVICE REQUIRED_LIBS FORBIDDEN_LIBS
while IFS='=' read -r k v; do case $k in
 APPLICATION|PACKAGE_SUFFIX|FLAVOR|RUNTIME_AAR|BACKEND|GPU_TARGET|RELEASE_COMPONENT|RELEASE_PACKAGE_ROLE|RELEASE_PLATFORM|RELEASE_VARIANT|MIN_SDK|PROVIDER|TARGET_PROFILE|DSP_SERVICE|REQUIRED_LIBS|FORBIDDEN_LIBS) printf -v "$k" %s "$v";;
 *) fail "unknown validator output: $k";; esac; done < "$CONTRACT"
for k in APPLICATION PACKAGE_SUFFIX FLAVOR RUNTIME_AAR BACKEND RELEASE_COMPONENT RELEASE_PACKAGE_ROLE RELEASE_PLATFORM RELEASE_VARIANT MIN_SDK PROVIDER TARGET_PROFILE REQUIRED_LIBS FORBIDDEN_LIBS; do
 [[ -n ${!k:-} ]] || fail "validator omitted $k"; done

NAMES=$TMP/apk.names
unzip -Z1 "$APK" > "$NAMES" || fail "APK is not a readable ZIP"
[[ -s $NAMES ]] || fail "APK is empty"
[[ $(wc -l < "$NAMES") -eq $(sort -u "$NAMES"|wc -l) ]] || fail "APK has duplicate members"
while IFS= read -r n; do [[ -n $n && $n != /* && $n != *\\* ]] || fail "unsafe APK path: $n"
 case "/$n/" in */../*) fail "unsafe APK path: $n";; esac; done < "$NAMES"
# Duplicate members and unsafe paths are rejected above. Force overwrite mode so
# verification can never block on an interactive unzip prompt in unattended builds.
unzip -oqq "$APK" -d "$TMP/apk" || fail "APK extraction failed"

"$APKSIGNER" verify --verbose "$APK" >/dev/null || fail "APK signature audit failed"
"$AAPT2" dump permissions "$APK" > "$TMP/permissions" || fail "APK permission audit failed"
grep -Fq android.permission.INTERNET "$TMP/permissions" ||
  fail "APK cannot resolve public Hugging Face repositories without INTERNET"
"$AAPT2" dump badging "$APK" > "$TMP/badging" || fail "APK identity audit failed"
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$TMP/manifest-tree" ||
  fail "APK manifest audit failed"
grep -Eq 'extractNativeLibs[^=]*=true' "$TMP/manifest-tree" ||
  fail "APK does not extract its bundled native runtime"
grep -Eq 'usesCleartextTraffic[^=]*=false' "$TMP/manifest-tree" ||
  fail "APK permits cleartext traffic despite its public-HTTPS discovery and transfer contract"
if [[ $VARIANT == hexagon ]]; then
  grep -Fq 'libcdsprpc.so' "$TMP/manifest-tree" ||
    fail "Hexagon APK does not declare the system FastRPC transport"
  grep -Eq 'android:required.*(0xffffffff|true)' "$TMP/manifest-tree" ||
    fail "Hexagon FastRPC transport is not required by the manifest"
else
  ! grep -Fq 'libcdsprpc.so' "$TMP/manifest-tree" ||
    fail "Qualcomm FastRPC manifest dependency leaked into $VARIANT APK"
fi
PACKAGE=$APPLICATION.$PACKAGE_SUFFIX.debug
case $VARIANT in
 vulkan) VERSION_SUFFIX=-vulkan;;
 hexagon) VERSION_SUFFIX=-hexagon;;
 tensorG3) VERSION_SUFFIX=-tensor-g3-nnapi;;
 tensorG5) VERSION_SUFFIX=-tensor-g5;;
esac
EXPECTED_VERSION_NAME=0.1.0-SNAPSHOT-$EXPECTED_BUILD_ID$VERSION_SUFFIX
grep -Eq "^package: name='$PACKAGE'([[:space:]]|$)" "$TMP/badging" || fail "wrong APK package identity (expected $PACKAGE)"
grep -Eq "versionCode='$EXPECTED_VERSION_CODE'([[:space:]]|$)" "$TMP/badging" ||
  fail "APK versionCode does not match this build (expected $EXPECTED_VERSION_CODE)"
grep -Fq "versionName='$EXPECTED_VERSION_NAME'" "$TMP/badging" ||
  fail "APK versionName does not match this flavor/build (expected $EXPECTED_VERSION_NAME)"
grep -Eq "sdkVersion:'${MIN_SDK}'|minSdkVersion[^0-9]*${MIN_SDK}([^0-9]|$)" "$TMP/badging" || fail "APK minSdk is not ${MIN_SDK}"

"$APKANALYZER" manifest print "$APK" > "$TMP/manifest.xml" || fail "decoded manifest audit failed"
MODEL_PREPARATION_SERVICE=$(tr '\n' ' ' < "$TMP/manifest.xml" |
  grep -oE '<service[^>]*SdxModelPreparationService[^>]*>' || true)
[[ -n $MODEL_PREPARATION_SERVICE ]] ||
  fail "APK manifest is missing SdxModelPreparationService"
grep -Fq 'android:exported="false"' <<<"$MODEL_PREPARATION_SERVICE" ||
  fail "SDX model preparation service must not be exported"
grep -Eq "android:process=\"(:sdx_model_import|$PACKAGE:sdx_model_import)\"" <<<"$MODEL_PREPARATION_SERVICE" ||
  fail "SDX model preparation service is not isolated in :sdx_model_import"
if [[ $VARIANT != tensorG5 ]]; then
  SDX_RUNTIME_SERVICE=$(tr '\n' ' ' < "$TMP/manifest.xml" |
    grep -oE '<service[^>]*SdxRuntimeService[^>]*>' || true)
  [[ -n $SDX_RUNTIME_SERVICE ]] ||
    fail "APK manifest is missing SdxRuntimeService"
  grep -Fq 'android:exported="false"' <<<"$SDX_RUNTIME_SERVICE" ||
    fail "SDX runtime service must not be exported"
  grep -Eq "android:process=\"(:sdx_model_runtime|$PACKAGE:sdx_model_runtime)\"" <<<"$SDX_RUNTIME_SERVICE" ||
    fail "SDX runtime service is not isolated in :sdx_model_runtime"
fi

mapfile -t ENTRIES < <(grep -E '^lib/[^/]+/[^/]+\.so$' "$NAMES" || true)
[[ ${#ENTRIES[@]} -gt 0 ]] || fail "APK has no native libraries"
declare -A LIBS=()
for e in "${ENTRIES[@]}"; do [[ $e == lib/arm64-v8a/* ]] || fail "non-arm64 ABI: $e"; LIBS[${e##*/}]=$TMP/apk/$e; done
for g in libjnikompile_graph.so libkompile_reasoning_android.so; do [[ -n ${LIBS[$g]:-} ]] || fail "missing graph AOT library: $g"; done
IFS=, read -ra REQUIRED <<< "$REQUIRED_LIBS"; for l in "${REQUIRED[@]}"; do [[ -n ${LIBS[$l]:-} ]] || fail "missing provider library: $l"; done
[[ -n ${LIBS[libjnitokenizers.so]:-} ]] || fail "missing provider tokenizer JNI library: libjnitokenizers.so"
TOKENIZER_JNI_SYMBOLS=$("$READELF" --dyn-syms --wide "${LIBS[libjnitokenizers.so]}") ||
  fail "cannot inspect tokenizer JNI export surface"
TOKENIZER_JNI_CONTRACT=(
  'Java_org_eclipse_deeplearning4j_tokenizers_bindings_TokenizersNative_applyChatTemplate__Lorg_bytedeco_javacpp_BytePointer_2Lorg_bytedeco_javacpp_BytePointer_2Lorg_bytedeco_javacpp_BytePointer_2Z'
  'Java_org_eclipse_deeplearning4j_tokenizers_bindings_TokenizersNative_applyChatTemplateContext__Lorg_bytedeco_javacpp_BytePointer_2Lorg_bytedeco_javacpp_BytePointer_2Lorg_bytedeco_javacpp_BytePointer_2'
  'Java_org_eclipse_deeplearning4j_tokenizers_bindings_TokenizersNative_createDecodeStream'
  'Java_org_eclipse_deeplearning4j_tokenizers_bindings_TokenizersNative_decodeStreamStep'
  'Java_org_eclipse_deeplearning4j_tokenizers_bindings_TokenizersNative_freeDecodeStream'
  'Java_org_eclipse_deeplearning4j_tokenizers_bindings_TokenizersNative_freeString__Lorg_bytedeco_javacpp_BytePointer_2'
  'Java_org_eclipse_deeplearning4j_tokenizers_bindings_TokenizersNative_getTokenId__Lorg_eclipse_deeplearning4j_tokenizers_bindings_TokenizersNative_00024OpaqueTokenizer_2Ljava_lang_String_2_3I'
)
for tokenizer_jni_symbol in "${TOKENIZER_JNI_CONTRACT[@]}"; do
  grep -Eq "[[:space:]]$tokenizer_jni_symbol(@[^[:space:]]*)?$" <<<"$TOKENIZER_JNI_SYMBOLS" ||
    fail "tokenizer Java/JNI binding mismatch; libjnitokenizers.so is missing: $tokenizer_jni_symbol"
done
LOWER=$(printf '%s\n' "${!LIBS[@]}"|tr '[:upper:]' '[:lower:]')
IFS=, read -ra FORBIDDEN <<< "$FORBIDDEN_LIBS"
for x in "${FORBIDDEN[@]}" libjnidispatch libgfortran libquadmath ld-linux libstdc++.so.6; do ! grep -Fqi "$x" <<<"$LOWER" || fail "forbidden library: $x"; done
case $VARIANT in
 vulkan) [[ -n ${LIBS[libnd4jvulkan.so]:-} ]]; ! grep -Eqi 'litert|hexagon|nnapi|neuralnetworks' <<<"$LOWER";;
 hexagon) [[ -n $DSP_SERVICE && -n ${LIBS[$DSP_SERVICE]:-} ]]; ! grep -Eqi 'litert|vulkan|nnapi|neuralnetworks' <<<"$LOWER";;
 tensorG3) [[ -n ${LIBS[libnd4jnnapi.so]:-} ]]; ! grep -Eqi 'litert|vulkan|hexagon' <<<"$LOWER";;
 tensorG5) [[ -n ${LIBS[liblitert-lm.so]:-} ]]; ! grep -Eqi 'vulkan|hexagon|nnapi|neuralnetworks' <<<"$LOWER";;
esac || fail "wrong or mixed provider native runtime"
if [[ $VARIANT == tensorG3 ]]; then
 for importer_library in libjnind4jcpu.so libnd4jcpu.so libopenblas.so libomp.so; do
  [[ -n ${LIBS[$importer_library]:-} ]] ||
   fail "Tensor G3 APK is missing required CPU importer library: $importer_library"
 done
fi

is_android_system_library(){
 case "$1" in
  libc.so|libdl.so|libm.so|liblog.so|libz.so|libandroid.so|libvulkan.so|libEGL.so|libGLESv2.so|libGLESv3.so|libjnigraphics.so|libmediandk.so|libnativewindow.so|libOpenSLES.so|libaaudio.so) return 0;;
  # NNAPI is an Android platform library from API 27. This app's minSdk is 28,
  # and the provider-independent SDX importer may link it in every flavor.
  libneuralnetworks.so) return 0;;
  libcdsprpc.so) [[ $VARIANT == hexagon ]]; return;;
 esac
 return 1
}

is_hexagon_dsp_system_library(){
 case "$1" in libc.so|libgcc.so) return 0;; esac
 return 1
}

# These libraries are the explicit provider-independent SDX GGUF CPU route. Their
# names/symbols may mention ND4J CPU or OpenBLAS; no provider library receives that
# exemption, and every dependency still has to be packaged and Android/AArch64-clean.
is_raw_sdx_cpu_library(){
 case "$1" in
  libsdx_cpu.so|libnd4jcpu.so|libjnind4jcpu.so|libjniopenblas*.so|libopenblas*.so) return 0;;
 esac
 return 1
}

audit_elf(){ local b=$1 f=$2 h d s needed undefined dependency_surface dep elf_identity
 h=$("$READELF" -h -l "$f") || fail "ELF header audit failed: $b"
 elf_identity=$(grep -E 'Class:|Type:|Machine:' <<<"$h" | tr '\n' ' ')
 d=$("$READELF" -d "$f") || fail "ELF dynamic audit failed: $b"
 s=$("$READELF" --dyn-syms --wide "$f") || fail "ELF symbol audit failed: $b"
 if [[ -n $DSP_SERVICE && $b == "$DSP_SERVICE" ]]; then
   [[ $VARIANT == hexagon ]] || fail "Hexagon DSP service leaked into $VARIANT APK"
   grep -Eq 'Machine:.*Hexagon' <<<"$h" && grep -Eq 'Type:[[:space:]]+DYN' <<<"$h" ||
     fail "not Qualcomm Hexagon DYN: $b ($elf_identity)"
   ! grep -Eq '[(]RPATH[)]|TEXTREL' <<<"$d" || fail "unsafe DSP ELF tag: $b"
   dependency_surface=$(printf '%s\n%s\n%s' "$h" "$d" "$s"|tr '[:upper:]' '[:lower:]')
   for x in openblas gfortran quadmath nd4jcpu sdx_cpu ld-linux linuxbrew; do
     ! grep -Fq "$x" <<<"$dependency_surface" ||
       fail "forbidden DSP dependency $x in $b"
   done
   while IFS= read -r dep; do
     [[ -z $dep ]] && continue
     is_hexagon_dsp_system_library "$dep" ||
       fail "unexpected Qualcomm DSP dependency $dep required by $b"
   done < <(grep -oE 'Shared library: \[[^]]+\]' <<<"$d" | tr -d '[]' | sed 's/^Shared library: //')
   return
 fi
 grep -Fq AArch64 <<<"$h" && grep -Eq 'Type:[[:space:]]+DYN' <<<"$h" ||
   fail "not AArch64 DYN: $b ($elf_identity)"
 grep -Fq GNU_RELRO <<<"$h" && grep -Fq BIND_NOW <<<"$d" || fail "ELF hardening missing: $b"
 ! grep -Eq '[(]RPATH[)]|TEXTREL' <<<"$d" || fail "unsafe ELF tag: $b"
 if grep -Fq '(RUNPATH)' <<<"$d"; then
   # LiteRT-LM and the explicit raw-SDX CPU importer resolve separately packaged
   # companion libraries beside the runtime. Permit only that non-escaping local
   # lookup; reject every absolute, parent-relative, or multi-entry RUNPATH.
   [[ $VARIANT == tensorG5 ]] || is_raw_sdx_cpu_library "$b" || fail "unexpected RUNPATH: $b"
   ! grep -F '(RUNPATH)' <<<"$d" | grep -Evq 'Library runpath: \[\$ORIGIN\][[:space:]]*$' || fail "unsafe RUNPATH: $b"
 fi
 needed=$(grep -oE 'Shared library: \[[^]]+\]' <<<"$d"|tr '\n' ' '||true)
 undefined=$(grep -E '[[:space:]]UND[[:space:]]' <<<"$s" || true)
 dependency_surface=$(printf '%s\n%s\n%s' "$h" "$d" "$undefined"|tr '[:upper:]' '[:lower:]')
 # Audit runtime dependencies and unresolved imports. Only the explicitly packaged
 # raw-SDX CPU companion libraries may expose ND4J CPU/OpenBLAS names.
 for x in gfortran quadmath ld-linux linuxbrew; do ! grep -Fq "$x" <<<"$dependency_surface" || fail "forbidden ELF dependency $x in $b"; done
 if ! is_raw_sdx_cpu_library "$b"; then
  for x in openblas nd4jcpu sdx_cpu; do ! grep -Fq "$x" <<<"$dependency_surface" || fail "forbidden provider dependency $x in $b"; done
 fi
 while IFS= read -r dep; do
   [[ -z $dep ]] && continue
   is_android_system_library "$dep" && continue
   [[ -n ${LIBS[$dep]:-} ]] ||
     fail "unpackaged native dependency $dep required by $b"
 done < <(grep -oE '\[[^]]+\]' <<<"$needed" | tr -d '[]')
 if [[ $b == libkompile_reasoning_android.so ]]; then
   local actual expected
   actual=$(grep -oE '\[[^]]+\]' <<<"$needed" | tr -d '[]' | sort | tr '\n' ' ')
   expected=$(printf '%s\n' libc.so libdl.so liblog.so libm.so libz.so | sort | tr '\n' ' ')
   [[ $actual == "$expected" ]] || fail "unexpected graph AOT dependencies: $actual"
 elif [[ $b == libjnikompile_graph.so ]]; then
   grep -Fq libkompile_reasoning_android.so <<<"$needed" || fail "JavaCPP graph transport is not linked"
   while IFS= read -r dep; do case $dep in
     libc.so|libdl.so|liblog.so|libm.so|libz.so|libkompile_reasoning_android.so) ;;
     '') ;; *) fail "unexpected JavaCPP graph dependency: $dep";; esac
   done < <(grep -oE '\[[^]]+\]' <<<"$needed" | tr -d '[]')
 elif [[ $b == libjnisdx_llm.so ]]; then
   grep -Fq libsdx_llm.so <<<"$needed" ||
     fail "direct SDX LLM JNI transport is not linked to libsdx_llm.so"
   ! grep -Fq libjnijavacpp.so <<<"$needed" ||
     fail "ART-facing SDX JNI transport must not share JavaCPP state with embedded Graal"
   while IFS= read -r dep; do case $dep in
     libc.so|libc++_shared.so|libdl.so|liblog.so|libm.so|libsdx_llm.so) ;;
     '') ;; *) fail "unexpected direct SDX LLM JNI dependency: $dep";; esac
   done < <(grep -oE '\[[^]]+\]' <<<"$needed" | tr -d '[]')
 fi
 if [[ $VARIANT == tensorG3 && $b == libnd4jnnapi.so ]]; then
   grep -Fq libneuralnetworks.so <<<"$needed" || fail "NNAPI system dependency missing"
   for symbol in \
     ANeuralNetworks_getDeviceCount \
     ANeuralNetworks_getDevice \
     ANeuralNetworksDevice_getName \
     ANeuralNetworksDevice_getType \
     ANeuralNetworksDevice_getFeatureLevel \
     ANeuralNetworksModel_getSupportedOperationsForDevices \
     ANeuralNetworksCompilation_createForDevices; do
    grep -Eq "[[:space:]]${symbol}(@[^[:space:]]*)?$" <<<"$s" ||
      fail "Tensor G3 provider missing pinned-device symbol: $symbol"
   done
   ! grep -Eq '[[:space:]]ANeuralNetworksCompilation_create(@[^[:space:]]*)?$' <<<"$s" ||
     fail "Tensor G3 provider contains forbidden generic NNAPI compilation"
   grep -aFq google-edgetpu "$f" ||
     fail "Tensor G3 provider missing google-edgetpu device fingerprint"
 fi
 if [[ $b == libsdx_llm.so ]]; then
  # This native image is confined to :sdx_model_import. It prepares and caches an
  # SDZ; the flavor runtime opens that SDZ only after the importer PID is gone.
  for symbol in sdxLlmCreateRuntime sdxLlmDestroyRuntime sdxLlmAbiVersion sdxLlmPrepareGguf sdxLlmFree sdxLlmGetLastError; do
   grep -Eq "[[:space:]]$symbol(@[^[:space:]]*)?$" <<<"$s" ||
    fail "libsdx_llm.so missing GGUF preparation export: $symbol"
  done
 fi
 if [[ $b == libnd4jcpu.so ]]; then
  # The exact runtime packaged in the APK must understand the canonical v2
  # text-generation metadata emitted by current SDZ producers. The immutable AOT
  # SDK comparison below binds this contract to the selected importer artifact.
  local -a text_generation_v2_contracts=(
   "causal-lm-in-graph-state-v2"
   "io.recurrentStates"
   "duplicate recurrent state input"
  )
  for contract in "${text_generation_v2_contracts[@]}"; do
   grep -aFq "$contract" "$f" ||
    fail "packaged libnd4jcpu.so lacks text-generation v2 contract: $contract"
  done
 fi
 if [[ $b == libnd4jcpu.so || $b == libnd4jnnapi.so ]]; then
  if grep -aFq 'executeSegmentWithCpuGraph: no CPU graph backends available' "$f"; then
   fail "packaged $b contains the obsolete manual CPU-backend chain"
  fi
 fi
}
for b in "${!LIBS[@]}"; do audit_elf "$b" "${LIBS[$b]}"; done

ASSETS=$TMP/apk/assets/offline-assets.json
[[ -s $ASSETS ]] || fail "offline asset manifest missing"
cmake -DMODE=assets -DINPUT="$ASSETS" -DVARIANT="$VARIANT" -DEXPECTED_BACKEND="$BACKEND" -DASSET_ROOT="$TMP/apk/assets" -P "$VALIDATOR" >/dev/null
grep -Eq '^assets/.+\.kgraph$' "$NAMES" || fail "graph AOT asset missing"

case $VARIANT in tensorG3) AD=tensor-g3;; tensorG5) AD=tensor-g5;; *) AD=$VARIANT;; esac
CDIR=$(cd "$(dirname "$CONFIG")" && pwd -P)
if [[ -n $RUNTIME_AAR_OVERRIDE ]]; then
 # The builder passes the exact normalized AAR consumed by this Gradle invocation.
 # Never replace it with a stale artifact from the default app/build directory.
 AAR=$RUNTIME_AAR_OVERRIDE
else
 AAR=$CDIR/app/libs/$AD/$RUNTIME_AAR
 NORMALIZED_AAR=$CDIR/app/build/sdx-normalized-aar/$AD/sdx-runtime-$AD.aar
 [[ -s $NORMALIZED_AAR ]] && AAR=$NORMALIZED_AAR
fi
# Tensor G3 exact-stages the source-bound producer AAR; the other providers may
# still normalize shared Java/tokenizer layers before packaging.
[[ -s $AAR ]] || fail "configured runtime AAR not found: $AAR"
if [[ $VARIANT == tensorG3 ]]; then
 AAR_HASH=$(sha256sum "$AAR") || fail "cannot hash Tensor G3 runtime AAR"; AAR_HASH=${AAR_HASH%% *}
 [[ $AAR_HASH == "$EXPECTED_SOURCE_RUNTIME_AAR_SHA256" ]] ||
  fail "Tensor G3 consumed runtime AAR differs from the source-bound producer AAR"
fi
AN=$TMP/aar.names; unzip -Z1 "$AAR" > "$AN" || fail "bad runtime AAR"
[[ $(wc -l < "$AN") -eq $(sort -u "$AN"|wc -l) ]] || fail "AAR has duplicate members"
for e in AndroidManifest.xml binding.json provider.json classes.jar; do grep -Fxq "$e" "$AN" || fail "AAR missing $e"; unzip -p "$AAR" "$e" > "$TMP/$e"; [[ -s $TMP/$e ]] || fail "AAR has empty $e"; done
cmake -DMODE=binding -DINPUT="$TMP/binding.json" -DPROVIDER_INPUT="$TMP/provider.json" -DVARIANT="$VARIANT" -P "$VALIDATOR" >/dev/null
unzip -Z1 "$TMP/classes.jar" > "$TMP/classes.names" || fail "bad classes.jar"
# Every entry the application resolves against. A provider AAR that predates a
# change to the shared SDX API otherwise ships a payload that passes every native
# check and still cannot be compiled against, with nothing in the Kotlin errors
# pointing at the AAR. Nested classes are single-quoted so the shell leaves the
# JVM's `$` separator alone.
CLASSES=(org/nd4j/dsp/model/HuggingFaceGgmlResolver.class 'org/nd4j/dsp/model/HuggingFaceGgmlResolver$Candidate.class' 'org/nd4j/dsp/model/HuggingFaceGgmlResolver$Discovery.class' 'org/nd4j/dsp/model/HuggingFaceGgmlResolver$Kind.class' 'org/nd4j/dsp/model/HuggingFaceGgmlResolver$Reference.class' 'org/nd4j/dsp/model/HuggingFaceGgmlResolver$RepositoryFile.class' org/nd4j/dsp/model/SdxCompiledModel.class org/nd4j/dsp/model/SdxModelCache.class org/nd4j/dsp/model/SdxTargetProfile.class org/nd4j/dsp/model/SdxTextModelAssets.class)
if [[ $VARIANT == tensorG5 ]]; then CLASSES+=(org/nd4j/dsp/runtime/litertlm/SdxLiteRtLmChatSession.class org/nd4j/dsp/runtime/presets/LiteRtLmPresets.class org/nd4j/dsp/runtime/litertlm/bindings/LiteRtLmNative.class)
else CLASSES+=(org/nd4j/dsp/runtime/SdxRuntime.class org/nd4j/dsp/runtime/SdxTextSession.class org/nd4j/dsp/runtime/presets/SdxRuntimePresets.class org/nd4j/dsp/runtime/bindings/SdxNative.class org/nd4j/dsp/model/SdxLlmNative.class org/eclipse/deeplearning4j/tokenizers/NativeTokenizer.class 'org/eclipse/deeplearning4j/tokenizers/NativeTokenizer$ChatMessage.class' org/eclipse/deeplearning4j/tokenizers/presets/TokenizersPresets.class org/eclipse/deeplearning4j/tokenizers/presets/TokenizersHelper.class org/eclipse/deeplearning4j/tokenizers/bindings/TokenizersNative.class); fi
for c in "${CLASSES[@]}"; do grep -Fxq "$c" "$TMP/classes.names" || fail "AAR missing API class: $c"; done
mapfile -t AE < <(grep -E '^jni/[^/]+/[^/]+\.so$' "$AN"||true); [[ ${#AE[@]} -gt 0 ]] || fail "AAR has no native libs"
declare -A AL=(); for e in "${AE[@]}"; do [[ $e == jni/arm64-v8a/* ]] || fail "AAR has non-arm64 ABI"; AL[${e##*/}]=1; done
# Provider-independent application runtimes are staged outside the provider AAR:
# libsdx_llm comes from DL4J's explicit android-aot SDK; Kompile builds its own
# direct host JNI bridge against that stable C ABI.
for l in "${REQUIRED[@]}"; do
 case "$l" in libsdx_llm.so|libjnisdx_llm.so) continue;; esac
 [[ -n ${AL[$l]:-} ]] || fail "AAR missing $l"
done
if [[ $VARIANT == tensorG5 ]]; then
 [[ -n ${AL[libjnilitertlm.so]:-} ]] || fail "AAR missing libjnilitertlm.so"
else
 for l in libjnisdx.so libjnitokenizers.so libtokenizers_wrapper.so libtokenizers_ffi.so; do
  [[ -n ${AL[$l]:-} ]] || fail "AAR missing $l"
 done
 # Import success depends on native Hugging Face chat-template rendering, not
 # merely on carrying tokenizer filenames or Java facade classes.
 for tokenizer_contract in \
  libtokenizers_wrapper.so:apply_chat_template \
  libtokenizers_ffi.so:ffi_tokenizer_apply_chat_template; do
  tokenizer_library=${tokenizer_contract%%:*}
  tokenizer_symbol=${tokenizer_contract#*:}
  tokenizer_symbols=$("$READELF" --dyn-syms --wide "${LIBS[$tokenizer_library]}") ||
   fail "cannot inspect tokenizer contract library: $tokenizer_library"
  grep -Eq "[[:space:]]$tokenizer_symbol(@[^[:space:]]*)?$" <<<"$tokenizer_symbols" ||
   fail "APK tokenizer library $tokenizer_library missing required export: $tokenizer_symbol"
 done
fi
for l in "${!AL[@]}"; do
 [[ -n ${LIBS[$l]:-} ]] || fail "AAR native library not packaged: $l"
 AH=$(unzip -p "$AAR" "jni/arm64-v8a/$l" | sha256sum) || fail "cannot hash AAR native library: $l"
 AH=${AH%% *}; PH=$(sha256sum "${LIBS[$l]}") || fail "cannot hash APK native library: $l"; PH=${PH%% *}
 if [[ $AH != "$PH" && $VARIANT == tensorG3 && $l == libomp.so ]]; then
  # Tensor G3's provider and CPU importer are built against the same NDK libomp
  # (same Build ID and exports), but the importer SDK removes deployment-irrelevant
  # DWARF. Compare the provider after that exact canonicalization; the SDK audit
  # below still requires the APK member to match the importer byte-for-byte.
  AAR_CANONICAL_LIBOMP=$TMP/aar-canonical-libomp.so
  unzip -p "$AAR" "jni/arm64-v8a/$l" > "$AAR_CANONICAL_LIBOMP" ||
   fail "cannot extract AAR OpenMP runtime"
  "$STRIP" --strip-debug "$AAR_CANONICAL_LIBOMP" ||
   fail "cannot canonicalize AAR OpenMP runtime"
  AH=$(sha256sum "$AAR_CANONICAL_LIBOMP") ||
   fail "cannot hash canonical AAR OpenMP runtime"
  AH=${AH%% *}
 fi
 [[ $AH == "$PH" ]] || fail "APK native library differs from configured AAR: $l"
done

# Audit the separately published provider-independent importer SDK byte-for-byte.
# Its immutable completion record binds a complete SDK ZIP. Importer-owned native
# members must also match the APK. The normalized provider AAR owns its tokenizer
# Java/JNI pair, which was already compared byte-for-byte with the APK above.
SDX_JNI_DIR=$SDX_SDK/jni/arm64-v8a
SDX_METADATA=$SDX_SDK/metadata/build.properties
SDX_NATIVE_MANIFEST=$SDX_SDK/metadata/cmake-owned-native-libraries.txt
SDX_NATIVE_BYTES=$SDX_SDK/metadata/sdk-native-bytes.txt
SDX_BUILD_RECEIPT=$SDX_SDK/metadata/build-receipt
SDX_COMPLETION=$SDX_SDK/.complete.cmake
[[ -s $SDX_JNI_DIR/libsdx_llm.so ]] || fail "SDX SDK is missing jni/arm64-v8a/libsdx_llm.so"
[[ ! -e $SDX_JNI_DIR/libjnisdx_llm.so ]] || fail "SDX SDK contains the Kompile-owned Android JNI bridge"
[[ -s $SDX_NATIVE_MANIFEST ]] || fail "SDX SDK native manifest is missing"
[[ -s $SDX_NATIVE_BYTES ]] || fail "SDX SDK native byte manifest is missing"
[[ -s $SDX_BUILD_RECEIPT ]] || fail "SDX SDK build receipt is missing"
[[ -s $SDX_COMPLETION ]] || fail "SDX SDK immutable completion record is missing"

SDX_BUILD_RECEIPT_HASH=$(sha256sum "$SDX_BUILD_RECEIPT") || fail "cannot hash SDX SDK build receipt"; SDX_BUILD_RECEIPT_HASH=${SDX_BUILD_RECEIPT_HASH%% *}
DECLARED_SDX_BUILD_RECEIPT_HASH=$(sed -n 's/^build[.]receipt[.]sha256=//p' "$SDX_METADATA")
[[ $DECLARED_SDX_BUILD_RECEIPT_HASH == "$SDX_BUILD_RECEIPT_HASH" ]] ||
  fail "SDX SDK metadata does not bind its build receipt"
if [[ $VARIANT == tensorG3 ]]; then
  [[ $SDX_BUILD_RECEIPT_HASH == "$EXPECTED_SDX_AOT_PROVENANCE_SHA256" ]] ||
    fail "selected SDX AOT SDK receipt differs from the Tensor G3 build provenance"
fi

EXPECTED_SDX_LIBRARY_HASH=$(sed -n 's/^library[.]sha256=//p' "$SDX_METADATA")
EXPECTED_SDX_NATIVE_COUNT=$(sed -n 's/^native[.]library[.]count=//p' "$SDX_METADATA")
COMPLETED_SDX_GENERATION_KEY=$(sed -n 's/^set(SDX_COMPLETED_GENERATION_KEY \[\[\(.*\)\]\])$/\1/p' "$SDX_COMPLETION")
COMPLETED_SDX_LIBRARY_HASH=$(sed -n 's/^set(SDX_COMPLETED_LIBRARY_SHA256 \[\[\(.*\)\]\])$/\1/p' "$SDX_COMPLETION")
SDX_ARCHIVE_NAME=$(sed -n 's/^set(SDX_COMPLETED_ARCHIVE_NAME \[\[\(.*\)\]\])$/\1/p' "$SDX_COMPLETION")
EXPECTED_SDX_ARCHIVE_HASH=$(sed -n 's/^set(SDX_COMPLETED_ARCHIVE_SHA256 \[\[\(.*\)\]\])$/\1/p' "$SDX_COMPLETION")
[[ $COMPLETED_SDX_GENERATION_KEY =~ ^[0-9a-f]{64}$ ]] || fail "SDX SDK completion metadata contains an invalid generation key"
for sdk_hash in "$EXPECTED_SDX_LIBRARY_HASH" "$COMPLETED_SDX_LIBRARY_HASH" "$EXPECTED_SDX_ARCHIVE_HASH"; do
 [[ $sdk_hash =~ ^[0-9a-f]{64}$ ]] || fail "SDX SDK completion metadata contains an invalid SHA-256"
done
[[ $EXPECTED_SDX_NATIVE_COUNT =~ ^[0-9]+$ ]] || fail "SDX SDK native library count is invalid"
[[ $SDX_ARCHIVE_NAME =~ ^sdx-aot-[A-Za-z0-9._-]+-android-arm64[.]zip$ ]] ||
  fail "SDX SDK completion record contains an unsafe archive name"
[[ $EXPECTED_SDX_LIBRARY_HASH == "$COMPLETED_SDX_LIBRARY_HASH" ]] ||
  fail "SDX SDK build metadata and completion record disagree on libsdx_llm.so"
CANONICAL_SDX_SDK=$(realpath -e -- "$SDX_SDK") || fail "cannot resolve the published SDX SDK generation"
[[ ${CANONICAL_SDX_SDK##*/} == "$COMPLETED_SDX_GENERATION_KEY"-* ]] ||
  fail "SDX SDK public path does not resolve to its content-addressed completed generation"
[[ ! -w $CANONICAL_SDX_SDK ]] || fail "completed SDX SDK generation is unexpectedly writable"
if find "$CANONICAL_SDX_SDK" -type l -print -quit | grep -q .; then
  fail "completed SDX SDK generation contains a symlink"
fi
if find "$CANONICAL_SDX_SDK" -perm /0222 -print -quit | grep -q .; then
  fail "completed SDX SDK generation contains a writable member"
fi
SDK_HASH=$(sha256sum "$SDX_JNI_DIR/libsdx_llm.so") || fail "cannot hash SDX SDK libsdx_llm.so"; SDK_HASH=${SDK_HASH%% *}
[[ $SDK_HASH == "$EXPECTED_SDX_LIBRARY_HASH" ]] ||
  fail "SDX SDK libsdx_llm.so does not match its immutable metadata"

SDX_ARCHIVE=$SDX_SDK/$SDX_ARCHIVE_NAME
[[ -s $SDX_ARCHIVE ]] || fail "SDX SDK immutable archive is missing: $SDX_ARCHIVE"
SDK_ARCHIVE_HASH=$(sha256sum "$SDX_ARCHIVE") || fail "cannot hash SDX SDK archive"; SDK_ARCHIVE_HASH=${SDK_ARCHIVE_HASH%% *}
[[ $SDK_ARCHIVE_HASH == "$EXPECTED_SDX_ARCHIVE_HASH" ]] || fail "SDX SDK archive does not match its completion record"
SDX_ARCHIVE_NAMES=$TMP/sdx-sdk.names
unzip -Z1 "$SDX_ARCHIVE" > "$SDX_ARCHIVE_NAMES" || fail "SDX SDK archive is not a readable ZIP"
[[ $(wc -l < "$SDX_ARCHIVE_NAMES") -eq $(sort -u "$SDX_ARCHIVE_NAMES" | wc -l) ]] ||
  fail "SDX SDK archive has duplicate members"

mapfile -t SDX_LIBRARY_NAMES < <(find "$SDX_JNI_DIR" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | LC_ALL=C sort)
[[ ${#SDX_LIBRARY_NAMES[@]} -gt 0 ]] || fail "SDX SDK has no Android native libraries"
(( ${#SDX_LIBRARY_NAMES[@]} == EXPECTED_SDX_NATIVE_COUNT )) ||
  fail "SDX SDK native library count disagrees with build metadata"
SDX_DECLARED_SET=$(LC_ALL=C sort -u "$SDX_NATIVE_MANIFEST")
SDX_ACTUAL_SET=$(printf '%s\n' "${SDX_LIBRARY_NAMES[@]}")
[[ $SDX_DECLARED_SET == "$SDX_ACTUAL_SET" ]] ||
  fail "SDX SDK native directory does not match cmake-owned-native-libraries.txt"

SDX_EXPECTED_ARCHIVE_NAMES=$TMP/sdx-sdk.expected-names
{
 printf '%s\n' \
  include/sdx_llm_c.h \
	  metadata/build.properties \
	  metadata/build-receipt \
	  metadata/jdk-support-receipt \
	  metadata/base-sdk-native-bytes.txt \
  metadata/classpath-bytes.txt \
  metadata/javacpp-native-image-reachability.txt \
  metadata/fresh-class-builds.txt \
  metadata/native-image-optimization.txt \
  metadata/cmake-owned-native-libraries.txt \
  metadata/sdk-native-bytes.txt \
  metadata/native-dependency-closure.txt \
  metadata/jnijavacpp.cpp \
  metadata/javacpp_jni_lifecycle.cpp
 while IFS= read -r sdk_name; do
  printf 'jni/arm64-v8a/%s\n' "$sdk_name"
 done <"$SDX_NATIVE_MANIFEST"
} | LC_ALL=C sort -u >"$SDX_EXPECTED_ARCHIVE_NAMES"
LC_ALL=C sort -u "$SDX_ARCHIVE_NAMES" >"$TMP/sdx-sdk.actual-names"
cmp -s "$SDX_EXPECTED_ARCHIVE_NAMES" "$TMP/sdx-sdk.actual-names" ||
 fail "SDX SDK archive member set differs from its declared native closure"

for sdk_member in \
	 metadata/build.properties \
	 metadata/build-receipt \
	 metadata/jdk-support-receipt \
	 metadata/classpath-bytes.txt \
	 metadata/javacpp-native-image-reachability.txt \
	 metadata/javacpp_jni_lifecycle.cpp \
 metadata/fresh-class-builds.txt \
 metadata/native-image-optimization.txt \
 metadata/cmake-owned-native-libraries.txt \
 metadata/sdk-native-bytes.txt; do
 grep -Fxq "$sdk_member" "$SDX_ARCHIVE_NAMES" || fail "SDX SDK archive is missing $sdk_member"
 ARCHIVE_MEMBER_HASH=$(unzip -p "$SDX_ARCHIVE" "$sdk_member" | sha256sum) || fail "cannot hash archived $sdk_member"; ARCHIVE_MEMBER_HASH=${ARCHIVE_MEMBER_HASH%% *}
 EXPLODED_MEMBER_HASH=$(sha256sum "$SDX_SDK/$sdk_member") || fail "cannot hash exploded $sdk_member"; EXPLODED_MEMBER_HASH=${EXPLODED_MEMBER_HASH%% *}
 [[ $ARCHIVE_MEMBER_HASH == "$EXPLODED_MEMBER_HASH" ]] || fail "SDX SDK archive differs from exploded $sdk_member"
done

for sdk_name in "${SDX_LIBRARY_NAMES[@]}"; do
 [[ $sdk_name =~ ^lib[A-Za-z0-9._+-]+[.]so$ ]] || fail "unsafe SDX SDK library name: $sdk_name"
 sdk_library=$SDX_JNI_DIR/$sdk_name
 sdk_member=jni/arm64-v8a/$sdk_name
 grep -Fxq "$sdk_member" "$SDX_ARCHIVE_NAMES" || fail "SDX SDK archive is missing native member $sdk_member"
 ARCHIVE_MEMBER_HASH=$(unzip -p "$SDX_ARCHIVE" "$sdk_member" | sha256sum) || fail "cannot hash archived SDX SDK library: $sdk_name"; ARCHIVE_MEMBER_HASH=${ARCHIVE_MEMBER_HASH%% *}
 SDK_HASH=$(sha256sum "$sdk_library") || fail "cannot hash SDX SDK library: $sdk_name"; SDK_HASH=${SDK_HASH%% *}
 [[ $SDK_HASH == "$ARCHIVE_MEMBER_HASH" ]] || fail "SDX SDK library differs from its immutable archive: $sdk_name"
 case "$sdk_name" in
  libjnitokenizers.so|libtokenizers_ffi.so|libtokenizers_wrapper.so)
   # These libraries intentionally come from the normalized provider AAR so its
   # native ABI cannot drift from the TokenizersNative classes in classes.jar.
   continue
   ;;
 esac
 [[ -n ${LIBS[$sdk_name]:-} ]] || fail "APK is missing SDX SDK library: $sdk_name"
 APK_HASH=$(sha256sum "${LIBS[$sdk_name]}") || fail "cannot hash packaged SDX SDK library: $sdk_name"; APK_HASH=${APK_HASH%% *}
 [[ $SDK_HASH == "$APK_HASH" ]] || fail "APK SDX SDK library differs from the selected SDK: $sdk_name"
done

ACTUAL_SDX_NATIVE_BYTES=$TMP/sdx-sdk-native-bytes.actual
for sdk_name in "${SDX_LIBRARY_NAMES[@]}"; do
 SDK_MEMBER_HASH=$(sha256sum "$SDX_JNI_DIR/$sdk_name") || fail "cannot hash SDX SDK native member: $sdk_name"
 printf '%s %s\n' "${SDK_MEMBER_HASH%% *}" "$sdk_name"
done >"$ACTUAL_SDX_NATIVE_BYTES"
cmp -s "$ACTUAL_SDX_NATIVE_BYTES" "$SDX_NATIVE_BYTES" ||
  fail "SDX SDK native closure differs from its provenance byte manifest"

[[ -n $DEXDUMP ]] || fail "dexdump required for flavor metadata audit"
mapfile -t DEX < <(find "$TMP/apk" -maxdepth 1 -type f -name 'classes*.dex' -print|sort)
[[ ${#DEX[@]} -gt 0 ]] || fail "APK has no DEX"
"$DEXDUMP" -d "${DEX[@]}" > "$TMP/dexdump" || fail "DEX audit failed"
BUILD_CONFIG=$("$APKANALYZER" dex code --class "$APPLICATION.BuildConfig" "$APK") ||
  fail "cannot decompile application BuildConfig"
for FIELD in \
  ".field public static final APPLICATION_ID:Ljava/lang/String; = \"$PACKAGE\"" \
  ".field public static final FLAVOR:Ljava/lang/String; = \"$FLAVOR\"" \
  ".field public static final ACCELERATOR_PROVIDER:Ljava/lang/String; = \"$PROVIDER\"" \
  ".field public static final SDX_TARGET_PROFILE:Ljava/lang/String; = \"$TARGET_PROFILE\"" \
  ".field public static final APK_BUILD_ID:Ljava/lang/String; = \"$EXPECTED_BUILD_ID\"" \
  ".field public static final SOURCE_RUNTIME_AAR_SHA256:Ljava/lang/String; = \"$EXPECTED_SOURCE_RUNTIME_AAR_SHA256\"" \
  ".field public static final RUNTIME_PROVENANCE_SHA256:Ljava/lang/String; = \"$EXPECTED_RUNTIME_PROVENANCE_SHA256\"" \
  ".field public static final SDX_AOT_PROVENANCE_SHA256:Ljava/lang/String; = \"$EXPECTED_SDX_AOT_PROVENANCE_SHA256\"" \
  ".field public static final DEVICE_ONLY:Z = true"; do
  grep -Fxq "$FIELD" <<<"$BUILD_CONFIG" ||
    fail "BuildConfig field is missing or incorrect: $FIELD"
done

APPLICATION_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.KompileChatApplication "$APK") ||
  fail "cannot decompile application bootstrap"
for contract in 'attachBaseContext' 'AndroidJavaCppMemoryPolicy' 'install'; do
  grep -Fq "$contract" <<<"$APPLICATION_CODE" ||
    fail "application bootstrap is missing early JavaCPP memory policy contract: $contract"
done
JAVACPP_MEMORY_POLICY_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.AndroidJavaCppMemoryPolicy "$APK") ||
  fail "cannot decompile Android JavaCPP memory policy"
JAVACPP_MEMORY_FUNCTIONS_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.AndroidJavaCppMemoryPolicyKt "$APK") ||
  fail "cannot decompile Android JavaCPP memory policy functions"
for contract in \
  'org.bytedeco.javacpp.maxbytes' \
  'org.bytedeco.javacpp.maxphysicalbytes' \
  'ActivityManager' \
  'calculateAndroidJavaCppMemoryLimits'; do
  grep -Fq "$contract" <<<"$JAVACPP_MEMORY_POLICY_CODE$JAVACPP_MEMORY_FUNCTIONS_CODE" ||
    fail "Android JavaCPP memory policy is missing packaged contract: $contract"
done

# A complete AAR is insufficient if D8 never carried its loader hierarchy into
# the application. Audit the actual APK DEX closure that ART will resolve.
DEX_CLASSES=(
  "Lai/kompile/chat/local/android/acquisition/HuggingFaceGgmlAcquisition;"
  "Lai/kompile/chat/local/android/graph/KompileGraphNative;"
  "Lai/kompile/chat/local/android/model/AcceleratedChatModelAndroid;"
  "Lai/kompile/chat/local/android/model/PlatformLocalChatModelFactory;"
  "Lai/kompile/chat/local/android/model/SdxGgufModelImporter;"
  "Lai/kompile/chat/local/android/model/SdxAndroidLlmLibrary;"
  "Lai/kompile/chat/local/android/model/SdxAndroidLlmAbi;"
  "Lai/kompile/chat/local/android/model/SdxAndroidLlmNative;"
  "Lai/kompile/chat/local/android/model/SdxModelPreparationClient;"
  "Lai/kompile/chat/local/android/model/SdxModelPreparationConnection;"
  "Lai/kompile/chat/local/android/model/SdxModelPreparationService;"
  "Lai/kompile/chat/local/android/model/PreparedModelPayload;"
  "Lorg/bytedeco/javacpp/Loader;"
  "Lorg/nd4j/dsp/model/HuggingFaceGgmlResolver;"
)
if [[ $VARIANT == tensorG5 ]]; then
  DEX_CLASSES+=(
    "Lorg/nd4j/dsp/runtime/presets/LiteRtLmPresets;"
    "Lorg/nd4j/dsp/runtime/litertlm/bindings/LiteRtLmNative;"
  )
else
  DEX_CLASSES+=(
    "Lai/kompile/chat/local/android/model/SdxPlatformChatSession;"
    "Lai/kompile/chat/local/android/model/SdxPlatformRuntimeOwner;"
    "Lai/kompile/chat/local/android/model/SdxRuntimeConnection;"
    "Lai/kompile/chat/local/android/model/SdxRuntimeProcessKt;"
    "Lai/kompile/chat/local/android/model/SdxRuntimeService;"
    'Lai/kompile/chat/local/android/model/SdxAndroidLlmAbi$ChunkCallback;'
    'Lai/kompile/chat/local/android/model/SdxAndroidLlmAbi$CancelCallback;'
    'Lai/kompile/chat/local/android/model/SdxAndroidLlmNative$ChunkCallback;'
    'Lai/kompile/chat/local/android/model/SdxAndroidLlmNative$CancelCallback;'
  )
fi
for descriptor in "${DEX_CLASSES[@]}"; do
  grep -Fq "$descriptor" "$TMP/dexdump" ||
    fail "APK DEX missing runtime loader class: $descriptor"
done
SDX_ANDROID_ABI_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.model.SdxAndroidLlmAbi "$APK") ||
  fail "cannot decompile Android SDX direct JNI adapter"
for contract in 'SdxAndroidLlmNative' 'sdxLlmRenderChatPrompt' 'sdxLlmGenerateStreaming'; do
  grep -Fq "$contract" <<<"$SDX_ANDROID_ABI_CODE" ||
    fail "R8 stripped or renamed Android SDX direct JNI adapter contract: $contract"
done
for forbidden_host_binding in 'SdxLlmNative' 'org.bytedeco.javacpp'; do
  ! grep -Fq "$forbidden_host_binding" <<<"$SDX_ANDROID_ABI_CODE" ||
    fail "Android SDX host adapter still crosses the JavaCPP boundary: $forbidden_host_binding"
done
SDX_DIRECT_JNI_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.model.SdxAndroidLlmNative "$APK") ||
  fail "cannot decompile Android SDX direct JNI declarations"
for contract in 'nativeCreateRuntime' 'nativePrepareGguf' \
  'nativeLoadCompiledModel' 'nativeGenerateStreaming'; do
  grep -Fq "$contract" <<<"$SDX_DIRECT_JNI_CODE" ||
    fail "R8 stripped or renamed Android SDX direct JNI contract: $contract"
done

# Prove the exact lifecycle that isolates both native execution domains. The direct
# host bridge prevents ART from seeding JavaCPP caches consumed by embedded Graal,
# while main-process open binds an important,
# death-supervised private service for the complete preparation, then unbinds and waits
# for the disposable importer process to vanish before opening the accelerator runtime.
GGUF_IMPORTER_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.model.SdxGgufModelImporter "$APK") ||
  fail "cannot decompile GGUF-to-SDZ ingestion boundary"
for contract in \
  'SdxModelPreparationClient' \
  'prepareInImporterProcess' \
  'sdxLlmPrepareGguf'; do
  grep -Fq "$contract" <<<"$GGUF_IMPORTER_CODE" ||
    fail "GGUF-to-SDZ ingestion boundary is missing contract: $contract"
done

CANONICAL_MODEL_OPEN_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.model.AcceleratedChatModelAndroid "$APK") ||
  fail "cannot decompile canonical SDZ model-open boundary"
for contract in \
  'SdxGgufModelImporter' \
  'PlatformLocalChatModelFactory' \
  'getCanonicalSdzPath'; do
  grep -Fq "$contract" <<<"$CANONICAL_MODEL_OPEN_CODE" ||
    fail "canonical SDZ model-open boundary is missing contract: $contract"
done
PREPARATION_CALL=$(grep -n -m1 'SdxGgufModelImporter.*prepare' <<<"$CANONICAL_MODEL_OPEN_CODE" || true)
ACCELERATOR_CALL=$(grep -n -m1 'PlatformLocalChatModelFactory.*open' <<<"$CANONICAL_MODEL_OPEN_CODE" || true)
[[ -n $PREPARATION_CALL && -n $ACCELERATOR_CALL ]] ||
  fail "model-open path does not expose GGUF ingestion and canonical SDZ open calls"
PREPARATION_CALL_LINE=${PREPARATION_CALL%%:*}
ACCELERATOR_CALL_LINE=${ACCELERATOR_CALL%%:*}
(( PREPARATION_CALL_LINE < ACCELERATOR_CALL_LINE )) ||
  fail "model-open path does not retire GGUF ingestion before canonical SDZ open"

MODEL_PREPARATION_CLIENT_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.model.SdxModelPreparationClient "$APK") ||
  fail "cannot decompile SDX model preparation client"
for contract in \
  'SdxModelPreparationConnection' \
  'awaitProcessExit' \
  '"/proc/'; do
  grep -Fq "$contract" <<<"$MODEL_PREPARATION_CLIENT_CODE" ||
    fail "SDX model preparation client is missing lifecycle contract: $contract"
done
MODEL_PREPARATION_CONNECTION_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.model.SdxModelPreparationConnection "$APK") ||
  fail "cannot decompile SDX model preparation service connection"
for contract in \
  'bindService' \
  'linkToDeath' \
  'unlinkToDeath' \
  'SdxModelPreparationService' \
  'sdx-importer-replies'; do
  grep -Fq "$contract" <<<"$MODEL_PREPARATION_CONNECTION_CODE" ||
    fail "SDX model preparation connection is missing bound-service contract: $contract"
done
for forbidden_path in acquireUnstableContentProviderClient ContentProviderClient; do
  ! grep -Fq "$forbidden_path" <<<"$MODEL_PREPARATION_CLIENT_CODE$MODEL_PREPARATION_CONNECTION_CODE" ||
    fail "SDX model preparation still contains the obsolete provider path: $forbidden_path"
done
MODEL_PREPARATION_WIRE_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.model.SdxModelPreparationProcessKt "$APK") ||
  fail "cannot decompile SDX model preparation wire contract"
for contract in \
  'buildSdxModelPreparationRequest' \
  'requireFrameworkOnlySdxWireBundle'; do
  grep -Fq "$contract" <<<"$MODEL_PREPARATION_WIRE_CODE" ||
    fail "SDX model preparation wire is missing framework-only contract: $contract"
done
for forbidden_wire in ResultReceiver putSerializable putParcelable; do
  ! grep -Fq "$forbidden_wire" <<<"$MODEL_PREPARATION_WIRE_CODE" ||
    fail "SDX model preparation wire contains an unsupported payload mechanism: $forbidden_wire"
done
MODEL_PREPARATION_SERVICE_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.model.SdxModelPreparationService "$APK") ||
  fail "cannot decompile SDX model preparation service"
for contract in \
  'sendingUid' \
  'prepareInImporterProcess' \
  'newSingleThreadExecutor' \
  'sdx-importer-owner' \
  'Landroid/os/Messenger;' \
  'killProcess'; do
  grep -Fq "$contract" <<<"$MODEL_PREPARATION_SERVICE_CODE" ||
    fail "SDX model preparation service is missing importer contract: $contract"
done

if [[ $VARIANT != tensorG5 ]]; then
  SDX_RUNTIME_OWNER_CODE=$("$APKANALYZER" dex code \
    --class ai.kompile.chat.local.android.model.SdxPlatformRuntimeOwner "$APK") ||
    fail "cannot decompile process-owned SDX runtime"
  for contract in \
    'SdxAndroidLlmLibrary' \
    'SdxAndroidLlmAbi' \
    'sdxLlmResolveModelBundle' \
    'sdxLlmLoadCompiledModel' \
    'sdxRuntimeProcessName'; do
    grep -Fq "$contract" <<<"$SDX_RUNTIME_OWNER_CODE" ||
      fail "process-owned SDX runtime is missing direct JNI ownership contract: $contract"
  done
  SDX_RUNTIME_SESSION_CODE=$("$APKANALYZER" dex code \
    --class 'ai.kompile.chat.local.android.model.SdxPlatformRuntimeOwner$Session' "$APK") ||
    fail "cannot decompile process-owned SDX generation session"
  for contract in 'sdxLlmRenderChatPrompt' 'sdxLlmGenerateStreaming' 'CancelCallback'; do
    grep -Fq "$contract" <<<"$SDX_RUNTIME_SESSION_CODE" ||
      fail "process-owned SDX session is missing direct JNI generation contract: $contract"
  done
  SDX_RUNTIME_IMPLEMENTATION_CODE="$SDX_RUNTIME_OWNER_CODE$SDX_RUNTIME_SESSION_CODE"
  for forbidden_runtime in \
    'Lorg/nd4j/dsp/runtime/SdxRuntime;->create' \
    'NativeTokenizer' \
    'SdxTextSession' \
    'SameDiff' \
    'fromFlatGraph'; do
    ! grep -Fq "$forbidden_runtime" <<<"$SDX_RUNTIME_IMPLEMENTATION_CODE" ||
      fail "process-owned SDX runtime uses legacy direct loader: $forbidden_runtime"
  done

  SDX_RUNTIME_PROXY_CODE=$("$APKANALYZER" dex code \
    --class ai.kompile.chat.local.android.model.SdxPlatformChatSession "$APK") ||
    fail "cannot decompile main-process SDX runtime proxy"
  for contract in 'SdxRuntimeConnection' 'sdxRuntimeProcessName' 'NativeOperationJournal'; do
    grep -Fq "$contract" <<<"$SDX_RUNTIME_PROXY_CODE" ||
      fail "main-process SDX runtime proxy is missing supervision contract: $contract"
  done
  ! grep -Fq 'SdxRuntime;->create' <<<"$SDX_RUNTIME_PROXY_CODE" ||
    fail "main-process SDX runtime proxy directly creates native runtime state"

  SDX_RUNTIME_CONNECTION_CODE=$("$APKANALYZER" dex code \
    --class ai.kompile.chat.local.android.model.SdxRuntimeConnection "$APK") ||
    fail "cannot decompile SDX runtime Binder supervisor"
  for contract in 'bindService' 'linkToDeath' 'unlinkToDeath' '"/proc/' 'SdxRuntimeService'; do
    grep -Fq "$contract" <<<"$SDX_RUNTIME_CONNECTION_CODE" ||
      fail "SDX runtime Binder supervisor is missing process-death contract: $contract"
  done

  SDX_RUNTIME_SERVICE_CODE=$("$APKANALYZER" dex code \
    --class ai.kompile.chat.local.android.model.SdxRuntimeService "$APK") ||
    fail "cannot decompile app-private SDX runtime service"
  for contract in 'SdxPlatformRuntimeOwner' 'newSingleThreadExecutor' 'sendingUid' 'Messenger' 'killProcess'; do
    grep -Fq "$contract" <<<"$SDX_RUNTIME_SERVICE_CODE" ||
      fail "app-private SDX runtime service is missing ownership contract: $contract"
  done

  SDX_RUNTIME_WIRE_CODE=$("$APKANALYZER" dex code \
    --class ai.kompile.chat.local.android.model.SdxRuntimeProcessKt "$APK") ||
    fail "cannot decompile SDX runtime wire and crash recovery"
  for contract in \
    'requireFrameworkOnlyRuntimeWireBundle' \
    'recoverAttemptAndPersist' \
    'findExitEvidence'; do
    grep -Fq "$contract" <<<"$SDX_RUNTIME_WIRE_CODE" ||
      fail "SDX runtime wire is missing framework-only crash-recovery contract: $contract"
  done
  for forbidden_wire in ResultReceiver putSerializable; do
    ! grep -Fq "$forbidden_wire" <<<"$SDX_RUNTIME_WIRE_CODE" ||
      fail "SDX runtime wire contains unsafe payload mechanism: $forbidden_wire"
  done
fi

if [[ $VARIANT == tensorG3 ]]; then
  TENSOR_G3_FACTORY_CODE=$("$APKANALYZER" dex code \
    --class ai.kompile.chat.local.android.model.PlatformLocalChatModelFactory "$APK") ||
    fail "cannot decompile Tensor G3 model factory"
  for contract in 'LOCAL_TENSOR_G3_NNAPI' 'sdx-tensor-g3-nnapi' 'SdxPlatformChatSession'; do
    grep -Fq "$contract" <<<"$TENSOR_G3_FACTORY_CODE" ||
      fail "Tensor G3 model factory is missing NNAPI plus ARM DSP contract: $contract"
  done
  for forbidden_factory in mobileVulkan mobileHexagon mobileMetal mobileCoreMlAne LiteRt; do
    ! grep -Fq "$forbidden_factory" <<<"$TENSOR_G3_FACTORY_CODE" ||
      fail "Tensor G3 model factory contains a non-NNAPI execution route: $forbidden_factory"
  done
fi

# INTERNET exists only for bounded public Hugging Face discovery and app-owned model
# transfer. Audit every direct reference from application code to common in-process
# network clients; no other application component may open a connection.
NETWORK_REFERENCE_TYPES=(
  java.net.HttpURLConnection
  java.net.URL
  java.net.URLConnection
  javax.net.ssl.HttpsURLConnection
  java.net.Socket
  java.net.ServerSocket
  java.net.DatagramSocket
  java.net.MulticastSocket
  java.nio.channels.SocketChannel
  java.nio.channels.ServerSocketChannel
  java.nio.channels.DatagramChannel
  android.app.DownloadManager
  okhttp3.OkHttpClient
  retrofit2.Retrofit
  android.webkit.WebView
)
for network_type in "${NETWORK_REFERENCE_TYPES[@]}"; do
  NETWORK_REFERENCE_TREE=$("$APKANALYZER" dex reference-tree \
    --references-to "$network_type" "$APK") ||
    fail "cannot audit APK references to $network_type"
  if [[ $network_type == java.net.HttpURLConnection ]]; then
    grep -Eq '^ ai[.]kompile[.]chat[.]local[.]android[.]acquisition[.]HuggingFaceGgmlAcquisition' \
      <<<"$NETWORK_REFERENCE_TREE" ||
      fail "APK is missing its bounded Hugging Face HTTP acquisition path"
  fi
  while IFS= read -r direct_reference; do
    case "$direct_reference" in
      ' ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition '*) ;;
      ' ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition$'*) ;;
      *) fail "network-capable application code escaped the Hugging Face allowlist: $direct_reference";;
    esac
  done < <(grep -E '^ ai[.]kompile[.]chat[.]local[.]android[.]' \
    <<<"$NETWORK_REFERENCE_TREE" || true)
done

HF_ACQUISITION_CODE=$("$APKANALYZER" dex code \
  --class ai.kompile.chat.local.android.acquisition.HuggingFaceGgmlAcquisition "$APK") ||
  fail "cannot decompile Hugging Face acquisition boundary"
for literal in '"huggingface.co"' '"/api/models/"' 'Ljava/net/HttpURLConnection;'; do
  grep -Fq "$literal" <<<"$HF_ACQUISITION_CODE" ||
    fail "Hugging Face acquisition boundary is missing required DEX contract: $literal"
done
HF_RESOLVER_CODE=$("$APKANALYZER" dex code \
  --class org.nd4j.dsp.model.HuggingFaceGgmlResolver "$APK") ||
  fail "cannot decompile Hugging Face repository resolver"
grep -Fq '"https://huggingface.co/api/models/"' <<<"$HF_RESOLVER_CODE" ||
  fail "Hugging Face resolver DEX is missing its public model API origin"

# The chat core also carries desktop routes this device build must not ship.
# The Android JavaCPP SDX binding is the required native boundary. Its old desktop JNA
# facade, remote HTTP client, and Java 11 HTTP stack must remain absent.
FORBIDDEN_DEX_CLASSES=(
  "Lai/kompile/chat/local/sdx/SdxChatModel;"
  "Lai/kompile/chat/local/sdx/SdxLlmAbi;"
  "Lcom/sun/jna/"
  "Lai/kompile/chat/local/RemoteChatModel;"
  "Ljava/net/http/"
)
for descriptor in "${FORBIDDEN_DEX_CLASSES[@]}"; do
  ! grep -Fq "$descriptor" "$TMP/dexdump" ||
    fail "host-only class reached the APK DEX: $descriptor"
done

ASH=$(sha256sum "$APK"); ASH=${ASH%% *}; RSH=$(sha256sum "$AAR"); RSH=${RSH%% *}
printf 'verified offline APK: variant=%s package=%s buildId=%s versionCode=%s apkSha256=%s runtimeAarSha256=%s sourceRuntimeAarSha256=%s runtimeProvenanceSha256=%s sdxAotProvenanceSha256=%s\n' \
  "$VARIANT" "$PACKAGE" "$EXPECTED_BUILD_ID" "$EXPECTED_VERSION_CODE" "$ASH" "$RSH" \
  "$EXPECTED_SOURCE_RUNTIME_AAR_SHA256" "$EXPECTED_RUNTIME_PROVENANCE_SHA256" "$EXPECTED_SDX_AOT_PROVENANCE_SHA256"
