# Learning Loop Gap Analysis

**Date:** 2026-06-22  
**Scope:** End-to-end learning loop (TRAIN weights/priors from extracted relations during crawl enrichment + UPDATE them incrementally on new documents) vs. the inference-only path that exists today.

---

## Executive Summary

**Direct answer:** The weight/embedding/prior learners are called by ONLY human correction (`KbCorrectionService.correct`). They are NEVER invoked by the crawl or cascade. The enrichment pipeline runs pure MAP inference with default weights (0.8 propagation rules) and never trains anything.

---

## Gap Table

| # | Link | Status | Evidence |
|---|------|--------|----------|
| 1 | **Unified-crawl ENRICHMENT step observable with granular progress** | **WIRED** | `UnifiedCrawlGraphServiceImpl.java:1653–1711` calls `GraphHydrationOrchestrator.run(factSheetId, config, progressCallback)` inside Phase 9 of `executeJob`. The callback fans out to `recordHydrationSubStageProgress` → `updatePipelineStep` → `CrawlProgressEvent` SSE. `GraphHydrationOrchestrator.java:77–79` defines `STAGE_DERIVATION / STAGE_PRUNE_COMPACT / STAGE_HEALTH` constants. `graphs-hub.component.html:97–99` shows the `hydration` sub-tab with `<app-hydration-progress-panel>`. |
| 2 | **Graph produces PRIORS and WEIGHTS established by learning during enrichment** | **MISSING** | `GraphHydrationOrchestrator.java:98–207` has exactly 3 stages: DERIVATION (→ `IncrementalReasoningOrchestrator.runFullReground`), PRUNE_COMPACT, HEALTH. None of the stages calls `PslWeightLearningService`, `MebnWeightLearner`, `SameDiffEmbeddingTrainer`, or any other learner. DERIVATION runs MAP inference with hard-coded 0.8 propagation rules (`IncrementalReasoningOrchestrator.java:440–451`). Priors = default; weights = default 0.8 for every crawl. |
| 3 | **Relation extraction happens before enrichment** | **WIRED** | `UnifiedCrawlGraphServiceImpl.java` Phases 1–8 (GRAPH_EXTRACTION → ENTITY_RESOLUTION → EDGE_COMPUTATION) run before Phase 9 (ENRICHMENT). `GraphExtractionOrchestrator`, `GraphCompactionService`, and `graphEdgeComputationService` are all called first. Relations are persisted to the graph store before `graphHydrationOrchestrator.run` is called at line 1666. |
| 4 | **Enrichment TRAINS learners / establishes priors from extracted relations** | **MISSING (THE CRUX)** | `IncrementalReasoningOrchestrator.doReground` (line 207–348) does: (a) project graph→FactStore, (b) build PSL program from facts with default 0.8 rules, (c) MAP solve, (d) materialize InferredFacts, (e) register program snapshot in `KbCorrectionService`. There is NO call to `PslWeightLearningService.learn/learnAndApply/updateOnBatch`, NO call to `MebnWeightLearner.learn`, NO call to `SameDiffEmbeddingTrainer`, NO call to `RotatELearner`, NO call to `Node2VecLearner`. The program snapshot is registered at line 319–321 so a *subsequent* human correction can warm-start weight updates — but NO learning happens during the cascade or crawl itself. |
| 4b | **WHO calls each learner** | **Human correction ONLY** | Full non-test grep of `PslWeightLearningService`, `updateOnBatch`, `learnAndApply`, `MebnWeightLearner`, `SameDiffEmbeddingTrainer`, `RotatELearner`, `Node2VecLearner` across the entire repo returns exactly two files outside the library itself: `KbCorrectionService.java` (PSL updateOnBatch at line 139) and `MatrixKgEmbeddingGraphAdapter.java` (storeEmbeddings — the write-back path called ONLY from `KGEmbeddingJobService.executeTrainingAsync` triggered by REST POST `/api/knowledge-graph/embeddings/train`). The crawl and cascade NEVER invoke any learner. |
| 5 | **UPDATE STEP: new document re-runs cascade AND updates weights/priors (minibatch)** | **PARTIAL → MISSING** | `GroundingCascadeHook.java:122–134` (`onChangesetCompleted`) schedules `GraphHydrationOrchestrator.enrich(factSheetId)` (line 215) on every `GraphChangesetCompletedEvent`. The cascade re-runs DERIVATION + PRUNE_COMPACT + HEALTH — but as noted in #4 this is pure re-inference with the same default weights. There is NO minibatch weight update. The `FileBackedWeightStore` bean exists (`kompile-knowledge-graph/.../persistence/FileBackedWeightStore.java`) and `KbCorrectionService` uses an `InMemoryWeightStore` (line 144), not the file-backed one — so learned weights from corrections are **not durable across restarts**. |
| 6a | **UI: enrichment progress observable** | **WIRED** | Graphs Hub → Hydration tab → `<app-hydration-progress-panel>` (`graphs-hub.component.html:97–100`). |
| 6b | **UI: learned PSL weights observable/controllable** | **MISSING** | No tab in Graphs Hub (`graphs-hub.component.html` — 22 tabs total) shows PSL rule weights, weight history, training status, or weight-tuning triggers. The `audit` tab (`app-audit-timeline`) shows WEIGHT_TUNED events post-hoc (`audit-timeline.component.ts:71`) but has no trigger for weight training. |
| 6c | **UI: KGE embedding training accessible** | **PARTIAL / ORPHANED** | `KGEmbeddingsComponent` exists (`kg-embeddings.component.ts`) and calls POST `/api/knowledge-graph/embeddings/train`. It is declared in `app.module.ts` (line 76, 219) but is NOT wired into `graphs-hub.component.html` or any active nav tab — the `<app-kg-embeddings>` selector appears nowhere in the template tree. The component is registered but unreachable from the UI. |
| 6d | **UI: MEBN prior control, Bayesian prior-setting UI** | **MISSING** | No UI panel exists for MEBN edge-strength learning, Bayesian prior initialization, or manual prior-setting. The `bayesian-panel.component.ts` exists under `graph-visualizer` but does not expose `MebnWeightLearner` or Bayesian prior controls. |
| 6e | **REST: correction endpoint (triggers PSL mini-batch)** | **WIRED** | `KbGroundingAuditController.java:66–80` — POST `/api/kb-grounding/{factSheetId}/corrections`. Package `ai.kompile.knowledgegraph.grounding.controller` is in `GlobalExceptionHandler.basePackages` (line 48). `audit-timeline.component.ts` has a correction form that calls this endpoint. |
| 6f | **REST: PSL weight store / weight history endpoint** | **MISSING** | No REST controller exposes `FileBackedWeightStore`, PSL weight snapshots by version, or the weight-tuning history JSONL. |
| 6g | **REST: KGE embedding training endpoint** | **WIRED (but UI-orphaned)** | `KGEmbeddingController.java:85–111` — POST `/api/knowledge-graph/embeddings/train`. Calls `KGEmbeddingJobService.startTraining` which extracts triples from the live matrix store (`MatrixKgEmbeddingGraphAdapter`) and trains TransE or RotatE. Results written back via `adapter.storeEmbeddings`. WIRED but not auto-triggered by crawl. |
| 6h | **Weights durable across restarts** | **MISSING for correction path** | `KbCorrectionService.getWeightStore` always creates `InMemoryWeightStore` (line 270). `FileBackedWeightStore` exists as a `@Component` but is not injected into `KbCorrectionService`. PSL weights from human corrections vanish on restart. |

