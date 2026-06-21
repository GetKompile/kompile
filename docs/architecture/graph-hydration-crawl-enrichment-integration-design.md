# Graph Hydration — Unified Crawl ENRICHMENT Phase Integration Design

**Status**: DESIGN (no code, no Maven)
**Date**: 2026-06-21
**User direction**: "graph enrichment integration — there are already pre-existing components we should just reuse. Enrichment is already a unified crawl phase and we could put the graph hydration there. If we inject that, it should have granular progress reporting like the rest of the unified crawl."
**Complements**:
- `docs/architecture/graph-hydration-inference-chain-design.md` (hydration chain DAG Stages 1–11)
- `docs/architecture/graph-pruning-compaction-health-design.md` (prune/compact/health counterbalance Stages P1–P5)

---

## 1. JOB 1 — Infrastructure Inventory

### 1.1 Crawl Phase/Step Structure

**Source of truth**: `CrawlPipelineStepRegistry.java`
`kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/CrawlPipelineStepRegistry.java:57`

The canonical ordered step list (`ALL_STEPS`, line 57–84):

| stepId | displayName | stepType | hardDependsOn | foundational | archivable | chunkConsumer |
|---|---|---|---|---|---|---|
| `LOADING` | Source Loading | IO | {} | **true** | false | false |
| `DISCOVERING` | Source Discovery | IO | {LOADING} | **true** | false | false |
| `CONVERTING` | Text Conversion | CPU | {LOADING} | **true** | false | false |
| `PREPROCESSING` | Document Preprocessing | CPU | {CONVERTING} | false | false | false |
| `ROUTING` | Content Routing | CPU | {CONVERTING} | false | false | false |
| `GRAPH_PREP` | Rule Graph Prep | GRAPH | {ROUTING} | false | false | false |
| `CHUNKING` | Chunking | CPU | {ROUTING} | false | false | **true (producer)** |
| `GRAPH_EXTRACTION` | Graph Extraction | LLM | {CHUNKING} | false | **true** | **true (consumer)** |
| `SURFACING` | Crawl Surface | GRAPH | {CHUNKING} | false | false | false |
| `ENTITY_RESOLUTION` | Entity Resolution | GRAPH | {GRAPH_EXTRACTION} | false | **true** | false |
| `EDGE_COMPUTATION` | Graph Edge Cleanup | GRAPH | {ENTITY_RESOLUTION} | false | **true** | false |
| `VECTOR_INDEXING` | Embedding & Vector Index | EMBEDDING | {CHUNKING} | false | **true** | **true (consumer)** |
| `ENRICHMENT` | Post-Crawl Enrichment | ENRICHMENT | **{}** (none) | false | **false** | false |

**ENRICHMENT position (registry line 82–83)**:
- Last step; no declared `hardDependsOn` (empty set)
- `archivable = false` — cannot be archived; is always at the tail of every run
- `foundational = false` — can be skipped
- stepType = `"ENRICHMENT"` (unique type, not shared with any other step)
- Currently: registered as a progress slot in `PipelineStepTracker` (initialized at line 60 of `PipelineStepTracker.java`) and assigned progress weight `99%` in `estimateProgressForPhase` (`UnifiedCrawlGraphServiceImpl.java:2061`) — **no execution block exists today**

**`CrawlStepPlan` selection mechanism** (`CrawlStepPlan.java:82`):
- **Explicit mode** (triggered when either `enabledSteps` or `archivedSteps` is non-empty in `UnifiedCrawlRequest`): only listed steps run; unlisted → SKIP. Transitive closure of hard dependencies is computed (`cascadeNonRun`, line 137). FOOTGUN: because `ENRICHMENT` has `hardDependsOn = {}`, it will NOT be pulled in transitively. It must be listed explicitly in `enabledSteps` if explicit mode is active.
- **Legacy mode** (both lists empty): all steps run unless individually toggled off; ENRICHMENT would run by default.

**`CrawlStepPlan.validate()`** (line 146): throws if a foundational step is not RUN, or a chunkConsumerOnly step runs without CHUNKING. ENRICHMENT is neither, so no constraint applies.

### 1.2 Existing ENRICHMENT-Phase Components to Reuse

The following components already implement the first two hydration stages (S2: Entity Resolution, Partial S4: Rule Derivation) inside the crawl pipeline. They run as separate named steps (ENTITY_RESOLUTION, EDGE_COMPUTATION) today. The ENRICHMENT step currently has no executor — it is the natural receiver for the remaining hydration stages.

