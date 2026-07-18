#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
fail(){ printf 'verify-offline-apk: %s\n' "$*" >&2; exit 1; }
need(){ [[ $# -ge 2 && -n $2 ]] || fail "missing value for $1"; }
APK= VARIANT= CONFIG= ANDROID_SDK= ANDROID_NDK= RUNTIME_AAR_OVERRIDE=
while [[ $# -gt 0 ]]; do case "$1" in
 --apk) need "$@"; APK=$2; shift 2;; --variant) need "$@"; VARIANT=$2; shift 2;;
 --config) need "$@"; CONFIG=$2; shift 2;; --android-sdk) need "$@"; ANDROID_SDK=$2; shift 2;;
 --android-ndk) need "$@"; ANDROID_NDK=$2; shift 2;;
 --runtime-aar) need "$@"; RUNTIME_AAR_OVERRIDE=$2; shift 2;;
 -h|--help) echo "Usage: verify-offline-apk.sh --apk FILE --variant NAME --config FILE --android-sdk DIR --android-ndk DIR [--runtime-aar FILE]"; exit 0;;
 *) fail "unknown argument: $1";; esac; done
[[ -n $APK && -n $VARIANT && -n $CONFIG && -n $ANDROID_SDK && -n $ANDROID_NDK ]] || fail "all five options are required"
[[ $VARIANT =~ ^(vulkan|hexagon|tensorG3|tensorG5)$ ]] || fail "unsupported APK variant: $VARIANT"
[[ -s $APK && -f $APK ]] || fail "APK not found or empty: $APK"
[[ -s $CONFIG && -f $CONFIG ]] || fail "config not found or empty: $CONFIG"
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
TMP=$(mktemp -d "${TMPDIR:-/tmp}/verify-offline-apk.XXXXXXXX")
trap 'rm -rf -- "$TMP"' EXIT HUP INT TERM

mapfile -t BTDIRS < <(find "$ANDROID_SDK/build-tools" -mindepth 1 -maxdepth 1 -type d -print | sort -V -r)
AAPT2= APKSIGNER= DEXDUMP=
for d in "${BTDIRS[@]}"; do [[ -x $d/aapt2 && -x $d/apksigner ]] || continue
 AAPT2=$d/aapt2; APKSIGNER=$d/apksigner; [[ -x $d/dexdump ]] && DEXDUMP=$d/dexdump; break; done
[[ -n $AAPT2 && -n $APKSIGNER ]] || fail "aapt2/apksigner not found in one build-tools version"
READELF=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
[[ -x $READELF ]] || fail "llvm-readelf not found or not executable: $READELF"

CONTRACT=$TMP/contract
cmake -DMODE=config -DINPUT="$CONFIG" -DVARIANT="$VARIANT" -DOUTPUT="$CONTRACT" -P "$VALIDATOR" >/dev/null
declare APPLICATION PACKAGE_SUFFIX FLAVOR RUNTIME_AAR BACKEND GPU_TARGET MIN_SDK PROVIDER TARGET_PROFILE DSP_SERVICE REQUIRED_LIBS FORBIDDEN_LIBS
while IFS='=' read -r k v; do case $k in
 APPLICATION|PACKAGE_SUFFIX|FLAVOR|RUNTIME_AAR|BACKEND|GPU_TARGET|MIN_SDK|PROVIDER|TARGET_PROFILE|DSP_SERVICE|REQUIRED_LIBS|FORBIDDEN_LIBS) printf -v "$k" %s "$v";;
 *) fail "unknown validator output: $k";; esac; done < "$CONTRACT"
for k in APPLICATION PACKAGE_SUFFIX FLAVOR RUNTIME_AAR BACKEND MIN_SDK PROVIDER TARGET_PROFILE REQUIRED_LIBS FORBIDDEN_LIBS; do
 [[ -n ${!k:-} ]] || fail "validator omitted $k"; done