---

## Prioritized Build List to Close the Learning Loop

### P0 — Wire PSL weight learning into the ENRICHMENT cascade (highest impact, no new abstractions)

**What:** After the MAP solve in `IncrementalReasoningOrchestrator.doReground`, call `PslWeightLearningService.updateOnBatch` using the InferredFacts written in this run as the ground-truth signal (atom key → posterior value).

**Where:** `IncrementalReasoningOrchestrator.java:302–315` (after the `for (EntailmentRecord record : records)` loop, before `registerProgram`). The program snapshot is already registered with `correctionService`; add a learning step between step 5 and step 6.

**Dependency:** Load previous weights via `FileBackedWeightStore.latest(factSheetId+":program")` to warm-start, apply `updateOnBatch(program, groundTruthMap, steps)`, save back. This makes every cascade run a minibatch update.

**Impact:** Closes gap #4 entirely. Inference thereafter uses learned, not default 0.8, weights.

---

### P1 — Wire KGE embedding training into the ENRICHMENT step or post-crawl hook

**What:** After the ENRICHMENT phase in `UnifiedCrawlGraphServiceImpl.java:1688` (post-hydration), call `KGEmbeddingJobService.startTraining(factSheetId, ROTATE, defaultConfig)` if no training job is running for that fact sheet.

**Where:** `UnifiedCrawlGraphServiceImpl.java:~1690` — add an optional phase after `completePipelineStep(job, "ENRICHMENT", ...)`. Guard with `@Autowired(required=false)` on `KGEmbeddingJobService`.

