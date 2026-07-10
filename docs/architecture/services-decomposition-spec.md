# `kompile-app-main` `services` package — decomposition assessment & spec

**Status:** assessment (no code changes). 2026-07-02.
**Context:** follow-on to the app-main modularization (6 rounds done: subprocess ×6, rag, source, facts,
dto, ingest, config — ~59K LOC / ~299 files into 12 modules; app-main 232,111 → 173,229 LOC). This spec
assesses the remaining core: the `ai.kompile.app.services` package.

---

## 1. Verdict

`services` is **84,251 LOC / 209 files** — the business-logic heart of app-main. Unlike the packages
carved out so far, it is **not** a cohesive feature with a thin edge; it is a partially-ordered graph of
domains around a large, mutually-entangled shared core. It **cannot** be lifted out as one module and
should not be attempted as one.

However, it is **not** a monolith either. There is exactly one **clean keystone** and a set of
**cohesive domains** that become extractable once the keystone is in place. This is a multi-phase
re-architecture (weeks, not a session), but it has a well-defined first move.

**Recommendation:** extract the **platform/resource kernel** first (clean, high-leverage, ~13K LOC).
Then peel domains outward (`mcp` → `agent` → `ingest-services` → `crawl`), each behind the proven
SPI-inversion + kept-package pattern, with **runtime/e2e validation per phase** (this layer is Spring-
wiring-, transaction-, and resource-management-sensitive; build-green is necessary but not sufficient).

---

## 2. Current structure

| Unit | Files | LOC | Character |
|---|---:|---:|---|
| **root** (no sub-pkg) | 81 | 33,914 | the shared hub + god-services (ingest, vector-store, model-lifecycle, cross-doc) |
| `subprocess` (launchers) | 20 | 14,075 | subprocess launchers — pair with the extracted subprocess Mains |
| `agent` | 22 | 10,023 | agent/CLI-chat subsystem |
| `mcp` | 23 | 8,178 | MCP client/server integration |
| `scheduler` | 12 | 3,394 | resource-aware job scheduling |
| `crawl` | 10 | 2,839 | crawl orchestration |
| `pipeline` | 3 | 1,779 | ingest pipeline glue |
| `graph` | 2 | 1,611 | matrix-graph subprocess clients |
| `enforcer` | 3 | 1,242 | LLM judge/enforcer |
| `cluster` | 8 | 1,033 | clustering (resource) |
| 11 smaller | ~20 | ~6,200 | audit, git, prompt, sdx, skill, diff{index,policy,tracker}, agenttask, scheduling, preprocessing |

Biggest god-classes: `subprocess/SubprocessIngestLauncher` (2833), `DocumentIngestService` (2367),
`subprocess/SubprocessConfigService` (1543), `CrossDocumentRelationExtractor` (1458),
`VectorStorePopulationService` (1300), `agent/CliAgentLLMChat` (1254), `agent/AgentChatService` (1200),
`ModelLifecycleManager` (1050).

## 3. Dependency structure (why it's hard)

**Internal graph is a hub, not a layering.** The root blob both *provides to* and *consumes from* its
sub-packages — bidirectional coupling:
- sub-pkg → root: `subprocess`→root **35**, `scheduler`/`cluster`/`agent`→root 4 each, `pipeline`→root 3.
- root → sub-pkg: root→`scheduler` **8**, root→`subprocess` 5, root→`pipeline` 3, root→{agent,mcp,cluster,audit,preprocessing} 1 each.

**Highest fan-in "hub" classes** (imported across app-main): `ServerPortService` (12),
`Nd4jEnvironmentConfigService` (9), `DeviceRoutingConfigService` (9), `IngestProgressTracker` (8),
`ModelLifecycleManager` (7), `DocumentIngestService` (6), `GpuResourceManager`/`AppIndexConfigService`/
`VectorPopulationProgressTracker` (5). Note these cluster into **two** groups: a *platform/config/resource*
group and an *ingest/indexing* group.

**External blockers are now thin** (post-extraction): `services` → app-main-non-services is
`config`-blocked **16** files, `tools` 1, `staging` 2, `scaffold` 1. `services`→`web` is **20 files but
all `web.dto`** (already the `kompile-app-dto` module). So services is nearly free of the rest of app-main
— the hard part is entirely *internal*.

## 4. The keystone — `kompile-app-platform` (extract first)

A **platform/resource kernel** of 45 files / **13,227 LOC** is a genuinely clean lower layer:
- Members: model lifecycle/admission/warmup/weight-cache/scheduler-config, GPU (`GpuResourceManager`,
  `GpuLifecycleEvent*`, `GpuToCpuMigrationService`), memory (`MemoryPool*`, `MemoryWatchdogService`,
  `HeavyMemoryCoordinatorImpl`), resource (`ResourceGovernor`, `ResourceTelemetryService`,
  `ResourceSnapshot`), device/nd4j (`DeviceRoutingConfigService`, `Nd4jEnvironmentConfigService`),
  `TritonCacheService`, `OpTimingService`, `ServerPortService`, **+ `scheduler` + `cluster`** sub-pkgs.
- **Proof:** this set imports **zero** other services sub-packages and **zero** non-kernel root classes.
  It only depends downward (app-core, `kompile-app-config`, leaf modules) — verified by a standalone
  import scan (empty back-edge set).

