#!/usr/bin/env bash
###############################################################################
# build-kompile-docker.sh
#
# Build kompile container images using Docker or Podman.
# Auto-detects the available container runtime.
#
# Usage:
#   ./build-kompile-docker.sh                           # CPU image, auto-detect runtime
#   ./build-kompile-docker.sh --backend cuda             # CUDA image
#   ./build-kompile-docker.sh --backend cpu --push       # CPU image + push to registry
#   ./build-kompile-docker.sh --backend cuda \
#     --cuda-version 12.6 \
#     --dl4j-branch master \
#     --native-targets cli,app,staging \
#     --tag myregistry/kompile:cuda-12.6
#   ./build-kompile-docker.sh --runtime podman           # Force podman
#   ./build-kompile-docker.sh --all                      # Build cpu + cuda images
#   ./build-kompile-docker.sh --run                      # Build + run with all GPUs
#   ./build-kompile-docker.sh --run --gpus '"device=0"'  # Run on GPU 0 only
#   ./build-kompile-docker.sh --run --gpus '"device=1,2"' --test  # Test on GPUs 1,2
#
# Build options:
#   --backend B          Backend: cpu, cuda (default: cpu)
#   --cuda-version V     CUDA version: 12.6, 12.9, 13.1 (default: 12.9)
#   --compute-cap C      CUDA compute capabilities (default: 86,89)
#   --dl4j-branch B      DL4J branch (default: master)
#   --kompile-branch B   Kompile branch (default: main)
#   --native-targets T   Native image targets (default: cli)
#   --graalvm-version V  GraalVM version (default: 21.0.7-graal)
#   --maven-version V    Maven version (default: 3.9.9)
#   --build-threads N    Parallel build threads (default: nproc)
#   --tag T              Image tag (default: kompile:<backend>)
#   --runtime R          Container runtime: docker, podman (default: auto)
#   --push               Push image after build
#   --no-cache           Build without layer cache
#   --all                Build all backends (cpu + cuda)
#   --list               List available Dockerfiles
#   --dry-run            Print the build command without executing
#
# Run/test options:
#   --run                Run the image after building
#   --gpus G             GPU passthrough spec (default: all). Examples:
#                          all              — all GPUs visible
#                          '"device=0"'     — GPU 0 only
#                          '"device=0,2"'   — GPUs 0 and 2
#                          2                — any 2 GPUs
#   --test               Run kompile test suite inside the container
#   --test-class C       Specific test class to run (requires --test)
#   --shm-size S         Shared memory size (default: 16g for CUDA, 1g for CPU)
#   --volumes V          Extra volume mounts (comma-separated host:container pairs)
#   --ports P            Extra port mappings (comma-separated, default: 9191:9191)
#   --entrypoint E       Override container entrypoint
#   --cmd C              Override container CMD (appended after entrypoint)
#   --help               Show this help
###############################################################################
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ─── Defaults ──────────────────────────────────────────────────────────────
BACKEND="cpu"
CUDA_VERSION="12.9"
COMPUTE_CAP="86,89"
DL4J_BRANCH="master"
KOMPILE_BRANCH="main"
NATIVE_TARGETS="all"
GRAALVM_VERSION="21.0.7-graal"
MAVEN_VERSION="3.9.9"
BUILD_THREADS=""
IMAGE_TAG=""
RUNTIME=""
DO_PUSH=0
NO_CACHE=0
BUILD_ALL=0
DRY_RUN=0
DO_RUN=0
DO_TEST=0
GPU_SPEC="all"
TEST_CLASS=""
SHM_SIZE=""
EXTRA_VOLUMES=""
EXTRA_PORTS="9191:9191"
OVERRIDE_ENTRYPOINT=""
OVERRIDE_CMD=""

