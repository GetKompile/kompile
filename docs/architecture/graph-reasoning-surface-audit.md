# Graph Reasoning Surface Audit

**Date:** 2026-07-10 · **Branch:** `feat/graph-reasoning-trace-enhancements` (working tree)
**Goal:** every feature of `kompile-graph-reasoning` reachable through one cohesive, updatable
graph infrastructure — a user who understands only *nodes and edges* can build a graph, ask it
questions, update it, and save/load it, without ever needing PSL / MEBN / FOL / subjective-logic
knowledge.

Method: four parallel read-only audits (library surface, infra exposure parity, update loop &
persistence, zero-expertise UX), with load-bearing claims re-verified directly against the tree.

---

## 1. Verdict

The library side is in strong shape: 44 packages, and `UnifiedGraph` genuinely is the hub —
topology, opinions, vector layers, weight maps, and model artifacts are all zero-setup instance
methods, and every major engine accepts a `ReasoningGraph` one-liner. The infra side has a solid
interactive update loop and a rich `ask_graph_verify` chain.

The gaps cluster into five headlines:

1. **The one-facade tool already exists and is registered nowhere.** The lib has
   `GraphQueryEngine` (intents: CAPABILITIES, OVERVIEW, SCHEMA, SEARCH, DESCRIBE, NEIGHBORS, PATH,
   TIMELINE, FACTS, SIMILAR, VERIFY, WHY, WHY_NOT, RANK, ASSETS, ARTIFACT) and the middleware has
   `GraphReasoningQueryTool` (`graph_reasoning_query`,
   `kompile-middleware/kompile-tools/kompile-tool-graph/.../GraphReasoningQueryTool.java:76`,
   `@Component @ConditionalOnBean(UnifiedGraphBridge)`) — built, tested, **absent from all three
   tool registries** and from both parity tests' required lists.
2. **~15 library capabilities are fully orphaned** — no REST, no MCP tool, no CLI: semiring
   proofs, DeepWhyNot, isotonic/conformal calibration, ATMS labels/`retractionImpact`, Belnap
   inconsistency/blame, Shapley attribution, QBAF/DF-QuAD/QE + claim dossier, PROV export,
   PCR5/ccFuse fusion, MPE/sensitivity/what-if (REST exists, no tool), KGE train/predict (REST
   exists, no tool), model-artifact bundling, simulator (REST only), MEBN authoring.
   Nearly all of the new E1–E18 work is lib-only except the verify chain.
3. **Bulk deletes silently break reasoning.** `deleteByFactSheetId` / `pruneNodes` / `pruneEdges`
   / `hardDeleteStaleNodes` are pure delegation in `EventPublishingKnowledgeGraphService:594-649`
   — no event, no stale flag, no cascade → FactStore keeps phantom atoms until a manual
   `graph_reproject`.
4. **Live reasoning state still doesn't ride in `.kgraph`.** Export merges live PSL/MEBN weight
   maps now (fixed since 07-08), but opinions come from the *stale analysis-asset snapshot*, not
   live KB state; traces and the ObservedFactJournal never ride along. Snapshots/undo inherit
   this.
5. **Two golden-path UX blockers:** `ask_graph_query` has no `compactHint()` so the `?`-variable
   syntax is invisible at COMPACT (queries silently return zero bindings), and there is **no way
   to discover predicate names** for `ask_graph_verify` (no `list_predicates` anywhere).

Corrections to the 2026-07-08 persistence audit (verified fixed on this branch): import now folds
all non-reserved relation attributes + `occurredAt` + tags into edge metadata
(`UnifiedGraphBridge.edgeMetadataOverlay:854-881`); export live-merges PSL/MEBN weight maps
(`mergeLiveWeightMaps:339-358`) and has an artifact contributor/importer SPI (TypeRegistry rides
along); `UnifiedGraphAnalysisAssetStore` is durable (`:53,143-153`); orphan opinions persist
(`UnifiedGraphWriter.writeOrphanOpinions:153-181`).

---

## 2. Library surface — reachability from UnifiedGraph

Classification: **A** = instance method on `UnifiedGraph`; **B** = pass the graph to a
constructor/static, one-liner; **C** = requires hand-built models/programs (expert knowledge);
**D** = utility.

