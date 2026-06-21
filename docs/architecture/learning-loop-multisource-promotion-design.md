# Learning Loop: Multi-Source Triggers, MEBN Weights, Fusion/Contradiction, and Fact Promotion

> Companion to `learning-loop-gap-analysis.md`. Covers four additional concerns raised in design
> review: (1) whether sources-upload and channels trigger the same learning pipeline as crawls,
> (2) whether MEBN weight learning is wired in the loop, (3) how multiple differing sources are
> fused and contradictions handled, and (4) whether NELL-style fact promotion exists. For each:
> **CURRENT STATE** (file:line), **GAP**, and **DESIGN** to close it.

---

## 1. Sources-Interface Trigger Gap

### Current state

The learning / enrichment pipeline is triggered via the event chain:

```
UnifiedCrawlGraphServiceImpl.publishGraphBuildCompletedEvent(job)    [crawl-graph, line 1852]
  → GraphBuildCompletedEvent
    → GraphBuildCompletedEventIntegration.onGraphBuildCompleted()    [graph-change-tracking, all lines]
      → GraphChangesetCompletedEvent
        → GroundingCascadeHook.onChangesetCompleted()               [hook, line 123]
          → GraphEnrichmentService.enrich(factSheetId)
            → GraphHydrationOrchestrator.run()  [DERIVATION → PRUNE_COMPACT → HEALTH]
```

**Channel path:** `BaseChannelAdapter` publishes `ChannelMessageReceivedEvent`. The
`GraphUpdateChannelBridge` listens and calls `MultiAgentExtractionService.runExtraction`, which
eventually writes graph nodes, triggering a `GraphChangesetCompletedEvent`. `GroundingCascadeHook`
also has an `onChannelMessage` listener but it currently does nothing beyond logging — the
cascade is deferred to the downstream changeset event (hook, lines 153–164).

**Sources-upload path (`DocumentUploadController` / `DocumentIngestService`):**
`DocumentIngestService.processDocumentAsync()` runs the full load → convert → chunk → embed →
index pipeline. The `DocumentSourceDescriptor` is built with
`sourceId("upload_" + fileName + "_" + taskId)` (DocumentIngestService.java:722).

**Critical gap: no event is published from the upload path.** `DocumentIngestService` does not
import or call `ApplicationEventPublisher`. It has no call to `publishEvent`. After indexing
completes the pipeline returns `PipelineResult` and the method exits. The
`GraphBuildCompletedEvent` / `GraphChangesetCompletedEvent` chain is **never triggered** for
manual uploads. The `GroundingCascadeHook` is therefore **not invoked**, and neither is
`GraphHydrationOrchestrator.enrich()` — meaning enrichment (MAP inference → weight learning →
cascade) does NOT run after a document is uploaded via the sources interface.

The `ExternalSourceIngestController` (sourceId at line 1003) has the same gap.

**Common seam:** `GraphChangesetCompletedEvent` is the universal trigger. Both the crawl path
(via `GraphBuildCompletedEventIntegration`) and the channel path (via `GraphUpdateChannelBridge`)
converge on it. **The upload path is the missing link.**

### Design to close it

**Option A — Publish `GraphBuildCompletedEvent` from `DocumentIngestService`** (minimal change):
Inject `ApplicationEventPublisher` into `DocumentIngestService` (it is already a Spring `@Service`).
After the pipeline's `pipelineResult` is confirmed successful (around line 1150), publish:

```java
eventPublisher.publishEvent(new GraphBuildCompletedEvent(
    this,
    "upload:" + taskId,
    pipelineResult.documentsProcessed(),
    0,                          // edges: unknown at this stage
    factSheetId,                // must be threaded from the upload request
    null
));
```

`GraphBuildCompletedEventIntegration` translates this into `GraphChangesetCompletedEvent`, which
`GroundingCascadeHook` already handles. The cascade runs asynchronously after the upload returns.

**The factSheetId problem:** `DocumentUploadController` currently does not require a
`factSheetId` on upload. A `?factSheetId=` query parameter must be threaded from the upload
endpoint through `processDocumentAsync` down to the event publish call. Where absent, the
event carries `factSheetId=null` and the cascade hook drops it gracefully (hook lines 125–129).