# ─── Parse args ────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend)          BACKEND="$2"; shift 2 ;;
    --cuda-version)     CUDA_VERSION="$2"; shift 2 ;;
    --compute-cap)      COMPUTE_CAP="$2"; shift 2 ;;
    --dl4j-branch)      DL4J_BRANCH="$2"; shift 2 ;;
    --kompile-branch)   KOMPILE_BRANCH="$2"; shift 2 ;;
    --native-targets)   NATIVE_TARGETS="$2"; shift 2 ;;
    --graalvm-version)  GRAALVM_VERSION="$2"; shift 2 ;;
    --maven-version)    MAVEN_VERSION="$2"; shift 2 ;;
    --build-threads)    BUILD_THREADS="$2"; shift 2 ;;
    --tag)              IMAGE_TAG="$2"; shift 2 ;;
    --runtime)          RUNTIME="$2"; shift 2 ;;
    --push)             DO_PUSH=1; shift ;;
    --no-cache)         NO_CACHE=1; shift ;;
    --all)              BUILD_ALL=1; shift ;;
    --dry-run)          DRY_RUN=1; shift ;;
    --run)              DO_RUN=1; shift ;;
    --gpus)             GPU_SPEC="$2"; shift 2 ;;
    --test)             DO_TEST=1; DO_RUN=1; shift ;;
    --test-class)       TEST_CLASS="$2"; shift 2 ;;
    --shm-size)         SHM_SIZE="$2"; shift 2 ;;
    --volumes)          EXTRA_VOLUMES="$2"; shift 2 ;;
    --ports)            EXTRA_PORTS="$2"; shift 2 ;;
    --entrypoint)       OVERRIDE_ENTRYPOINT="$2"; shift 2 ;;
    --cmd)              OVERRIDE_CMD="$2"; shift 2 ;;
    --list|-l)
      echo "Available Dockerfiles:"
      for f in "${SCRIPT_DIR}"/Dockerfile.*; do
        [ -f "$f" ] || continue
        local_name="$(basename "$f")"
        desc="$(head -5 "$f" | grep -m1 '#.*kompile' || echo "")"
        echo "  ${local_name}  ${desc}"
      done
      exit 0
      ;;
    --help|-h)
      head -40 "$0" | tail -38
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# ─── Auto-detect container runtime ────────────────────────────────────────
detect_runtime() {
  if [ -n "${RUNTIME}" ]; then
    if ! command -v "${RUNTIME}" &>/dev/null; then
      echo "ERROR: ${RUNTIME} not found" >&2
      exit 1
    fi
    echo "${RUNTIME}"
    return
  fi

  if command -v docker &>/dev/null; then
    # Verify docker daemon is running
    if docker info &>/dev/null 2>&1; then
      echo "docker"
      return
    fi
  fi

  if command -v podman &>/dev/null; then
    echo "podman"
    return
  fi

  echo "ERROR: Neither docker nor podman found. Install one of them." >&2
  exit 1
}

CONTAINER_RT="$(detect_runtime)"
echo "=== Container runtime: ${CONTAINER_RT} ==="

# ─── CUDA base image tag resolution ───────────────────────────────────────
resolve_cuda_tags() {
  local ver="$1"
  case "${ver}" in
    12.6)
      CUDA_BASE_TAG="12.6.3-devel-ubuntu22.04"
      CUDA_RUNTIME_TAG="12.6.3-runtime-ubuntu22.04"
      ;;
    12.9)
      CUDA_BASE_TAG="12.9.0-devel-ubuntu22.04"
      CUDA_RUNTIME_TAG="12.9.0-runtime-ubuntu22.04"
      ;;
    13.1)
      CUDA_BASE_TAG="13.1.0-devel-ubuntu22.04"
      CUDA_RUNTIME_TAG="13.1.0-runtime-ubuntu22.04"
      ;;
    *)
      echo "ERROR: Unsupported CUDA version: ${ver}. Use 12.6, 12.9, or 13.1." >&2
      exit 1
      ;;
  esac
}