| Stage in Hydration DAG | Existing Component | Current Crawl Step | Path:Line |
|---|---|---|---|
| **S2a: Pre-persist entity dedup** | `EntityResolutionService` | inline in GRAPH_EXTRACTION | `kompile-knowledge-graph/.../resolution/EntityResolutionService.java:70` |
| **S2b: Post-persist entity merge** | `GraphCompactionService.compact()` | `ENTITY_RESOLUTION` step | `kompile-knowledge-graph/.../resolution/GraphCompactionService.java:424` |
| **S2c: GTIN identity nodes** | `BarcodeIdentityGraphService.materialize()` | post-ENTITY_RESOLUTION | `kompile-knowledge-graph/.../resolution/IdentityGraphService.java:195` |
| **S4/S9/S10: PSL + materialization** | `IncrementalReasoningOrchestrator.runFullReground()` | NOT wired into crawl — CASCADE only | `kompile-knowledge-graph/.../reasoning/IncrementalReasoningOrchestrator.java:156` |
| **S3: Type inference** | `OwlRlReasoner`, `TypeHierarchy` | NOT wired into crawl | `kompile-graph-reasoning/.../mebn/type/owl/OwlRlReasoner.java:71` |
| **S5: Link prediction** | `LinkPredictor`, `RotatELearner` | NOT wired into crawl | `kompile-graph-reasoning/.../embedding/learn/LinkPredictor.java:44` |
| **S7: Causal enrichment** | `TemporalAttributionService` | NOT wired into crawl | `kompile-graph-reasoning/.../attribution/TemporalAttributionService.java` |
| **S8: Temporal enrichment** | `TemporalView`, `AllenRelation` | NOT wired into crawl | `kompile-graph-reasoning/.../model/TemporalView.java:57` |
| **S9: TMS** | `ContradictionDetector` (graph-reasoning), `BeliefReviser` | NOT wired into crawl | `kompile-graph-reasoning/.../tms/ContradictionDetector.java:36` |
| **P1: Inferred edge retraction** | `InferredFactGraphPruner` (NEW per prune-design §3.7) | NOT wired | new component |
| **P2: Post-hydration compaction** | `GraphCompactionService.compact()` | health-gated second pass | reuse existing |
| **PH: Health snapshot** | `GraphHealthService.persistSnapshot()` | NOT wired into crawl | `kompile-knowledge-graph/.../maintenance/GraphHealthService.java:170` |

**Key observation**: `ENTITY_RESOLUTION` and `EDGE_COMPUTATION` already ARE hydration stages — they implement S2b and the edge cleanup that S4 depends on. They must remain as their own named crawl steps (they already have granular progress). The ENRICHMENT step picks up AFTER them, running S3→S4→S5→S7→S8→S9→S10 + P1→P2→P3→P4→P5→PH.

### 1.3 Progress Reporting Infrastructure

**`CrawlProgressEvent`** (`kompile-app-core/.../crawl/graph/CrawlProgressEvent.java`):
- Extends `ApplicationEvent`; fields: `jobId (String)`, `progressSnapshot (Object)`, `eventType (EventType)`, `message (String)`
- `EventType` enum: `STARTED`, `PROGRESS`, `SOURCE_COMPLETE`, `PHASE_CHANGE`, `ERROR`, `COMPLETED`, `CANCELLED`

**`publishProgressEvent` method** (`UnifiedCrawlGraphServiceImpl.java:1930`):
```java
private void publishProgressEvent(UnifiedCrawlJob job, EventType eventType, String message) {
    eventPublisher.publishEvent(new CrawlProgressEvent(
        this, job.getJobId(), job.toProgressSnapshot(), eventType, message));
}
```

**`updateProgress` method** (`UnifiedCrawlGraphServiceImpl.java:1905`):
- Sets `job.getCurrentPhase()`, updates `job.getProgressPercent()` via `Math.max` accumulator, calls `updateMemorySnapshot`
- Throttled at 250 ms (`PROGRESS_EVENT_INTERVAL_NANOS = 250_000_000L`, line ~1998) — only publishes if `(now - lastProgressEventNanos) >= PROGRESS_EVENT_INTERVAL_NANOS`
- On tick: calls `recordEvent(job, phase, "INFO", message, details)` + `updatePipelineStepFromCounters(job, phase, message, details)` + `publishProgressEvent`

**`PipelineStepTracker` methods** (`PipelineStepTracker.java`):
- `updatePipelineStep(job, stepId, status, completedItems, totalItems, failedItems, skippedItems, deferredItems, processingItems, currentItem, message)` (line 174) — full 11-arg update
- `completePipelineStep(job, stepId, totalItems, message)` (line 240) — sets status=COMPLETED, percent=100
- `incrementPipelineStep(job, stepId, delta, failedDelta, message)` (line 272) — addAndGet on counters
- `skipPipelineStep(job, stepId, reason)` (line 258)

**`UnifiedCrawlJob.ProgressSnapshot`** (inner class, line 1089): immutable snapshot with all job counters + `List<PipelineStepSnapshot> pipelineSteps` (line 1140); captured by `job.toProgressSnapshot()` (line 961) at event publish time.

**SSE delivery** (`CrawlProgressSseController.java`):
- `GET /api/crawl-events/stream` (line 105) — global SseEmitter; `GET /api/crawl-events/stream/{jobId}` (line 119) — per-job
- `@EventListener onCrawlProgressEvent(CrawlProgressEvent)` (line 137) serializes via `buildPayload(event)` → JSON → SSE
- `@Scheduled(fixedDelay = 15_000)` heartbeat (line 160)
- On new connection: immediately pushes current snapshot for all active jobs (line 217–244)

**Existing per-step progress pattern** (from ENTITY_RESOLUTION block, `UnifiedCrawlGraphServiceImpl.java:1468–1543`):
```java
// Step start
updatePipelineStep(job, "ENTITY_RESOLUTION", RUNNING, 0, 1, 0, 0, 0, 0, null, "Running entity resolution");
// Per-item callback
progress -> recordEntityResolutionProgress(job, progress)
    // internally calls updatePipelineStep with processed/total counts
    // and phase percent = 72 + min(8, processed*8/total)
// Step complete
completePipelineStep(job, "ENTITY_RESOLUTION", 1, "N merge(s), N final entities");
// On cancel / exception
skipPipelineStep or failPipelineStep
```