NAMES=$TMP/apk.names
unzip -Z1 "$APK" > "$NAMES" || fail "APK is not a readable ZIP"
[[ -s $NAMES ]] || fail "APK is empty"
[[ $(wc -l < "$NAMES") -eq $(sort -u "$NAMES"|wc -l) ]] || fail "APK has duplicate members"
while IFS= read -r n; do [[ -n $n && $n != /* && $n != *\\* ]] || fail "unsafe APK path: $n"
 case "/$n/" in */../*) fail "unsafe APK path: $n";; esac; done < "$NAMES"
unzip -qq "$APK" -d "$TMP/apk" || fail "APK extraction failed"

"$APKSIGNER" verify --verbose "$APK" >/dev/null || fail "APK signature audit failed"
"$AAPT2" dump permissions "$APK" > "$TMP/permissions" || fail "APK permission audit failed"
! grep -Fq android.permission.INTERNET "$TMP/permissions" || fail "offline APK requests INTERNET"
"$AAPT2" dump badging "$APK" > "$TMP/badging" || fail "APK identity audit failed"
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$TMP/manifest-tree" ||
  fail "APK manifest audit failed"
grep -Eq 'extractNativeLibs[^=]*=true' "$TMP/manifest-tree" ||
  fail "APK does not extract its bundled native runtime"
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
grep -Eq "^package: name='$PACKAGE'([[:space:]]|$)" "$TMP/badging" || fail "wrong APK package identity (expected $PACKAGE)"
grep -Eq "sdkVersion:'${MIN_SDK}'|minSdkVersion[^0-9]*${MIN_SDK}([^0-9]|$)" "$TMP/badging" || fail "APK minSdk is not ${MIN_SDK}"

mapfile -t ENTRIES < <(grep -E '^lib/[^/]+/[^/]+\.so$' "$NAMES" || true)
[[ ${#ENTRIES[@]} -gt 0 ]] || fail "APK has no native libraries"
declare -A LIBS=()
for e in "${ENTRIES[@]}"; do [[ $e == lib/arm64-v8a/* ]] || fail "non-arm64 ABI: $e"; LIBS[${e##*/}]=$TMP/apk/$e; done
for g in libjnikompile_graph.so libkompile_reasoning_android.so; do [[ -n ${LIBS[$g]:-} ]] || fail "missing graph AOT library: $g"; done
IFS=, read -ra REQUIRED <<< "$REQUIRED_LIBS"; for l in "${REQUIRED[@]}"; do [[ -n ${LIBS[$l]:-} ]] || fail "missing provider library: $l"; done
LOWER=$(printf '%s\n' "${!LIBS[@]}"|tr '[:upper:]' '[:lower:]')
IFS=, read -ra FORBIDDEN <<< "$FORBIDDEN_LIBS"
for x in "${FORBIDDEN[@]}" libgfortran libquadmath ld-linux libstdc++.so.6; do ! grep -Fqi "$x" <<<"$LOWER" || fail "forbidden library: $x"; done
case $VARIANT in
 vulkan) [[ -n ${LIBS[libnd4jvulkan.so]:-} ]]; ! grep -Eqi 'litert|hexagon|nnapi|neuralnetworks' <<<"$LOWER";;
 hexagon) [[ -n $DSP_SERVICE && -n ${LIBS[$DSP_SERVICE]:-} ]]; ! grep -Eqi 'litert|vulkan|nnapi|neuralnetworks' <<<"$LOWER";;
 tensorG3) [[ -n ${LIBS[libnd4jnnapi.so]:-} ]]; ! grep -Eqi 'litert|vulkan|hexagon' <<<"$LOWER";;
 tensorG5) [[ -n ${LIBS[liblitert-lm.so]:-} ]]; ! grep -Eqi 'vulkan|hexagon|nnapi|neuralnetworks' <<<"$LOWER";;
esac || fail "wrong or mixed provider native runtime"

is_android_system_library(){
 case "$1" in
  libc.so|libdl.so|libm.so|liblog.so|libz.so|libandroid.so|libvulkan.so|libEGL.so|libGLESv2.so|libGLESv3.so|libjnigraphics.so|libmediandk.so|libnativewindow.so|libOpenSLES.so|libaaudio.so) return 0;;
  libneuralnetworks.so) [[ $VARIANT == tensorG3 ]]; return;;
  libcdsprpc.so) [[ $VARIANT == hexagon ]]; return;;
 esac
 return 1
}

is_hexagon_dsp_system_library(){
 case "$1" in libc.so|libgcc.so) return 0;; esac
 return 1
}