**Option B — Add a new `DocumentIngestedEvent`** that carries the `factSheetId` and have
`GraphBuildCompletedEventIntegration` also translate that. This keeps the ingest service
decoupled from crawl-graph internals.

**Provenance gap:** The upload path creates a `DocumentSourceDescriptor` with
`sourceId("upload_" + ...)` but does NOT write `GraphProvenanceKeys._source = "upload"` into
node metadata. The `GraphProvenanceKeys.crawl()` builder exists; an `upload()` builder must
be added so that upload-derived facts carry `_source=upload`, `_sourceDocumentId=<taskId>`, etc.
This makes multi-source provenance (concern 3) work correctly for uploaded files.

**Summary:** Sources-upload does NOT trigger the pipeline. The fix is one `publishEvent` call +
threading `factSheetId` + a `GraphProvenanceKeys.upload()` builder.

---

## 2. MEBN Weight Learning in the Loop

### Current state

`MebnWeightLearner` exists at:
`kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/learning/MebnWeightLearner.java`

It has a fully working `learn(MTheory, ReasoningGraph, observations, maxEpochs)` method — batch
gradient descent over noisy-OR edge strengths with `ProjectedGradientOptimizer`.

`MebnWeightPersistenceAdapter` exists at:
`kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/persistence/MebnWeightPersistenceAdapter.java`

It serializes/deserializes edge strengths to `<dataDir>/data/graph/reasoning/<fsId>/mebn-weights.json`.

**`MebnWeightLearner` has zero callers in production code.** The grep across all production Java
files (excluding tests) returns only the class's own file and doc-comments in adjacent files.
Neither `IncrementalReasoningOrchestrator`, `GraphHydrationOrchestrator`, nor `KbCorrectionService`
instantiates or calls `MebnWeightLearner.learn()`.

**`MebnWeightPersistenceAdapter` is also uncalled** from production paths; it has only its own
test.

**PSL weight learning IS partially wired:** `KbCorrectionService` instantiates
`PslWeightLearningService` and calls `weightLearner.updateOnBatch()` on human corrections
(KbCorrectionService.java:139). This mini-batch update on a correction is the only weight
learning that runs today. It is limited to PSL and only runs on human correction events, not
on bulk ingestion.

### Gap

MEBN edge-strength learning is entirely disconnected from any live trigger. The weights used
by `MebnInferenceService` are hand-initialized defaults for every fact-sheet re-ground.

### Design to close it

**Step 1 — Wire MEBN learning into `IncrementalReasoningOrchestrator.doReground()`**

After the MAP solve (step 4) produces `HlMrfMapInference.Result`, the inferred values can serve
as the observation target for `MebnWeightLearner.learn()`:

```java
// After STEP 5 (materialize inferred facts) in doReground():
if (mebnWeightLearner != null && mebnTheoryProvider != null) {
    MTheory theory = mebnTheoryProvider.theoryForFactSheet(factSheetId);
    if (theory != null) {
        Map<String, Double> observations = buildMebnObservations(factStore, result);
        MTheory fitted = mebnWeightLearner.learn(theory, graphProjector.getGraph(factSheetId), observations, 20);
        mebnWeightPersistenceAdapter.persist(factSheetId, fitted);
    }
}
```

Inject `MebnWeightLearner`, `MebnWeightPersistenceAdapter` (both `@Nullable`) into
`IncrementalReasoningOrchestrator` so tests without these beans continue to work.

**Step 2 — Load persisted weights at inference time**

`MebnInferenceService` must call `mebnWeightPersistenceAdapter.load(factSheetId, theory)` before
`infer()` so the learned strengths are used. Currently it uses hand-set defaults every run.

**Step 3 — Integrate with bulk ingestion**

`KbCorrectionService` already does PSL mini-batch on corrections. Extend it to also call
`MebnWeightLearner.learn()` (full batch, smaller epoch count) whenever a correction is applied
and a `MTheory` is available for the fact sheet.

**Step 4 — Share observations between PSL and MEBN**

Both learners need the same "observed vs. derived" pairs. Factor this into a
`LearningObservationBuilder` shared by both so corrections feed both weight stores coherently.

**Summary:** MEBN weight learning is NOT called by anything. Two `@Nullable` injections into
`IncrementalReasoningOrchestrator` + a `load()` call in `MebnInferenceService` + a `persist()`
call after `doReground` closes the loop.

---