**Phase progress weights** (`estimateProgressForPhase`, `UnifiedCrawlGraphServiceImpl.java:2025–2063`):

| Phase | Progress % |
|---|---|
| ENTITY_RESOLUTION | 72–80 |
| EDGE_COMPUTATION | 82 |
| EMBEDDING/VECTOR_INDEXING | 85–99 |
| ENRICHMENT | **99** (current placeholder — needs expansion) |

**Distributed crawl**: `DistributedCrawlAggregator.aggregate()` prefixes per-worker step IDs as `w{idx}:ENRICHMENT_HYDRATION_RESOLUTION`, etc. and sums/maxes counters before publishing one aggregate `CrawlProgressEvent` with jobId `"distributed-<sessionId>"`. The SSE controller and step monitor consume it unchanged — the per-hydration-stage sub-steps will appear as `[W0] Hydration: Type Inference`, etc. in the distributed view.

---

## 2. JOB 2 — Design and Decision

### 2.1 Injection Point: AUGMENT, Not Replace

The ENRICHMENT step **augments** the existing crawl pipeline — it does NOT replace ENTITY_RESOLUTION or EDGE_COMPUTATION. The natural ownership is:

```
...GRAPH_EXTRACTION → ENTITY_RESOLUTION → EDGE_COMPUTATION → ENRICHMENT (new) → VECTOR_INDEXING...
```

Wait: VECTOR_INDEXING has `hardDependsOn = {CHUNKING}` (not EDGE_COMPUTATION), so it runs in parallel with ENTITY_RESOLUTION and EDGE_COMPUTATION in practice. ENRICHMENT has `hardDependsOn = {}`, so it runs last by convention (its position in `ALL_STEPS` is the last entry). ENRICHMENT should **logically depend on** ENTITY_RESOLUTION and EDGE_COMPUTATION because hydration input is the compacted, edge-cleaned graph. This means `hardDependsOn` should be updated to `{ENTITY_RESOLUTION, EDGE_COMPUTATION}` in the registry.

**Design decision: AUGMENT**. The existing ENTITY_RESOLUTION and EDGE_COMPUTATION steps cover S2b and edge cleanup. ENRICHMENT covers S3 (Type Inference), S4 (Rule Derivation via PSL), S5 (Link Prediction), S6 (Probabilistic Fusion), S7 (Causal Enrichment), S8 (Temporal Enrichment), S9 (TMS/Contradiction), S10 (Materialization), P1 (Inferred Edge Retraction), P2 (Post-hydration Compaction), P3 (Confidence Pruning), P4 (Orphan GC), P5 (Component Sweep), PH (Health Snapshot).

**What ENRICHMENT is NOT**: It does not re-run `GraphCompactionService` from scratch (that is ENTITY_RESOLUTION). It picks up a compacted graph and runs the inference stages that ENTITY_RESOLUTION and EDGE_COMPUTATION do not cover.

### 2.2 Hydration Stage → Sub-Step Mapping

ENRICHMENT runs as **one crawl step** but emits **N granular sub-step progress updates** within that step. The `PipelineStepTracker` tracks one `PipelineStepProgress` for `"ENRICHMENT"` with `totalItems = N_stages` and `completedItems` incrementing as each hydration stage completes. Per-stage counts flow into `message` and `currentItem` fields of `updatePipelineStep`.

The hydration sub-stages map onto concrete components as follows:

| Sub-step ID | displayName | Component Reuse | New? |
|---|---|---|---|
| `HYDRATION_TYPE_INFERENCE` | Hydration: Type Inference | `OwlRlReasoner.reason()` + `TypeHierarchy.fromGraph()` | WIRE (component exists) |
| `HYDRATION_RULE_DERIVATION` | Hydration: Rule Derivation | `FolInferenceService` + `HlMrfMapInference.solve()` + `EntailmentEngine` | WIRE (component exists) |
| `HYDRATION_LINK_PREDICTION` | Hydration: Link Prediction | `RotatELearner.train()` + `LinkPredictor.predictTails()` + `EmbeddingPslEvidence` | WIRE (components exist); training is expensive — gated by `HydrationConfig.trainEmbeddings` |
| `HYDRATION_PROB_FUSION` | Hydration: Probabilistic Fusion | `MebnInferenceService` + noisy-OR fusion | WIRE (targeted subgraph only, per OQ-2 in hydration design) |
| `HYDRATION_CAUSAL` | Hydration: Causal Enrichment | `TemporalAttributionService.attribute()` | WIRE (component exists) |
| `HYDRATION_TEMPORAL` | Hydration: Temporal Enrichment | `TemporalView` + `AllenRelation.compute()` | WIRE (components exist) |
| `HYDRATION_TMS` | Hydration: Contradiction/TMS | `ContradictionDetector` (graph-reasoning) + `BeliefReviser.retract()` | WIRE (component exists); NOTE: use graph-reasoning version, not JPA maintenance version |
| `HYDRATION_MATERIALIZE` | Hydration: Materialization | `InferredFactGraphMaterializer.materialize()` + `InferredFactStore` | WIRE (component exists) |
| `HYDRATION_PRUNE_RETRACTED` | Hydration: Prune Retracted Edges | `InferredFactGraphPruner.pruneRetracted()` | NEW (per prune-design §3.7) |
| `HYDRATION_COMPACT` | Hydration: Health-Gated Compaction | `GraphCompactionService.compact()` (second pass, health-gated) | WIRE |
| `HYDRATION_CONFIDENCE_PRUNE` | Hydration: Confidence Pruning | `ConfidencePruner.execute()` | WIRE |
| `HYDRATION_ORPHAN_GC` | Hydration: Orphan GC | `OrphanPruner.executeAllLevels()` (extended per prune-design §3.9) | EXTEND |
| `HYDRATION_COMPONENT_SWEEP` | Hydration: Component Sweep | `ComponentPruner.execute()` | WIRE |
| `HYDRATION_HEALTH_SNAPSHOT` | Hydration: Health Snapshot | `GraphHealthService.persistSnapshot()` | WIRE |

