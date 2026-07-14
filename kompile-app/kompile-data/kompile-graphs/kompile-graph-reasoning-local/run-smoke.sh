#!/usr/bin/env bash
# run-smoke.sh — build and run the kgr_* C ABI smoke test
#
# Usage:
#   cd kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local
#   ./run-smoke.sh
#
# Prerequisites:
#   1. The native library has been built (see build-native.sh):
#        target/libkompile_reasoning.so  (or .dylib on macOS)
#        target/kompile_reasoning.h      (generated — used only for parity check)
#   2. gcc is on PATH
#   3. The module jar (or test-classes) is available in target/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/target"
INCLUDE_DIR="$SCRIPT_DIR/include"

# ── Determine platform shared-lib extension ───────────────────────────────────
case "$(uname -s)" in
  Darwin*) SO_EXT=dylib ;;
  *)       SO_EXT=so    ;;
esac

LIB_NAME="libkompile_reasoning.$SO_EXT"
LIB_PATH="$TARGET/$LIB_NAME"

# Public checked-in header (consumers use this — not the generated one)
PUBLIC_HDR="$INCLUDE_DIR/kompile_reasoning.h"

# Generated header produced by native-image (used only for parity gate)
GENERATED_HDR="$TARGET/libkompile_reasoning.h"
# Fallback: older image name variant
if [ ! -f "$GENERATED_HDR" ]; then
  GENERATED_HDR="$TARGET/kompile_reasoning.h"
fi

if [ ! -f "$LIB_PATH" ]; then
  echo "ERROR: $LIB_PATH not found — run build-native.sh first"
  exit 1
fi

if [ ! -f "$PUBLIC_HDR" ]; then
  echo "ERROR: $PUBLIC_HDR not found — has include/kompile_reasoning.h been created?"
  exit 1
fi

# ── Build the classpath for the fixture writer ────────────────────────────────
# Collect all jars in the local Maven repository that are dependencies of this
# module. Simplest approach: use the module's own dependency classpath that Maven
# already computed during test-compile.  We grab it from the surefire classpath
# file if present, otherwise fall back to a glob.
CP_FILE="$TARGET/dependency-classpath.txt"
if [ ! -f "$CP_FILE" ]; then
  echo "Building dependency classpath..."
  JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"
  # Write the classpath to a file using the dependency plugin
  "/home/agibsonccc/dev-apps/mvn/bin/mvn" -f "$SCRIPT_DIR/pom.xml" \
    dependency:build-classpath -DincludeScope=test \
    -Dmdep.outputFile="$CP_FILE" -q
fi

# Build full classpath: test-classes + main-classes + deps
FULL_CP="$TARGET/test-classes:$TARGET/classes:$(cat "$CP_FILE")"

# ── Write the fixture .kgraph ─────────────────────────────────────────────────
FIXTURE="$TARGET/fixture.kgraph"
ROUNDTRIP="$TARGET/roundtrip.kgraph"
RETRACT="$TARGET/retract.kgraph"

echo "--- Writing fixture.kgraph ---"
JAVA_CMD="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}/bin/java"
"$JAVA_CMD" -cp "$FULL_CP" \
  ai.kompile.graph.reasoning.local.fixture.WriteFixtureKgraph \
  "$FIXTURE"

# ── PARITY GATE: public header must declare every kgr_* in the generated header
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "--- Header parity check (include/kompile_reasoning.h vs generated) ---"

# Extract kgr_* FUNCTION-name tokens from a header file.
# We look for tokens that appear as C function declarations (followed by '('),
# rather than typedef/struct names (which end with '_t' and appear after 'typedef').
# Simple heuristic: extract all kgr_* tokens, then exclude known typedef-only names.
extract_kgr_names() {
  grep -oE 'kgr_[a-zA-Z_]+' "$1" \
    | grep -vE '^kgr_(isolate_t|thread_t|session_t|create_isolate_params_t)$' \
    | sort -u
}

GENERATED_NAMES=$(extract_kgr_names "$GENERATED_HDR")
PUBLIC_NAMES=$(extract_kgr_names "$PUBLIC_HDR")

PARITY_FAIL=0

