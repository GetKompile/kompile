# Kompile Local SDK — Standardized Distribution Spec

## Goals

The Kompile Local SDK is a **platform-native, fully-offline SDK** that bundles:

1. **`libkompile_reasoning`** — the Kompile graph-reasoning engine (GraalVM native image).
   Exposes a 11-symbol C ABI (`kgr_*`) covering isolate lifecycle, session management,
   tool catalog listing, and tool dispatch.  Runs the full reasoning graph (Datalog / PSL /
   Bayesian / MEBN / embeddings / centrality) against `.kgraph` files with zero network access.

2. **`libsdx_llm`** — the SDX AOT LLM engine (GraalVM native image from the dl4j sdx-aot module).
   Exposes a 14-symbol C ABI (`sdxLlm*`) covering GGUF model loading, autoregressive generation,
   tokenization, VLM extraction, and audio transcription.

Together they enable any language to run a **local LLM pipeline with tool calls backed by a
reasoning graph**, matching the semantics of `kompile-chat-local` for JVM/Android/iOS.

## Layout

```
kompile-local-sdk-<ver>-<platform>.zip
├── manifest.json
├── README.md
├── include/
│   ├── kompile_reasoning.h    # kgr ABI (ABI v1, 11 symbols)
│   └── sdx_llm_c.h            # sdxLlm ABI (ABI v1, 14 symbols)
├── lib/
│   ├── libkompile_reasoning.so   # linux-x86_64; .dylib on macOS, .dll on Windows
│   └── libsdx_llm.so             # linux-x86_64 CPU; separate CUDA variant exists
│       (lib64/ contains BLAS/ND4J side-loaded libs for libsdx_llm)
├── bindings/
│   ├── python/
│   │   ├── kompile_reasoning.py   # ctypes binding (kompile, Apache-2.0)
│   │   └── sdx_llm.py             # ctypes binding (dl4j/nd4j, Apache-2.0)
│   ├── rust/
│   │   ├── kompile_reasoning.rs   # FFI + safe wrapper (mirrors llm.rs style)
│   │   └── sdx_llm.rs             # vendored from dl4j in-tree bindings
│   ├── typescript/
│   │   ├── kompile_reasoning.ts   # koffi binding (mirrors sdx_llm.ts worker style)
│   │   └── sdx_llm.ts             # vendored from dl4j in-tree bindings
│   ├── swift/
│   │   ├── KompileReasoning.swift # Swift wrapper (mirrors SdxLlm.swift style)
│   │   └── SdxLlm.swift           # vendored from dl4j in-tree bindings
│   └── csharp/
│       ├── KompileReasoning.cs    # P/Invoke wrapper (mirrors SdxLlmRuntime.cs style)
│       └── SdxLlmRuntime.cs       # vendored from dl4j in-tree bindings
└── examples/
    ├── data/fixture.kgraph          # Alice/acme test graph (WORKS_AT edge)
    ├── python/chat_with_graph.py    # REFERENCE PIPELINE — runs fully today
    ├── rust/chat_with_graph.rs      # + Cargo.toml
    ├── typescript/chat_with_graph.ts # + package.json
    ├── swift/chat_with_graph.swift  # (syntax review only — no swiftc on linux)
    └── csharp/ChatWithGraph.cs      # (dotnet not present on this box)
```

## Per-Platform Classifiers

| Classifier | Status | Notes |
|---|---|---|
| `linux-x86_64` | SHIPPING NOW | libkompile_reasoning.so (90 MB) + libsdx_llm.so (319 MB CPU) |
| `linux-x86_64-cuda` | ARTIFACT PRESENT | sdx-aot/target/aot-sdk/cuda/lib/libsdx_llm.so |
| `macos-arm64` | FUTURE | Aligns with sdx-runtime-sdk + kompile native-sdk convention |
| `windows-x86_64` | FUTURE | DLL export (`SDX_LLM_API __declspec(dllexport)` already in header) |
| `android-arm64` | FUTURE | React Native TurboModule + JNI path exists in sdx-runtime-examples |
| `ios-arm64` | FUTURE | Swift Package + ObjC++ path exists; SPM publish aligns with SdxLlm.swift |

Classifier naming follows `SdkConstants` conventions in the kompile codebase.

## Version Pairing