audit_elf(){ local b=$1 f=$2 h d s needed undefined dependency_surface dep
 h=$("$READELF" -h -l "$f") || fail "ELF header audit failed: $b"
 d=$("$READELF" -d "$f") || fail "ELF dynamic audit failed: $b"
 s=$("$READELF" --dyn-syms --wide "$f") || fail "ELF symbol audit failed: $b"
 if [[ -n $DSP_SERVICE && $b == "$DSP_SERVICE" ]]; then
   [[ $VARIANT == hexagon ]] || fail "Hexagon DSP service leaked into $VARIANT APK"
   grep -Eq 'Machine:.*Hexagon' <<<"$h" && grep -Eq 'Type:[[:space:]]+DYN' <<<"$h" ||
     fail "not Qualcomm Hexagon DYN: $b"
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
 grep -Fq AArch64 <<<"$h" && grep -Eq 'Type:[[:space:]]+DYN' <<<"$h" || fail "not AArch64 DYN: $b"
 grep -Fq GNU_RELRO <<<"$h" && grep -Fq BIND_NOW <<<"$d" || fail "ELF hardening missing: $b"
 ! grep -Eq '[(]RPATH[)]|TEXTREL' <<<"$d" || fail "unsafe ELF tag: $b"
 if grep -Fq '(RUNPATH)' <<<"$d"; then
   # LiteRT-LM resolves its separately packaged constraint provider beside the
   # runtime. Permit only that non-escaping local lookup; reject every other
   # provider and every absolute, parent-relative, or multi-entry RUNPATH.
   [[ $VARIANT == tensorG5 ]] || fail "unexpected RUNPATH: $b"
   ! grep -F '(RUNPATH)' <<<"$d" | grep -Evq 'Library runpath: \[\$ORIGIN\][[:space:]]*$' || fail "unsafe RUNPATH: $b"
 fi
 needed=$(grep -oE 'Shared library: \[[^]]+\]' <<<"$d"|tr '\n' ' '||true)
 undefined=$(grep -E '[[:space:]]UND[[:space:]]' <<<"$s" || true)
 dependency_surface=$(printf '%s\n%s\n%s' "$h" "$d" "$undefined"|tr '[:upper:]' '[:lower:]')
 # Audit runtime dependencies and unresolved imports. Public compatibility symbols
 # may retain backend names even when that backend is compiled out.
 for x in openblas gfortran quadmath nd4jcpu sdx_cpu ld-linux linuxbrew; do ! grep -Fq "$x" <<<"$dependency_surface" || fail "forbidden ELF dependency $x in $b"; done
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
 fi
 if [[ $VARIANT == tensorG3 && $b == libnd4jnnapi.so ]]; then
   grep -Fq libneuralnetworks.so <<<"$needed" || fail "NNAPI system dependency missing"
   grep -Eq 'ANeuralNetworks[A-Za-z0-9_]+' <<<"$s" || fail "NNAPI symbols missing"
 fi
}
for b in "${!LIBS[@]}"; do audit_elf "$b" "${LIBS[$b]}"; done

ASSETS=$TMP/apk/assets/offline-assets.json
[[ -s $ASSETS ]] || fail "offline asset manifest missing"
cmake -DMODE=assets -DINPUT="$ASSETS" -DVARIANT="$VARIANT" -DEXPECTED_BACKEND="$BACKEND" -DASSET_ROOT="$TMP/apk/assets" -P "$VALIDATOR" >/dev/null
grep -Eq '^assets/.+\.kgraph$' "$NAMES" || fail "graph AOT asset missing"

case $VARIANT in tensorG3) AD=tensor-g3;; tensorG5) AD=tensor-g5;; *) AD=$VARIANT;; esac
if [[ -n $RUNTIME_AAR_OVERRIDE ]]; then
 AAR=$RUNTIME_AAR_OVERRIDE
else
 CDIR=$(cd "$(dirname "$CONFIG")" && pwd -P)
 AAR=$CDIR/app/libs/$AD/$RUNTIME_AAR