**Existing reuse summary**: 12 of 14 sub-stages reuse existing components with no new code in the component itself — only wiring. 1 sub-stage (`HYDRATION_PRUNE_RETRACTED`) requires a new class (`InferredFactGraphPruner`). 1 sub-stage (`HYDRATION_ORPHAN_GC`) requires extending `OrphanPruner` to handle all NodeLevels.

### 2.3 CrawlStepPlan Registration

Three changes to `CrawlPipelineStepRegistry.java` (line 82–83):

1. **Update `hardDependsOn`** for ENRICHMENT: `Set.of("ENTITY_RESOLUTION", "EDGE_COMPUTATION")` so the step plan validator enforces correct ordering and transitive closure in explicit mode includes ENRICHMENT only when its dependencies are RUN.

2. **FOOTGUN MITIGATION**: Because `ENRICHMENT` previously had no hard deps, callers using explicit `enabledSteps` who want hydration must add `"ENRICHMENT"` to `enabledSteps` (and its new deps: `"ENTITY_RESOLUTION"`, `"EDGE_COMPUTATION"`, `"GRAPH_EXTRACTION"`, `"CHUNKING"`, `"ROUTING"`, `"CONVERTING"`, `"LOADING"`, `"DISCOVERING"` — the transitive closure). The existing `cascadeNonRun` logic in `CrawlStepPlan.from()` (line 137) handles this automatically once the deps are declared. Document this in code comments.

3. **Consider `archivable = true`** for ENRICHMENT: today it is false, preventing it from being archived (deferred to disk). If hydration produces `InferredFact` data, having it archivable would allow crawls to ARCHIVE the enrichment phase and replay it later without re-crawling. Recommended: set `archivable = true` in a follow-on, not Phase 1.

### 2.4 Granular Progress Design

**Pattern**: one `PipelineStepProgress` for `"ENRICHMENT"` (totalItems = 14 sub-stages for full hydration, fewer if some are health-gated or config-skipped). Each sub-stage:

```java
// Sub-stage start
updatePipelineStep(job, "ENRICHMENT", RUNNING,
    completedSubStages, totalSubStages, 0, 0, 0, 1,
    "HYDRATION_TYPE_INFERENCE", "Running type inference (OWL RL)");
publishProgressEvent(job, PROGRESS, "Hydration: Type Inference — 0 types derived");

// Per-item progress within a sub-stage (e.g., per-entity-type block in OwlRlReasoner)
// Use the existing throttle guard: only publish if (now - lastProgressEventNanos) >= 250ms
updatePipelineStep(job, "ENRICHMENT", RUNNING,
    completedSubStages, totalSubStages, 0, 0, 0, 1,
    "HYDRATION_TYPE_INFERENCE", String.format("Type Inference: %d types derived, %d inconsistencies", derived, incons));

// Sub-stage complete
incrementPipelineStep(job, "ENRICHMENT", 1, 0, "Type Inference complete: N types derived");
```

**Sub-stage-level counts** surface as the `message` and `currentItem` fields in `PipelineStepSnapshot` and propagate via `ProgressSnapshot` → `CrawlProgressEvent` → SSE → frontend step-monitor panel. Specific metrics per sub-stage:

| Sub-stage | Progress Metric |
|---|---|
| `HYDRATION_TYPE_INFERENCE` | `typesInferred`, `inconsistenciesFound` |
| `HYDRATION_RULE_DERIVATION` | `factsGrounded`, `atomsConverged`, `iterationsRun` |
| `HYDRATION_LINK_PREDICTION` | `predictedLinks`, `edgesAdded` (above θ_derive) |
| `HYDRATION_PROB_FUSION` | `signalsFused`, `calibrationApplied` |
| `HYDRATION_CAUSAL` | `causalChainsFound`, `candidateNodesCreated` |
| `HYDRATION_TEMPORAL` | `temporalEdgesAdded`, `allenRelationsComputed` |
| `HYDRATION_TMS` | `contradictionsDetected`, `factsRetracted` |
| `HYDRATION_MATERIALIZE` | `edgesCreated`, `attributesSet`, `skipped` |
| `HYDRATION_PRUNE_RETRACTED` | `edgesDeleted` (retracted INFERRED edges) |
| `HYDRATION_COMPACT` | `mergesPerformed` (or `skipped: health OK`) |
| `HYDRATION_CONFIDENCE_PRUNE` | `nodesPruned`, `edgesPruned` |
| `HYDRATION_ORPHAN_GC` | `orphansRemoved` |
| `HYDRATION_COMPONENT_SWEEP` | `componentsRemoved` |
| `HYDRATION_HEALTH_SNAPSHOT` | `healthDelta` (orphanRate Δ, conformanceScore Δ) |