`manifest.json` in each zip stamps:
- `kompileVersion`: the kompile release version (kompile-graph-reasoning-local artifact version)
- `sdxVersion`: the dl4j/sdx-aot release version
- `kgrAbiVersion`: 1 (from `KGR_ABI_VERSION` in kompile_reasoning.h)
- `sdxLlmAbiVersion`: 1 (from `SDX_LLM_ABI_VERSION` in sdx_llm_c.h)
- `platform`: e.g. `linux-x86_64`
- `buildTimestamp`, `gitRevKompile`, `gitRevSdx`

Both ABI versions are CHECKED at runtime by all bindings before first use.
A mismatch aborts with a clear error rather than crashing unpredictably.

## Binding Shipping Strategy (Current)

All bindings are shipped as **vendored source** inside the zip.  No package registry
publication is required to use the SDK.  Language-specific registry publishing is planned
as Phase 2:

| Language | Current | Phase 2 target |
|---|---|---|
| Python | Vendored `.py` source | PyPI `kompile-reasoning` package + `sdx-llm` |
| Rust | Vendored `.rs` source in examples | crates.io `kompile-reasoning` + `sdx-llm` |
| TypeScript | Vendored `.ts` source | npm `@kompile/reasoning` + `@nd4j/sdx-llm` |
| Swift | Vendored `.swift` source | Swift Package Index |
| C# | Vendored `.cs` source | NuGet `Kompile.Reasoning` + `Nd4j.Sdx.Llm` |

## Canonical `LocalPipeline` API Shape Per Language

Every example implements the same logical pipeline, mirroring `ChatEngine` semantics:

```
1. openGraph(kgraphPath)   → reasoning session (kgr ABI)
2. printToolsCatalog()     → kgr_tools() → JSON array
3. openModel(modelPath)    → optional; degrades gracefully if absent
4. chatLoop:
     - user message
     - build system prompt = instructions + tools catalog JSON
     - generate(systemPrompt + userMessage)
     - parse tool calls from generation: bare {"tool","args"} or ```json fenced
     - dispatch each tool: kgr_dispatch → result JSON
     - inject TOOL_RESULT role="user" message
     - re-generate with tool results (max 4 rounds)
     - return final text to user
5. close model
6. close graph session
```

Tool call parsing (from kompile-chat-local conventions):
- Format 1: bare JSON object on its own line: `{"tool": "name", "args": {...}}`
- Format 2: JSON fenced block: ` ```json\n{"tool":...}\n``` `
- Max 4 tool-call rounds per user turn; 1 corrective retry on parse failure.
- Tool result injection: role=user, content=`TOOL_RESULT <toolName>: <resultJson>`

## Relationship to `kompile-chat-local`

`kompile-chat-local` (JVM / Android / iOS consumer) and this SDK implement the SAME
logical pipeline over the SAME two C ABIs. The difference is the consumer layer:

- `kompile-chat-local`: uses JNA/JNI to call `kgr_*` and `sdxLlm*` from the JVM.
  Used in the kompile app, Android app, and iOS app.
- `kompile-local-sdk`: ships the `.so`/`.dylib` with language bindings for Python,
  Rust, TypeScript, Swift, and C#. Used by third-party developers integrating
  locally-run LLM+reasoning pipelines.

The wire protocol (system prompt format, tool call JSON schema, TOOL_RESULT injection)
is IDENTICAL in both paths — a session exported from the kompile app can be replayed
in any language binding without modification.

## Process Model: Loading Both Libraries in One Process

Both `libkompile_reasoning.so` and `libsdx_llm.so` are full GraalVM native-image
runtimes.  Each embeds its own GraalVM runtime and exports C symbols for its own
isolate lifecycle (`graal_create_isolate`, `graal_attach_thread`, etc.).

### The Conflict

Both libraries export **9 identical `graal_*` names**, **3 `JNI_*` names**, and
**7 `__svm_*` names** at global ELF visibility.  When the first library is loaded
with `RTLD_GLOBAL`, those names populate the dynamic linker's global namespace.
When the second library initialises its own GraalVM isolate, internal bootstrap
code calls `dlsym(RTLD_DEFAULT, "graal_create_isolate")` — which finds the FIRST
library's function, routing the second isolate's init through the wrong runtime.
Result: `ExceptionInInitializerError` inside the second library.

### The Fix (defence in depth)

**Layer 1 — export allowlist on `libkompile_reasoning.so`** (compile-time).
A GNU linker version script (`src/main/linker/kgr_exports.lds`) hides all GraalVM
internal symbols from `.dynsym`, exporting **only `kgr_*`** symbols under the
`KGR_1` version node.  The build applies this via `src/main/linker/ld` — a thin
bash wrapper that intercepts GraalVM's `ld` invocation (via `gcc -B`) to substitute
the correct version script (GraalVM 21 unconditionally injects an anonymous version
script of its own; a second named script on the same command line would fatal-error).

After a correct build, `readelf -S libkompile_reasoning.so | grep VERDEF` must show
a `.gnu.version_d` section with the `KGR_1` version node.

**Layer 2 — RTLD_LOCAL in all language bindings** (call-time).

| Binding | Mechanism | Status |
|---|---|---|
| Python (`kompile_reasoning.py`, `sdx_llm.py`) | `ctypes.CDLL(path)` default = RTLD_LOCAL | Fixed 2026-07-12 with comment |
| TypeScript (`kompile_reasoning.ts`, `sdx_llm.ts`) | koffi is handle-scoped by design | Safe |
| Rust (`kompile_reasoning.rs`, `sdx_llm.rs`) | `#[link]` = link-time resolution, no RTLD_GLOBAL | Safe |
| Swift | `@_silgen_name` / bridging header = static link | Safe |
| C# (`KompileReasoning.cs`, `SdxLlmRuntime.cs`) | `NativeLibrary.Load` = RTLD_LOCAL on Linux | Safe |