# ─── Build a single image ─────────────────────────────────────────────────
build_image() {
  local backend="$1"
  local tag="${IMAGE_TAG}"
  local dockerfile="${SCRIPT_DIR}/Dockerfile.${backend}"

  if [ ! -f "${dockerfile}" ]; then
    echo "ERROR: ${dockerfile} not found" >&2
    return 1
  fi

  # Default tag
  if [ -z "${tag}" ]; then
    case "${backend}" in
      cuda) tag="kompile:cuda-${CUDA_VERSION}" ;;
      *)    tag="kompile:${backend}" ;;
    esac
  fi

  # Build args
  local build_args=(
    --build-arg "DL4J_BRANCH=${DL4J_BRANCH}"
    --build-arg "KOMPILE_BRANCH=${KOMPILE_BRANCH}"
    --build-arg "NATIVE_TARGETS=${NATIVE_TARGETS}"
    --build-arg "GRAALVM_VERSION=${GRAALVM_VERSION}"
    --build-arg "MAVEN_VERSION=${MAVEN_VERSION}"
  )

  if [ -n "${BUILD_THREADS}" ]; then
    build_args+=(--build-arg "BUILD_THREADS=${BUILD_THREADS}")
  fi

  # Backend-specific args
  case "${backend}" in
    cuda)
      resolve_cuda_tags "${CUDA_VERSION}"
      build_args+=(
        --build-arg "CUDA_VERSION=${CUDA_VERSION}"
        --build-arg "CUDA_BASE_TAG=${CUDA_BASE_TAG}"
        --build-arg "CUDA_RUNTIME_TAG=${CUDA_RUNTIME_TAG}"
        --build-arg "COMPUTE_CAP=${COMPUTE_CAP}"
        --build-arg "VARIANT=cuda"
      )
      ;;
    cpu)
      build_args+=(--build-arg "VARIANT=cpu-intel")
      ;;
  esac

  # Cache control
  if [ "${NO_CACHE}" -eq 1 ]; then
    build_args+=(--no-cache)
  fi

  # If --test was requested, build the test target (keeps source + Maven)
  if [ "${DO_TEST}" -eq 1 ]; then
    build_args+=("--target" "test")
    tag="${tag}-test"
  fi

  local cmd=(
    "${CONTAINER_RT}" build
    -f "${dockerfile}"
    -t "${tag}"
    "${build_args[@]}"
    "${PROJECT_ROOT}"
  )

  echo "=== Building ${backend} image ==="
  echo "  Dockerfile: ${dockerfile}"
  echo "  Tag:        ${tag}"
  echo "  Runtime:    ${CONTAINER_RT}"
  echo "  DL4J:       ${DL4J_BRANCH}"
  echo "  Kompile:    ${KOMPILE_BRANCH}"
  echo "  Natives:    ${NATIVE_TARGETS}"
  if [ "${backend}" = "cuda" ]; then
    echo "  CUDA:       ${CUDA_VERSION}"
    echo "  Compute:    ${COMPUTE_CAP}"
  fi
  echo ""

  if [ "${DRY_RUN}" -eq 1 ]; then
    echo "[dry-run] ${cmd[*]}"
    return 0
  fi

  "${cmd[@]}" 2>&1 | tee "/tmp/kompile-docker-${backend}.log"
  local rc=${PIPESTATUS[0]}

  if [ "${rc}" -ne 0 ]; then
    echo "ERROR: Build failed for ${backend}. See /tmp/kompile-docker-${backend}.log" >&2
    return 1
  fi

  echo ""
  echo "=== Image built: ${tag} ==="
  "${CONTAINER_RT}" images "${tag}"

  # Extract and report SDX artifacts from the image
  local sdx_extract_dir="${PROJECT_ROOT}/dist/sdx-sdk"
  local container_id
  container_id=$("${CONTAINER_RT}" create "${tag}" /bin/true 2>/dev/null)
  if [ -n "${container_id}" ]; then
    # Try to copy SDX artifacts out of the image
    if "${CONTAINER_RT}" cp "${container_id}:/kompile/sdx-sdk" "${sdx_extract_dir}" 2>/dev/null; then
      local sdx_count
      sdx_count=$(find "${sdx_extract_dir}" -type f \( -name '*.zip' -o -name '*.aar' \) 2>/dev/null | wc -l)
      if [ "${sdx_count}" -gt 0 ]; then
        echo ""
        echo "=== SDX runtime SDK artifacts (${sdx_count}) ==="
        find "${sdx_extract_dir}" -type f \( -name '*.zip' -o -name '*.aar' \) -exec basename {} \; 2>/dev/null | sort
        echo "  Extracted to: ${sdx_extract_dir}"
      fi
    fi
    "${CONTAINER_RT}" rm "${container_id}" &>/dev/null || true
  fi

  # Push if requested
  if [ "${DO_PUSH}" -eq 1 ]; then
    echo "=== Pushing ${tag} ==="
    "${CONTAINER_RT}" push "${tag}"
  fi
}

