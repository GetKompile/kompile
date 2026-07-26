# kompile-chat-local

A **local-first chat application and graph MCP server**: load a `.kgraph`
knowledge graph and an SDX model, chat with the model, and let it tool-call the
local graph-reasoning dispatcher. The same graph backend is exposed as a real
MCP 2024-11-05 stdio server for external clients. Companion design:
`docs/architecture/graph-reasoning-mobile-aot.md`; dl4j-side dependencies:
`~/Documents/GitHub/deeplearning4j/SDX_MOBILE_LLM_C_API_HANDOFF.md`.

`kompile-chat-local` is part of the repository root reactor. The normal JVM and
MCP builds use Maven only:

```bash
# build and test the library, MCP server, and CLI
./mvnw -pl :kompile-chat-local-cli -am test

# build the two runnable shaded jars
./mvnw -pl :kompile-chat-local-cli -am -DskipTests package
# See "Running the CLI" below for chat model/graph arguments.
java -jar kompile-chat-local/kompile-chat-local-cli/target/kompile-chat-local-cli-0.1.0-SNAPSHOT-mcp.jar --kgraph /path/to/graph.kgraph

# build the standalone GraalVM MCP executable
./mvnw -pl :kompile-chat-local-cli -am -Pnative-mcp -DskipTests package
kompile-chat-local/kompile-chat-local-cli/target/kompile-chat-local-mcp --kgraph /path/to/graph.kgraph
```

The Android module remains opt-in with `-Dkompile.mobile=<variant>`. Its Maven
lifecycle consumes Maven-installed graph and SDX AAR artifacts; see
`mobile/android/README.md` for the producer and APK commands.

## Modules

| Module | What it is |
|---|---|
| `kompile-chat-local-core` | Pure-JVM engine: `ChatEngine` tool loop (max 4 rounds, corrective retry), `ToolCallParser`, `GraphToolBridge` (LocalReasoningSession + LocalToolDispatcher), `SdxChatModel` (JNA → `libsdx_llm`, text-level `sdxLlm*` ABI v1), `SdxSubprocessChatModel` (subprocess via `sdx-llm` binary — avoids GraalVM isolate conflict when running inside JVM), `RemoteChatModel` (OpenAI-compatible `/v1/chat/completions`), `InferenceRouter` (local-first, remote fallback), `ChatConfig` (properties + `KOMPILE_CHAT_*` env) |
| `kompile-chat-local-mcp` | Embeddable MCP 2024-11-05 server and stdio transport exposing the complete local graph tool catalog through `initialize`, `tools/list`, and `tools/call` |
| `kompile-chat-local-cli` | Two launchers: the interactive terminal REPL in the `exec` shaded jar and the graph MCP stdio server in the `mcp` shaded jar; the MCP launcher also has a GraalVM `native-mcp` profile |
| `kompile-chat-local-mobile` | Profile-gated Maven lifecycle owner for Maven artifact staging, Android APK assembly, final-APK verification, and the deterministic all-runtime ZIP; supported paths contain no Python |
| `mobile/android` | Fully offline Compose app (minSdk 28): stock-GraalVM/NDK graph AOT through JavaCPP plus separate Vulkan GPU, Hexagon/HTP, and Tensor G5 TPU/NPU flavors; device-only and fail-closed with no CPU/OpenBLAS fallback |
| `mobile/ios` | SwiftUI app (iOS 16+, XcodeGen `project.yml`). Swift **port** of the ChatEngine loop (verbatim prompts/conventions); binds `kgr_*` (`kompile_reasoning.h`) and `sdxLlm*` behind `#if canImport` guards; URLSession remote fallback |

## MCP graph server

This is an actual MCP server, not only a `.kgraph` importer. It publishes the
same 16-tool catalog used by the chat engine, including graph loading, search,
reasoning, explanation, assertions, validation, and export operations. Start it
with an empty graph and call `graph_load`, or preload a graph with `--kgraph`.
Both the JVM shaded jar and the GraalVM executable speak MCP over stdio and keep
stdout reserved for protocol messages.

## Conventions (identical across JVM/Android/iOS)