**Impact:** Closes gap #4b for KGE embeddings. RotatE/TransE vectors become up-to-date after each crawl without manual trigger.

---

### P2 — Fix weight durability: inject FileBackedWeightStore into KbCorrectionService

**What:** Replace `new InMemoryWeightStore()` in `KbCorrectionService.getWeightStore` (line 270) with the Spring-managed `FileBackedWeightStore` bean.

**Where:** `KbCorrectionService.java:77–83` — add `@Autowired FileBackedWeightStore fileBackedWeightStore`, change `getWeightStore()` to return `fileBackedWeightStore.fileWeightStoreFor(String.valueOf(factSheetId))`.

**Impact:** Closes gap #6h. Learned PSL weights from human corrections survive restarts and warm-start the next crawl enrichment.

---

### P3 — Wire KGEmbeddingsComponent into Graphs Hub

**What:** Add a `kgeTraining` sub-tab to `graphs-hub.component.html` pointing to `<app-kg-embeddings [factSheetId]="activeFactSheetId">`.

**Where:** `graphs-hub.component.html` after the `hydration` tab (line 97). Add matching `*ngIf="activeSubTab === 'kgeTraining'"` content block.

**Impact:** Closes gap #6c. KGE training is already fully implemented — just unreachable from any UI nav.

---

### P4 — Add PSL Weights panel to Graphs Hub (learning status + weight history)

**What:** New `WeightsPanelComponent` calling GET `/api/kb-grounding/{factSheetId}/weights` (new REST endpoint exposing `FileBackedWeightStore.versions + latest`) and listing WEIGHT_TUNED audit events from the existing audit trail.

**Where:** New REST endpoint in `KbGroundingAuditController.java` (GET `/api/kb-grounding/{factSheetId}/weights/{programId}`). New Angular component. New sub-tab `weights` in `graphs-hub.component.html`.

**Impact:** Closes gaps #6b and #6f together. Makes the learning history observable.

---

### P5 (Deferred) — Incremental semi-naive cascade + MEBN learning wiring

**What:** Replace `buildProgramFromFactStore` default 0.8 rules with rules loaded from `GraphPslProgramBuilder` (using live `KnowledgeGraphService`); wire `MebnWeightLearner` into a post-cascade MEBN re-fitting step.

**Where:** `IncrementalReasoningOrchestrator.java:408–455` (comment already identifies this as a TODO). Requires injecting `KnowledgeGraphService` and `MebnWeightLearner` (currently not Spring beans, plain Java classes).

**Impact:** Closes remaining MEBN learning gap. Lower priority because PSL and KGE cover the immediate learning loop.

---

## Supplementary Notes

### Why the inference runs but learns nothing

`IncrementalReasoningOrchestrator.doReground` at line 233:
```
PslProgram program = buildProgramFromFactStore(factStore);
```
`buildProgramFromFactStore` (line 408–454) always constructs rules with `program.addRule("0.8: " + pred + "(?X) -> derived_" + pred + "(?X)")`. This is a constant-weight soft propagation rule, not a learned weight. The MAP solve optimizes atom values *given* these weights — it does not update the weights. The "learned weights" path in `PslWeightLearningService.updateOnBatch` is only reachable via `KbCorrectionService.correct` (line 139) which is triggered by a human POST to `/api/kb-grounding/{factSheetId}/corrections`.

### KGEmbeddingJobService: fully functional but manually triggered only

`KGEmbeddingJobService.startTraining` (line 90) is complete: it extracts triples from the live matrix store, runs TransE/RotatE training with per-epoch WebSocket progress, and writes embeddings back. The training pipeline is correct and production-ready. The only gap is the auto-trigger from crawl/cascade and the UI nav link.

### FileBackedWeightStore is Spring-managed but unused

`FileBackedWeightStore` is a `@Component` annotated with `@Value("${kompile.data.dir:}")`. It persists weights under `<dataDir>/data/graph/reasoning/`. It is injected nowhere in production code — `KbCorrectionService` uses `InMemoryWeightStore` via `computeIfAbsent(factSheetId, id -> new InMemoryWeightStore())`.

### Correction REST → weight mini-batch path is functional

POST `/api/kb-grounding/{factSheetId}/corrections` → `KbCorrectionService.correct` → `PslWeightLearningService.updateOnBatch(program, Map.of(atomKey, effectiveValue), 3)` → `ws.save(factSheetId + ":program", updated.rules())` → but `ws` is `InMemoryWeightStore`, so the update is in-memory only. The `FactAuditEvent.weightTuned` events are emitted to `FileBackedAuditLog` (durable). The audit trail is durable; the weights are not.
