# Graph Simulator ("Graph Lab") — Design

**Date:** 2026-07-02
**Status:** DESIGN — for review; no implementation yet
**Scope:** An interactive bench for hydrating sandbox knowledge graphs with different kinds of
datasets and watching what patterns the reasoning stack (PSL, MEBN, OWL-RL, causal, KGE/GNN,
entity resolution, opinion/confidence) learns — with the existing `GraphVisualizerComponent`
from kompile-app-main at the center.

**Companion docs (this design deliberately builds on them, not beside them):**
- `graph-hydration-inference-chain-design.md` — the 6-stage derivation DAG this bench exercises
- `graph-ui-kb-reasoning-cockpit-design.md` — visualizer / GraphsHub inventory and cockpit direction
- `grounding-evaluation-design.md` — the metric definitions the scorer reuses (FEVER-style, macro-F1, calibration)
- `reasoning-stack-usage-gaps.md` — the live baseline (17-step cascade) as of 2026-07-02

---

## 1. Problem

The reasoning stack is built and genuinely runs: every crawl executes
`GraphHydrationOrchestrator` (DERIVATION → PRUNE_COMPACT → GNN_SCORING → ONTOLOGY_CONFORMANCE →
HEALTH), whose DERIVATION stage is the 17-step `IncrementalReasoningOrchestrator.doReground()`
(graph→FactStore projection, PSL program build, HL-MRF MAP solve, entailment, Beta promotion,
hybrid consensus, PSL weight learning, TMS contradiction scan, optional MEBN learning).

But the **only way to exercise it today is a full crawl over real documents**. That has three
consequences:

1. **Slow, expensive feedback.** A crawl takes tens of minutes and burns LLM extraction calls
   before the reasoning stack even starts.
2. **No ground truth.** Real corpora don't come with "here is what the engines *should* have
   inferred," so there is no way to tell whether a violet INFERRED edge is insight or noise —
   or which engine deserves credit/blame.
3. **Non-repeatable.** Extraction is LLM-dependent and noisy, so no two runs hydrate the same
   graph; you cannot compare engine configurations, thresholds, or code changes on equal input.

The simulator closes this loop: **a sandbox graph, hydrated from a chosen dataset source
(synthetic scenario with planted ground truth, a real document corpus, structured files, or a
fork of an existing graph), reasoned over with selectively enabled engines, rendered live in the
existing visualizer, and scored against what was planted.**

### Non-goals

- No new reasoning algorithms. The simulator *drives* `GraphHydrationOrchestrator` and the
  existing services; it never re-implements them.
- No second visualizer. `GraphVisualizerComponent` is reused unchanged (one additive overlay
  input on `GraphCanvasComponent` is the only proposed touch).
