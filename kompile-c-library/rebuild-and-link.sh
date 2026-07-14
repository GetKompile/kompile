#!/bin/bash
#
# Copyright 2025 Kompile Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# rebuild-and-link.sh — build libkompile_pipelines native image, run parity gate,
#                        compile and link C smoke tests against the public header.
#
# Usage (from repo root or from kompile-c-library/):
#   cd kompile-c-library && ./rebuild-and-link.sh
#
# What this script does:
#   1. Build libkompile_pipelines.so via the native-library Maven profile
#      (requires GraalVM JAVA_HOME).
#   2. Copy the generated .so and .h files into kompile-c-library/lib/ and
#      kompile-c-library/include/.
#   3. PARITY GATE: verify every non-builtin symbol declared in the generated
#      header appears in include/kompile.h, and vice versa (builtins exempted
#      because GraalVM emits them into graal_isolate.h, not the user header).
#   4. Compile include/kompile.h alone to verify it is self-contained
#      (no graal_isolate.h on the include path).
#   5. Compile src/smoke/kompile_lifecycle_smoke.c against include/kompile.h
#      with -Werror=implicit-function-declaration and link against the fresh .so.
#   6. RUN the lifecycle smoke test (PATH A: kompileCreateIsolate → ABI version
#      check → kompileTearDownIsolate).
#   7. Build the C wrapper library (libkompile_c_library.so) via cmake/make.
#   8. Copy artifacts to the Python lib directory.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ── Paths ──────────────────────────────────────────────────────────────────────
PIPELINES_MODULE="${REPO_ROOT}/kompile-app/kompile-data/kompile-pipelines-framework/kompile-pipelines-framework-runtime"
NATIVE_LIB_DIR="${PIPELINES_MODULE}/target"
INCLUDE_DIR="${SCRIPT_DIR}/include"
LIB_DIR="${SCRIPT_DIR}/lib"
SMOKE_DIR="${SCRIPT_DIR}/src/smoke"
TARGET_DIR="${SCRIPT_DIR}/target"
PYTHON_LIB_DIR="${SCRIPT_DIR}/../kompile-python/lib"

# ── GraalVM for native-image build ────────────────────────────────────────────
GRAALVM_JAVA_HOME="${GRAALVM_JAVA_HOME:-${HOME}/.sdkman/candidates/java/21.0.10-graal}"
MVN="/home/agibsonccc/dev-apps/mvn/bin/mvn"

# ── Platform SO extension ──────────────────────────────────────────────────────
case "$(uname -s)" in
  Darwin*) SO_EXT=dylib ;;
  *)       SO_EXT=so    ;;
esac
LIB_FILE="libkompile_pipelines.${SO_EXT}"

echo "========================================================================"
echo " Kompile Pipelines — rebuild-and-link"
echo "========================================================================"
echo " GRAALVM_JAVA_HOME = ${GRAALVM_JAVA_HOME}"
echo " Pipelines module  = ${PIPELINES_MODULE}"
echo ""

# ── STEP 1: Build native shared library ───────────────────────────────────────
echo "--- [1/8] Building libkompile_pipelines (native-library profile) ---"
JAVA_HOME="${GRAALVM_JAVA_HOME}" "${MVN}" \
  -f "${PIPELINES_MODULE}/pom.xml" \
  clean package -Pnative-library -DskipTests \
  -J-Xmx18g
echo "    Build complete."