# ─── Run / test a built image ─────────────────────────────────────────────
run_image() {
  local backend="$1"
  local tag="${IMAGE_TAG}"

  # Resolve tag same way as build_image
  if [ -z "${tag}" ]; then
    case "${backend}" in
      cuda) tag="kompile:cuda-${CUDA_VERSION}" ;;
      *)    tag="kompile:${backend}" ;;
    esac
  fi

  # Match the -test suffix that build_image appends
  if [ "${DO_TEST}" -eq 1 ]; then
    tag="${tag}-test"
  fi

  # Verify image exists (skip in dry-run mode)
  if [ "${DRY_RUN}" -eq 0 ] && ! "${CONTAINER_RT}" image inspect "${tag}" &>/dev/null; then
    echo "ERROR: Image ${tag} not found. Build it first." >&2
    return 1
  fi

  local run_args=("--rm" "-it")

  # ── GPU passthrough ────────────────────────────────────────────────────
  if [ "${backend}" = "cuda" ]; then
    if [ "${CONTAINER_RT}" = "docker" ]; then
      # Docker uses --gpus flag (requires nvidia-container-toolkit)
      run_args+=("--gpus" "${GPU_SPEC}")
    elif [ "${CONTAINER_RT}" = "podman" ]; then
      # Podman uses CDI device specs or --device flags
      if [ "${GPU_SPEC}" = "all" ]; then
        # Pass all NVIDIA devices
        run_args+=("--device" "nvidia.com/gpu=all")
        # Fallback: also try direct device nodes if CDI isn't configured
        for dev in /dev/nvidia*; do
          [ -e "$dev" ] && run_args+=("--device" "$dev")
        done
      else
        # Parse device spec: "device=0,2" or just "0,2"
        local devspec="${GPU_SPEC}"
        devspec="${devspec#\"}"
        devspec="${devspec%\"}"
        devspec="${devspec#device=}"
        IFS=',' read -ra devids <<< "${devspec}"
        for id in "${devids[@]}"; do
          if [[ "$id" =~ ^[0-9]+$ ]]; then
            # Numeric GPU index — try CDI first, fall back to device node
            run_args+=("--device" "nvidia.com/gpu=${id}")
          fi
        done
        # Also pass control devices
        for dev in /dev/nvidiactl /dev/nvidia-uvm /dev/nvidia-uvm-tools; do
          [ -e "$dev" ] && run_args+=("--device" "$dev")
        done
      fi
      # Security opt needed for podman GPU access
      run_args+=("--security-opt=label=disable")
    fi
  fi

  # ── Shared memory ──────────────────────────────────────────────────────
  local shm="${SHM_SIZE}"
  if [ -z "${shm}" ]; then
    case "${backend}" in
      cuda) shm="16g" ;;
      *)    shm="1g" ;;
    esac
  fi
  run_args+=("--shm-size=${shm}")

  # ── Port mappings ──────────────────────────────────────────────────────
  if [ -n "${EXTRA_PORTS}" ]; then
    IFS=',' read -ra ports <<< "${EXTRA_PORTS}"
    for p in "${ports[@]}"; do
      run_args+=("-p" "${p}")
    done
  fi

  # ── Volume mounts ──────────────────────────────────────────────────────
  if [ -n "${EXTRA_VOLUMES}" ]; then
    IFS=',' read -ra vols <<< "${EXTRA_VOLUMES}"
    for v in "${vols[@]}"; do
      run_args+=("-v" "${v}")
    done
  fi

  # ── Entrypoint / command override ──────────────────────────────────────
  if [ -n "${OVERRIDE_ENTRYPOINT}" ]; then
    run_args+=("--entrypoint" "${OVERRIDE_ENTRYPOINT}")
  fi

  # ── Test mode: run Maven tests inside the container ────────────────────
  local container_cmd=()
  if [ "${DO_TEST}" -eq 1 ]; then
    # Override entrypoint to bash for test execution
    run_args+=("--entrypoint" "bash")
    local test_cmd="/opt/maven/bin/mvn --batch-mode test"
    if [ -n "${TEST_CLASS}" ]; then
      test_cmd="${test_cmd} -Dtest=${TEST_CLASS}"
    fi
    test_cmd="${test_cmd} 2>&1 | tee /tmp/test-results.log"
    container_cmd+=("-c" "cd /build/kompile && ${test_cmd}")

    echo "=== Running tests in container ==="
    echo "  Image:      ${tag}"
    echo "  GPU spec:   ${GPU_SPEC}"
    echo "  SHM size:   ${shm}"
    if [ -n "${TEST_CLASS}" ]; then
      echo "  Test class: ${TEST_CLASS}"
    fi
  else
    echo "=== Running container ==="
    echo "  Image:      ${tag}"
    if [ "${backend}" = "cuda" ]; then
      echo "  GPU spec:   ${GPU_SPEC}"
    fi
    echo "  SHM size:   ${shm}"
    echo "  Ports:      ${EXTRA_PORTS}"
  fi

  if [ -n "${OVERRIDE_CMD}" ]; then
    container_cmd+=("${OVERRIDE_CMD}")
  fi

  local cmd=("${CONTAINER_RT}" run "${run_args[@]}" "${tag}" "${container_cmd[@]}")

  echo ""
  if [ "${DRY_RUN}" -eq 1 ]; then
    echo "[dry-run] ${cmd[*]}"
    return 0
  fi

  "${cmd[@]}"
}

# ─── Main ──────────────────────────────────────────────────────────────────
if [ "${BUILD_ALL}" -eq 1 ]; then
  failed=0
  for backend in cpu cuda; do
    if [ -f "${SCRIPT_DIR}/Dockerfile.${backend}" ]; then
      IMAGE_TAG="" build_image "${backend}" || failed=$((failed + 1))
    fi
  done
  if [ "${failed}" -gt 0 ]; then
    echo "ERROR: ${failed} image build(s) failed" >&2
    exit 1
  fi
else
  build_image "${BACKEND}"
fi

# Run if requested (after build)
if [ "${DO_RUN}" -eq 1 ]; then
  run_image "${BACKEND}"
fi