- Tool call: model replies with bare or ```json-fenced `{"tool":"<name>","args":{...}}`
- Tool result: appended as role `user`, content `TOOL_RESULT <tool>: <json>` (OpenAI-compat safe)
- System prompt: compact instructions + primary tool example (full catalog excluded to fit small model context)
- Local prompt template: CHATML_IM (`<|im_start|>/<|im_end|>`) for Qwen2.x/Phi-3; GENERIC_PIPE for others
- SDX text ABI: `sdxLlmCreateRuntime/LoadModel/Generate(promptJson opts)/Free/GetLastError` (ABI version 1)

## Running the CLI (Linux, built lib)

### Prerequisites

| Artifact | Path | Notes |
|---|---|---|
| `sdx-llm` binary | `deeplearning4j/nd4j/sdx-aot/target/aot-sdk/cpu/bin/sdx-llm` | AOT-compiled, no JVM needed |
| companion native libs | `…/aot-sdk/cpu/lib/` (libjnind4jcpu.so, libmkl_*, etc.) | Side-loaded by `sdx-llm` binary |
| `libsdx_llm.so` | `kompile-local-sdk/lib/libsdx_llm.so` (or `aot-sdk/cpu/lib/`) | JNA in-process: requires aot-sdk/cpu companion libs (libjnind4jcpu, libmkl, etc.) |
| fp16 or q4_k_m model | `~/.kompile/models/chat/qwen2.5-0.5b-instruct-*.gguf` | Q5_0/Q5_1 dequant fixed 2026-07-12; q4_k_m works sidecar-free (R8 item 4 fixed 2026-07-12: CONTROL tokens now in `added_tokens`) |
| tokenizer | auto-resolved from GGUF metadata (FIX 2, 2026-07-12) | `--tokenizer` is optional; sidecar or GGUF-embedded both work |
| fixture graph | `kompile-local-sdk/examples/data/fixture.kgraph` | alice→WORKS_AT→acme |

### Quickstart — in-process JNA mode (FIX 1 applied 2026-07-12)

```bash
# Requires: aot-sdk/cpu unpacked alongside libsdx_llm.so
# SDX_LLM_AOT_HOME must point to the aot-sdk/cpu directory
# so the companion JNI libs (libjnind4jcpu.so, libmkl, etc.) are found.
SDX_LIB=~/Documents/GitHub/deeplearning4j/nd4j/sdx-aot/target/aot-sdk/cpu/lib/libsdx_llm.so
SDX_LLM_AOT_HOME=~/Documents/GitHub/deeplearning4j/nd4j/sdx-aot/target/aot-sdk/cpu
MODEL=~/.kompile/models/chat/qwen2.5-1.5b-instruct-fp16.gguf
KGRAPH=~/Documents/GitHub/kompile/kompile-local-sdk/examples/data/fixture.kgraph
JAR=kompile-chat-local/kompile-chat-local-cli/target/kompile-chat-local-cli-0.1.0-SNAPSHOT-exec.jar

SDX_LLM_AOT_HOME="$SDX_LLM_AOT_HOME" java -jar "$JAR" \
  --sdx-lib "$SDX_LIB" \
  --sdx-mode inprocess \
  --model "$MODEL" \
  --kgraph "$KGRAPH"
```

Startup output with FIX 1 applied:
```
[sdx] mode=in-process (JNA), lib: .../libsdx_llm.so
[inference] Active route: LOCAL_SDX
[inference] Local model: sdx:qwen2.5-1.5b-instruct-fp16.gguf (template=CHATML_IM, mode=in-process)
```

Note: `--tokenizer` is now optional — the 3-path resolver tries explicit → sidecar `tokenizer.json` → GGUF-embedded metadata (FIX 2). For `qwen2.5-1.5b-instruct-fp16.gguf` the GGUF-embedded path resolves vocab 151936 automatically.

### Quickstart (subprocess mode — original working path)

```bash
SDX_BIN=~/Documents/GitHub/deeplearning4j/nd4j/sdx-aot/target/aot-sdk/cpu/bin/sdx-llm
MODEL=~/.kompile/models/chat/qwen2.5-0.5b-instruct-fp16.gguf
TOKENIZER=~/.kompile/models/tokenizers/qwen2.5-0.5b
KGRAPH=~/Documents/GitHub/kompile/kompile-local-sdk/examples/data/fixture.kgraph
JAR=kompile-chat-local/kompile-chat-local-cli/target/kompile-chat-local-cli-0.1.0-SNAPSHOT-exec.jar

java -jar "$JAR" \
  --sdx-bin "$SDX_BIN" \
  --model "$MODEL" \
  --tokenizer "$TOKENIZER" \
  --kgraph "$KGRAPH"