This is the single "clean carve" left in `services`, and it unblocks everything else (every domain
depends on model/GPU/resource/scheduler). Effort: comparable to `kompile-app-facts`/`ingest`
(1–2 focused days). Risk: **runtime-sensitive** — this is the resource governor, GPU routing, memory
watchdog; must be validated by a real deploy + crawl, not just compile.

## 5. Proposed target architecture (layered)

```
Layer 0  app-core, kompile-app-config, kompile-app-dto, leaf modules            (done)
Layer 1  kompile-app-platform     ~13K  model/gpu/memory/resource/scheduler     ← KEYSTONE, do first
Layer 2  kompile-app-mcp          ~8K   MCP integration        (clean of app-main-non-services)
         kompile-app-ingest-svc   ~16K  DocumentIngest, VectorStorePopulation, IngestProgress,
                                        IndexSync, CrossDocumentRelation, Contextual*, pipeline,
                                        preprocessing   (co-locates with kompile-app-ingest domain)
Layer 3  kompile-app-agent        ~11K  agent/chat + enforcer  (→ mcp, platform)
         kompile-app-crawl        ~3K   crawl orchestration    (→ agent, scheduler, cluster)
Layer 4  (residual in app-main)   subprocess launchers (~14K, most-entangled: root 35 + 33 app-main
                                  refs — the composition layer), + small services, + the 12 config
                                  wiring classes. app-main becomes the thin assembly/composition root.
```

Sizes are approximate; exact membership is decided per-phase by the same clean/dirty split used for
`web.dto` and `config` (files with no upward refs move; the rest stay + get an SPI).

## 6. Sequencing (risk-ordered)

1. **`kompile-app-platform`** — keystone, clean lower layer. Unblocks all. *(runtime-validate)*
2. **`kompile-app-mcp`** — `services/mcp` has **no** app-main-non-services refs; cleanest domain. Good second.
3. **`kompile-app-ingest-svc`** — biggest payoff, pairs with `kompile-app-ingest`. Hard part: the
   god-classes (`DocumentIngestService` 2367, `VectorStorePopulationService` 1300) and root↔pipeline
   back-edges → SPI inversions.
4. **`kompile-app-agent`** — depends on `mcp` (done by then) + platform.
5. **`kompile-app-crawl`** — depends on agent/scheduler/cluster.
6. **subprocess launchers** — do last or **leave in app-main** as the composition layer (root 35 + 33
   app-main refs make them the natural "wiring" residue; extracting them is high-cost, low-value).

## 7. Mechanics (reuse the proven pattern)

- **Keep package names** (`ai.kompile.app.services…`) — protects native-image reflect/resource-config and
  the ~dozens of in-app importers; split packages across module + app-main are already established here.
- **Invert back-edges with tiny SPIs** in the lower module, implemented in the higher layer / app-main —
  exactly like `FactSheetIndexConfigurer` and `Nd4jEnvironmentConfigProvider`. Optional collaborators
  (`@Autowired(required=false)`) are the easy ones.
- **Standalone module build = cycle-free proof.** Each phase: build the module offline first (must
  succeed without app-main), then app-main compile + test-compile.
- **Per-phase e2e validation is mandatory here** (unlike the leaf carves): deploy the uber jar + run a
  crawl. This layer owns resource governance, GPU routing, transactions, model lifecycle, and Spring
  wiring — build-green does not catch a broken `@Transactional` manager, a mis-scoped bean, or a
  resource-admission regression.
- **Config:** the 16 services→blocked-config edges are mostly platform services using
  `ResourceSchedulerConfig`/`AppConfig` — fold those into the platform-kernel phase (move the constant/
  properties down, or SPI).

## 8. Risks & open questions

- **God-classes.** `DocumentIngestService`, `SubprocessIngestLauncher`, `VectorStorePopulationService`
  are 1.3–2.8K LOC each and touch many collaborators; each is a mini-project to place cleanly.
- **Runtime-only failure modes.** Transaction managers, `@Async`/scheduling, GPU/CUDA routing, memory
  watchdog thresholds, Spring bean scoping — none are caught by the compiler. This is the main reason
  services decomposition is categorically riskier than the carving done so far.
- **`ingest-svc` vs the existing `kompile-app-ingest` domain module.** Decide whether the ingest
  *services* merge into `kompile-app-ingest` or form a sibling that depends on it (recommended: sibling,
  to keep the JPA-domain module lean).
- **Diminishing structural value below the platform kernel.** After platform + mcp, further splits are
  effort-heavy; app-main may reasonably stabilize as "thin assembly + web controllers + service
  orchestration + subprocess launchers" rather than fully atomized.

## 9. Bottom line

- **One clean, high-value move exists:** extract `kompile-app-platform` (~13K LOC, keystone, zero
  intra-services back-coupling). Do it as its own scoped task **with a deploy+crawl validation**.
- **Everything beyond it is real re-architecture** (SPIs, god-class placement, runtime validation),
  best run as a sequenced program (mcp → ingest-svc → agent → crawl), not another "continue."
- **Leaving the subprocess launchers + 12 wiring configs in app-main is a legitimate end-state** — that
  residue *is* the composition root.
