# Kompile Local SDK — Standardized Distribution Spec

## Goals

The Kompile Local SDK is a **platform-native, fully-offline SDK** that bundles:

1. **`libkompile_reasoning`** — the Kompile graph-reasoning engine (GraalVM native image).
   Exposes a 11-symbol C ABI (`kgr_*`) covering isolate lifecycle, session management,
   tool catalog listing, and tool dispatch.  Runs the full reasoning graph (Datalog / PSL /
   Bayesian / MEBN / embeddings / centrality) against `.kgraph` files with zero network access.

2. **`libsdx_llm`** — the SDX AOT LLM engine (GraalVM native image from the dl4j sdx-aot module).
   Exposes the versioned ABI v2 C surface (`sdxLlm*`) covering canonical compiled-bundle
   resolution/loading, explicit tokenizer and chat-template handling, autoregressive generation,
   loose GGUF/GGML research loading, VLM extraction, and audio transcription.

Together they enable any language to run a **local LLM pipeline with tool calls backed by a
reasoning graph**, matching the semantics of `kompile-chat-local` for JVM/Android/iOS.

## Layout

```
kompile-local-sdk-<ver>-<platform>.zip
├── sdx-sdk-manifest.json             # canonical upstream file, unchanged
├── kompile-composition-manifest.json # independent upstream/KGR provenance
├── README.md
├── <canonical SDX SDK tree extracted intact from the selected AOT archive>
├── include/kompile_reasoning.h     # Kompile overlay
├── lib/libkompile_reasoning.so     # Kompile overlay
├── bindings/<language>/kompile_*   # Kompile-owned reasoning bindings only
└── examples/
    ├── data/fixture.kgraph          # optional locally supplied reasoning fixture
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

`kompile-composition-manifest.json` stamps the independent `kompileVersion` and
`kgrAbiVersion`, plus the upstream `releaseVersion`, `releaseTag`, schema version,
and SHA-256 of the untouched `sdx-sdk-manifest.json`.

Both ABI versions are CHECKED at runtime by all bindings before first use.
A mismatch aborts with a clear error rather than crashing unpredictably.

## Binding Shipping Strategy (Current)

Kompile reasoning bindings are overlaid from this repository. SDX bindings are never
vendored here; they remain whatever the canonical manifest-selected SDK provides.
Language-specific registry publishing remains a later phase.

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
3. openModel(bundlePath)   → canonical compiled SDZ/bundle plus tokenizer metadata;
                             loose GGUF/GGML is an explicit research-only path
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

- `kompile-chat-local`: uses JNA/JNI on JVM/Android and the versioned C/Swift bridge on
  iOS to call `kgr_*` and `sdxLlm*`.
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
These companions come from the canonical manifest-selected AOT archive.
Kompile assembly neither selects individual SDX libraries nor rebuilds their layout.

## dl4j Publishing Requirements

DL4J publishes `sdk-v<version>/sdx-sdk-manifest.json`; Kompile selects the artifact by
component, package role, platform, and variant and uses its exact filename and checksum.

## Assembly Ownership

`assemble-local-sdk.sh` requires the canonical manifest and checksum sidecar plus the exact
selected AOT archive. It verifies the `aot/aot-sdk/platform/variant` record, filename, size,
and SHA-256, extracts that archive intact, then overlays only Kompile reasoning artifacts.
Missing or inconsistent inputs are hard failures; no placeholder SDK is emitted.
The staged directory and ZIP are published as one journaled transaction: deterministic
backups restore both outputs after a promotion failure or interrupted process, and the
transaction commits only after both replacements are installed.

Reproducible mobile application distribution is owned by Maven and
`kompile-chat-local/mobile/cmake/FinalOfflineDistribution.cmake`, including validation,
staging, archive verification, and deterministic metadata. Missing accelerator runtimes are
hard failures in that path; placeholder libraries are never presented as runnable builds.