| Capability | Class | Entry point |
|---|---|---|
| Topology, subgraph/neighborhood, facts(), types(), meta | A | `UnifiedGraph` |
| Vector layers, opinions, weight maps, putModel/putArtifact, save/load | A | `UnifiedGraph` |
| opinionStore(), embeddingTable(layer), withEmbeddingLayer() | A | `UnifiedGraph.java:450-481` |
| Hybrid ranking (auto PSL or BN) | B | `HybridReasoner.rank(graph)` (`hybrid/HybridReasoner.java:82-113`) |
| Query/verify/explain facade | B | `GraphQueryEngine.query(graph, Query.verify(…))` |
| PSL program from graph | B | `GraphPslProgramBuilder.build(graph):75` |
| Bayesian network from graph | B | `GraphBayesianNetworkBuilder.build(graph):58` |
| Node2Vec / RotatE (SameDiff Adam) KGE | B | `Node2VecLearner.learn:99`, `RotatELearner.learn:170` |
| Community, sparsity, pruning, scenarios | B | `LouvainDetector`, `SparsityMetrics`, `GraphPruner`, `Scenarios` |
| **Claim dossier** (subject/predicate/object in → verdict + trace out) | **B** | `DossierBuilder.assess(graph, s, p, o):113` |
| Belnap marking over graph facts | B | `BelnapMarking.mark(graph.facts())` |
| Custom PSL/Datalog/FOL rules | C | `PslRule`/`DatalogRule`/`FolRule` hand-authored (no rule-string parser in-lib) |
| MEBN MTheory | C (partial) | `RelationalMTheoryBuilder` needs typed `RelationDescriptor`s; infra-side `KgMTheoryBuilder` auto-builds lazily from the KG |
| WhyNot/DeepWhyNot, VerdictFragility, Shapley, ATMS, conformal/isotonic | C | need grounding artifacts (InferredFactStore, JustificationIndex, RuleNf, labeled data) |
| QBAF / ClaimAdjudicator | C | needs `EvidenceItem` list |

**Facade gaps (in-lib):** `GraphQueryEngine` does not route: DossierBuilder, ProvSerializer,
Shapley, Belnap/InconsistencyMeasures, ATMS, calibration, semiring proofs, DeepWhyNot, QBAF,
MEBN, OWL, weight learning, HighConflictFusion. `HybridReasoner` covers ranking only.

**DSL leaks at lib level:** the atom-key string format `"pred(arg1, arg2)"` is the *only* entry
for `DefaultKbVerifier.verify()`, `WhyNotExplainer.explain()`, `DeepWhyNot.explainDeep()`,
`VerdictFragility.assess()` — no typed `(predicate, args[])` overload. Mitigation exists:
`UnifiedGraph.facts()` (`UnifiedGraph.java:428-438`) auto-generates well-formed keys.

**Trace convergence confirmed:** ReasoningTrail/CompositeReasoningTrail/OpinionTree/Explanation/
ClaimDossier all have `toReasoningTrace()`; DerivationTree bridges via
`ReasoningTrace.fromDerivation(:199)`. `.kgraph` round-trips all five aspect families.

---

## 3. Exposure parity — orphaned capabilities