- Not a load-test harness (that's `performance_harness` territory); scale knobs exist but the
  point is pattern fidelity, not throughput.
- Contradiction-detection unification is in-flight elsewhere; the noise-injection scenario
  integrates with whatever lands, it does not touch that code now.

---

## 2. Verified reuse inventory (what the simulator stands on)

| Seam | What it gives the simulator | Where |
|---|---|---|
| `GraphHydrationOrchestrator.run(factSheetId, HydrationConfig, BiConsumer<stage,msg>)` → `HydrationResult` | The whole reasoning pass, per fact sheet, with per-stage progress callbacks. BATCH and INCREMENTAL modes exist | `kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/GraphHydrationOrchestrator.java` |
| `HydrationConfig(enabledStageIds, confidencePruneThreshold, dryRun)` | Per-run stage selection (DERIVATION, PRUNE_COMPACT, GNN_SCORING, ONTOLOGY_CONFORMANCE, HEALTH), threshold knob, and a **dry-run mode** (preview without writes) | same package |
| `LearningMetrics` record | Structured "what was learned" telemetry per pass: factVersionsWritten, retracted, promoted, totalCorroboration, StrengthBand distribution (ESTABLISHED/HIGH/PROBABLE/SPECULATIVE/SUPPRESSED). Rule-weight deltas documented as a deferred gap (`WEIGHT_DELTA_UNAVAILABLE`) | same package |
| 17-step cascade | PSL program build (auto propagation rules + `*.psl` + ontology DOMAIN/RANGE + OWL-derived), MAP solve, entail → `InferredFactStore`, Beta promotion (`FactPromotionTracker`), hybrid consensus, 1-step weight learning, TMS scan, optional MEBN learning | `IncrementalReasoningOrchestrator` (kompile-knowledge-graph) |
| Inferred-fact materialization | `InferredFactGraphMaterializer` behind `kbCascadeMaterializeInferredEnabled` with idempotent-replace, min-confidence gate, feedback-loop guard — safe to enable per sim run so inferences become *visible edges* | kompile-knowledge-graph |
| MEBN auto-enable | `kbMebnAutoEnableWhenOntologyBound=true` via `OntologyProjectionProvider.hasBoundOntology`; bounded MTheory (≤20 MFrags) | crawl-graph + knowledge-graph |
| OWL-RL loop | `GraphOntologyBindingService.autoProvisionStructuralOntology`, `owlDerivedPslRules`, has-a closure → `EdgeProvenance.INFERRED` edges, is-a → `owlInferredTypes` node metadata; `POST /api/graph-ontology/classify` | app-main + crawl-graph |
| `GraphVisualizerComponent` | The centerpiece. `@Input factSheetId` / `factSheetName`; tabs Details/Relations/Filter/Weights/Layout/Attribution; already renders entity_type coloring, violet INFERRED edges, `InferredRelationOverlay`, temporal filters; calls `GET /fact-sheets/{id}/graph/visualization`, statistics, node expand, `/attribution/explain-quick` | `kompile-app-main/src/main/frontend/src/app/components/graph-visualizer/` |
| `GraphCanvasComponent` | D3 renderer with **additive overlay inputs** (`posteriorOverlay`, `priorOverlay`, `mebnMfragMap`, `influenceOverlayActive`) — the pattern the ground-truth overlay follows | same dir |
| `BayesianPanelComponent` | MEBN posteriors, what-if, MPE against `/attribution/bayesian/*` — already wired into the visualizer's Attribution tab | same dir |
| `GraphsHubComponent` | 24 panels (opinions, communities, folRules, factsByTier, weights, provenance, health, eval debugger, grounding console/monitor, audit, ontology…) all driven by `FactSheetService.activeSheet$` — **every panel works on a sim sheet for free** | `components/graphs-hub/` |
| Fact-sheet lifecycle | `POST /api/fact-sheets` (create), `POST /{id}/activate`, `DELETE /{id}`, `POST /{src}/copy-to/{dst}` | `app-main/.../web/controllers/FactSheetController.java` |
| Graph store | `@Primary` `MatrixKnowledgeGraphService` (never JPA), `graphIdForFactSheet = "factsheet_"+id`, `getOrCreateGraph` persists `:meta`, **batched** node/edge writes (`createEdgesBatch`, single-RPC, subprocess-safe) | kompile-knowledge-graph |
| Crawl entry | `POST /api/unified-crawl/start` (+ `deriveOntology` toggle, `OntologyAutoProvisioner` SPI); structured-format path Tika → `TikaGenericGraphExtractor` (JSON/YAML/XML/CSV/TSV/MD); domain-planning demo manifests | kompile-crawl-graph + app-main |
| Live progress | `CrawlProgressEvent` SSE (STARTED / throttled PROGRESS / COMPLETED / ERROR) with EventSource subscribers in both crawl UIs; `reasoning_trace` SSE → `ReasoningTrailComponent` for explanation cards | app-core/app-main |
| Evaluation | `kompile-evaluation` `GraphEvaluator`s (EntityPresence, EntityTypeAccuracy, RelationshipPresence, GraphCompleteness — LLM-judged) + `grounding-evaluation-design.md` metric definitions (3-class verify accuracy, macro-F1 ≥70% synthetic target, calibration/ECE) | kompile-middleware/kompile-evaluation |
| Grounding primitives | `KbVerifier`, `ConjunctiveQueryEngine`, `DerivationTree` (lib, `fol/grounding/`) — lets the scorer *verify* planted claims through the same path agents use | kompile-graph-reasoning |

---

## 3. Core decision — a simulation run IS a fact sheet

Each run creates a real fact sheet (`POST /api/fact-sheets`, name `Sim: <scenario> #<n>`,
`simulator=true` marker in its metadata/description). Its graph id is the standard
`factsheet_<id>`.

**Why this and not a new `sim_<runId>` graph namespace:**
- The visualizer, GraphsHub's 24 panels, hydration, ontology binding, MEBN auto-enable,
  attribution endpoints, statistics — *everything* is keyed by fact sheet. Reuse is total and
  immediate; a separate namespace would need a parallel read stack and a visualizer data-source
  seam for zero benefit.
- Cleanup and promotion are existing operations: `DELETE /api/fact-sheets/{id}` disposes a
  sandbox; "promote" = keep the sheet and drop the sim marker.
- The graph itself lives purely in the matrix store as always. The fact-sheet *row* is normal
  app domain lifecycle — this does not put graph code on JPA.

Sim sheets are ordinary sheets everywhere (mandate: features on, no hidden modes). The
fact-sheet picker gets an optional "hide simulations" filter chip (default OFF — decision point
Q1 below).

---

## 4. Dataset kinds — the `GraphScenario` SPI

One small new SPI, infra-free and deterministic, in the reasoning library
(`ai.kompile.graph.reasoning.simulation`), so scenarios are unit-testable in the lib and usable
by lib tests as fixtures:

```java
public interface GraphScenario {
    ScenarioDescriptor describe();          // id, name, param schema, dataset kind
    ScenarioRun generate(long seed, Map<String,Object> params);
}
// ScenarioRun = List<TickBatch> + GroundTruthManifest (+ optional OntologySpec)
// TickBatch  = nodes[] (name, entityType, metadata, occurredAt?) + edges[] (src, dst, relationType, confidence, metadata)
```

Store-agnostic records; the runner (below) maps them onto `KnowledgeGraphService` batch calls.
`Date.now`/randomness only via the injected `seed` — same (scenario, seed, params) must always
produce the same graph, or scoring and regression tests are meaningless.

### 4.1 Synthetic scenarios (v1 set — each plants known patterns)

| Scenario | Generates | Planted truth (what engines should learn) | Exercises |
|---|---|---|---|
| **rule-world** | Entities + facts sampled from a *hidden* weighted rule set, plus held-out entailments and noise | The held-out entailed edges; the generating rules/weights | PSL grounding + MAP + weight learning; the purest "did it learn the pattern" test |
| **org-network** | Org chart, roles, memberships, collaborations; structural ontology emitted as `OntologySpec` | is-a/has-a closure edges; transitive reporting chains; community partition | OWL-RL closure, ontology conformance, communities (Louvain/PageRank stats), PSL propagation, MEBN (ontology-bound → auto-enables) |
| **duplicate-identity** | The same underlying entities under surface-form variants, aliases, barcodes/emails | The duplicate sets; expected `RESOLVES_TO` / merges | EntityResolutionService, GraphCompactionService, barcode identity graph |
| **causal-chain** | Temporal event log from a known causal DAG with confounders and lag; `occurredAt` stamped | The true causal links (and the non-links through confounders) | Causal attribution, temporal reasoning, event patterns |
| **noise injector** (decorator) | Contradictions, stale facts, wrong types layered on any scenario at a configurable rate | Which facts are corrupt; which should end SUPPRESSED/retracted | TMS scan, Beta/Opinion updates, pruning; integrates with in-flight contradiction work *later* |

`GroundTruthManifest` = planted entities, duplicate sets, expected inferred edges
(subject/predicate/object + why), expected types, expected communities, expected causal links,
generating rules + weights, corrupted-fact keys. Persisted as JSON with the run.

### 4.2 Real-data kinds (same bench, no ground-truth manifest)

- **Corpus mode** — a docs directory / upload manifest (e.g. the domain-planning demo set) crawled into
  the sim sheet via the existing `POST /api/unified-crawl/start` (with `deriveOntology` toggle).
  Slow and LLM-dependent — that is the point of having it *and* synthetic mode.
- **Structured files** — CSV/JSON/YAML/XML through the existing structured-format extraction.
- **Fork mode** — copy an existing fact sheet's graph into a sandbox sheet (batched read →
  batched write) to experiment destructively without touching the source. Honors the
  non-destructive mandate.

