#!/usr/bin/env bash
# Validation dispatch. Each case FIRST builds the backend under test from the
# checked-out DL4J source (otherwise `-pl platform-tests test` resolves stale
# published snapshots instead of the ref being validated), then runs the
# tests. cwd is the DL4J checkout; invoked by run-build.sh.
set -euo pipefail
target="${1:?validation target required}"
common=(--batch-mode --no-transfer-progress -DskipTestResourceEnforcement=true)
build_common=(--batch-mode --no-transfer-progress -DskipTests
  -Dlibnd4j.generate.flatc=ON -Dlibnd4j.sdx.standalone=ON -Dlibnd4j.triton=ON
  "-Dlibnd4j.buildthreads=${DL4J_BUILD_THREADS:-8}")
test_args=()
[ -z "${MAVEN_TEST_ARGS:-}" ] || read -r -a test_args <<< "$MAVEN_TEST_ARGS"

build_cpu() { # build_cpu JAVACPP_PLATFORM [extra args...]
  local platform="$1"; shift
  mvn -Pcpu -pl :nd4j-native,:nd4j-native-preset,:libnd4j --also-make install \
    "-Djavacpp.platform=${platform}" ${build_common[@]+"${build_common[@]}"} "$@"
}
build_cuda() {
  local version="${CUDA_VERSION:-12.9}"
  bash ./change-cuda-versions.sh "$version"
  mvn -Pcuda -pl ":nd4j-cuda-${version},:nd4j-cuda-${version}-preset,:libnd4j" --also-make install \
    -Dlibnd4j.chip=cuda -Dlibnd4j.cuda.compile.skip=false -Dlibnd4j.cpu.compile.skip=true \
    "-Dlibnd4j.compute=${CUDA_COMPUTE_CAPABILITIES:?}" -Djavacpp.platform=linux-x86_64 \
    ${build_common[@]+"${build_common[@]}"}
}

case "$target" in
  cpu-sanity-linux|cpu-tests-linux)
    build_cpu linux-x86_64
    mvn -Pcpu -pl platform-tests test -Djavacpp.platform=linux-x86_64 \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  cpu-sanity-macos)
    build_cpu macosx-arm64 -Dlibnd4j.arch=armv8-a -Dlibnd4j.platform=macosx-arm64
    mvn -Pcpu -pl platform-tests test -Djavacpp.platform=macosx-arm64 \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  cpu-integration-linux)
    build_cpu linux-x86_64
    mvn -Pcpu -pl platform-tests verify -Djavacpp.platform=linux-x86_64 \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  gpu-tests-linux|test-multiple-cuda-arches)
    build_cuda
    mvn -Pcuda -pl platform-tests test -Djavacpp.platform=linux-x86_64 \
      "-Dlibnd4j.compute=${CUDA_COMPUTE_CAPABILITIES:?}" \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  avx512-sde-linux)
    build_cpu linux-x86_64 -Dlibnd4j.extension=avx512
    mvn -Pcpu -pl platform-tests test -Dlibnd4j.extension=avx512 \
      -Djavacpp.platform.extension=-avx512 \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  gpu-multidevice-linux)
    # Multi-GPU device management on 4x V100 (BUILD_GENERAL1_LARGE). Device
    # selection/arbitration is ND4J's job (DeviceMemoryManager, placement
    # planner) — the lane only provides the hardware; NO vendor device env
    # vars (CUDA_VISIBLE_DEVICES) are ever set here.
    build_cuda
    mvn -Pcuda -pl platform-tests test -Djavacpp.platform=linux-x86_64 \
      "-Dtest=${MULTIDEVICE_TEST_FILTER:-*MultiDevice*,DeviceRoutingTest,DevicePlacementPlanner*,CudaMemoryPool*,*GpuFailover*}" \
      "-Dlibnd4j.compute=${CUDA_COMPUTE_CAPABILITIES:?}" \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  vulkan-smoke-linux)
    # Mirrors run-vulkan-smoke-tests.yml: lavapipe (CPU Vulkan ICD) — no GPU
    # host needed. Build with the vulkan property, then run the smoke test.
    export VK_ICD_FILENAMES="${VK_ICD_FILENAMES:-/usr/share/vulkan/icd.d/lvp_icd.x86_64.json}"
    mvn -Pvulkan -Dlibnd4j.vulkan -pl :nd4j-vulkan,:nd4j-vulkan-preset,:libnd4j --also-make install \
      -Dplatform.classifier=linux-x86_64 ${build_common[@]+"${build_common[@]}"}
    mvn -Pvulkan -pl :nd4j-vulkan,platform-tests --also-make test \
      "-Dtest=${VULKAN_TEST_FILTER:-VulkanBackendSmokeTest}" \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  mlx-smoke-macos)
    mvn -Pmlx -pl :nd4j-mlx,:nd4j-mlx-platform,platform-tests --also-make test \
      -Djavacpp.platform=macosx-arm64 ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  rocm-pjrt-smoke-linux)
    mvn -Procm -pl :nd4j-rocm,:nd4j-rocm-preset,platform-tests --also-make test \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  zluda-java-validation)
    mvn install -Pzluda,tpu -pl :nd4j-zluda,:nd4j-tpu --also-make -DskipTests \
      ${common[@]+"${common[@]}"} ;;
  zluda-smoke-linux)
    mvn -Pzluda -pl :nd4j-zluda,platform-tests --also-make test \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  tpu-smoke-linux)
    mvn -Ptpu -pl :nd4j-tpu,platform-tests --also-make test -Dlibnd4j.tpu \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  hexagon-smoke-linux)
    mvn -Phexagon -pl :nd4j-hexagon,platform-tests --also-make test -Dlibnd4j.hexagon \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  run-tests-matrix)
    : "${RUN_TESTS_PROFILES:?}" "${RUN_TESTS_MODULES:?}"
    read -r -a profiles <<< "$RUN_TESTS_PROFILES"
    mvn ${profiles[@]+"${profiles[@]}"} -pl "$RUN_TESTS_MODULES" --also-make test \
      ${common[@]+"${common[@]}"} ${test_args[@]+"${test_args[@]}"} ;;
  *) echo "No validation port for $target" >&2; exit 2 ;;
esac