**Phase weight update**: `estimateProgressForPhase("ENRICHMENT", job)` should return a value proportional to completed sub-stages:
```
enrichmentProgress = 82 + min(17, (completedSubStages * 17) / totalSubStages)
// 82 = after EDGE_COMPUTATION; 99 = all enrichment done
```
This keeps ENRICHMENT in the 82–99% band, consistent with the existing progress map.

**Throttle**: same 250 ms throttle as all other steps — the ENRICHMENT executor calls `updateProgress(job, "ENRICHMENT", ...)` which already applies the throttle guard at `UnifiedCrawlGraphServiceImpl.java:1918`. Sub-stage progress is emitted via the same `publishProgressEvent` call at line 1919.

**Distributed crawl**: each worker's ENRICHMENT step progresses independently. `DistributedCrawlAggregator.tagStep()` (line 166–187) prefixes sub-step display names with `[W{idx}]`. The aggregate view shows all workers' ENRICHMENT stages side by side in the step monitor. No additional aggregation logic is needed — the existing averaging of `progressPercent` across workers handles it.

### 2.5 Batch vs Incremental — Shared Stage Implementations

The same hydration stage implementations are used in BOTH modes:

| Mode | Trigger | Orchestrator | Stage impls |
|---|---|---|---|
| **BATCH** (new, this doc) | ENRICHMENT crawl step after EDGE_COMPUTATION | `GraphHydrationPipeline.run(graph, config)` | same `HydrationStage` implementations |
| **INCREMENTAL** (cascade, existing) | `GroundingCascadeHook.onChangesetCompleted()` | `IncrementalReasoningOrchestrator.runFullReground()` → (future) `GraphHydrationPipeline.runIncremental()` | same stage implementations, delta-scoped |

**Shared contract**: `HydrationStage.execute(HydrationContext)` (per `graph-hydration-inference-chain-design.md §3.2`) is the common SPI. Each stage is stateless and idempotent. The BATCH invocation from ENRICHMENT passes `HydrationConfig` with `trainEmbeddings = false` (link prediction training is expensive; prediction-only is fine in a post-crawl enrichment). The CASCADE invocation uses the same config.

**Progress reporting in cascade mode**: the cascade (`GroundingCascadeHook`) does not have access to `UnifiedCrawlJob` (it fires from Spring events, not within a crawl execution). Cascade progress is currently not reported via `CrawlProgressEvent` — it fires asynchronously after the crawl SSE is already done. This is correct and unchanged: the BATCH ENRICHMENT step is the crawl-time hydration; the cascade is the event-time update. No `CrawlProgressEvent` is emitted during cascade execution.

### 2.6 Execution Wiring — Where to Put the ENRICHMENT Executor

`UnifiedCrawlGraphServiceImpl` currently has no ENRICHMENT execution block. The implementation location follows the exact same pattern as the existing ENTITY_RESOLUTION block (lines 1468–1543):

```java
// In UnifiedCrawlGraphServiceImpl, after EDGE_COMPUTATION block (line ~1645):
if (stepPlan.isRun("ENRICHMENT") && graphHydrationPipeline != null) {
    updatePipelineStep(job, "ENRICHMENT", RUNNING, 0, TOTAL_HYDRATION_STAGES, ...);
    Long factSheetId = crawlRequest.getFactSheetId();

    // Sub-stage callbacks passed to GraphHydrationPipeline
    HydrationConfig config = buildHydrationConfig(crawlRequest);
    HydrationProgressCallback progressCb = (subStageId, completedStages, totalStages, message) ->
        recordHydrationSubStageProgress(job, subStageId, completedStages, totalStages, message);

    HydrationResult result = graphHydrationPipeline.run(
        graphToReasoningGraphAdapter.adapt(factSheetId),
        config,
        progressCb);

    completePipelineStep(job, "ENRICHMENT", result.stages().size(),
        String.format("Hydration complete: %d facts derived, %d pruned, health Δ=%.2f",
            result.derivedFacts().size(), result.pruneResult().edgesRemoved(),
            result.healthDelta()));
} else if (!stepPlan.isRun("ENRICHMENT")) {
    skipPipelineStep(job, "ENRICHMENT", "Hydration enrichment skipped or unavailable");
}
```

**`graphHydrationPipeline`** is injected as `@Autowired @Nullable GraphHydrationPipeline graphHydrationPipeline` — null-safe so crawls work before the pipeline is implemented (fallback to skip).

**`HydrationProgressCallback`** is a new functional interface (one method: `onSubStageProgress(String subStageId, int completed, int total, String message)`) used by `GraphHydrationPipeline` to report stage-level progress back to the crawl job without coupling the infra-free library to Spring.

### 2.7 `UnifiedCrawlRequest` Configuration

No new field is strictly required for Phase 1 — the ENRICHMENT step is controlled by the existing `enabledSteps`/`archivedSteps` mechanism. However, two optional additions improve UX:

1. **`hydrateConfig` field** (optional `HydrationConfig` override in the request): allows the REST caller to pass per-crawl hydration thresholds (`thetaDerive`, `trainEmbeddings`, `enabledStageIds`). Default = server-configured global `HydrationConfig` from `graph-extraction-config.json`.