fi
[[ -s $AAR ]] || fail "configured runtime AAR not found: $AAR"
AN=$TMP/aar.names; unzip -Z1 "$AAR" > "$AN" || fail "bad runtime AAR"
[[ $(wc -l < "$AN") -eq $(sort -u "$AN"|wc -l) ]] || fail "AAR has duplicate members"
for e in AndroidManifest.xml binding.json classes.jar; do grep -Fxq "$e" "$AN" || fail "AAR missing $e"; unzip -p "$AAR" "$e" > "$TMP/$e"; [[ -s $TMP/$e ]] || fail "AAR has empty $e"; done
cmake -DMODE=binding -DINPUT="$TMP/binding.json" -DVARIANT="$VARIANT" -P "$VALIDATOR" >/dev/null
unzip -Z1 "$TMP/classes.jar" > "$TMP/classes.names" || fail "bad classes.jar"
CLASSES=(org/nd4j/dsp/model/SdxModelCache.class org/nd4j/dsp/model/SdxTargetProfile.class)
if [[ $VARIANT == tensorG5 ]]; then CLASSES+=(org/nd4j/dsp/runtime/litertlm/SdxLiteRtLmChatSession.class org/nd4j/dsp/runtime/presets/LiteRtLmPresets.class org/nd4j/dsp/runtime/litertlm/bindings/LiteRtLmNative.class)
else CLASSES+=(org/nd4j/dsp/runtime/SdxRuntime.class org/nd4j/dsp/runtime/SdxTextSession.class org/nd4j/dsp/runtime/presets/SdxRuntimePresets.class org/nd4j/dsp/runtime/bindings/SdxNative.class org/eclipse/deeplearning4j/tokenizers/NativeTokenizer.class org/eclipse/deeplearning4j/tokenizers/presets/TokenizersPresets.class org/eclipse/deeplearning4j/tokenizers/presets/TokenizersHelper.class org/eclipse/deeplearning4j/tokenizers/bindings/TokenizersNative.class); fi
for c in "${CLASSES[@]}"; do grep -Fxq "$c" "$TMP/classes.names" || fail "AAR missing API class: $c"; done
mapfile -t AE < <(grep -E '^jni/[^/]+/[^/]+\.so$' "$AN"||true); [[ ${#AE[@]} -gt 0 ]] || fail "AAR has no native libs"
declare -A AL=(); for e in "${AE[@]}"; do [[ $e == jni/arm64-v8a/* ]] || fail "AAR has non-arm64 ABI"; AL[${e##*/}]=1; done
for l in "${REQUIRED[@]}"; do [[ -n ${AL[$l]:-} ]] || fail "AAR missing $l"; done
if [[ $VARIANT == tensorG5 ]]; then [[ -n ${AL[libjnilitertlm.so]:-} ]] || fail "AAR missing libjnilitertlm.so"
else for l in libjnisdx.so libjnitokenizers.so libtokenizers_wrapper.so libtokenizers_ffi.so; do [[ -n ${AL[$l]:-} ]] || fail "AAR missing $l"; done; fi
for l in "${!AL[@]}"; do
 [[ -n ${LIBS[$l]:-} ]] || fail "AAR native library not packaged: $l"
 AH=$(unzip -p "$AAR" "jni/arm64-v8a/$l" | sha256sum) || fail "cannot hash AAR native library: $l"
 AH=${AH%% *}; PH=$(sha256sum "${LIBS[$l]}") || fail "cannot hash APK native library: $l"; PH=${PH%% *}
 [[ $AH == "$PH" ]] || fail "APK native library differs from configured AAR: $l"
done

[[ -n $DEXDUMP ]] || fail "dexdump required for flavor metadata audit"
mapfile -t DEX < <(find "$TMP/apk" -maxdepth 1 -type f -name 'classes*.dex' -print|sort)
[[ ${#DEX[@]} -gt 0 ]] || fail "APK has no DEX"
"$DEXDUMP" -d "${DEX[@]}" > "$TMP/dexdump" || fail "DEX audit failed"
for v in "$FLAVOR" "$PROVIDER" "$TARGET_PROFILE"; do grep -Fq "$v" "$TMP/dexdump" || fail "missing flavor/provider metadata: $v"; done
grep -Fq DEVICE_ONLY "$TMP/dexdump" || fail "missing DEVICE_ONLY metadata"

# A complete AAR is insufficient if D8 never carried its loader hierarchy into
# the application. Audit the actual APK DEX closure that ART will resolve.
DEX_CLASSES=(
  "Lai/kompile/chat/local/android/graph/KompileGraphNative;"
  "Lorg/bytedeco/javacpp/Loader;"
)
if [[ $VARIANT == tensorG5 ]]; then
  DEX_CLASSES+=(
    "Lorg/nd4j/dsp/runtime/presets/LiteRtLmPresets;"
    "Lorg/nd4j/dsp/runtime/litertlm/bindings/LiteRtLmNative;"
  )
else
  DEX_CLASSES+=(
    "Lorg/nd4j/dsp/runtime/presets/SdxRuntimePresets;"
    "Lorg/nd4j/dsp/runtime/bindings/SdxNative;"
    "Lorg/eclipse/deeplearning4j/tokenizers/NativeTokenizer;"
    "Lorg/eclipse/deeplearning4j/tokenizers/presets/TokenizersPresets;"
    "Lorg/eclipse/deeplearning4j/tokenizers/presets/TokenizersHelper;"
    "Lorg/eclipse/deeplearning4j/tokenizers/bindings/TokenizersNative;"
  )
fi
for descriptor in "${DEX_CLASSES[@]}"; do
  grep -Fq "$descriptor" "$TMP/dexdump" ||
    fail "APK DEX missing runtime loader class: $descriptor"
done

ASH=$(sha256sum "$APK"); ASH=${ASH%% *}; RSH=$(sha256sum "$AAR"); RSH=${RSH%% *}
printf 'verified offline APK: variant=%s package=%s apkSha256=%s runtimeAarSha256=%s\n' "$VARIANT" "$PACKAGE" "$ASH" "$RSH"
