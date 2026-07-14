# Kompile Local SDK

Standardized, fully-local distribution that combines **Kompile graph reasoning**
with the **SDX AOT LLM engine** — one SDK that lets any language execute LLM
pipelines locally: SDX runs the model, Kompile graph reasoning answers tool calls,
same pipeline shape in every language.

## What's inside

| Component | File | ABI | Size |
|---|---|---|---|
| `libkompile_reasoning` | `lib/libkompile_reasoning.so` | kgr v1 | ~90 MB |
| `libsdx_llm` | `lib/libsdx_llm.so` | sdxLlm v1 | ~319 MB |
| C headers | `include/` | — | — |
| Python binding | `bindings/python/` | ctypes | vendored source |
| Rust binding | `bindings/rust/` | FFI extern-C | vendored source |
| TypeScript binding | `bindings/typescript/` | koffi | vendored source |
| Swift binding | `bindings/swift/` | SPM system-library | vendored source |
| C# binding | `bindings/csharp/` | P/Invoke | vendored source |

## Quick start by language

### Python (runs today, no install needed)

```bash
# Reasoning-only (no model required):
python3 examples/python/chat_with_graph.py

# Full pipeline with model (requires aot-sdk with companion JNI libs):
# SDX_LLM_AOT_HOME must point to the aot-sdk/cpu dir (not the SDK lib/ dir)
# — it needs libjnind4jcpu.so, libmkl_*.so, libjnitokenizers.so etc.
KOMPILE_SDK_MODEL_PATH=~/.kompile/models/chat/qwen2.5-1.5b-instruct-fp16.gguf \
SDX_LLM_AOT_HOME=~/Documents/GitHub/deeplearning4j/nd4j/sdx-aot/target/aot-sdk/cpu \
    python3 examples/python/chat_with_graph.py
# Note: tokenizer is auto-resolved from GGUF metadata (no sidecar needed)
```

### Rust

```bash
cd examples/rust
KGR_LIB_DIR=../../lib SDX_LLM_LIB_DIR=../../lib cargo build --release
LD_LIBRARY_PATH=../../lib ./target/release/chat_with_graph
```

### TypeScript / Node

```bash
cd examples/typescript
npm install
npm run build
LD_LIBRARY_PATH=../../lib npm start
```

### Swift (macOS only — no swiftc on Linux)

```bash
# From examples/swift/
swift build -Xlinker -L../../lib -Xcc -I../../include
.build/debug/chat_with_graph
```

### C# (requires .NET 8 SDK — not present on this box)

```bash
cd examples/csharp
dotnet run
```

## Pipeline semantics (all languages identical)

Every example implements the same logical pipeline, mirroring `kompile-chat-local`:

1. **Open graph session**: `kgr_open(thread, kgraph_path)` → session handle
2. **Get tools catalog**: `kgr_tools(thread)` → JSON array of tool descriptors
3. **Open model** (optional): `sdxLlmLoadModel(...)` → model handle
4. **Chat loop**:
   - Build system prompt: `<instructions> + <tools catalog JSON>`
   - Generate: LLM call with system prompt + user message
   - Parse tool calls: bare `{"tool":"name","args":{...}}` OR fenced JSON block
   - Dispatch: `kgr_dispatch(thread, session, tool_name, args_json)` → result JSON
   - Inject result: role=user `TOOL_RESULT <tool>: <json>`
   - Re-generate (max 4 rounds)
5. **Close**: session → isolate → model → runtime

## Loading both libraries in one process

Both `libkompile_reasoning` and `libsdx_llm` embed a complete GraalVM runtime and
export overlapping `graal_*` / `JNI_*` symbols.  Two rules must be followed:

1. **Never load with RTLD_GLOBAL.** All bundled bindings use `RTLD_LOCAL` (the OS
   default for `dlopen` / ctypes / koffi / NativeLibrary.Load).  Loading either
   library with `RTLD_GLOBAL` pollutes the global dynamic linker namespace, causing
   the second library's isolate bootstrap to call the wrong runtime and crash with
   `ExceptionInInitializerError`.

2. **Export allowlist (BOTH sides, 2026-07-12):**
   - `libkompile_reasoning.so`: version script `KGR_1 { global: kgr*; local: *; }` applied
   - `libsdx_llm.so`: version script `SDX_LLM_1 { global: sdx*; local: *; }` applied (FIX 1)

   Both libraries hide all GraalVM internals (`graal_*`, `JNI_*`, `__svm_*`) from
   `.dynsym`.  After a correct build each `.so` must show a `.gnu.version_d` VERDEF section.
   Verify: `nm -D --defined-only libsdx_llm.so | grep -c "graal_\|JNI_\|__svm_"` → 0.

   **RE-PROOF (2026-07-12):** both loaded in one Python process, KGR verify +
   SDX load both succeeded (vocabSize=151936) — zero `ExceptionInInitializerError`.

Companion libraries for `libsdx_llm` (`libjnind4jcpu.so`, `libjniopenblas.so`,
`libjnitokenizers.so`, …) are NOT in `lib/` here — they ship with the sdx-aot SDK.
Set `SDX_LLM_AOT_HOME` to the sdx-aot SDK root, or add its `lib/` to
`LD_LIBRARY_PATH`.

## Environment variables

| Variable | Purpose |
|---|---|
| `KGR_LIBRARY` | Explicit path to `libkompile_reasoning.so` |
| `SDX_LLM_AOT_HOME` | Directory with `lib/libsdx_llm.so` |
| `SDX_LLM_LIBRARY` | Explicit path to `libsdx_llm.so` |
| `SDX_NATIVE_LIB_DIR` | Side-loaded natives directory for libsdx_llm |
| `KOMPILE_SDK_KGRAPH` | Path to `.kgraph` file (default: examples/data/fixture.kgraph) |
| `KOMPILE_SDK_MODEL_PATH` | Path to a GGUF chat model |

## Current-state matrix

| Language | Reasoning | Model | Build | Notes |
|---|---|---|---|---|
| Python | LIVE | LIVE (fp16 or q4_k_m, no sidecar needed) | No build needed | Runs today; embedded tokenizer includes all 22 Qwen2.5 special tokens (R8 item 4 fixed 2026-07-12) |
| Rust | Source ready | Source ready | `cargo build` | No install needed |
| TypeScript | Source ready | Source ready | `npm install && npm run build` | koffi required |
| Swift | Source ready | Source ready | macOS only | No swiftc on Linux |
| C# | Source ready | Source ready | dotnet absent | NOT COMPILED |

## Assembly

```bash
./assemble-local-sdk.sh
# → target/kompile-local-sdk-0.1.0-SNAPSHOT-linux-x86_64.zip
```

## Future platforms

- `macos-arm64`: dl4j must build libsdx_llm for Apple Silicon + publish via GitHub release
- `windows-x86_64`: DLL exports already in sdx_llm_c.h header (Windows `__declspec(dllexport)`)
- `android-arm64`: React Native TurboModule + JNI path exists in sdx-runtime-examples
- `ios-arm64`: Swift Package + ObjC++ path exists; SPM publish path defined

See `docs/architecture/kompile-local-sdk.md` for the full design specification.
