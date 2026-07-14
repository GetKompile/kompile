#!/usr/bin/env bash
# build-native.sh — one-command build of libkompile_reasoning.so
#
# Usage:
#   cd kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local
#   ./build-native.sh
#
# Output (in target/):
#   libkompile_reasoning.so   — the shared C library
#   graal_isolate.h           — GraalVM isolate lifecycle API (from GraalVM itself)
#   kompile_reasoning.h       — generated kgr_* function declarations
#
# Prerequisites:
#   GraalVM 21 at ~/.sdkman/candidates/java/21.0.10-graal
#   Maven at /home/agibsonccc/dev-apps/mvn/bin/mvn

set -euo pipefail

GRAALVM_HOME="${GRAALVM_HOME:-$HOME/.sdkman/candidates/java/21.0.10-graal}"
MVN="/home/agibsonccc/dev-apps/mvn/bin/mvn"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Kompile Graph Reasoning — Native C Library Build ==="
echo "  GRAALVM_HOME : $GRAALVM_HOME"
echo "  Module       : $SCRIPT_DIR"
echo ""

# Verify native-image is present
if [ ! -x "$GRAALVM_HOME/bin/native-image" ]; then
  echo "ERROR: native-image not found at $GRAALVM_HOME/bin/native-image"
  echo "       Set GRAALVM_HOME to the GraalVM 21 installation directory."
  exit 1
fi

# Ensure the ld wrapper script is executable.
# src/main/linker/ld intercepts the GCC linker invocation so we can replace
# GraalVM's auto-generated anonymous version script with kgr_exports.lds.
# (See pom.xml -H:NativeLinkerOption=-B... comment for the full rationale.)
chmod +x "$SCRIPT_DIR/src/main/linker/ld"

# Build via the repo-standard C-library profile (same id as
# kompile-pipelines-framework-runtime / kompile-c-library). The profile also
# auto-activates on -Dkompile.dist, so full dist builds produce this library too.
JAVA_HOME="$GRAALVM_HOME" \
  "$MVN" -f "$SCRIPT_DIR/pom.xml" \
  -Pnative-library \
  -DskipTests \
  package

echo ""
echo "=== Build complete ==="
ls -lh "$SCRIPT_DIR/target/"*.so "$SCRIPT_DIR/target/"*.h 2>/dev/null \
  || ls -lh "$SCRIPT_DIR/target/"*.dylib "$SCRIPT_DIR/target/"*.h 2>/dev/null \
  || echo "(no .so/.dylib or .h found — check target/ for native-image output)"
