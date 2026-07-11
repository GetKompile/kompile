#!/usr/bin/env bash
# In-build entry point for Unix hosts (Linux containers, LINUX_EC2, MAC_ARM).
# Clones DL4J at DL4J_REF, builds the target's backend from source into the
# local Maven repository, optionally builds the kompile distribution with
# GraalVM, exports the built Maven artifacts, and publishes dist/ to S3 (and
# a GitHub Release when configured). macOS ships bash 3.2: keep empty-array
# expansions guarded with ${arr[@]+...}.
set -euo pipefail

: "${BUILD_TARGET:?BUILD_TARGET is required}"
: "${DL4J_REPOSITORY:?DL4J_REPOSITORY is required}"
: "${DL4J_REF:?DL4J_REF is required}"
: "${DL4J_BUILD_THREADS:?DL4J_BUILD_THREADS is required}"
: "${JAVA11_HOME:?JAVA11_HOME is required}"

root="${CODEBUILD_SRC_DIR:?CODEBUILD_SRC_DIR is required}"
scripts="${root}/aws/codebuild/scripts"
dl4j="${root}/.codebuild/deeplearning4j"
export CCACHE_DIR="${CCACHE_DIR:-$HOME/.ccache}"
rm -rf "${dl4j}"
mkdir -p "${root}/.codebuild" "${root}/dist"

if [ -n "${DL4J_GITHUB_TOKEN:-}" ]; then
  askpass="${root}/.codebuild/git-askpass.sh"
  printf '#!/usr/bin/env bash\ncase "$1" in *Username*) echo x-access-token;; *) echo "$DL4J_GITHUB_TOKEN";; esac\n' > "$askpass"
  chmod 700 "$askpass"
  export GIT_ASKPASS="$askpass" GIT_TERMINAL_PROMPT=0
fi
git clone --filter=blob:none --no-checkout "${DL4J_REPOSITORY}" "${dl4j}"
git -C "${dl4j}" fetch --depth 1 origin "${DL4J_REF}"
git -C "${dl4j}" checkout --detach FETCH_HEAD

export JAVA_HOME="${JAVA11_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export MAVEN_OPTS="${DL4J_MAVEN_OPTS:-}"
platform=linux-x86_64
profiles=(-Pcpu)
modules=:nd4j-native,:nd4j-native-preset,:libnd4j
args=(-Dlibnd4j.generate.flatc=ON -Dlibnd4j.sdx.standalone=ON -Dlibnd4j.triton=ON
      -Dlibnd4j.oom.memory.threshold=95 -Dlibnd4j.oom.velocity.threshold=40)
validation=false