**Rule: RTLD_GLOBAL is forbidden for either library.**  Any binding that passes
`RTLD_GLOBAL` (e.g. Python's `ctypes.RTLD_GLOBAL` mode) will re-introduce the conflict.

### SDX-aot status

`libsdx_llm.so` does not yet apply a symbol-visibility filter — all `graal_*`,
`JNI_*`, and `__svm_*` symbols remain in its `.dynsym`.  With Layer 2 (RTLD_LOCAL)
in place this is safe for the caller.  A matching export allowlist on the sdx-aot
side would provide defence in depth for callers who cannot guarantee RTLD_LOCAL.
The exact patch is documented in R7 item 7 of `SDX_MOBILE_LLM_C_API_HANDOFF.md`
in the dl4j repository.

### Companion Libraries (sdx only)

`libsdx_llm.so` side-loads `libjnind4jcpu.so`, `libjniopenblas.so`,
`libjnitokenizers.so` (and others) from the same directory at model-load time.
These companions are NOT included in `kompile-local-sdk/lib/` — set
`SDX_LLM_AOT_HOME` to the unpacked sdx-aot SDK root (where `lib/` contains all
side-libs alongside `libsdx_llm.so`), or add the sdx-aot `lib/` directory to
`LD_LIBRARY_PATH`.  `kompile-local-sdk/lib/` contains only the two `.so` files;
the companion set is dl4j's responsibility to deliver.

## dl4j Publishing Requirements

For non-linux-x86_64 platforms, dl4j must publish `libsdx_llm` as:

- A GitHub release artifact or Maven classifier (same convention as `nd4j-native` classifiers)
- Documented in `SDX_MOBILE_LLM_C_API_HANDOFF.md` (already exists in dl4j repo root)
- Required behaviors: streaming generate, tokenization inside library, embeddings,
  chat templating, constrained decoding (JSON-Schema/GBNF), Android/iOS mobile builds,
  memory budgets

The `kompile-local-sdk/assemble-local-sdk.sh` script has explicit `MISSING` placeholders
for each future platform that emit loud errors rather than silent omissions.

## Assembly Script Behavior

`assemble-local-sdk.sh` uses only `cp`, `zip`, and `python3 -c` (for manifest stamp).
It reads library versions from `KGR_ABI_VERSION` / `SDX_LLM_ABI_VERSION` macros in the
headers (grep-based, no compilation needed). The output zip is:
`target/kompile-local-sdk-<kompileVersion>-linux-x86_64.zip`

If `libsdx_llm.so` is absent a loud `*** MISSING ***` notice is printed to stderr and
`lib/LIBSDX_LLM_MISSING.txt` is written into the zip instead.