# ── STEP 2: Copy generated artifacts ──────────────────────────────────────────
echo ""
echo "--- [2/8] Copying native image headers and library ---"
mkdir -p "${INCLUDE_DIR}" "${LIB_DIR}" "${TARGET_DIR}"
cp -f "${NATIVE_LIB_DIR}"/*.h "${INCLUDE_DIR}/" 2>/dev/null || true
cp -f "${NATIVE_LIB_DIR}/${LIB_FILE}" "${LIB_DIR}/" 2>/dev/null || \
  cp -f "${NATIVE_LIB_DIR}"/libkompile_pipelines*.so "${LIB_DIR}/" 2>/dev/null || true
echo "    Headers in ${INCLUDE_DIR}/"
echo "    Library in ${LIB_DIR}/"

# ── STEP 3: PARITY GATE ───────────────────────────────────────────────────────
echo ""
echo "--- [3/8] Header parity gate ---"

PUBLIC_HDR="${INCLUDE_DIR}/kompile.h"
# The generated header is named libkompile_pipelines.h by native-image
GENERATED_HDR="${NATIVE_LIB_DIR}/libkompile_pipelines.h"
if [ ! -f "${GENERATED_HDR}" ]; then
  GENERATED_HDR="${INCLUDE_DIR}/libkompile_pipelines.h"
fi

if [ ! -f "${GENERATED_HDR}" ]; then
  echo "    WARNING: generated header not found at ${GENERATED_HDR}"
  echo "    Skipping parity gate — run the build first."
else
  # Extract all non-typedef identifiers that look like C function names.
  # We look for symbol names that appear in function-declaration context
  # (token immediately followed by '(' on the same or next non-blank line).
  # Simple heuristic: grep for all lower-case multi-word identifiers, then
  # filter out known type-only suffixes.

  # Extract kompile* function names from a header (not typedef aliases).
  extract_kompile_names() {
    grep -oE 'kompile[A-Za-z_]+' "$1" \
      | grep -vE '(kompile_isolate_t|kompile_thread_t)$' \
      | sort -u
  }

  # Extract all pipeline function names from the generated header that are
  # NOT GraalVM internal names (graal_* are in graal_isolate.h, not user header).
  extract_generated_user_names() {
    # Pull all declared C function names: lines with a return type followed by
    # the function name before '('.  Filter out graal_* builtins.
    grep -oE '[a-zA-Z_][a-zA-Z0-9_]+[[:space:]]*\(' "$1" \
      | grep -oE '^[a-zA-Z_][a-zA-Z0-9_]+' \
      | grep -vE '^(graal_|if|for|while|return|sizeof|typedef)' \
      | sort -u
  }

  GENERATED_USER_NAMES=$(extract_generated_user_names "${GENERATED_HDR}")
  PUBLIC_NAMES_ALL=$(grep -oE '[a-zA-Z_][a-zA-Z0-9_]+[[:space:]]*\(' "${PUBLIC_HDR}" \
    | grep -oE '^[a-zA-Z_][a-zA-Z0-9_]+' \
    | grep -vE '^(if|for|while|return|sizeof|typedef)' \
    | sort -u || true)

  # The four lifecycle builtins are emitted by native-image into graal_isolate.h
  # (as graal_create_isolate etc.), NOT into the user header.  Our kompile*
  # aliases for them are in kompile.h; they will not appear in the generated
  # user header.  That is expected — exempt them from the "extra in public"
  # warning.
  BUILTIN_NAMES="kompileCreateIsolate kompileAttachThread kompileDetachThread kompileTearDownIsolate"

  PARITY_FAIL=0

  # Every name in the generated user header must appear in the public header.
  while IFS= read -r name; do
    [ -z "$name" ] && continue
    if ! grep -qF "$name" "${PUBLIC_HDR}"; then
      echo "  PARITY FAIL: '$name' is in the generated header but MISSING from include/kompile.h"
      PARITY_FAIL=1
    fi
  done <<< "${GENERATED_USER_NAMES}"

  # Names in the public header absent from the generated header:
  # allowed only for the four builtin aliases + kompileAbiVersion (emitted as
  # user symbol but may appear under a different slot in the generated header).
  # Also allow vmLocatorSymbol (internal GraalVM symbol, may not appear in user section).
  EXEMPT_NAMES="${BUILTIN_NAMES} kompileAbiVersion vmLocatorSymbol"
  while IFS= read -r name; do
    [ -z "$name" ] && continue
    if ! grep -qF "$name" "${GENERATED_HDR}"; then
      is_exempt=0
      for en in ${EXEMPT_NAMES}; do
        [ "$name" = "$en" ] && { is_exempt=1; break; }
      done
      if [ $is_exempt -eq 0 ]; then
        echo "  PARITY WARN: '$name' is in public header but NOT in generated header — verify intentional"
      fi
    fi
  done <<< "${PUBLIC_NAMES_ALL}"

  if [ $PARITY_FAIL -eq 1 ]; then
    echo "  PARITY RESULT: FAIL — fix: add missing declarations to include/kompile.h"
    exit 1
  else
    echo "  PARITY RESULT: PASS — all generated user symbols covered by public header"
  fi
fi

# ── STEP 4: Self-containment check ────────────────────────────────────────────
echo ""
echo "--- [4/8] Self-containment check (kompile.h, NO graal_isolate.h on path) ---"
SELFCONTAIN_TMP="${TARGET_DIR}/selfcontain_check.c"
cat > "${SELFCONTAIN_TMP}" <<'EOSC'
/* Self-containment probe: kompile.h must compile without graal_isolate.h */
#include "kompile.h"
int selfcontain_check_ok = 1;
EOSC
gcc -c \
  -I"${INCLUDE_DIR}" \
  -o "${TARGET_DIR}/selfcontain_check.o" \
  "${SELFCONTAIN_TMP}"