Scoring for these kinds falls back to the LLM `GraphEvaluator`s (§6).

---

## 5. Backend — `SimulationRunService` (kompile-crawl-graph) + controller (app-main)

The runner orchestrates; it owns **no** reasoning logic.

**Run lifecycle:** `CONFIGURED → HYDRATING(tick i/N) → REASONING(stage) → SCORING → IDLE`
(loops per tick in streamed mode). One run at a time per fact sheet; runs are registered in an
in-memory registry + a durable JSON journal under `~/.kompile/simulations/<runId>/`
(config, manifest, per-tick metrics) so a run's *analysis* survives restart, and hydration is
**per-step re-runnable**: deterministic node/edge ids per (scenario, seed, tick) make re-applying
a tick idempotent.

**Per tick:**
1. Apply `TickBatch` via batched node/edge creation (single RPC batches; matrix-subprocess safe).
2. If "reason every K ticks" hits: `GraphHydrationOrchestrator.run(factSheetId, uiConfig, cb)`
   — `uiConfig` built from the page's stage toggles + threshold + dryRun; progress callback
   republished as SSE. For sim runs, inferred-fact materialization is enabled *for that pass*
   (existing guards: idempotent replace, min-conf, feedback-loop guard) so inferences appear as
   violet edges in the visualizer. The `InferredFactStore` is additionally read directly so
   below-threshold derivations still show in the inspector.
