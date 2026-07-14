# KompileChatLocal — iOS

SwiftUI chat app that binds the kompile graph-reasoning C library and the SDX LLM
text-generation library for fully on-device, graph-aware conversations.
Remote fallback via any OpenAI-compatible endpoint (Ollama, kompile-local, OpenAI, …).

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| macOS | 14 Sonoma+ | Xcode host OS |
| Xcode | 16+ | Swift 5.10 required |
| xcodegen | 2.x | `brew install xcodegen` |
| iOS deployment target | 16.0+ | |

---

## First-time project generation

```bash
cd kompile-chat-local/mobile/ios
xcodegen generate
open KompileChatLocal.xcodeproj
```

That is the only command needed on macOS.  The `.xcodeproj` is **not** checked in
(it is git-ignored); regenerate it from `project.yml` whenever the file list changes.

---

## Xcframework artifacts (two future artifacts)

Both libraries are **guarded** with `#if canImport(...)`.  The project builds and
runs in remote-only mode with neither artifact present.

### 1. `kompile-reasoning-ios-arm64.xcframework`   (P2 — future build)

- C library: `libkompile_reasoning.a` (arm64-apple-ios16)
- Built from the GraalVM native-image pipeline:
  `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local/`
- Public header:
  `include/kompile_reasoning.h`  (already checked in — canonical ABI)
- Module name used in Swift guards: `KompileReasoning`

### 2. `sdx-llm-ios.xcframework`   (future dl4j mobile release)

- C library: `libsdx_llm.a` (arm64-apple-ios16)
- JNA binding reference: `kompile-chat-local-core/…/sdx/SdxLlmAbi.java`
- Module name used in Swift guards: `SdxLlm`
- Text API bound: `sdxLlmCreateRuntime / LoadModel / Generate / Free / GetLastError`
  (old `dsp_runtime_c` tensor API is intentionally NOT imported)

### Dropping in an artifact

1. Copy the `.xcframework` bundle into `mobile/ios/Frameworks/`.
2. Uncomment the matching `dependencies:` block in `project.yml`.
3. Re-run `xcodegen generate`.
4. In Xcode → Target → General → Frameworks: verify it is **Embed & Sign**.

---

## Current-state capability table

| Feature | Status | Notes |
|---------|--------|-------|
| Remote chat (OpenAI-compat) | **Works** once built on macOS | Configure base URL + optional API key in Settings |
| SSE streaming (remote) | **Works** | AsyncStream token delivery |
| Local SDX inference | Wired, guarded | `#if canImport(SdxLlm)` — awaiting arm64 xcframework |
| Graph reasoning (kgr_*) | Wired, guarded | `#if canImport(KompileReasoning)` — awaiting arm64 xcframework |
| .kgraph file import | **Works** | UIDocumentPicker → app container copy |
| Tool-calling loop | **Works** | 4 rounds default; mirrors ChatEngine.java exactly |
| OVERVIEW banner | Works when graph loaded | Dispatches `graph_reasoning_query {operation:OVERVIEW}` on open |

---

## Architecture

```
KompileChatLocalApp
├── ContentView (TabView: Chat | Settings)
├── ChatView
│   ├── message bubble list (system + tool_result hidden)
│   ├── ToolRoundCard (collapsible, per tool dispatch)
│   ├── route badge (LOCAL / REMOTE / NO BACKEND)
│   └── graph-loaded banner (OVERVIEW summary)
├── SettingsView
│   ├── UIDocumentPicker (.kgraph, .gguf/.safetensors)
│   └── generation params (temperature, maxTokens, topP, topK, maxToolRounds)
└── Services
    ├── ChatEngine          ← port of ChatEngine.java (tool loop + retry)
    ├── ToolCallParser      ← port of ToolCallParser.java (2-strategy fenced/bare)
    ├── InferenceRouter     ← local-first routing; publishes activeRoute
    ├── SdxLlmService       ← sdxLlm* binding; #if canImport(SdxLlm)
    ├── GraphReasoningService ← kgr_* binding; #if canImport(KompileReasoning)
    ├── RemoteChatService   ← URLSession POST /v1/chat/completions + SSE
    └── GenOptions          ← port of GenOptions.java; toOptionsJson() exact match
```

---

## ChatEngine port — divergences from the Java core

| Aspect | Java | Swift |
|--------|------|-------|
| `generate(messages, opts)` | synchronous (blocks caller thread) | `async throws` — awaited on Task |
| Local prompt template | N/A (remote-only in Java) | `<\|system\|>…<\|user\|>…<\|assistant\|>` (Phi-3 style); swappable in `InferenceRouter.buildLocalPrompt` |
| `MiniJson.write(args)` | custom mini-JSON serialiser | `JSONSerialization.data(withJSONObject:)` — same output for typical tool arg maps |
| `bridge.catalogJson()` | `GraphToolBridge.catalogJson()` | `GraphReasoningService.catalogJson()` via semaphore-guarded kgrQueue dispatch |
| Corrective retry | two extra `router.generate` calls | identical logic, `async/await` |
| Max rounds forced synthesis | appends user message, generates | identical |

All behavioural rules (system prompt wording, TOOL_RESULT prefix, fenced + bare JSON
strategies, retry message text, synthesis message text) are verbatim ports.

---

## Fallback configuration walkthrough

The app degrades gracefully across three tiers:

**Tier 1 — remote only (no xcframeworks)**
1. Build and run on a simulator or device from Xcode.
2. In Settings → Remote Endpoint, set Base URL to your kompile-local or Ollama URL.
3. Chat works immediately.  Graph tools return `{"status":"ERROR","message":"KompileReasoning library not available"}` and are shown in collapsed ToolRoundCard rows.

**Tier 2 — graph reasoning + remote LLM**
1. Drop `kompile-reasoning-ios-arm64.xcframework` into `Frameworks/`.
2. Uncomment its block in `project.yml`, re-run xcodegen, Embed & Sign.
3. In Settings → Knowledge Graph, import a `.kgraph` file.
4. The OVERVIEW banner appears; graph tools dispatch locally.

**Tier 3 — fully local**
1. Drop both xcframeworks into `Frameworks/`, configure both in project.yml.
2. Import a `.gguf` or `.safetensors` model in Settings → Local Model.
3. InferenceRouter switches to LOCAL route; the route badge turns green.

---

## Notes for the Android agent and future sessions

- The `ToolCallParser` strategies (fenced ```json then bare brace) are identical
  to both the Java core and the Android Kotlin port — keep them in sync.
- The system prompt string in `ChatEngine.buildSystemPrompt()` is the canonical
  source; the Java `ChatEngine.buildSystemPrompt()` is the reference.  If the
  Java wording changes, update this file and the Android equivalent.
- `GenOptions.toOptionsJson()` schema `{"maxNewTokens":N,"sampling":{...}}` must
  match `SdxLlmAbi` expectations — verified against `GenOptions.java`.
- `kgr_free()` is called for EVERY `const char*` returned by `kgr_tools()` and
  `kgr_dispatch()`.  Any future caller of `GraphReasoningService` that calls
  `kgr_dispatch` directly in C must also call `kgr_free`.
- The semaphore in `GraphReasoningService.catalogJson()` is safe only because the
  call happens on a background thread (ChatEngine runs in a Task).  Do not call
  `catalogJson()` from the main thread with a long-running isolate.