# Every name in the generated header must appear in the public header
while IFS= read -r name; do
  if ! grep -qF "$name" "$PUBLIC_HDR"; then
    echo "  PARITY FAIL: '$name' is in the generated header but MISSING from include/kompile_reasoning.h"
    PARITY_FAIL=1
  fi
done <<< "$GENERATED_NAMES"

# Names in the public header that are absent from the generated header are allowed
# ONLY for the four builtin lifecycle symbols (native-image emits graal_* for
# builtins in graal_isolate.h, not in the user-symbol header).
# Any other extra public symbol is a warning so the maintainer is aware.
BUILTIN_NAMES="kgr_create_isolate kgr_attach_thread kgr_detach_thread kgr_tear_down_isolate"
while IFS= read -r name; do
  if ! grep -qF "$name" "$GENERATED_HDR"; then
    is_builtin=0
    for bn in $BUILTIN_NAMES; do
      [ "$name" = "$bn" ] && { is_builtin=1; break; }
    done
    if [ $is_builtin -eq 0 ]; then
      echo "  PARITY WARN: '$name' is in the public header but NOT in the generated header — verify intentional"
    fi
  fi
done <<< "$PUBLIC_NAMES"

if [ $PARITY_FAIL -eq 1 ]; then
  echo "  PARITY RESULT: FAIL"
  echo "  Fix: add the missing declarations to include/kompile_reasoning.h"
  exit 1
else
  echo "  PARITY RESULT: PASS — every generated kgr_* symbol is covered by the public header"
fi

# ── Compile the C smoke test against the PUBLIC checked-in header ─────────────
SMOKE_SRC="$SCRIPT_DIR/src/smoke/main.c"
SMOKE_BIN="$TARGET/smoke"

echo ""
echo "--- Compiling C smoke test (include/kompile_reasoning.h, -Werror=implicit-function-declaration) ---"
gcc -O0 -g \
  -I"$INCLUDE_DIR" \
  -L"$TARGET" \
  -Wl,-rpath,"$TARGET" \
  -Werror=implicit-function-declaration \
  -o "$SMOKE_BIN" \
  "$SMOKE_SRC" \
  -lkompile_reasoning

# ── Run the C smoke test ──────────────────────────────────────────────────────
echo "--- Running C smoke test ---"
LD_LIBRARY_PATH="$TARGET${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  DYLD_LIBRARY_PATH="$TARGET${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}" \
  "$SMOKE_BIN" "$FIXTURE" "$ROUNDTRIP" "$RETRACT"

C_EXIT_CODE=$?

# ── Python mini-smoke ─────────────────────────────────────────────────────────
echo ""
PY_BINDING="$SCRIPT_DIR/bindings/python/kompile_reasoning.py"
PYTHON_SMOKE_STATUS=0

if command -v python3 >/dev/null 2>&1; then
  echo "--- Running Python mini-smoke (bindings/python/kompile_reasoning.py) ---"
  PY_VERSION=$(python3 --version 2>&1)
  echo "  python3: $PY_VERSION"

  LD_LIBRARY_PATH="$TARGET${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    DYLD_LIBRARY_PATH="$TARGET${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}" \
    python3 "$PY_BINDING" "$LIB_PATH" "$FIXTURE"
  PYTHON_SMOKE_STATUS=$?

  if [ $PYTHON_SMOKE_STATUS -eq 0 ]; then
    echo "  Python smoke: PASS"
  else
    echo "  Python smoke: FAIL (exit $PYTHON_SMOKE_STATUS)"
  fi
else
  echo "  SKIP: python3 not found on PATH — skipping Python mini-smoke"
fi

# ── Final result ──────────────────────────────────────────────────────────────
echo ""
if [ $C_EXIT_CODE -eq 0 ] && [ $PYTHON_SMOKE_STATUS -eq 0 ]; then
  echo "SMOKE TEST PASSED (parity + C + Python)"
elif [ $C_EXIT_CODE -ne 0 ]; then
  echo "SMOKE TEST FAILED — C smoke exit=$C_EXIT_CODE"
  exit $C_EXIT_CODE
else
  echo "SMOKE TEST FAILED — Python smoke exit=$PYTHON_SMOKE_STATUS"
  exit $PYTHON_SMOKE_STATUS
fi