2. **`skipHydration` flag** (boolean, default false): convenience shortcut equivalent to removing `"ENRICHMENT"` from `enabledSteps`. Avoids forcing callers to know the step ID.

---

## 3. Phased Build Plan

### Phase 1 (First Buildable Slice): Wire Existing Stages + Progress

**Goal**: ENRICHMENT step runs in the crawl, executing S2b-reuse (compaction result handoff) → S3 (Type Inference) → S4 (Rule Derivation via PSL) → S9 (TMS) → S10 (Materialization) → PH (Health Snapshot), with granular progress.

**Deliverables** (ordered by dependency):

1. **`HydrationStage` SPI + `HydrationContext` + `HydrationConfig` + `HydrationResult` + `HydrationProgressCallback`**
   Module: `kompile-graph-reasoning/.../hydration/`
   Per the interface sketch in `graph-hydration-inference-chain-design.md §3.2`, plus `HydrationProgressCallback` functional interface.

2. **`GraphHydrationPipeline.run(ReasoningGraph, HydrationConfig, HydrationProgressCallback)`**
   Module: `kompile-graph-reasoning` (infra-free)
   Runs stages in topological order; calls `callback.onSubStageProgress()` after each stage.

3. **Stage adapters in `kompile-knowledge-graph`** wiring existing beans:
   - `TypeInferenceHydrationStage` → `OwlRlReasoner` + `TypeHierarchy`
   - `RuleDerivationHydrationStage` → `FolInferenceService` + `HlMrfMapInference` + `EntailmentEngine`
   - `TmsHydrationStage` → `ContradictionDetector` (graph-reasoning) + `BeliefReviser` + `JustificationIndex`
   - `MaterializationHydrationStage` → `InferredFactGraphMaterializer` + `InferredFactStore`
   - `HealthSnapshotHydrationStage` → `GraphHealthService.persistSnapshot()`

4. **`InferredFactGraphPruner`** (new, `kompile-knowledge-graph/.../reasoning/`):
   `pruneRetracted(factSheetId, runId, retractedAtomKeys)` — removes `EdgeProvenance.INFERRED` edges for retracted atoms; delegates to `KnowledgeGraphService.pruneEdges()`.
   `pruneByConfidence(factSheetId, threshold, dryRun)` — health-gated confidence prune.

5. **`IncrementalReasoningOrchestrator.runFullReground()` return type change**:
   Returns `FullRegroundResult { int versionsWritten; Set<String> retractedAtomKeys; String runId; }` instead of `void`.
   Required so `InferredFactGraphPruner.pruneRetracted()` gets the retracted keys from Stage 9 (noted as OQ-P1 in prune design).

6. **ENRICHMENT execution block in `UnifiedCrawlGraphServiceImpl`** (after line ~1645):
   Pattern: matches ENTITY_RESOLUTION block; `@Nullable` guard on `graphHydrationPipeline`; `recordHydrationSubStageProgress(job, ...)` helper method following the exact same pattern as `recordEntityResolutionProgress` (line 1968–1996).

7. **`CrawlPipelineStepRegistry` update**:
   Change ENRICHMENT `hardDependsOn` from `Set.of()` to `Set.of("ENTITY_RESOLUTION", "EDGE_COMPUTATION")`.

8. **`estimateProgressForPhase` update** (`UnifiedCrawlGraphServiceImpl.java:2061`):
   Replace the flat `99` return for ENRICHMENT with a proportional formula based on `completedSubStages`.

9. **`GraphReasoningGraphAdapter`** (new, `kompile-knowledge-graph` or `kompile-app-core`):
   Adapts the live `@Primary` store (accessed via `KnowledgeGraphService`) into `ReasoningGraph` for `GraphHydrationPipeline.run()`. This is the bridge from the Spring store to the infra-free lib.
   Alternative if too heavy: `GraphToFactStoreProjector` already does graph→FactStore; the pipeline can run directly on the FactStore without a full `ReasoningGraph` adapter for Phase 1.

**Out of scope for Phase 1**: `RotatELearner` training (S5), MEBN global inference (S6), `TemporalAttributionService` causal chain (S7), temporal enrichment (S8), Platt calibration, `PruneCompactBudget` health control loop, `OrphanPruner.executeAllLevels()` extension.

### Phase 2: Link Prediction + Health-Gated Prune/Compact Loop

**Deliverables**:
- `LinkPredictionHydrationStage` wiring `RotatELearner` + `LinkPredictor` (prediction-only, no training in BATCH mode)
- `PruneCompactBudget.from(GraphHealthSnapshot, HealthSetpoints)` (per prune-design §3.5)
- Health-gated stages: `HYDRATION_COMPACT`, `HYDRATION_CONFIDENCE_PRUNE`, `HYDRATION_ORPHAN_GC`, `HYDRATION_COMPONENT_SWEEP`
- `OrphanPruner.executeAllLevels()` extension
- `HealthSetpoints` record in `graph-extraction-config.json` (hot-reloaded)

### Phase 3: Causal + Temporal Enrichment Stages

**Deliverables**:
- `CausalHydrationStage` wiring `TemporalAttributionService` (per hydration-design §9 Phase 3)
- `TemporalEnrichmentStage` wiring `TemporalView` + `AllenRelation`
- `CANDIDATE_TTL` expiry via `CandidatePruner` (per prune-design §4 Phase 3)

### Phase 4: True Incremental Cascade Integration