| Capability | Lib entry | REST | MCP (SSE) | MCP (stdio/CLI) | Status |
|---|---|---|---|---|---|
| Semiring proofs (Viterbi/top-k, ProofSet, ProofFragility) | `fol/semiring/*` | — | — | — | **ORPHANED** |
| DeepWhyNot (multi-hop why-not) | `DeepWhyNot.explainDeep:222` | — | — | — | **ORPHANED** (KbGroundingService uses shallow `WhyNotExplainer` only, `KbGroundingService.java:318-331`) |
| Isotonic + conformal calibration | `IsotonicCalibrator`, `ConformalVerdict`, `CalibrationHarness` | — | — | — | **ORPHANED**; live `PlattCalibrator` defaults to identity (w=1,b=0) → `calibratedConfidence` cosmetic (`KbGroundingController.java:144`) |
| ATMS labels / `retractionImpact` | `Atms.java:334` | coarsened via `/retract` | coarsened | coarsened | PARTIAL |
| Belnap 4-valued + blame | `BelnapMarking`, `InconsistencyMeasures.blame:205` | — | — | — | **ORPHANED** |
| Shapley source attribution | `ShapleyAttribution`, `ClaimShapley` | — | — | — | **ORPHANED** |
| QBAF / DF-QuAD / QE / **claim dossier** | `ClaimAdjudicator:102`, `DossierBuilder:113` | — | — | — | **ORPHANED** |
| PROV-N / PROV-JSON export | `ProvSerializer:122,213` | — | — | — | **ORPHANED** |
| PCR5 / ccFuse / HighConflictFusion | `confidence/ds/*` | — | — | — | **ORPHANED** |
| Joint MPE / sensitivity / what-if | `BayesianNetworkController.java:87,206,223,239` | ✔ | — | — | REST-only |
| KGE train / score / predict / similar | `KGEmbeddingController` `/api/knowledge-graph/embeddings` | ✔ | — | — | REST-only |
| Simulator (scenarios/runs/reason/promote) | `GraphSimulatorController` `/api/graph-sim` | ✔ | — | — | REST-only |
| Model-artifact bundling | `putModel/putArtifact` | internal | internal | — | PARTIAL |
| MEBN authoring (MTheory/MFrag write path) | `mebn/*` | read-only stats/theory | read-only | read-only | PARTIAL |
| **graph_reasoning_query facade tool** | `GraphReasoningQueryTool.java:76` | n/a | **NOT REGISTERED** | **NOT REGISTERED** | **ORPHANED TOOL** |

**Registry drift (repeat offender):** `graph_aggregate` / `graph_forecast` / `graph_centrality` /
`graph_rag_search` are CLI-only (backing REST is live); `graph_snapshot_*` @Tools are backend-only
(CLI covers snapshots via `knowledge_graph` actions). Three hand-maintained lists again let a
built tool (`graph_reasoning_query`) fall through — the parity tests only assert over their own
hardcoded required-lists, so a bean absent from *both* the registry and the test is invisible.

**Partial exposures:**
- `ReasoningTrailMapper.toTrailDto(ReasoningTrace)` (`app-agent/.../ReasoningTrailMapper.java:98-113`)
  drops `naturalLanguageSummary`, `evidence`, `activatedRules`, `computedAt`, `breakdown` — all of
  which the `ReasoningTrail` overload (`:47-85`) emits. Any tool that produces the canonical trace
  type renders poorer in chat than the legacy trail type.
- `/verify` response lacks calibration method/interval metadata (nothing to render even if tools wanted it).
- Bayesian `/query` drops MFrag/MTheory provenance the engine has internally.
- ask_graph_verify **does** render the new chain: verdict, evidence, counterEvidence,
  refutationBasis, unknownReason incl. near-miss, opinion, fragility (confirmed in
  `AskGraphVerifyTool` compactHint + formatter).

**Bypass paths (cohesion risks):** `CypherController` raw Neo4j writes (no events, no TMS, no
snapshots); `GraphHydrationController:60-82` mutates derived edges outside TMS bookkeeping;
`/api/graph-rag/search` returns ranked results with none of the grounding pipeline's
verification/calibration; KGE `/train` not fact-sheet-gated (`KGEmbeddingController:85`); Bayesian
endpoints default to global scope when `factSheetId` omitted.

---

## 4. Update loop

**Works (per-item interactive editing):** node/edge CRUD through the `@Primary`
`EventPublishingKnowledgeGraphService` → `Node/EdgeMutationEvent` → stale flag + cascade →
`GraphToFactStoreProjector` → subscriber fan-out (`KbFactSubscriptionService`) → CSR per-type
eviction → mutation JSONL. `ask_graph_assert`/`retract` → immediate cascade + ObservedFactJournal.
CSR cache invalidation is sound (per-type eviction on edge ops, full clear on node removal,
node-count sentinel).

**Gaps, ranked:**

