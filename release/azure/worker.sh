#!/usr/bin/env bash
set -Eeuo pipefail
export HOME=${HOME:-/root}
export PATH=${PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}

CONFIG_B64='__KOMPILE_AZURE_WORKER_CONFIG_B64__'
BUILD_DRIVER_B64='__KOMPILE_BUILD_DRIVER_B64__'
CONFIG_FILE=/tmp/kompile-azure-worker.json
BUILD_DRIVER=/tmp/kompile-build-platform.py
WORK_ROOT=${KOMPILE_WORK_ROOT:-/opt/kompile-release}
SOURCE_DIR=${WORK_ROOT}/source
OUTPUT_DIR=${WORK_ROOT}/output
MAVEN_REPO=${WORK_ROOT}/m2
BUILD_LOG=${OUTPUT_DIR}/build.log
BUILD_PID_FILE=/tmp/kompile-release-build.pid
WATCHDOG_PID=""

mkdir -p "${OUTPUT_DIR}" "${MAVEN_REPO}"
printf '%s' "${CONFIG_B64}" | base64 --decode >"${CONFIG_FILE}"
printf '%s' "${BUILD_DRIVER_B64}" | base64 --decode >"${BUILD_DRIVER}"
exec > >(tee -a "${BUILD_LOG}") 2>&1

config() {
  python3 -c 'import json,sys; value=json.load(open(sys.argv[1], encoding="utf-8"));
for part in sys.argv[2].split("."): value=value[part]
print(json.dumps(value) if isinstance(value,(dict,list)) else value)' "${CONFIG_FILE}" "$1"
}

STORAGE_ACCOUNT=$(config storageAccount)
ARTIFACT_CONTAINER=$(config artifactContainer)
ARTIFACT_PREFIX=$(config artifactPrefix)
RUN_ID=$(config runId)
SHARD_ID=$(config shard.id)
BRANCH=$(config branch)
COMMIT=$(config commit)
REPOSITORY=$(config repository)
IDENTITY_CLIENT_ID=$(config managedIdentityClientId)
KILL_SWITCH_URL=$(config killSwitchUrl)
PLATFORM=$(config shard.build.javacppPlatform)
CONTAINER_IMAGE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["shard"].get("containerImage", ""))' "${CONFIG_FILE}")
CONTAINER_FAMILY=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["shard"].get("containerFamily", "debian"))' "${CONFIG_FILE}")
BLOB_ROOT="https://${STORAGE_ACCOUNT}.blob.core.windows.net/${ARTIFACT_CONTAINER}/${ARTIFACT_PREFIX}/${RUN_ID}/${SHARD_ID}"

azcopy_retry() {
  local attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    azcopy "$@" && return 0
    sleep $((attempt * 6))
  done
  return 1
}

upload_if_present() {
  [ ! -e "$1" ] && return 0
  azcopy_retry copy "$1" "${BLOB_ROOT}/$2" --overwrite=true
}

finish() {
  local exit_code=$?
  set +e
  [ -n "${WATCHDOG_PID}" ] && kill "${WATCHDOG_PID}" 2>/dev/null
  local upload_failed=0
  upload_if_present "${BUILD_LOG}" build.log || upload_failed=1
  upload_if_present "${OUTPUT_DIR}/maven-repository.tar.gz" maven-repository.tar.gz || upload_failed=1
  upload_if_present "${OUTPUT_DIR}/sdk-assets.tar.gz" sdk-assets.tar.gz || upload_failed=1
  upload_if_present "${OUTPUT_DIR}/shard-manifest.json" shard-manifest.json || upload_failed=1
  [ "${upload_failed}" -ne 0 ] && exit_code=1
  python3 -c 'import json,sys,time; json.dump({"shard":sys.argv[1],"exitCode":int(sys.argv[2]),"completedAt":int(time.time())},open(sys.argv[3],"w"),sort_keys=True)'     "${SHARD_ID}" "${exit_code}" "${OUTPUT_DIR}/status.json"
  # status.json is the final durable marker consumed by the controller.
  if ! upload_if_present "${OUTPUT_DIR}/status.json" status.json; then
    printf 'Failed to upload durable status marker for %s\n' "${SHARD_ID}" >&2
    exit_code=1
  fi
  sync
  shutdown -h now || true
  exit "${exit_code}"
}
trap finish EXIT

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends   autoconf automake build-essential ca-certificates ccache cmake curl docker.io   gfortran git gnupg jq libdwarf-dev libdw-dev libelf-dev libgomp1 libomp-dev   libopenblas-dev libtool libusb-1.0-0-dev libvulkan-dev libvulkan1 maven   mesa-vulkan-drivers nasm ninja-build openjdk-11-jdk openjdk-17-jdk   pinentry-curses pkg-config python3 swig tar unzip vulkan-tools wget zip zlib1g-dev
apt-get install -y llvm-18-dev mlir-18-tools ||   apt-get install -y llvm-dev libmlir-dev mlir-tools || true