3. Collect `HydrationResult` + `LearningMetrics` + per-family pattern reports (inferred edges,
   `owlInferredTypes`, RESOLVES_TO/merges, community assignments, MEBN posterior summaries).
4. Score vs `GroundTruthManifest` (§6); append to the run's metric timeline.
5. Emit `SimulationProgressEvent` (same shape/discipline as `CrawlProgressEvent`, throttled).

**REST (all new, thin):**
```
GET    /api/graph-sim/scenarios                      — descriptors + param schemas
POST   /api/graph-sim/runs                           — {scenarioId|corpus|files|forkFactSheetId, params, seed, tickSize, reasonEveryK, stageToggles, threshold, dryRun} → creates sheet + run
GET    /api/graph-sim/runs / runs/{id}               — status, timeline, reports
POST   /api/graph-sim/runs/{id}/play|pause|step      — tick control
POST   /api/graph-sim/runs/{id}/reason               — re-run selected stages now (re-entrant)
GET    /api/graph-sim/runs/{id}/score                — per-family precision/recall/F1 + calibration
GET    /api/graph-sim/runs/{id}/ground-truth         — reveal manifest (for the overlay)
POST   /api/graph-sim/runs/{id}/promote              — keep sheet, drop sim marker
DELETE /api/graph-sim/runs/{id}                      — dispose run + sheet (explicit, sandbox-only)
GET    /api/graph-sim/runs/{id}/events               — SSE
```