## 3. Multi-Source Reasoning: Fusion, Contradiction, Provenance

### Current state

**Per-source provenance:** `GraphProvenanceKeys` defines `_source`, `_sourceDocumentId`,
`_sourceChunkId`, `_crawlRunId`, `_extractionModel` as metadataJson keys on `GraphNode`
(GraphProvenanceKeys.java:34–50). The `describe()` helper and `crawl()` builder exist.
Channel-sourced facts store `_source=channel:slack` in tests
(GraphProvenanceKeysTest.java:31, MultiAgentExtractionServiceTest.java:357). This provenance
travels with the graph on clone (metadata seam).

**Gap — no `upload()` provenance builder:** Only `GraphProvenanceKeys.crawl()` exists.
Upload-derived nodes never record `_source`, so there is no way to filter or weigh them differently.

**Multi-source fusion — `Opinion.cumulativeFuse()` exists but is UNUSED in materialization:**
`Opinion` has a correct Jøsang cumulative fusion implementation (Opinion.java:135–155). It is
tested but has **zero production callers** in the graph or knowledge-base modules. When the same
claim appears in two sources, `InferredFactGraphMaterializer.addRelation()` calls
`graphService.createEdgeWithMetadata()` which is a simple write. There is no lookup of an
existing inferred edge with the same atom key to fuse the two source opinions. The result is
**last-write-wins**: the edge from the second crawl silently overwrites or coexists alongside the
first without any confidence accumulation.

**Cross-source contradiction detection:** `ContradictionDetector` (knowledge-graph module) does
detect multiple edges of differing types on the same node pair
(ContradictionDetector.java:86–113). It is called from `IncrementalReasoningOrchestrator.doReground()`
at STEP 7 (orchestrator lines 334–340). However, it only **logs** contradictions; it does not
halt the cascade, does not emit a queryable event, and does not route back through
`BeliefReviser`. The TMS-layer `BeliefReviser` in `kompile-graph-reasoning` is a pure-library
class with no production caller (it is referenced only in `PruneCompactOrchestrator` comments).

**`BeliefReviser` (`kompile-graph-reasoning/tms/`):** Exists and is tested
(TruthMaintenanceTest.java), but no production code instantiates it to retract beliefs based
on contradiction.

### Design to close it

**A — `GraphProvenanceKeys.upload()` builder (trivial)**

```java
public static Map<String, Object> upload(String taskId, String fileName) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(SOURCE, "upload");
    m.put(SOURCE_DOCUMENT_ID, taskId);
    if (fileName != null) m.put(SOURCE_CHUNK_ID, fileName);
    return m;
}
```

Write this into every node materialized by the upload pipeline (or by the graph extraction that
fires from the upload trigger, once concern 1 is wired).

**B — Opinion fusion in `InferredFactGraphMaterializer`**

Replace the blind `createEdgeWithMetadata` call with a fuse-or-create pattern:

```java
// In StoreSink.addRelation():
Optional<GraphEdge> existing = graphService.findInferredEdge(from.getNodeId(), to.getNodeId(), atom.predicate(), factSheetId);
if (existing.isPresent()) {
    Opinion priorOpinion = Opinion.fromSoftTruth(existing.get().getConfidence(), /* evidenceCount */ 1);
    Opinion newOpinion   = Opinion.fromSoftTruth(fact.value(), 1);
    Opinion fused        = priorOpinion.cumulativeFuse(newOpinion);
    graphService.updateEdgeConfidence(existing.get().getEdgeId(), fused.expectation());
    // Append sourceDocumentId to edge metaJson's provenance list
} else {
    graphService.createEdgeWithMetadata(...);
}
```

This requires a `findInferredEdge()` method on `KnowledgeGraphService` (currently absent;
the graph service has `getEdgesInFactSheet()` returning all edges but no predicate+node-pair
index). Add it as a default-impl method using the existing edge list filtered in-memory, with a
follow-up index optimization.

**C — Cross-source contradiction surface**

Extend `IncrementalReasoningOrchestrator.doReground()` STEP 7 to:
1. Emit a `ContradictionDetectedEvent` carrying the contradiction list and factSheetId.
2. Add a `GraphChangeTrackingAutoConfiguration` `@Bean ContradictionEventBroadcaster` that
   listens and (a) persists contradictions to a per-fact-sheet JSONL under
   `data/graph/contradictions/<fsId>/`, (b) fires a WebSocket notification to the frontend.
