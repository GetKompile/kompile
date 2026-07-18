#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
die(){ printf 'verify-offline-graph-chat-bundle: %s\n' "$*" >&2; exit 1; }
need(){ command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
bundle="$script_dir/build/kompile-offline-graph-chat-full.zip"; android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"; android_ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
while (($#)); do case "$1" in --bundle) bundle=$2; shift 2;; --android-sdk) android_sdk=$2; shift 2;; --android-ndk) android_ndk=$2; shift 2;; --help) printf 'Usage: %s [--bundle ZIP] [--android-sdk DIR] [--android-ndk DIR]\n' "$0"; exit 0;; *) die "unknown option: $1";; esac; done
for c in cmake unzip zipinfo sha256sum sort cmp find readelf cut; do need "$c"; done
[[ "$(uname -s)" == "Linux" ]] ||
  die "this bundle verifier requires Linux with GNU coreutils/findutils"
[[ -f "$bundle" && -s "$bundle" ]] || die "bundle missing or empty: $bundle"
sidecar="$bundle.sha256"; [[ -f "$sidecar" ]] || die "bundle checksum sidecar missing: $sidecar"
expected="$(cut -d ' ' -f 1 "$sidecar")"; side_name="$(cut -d ' ' -f 3- "$sidecar")"
[[ "$expected" =~ ^[0-9a-f]{64}$ && "$side_name" == "$(basename -- "$bundle")" ]] || die "malformed bundle checksum sidecar"
actual="$(sha256sum "$bundle" | cut -d ' ' -f 1)"; [[ "$actual" == "$expected" ]] || die "bundle checksum mismatch"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/verify-offline-graph-chat.XXXXXX")"; cleanup(){ rm -rf -- "$tmp"; }; trap cleanup EXIT HUP INT TERM
unzip -Z1 "$bundle" > "$tmp/names"; [[ -s "$tmp/names" ]] || die "empty ZIP"
LC_ALL=C sort "$tmp/names" > "$tmp/sorted"; cmp -s "$tmp/names" "$tmp/sorted" || die "ZIP members are not deterministically sorted"
uniq -d "$tmp/sorted" > "$tmp/duplicates"; [[ ! -s "$tmp/duplicates" ]] || die "ZIP contains duplicate members"
zipinfo -T -l "$bundle" > "$tmp/zipinfo"
while IFS= read -r line; do [[ "$line" == -* ]] || continue; perm="${line%% *}"; [[ "$perm" == -rw-r--r-- || "$perm" == -rwxr-xr-x ]] || die "ZIP member mode is not normalized: $perm"; [[ "$line" == *19800101.000000* ]] || die "ZIP member timestamp is not deterministic"; done < "$tmp/zipinfo"
while IFS= read -r name; do [[ -n "$name" && "$name" != /* && "$name" != *\\* ]] || die "unsafe ZIP member: $name"; [[ ! "$name" =~ (^|/)\.\.(/|$) ]] || die "unsafe ZIP member: $name"; [[ "${name: -1}" != / ]] || die "directory ZIP member forbidden"; done < "$tmp/names"
unzip -qq "$bundle" -d "$tmp/stage"
cmake -DMODE=verify -DSTAGE="$tmp/stage" -P "$script_dir/cmake/OfflineGraphChatBundle.cmake"
required=(
 README.md MANIFEST.json
 artifacts/contracts/accelerators.json
 artifacts/android/kompile-offline-graph-chat-vulkan.apk
 artifacts/android/kompile-offline-graph-chat-hexagon.apk
 artifacts/android/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk
 artifacts/android/kompile-offline-graph-chat-tensor-g5.apk
 artifacts/runtime/sdx-runtime-android-arm64-vulkan.aar
 artifacts/runtime/sdx-runtime-android-arm64-hexagon.aar
 artifacts/runtime/sdx-runtime-android-arm64-tensor-g3.aar
 artifacts/runtime/sdx-chat-runtime-android-arm64-google-tensor-g5.aar
 artifacts/graph/kompile-graph-reasoning-native-sdk.zip
 artifacts/graph/android-arm64-v8a/libkompile_reasoning_android.so
 artifacts/graph/android-arm64-v8a/libjnikompile_graph.so
 artifacts/graph/include/kompile_reasoning.h
 artifacts/graph/fixture.kgraph
 artifacts/models/canonical-mlp.sdz
 artifacts/models/canonical-mlp.README.md
 source/kompile-chat-local/mobile/android/tools/verify-offline-apk.sh
 source/kompile-chat-local/mobile/android/tools/verify-offline-apk-json.cmake
 source/kompile-chat-local/mobile/android/app/src/hexagon/AndroidManifest.xml
 source/kompile-chat-local/kompile-chat-local-core/src/main/java/ai/kompile/chat/local/ProjectArchiveInstaller.java
 source/kompile/kompile-app/kompile-models/kompile-model-staging/src/main/java/ai/kompile/staging/sdx/AtomicProjectPublisher.java
 source/kompile/kompile-app/kompile-models/kompile-model-staging/src/main/java/ai/kompile/staging/sdx/SdxProjectOutputService.java
 source/kompile/kompile-app/kompile-models/kompile-model-staging/src/main/frontend/src/app/components/download-model/download-model.component.ts
 source/kompile/kompile-app/kompile-data/kompile-project-store/src/main/java/ai/kompile/project/archive/ProjectArchiveService.java
 source/kompile/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/unified/UnifiedGraph.java
 source/deeplearning4j/nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-model/src/main/java/org/nd4j/dsp/model/SdxModelCompiler.java
 source/deeplearning4j/nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-model/src/main/java/org/nd4j/dsp/model/SdxNnapiDevicePolicy.java
 source/deeplearning4j/nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-model/src/main/java/org/nd4j/dsp/model/SdxQuantizationContract.java
 source/deeplearning4j/libnd4j/tools/mobile/verify-android-accelerator-aar.sh
 source/deeplearning4j/libnd4j/cmake/HexagonFastRpc.cmake
 source/deeplearning4j/libnd4j/cmake/SdxRuntimePackage.cmake
 source/deeplearning4j/libnd4j/include/graph/hexagon/runtime/SdxHexagonHostAdapter.cpp
 source/deeplearning4j/libnd4j/include/graph/hexagon/runtime/SdxHexagonDspService.c
)
for member in "${required[@]}"; do [[ -f "$tmp/stage/$member" && -s "$tmp/stage/$member" ]] || die "required member missing or empty: $member"; done
for apk in "$tmp/stage"/artifacts/android/*.apk; do
 sum="$apk.sha256"; [[ -f "$sum" ]] || die "APK checksum missing"; ah="$(cut -d ' ' -f 1 "$sum")"; an="$(cut -d ' ' -f 3- "$sum")"
 [[ "$ah" =~ ^[0-9a-f]{64}$ && "$an" == "$(basename -- "$apk")" ]] || die "malformed APK checksum"
 got="$(sha256sum "$apk" | cut -d ' ' -f 1)"; [[ "$got" == "$ah" ]] || die "APK checksum mismatch"
 unzip -tqq "$apk" >/dev/null || die "invalid APK"; unzip -Z1 "$apk" > "$tmp/apk.names"; LC_ALL=C sort "$tmp/apk.names" | uniq -d > "$tmp/apk.dups"; [[ ! -s "$tmp/apk.dups" ]] || die "APK duplicate member"
 while IFS= read -r n; do [[ "$n" != /* && "$n" != *\\* && ! "$n" =~ (^|/)\.\.(/|$) ]] || die "unsafe APK member"; done < "$tmp/apk.names"
 mapfile -t native < <(grep '^lib/' "$tmp/apk.names" || true); ((${#native[@]})) || die "APK has no native libraries"; for n in "${native[@]}"; do [[ "$n" == lib/arm64-v8a/* ]] || die "APK contains non-arm64 ABI: $n"; done
done
find_tool(){ local root=$1 name=$2 found; [[ -d "$root" ]] || die "tool root missing: $root"; found="$(find "$root" \( -type f -o -type l \) -name "$name" -print | LC_ALL=C sort -r | head -n1)"; [[ -n "$found" && -x "$found" ]] || die "executable tool not found: $name"; printf '%s\n' "$found"; }
[[ -n "$android_sdk" ]] || die "Android SDK required"; [[ -n "$android_ndk" ]] || die "Android NDK required"
aapt2="$(find_tool "$android_sdk/build-tools" aapt2)"; apksigner="$(find_tool "$android_sdk/build-tools" apksigner)"; ndk_readelf="$(find_tool "$android_ndk/toolchains/llvm/prebuilt" llvm-readelf)"
apk_verifier="$tmp/stage/source/kompile-chat-local/mobile/android/tools/verify-offline-apk.sh"
accelerator_config="$tmp/stage/artifacts/contracts/accelerators.json"
variants=(vulkan hexagon tensorG3 tensorG5)
apk_names=(
 kompile-offline-graph-chat-vulkan.apk
 kompile-offline-graph-chat-hexagon.apk
 kompile-offline-graph-chat-tensor-g3-pixel-8a.apk
 kompile-offline-graph-chat-tensor-g5.apk
)
aar_names=(
 sdx-runtime-android-arm64-vulkan.aar
 sdx-runtime-android-arm64-hexagon.aar
 sdx-runtime-android-arm64-tensor-g3.aar
 sdx-chat-runtime-android-arm64-google-tensor-g5.aar
)
for i in "${!variants[@]}"; do
 bash "$apk_verifier" \
  --apk "$tmp/stage/artifacts/android/${apk_names[$i]}" \
  --variant "${variants[$i]}" \
  --config "$accelerator_config" \
  --runtime-aar "$tmp/stage/artifacts/runtime/${aar_names[$i]}" \
  --android-sdk "$android_sdk" \
  --android-ndk "$android_ndk"
done
for apk in "$tmp/stage"/artifacts/android/*.apk; do "$apksigner" verify --verbose "$apk" >/dev/null || die "APK signature failure"; permissions="$("$aapt2" dump permissions "$apk")"; [[ "$permissions" != *android.permission.INTERNET* ]] || die "offline APK requests INTERNET"; done
verify_elf(){ local so=$1 base dyn sym combined needed allowed; base="$(basename -- "$so")"; "$ndk_readelf" -h "$so" | grep -q 'Class:.*ELF64' || die "$base is not ELF64"; "$ndk_readelf" -h "$so" | grep -q 'Data:.*little endian' || die "$base endian invalid"; "$ndk_readelf" -h "$so" | grep -Eq 'Type:.*DYN' || die "$base is not shared"; "$ndk_readelf" -h "$so" | grep -Eq 'Machine:.*AArch64' || die "$base is not AArch64"; dyn="$("$ndk_readelf" -d "$so")"; sym="$("$ndk_readelf" -Ws "$so")"; combined="${dyn,,} ${sym,,}"; for bad in rpath runpath textrel openblas gfortran quadmath libnd4j sdx_cpu ld-linux linuxbrew; do [[ "$combined" != *"$bad"* ]] || die "$base forbidden marker: $bad"; done; needed="$(printf '%s\n' "$dyn" | grep -o 'Shared library: \[[^]]*\]' | sed 's/.*\[//;s/\]//' | LC_ALL=C sort)"; if [[ "$base" == libkompile_reasoning_android.so ]]; then allowed=$'libc.so\nlibdl.so\nliblog.so\nlibm.so\nlibz.so'; else [[ "$needed" == *libkompile_reasoning_android.so* ]] || die "JavaCPP transport not linked to graph AOT"; allowed=$'libc.so\nlibdl.so\nlibkompile_reasoning_android.so\nliblog.so\nlibm.so\nlibz.so'; fi; comm -23 <(printf '%s\n' "$needed") <(printf '%s\n' "$allowed" | LC_ALL=C sort) > "$tmp/baddeps"; [[ ! -s "$tmp/baddeps" ]] || die "$base unexpected dependencies"; }
verify_elf "$tmp/stage/artifacts/graph/android-arm64-v8a/libkompile_reasoning_android.so"; verify_elf "$tmp/stage/artifacts/graph/android-arm64-v8a/libjnikompile_graph.so"
for aar in "$tmp/stage"/artifacts/runtime/*.aar; do unzip -tqq "$aar" >/dev/null || die "invalid AAR"; unzip -Z1 "$aar" > "$tmp/aar.names"; grep -q '^jni/arm64-v8a/.*\.so$' "$tmp/aar.names" || die "AAR lacks arm64 runtime"; ! grep -Eq '^jni/(armeabi|armeabi-v7a|x86|x86_64)/' "$tmp/aar.names" || die "AAR forbidden ABI"; done
sdk="$tmp/stage/artifacts/graph/kompile-graph-reasoning-native-sdk.zip"; unzip -tqq "$sdk" >/dev/null || die "invalid graph SDK"; unzip -Z1 "$sdk" > "$tmp/sdk.names"; grep -q 'kompile_reasoning.h$' "$tmp/sdk.names" || die "graph SDK lacks header"; grep -Eq 'libkompile_reasoning.*\.(so|dylib|dll)$' "$tmp/sdk.names" || die "graph SDK lacks AOT library"
if find "$tmp/stage/source" -type d \( -name python -o -name python-end-to-end -o -name __pycache__ \) -print -quit | grep -q . || find "$tmp/stage/source" -type f \( -name '*.py' -o -name '*.pyc' \) -print -quit | grep -q .; then die "Python source forbidden"; fi
find "$tmp/stage/source" -type f \( -iname '*research*' -o -iname '*catalog*' -o -iname '*model*' \) -print -quit | grep -q . || die "model research catalog/source missing"
printf 'Verified %s\nSHA-256 %s\n' "$bundle" "$actual"