```

Startup output:
```
[sdx] subprocess mode, bin: .../sdx-llm
[inference] Active route: LOCAL_SDX
[inference] Local model: sdx-subprocess:qwen2.5-0.5b-instruct-fp16.gguf (template=CHATML_IM, mode=subprocess)
```

CLI flags:

| Flag | Env var | Purpose |
|---|---|---|
| `--sdx-bin <path>` | `KOMPILE_CHAT_SDX_BIN` | Path to `sdx-llm` binary (subprocess mode; preferred) |
| `--model <path>` | `KOMPILE_CHAT_MODEL_PATH` | Model file (.gguf fp16 or .sdz) |
| `--tokenizer <path>` | `KOMPILE_CHAT_TOKENIZER_PATH` | tokenizer.json or directory |
| `--kgraph <path>` | `KOMPILE_CHAT_KGRAPH_PATH` | Standalone `.kgraph` session file (mutually exclusive with `--project`) |
| `--project <path>` | `KOMPILE_CHAT_PROJECT_PATH` | Project directory containing `kompile.project.json`, or a `.kproject` archive |
| `--fact-sheet-id <id>` | `KOMPILE_CHAT_FACT_SHEET_ID` | Select `data/graph/factsheet-<id>.kgraph` from the project |
| `--sdx-lib <path>` | `KOMPILE_CHAT_SDX_LIB` | Path to `libsdx_llm.so` for JNA in-process mode (requires SDX_LLM_AOT_HOME with companion libs) |
| `--sdx-mode <mode>` | `KOMPILE_CHAT_SDX_MODE` | `auto` (default), `inprocess` (JNA), `subprocess` (fork sdx-llm binary) |
| `--remote-url <url>` | `KOMPILE_CHAT_REMOTE_URL` | Remote OpenAI-compatible endpoint |
| `--max-tool-rounds <n>` | `KOMPILE_CHAT_MAX_TOOL_ROUNDS` | Max tool calls per turn (default 4) |
| `--temperature <f>` | `KOMPILE_CHAT_TEMPERATURE` | Sampling temperature (default 0.7) |

The properties-file equivalents are `project.path` and `fact.sheet.id`. With a project directory,
the CLI prefers the complete `data/graph/project.kgraph`, then `data/graph/global.kgraph`, then
exactly one recursive `.kgraph`; use `--fact-sheet-id` to request a specific fact-sheet graph.
Directory paths are traversed with no-follow secure directory handles, and the selected graph is
copied to a temporary snapshot before the chat engine opens it. Archives require an exact
manifest-to-ZIP inventory match. The checksummed `kompile.project.json` identity is verified against
the outer archive manifest, and only the selected graph is streamed to a temporary file after its
declared size and SHA-256 are verified.
Explicit missing, corrupt, or ambiguous graph requests fail closed. Starting with an empty graph
is allowed only when neither `--project` nor `--kgraph` (including config/env equivalents) is set.

```bash
java -jar "$JAR" --project /path/to/project --fact-sheet-id 17 --remote-url http://localhost:8091
java -jar "$JAR" --project /path/to/export.kproject --model "$MODEL" --sdx-bin "$SDX_BIN"
```

## What runs today vs. what it's waiting on

| Path | Status | Notes |
|---|---|---|
| CLI/JVM: graph tools fully local (load/verify/explain/assert/…) | **Works** | `[inference] Active route: LOCAL_SDX` on startup |
| CLI/JVM: subprocess SDX generate (linux) | **Works** | 5–8 tok/s, fp16 GGUF; load 4.3–8.9s |
| CLI/JVM + Android: remote chat via configured endpoint | **Works** | `--remote-url` + any OpenAI-compat URL |
| Android: native graph reasoning | **Built/package-verified** | Stock GraalVM AOT object + NDK r28b arm64/bionic link; JavaCPP `kgr_*` transport |
| 0.5B tool-call JSON (graph queries) | **Flaky** | 0.5B too small for structured JSON completion; use 1.5B+ model |
| q4_k_m quantized GGUF | **Working sidecar-free** (FIX 3 + FIX 4, 2026-07-12) | Q5_0/Q5_1 dequant fixed; q4_k_m identical to fp16 (6.7 tok/s). Embedded tokenizer now includes all 22 Qwen2.5 special tokens (R8 item 4 fixed): no sidecar needed |
| JNA in-process (libsdx_llm.so from JVM) | **Works** (FIX 1, 2026-07-12) | Export-allowlist applied: `graal_*/JNI_*/__svm_*` hidden (0 leaked of 23); `--sdx-mode inprocess` with `SDX_LLM_AOT_HOME` pointing to aot-sdk/cpu |
| iOS: local reasoning | Wired + guarded | Waiting on `kompile-reasoning-ios-arm64.xcframework` |

## Confirmed timings (linux x86_64, CPU, Qwen2.5-0.5B fp16)

| Metric | Value |
|---|---|
| Runtime creation | ~5ms |
| Model load time | ~4–9s (SameDiff graph compilation) |
| Generation speed | 5–8 tok/s |
| EOS (short responses, e.g. "Hello,") | ~640ms |
| Max-token cap | 256 (tool-call mode), configurable otherwise |

## Known caveats

### JNA in-process — `SDX_LLM_AOT_HOME` must point to aot-sdk/cpu

`libsdx_llm.so` side-loads ND4J JNI backends (`libjnind4jcpu.so`, `libmkl_*.so`,
`libjnitokenizers.so`) via `SDX_NATIVE_LIB_DIR` or `SDX_LLM_AOT_HOME/lib/`.
The `kompile-local-sdk/lib/` directory only ships `libsdx_llm.so` itself (not the JNI
backends). Set `SDX_LLM_AOT_HOME` to the full `aot-sdk/cpu` path where all 54 companion
libs are present.

The GraalVM isolate conflict (`ExceptionInInitializerError`) that previously prevented
in-process loading is **fixed** by the export-allowlist version script (`SDX_LLM_1`):
`graal_*/JNI_*/__svm_*` symbols are now hidden (`0` leaked of the previous 23).
Verified: both `libkompile_reasoning.so` (KGR_1) and `libsdx_llm.so` (SDX_LLM_1)
coexist in one Python process and one JVM process without conflict.

### Companion libs must be in `../lib` relative to `sdx-llm`

The `sdx-llm` binary side-loads `libjnind4jcpu.so`, `libmkl_*.so`, `libjnitokenizers.so`
etc. from `../lib`. Keep the binary and its sibling `lib/` together as shipped in
`aot-sdk/cpu/`. Do NOT copy just the binary — it will fail to load ND4J.

### q4_k_m quantized weights — fully working sidecar-free (FIX 3 + FIX 4, 2026-07-12)

Q5_0/Q5_1 dequantization is fixed (FIX 3). `q4_k_m` GGUF models generate coherent output
identical to fp16 at the same speed (6.7 tok/s on CPU).

**No sidecar `tokenizer.json` is required** (FIX 4, R8 item 4, 2026-07-12). The GGUF-embedded
tokenizer path now reads `tokenizer.ggml.token_type` and adds every non-NORMAL token (type != 1)
to `added_tokens` with `"special": true`. All 22 Qwen2.5 special tokens (IDs 151643–151664)
including the ChatML delimiters `<|im_start|>` (151644) and `<|im_end|>` (151645) are now
correctly promoted. Verified: both markers tokenize to exactly 1 token id; generation is coherent
without any sidecar file present.

### 0.5B model and tool-call reliability

Qwen2.5-0.5B fp16 generates real tokens and follows simple instructions (single-word
answers, short text) but cannot reliably complete tool-call JSON structures (`{"tool":...}`)
required for graph queries. Recommend 1.5B+ (e.g. Qwen2.5-1.5B-Instruct) for production
tool-calling. The 0.5B can be used for demo generation proof and no-tools chat.

### tokenizer resolution (FIX 2, 2026-07-12)

`--tokenizer` is now **optional** for GGUF models. The 3-path resolver tries:
1. Explicit `--tokenizer` path (if provided)
2. Sidecar `tokenizer.json` in the model directory
3. **GGUF-embedded**: reads `tokenizer.ggml.tokens` + `tokenizer.ggml.merges` from
   GGUF metadata → builds HuggingFace tokenizer.json in memory

`qwen2.5-1.5b-instruct-fp16.gguf` (no sidecar) resolves vocab 151936 automatically.
For `qwen2.5-0.5b-instruct-fp16.gguf` a sidecar at
`~/.kompile/models/tokenizers/qwen2.5-0.5b/tokenizer.json` still works if preferred.