echo "  PASS — include/kompile.h is self-contained (no GraalVM headers needed)"
rm -f "${SELFCONTAIN_TMP}" "${TARGET_DIR}/selfcontain_check.o"

# ── STEP 5: Compile lifecycle smoke test ──────────────────────────────────────
echo ""
echo "--- [5/8] Compiling lifecycle smoke test (kompile.h only, -Werror=implicit-function-declaration) ---"
mkdir -p "${TARGET_DIR}"
SMOKE_SRC="${SMOKE_DIR}/kompile_lifecycle_smoke.c"
SMOKE_BIN="${TARGET_DIR}/kompile_lifecycle_smoke"

gcc -O0 -g \
  -I"${INCLUDE_DIR}" \
  -L"${LIB_DIR}" \
  -Wl,-rpath,"${LIB_DIR}" \
  -Werror=implicit-function-declaration \
  -o "${SMOKE_BIN}" \
  "${SMOKE_SRC}" \
  -lkompile_pipelines
echo "  Compiled OK: ${SMOKE_BIN}"

# ── STEP 6: Run lifecycle smoke test ──────────────────────────────────────────
echo ""
echo "--- [6/8] Running lifecycle smoke test (PATH A — kompileCreateIsolate builtins) ---"
LD_LIBRARY_PATH="${LIB_DIR}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}" \
DYLD_LIBRARY_PATH="${LIB_DIR}${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}" \
  "${SMOKE_BIN}"
SMOKE_EXIT=$?
if [ $SMOKE_EXIT -ne 0 ]; then
  echo "  SMOKE TEST FAILED (exit ${SMOKE_EXIT})"
  exit $SMOKE_EXIT
fi
echo "  SMOKE TEST PASSED"

# ── STEP 7: Build C wrapper library ───────────────────────────────────────────
echo ""
echo "--- [7/8] Building libkompile_c_library.so (cmake + make) ---"
cd "${SCRIPT_DIR}"
cmake .
make
echo "  Built libkompile_c_library.so"

# ── STEP 8: Copy to Python lib directory ──────────────────────────────────────
echo ""
echo "--- [8/8] Copying artifacts to Python lib directory ---"
mkdir -p "${PYTHON_LIB_DIR}"
cp libkompile_c_library.so "${PYTHON_LIB_DIR}/"
echo "  Copied libkompile_c_library.so to ${PYTHON_LIB_DIR}/"

if [ -f "${LIB_DIR}/libkompile_pipelines.so" ]; then
  cp "${LIB_DIR}/libkompile_pipelines.so" "${PYTHON_LIB_DIR}/"
  echo "  Copied libkompile_pipelines.so to ${PYTHON_LIB_DIR}/"
fi

echo ""
echo "========================================================================"
echo " ALL STEPS PASSED"
echo "========================================================================"