| # | Gap | Evidence | Severity |
|---|---|---|---|
| U1 | Bulk deletes publish nothing — `deleteByFactSheetId`, `pruneNodes`, `pruneEdges`, `hardDeleteStaleNodes` are pure delegation → phantom atoms in FactStore, stale=false lies | `EventPublishingKnowledgeGraphService:594-649` | **P0** |
| U2 | Debounce is dead code for mutation events: `GraphMutationRecordingListener:48,56→70` calls IMMEDIATE `schedule()` for the same events `GroundingCascadeEventListener:122,155,188` debounces → every single edit fires a full cascade; the 15s/300s coalescing never engages | verified by grep | P1 (perf/design) |
| U3 | KGE/embedding layers never refresh on mutation — `TrainingSchedulerBridge` listens only to training-lifecycle events; no wire from graph change → re-embed/re-train | agent-verified | P1 |
| U4 | `graph_import`/snapshot-restore bypass the event decorator (direct `MatrixGraphStore` writes) — no per-item subscriber fan-out, no mutation-log records; cascade does fire via `GraphBuildCompletedEvent` | `UnifiedGraphBridge.importGraph:586-645` | P2 |
| U5 | Mutation JSONL is global `~/.kompile/graph-mutations.jsonl`, not per-project | `GraphMutationStore:123` | P2 |
| U6 | Analysis-asset UnifiedGraph view refreshes only on export/import — consumers of `analysisAssets.get()` see arbitrarily stale state; no incremental live↔UnifiedGraph sync exists | `UnifiedGraphAnalysisAssetStore` | P2 |

---

## 5. Persistence fidelity (`.kgraph` as "one file moves everything")

| Finding (from 07-08 audit) | 2026-07-10 verdict |
|---|---|
| Import drops non-reserved relation attrs / occurredAt / tags | **FIXED** — `edgeMetadataOverlay` folds all of it (`UnifiedGraphBridge:854-881`) |
| Export never carries learned weights | **FIXED** — `mergeLiveWeightMaps:339-358` pulls live PSL WeightStore + MEBN strengths; `restoreLiveWeightMaps:363-389` on import |
| TypeRegistry / artifacts | **FIXED** — contributor/importer SPI (`contributeArtifacts:647`, `restoreArtifacts:658`) |
| Asset store durability | **FIXED** — write-through `.kgraph` sidecar (`UnifiedGraphAnalysisAssetStore:53,143-153`) |
| Orphan opinions dropped by writer | **FIXED** — `UnifiedGraphWriter.writeOrphanOpinions:153-181` |
| **Export reads opinions from the stale analysis-asset snapshot, not live KB state** | **OPEN** — `mergeAnalysisAssets:326-336`; opinions computed by cascades since the last import/export are silently absent |
| **Imported opinions never pushed into live KB OpinionStore** (asset store only → invisible to grounding reasoners) | **OPEN** |
| Reasoning traces never bundled in `.kgraph` | **OPEN** (app-agent `ReasoningTraceStore` is transient) |
| ObservedFactJournal (agent-asserted facts) not bundled → snapshot/export not portable across instances | **OPEN** (same-instance replay works via the journal file) |
| Snapshots inherit all of the above | **OPEN** — "undo" restores topology+weights+assets but not live opinion state |

---

## 6. Zero-expertise UX

