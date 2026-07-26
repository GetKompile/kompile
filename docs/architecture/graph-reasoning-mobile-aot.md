# Graph Reasoning on Mobile: a standard C library alongside the SDX Runtime

Date: 2026-07-11 (v2 — REVISED same day: C-library-only shape, **no Gluon**, per decision)
Status: IN PROGRESS — **P0 SHIPPED 2026-07-11 + same-day fix wave** (desktop linux-x86_64: `kompile-graph-reasoning-local` module, 13 tools, lib 1927/1927 + module 105/105 tests, `libkompile_reasoning.so` 90MB serial-GC via stock native-image --shared, C smoke 35/35 incl. assert/retract persistence round-trips + 5000-dispatch GC soak)
Owner: kompile graphs
Companion handoff (dl4j side): `~/Documents/GitHub/deeplearning4j/SDX_MOBILE_LLM_C_API_HANDOFF.md`

## 0. Framing (decision record)

- The deeplearning4j repo is the **official upcoming dl4j release** and ships alongside
  kompile — dl4j-side gaps are fixed in dl4j, not worked around in kompile.
- **SDX is a full C library only**: `libsdx_*` exporting the `sdx*` ABI
  (`libnd4j/include/dsp/runtime/dsp_runtime_c.h`), JVM-free, compiled per platform.
- **Graph reasoning ships the same way: JUST a standard C library** —
  `libkompile_reasoning` + `kompile_reasoning.h`, built with **stock GraalVM
  `native-image --shared` + `@CEntryPoint`** (same shape as the existing
  `kompile-c-library`/`kompile.h` desktop bridge). **Gluon is rejected** — no
  third-party AOT toolchain; mobile cross-targets are in-house build work.
- v1 of this doc recommended Gluon (iOS) + AAR-on-ART (Android). Superseded: one
  artifact shape on every platform; Android consumes the `.so` via JNA exactly like it
  consumes `libsdx_runtime`, iOS links the static lib/xcframework like SDX's.

## 1. Goal

A phone runs **fully local graph reasoning + model chat**: the SDX model generates and
tool-calls a local reasoning session over a `.kgraph` file — no server, no network.

```
┌────────────────────────── mobile app (scaffolded) ──────────────────────────┐
│  Chat UI (SwiftUI / Compose)                                                │
│    │ prompt + tool schemas               ▲ answer                           │
│    ▼                                     │                                  │
│  Tool-calling chat loop  ── tool_call JSON ──►  GraphReasoningService       │
│    │                                             │  kgr_dispatch(name,json) │
│    ▼                                             ▼                          │
│  libsdx (C, sdx* ABI)                          libkompile_reasoning (C)     │
│  model.sdz ── generate/embed                   project.kgraph ── reason     │
└─────────────────────────────────────────────────────────────────────────────┘
   two plain C libraries, same packaging conventions, same release discipline
```

## 2. What the audits established (2026-07-11)

### 2.1 The library is already nearly AOT-ready

`kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning`: 351 files, ~72k LOC,
48 packages, 1.5 MB jar, Java 17 (records, sealed interfaces, pattern matching — no
preview features). Audit:

| Concern | Finding |
|---|---|
| Reflection / ServiceLoader / proxies / Unsafe | **Zero sites** — no reflect-config needed for the lib itself |
| Threads / executors / parallel streams | **Zero** — entirely single-threaded |
| JNI, AWT, JMX, resources, env/props reads | **Zero** |
| File I/O | Only caller-supplied `Path` (`UnifiedGraph.save/load`) |
| JSON | Hand-rolled (`MiniJson`, `toJson()` builders) — no jackson-databind |
| Other kompile modules | **None** — fully self-contained |
| Java serialization | `JavaSerde` (ObjectStreams) for `.kgraph` `models/*`; ~20 `Serializable` types (PslProgram, PslRule, PslAtom, Term, ArithmeticRule(+2 nested), MTheory, MFrag, RandomVariable, EntityType, TypeRegistry(+Declaration), TypeAttributeSchema, AttributeDefinition, TypeConstraint impls, Opinion, MassFunction, ReasoningTrace(+Step)); lambdas/CPTs correctly `transient`; 2 custom `readObject` (PslProgram, MFrag) |
| ND4J | Confined to **7 classes**: `psl.TensorHlMrfInference`, `embedding.learn.{RotatELearner, SameDiffEmbeddingTrainer, SameDiffModelIO, RotatEPersistenceBridge}`, `learning.{SameDiffPslWeightGradient, SameDiffMebnStrengthLearner}` |

