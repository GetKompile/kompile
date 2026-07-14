#!/usr/bin/env bash
# assemble-local-sdk.sh — Gather all SDK components into a distributable zip
#
# Output: target/kompile-local-sdk-<version>-linux-x86_64.zip
#
# Usage:
#   ./assemble-local-sdk.sh
#
# Environment:
#   KGR_LIB_SRC   Override source of libkompile_reasoning.so (default: lib/ in this dir)
#   SDX_LIB_SRC   Override source of libsdx_llm.so (default: lib/ in this dir)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── Version extraction ────────────────────────────────────────────────────────

KGR_ABI_VERSION=$(grep '#define KGR_ABI_VERSION' include/kompile_reasoning.h \
    | awk '{print $3}' | tr -d '[:space:]')
SDX_ABI_VERSION=$(grep 'SDX_LLM_ABI_VERSION' include/sdx_llm_c.h \
    | grep '=' | head -1 | awk '{print $NF}' | tr -d ';[:space:]')

# Read kompile version from manifest.json
KOMPILE_VERSION=$(python3 -c "
import json
with open('manifest.json') as f:
    d = json.load(f)
print(d.get('kompileVersion', '0.1.0-SNAPSHOT'))
")

PLATFORM="linux-x86_64"
ZIP_NAME="kompile-local-sdk-${KOMPILE_VERSION}-${PLATFORM}.zip"
STAGE_DIR="target/stage/${ZIP_NAME%.zip}"

echo "==================================================================="
echo "Kompile Local SDK — assembling ${ZIP_NAME}"
echo "  KGR ABI version:     ${KGR_ABI_VERSION}"
echo "  SDX LLM ABI version: ${SDX_ABI_VERSION}"
echo "==================================================================="

# ── Stage directory ───────────────────────────────────────────────────────────

rm -rf "${STAGE_DIR}"
mkdir -p "${STAGE_DIR}/include"
mkdir -p "${STAGE_DIR}/lib"
mkdir -p "${STAGE_DIR}/bindings/python"
mkdir -p "${STAGE_DIR}/bindings/rust"
mkdir -p "${STAGE_DIR}/bindings/typescript"
mkdir -p "${STAGE_DIR}/bindings/swift"
mkdir -p "${STAGE_DIR}/bindings/csharp"
mkdir -p "${STAGE_DIR}/examples/python"
mkdir -p "${STAGE_DIR}/examples/rust"
mkdir -p "${STAGE_DIR}/examples/typescript"
mkdir -p "${STAGE_DIR}/examples/swift"
mkdir -p "${STAGE_DIR}/examples/csharp"
mkdir -p "${STAGE_DIR}/examples/data"

# ── Headers ───────────────────────────────────────────────────────────────────

cp include/kompile_reasoning.h "${STAGE_DIR}/include/"
cp include/sdx_llm_c.h         "${STAGE_DIR}/include/"

# Add provenance comments (prepend without disturbing license headers)
KOMPILE_HEADER_SRC="kompile/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local/include/kompile_reasoning.h"
SDX_HEADER_SRC="deeplearning4j/nd4j/sdx-aot/include/sdx_llm_c.h"
python3 -c "
import re, sys
for (f, src) in [
    ('${STAGE_DIR}/include/kompile_reasoning.h', '${KOMPILE_HEADER_SRC}'),
    ('${STAGE_DIR}/include/sdx_llm_c.h', '${SDX_HEADER_SRC}'),
]:
    with open(f) as fh: content = fh.read()
    note = f'/* SDK provenance: copied from {src} */\n'
    if '/* SDK provenance' not in content:
        with open(f, 'w') as fh: fh.write(note + content)
"

# ── Libraries ─────────────────────────────────────────────────────────────────

KGR_SRC="${KGR_LIB_SRC:-lib/libkompile_reasoning.so}"
SDX_SRC="${SDX_LIB_SRC:-lib/libsdx_llm.so}"

if [[ -f "${KGR_SRC}" ]]; then
    cp "${KGR_SRC}" "${STAGE_DIR}/lib/libkompile_reasoning.so"
    echo "[OK]  libkompile_reasoning.so  ($(du -sh "${STAGE_DIR}/lib/libkompile_reasoning.so" | cut -f1))"
else
    echo "*** MISSING: libkompile_reasoning.so not found at ${KGR_SRC}" >&2
    echo "Run ./build-native.sh in kompile-graph-reasoning-local first." >&2
    echo "LIBKOMPILE_REASONING_MISSING: expected at ${KGR_SRC}" \
        > "${STAGE_DIR}/lib/LIBKOMPILE_REASONING_MISSING.txt"
fi

if [[ -f "${SDX_SRC}" ]]; then
    cp "${SDX_SRC}" "${STAGE_DIR}/lib/libsdx_llm.so"
    echo "[OK]  libsdx_llm.so            ($(du -sh "${STAGE_DIR}/lib/libsdx_llm.so" | cut -f1))"
else
    echo "" >&2
    echo "*** MISSING: libsdx_llm.so not found at ${SDX_SRC}" >&2
    echo "    The other agent building sdx-aot has not yet produced this artifact." >&2
    echo "    Poll: find /home/agibsonccc/Documents/GitHub/deeplearning4j/nd4j/sdx-aot/target -name libsdx_llm.so" >&2
    echo "" >&2
    echo "LIBSDX_LLM_MISSING: expected at ${SDX_SRC}" \
        > "${STAGE_DIR}/lib/LIBSDX_LLM_MISSING.txt"
    echo "When present, re-run this script or copy it manually to lib/libsdx_llm.so" \
        >> "${STAGE_DIR}/lib/LIBSDX_LLM_MISSING.txt"
fi

# ── Bindings ─────────────────────────────────────────────────────────────────

echo ""
echo "Copying bindings..."

# Python
cp bindings/python/kompile_reasoning.py  "${STAGE_DIR}/bindings/python/"
cp bindings/python/sdx_llm.py            "${STAGE_DIR}/bindings/python/"
echo "[OK]  bindings/python/kompile_reasoning.py  (source: kompile)"
echo "[OK]  bindings/python/sdx_llm.py            (source: deeplearning4j)"

# Rust
cp bindings/rust/kompile_reasoning.rs    "${STAGE_DIR}/bindings/rust/"
cp bindings/rust/sdx_llm.rs             "${STAGE_DIR}/bindings/rust/"
echo "[OK]  bindings/rust/kompile_reasoning.rs    (source: kompile)"
echo "[OK]  bindings/rust/sdx_llm.rs             (source: deeplearning4j)"

# TypeScript
cp bindings/typescript/kompile_reasoning.ts  "${STAGE_DIR}/bindings/typescript/"
cp bindings/typescript/sdx_llm.ts           "${STAGE_DIR}/bindings/typescript/"
echo "[OK]  bindings/typescript/kompile_reasoning.ts  (source: kompile)"
echo "[OK]  bindings/typescript/sdx_llm.ts           (source: deeplearning4j)"

# Swift
cp bindings/swift/KompileReasoning.swift     "${STAGE_DIR}/bindings/swift/"
cp bindings/swift/SdxLlm.swift              "${STAGE_DIR}/bindings/swift/"
echo "[OK]  bindings/swift/KompileReasoning.swift     (source: kompile)"
echo "[OK]  bindings/swift/SdxLlm.swift              (source: deeplearning4j)"

# C#
cp bindings/csharp/KompileReasoning.cs       "${STAGE_DIR}/bindings/csharp/"
cp bindings/csharp/SdxLlmRuntime.cs         "${STAGE_DIR}/bindings/csharp/"
echo "[OK]  bindings/csharp/KompileReasoning.cs       (source: kompile)"
echo "[OK]  bindings/csharp/SdxLlmRuntime.cs         (source: deeplearning4j)"

# Registry publishing metadata
if [[ -f bindings/python/pyproject.toml ]]; then
    cp bindings/python/pyproject.toml "${STAGE_DIR}/bindings/python/"
    echo "[OK]  bindings/python/pyproject.toml"
fi
if [[ -f bindings/typescript/package.json ]]; then
    cp bindings/typescript/package.json "${STAGE_DIR}/bindings/typescript/"
    echo "[OK]  bindings/typescript/package.json"
fi
if [[ -f bindings/rust/Cargo.toml ]]; then
    cp bindings/rust/Cargo.toml "${STAGE_DIR}/bindings/rust/"
    echo "[OK]  bindings/rust/Cargo.toml"
fi
if [[ -f PUBLISHING.md ]]; then
    cp PUBLISHING.md "${STAGE_DIR}/PUBLISHING.md"
    echo "[OK]  PUBLISHING.md"
fi

# ── Examples ──────────────────────────────────────────────────────────────────

echo ""
echo "Copying examples..."

cp examples/python/chat_with_graph.py     "${STAGE_DIR}/examples/python/"
cp examples/rust/chat_with_graph.rs       "${STAGE_DIR}/examples/rust/"
cp examples/rust/Cargo.toml              "${STAGE_DIR}/examples/rust/"
cp examples/rust/build.rs               "${STAGE_DIR}/examples/rust/"
cp examples/typescript/chat_with_graph.ts "${STAGE_DIR}/examples/typescript/"
cp examples/typescript/package.json      "${STAGE_DIR}/examples/typescript/"
cp examples/typescript/tsconfig.json     "${STAGE_DIR}/examples/typescript/"
cp examples/swift/chat_with_graph.swift  "${STAGE_DIR}/examples/swift/"
cp examples/csharp/ChatWithGraph.cs      "${STAGE_DIR}/examples/csharp/"
cp examples/data/fixture.kgraph         "${STAGE_DIR}/examples/data/"
echo "[OK]  examples/ (all 5 languages + fixture.kgraph)"

# ── Manifest + README ─────────────────────────────────────────────────────────

# Stamp build timestamp into manifest
python3 -c "
import json, datetime
with open('manifest.json') as f:
    m = json.load(f)
m['buildTimestamp'] = datetime.datetime.utcnow().isoformat() + 'Z'
m['kgrAbiVersion'] = int('${KGR_ABI_VERSION}')
m['sdxLlmAbiVersion'] = int('${SDX_ABI_VERSION}')
with open('${STAGE_DIR}/manifest.json', 'w') as f:
    json.dump(m, f, indent=2)
"
echo "[OK]  manifest.json (build-stamped)"

cp README.md "${STAGE_DIR}/README.md"
echo "[OK]  README.md"

# ── Zip ───────────────────────────────────────────────────────────────────────

mkdir -p target
TARGET_ZIP="target/${ZIP_NAME}"
rm -f "${TARGET_ZIP}"
(cd target/stage && zip -r "../${ZIP_NAME}" "${ZIP_NAME%.zip}/")

echo ""
echo "==================================================================="
echo "Assembly complete: target/${ZIP_NAME}"
echo ""
unzip -l "target/${ZIP_NAME}" | tail -n +4 | head -60
echo "==================================================================="
