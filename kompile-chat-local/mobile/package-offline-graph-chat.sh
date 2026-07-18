#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
die(){ printf 'package-offline-graph-chat: %s\n' "$*" >&2; exit 1; }
need(){ command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
kompile_root="$(CDPATH= cd -- "$script_dir/../.." && pwd -P)"
sibling_root="$(dirname -- "$kompile_root")"
dl4j_root="$sibling_root/deeplearning4j"; examples_root="$sibling_root/deeplearning4j-examples"
output="$script_dir/build/kompile-offline-graph-chat-full.zip"; android_root="$script_dir/android"
graph_module="$kompile_root/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local"
graph_reasoning_module="$kompile_root/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning"
project_store_module="$kompile_root/kompile-app/kompile-data/kompile-project-store"
staging_module="$kompile_root/kompile-app/kompile-models/kompile-model-staging"
vulkan_apk="$android_root/build/offline-dist/kompile-offline-graph-chat-vulkan.apk"
hexagon_apk="$android_root/build/offline-dist/kompile-offline-graph-chat-hexagon.apk"
tensor_g3_apk="$android_root/build/offline-dist/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk"
tensor_g5_apk="$android_root/build/offline-dist/kompile-offline-graph-chat-tensor-g5.apk"
vulkan_aar="$dl4j_root/libnd4j/build/mobile/vulkan/dist/sdx-runtime-android-arm64-vulkan.aar"
hexagon_aar="$dl4j_root/libnd4j/build/mobile/hexagon/dist/sdx-runtime-android-arm64-hexagon.aar"
tensor_g3_aar="$dl4j_root/libnd4j/build/mobile/tensor-g3/dist/sdx-runtime-android-arm64-tensor-g3.aar"
tensor_g5_aar="$dl4j_root/libnd4j/build/mobile/google-tensor-g5/dist/sdx-chat-runtime-android-arm64-google-tensor-g5.aar"
graph_sdk="$graph_module/target/kompile-graph-reasoning-local-0.1.0-SNAPSHOT-native-sdk.zip"
android_graph_library="$graph_module/target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so"
android_graph_javacpp_library="$android_root/app/src/main/jniLibs/arm64-v8a/libjnikompile_graph.so"
graph_header="$graph_module/include/kompile_reasoning.h"
canonical_sdz="$examples_root/sdx-runtime-examples/models/mlp.sdz"
canonical_sdz_readme="$examples_root/sdx-runtime-examples/models/README.md"
while (($#)); do
 case "$1" in
 --output|--dl4j-root|--examples-root|--vulkan-apk|--hexagon-apk|--tensor-g3-apk|--tensor-g5-apk|--tensor-apk|--vulkan-aar|--hexagon-aar|--tensor-g3-aar|--tensor-g5-aar|--tensor-aar|--graph-sdk|--android-graph-library|--android-graph-javacpp-library|--graph-header|--canonical-sdz|--canonical-sdz-readme)
  (($# >= 2)) || die "missing value for $1"; key="${1#--}"; key="${key//-/_}"; [[ "$key" == tensor_apk ]] && key=tensor_g5_apk; [[ "$key" == tensor_aar ]] && key=tensor_g5_aar; printf -v "$key" '%s' "$2"; shift 2;;
 --help) printf 'Usage: %s [--output ZIP] [artifact/root overrides]\n' "$0"; exit 0;;
 *) die "unknown option: $1";; esac
done
[[ "$output" == /* ]] || output="$(pwd -P)/$output"
for c in cmake zip sha256sum find sort touch; do need "$c"; done
[[ "$(uname -s)" == "Linux" ]] ||
  die "this reproducible packager requires Linux with GNU coreutils/findutils"
stage="$(mktemp -d "${TMPDIR:-/tmp}/offline-graph-chat.XXXXXX")"
cleanup(){ rm -rf -- "$stage"; }; trap cleanup EXIT HUP INT TERM
copy_file(){ local src=$1 dst=$2; [[ -f "$src" && -s "$src" ]] || die "required file missing or empty: $src"; mkdir -p -- "$stage/$(dirname -- "$dst")"; cp -p -- "$src" "$stage/$dst"; }
copy_tree(){ local src=$1 dst=$2; [[ -d "$src" ]] || die "required directory missing: $src"; while IFS= read -r -d '' f; do local rel="${f#"$src"/}"; mkdir -p -- "$stage/$dst/$(dirname -- "$rel")"; cp -p -- "$f" "$stage/$dst/$rel"; done < <(find "$src" \( -type d \( -name .git -o -name .gradle -o -name .idea -o -name .kotlin -o -name __pycache__ -o -name build -o -name target -o -name node_modules -o -name libs -o -name jniLibs -o -name blasbuild -o -name python -o -name python-end-to-end \) -prune \) -o \( -type f ! -name .DS_Store ! -name local.properties ! -name '*.py' ! -name '*.pyc' -print0 \)); }
copy_file "$vulkan_apk" artifacts/android/kompile-offline-graph-chat-vulkan.apk
copy_file "$hexagon_apk" artifacts/android/kompile-offline-graph-chat-hexagon.apk
copy_file "$tensor_g3_apk" artifacts/android/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk
copy_file "$tensor_g5_apk" artifacts/android/kompile-offline-graph-chat-tensor-g5.apk
for apk in "$stage"/artifacts/android/*.apk; do hash="$(sha256sum "$apk" | cut -d ' ' -f 1)"; printf '%s  %s\n' "$hash" "$(basename -- "$apk")" > "$apk.sha256"; done
copy_file "$vulkan_aar" artifacts/runtime/sdx-runtime-android-arm64-vulkan.aar
copy_file "$hexagon_aar" artifacts/runtime/sdx-runtime-android-arm64-hexagon.aar
copy_file "$tensor_g3_aar" artifacts/runtime/sdx-runtime-android-arm64-tensor-g3.aar
copy_file "$tensor_g5_aar" artifacts/runtime/sdx-chat-runtime-android-arm64-google-tensor-g5.aar
copy_file "$android_root/accelerators.json" artifacts/contracts/accelerators.json
copy_file "$graph_sdk" artifacts/graph/kompile-graph-reasoning-native-sdk.zip
copy_file "$android_graph_library" artifacts/graph/android-arm64-v8a/libkompile_reasoning_android.so
copy_file "$android_graph_javacpp_library" artifacts/graph/android-arm64-v8a/libjnikompile_graph.so
copy_file "$graph_header" artifacts/graph/include/kompile_reasoning.h
copy_file "$android_root/app/src/main/assets/graphs/fixture.kgraph" artifacts/graph/fixture.kgraph
copy_file "$canonical_sdz" artifacts/models/canonical-mlp.sdz
copy_file "$canonical_sdz_readme" artifacts/models/canonical-mlp.README.md
copy_tree "$kompile_root/kompile-chat-local" source/kompile-chat-local
copy_tree "$graph_module" source/kompile-graph-reasoning-local
copy_tree "$graph_reasoning_module" source/kompile/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning
copy_tree "$project_store_module" source/kompile/kompile-app/kompile-data/kompile-project-store
copy_tree "$staging_module" source/kompile/kompile-app/kompile-models/kompile-model-staging
copy_file "$kompile_root/pom.xml" source/kompile/pom.xml
copy_file "$kompile_root/kompile-app/pom.xml" source/kompile/kompile-app/pom.xml
copy_tree "$dl4j_root/libnd4j/tools/mobile" source/deeplearning4j/libnd4j/tools/mobile
for tree in java java-end-to-end models; do copy_tree "$examples_root/sdx-runtime-examples/$tree" "source/deeplearning4j-examples/sdx-runtime-examples/$tree"; done
copy_file "$examples_root/sdx-runtime-examples/README.md" source/deeplearning4j-examples/sdx-runtime-examples/README.md
for rel in nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-model nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-preset nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-litertlm libnd4j/include/graph/vulkan libnd4j/include/graph/hexagon libnd4j/include/graph/cpu libnd4j/include/graph/tpu libnd4j/include/graph/impl; do copy_tree "$dl4j_root/$rel" "source/deeplearning4j/$rel"; done
for rel in libnd4j/CMakeLists.txt libnd4j/buildnativeoperations.sh libnd4j/tools/sdx-compile.sh libnd4j/cmake/BuildVulkan.cmake libnd4j/cmake/VulkanConfiguration.cmake libnd4j/cmake/BuildHexagon.cmake libnd4j/cmake/HexagonConfiguration.cmake libnd4j/cmake/HexagonDependencies.cmake libnd4j/cmake/HexagonDependencyValidate.cmake libnd4j/cmake/HexagonKlExtract.cmake libnd4j/cmake/HexagonFastRpc.cmake libnd4j/cmake/ResumableDownload.cmake libnd4j/cmake/MainBuildFlow.cmake libnd4j/cmake/Options.cmake libnd4j/cmake/SdxRuntimePackage.cmake libnd4j/include/dsp/NativeOpsDsp.h libnd4j/include/dsp/runtime/dsp_runtime_c.h libnd4j/include/graph/GraphReplayHandle.h libnd4j/include/graph/NativeDynamicShapePlan.h libnd4j/include/graph/ReplayCacheManager.h libnd4j/include/legacy/cpu/NativeOps_dsp.cpp libnd4j/include/legacy/cuda/NativeOps_dsp.cu libnd4j/include/legacy/impl/DspRuntimeC.cpp libnd4j/include/legacy/vulkan/NativeOps_dsp_plan.cpp 'ADRs/ADR-0110-Vulkan-Backend.md' 'ADRs/ADR-0111-Vulkan-Device-Management.md' 'ADRs/ADR-0112-Vulkan-Java-Layer-Kernels-Tests.md' 'ADRs/0115 - Vulkan SPIR-V and Pipeline Disk Cache.md' 'ADRs/0088 - Hexagon MLIR Backend.md'; do copy_file "$dl4j_root/$rel" "source/deeplearning4j/$rel"; done
printf '%s\n' 'Offline graph-chat source, Android packages, accelerator runtimes, graph AOT artifacts, canonical SDZ fixture, canonical .kproject staging/project-store sources, model research catalog, and integrity manifest. No production model weights are distributed.' > "$stage/README.md"
cmake -DMODE=manifest -DSTAGE="$stage" -P "$script_dir/cmake/OfflineGraphChatBundle.cmake"
find "$stage" -type f -perm /111 -exec chmod 0755 {} +
find "$stage" -type f ! -perm /111 -exec chmod 0644 {} +
find "$stage" -exec touch -h -t 198001010000.00 {} +
mkdir -p -- "$(dirname -- "$output")"; tmp="$output.tmp"; rm -f -- "$tmp"
( cd "$stage"; find . -type f -printf '%P\n' | LC_ALL=C sort | zip -X -q -9 "$tmp" -@ )
mv -f -- "$tmp" "$output"; hash="$(sha256sum "$output" | cut -d ' ' -f 1)"
printf '%s  %s\n' "$hash" "$(basename -- "$output")" > "$output.sha256"
printf 'Created %s\nSHA-256 %s\n' "$output" "$hash"
