#!/usr/bin/env bash
#
# run-cpu.sh — launch kompile-fpna-v6 with the CUDA backend TEMPORARILY disabled.
#
# WHY: the bundled nd4j-cuda .so currently 100%-fails Triton kernel compilation
#      (IsIsolatedFromAbove TypeID regression), which crash-loops the embedding
#      subprocess. Until the dl4j native fix lands we run on the CPU backend
#      (nd4j-native) only, so optimized-batching + UI changes can be tested.
#
# CUDA IS NOT REMOVED. The fat jar still bundles both backends; this only hides
# the GPU at runtime so JCublasBackend.canRun() == false and the CPU backend
# loads instead. Flip the toggle below to go back to GPU once it's fixed.
#
#   ./run-cpu.sh                  # default: CPU only (GPU hidden)
#   KOMPILE_FPNA_CUDA=on ./run-cpu.sh   # use the GPU again (dual-backend)
#
set -euo pipefail
cd "$(dirname "$0")"
PROJECT_DIR="$PWD"
JAR="target/kompile-fpna-v6-0.1.0-SNAPSHOT.jar"

# ---- CUDA toggle (the env-var switch) ---------------------------------------
if [ "${KOMPILE_FPNA_CUDA:-off}" = "on" ]; then
  echo "[run-cpu] CUDA ENABLED — dual-backend (GPU primary)"
  BACKEND_PRIORITY="CUDA_GPU,CPU"
else
  echo "[run-cpu] CUDA DISABLED — CPU-only (nd4j-native); GPU hidden via CUDA_VISIBLE_DEVICES=-1"
  export CUDA_VISIBLE_DEVICES=-1   # 0 visible GPUs -> CUDA backend self-skips; child subprocesses inherit this too
  BACKEND_PRIORITY="CPU"
fi

if [ ! -f "$JAR" ]; then
  echo "[run-cpu] ERROR: $JAR not found — build it first: mvn clean package -DskipTests" >&2
  exit 1
fi

mkdir -p data/logs
nohup java \
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/sun.misc=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
  -Dnd4j.multibackend.enabled=true -Dnd4j.backend.priority="$BACKEND_PRIORITY" \
  -jar "$JAR" \
  --kompile.data.dir="$PROJECT_DIR" \
  > data/logs/app.out.log 2> data/logs/app.err.log &

echo "[run-cpu] launched PID $! (backend.priority=$BACKEND_PRIORITY)"
echo "[run-cpu] logs: $PROJECT_DIR/data/logs/app.out.log"
echo "[run-cpu] readiness: curl -s localhost:8080/api/setup/status"