Excluding the 7 ND4J classes loses only GPU tensor PSL solve for >4000 ground rules
(pure-Java `Scalar`/`Admm`/`Sgd` solvers cover the whole range — `SgdHlMrfInference` is
already the designed no-ND4J fallback in `HlMrfMapInference.chooseSolver()`) and KGE
**training**/gradient weight learning. KGE **inference** is pure `double[]` over
`EmbeddingTable` vectors bundled in the `.kgraph`. Training stays server-side; nothing
in the on-device story is lost.

### 2.2 The tool surface splits cleanly into local vs server

All 22 graph MCP tools today are HTTP shims (`CliTool` impls); the engines live in the
lib. Device surface:

- **Tier 1 (pure lib):** `graph_reasoning_query` → `GraphQueryEngine` — 17 operations
  (SEARCH, DESCRIBE, NEIGHBORS, PATH, FACTS, VERIFY, WHY, WHY_NOT, RANK, OVERVIEW,
  SCHEMA, TIMELINE, SIMILAR, RELATIONS, CAPABILITIES, ASSETS, ARTIFACT) ≈ 80% of
  chat-with-graph; plus new `graph_load`/`graph_save` wrapping `UnifiedGraph.load/save`.
- **Tier 2 (lib + small state adapter):** `ask_graph_verify` (`DefaultKbVerifier`),
  `ask_graph_query` (`ConjunctiveQueryEngine`), `ask_graph_explain`/`graph_reason`
  (`DerivationTree`), `ask_graph_assert`/`ask_graph_retract` (`ConcurrentFactStore` +
  `ContradictionDetector`/TMS), `ask_graph_claim` (`DossierBuilder`),
  `ask_graph_synthesize` (`AnswerSynthesizer` over `graph.entities()`),
  `ask_graph_mebn`/`graph_bayes` (MEBN + variable elimination over the loaded graph),
  `graph_embeddings` inference actions, `graph_centrality` (pure-Java pagerank/degree
  in the local module, or `kompile-graph-algorithms` if its dep audit is clean).
  The one adapter: an on-device `GraphToFactStoreProjector` equivalent — after
  `UnifiedGraph.load()`, project `graph.facts()` into `FactStore`/`InferredFactStore` +
  `JustificationIndex` ("KbState") so verify/query/explain don't return UNKNOWN.
- **Server-only (excluded from v1):** `ask_graph_explain_fused` (LLM registry —
  the local SDX model could take the narration role later), `ask_graph_subscribe`
  (server SSE state), `graph_simulate` (JPA sandboxes), `graph_search` (Neo4j),
  `graph_forecast` (JPA+LLM), `knowledge_graph` CRUD (JPA). `graph_import/export` are
  moot on device — `load`/`save` replace them.

The MCP JSON contract (tool `id()` + programmatic `parameterSchema()`) becomes the FFI
contract: same tool names, same JSON shapes, dispatched in-process.

### 2.3 SDX packaging is the template to copy

The scaffold selects the iOS `apple-xcframework` or Android `android-aar` entry from
the canonical `sdk-v{version}/sdx-sdk-manifest.json`, copies its exact `fileName`, and
verifies SHA-256. `KOMPILE_SDX_SDK_BASE_URL` remains the registry override; cache writes
use `~/.kompile/models/sdx-sdk/<sdkId>/...`. The scaffolded chat loop has **no tool-calling** yet;
insertion point is the token-accumulation loop on both platforms.