3. Add a `/api/graph/{factSheetId}/contradictions` endpoint backed by this log so the Graphs UI
   can surface cross-source contradictions.

**D — TMS retraction: `BeliefReviser` integration (phase 2)**

Wire `BeliefReviser` into `IncrementalReasoningOrchestrator` for ESTABLISHED-confidence facts
only: when a contradiction is detected and both edges exist, call
`BeliefReviser.retractLeastJustified()` using the `JustificationIndex` already built in STEP 6.
Mark the retracted edge as stale via `knowledgeGraphService.pruneEdges()`. This is deferred
because it requires the `@Primary` matrix store to support edge stale-flagging reliably.

**Priority:** C before D; B is the most impactful because it turns corroboration from two
independent sources into a lower-uncertainty Opinion rather than a coin-flip.

**Summary:** Multi-source fusion is last-write-wins today. Opinion.cumulativeFuse() is
implemented but never called in materialization. ContradictionDetector runs but logs only.
BeliefReviser has no production callers.

---

## 4. Fact Promotion + Downstream Propagation (MISSING)

### Current state

**`StrengthBand` and `StrengthLayer` exist:**
- `StrengthBand` (kompile-graph-reasoning, Opinion.java:236–243): SUPPRESSED / SPECULATIVE /
  PROBABLE / HIGH / ESTABLISHED, derived by `Opinion.projectBand()`.
- `StrengthLayer` (kompile-knowledge-graph, audit/): parallel enum with scalar cutoffs (0.20 /
  0.50 / 0.85).

**Neither is evaluated dynamically.** `StrengthBand` is read from an `InferredFact`'s confidence
scalar and surfaced in `VerifyElementController` response (lines 87+) and process-mining
suggestion conversion (`ProcessTreeToSuggestion.java:144`). But the band is computed once at
read time from the current confidence value — it is not stored as a durable field that transitions
as evidence accrues.

**There is no candidate→accepted promotion mechanism.** A fact's `InferredFact.value()` changes
on each MAP solve (IncrementalReasoningOrchestrator.doReground() writes a new version only when
`|Δvalue| > VERSION_EPSILON`). The version counter increments but there is no code that:
- Counts how many independent sources corroborate the fact
- Applies `Opinion.cumulativeFuse()` across those corroborations
- Compares the resulting `StrengthBand` against the previous band
- Fires a "band transition" event when the band crosses e.g. SPECULATIVE→PROBABLE

**Downstream effects are also missing.** Today when a fact's `InferredFact.value()` changes:
- The cascade re-ground runs (via GroundingCascadeHook) which re-solves the MAP → dependent
  inferences update via the full-reground path. This IS the incremental update of dependent
  inferences.
- PSL rule weights update only on **human correction** (KbCorrectionService line 139), not on
  autonomous confidence increases.
- MEBN priors do not update at all (concern 2 above).
- No process/rule/domain-object is notified of the band transition.
- The grounding `verify()` result for claims that depend on the promoted fact IS stale until the
  next cascade run, but cascades fire on every `GraphChangesetCompletedEvent`, so they are
  eventually consistent.

### Gap

Fact promotion is entirely missing. Confidence values accumulate if the MAP solve pushes them
up across re-grounds (e.g. more supporting evidence → higher PSL soft-truth), but:
1. There is no accumulation of *corroboration count* separate from the MAP value.
2. The StrengthBand transition is never detected.
3. No downstream component is explicitly notified when promotion occurs.

### Design

**Component: `FactPromotionTracker`** (new class in kompile-knowledge-graph)

Responsibility: compare the StrengthBand before and after each MAP solve; when the band
increases, record a PROMOTED audit event and emit a `FactPromotedEvent`.

```java
// Inside IncrementalReasoningOrchestrator.doReground(), after storing a new InferredFact version:
if (promotionTracker != null && existing.isPresent()) {
    StrengthBand oldBand = Opinion.fromSoftTruth(existing.get().value()).projectBand();
    StrengthBand newBand = Opinion.fromSoftTruth(newValue).projectBand();
    if (newBand.ordinal() > oldBand.ordinal()) {
        promotionTracker.recordPromotion(factSheetId, atomKey, oldBand, newBand, runId);
    }
}
```