if [ "$(uname -m)" = aarch64 ]; then
  AZCOPY_URL=https://aka.ms/downloadazcopy-v10-linux-arm64
else
  AZCOPY_URL=https://aka.ms/downloadazcopy-v10-linux
fi
curl --fail --location --retry 5 "${AZCOPY_URL}" -o /tmp/azcopy.tar.gz
tar -xzf /tmp/azcopy.tar.gz -C /tmp
install "$(find /tmp -path '*/azcopy' -type f | head -1)" /usr/local/bin/azcopy
azcopy login --identity --identity-client-id "${IDENTITY_CLIENT_ID}"

export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-$(dpkg --print-architecture)
export CCACHE_DIR=${WORK_ROOT}/ccache
export CMAKE_C_COMPILER_LAUNCHER=ccache CMAKE_CXX_COMPILER_LAUNCHER=ccache
mkdir -p "${CCACHE_DIR}"
ccache --max-size=100G

curl --fail --location --retry 5   https://github.com/google/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz   -o /tmp/protobuf-3.8.0.tar.gz
tar -xzf /tmp/protobuf-3.8.0.tar.gz -C /tmp
(cd /tmp/protobuf-3.8.0 && ./configure --prefix=/opt/protobuf &&   make -j"$(nproc)" && make install)
export PATH="/opt/protobuf/bin:${PATH}"

if [ "$(uname -m)" = x86_64 ]; then
  curl --fail --location --retry 5     https://github.com/Kitware/CMake/releases/download/v3.28.3/cmake-3.28.3-linux-x86_64.tar.gz     -o /tmp/cmake.tar.gz
  mkdir -p /opt/cmake
  tar -xzf /tmp/cmake.tar.gz -C /opt/cmake --strip-components=1
  export PATH="/opt/cmake/bin:${PATH}"
fi
curl --proto '=https' --tlsv1.2 --fail --silent --show-error   https://sh.rustup.rs | sh -s -- -y --profile minimal
export PATH="${HOME}/.cargo/bin:${PATH}"
cargo install --locked cbindgen

if [[ "${PLATFORM}" == android-* ]]; then
  NDK_VERSION=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["shard"]["build"].get("ndkVersion", "r27d"))' "${CONFIG_FILE}")
  curl --fail --location --retry 5     "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip"     -o /tmp/android-ndk.zip
  unzip -q /tmp/android-ndk.zip -d /opt/android
  export ANDROID_NDK="/opt/android/android-ndk-${NDK_VERSION}"
  export ANDROID_NDK_HOME="${ANDROID_NDK}"
fi

watch_kill_switch() {
  local value current_pid
  while true; do
    rm -f /tmp/kompile-kill-switch.json
    if ! azcopy copy "${KILL_SWITCH_URL}" /tmp/kompile-kill-switch.json       --overwrite=true >/dev/null 2>&1; then
      value=true
    else
      value=$(python3 -c 'import json; print(str(bool(json.load(open("/tmp/kompile-kill-switch.json")).get("enabled"))).lower())' 2>/dev/null || printf true)
    fi
    if [ "${value}" = true ]; then
      printf 'Azure release kill switch enabled or unreadable; stopping %s.\n' "${SHARD_ID}"
      current_pid=$([ -f "${BUILD_PID_FILE}" ] && tr -dc '0-9' <"${BUILD_PID_FILE}" || true)
      [ -n "${current_pid}" ] && kill -TERM -- "-${current_pid}" 2>/dev/null
      sleep 5
      [ -n "${current_pid}" ] && kill -KILL -- "-${current_pid}" 2>/dev/null
      shutdown -h now || true
      return
    fi
    sleep 20
  done
}
watch_kill_switch &
WATCHDOG_PID=$!