**Deliverables**:
- `GraphHydrationPipeline.runIncremental(graph, deltaAtomKeys, config)` replacing `IncrementalReasoningOrchestrator.runFullReground()`
- `GroundingCascadeHook` updated to call `pipeline.runIncremental()`
- Full hysteresis state + rederivation penalty (per prune-design §4 Phase 2)

---

## 4. Open Questions

**OQ-1: `GraphReasoningGraphAdapter` vs direct FactStore**
The `GraphHydrationPipeline` operates on `ReasoningGraph` (the infra-free lib model). Adapting the live `@Primary` matrix/vector store into `ReasoningGraph` requires a new adapter. `GraphToFactStoreProjector` already does the graph→FactStore projection for PSL. For Phase 1, running PSL directly on the FactStore (bypassing `ReasoningGraph`) avoids needing the adapter. For Stages 3, 5, 7, 8 which use `ReasoningGraph` APIs, the adapter IS needed. **Decision point**: implement the adapter in Phase 1 for correctness, or defer and run Phase 1 stages via FactStore-only paths?

**OQ-2: `enabledSteps` footgun in explicit mode**
With `hardDependsOn = {ENTITY_RESOLUTION, EDGE_COMPUTATION}`, callers using explicit `enabledSteps` who want ENRICHMENT must also list all transitive deps (`ENTITY_RESOLUTION`, `EDGE_COMPUTATION`, `GRAPH_EXTRACTION`, `CHUNKING`, `ROUTING`, `CONVERTING`, `LOADING`, `DISCOVERING`). The `CrawlStepPlan.cascadeNonRun()` logic handles this automatically. Should `CrawlStepPlan.from()` add a convenience expansion where listing `ENRICHMENT` in `enabledSteps` auto-includes its full transitive dep closure? Recommended: yes, add a `expandTransitiveDeps` option (default true) to `CrawlStepPlan.from()`.

**OQ-3: `hydrateConfig` in `UnifiedCrawlRequest` vs global config**
Per-crawl `HydrationConfig` (θ_derive, enabled stages, max iterations) adds flexibility but complicates the API surface. The global `graph-extraction-config.json` already carries per-factSheet overrides via `@JsonAnySetter`. Recommendation: use the global config file for Phase 1; add per-request override in Phase 2 only if operational need arises.