**Historical reality check (2026-07-11):** the former release scheme had no published assets;
the template symbols (`dsp_model_*` iOS, `sdx_init/...` Android JNA) do **not** exist
in the real `dsp_runtime_c.h` — the real ABI is tensor-level
(`sdxCreateRuntime/sdxLoadBundle/sdxCreateContext/sdxRun`). The text-level LLM C API,
mobile builds, and release publishing are dl4j deliverables — see the handoff doc.
kompile regenerates the templates against the real header once it freezes.

### 2.4 Toolchain reality (web-verified 2026-07-11) and what "no Gluon" implies

- Stock GraalVM native-image targets linux x64/arm64, macOS, windows — `--shared` +
  `@CEntryPoint` is stable and production-grade there (kompile-c-library precedent).
  It has **no ios/android targets**; the LLVM backend is no longer shipped prebuilt;
  the WASM backend is browser-oriented/experimental.
- Gluon Substrate packages a patched GraalVM for ios/android — **rejected here** as a
  toolchain dependency. The deltas it maintains are contained and readable (Apache 2):
  iOS = darwin-arm64 AOT objects linked against the iOS SDK with no-JIT flags
  (Gluon itself now uses GraalVM's own LIR aarch64 backend, not LLVM);
  Android = linux-aarch64 objects linked against bionic via the NDK. We implement
  those two link targets **in-house**, sequenced after the desktop C library proves
  the API — and pair them with dl4j's libsdx android/ios lanes so one CI matrix ships
  both libraries.
- ND4J/JavaCPP publish no mobile classifiers today → the 7-class exclusion stands
  regardless (native-image side: `@Substitute isAvailable() → false` constant-folds the
  tensor branch out of the closed world; nd4j-api stays off the image classpath).
- Rejected alternatives: J2ObjC (ObjC-only output, records pre-release), MobiVM
  (Java 8–11), TeaVM (serialization unsupported), WASM-in-app (App Store 2.5.2 risk +
  no native call path).

## 3. Architecture

### 3.1 New module: `kompile-graph-reasoning-local`

Location: `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local`,
depending ONLY on `kompile-graph-reasoning` (as infra-free as the lib itself).

```
ai.kompile.graph.reasoning.local
├── LocalReasoningSession      // load/save .kgraph; owns UnifiedGraph + KbState
│     open(Path)/save(Path)/close(); KbState = FactStore + InferredFactStore +
│     JustificationIndex primed from graph.facts()
├── LocalToolDispatcher        // dispatch(String tool, String argsJson) -> String
│     routes to the Tier 1/2 surface above; JSON via MiniJson
├── LocalToolCatalog           // names + descriptions + parameterSchema JSON,
│     1:1 with CliTool definitions (parity test against kompile-cli)
└── nativeapi/GraphReasoningCApi  // @CEntryPoint shim (native profile only)
```

### 3.2 C ABI — `include/kompile_reasoning.h` (canonical, checked-in)

**Canonical public header:**
`kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local/include/kompile_reasoning.h`

This file is versioned and checked in.  It is **self-contained — no GraalVM headers
required** (`graal_isolate.h` is NOT needed).  The isolate lifecycle functions are
exported as `kgr_*` builtins so consumers operate entirely within the `kgr_*` namespace.

**Isolate lifecycle builtins** (added 2026-07-11; `@CEntryPoint(builtin=…)` in
`GraphReasoningCApi.java`; GraalVM substitutes the real implementation at build time):

```c
// create_isolate: C signature dictated by GraalVM CREATE_ISOLATE builtin
int kgr_create_isolate(kgr_create_isolate_params_t *params,   // NULL for defaults
                        kgr_isolate_t             **isolate,
                        kgr_thread_t              **thread);
int kgr_attach_thread  (kgr_isolate_t *isolate, kgr_thread_t **thread);
int kgr_detach_thread  (kgr_thread_t *thread);
int kgr_tear_down_isolate(kgr_thread_t *thread);
```

Thread rule: one `kgr_thread_t*` per OS thread; handles are NOT shareable.
Single-threaded flow: `kgr_create_isolate` → all `kgr_*` calls → `kgr_tear_down_isolate`.
Additional threads: `kgr_attach_thread` on entry, `kgr_detach_thread` on exit.

**Session and reasoning functions** (all take a `kgr_thread_t*` as first arg):

```c
typedef long long kgr_session_t;

int           kgr_abi_version(kgr_thread_t *thread);           // returns KGR_ABI_VERSION
kgr_session_t kgr_open      (kgr_thread_t *, const char *kgraph_path);
const char*   kgr_tools     (kgr_thread_t *);                  // catalog JSON; free w/ kgr_free
const char*   kgr_dispatch  (kgr_thread_t *, kgr_session_t,
                              const char *tool_name, const char *args_json);
int           kgr_save      (kgr_thread_t *, kgr_session_t, const char *path);
void          kgr_free      (kgr_thread_t *, const char *result);
void          kgr_close     (kgr_thread_t *, kgr_session_t);
```

**Header parity gate** (`run-smoke.sh`): every `kgr_*` symbol in the native-image-generated
header (`target/libkompile_reasoning.h`) must be declared in the public header — the smoke
fails with a clear message on drift.  The four builtin lifecycle symbols are exempted from
the reverse check (GraalVM emits them in `graal_isolate.h`, not the user-symbol header).

**SDK zip artifact** (produced by `native-library` Maven profile):
```
target/kompile-graph-reasoning-local-<version>-native-sdk.zip
  include/kompile_reasoning.h
  lib/libkompile_reasoning.so
  bindings/python/kompile_reasoning.py
  README.md
```
Assembled by `src/main/assembly/native-sdk.xml`; attached with classifier `native-sdk`.
Mirrors the SDX `sdx-runtime-sdk` directory shape so future iOS/Android classifiers slot in.

**Language bindings** are in `bindings/`:
- `bindings/python/kompile_reasoning.py` — ctypes wrapper (create isolate, open/tools/dispatch/save/close, UTF-8 strings, automatic `kgr_free`)
- JNA (Kotlin/Android) and Swift snippets: see `README.md` and the header doc-comment block.

Follow SDX ABI discipline: exported-symbol namespace `kgr*`, ABI version function, JSON
error contract (`{"status":"ERROR","message":"..."}`; never exceptions across the boundary).
Swift/Kotlin `GraphReasoningService` wraps this exactly the way `SdxInferenceService` wraps
libsdx (Android: JNA `Native.load("kompile_reasoning", ...)`; iOS: xcframework module +
bridging header with this header).

### 3.3 Serialization config

`serialization-config.json` for the ~20 enumerated types, generated/validated by
running the native-image **tracing agent over the existing lib test suite** (1857
tests exercise every save/load path), committed under
`kompile-graph-reasoning-local/src/main/resources/META-INF/native-image/`.
Fallback if mobile Substrate builds choke on ObjectStreams: explicit
`DataOutput`-based codecs for the model families behind `JavaSerde` — bounded work;
the transient-lambda discipline already did the hard part.

## 4. Platform & packaging matrix

| Platform | Toolchain | Artifact |
|---|---|---|
| linux-x86_64 / linux-aarch64 | stock native-image `--shared` | `libkompile_reasoning.so` (+ header) — also serves desktop `sdk serve` and CI smoke |
| macos-arm64 / macos-x86_64 | stock native-image `--shared` | `libkompile_reasoning.dylib` |
| windows-x86_64 | stock native-image `--shared` | `kompile_reasoning.dll` |
| android-arm64 (+x86_64 emu) | in-house cross-target: native-image objects → NDK/bionic link | `kompile-reasoning-android-<abi>.aar` wrapping `jni/<abi>/libkompile_reasoning.so` |
| ios-arm64 (+simulator) | in-house cross-target: darwin-arm64 AOT → iOS SDK link, AOT-only flags | `kompile-reasoning-ios-arm64.xcframework.zip` |

Release/publishing follows the SDX scheme exactly (same tag family, same
`{sdkId}-{classifier}.{ext}` asset naming, `SdkConstants` descriptor +
`kompile sdk download` + scaffold copy steps + `--include-graph <path.kgraph>` flag;
graph assets land in `Resources/Graphs/` / `assets/graphs/`). Ships **enabled** when
the artifact is present.

## 5. Chat integration (the fully local loop)

> **Implemented 2026-07-12** in `kompile-chat-local/` (standalone parent, outside the
> root reactor): JVM core + CLI (tests green), Android app (core runs on ART), iOS app
> (Swift port of the loop) — all local paths guarded pending `libsdx_llm` +
> ios/android reasoning artifacts; remote kompile-endpoint fallback works today.
> The text-level `sdxLlm*` C ABI (handoff R1) already exists in
> `deeplearning4j-examples/sdx-runtime-examples`. See `kompile-chat-local/README.md`.

1. System prompt embeds the `kgr_tools()` catalog (compactHint discipline).
2. Model emits `{"tool": ..., "args": {...}}`; the loop intercepts at the existing
   token-accumulation point, calls `kgr_dispatch`, appends the result as a tool
   message, re-generates; bounded tool rounds per turn.
3. Reliability of step 2 for 0.6B-class models depends on **constrained decoding in
   libsdx** (grammar/JSON-schema + stop sequences) — dl4j handoff R2. Until then,
   strict-parse + retry.

## 6. Phased plan

- **P0 — desktop C library — ✅ DONE 2026-07-11 (linux-x86_64):** module +
  dispatcher + 13 tools (core/grounding/inference/analytics) + 91 tests;
  27-entry serialization-config; `Nd4jSubstitutions` constant-folds
  `TensorHlMrfInference.isAvailable()` → zero ND4J classes in image text (nd4j-api
  remains analysis-classpath-only); `native-library` pom profile (repo-standard C-library id, also auto-activates on
  `-Dkompile.dist` like libkompile_pipelines) + `build-native.sh` +
  `run-smoke.sh`; C smoke 20/20 incl. write-through assert → `kgr_save` → reopen →
  re-verify. **Follow-up fixes SHIPPED (same session):**
  - **`removeRelation` lib API**: `MutableReasoningGraph.removeRelation(src,type,tgt)` +
    `removeRelationById(id)`; exposed on `UnifiedGraph`; `facts()` excludes removed
    relations; save/load round-trips removal; 11 new lib tests.
  - **Retract topology write-through**: `ask_graph_retract` now calls
    `graph.removeRelation()` + `reprimeKb()` for binary atoms; response includes
    `topologyRemoved:true|false`; retraction survives save/reload; 4 new module tests.
  - **KGE scorer wired into `ask_graph_claim`**: `InferenceHandlers.buildEmbeddingScorer`
    selects first non-empty ENTITY layer (same logic as AnalyticsHandlers), builds a
    cosine-based `KgeTripleScorer`; DossierBuilder receives it so `ask_graph_claim`
    emits a KGE supporting item when vectors are present; null (no channel) when absent;
    3 new module tests.
  - **Bundled-rules materialization**: `DatalogRulesBundle` (JSON round-trip for
    `List<DatalogRule>`); canonical artifact key `"datalog_rules"` in `.kgraph`;
    `LocalKbState.primeFromGraph` / `reprimeFromGraph` run bounded synchronous
    `RecursiveQueryEngine.evaluate` + `toInferredFacts("local:datalog")`; failures →
    WARN + observed-only fallback; `ask_graph_verify` returns SUPPORTED with
    `derivationDepth>0` for derived atoms; `ask_graph_explain` shows the rule
    derivation; new module tests cover grandparent-style rule bundle round-trip.
  - **Native rebuild + extended smoke**: classpath pruned (kotlin-stdlib/oshi/JNA
    excluded; `-H:+RemoveSaturatedTypeFlows`); `--gc=epsilon` was tried for size and
    **REVERTED — forbidden here**: epsilon never collects, and this library hosts
    long-lived sessions with unbounded dispatches (guaranteed OOM). Final image
    **90MB with serial GC**; smoke 35 checks incl. retract→save→reload→UNKNOWN
    topology round-trip in pure C and a 5000-dispatch soak (RSS plateaus at +49MB —
    collection proven, no `kgr_free` leak).
  Follow-ups: macos-arm64 lane, catalog-parity test vs `CliTool`, further size work
  (real wins need dead reasoning-package pruning, not GC games).
- **P1 — Android cross-target:** native-image → bionic/NDK link lane; AAR assembly;
  `GraphReasoningService.kt` + tool-calling `ChatScreen` templates; emulator/device
  smoke. (Coordinate with dl4j's libsdx android lane — shared CI matrix.)
- **P2 — iOS cross-target:** darwin-arm64 AOT-only build + iOS SDK link; xcframework
  assembly; Swift service + `ChatView` tool loop templates; on-device `.kgraph`
  JavaSerde verification (fallback codecs if needed); App Store validation build.
- **P3 — product glue:** `--include-graph` end-to-end, `SdkConstants`/download
  registry entries, prompt-template tuning for the catalog SDZ models, memory
  ceilings (isolate heap flags) + graph-size guidance, docs.

Blocking dependencies on dl4j (tracked in the handoff): text-level LLM C API (R1),
constrained decoding (R2), libsdx mobile builds (R3), published releases (R5).

## 7. Risks & mitigations

| Risk | Level | Mitigation |
|---|---|---|
| In-house native-image ios/android link targets are real toolchain work | **Med-High** | Contained, precedented deltas (bionic link; iOS SDK link, AOT-only); desktop-first phasing proves the API before any cross-link; pair with libsdx mobile lanes |
| Substrate JRE vs `ObjectInputStream` on mobile targets | Medium | P0 desktop proves serialization-config; P1/P2 re-verify on device; bounded fallback codecs |
| Static lib size (Substrate + 72k LOC) | Low-Med | Expect tens of MB — small next to an LLM; symbol allowlist, closed-world dead-code |
| Tensor/SameDiff reachability leaking nd4j into the image | Low | `@Substitute` constant-fold + nd4j-api off image classpath; image build fails loudly |
| Tool-calling quality on 0.6B models without constrained decode | Med (quality) | dl4j R2 (grammar/JSON-schema); interim strict-parse + bounded retries |
| Per-fact-sheet semantics on device (single-graph world) | Low | `factSheetId` accepted/ignored (one session = one graph); documented in catalog |
| iOS lane needs a macOS host | Low | macOS CI job; all other lanes unaffected |

## 8. Open questions

1. Tag family: separate `kgr-v{v}` for `libkompile_reasoning`, `kompile-local-sdk-v{v}` for the composition, and canonical `sdk-v{v}` plus `sdx-sdk-manifest.json` for DL4J SDK artifacts. Base URL overrides remain independent.
2. `kompile-graph-algorithms` purity audit (centrality) vs ~100-line pure-Java
   pagerank/degree in `-local`.
3. Final symbol namespace freeze with dl4j (kompile regenerates scaffold templates
   against the real headers for BOTH libraries at the same time).
4. On-device `explain_fused`-lite using the SDX model for narration — P3+ candidate.