git init "${SOURCE_DIR}"
git -C "${SOURCE_DIR}" remote add origin "${REPOSITORY}"
if [ -n "${BRANCH}" ]; then
  git -C "${SOURCE_DIR}" fetch --depth=1 origin \
    "+refs/heads/${BRANCH}:refs/remotes/origin/${BRANCH}"
  branch_commit=$(git -C "${SOURCE_DIR}" rev-parse "refs/remotes/origin/${BRANCH}^{commit}")
  if [ "${branch_commit}" != "${COMMIT}" ]; then
    printf 'Branch %s resolved to %s, expected %s\n' \
      "${BRANCH}" "${branch_commit}" "${COMMIT}" >&2
    exit 2
  fi
else
  git -C "${SOURCE_DIR}" fetch --depth=1 origin "${COMMIT}"
fi
git -C "${SOURCE_DIR}" checkout --detach "${COMMIT}"
[ "$(git -C "${SOURCE_DIR}" rev-parse HEAD)" = "${COMMIT}" ] || exit 2
mkdir -p "${OUTPUT_DIR}/maven-repository" "${OUTPUT_DIR}/sdk-assets"

build=(python3 "${BUILD_DRIVER}" --config "${CONFIG_FILE}"   --source "${SOURCE_DIR}" --repository "${MAVEN_REPO}"   --maven-output "${OUTPUT_DIR}/maven-repository"   --sdk-output "${OUTPUT_DIR}/sdk-assets")
if [ -n "${CONTAINER_IMAGE}" ]; then
  docker pull "${CONTAINER_IMAGE}"
  if [ "${CONTAINER_FAMILY}" = almalinux ]; then
    install_command="dnf install -y autoconf automake diffutils findutils gcc gcc-c++ gcc-gfortran git java-11-openjdk-devel libtool make maven nasm ninja-build patch pkgconfig python3 tar unzip wget which xz zip && python3 /kompile-build-platform.py --config /kompile-config.json --source /workspace --repository /kompile-m2 --maven-output /kompile-output/maven-repository --sdk-output /kompile-output/sdk-assets"
  else
    install_command="apt-get update && apt-get install -y --no-install-recommends autoconf automake build-essential ca-certificates cmake gfortran git libomp-dev libopenblas-dev libtool maven nasm ninja-build openjdk-11-jdk pkg-config python3 swig unzip xz-utils zip && export PATH=/opt/protobuf/bin:/opt/cmake/bin:\$PATH && python3 /kompile-build-platform.py --config /kompile-config.json --source /workspace --repository /kompile-m2 --maven-output /kompile-output/maven-repository --sdk-output /kompile-output/sdk-assets"
  fi
  build=(docker run --rm --network host     -v "${SOURCE_DIR}:/workspace"     -v "${MAVEN_REPO}:/kompile-m2"     -v "${OUTPUT_DIR}:/kompile-output"     -v "${CONFIG_FILE}:/kompile-config.json:ro"     -v "${BUILD_DRIVER}:/kompile-build-platform.py:ro"     -v /opt/protobuf:/opt/protobuf:ro     -v /opt/cmake:/opt/cmake:ro     -w /workspace "${CONTAINER_IMAGE}" bash -lc "${install_command}")
fi

setsid "${build[@]}" &
BUILD_PID=$!
printf '%s\n' "${BUILD_PID}" >"${BUILD_PID_FILE}"
wait "${BUILD_PID}"
rm -f "${BUILD_PID_FILE}"

python3 - "${OUTPUT_DIR}" "${CONFIG_FILE}" <<'PY'
import hashlib
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
config = json.load(open(sys.argv[2], encoding="utf-8"))
files = []
for path in sorted(item for item in root.rglob("*") if item.is_file()):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    files.append({
        "path": path.relative_to(root).as_posix(),
        "sha256": digest,
        "size": path.stat().st_size,
    })
json.dump({
    "schemaVersion": 2,
    "provider": "azure",
    "runId": config["runId"],
    "shard": config["shard"]["id"],
    "commit": config["commit"],
    "dl4jCommit": config.get("dl4jCommit", ""),
    "dl4jInputMode": config["dl4jInputMode"],
    "releaseVersion": config["releaseVersion"],
    "classifiers": [
        item.get("classifier", item.get("distributionClassifier", item["name"]))
        for item in config["shard"]["build"]["variants"]
    ],
    "files": files,
}, open(root / "shard-manifest.json", "w", encoding="utf-8"),
indent=2, sort_keys=True)
PY

tar -C "${OUTPUT_DIR}/maven-repository" -czf   "${OUTPUT_DIR}/maven-repository.tar.gz" .
tar -C "${OUTPUT_DIR}/sdk-assets" -czf   "${OUTPUT_DIR}/sdk-assets.tar.gz" .