**Already good:** `graph_reason` is fully engine-agnostic ("the system automatically selects the
best reasoning approach"), `ask_graph_synthesize` takes natural language, assert/retract surface
`cascadeTriggered`/`stale` transparently, snapshots are discoverable in the `knowledge_graph`
compactHint, MCP export/import hardwire the full-fidelity format (no format choice).

**Blockers and leaks (ranked, smallest fix first):**

1. `ask_graph_query` has **no compactHint** → the `?`-variable prefix rule vanishes at COMPACT
   (the stdio default) → naive queries return zero bindings with no error. Same gap (lower
   stakes) for `ask_graph_explain`, `ask_graph_synthesize`, `ask_graph_mebn`.
2. **No predicate discovery** — nothing lists the predicate vocabulary of a fact sheet, yet
   `ask_graph_verify`/`query` require exact case-sensitive predicate names. Add
   `knowledge_graph action=list_predicates`.
3. `factSheetId` **required** on `ask_graph_retract` (`AskGraphRetractTool:88-98`) with no inline
   discovery or active-sheet default; optional everywhere else.
4. Jargon in required-path surfaces: `opinion{b,d,u,a}` unexplained in verify hint; "PSL rules
   from a completed grounding cascade" in the near-miss hint; "TMS retract"; "hybrid / MEBN" in
   `graph_import` messages; "embedding layers, opinions, weights" in `graph_export` description;
   `mode` enum `GROUNDING/HYBRID/CAUSAL/PSL/MEBN` on explain (auto-detected, so acceptable as
   opt-in, but names are internal).
5. CLI `graph export --format` is **required** (`GraphExportCommand:38`) and
   `docs/cli/graph-commands.md:27-28` doesn't say only `kgraph` is round-trip-complete.
6. `deriveOntology` runs invisibly in crawl enrichment (`UnifiedCrawlGraphServiceImpl:2517`), not
   surfaced in `crawl_source` params or results.
7. AGENTS.md has **no graph quickstart** (list_fact_sheets → crawl_source → ask/verify →
   snapshot); docs are developer-oriented with no "verify your first fact" path.

**Auto-derivation status** (house rule: ship enabled): PSL program ✔ auto in cascade; BN ✔ auto;
MEBN ✔ lazy (`KgMTheoryBuilder` BFS); entity resolution ✔ default-on; opinion seeding ✔ from
extraction confidence; ontology derivation ~ crawl-only (not on import); rule mining ~ cascade
when learning enabled + ontology-axiom rules need `bind_ontology`; KGE training ✘ manual REST
only; calibration ✘ identity placeholder.

---

## 7. Recommended roadmap

**P0 — cohesion & correctness**
1. Register `graph_reasoning_query` on both registries + both parity tests. It *is* the
   nodes-and-edges front door (capabilities/overview/schema/search/verify/why/why-not/rank in one
   tool). Extend it (or the lib `GraphQueryEngine`) with intents for the orphans as they land:
   DOSSIER, PROOFS, ATTRIBUTION, INCONSISTENCY, PROV.
2. Fix silent bulk deletes (U1): route through per-item deletes or publish a consolidated
   `GraphBatchMutationEvent` + stale + cascade.
3. Export live opinions from KB state instead of the stale asset snapshot; push imported opinions
   into the live OpinionStore. This also makes snapshots/undo faithful.
4. Registry meta-fix: parity tests should sweep *all* `@Tool`/`CliTool` beans on the classpath and
   fail on anything not registered or excluded — that's the test shape that would have caught
   `graph_reasoning_query`.

**P1 — feature parity through the front door**
5. `graph_claim` tool on `DossierBuilder.assess(graph, s, p, o)` — it's already Class B and fuses
   direct-edge/verifier/path/KGE/mined-rule signals into one verdict + trace. This single tool
   retires half the orphan list for end users.
6. Upgrade verify's near-miss path from `WhyNotExplainer` to `DeepWhyNot` (bounded depth).
7. Wrap existing REST in tools: MPE/sensitivity/what-if; KGE train/score/predict (+ debounced
   auto-retrain on `GraphBuildCompletedEvent` to fix U3); simulator run/promote.
8. `format=prov` option on explain endpoints (`ProvSerializer` is ready); bundle traces +
   ObservedFactJournal as `.kgraph` artifact entries.
9. Resolve the cascade double-trigger (U2): recording listener persists only; cascade scheduling
   owned solely by `GroundingCascadeEventListener` (debounced for mutations, immediate for
   assert/changeset).
10. Enrich `toTrailDto(ReasoningTrace)` to parity with the trail overload (summary, evidence,
    activatedRules, breakdown).
11. Put `graph_aggregate`/`graph_forecast`/`graph_centrality`/`graph_rag_search` on the backend
    registry; funnel Cypher/hydration writes through the event decorator.

**P2 — zero-expertise polish**
12. compactHints for query/explain/synthesize/mebn; `list_predicates` action; active-sheet default
    for retract; plain-English opinion labels (support/counter-evidence/uncertainty); de-jargon
    export/import/verify copy; CLI `--format` default `kgraph` + docs note; surface
    `deriveOntology` in `crawl_source`; AGENTS.md graph quickstart; wire `CalibrationHarness` so
    `calibratedConfidence` is real; typed `(predicate, args[])` overloads on lib
    verifier/explainer APIs.

---

*Related docs: `graph-reasoning-trace-enhancements.md` (feature spec, all E1–E18 shipped in-lib),
prior audits summarized in project memory (persistence 07-08, tool-surface 07-08/09).*