**OQ-4: ENRICHMENT `archivable = false` and resumability**
Currently ENRICHMENT cannot be archived (deferred to disk for later replay). If a crawl fails during hydration (e.g., PSL solve OOM on large graph), the enrichment cannot be retried without re-running all prior steps. Setting `archivable = true` would allow the crawl resumability system (req #1 from `project_crawl_resumability.md`) to checkpoint post-extraction and resume from ENRICHMENT. **Recommendation**: set `archivable = true` in Phase 2 after the execution block is stable.

**OQ-5: `VECTOR_INDEXING` vs `ENRICHMENT` execution order**
`VECTOR_INDEXING` has `hardDependsOn = {CHUNKING}` — it runs in parallel with ENTITY_RESOLUTION and EDGE_COMPUTATION today. If `ENRICHMENT.hardDependsOn = {ENTITY_RESOLUTION, EDGE_COMPUTATION}`, and VECTOR_INDEXING can finish after ENRICHMENT starts, there is no sequencing conflict. But if ENRICHMENT's type inference modifies node type attributes that embedding indexing depends on, VECTOR_INDEXING should logically run after ENRICHMENT. **Decision**: add `VECTOR_INDEXING` to ENRICHMENT's `hardDependsOn`? Recommendation: no — embedding vectors are chunk-level, not node-type-level. No dependency needed.

---

## 5. Forks — Flag and Recommend

### Fork 1: Replace vs Augment Existing Enrichment Step

**Option A — Replace**: Remove ENTITY_RESOLUTION and EDGE_COMPUTATION as separate steps; fold them into ENRICHMENT as sub-stages H-S2b and H-EDGE. Single unified enrichment step.

**Option B — Augment (RECOMMENDED)**: Keep ENTITY_RESOLUTION and EDGE_COMPUTATION as named crawl steps with their existing granular progress. ENRICHMENT runs after them, picking up the compacted graph. This avoids regressions in the existing working steps, preserves skip-ability of each step independently, and respects the principle of minimal change to working code.

**Rationale**: ENTITY_RESOLUTION already has production-grade progress reporting (`recordEntityResolutionProgress`, 8 phases in `GraphCompactionService.notifyProgress`). Folding it into ENRICHMENT would regress this. AUGMENT is the correct choice.

### Fork 2: One Coarse ENRICHMENT Step vs N Granular Sub-Steps in the Plan

**Option A — One coarse step**: `ENRICHMENT` appears once in `CrawlStepPlan`. Granularity lives in `message`/`currentItem` fields of the single `PipelineStepProgress` object.

**Option B — N granular steps**: Add `ENRICHMENT_TYPE_INFERENCE`, `ENRICHMENT_RULE_DERIVATION`, `ENRICHMENT_TMS`, etc. as separate step IDs in `CrawlPipelineStepRegistry`. Each is independently selectable/skippable.

**Option A (RECOMMENDED)**: The step plan already has 13 entries. Adding 14 more hydration sub-steps would make explicit `enabledSteps` configuration extremely verbose and would multiply the `validate()` complexity. The user wants granular progress reporting — but granularity can live in the sub-stage counts within a single ENRICHMENT step rather than in the step plan itself. The `currentItem` and `message` fields of `PipelineStepProgress` already surface sub-stage identity in the UI's step monitor. If individual sub-stages need to be independently skippable (e.g., "skip link prediction but run type inference"), use `HydrationConfig.enabledStageIds` (per `graph-hydration-inference-chain-design.md §3.2`) rather than crawl step plan entries.

**Compromise**: For Phase 2, consider a mid-granularity split: `ENRICHMENT_DERIVE` (Stages S3–S10, the grow pass) and `ENRICHMENT_PRUNE` (Stages P1–P5, the shrink pass), each as a separate crawl step. This is the minimum that allows skipping the prune pass independently.

### Fork 3: Prune/Health Inside the Crawl ENRICHMENT vs Only Post-Crawl/Cascade

**Option A — Prune inside ENRICHMENT** (RECOMMENDED for Phase 1): The health snapshot and health-gated compaction/pruning run as sub-stages within ENRICHMENT. Every batch crawl both grows and shrinks the graph atomically.

**Option B — Prune only in cascade**: The crawl's ENRICHMENT only runs the grow stages (S3–S10). The prune/compact/health stages (P1–PH) run only in the `GroundingCascadeHook` post-crawl cascade. This makes the ENRICHMENT step lighter and faster.

**RECOMMENDATION**: Option A. The user direction says "homeostatic — DERIVE ⊕ PRUNE/COMPACT" and the prune-design explicitly states "every time derivation runs, P1–P5 run immediately afterward." Doing this in the ENRICHMENT step means the crawl's step monitor shows the full health lifecycle, and the graph is in a healthy state when the crawl completes. The health-gated flags (P2–P5 only run if health metrics exceed thresholds) keep this lightweight for healthy graphs.

---

## 6. Appendix: Component Path Reference

| Component | Path | Key Method |
|---|---|---|
| `CrawlPipelineStepRegistry` | `kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/CrawlPipelineStepRegistry.java:57` | `ALL_STEPS` (line 57); ENRICHMENT entry (line 82) |
| `CrawlStepPlan` | `kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/CrawlStepPlan.java:82` | `from(UnifiedCrawlRequest)`, `cascadeNonRun` (line 137) |
| `UnifiedCrawlJob` | `kompile-app-core/src/main/java/ai/kompile/core/crawl/graph/UnifiedCrawlJob.java:63` | `toProgressSnapshot()` (line 961), `PipelineStepProgress` (line 695) |
| `UnifiedCrawlRequest` | `kompile-app-core/src/main/java/ai/kompile/core/crawl/graph/UnifiedCrawlRequest.java:47` | `enabledSteps` (line 107), `archivedSteps` (line 115) |
| `UnifiedCrawlGraphServiceImpl` | `kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/UnifiedCrawlGraphServiceImpl.java` | `publishProgressEvent` (line 1930), `updateProgress` (line 1905), ENTITY_RESOLUTION block (line 1468), `estimateProgressForPhase` (line 2025) |
| `PipelineStepTracker` | `kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/PipelineStepTracker.java` | `updatePipelineStep` (line 174), `completePipelineStep` (line 240), `incrementPipelineStep` (line 272) |
| `CrawlProgressSseController` | `kompile-app-main/src/main/java/ai/kompile/app/web/controllers/CrawlProgressSseController.java` | `GET /api/crawl-events/stream` (line 105), `onCrawlProgressEvent` (line 137) |
| `DistributedCrawlAggregator` | `kompile-app-main/src/main/java/ai/kompile/app/services/crawl/DistributedCrawlAggregator.java` | `aggregate(session)` (line 46), `tagStep` (line 166) |
| `GraphCompactionService` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/GraphCompactionService.java:416` | `compact(Long, CompactionConfig)` (line 424), `notifyProgress` |
| `IncrementalReasoningOrchestrator` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/IncrementalReasoningOrchestrator.java:95` | `runFullReground(Long)` — return type must change to expose `retractedAtomKeys` |
| `GroundingCascadeHook` | `kompile-graph-change-tracking/src/main/java/ai/kompile/graphchangetracking/hook/GroundingCascadeHook.java:70` | `schedule(Long, String)` (line 173) |
| `InferredFactGraphMaterializer` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/InferredFactGraphMaterializer.java:51` | `materialize(Collection<InferredFact>, Long)` — write-only today |
| `GraphHealthService` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/GraphHealthService.java:93` | `computeSnapshot(Long)` (line 93), `persistSnapshot(Long)` (line 170) |
| `FolInferenceService` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/FolInferenceService.java:73` | `infer(ReasoningGraph, FolRuleSet)` |
| `OwlRlReasoner` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/mebn/type/owl/OwlRlReasoner.java:71` | `reason(OwlOntology, ReasoningGraph)` |
| `LinkPredictor` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/embedding/learn/LinkPredictor.java:44` | `predictTails(head, relation, topK)` |
| `ContradictionDetector` (graph-reasoning) | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/ContradictionDetector.java:36` | `findFactContradictions(FactStore)` — use this, NOT the JPA maintenance version |
| `BeliefReviser` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/BeliefReviser.java:34` | `retract(atomKey, factStore, index)` |
| `TemporalAttributionService` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/attribution/TemporalAttributionService.java` | `attribute(ReasoningGraph, TemporalAttributionQuery)` |