case "${BUILD_TARGET}" in
  linux-x86_64) platform=linux-x86_64 ;;
  linux-x86_64-compat) platform=linux-x86_64; args+=(-Dlibnd4j.cpu.compat=true) ;;
  linux-arm64) platform=linux-arm64; profiles+=(-Posx-aarch64-protoc); args+=(-Dlibnd4j.arch=armv8-a) ;;
  macos-arm64) platform=macosx-arm64; args+=(-Dlibnd4j.arch=armv8-a -Dlibnd4j.platform=macosx-arm64) ;;
  android-arm64) platform=android-arm64; profiles+=(-Pandroid-arm64); args+=("-Dandroid.ndk=${ANDROID_NDK_HOME:?}") ;;
  android-x86_64) platform=android-x86_64; profiles+=(-Pandroid-x86_64); args+=("-Dandroid.ndk=${ANDROID_NDK_HOME:?}") ;;
  cross-*)
    platform="${BUILD_TARGET#cross-}"
    modules=:libtokenizers,:tokenizers-native-preset,:tokenizers-native
    profiles=()
    ;;
  linux-cuda-12.9|linux-cuda-13.1)
    cuda="${BUILD_TARGET##*-}"
    bash "${dl4j}/change-cuda-versions.sh" "${cuda}"
    profiles=(-Pcuda)
    modules=":nd4j-cuda-${cuda},:nd4j-cuda-${cuda}-preset,:libnd4j"
    args+=(-Dlibnd4j.chip=cuda -Dlibnd4j.cuda.compile.skip=false -Dlibnd4j.cpu.compile.skip=true "-Dlibnd4j.compute=${CUDA_COMPUTE_CAPABILITIES:?}")
    ;;
  linux-zluda)
    profiles=(-Pcuda); modules=:nd4j-cuda-12.9,:nd4j-cuda-12.9-preset,:libnd4j
    args+=(-Dlibnd4j.chip=cuda -Dlibnd4j.cuda.compile.skip=false -Dlibnd4j.cpu.compile.skip=true "-Dlibnd4j.compute=${CUDA_COMPUTE_CAPABILITIES:?}" "-Dlibnd4j.zluda=${ZLUDA_TARGET:?}")
    ;;
  linux-tpu-pjrt)
    profiles=(-Ptpu); modules=:nd4j-tpu,:nd4j-tpu-preset,:libnd4j
    args+=(-Dlibnd4j.tpu -Dplatform.classifier=linux-x86_64)
    ;;
  linux-hexagon)
    profiles=(-Phexagon); modules=:nd4j-hexagon,:nd4j-hexagon-preset,:libnd4j
    args+=(-Dlibnd4j.hexagon -Dplatform.classifier=linux-x86_64)
    ;;
  *sanity*|*integration*|*smoke*|*validation*|avx512-*|test-multiple-*|run-tests-matrix)
    validation=true
    ;;
  windows-*) echo "Windows targets require buildspec-windows.yml" >&2; exit 2 ;;
  *) echo "Unknown BUILD_TARGET: ${BUILD_TARGET}" >&2; exit 2 ;;
esac

cd "${dl4j}"
if [ "${validation}" = true ]; then
  "${scripts}/run-validation.sh" "${BUILD_TARGET}" 2>&1 | tee "${root}/dist/${BUILD_TARGET}.log"
else
  extra=(); [ -z "${DL4J_EXTRA_MAVEN_ARGS:-}" ] || read -r -a extra <<< "${DL4J_EXTRA_MAVEN_ARGS}"
  mvn ${profiles[@]+"${profiles[@]}"} -pl "${modules}" --also-make install -DskipTests \
    --batch-mode --no-transfer-progress "-Dlibnd4j.buildthreads=${DL4J_BUILD_THREADS}" \
    "-Djavacpp.platform=${platform}" ${args[@]+"${args[@]}"} ${extra[@]+"${extra[@]}"} \
    2>&1 | tee "${root}/dist/${BUILD_TARGET}-dl4j.log"

  # Export the DL4J artifacts this build installed so DL4J-only targets still
  # ship something usable (the S3 cache is not a distribution channel).
  m2="${HOME}/.m2/repository"
  subtrees=()
  for d in org/nd4j org/deeplearning4j org/bytedeco org/eclipse/deeplearning4j; do
    [ -d "${m2}/${d}" ] && subtrees+=("${d}")
  done
  if [ "${#subtrees[@]}" -gt 0 ]; then
    tar -czf "${root}/dist/${BUILD_TARGET}-maven-artifacts.tgz" -C "${m2}" ${subtrees[@]+"${subtrees[@]}"}
  fi
fi

if [ "${BUILD_KOMPILE:-true}" = true ]; then
  export JAVA_HOME="${GRAALVM_HOME:?GRAALVM_HOME is required}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
  export MAVEN_OPTS="${KOMPILE_MAVEN_OPTS:-}"
  cd "${root}"
  ./build-dist.sh "${KOMPILE_VARIANT:?KOMPILE_VARIANT is required}" \
    --parallel "${NATIVE_PARALLELISM:?NATIVE_PARALLELISM is required}" \
    --output-dir "${root}/dist"
fi

"${scripts}/publish-dist.sh" "${root}/dist"