`FactPromotionTracker` writes to:
1. The per-fact-sheet audit log (via `KbCorrectionService.appendDerivedAuditEvent` pattern).
2. A `FactPromotedEvent` via `ApplicationEventPublisher`.

**Downstream effects wired by `FactPromotedEvent` listeners:**

| Listener | Effect |
|---|---|
| `PslWeightUpdaterHook` (new) | When atomKey is ESTABLISHED, call `KbCorrectionService.registerProgram` with a weight-update on the program snapshot |
| `MebnWeightUpdaterHook` (new) | Call `MebnWeightLearner.learn()` with the promoted fact as a high-confidence observation |
| `CascadeRegroundHook` (existing `GroundingCascadeHook`) | Already handles the next GraphChangesetCompletedEvent; no change needed |
| `PromotionAuditBroadcaster` (new) | WebSocket message to the KB cockpit panel (future) |

**Prior feeding for MEBN/PSL:** When a fact reaches ESTABLISHED:
- Use `Opinion.fromObservedValue(fact.value())` (u=0, fully observed) to build a hard-prior
  atom in the next MAP program (`program.observe(atomKey, 1.0)` with high weight instead of the
  soft default 0.8 propagation rule). This makes PSL treat ESTABLISHED facts as near-fixed
  evidence rather than soft propagation targets.
- For MEBN: set the initial residue value in the relevant random variable's CPT to 0.9 when
  the grounded RV is ESTABLISHED (currently all residues default to 0.01).

**Corroboration count:** Add a `corroborationCount` field to `InferredFact` or store it in
`metadataJson`. Each time a new source's evidence is fused via `Opinion.cumulativeFuse()` (concern
3 above), increment the count. `FactPromotionTracker` can then also use the count to distinguish
"confidence rose because one source repeated the claim" from "confidence rose because three
independent sources agreed".

**`StrengthBand` as a durable field:** The materialized inferred edge's `metaJson` (built in
`InferredFactGraphMaterializer.buildMetaJson`) should include `"strengthBand": band.name()` so
the graph store and API can filter on it directly without recomputing the projection at every
read. Update `buildMetaJson` to write this.

### Prioritized build list for all four concerns

| Priority | Task | Files to change |
|---|---|---|
| P0 | Wire `publishEvent(new GraphBuildCompletedEvent(...))` from `DocumentIngestService` after successful pipeline completion; thread `factSheetId` from upload request | `DocumentIngestService.java`, `DocumentUploadController.java` |
| P0 | Add `GraphProvenanceKeys.upload(taskId, fileName)` builder; call it in upload graph extraction | `GraphProvenanceKeys.java`, extraction path |
| P1 | Wire `MebnWeightLearner.learn()` in `IncrementalReasoningOrchestrator.doReground()` with `@Nullable` injection; call `MebnWeightPersistenceAdapter.persist()` after | `IncrementalReasoningOrchestrator.java` |
| P1 | Call `MebnWeightPersistenceAdapter.load()` in `MebnInferenceService` before `infer()` | `MebnInferenceService.java` |
| P2 | Add `findInferredEdge()` to `KnowledgeGraphService`; fuse via `Opinion.cumulativeFuse()` in `InferredFactGraphMaterializer.StoreSink.addRelation()` | `KnowledgeGraphService`, `InferredFactGraphMaterializer.java` |
| P2 | Emit `ContradictionDetectedEvent` from STEP 7; persist to JSONL; add REST endpoint | `IncrementalReasoningOrchestrator.java`, new event + controller |
| P3 | `FactPromotionTracker`: detect StrengthBand transitions in `doReground()`; emit `FactPromotedEvent` | New class + `IncrementalReasoningOrchestrator.java` |
| P3 | `PslWeightUpdaterHook` + `MebnWeightUpdaterHook` listening on `FactPromotedEvent` | Two new hook classes |
| P3 | Persist `strengthBand` in `InferredFactGraphMaterializer.buildMetaJson()` | `InferredFactGraphMaterializer.java` |
| P4 | `BeliefReviser` integration for TMS retraction on strong contradictions | `IncrementalReasoningOrchestrator.java`, matrix-store stale-edge support |
| P4 | ESTABLISHED-fact hard-prior seam in `buildProgramFromFactStore()` | `IncrementalReasoningOrchestrator.java` |