**Config:** managed-config only (KbConfig pattern, NO `@Value`): `kbSimMaxNodesPerRun`,
`kbSimDefaultTickSize`, `kbSimMaterializeInferred` (default **true** — sim-scoped),
`kbSimScorePerTick` (default true). Feature itself is always on; these are value knobs.

---

## 6. Scoring — "did it learn the right patterns?"

**Deterministic scorer** (new, lib: `PatternRecoveryScorer`) for synthetic scenarios — no LLM:

- Per pattern family: precision / recall / F1 of recovered vs planted
  (inferred edges, inferred types, resolutions as pairwise-cluster P/R, communities as pairwise
  F1/ARI, causal links). "Recovered" is checked through `KbVerifier`/`ConjunctiveQueryEngine`
  where applicable — the same path agents use, per `grounding-evaluation-design.md`.
- **Calibration:** bucket recovered-claim confidence vs correctness → reliability curve + ECE
  (the design doc's §2 metrics; synthetic macro-F1 target ≥ 0.70).
- **Learning curves:** score per tick → does precision rise with corroboration? do StrengthBands
  migrate SPECULATIVE → ESTABLISHED for true facts and → SUPPRESSED for planted noise?
- Hallucination rate = recovered-but-never-planted-and-not-entailed, per family.

**Corpus/files/fork modes:** no exact truth → reuse `kompile-evaluation` LLM evaluators
(GraphCompleteness, EntityPresence, RelationshipPresence, EntityTypeAccuracy) as the soft score.

**Known telemetry gap (optional follow-up, not required for v1):** per-rule weight deltas are
documented as `WEIGHT_DELTA_UNAVAILABLE` in `LearningMetrics` — closing it (richer
`RegroundResult` or a `PslWeightEvent`) upgrades rule-world from "did it recover entailments" to
"watch the rule weights converge," which is the single most vivid "patterns it learns" display.

---

## 7. UI — the visualizer at the center

New standalone page **Graph Simulator** (sidebar entry + route, on by default; standalone
component, `var(--token)` theme tokens, dark-mode compliant, displayRef-style labels — never raw
node/edge ids).

```
┌──────────────┬──────────────────────────────────────────┬───────────────────┐
│ SCENARIO     │                                          │ LEARNED PATTERNS  │
│  kind ▾      │                                          │ ┌───────────────┐ │
│  params…     │        <app-graph-visualizer             │ │Inferred (12/15)│ │
│  seed        │           [factSheetId]="run.sheetId">   │ │Types    (8/8) │ │
│  ontology ☑  │                                          │ │Resolved (4/6) │ │
│ ENGINES      │     (unchanged component: violet         │ │Communit.(3/3) │ │
│  DERIVATION☑ │      INFERRED edges, entity_type         │ │Causal   (2/5) │ │
│  PRUNE     ☑ │      colors, Attribution/Bayesian        │ └───────────────┘ │
│  GNN       ☑ │      panels, Ontology tab, filters)      │ ▸ each item:      │
│  ONTOLOGY  ☑ │                                          │   claim, band chip│
│  MEBN      ☑ │   [ground-truth overlay: ✓ green         │   explain, ✓/✗/△ │
│ RUN          │    recovered · amber missed(ghost)       │ [Reveal truth ⇄]  │
│ ▶ play ⏸ ⏭  │    · red hallucinated]                   │ [Open in GraphsHub]│
├──────────────┴──────────────────────────────────────────┴───────────────────┤
│ TIMELINE  tick 7/20   nodes 340  edges 912  inferred 27  promoted 9         │
│ [band distribution stacked area] [P/R per family line chart] [ECE]          │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **Center:** `GraphVisualizerComponent` embedded exactly as GraphsHub embeds it
  (`[factSheetId]`). Refresh on tick via its existing reload path, triggered by the SSE stream
  (EventSource service copied from the crawl UIs' pattern, throttled).
- **Right rail:** pattern inspector fed by the run report; explanations reuse
  `/attribution/explain-quick` and the `reasoning_trace` trail card component. Ground-truth
  compare is a reveal toggle — before reveal you see what it learned; after, the scoreboard.
- **Ground-truth overlay** is the one visualizer-adjacent code change: a new additive
  `@Input` on `GraphCanvasComponent` (exactly the `posteriorOverlay` pattern): per-edge/node
  status map → green/amber(ghost)/red styling. No D3 internals touched.
- **Bottom:** timeline of `LearningMetrics` + scores per tick.
- **Open in Graphs Hub** activates the sheet — all 24 existing panels (opinions, communities,
  FOL rules, facts-by-tier, weights, eval debugger…) become the deep-inspection surface for
  free. The simulator page stays focused on the loop.

---

## 8. New vs reused (explicit)

**New code:** `GraphScenario` SPI + 4 generators + noise decorator + `GroundTruthManifest` +
`PatternRecoveryScorer` (lib, infra-free); `SimulationRunService` + run journal + SSE event +
`/api/graph-sim/*` controller (crawl-graph/app-main); simulator page + inspector + timeline
(frontend); ONE additive overlay `@Input` on `GraphCanvasComponent`; managed-config keys.

**Reused unchanged:** `GraphVisualizerComponent`, `BayesianPanelComponent`, GraphsHub + all
panels, `GraphHydrationOrchestrator` + the entire reasoning stack, matrix store + batch APIs,
fact-sheet lifecycle, unified-crawl + structured extraction, SSE/EventSource pattern,
`kompile-evaluation`, `KbVerifier`/query engine, attribution + reasoning-trail endpoints.

---

## 9. Mandates honored

- **Graphs never touch JPA** — all graph writes via the `@Primary` matrix store batch APIs;
  the fact-sheet row is ordinary app domain, not graph code.
- **Ship enabled** — page and scenarios on by default; only value knobs in config; the one
  boolean (`kbSimMaterializeInferred`) defaults **true**.
- **Managed config, no `@Value`.**
- **Batched, subprocess-safe writes** — no per-edge RPC storms.
- **Non-destructive / re-runnable** — sandbox sheets; fork-don't-mutate; deterministic
  idempotent ticks; every step re-invocable; disposal only ever deletes sim-marked sheets.
- **No raw ids in UI** — displayRef pipeline.
- **Deterministic seeds** — every scenario is a TDD fixture: (scenario, seed) → exact expected
  inferred set; regression tests assert the scorer's F1 stays at 1.0 for noise-free runs.

---

## 10. Phasing

- **P1 — the bench:** fact-sheet sandbox + SPI + `rule-world` + `org-network` + all-at-once
  hydration + reason + deterministic scorer + page (visualizer center, inspector, score cards).
- **P2 — the movie:** streamed ticks, timeline/learning curves, ground-truth canvas overlay,
  `duplicate-identity` + `causal-chain` scenarios, dry-run preview.
- **P3 — real data:** corpus mode via unified-crawl, structured files, fork mode, LLM-evaluator
  scoring.
- **P4 — optional deepening:** rule-weight-delta plumbing (richer `RegroundResult`), noise
  scenario ↔ contradiction-unification integration (after that work lands), ask_graph
  agent-in-the-loop (query the sim KB via MCP and score answers).

## 11. Open questions (for review)

1. Sandbox-as-fact-sheet: confirmed? Should sim sheets be visible in all normal pickers
   (mandate-friendly) with just a chip, or filterable-out by default?
2. Is synthetic-first (P1/P2) the right priority, with corpus replay at P3 — or is crawling a
   real corpus into the bench a day-one requirement?
3. Per-run inferred-edge materialization ON for sim sheets (visible violet edges; existing
   guards) — acceptable while the global cascade default stays false?
4. Placement: standalone sidebar page (proposed) vs a 25th GraphsHub tab?
5. Is the P4 rule-weight-delta follow-up in scope for this effort, or a separate work package?
