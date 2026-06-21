# Graph Pruning, Compaction, and Health-as-Control-Loop Design

**Status**: DESIGN-ONLY (no code, no Maven)
**Date**: 2026-06-21
**Scope**: The counterbalancing "shrink" half of the graph-hydration inference chain — provenance-gated pruning, entity compaction, and GraphHealthSnapshot-driven homeostatic control that prevents derivation from bloating the KB with low-confidence noise.
**Complements**:
- `docs/architecture/graph-hydration-inference-chain-design.md` (the "grow" half: Stages 1–11)
- `docs/architecture/incremental-cascade-reasoning-design.md` (cascade mechanics)
- `docs/architecture/reasoning-trail-explainability-design.md` (ReasoningTrail / derivation provenance)

---

## 1. Problem Statement

The hydration chain (`graph-hydration-inference-chain-design.md §2`) derives new facts at every cascade: rule-derived edges, link-predicted triples, CANDIDATE latent-cause nodes, and temporal ordering edges all accumulate across runs. Without a counterbalancing shrink pass:

1. **Noise bloat**: low-confidence derived facts (SPECULATIVE tier, 0.3–0.5) accumulate over many cascade runs, compounding into false-positive chains.
2. **Orphan proliferation**: CANDIDATE nodes from abduction (Stage 7) may never be corroborated and remain disconnected.
3. **Duplicate accumulation**: re-crawling the same source under different surface forms creates entities that `EntityResolutionService` (pre-persist) and `GraphCompactionService` (post-persist) may have missed if they were not triggered post-crawl.
4. **Stale derived facts**: after `BeliefReviser.retract` removes a supporting observed fact, downstream `INFERRED` edges in the `@Primary` store become unsupported but are never deleted.
5. **RESOLVES_TO dangling edges**: when `GraphCompactionService` merges entity B into entity A and deletes B, `IdentityGraphService`'s `RESOLVES_TO` edges to the now-deleted B node dangle silently (`IdentityGraphService.java:195` — no post-merge cleanup).

The user's direction (2026-06-21): hydration MUST be **homeostatic** — DERIVE (grow) ⊕ PRUNE/COMPACT (shrink), balanced by **GraphHealthSnapshot** as the fitness/objective signal.

---

## 2. Job 1 — Infrastructure Inventory

### 2.1 Component Table

| Component | Path:Line | What It Does | Trigger | Critical Gap |
|-----------|-----------|--------------|---------|-------------|
| `UnifiedCrawlJob` | `kompile-app-core/.../crawl/graph/UnifiedCrawlJob.java:63` | State/telemetry POJO for a crawl job; atomics + concurrent collections for thread-safe progress | Not a bean; instantiated by the crawler pipeline | No TTL/expiry on the job; `documentProgress` map and `errors` list unbounded; no hook for post-crawl compaction or health snapshot |
| `UnifiedCrawlRequest` | `kompile-app-core/.../crawl/graph/UnifiedCrawlRequest.java:47` | Request DTO for crawl configuration; `enabledSteps`/`archivedSteps` step selection; retry fields | Deserialized from REST JSON | No field to trigger post-crawl compaction or health snapshot; `archivedSteps` footgun (see MEMORY) |
| `DynamicBatchSizer` | `kompile-app-core/.../crawl/graph/DynamicBatchSizer.java:90` | AIMD batch-size controller per pipeline stage; `recordBatchResult(batchSize, elapsedMs, success, memoryPercent)` where `memoryPercent` is 0..1 fraction | Called per batch by crawler pipeline | No feedback from graph health (orphan rate spike, conformance drop) into batch sizing; no emergency-grow after memory recovery |
| `EntityResolutionService` | `kompile-knowledge-graph/.../resolution/EntityResolutionService.java:48` | Pre-persistence entity dedup on `ExtractionResult` lists; Levenshtein + alias + GTIN; `resolve(List<ExtractionResult>)` | Called during graph extraction, before `KnowledgeGraphService.createNode` | O(n²) scan with no blocking step; threshold not loaded from `graph-extraction-config.json`; different normalization from `GraphCompactionService` |
| `GraphCompactionService` | `kompile-knowledge-graph/.../resolution/GraphCompactionService.java:416` | Post-persistence entity dedup/merge: 5 phases (type-correct → block → score → components → merge); `compact(Long factSheetId, CompactionConfig)` | Manual REST / `GraphMaintenanceServiceImpl.reResolve()`; NEVER triggered automatically after crawl | `loadEntityNodes(null)` hardcapped at 100k (line 748); ThreadLocal embedding cache not shared across runs; no RESOLVES_TO edge cleanup after merge |
| `IdentityGraphService` | `kompile-knowledge-graph/.../resolution/IdentityGraphService.java:195` | Materializes `IDENTIFIER → ENTITY` (RESOLVES_TO) nodes for barcodes, emails, etc.; `materialize(Long factSheetId)` | REST / post-compaction; no automatic trigger after entity merge | RESOLVES_TO edges dangle when entity is merged/deleted by `GraphCompactionService`; no scheduled rematerialization |
| `OrphanPruner` | `kompile-knowledge-graph/.../maintenance/OrphanPruner.java:82` | Scans + soft-deletes ENTITY orphans (zero edges); grace-period hard-delete; delegates policy to `OrphanPruningPolicy` from `kompile-graph-reasoning` | `GraphMaintenanceServiceImpl.pruneOrphans()` → `MaintenanceScheduler` (3 AM daily, off by default) | Only scans `NodeLevel.ENTITY` (line 179); DOCUMENT/SNIPPET/TABLE/ATTACHMENT/IDENTIFIER orphans not cleaned; ENTITY nodes connected only to IDENTIFIER via RESOLVES_TO incorrectly classified as orphans |
| `ConfidencePruner` | `kompile-knowledge-graph/.../maintenance/ConfidencePruner.java:98` | Prunes nodes/edges below confidence threshold; corroboration heuristic (`minCorroboratingMentions`); `execute(factSheetId, ConfidencePrunePolicy, dryRun)` | `GraphMaintenanceServiceImpl.pruneByConfidence()` → `MaintenanceScheduler` | O(N) JSON parse per node for corroboration check; scalar `sourceChunkId` counts as 1 mention (insufficient for threshold ≥ 2) |
| `ComponentPruner` | `kompile-knowledge-graph/.../maintenance/ComponentPruner.java` | Prunes small disconnected ENTITY components below `minComponentSize`; delegates to `ComponentPruningPolicy` | `GraphMaintenanceServiceImpl.pruneSmallComponents()` → `MaintenanceScheduler` | `isStale` check uses string-contains on `metadataJson` (line 168: `"_stale"`, `"true"`) — can false-positive on description text |
| `ContradictionDetector` (maintenance) | `kompile-knowledge-graph/.../maintenance/ContradictionDetector.java:75` | Edge-level contradiction detection (same source-target pair, different types/labels) | `GraphMaintenanceServiceImpl.detectContradictions()` — always uses `FLAG_FOR_REVIEW` (line 321), never auto-resolves in scheduled runs | JPA-only on live store; only detects edge contradictions, not node attribute conflicts; `NEWER_WINS` uses `LocalDateTime` which is null on matrix store |
| `TtlSweepExecutor` | `kompile-knowledge-graph/.../maintenance/TtlSweepExecutor.java:58` | TTL-based expiry for nodes/edges with `validUntil` field | `MaintenanceScheduler` TTL_SWEEP task (first in scheduled task list) | **DEAD on live store** (documented at line 58-65): uses JPA repositories which are unpopulated on `@Primary` matrix/vector store; `validUntil` field missing from `MatrixGraphNode`; entire TTL_SWEEP phase is a no-op |
| `GraphMaintenanceServiceImpl` | `kompile-knowledge-graph/.../maintenance/GraphMaintenanceServiceImpl.java:248` | Orchestrates all maintenance: pre-snapshot → tasks → post-snapshot; 50-report in-memory history | REST endpoints + `MaintenanceScheduler.runFullMaintenance()` | History lost on restart (in-memory only, line 83); `ENTITY_RE_RESOLUTION` and `CONTRADICTION_DETECT` not in scheduled task list (line 148-154) |
| `MaintenanceScheduler` | `kompile-knowledge-graph/.../maintenance/MaintenanceScheduler.java:121` | Cron `0 0 3 * * *` daily maintenance; standard tasks: TTL_SWEEP, ORPHAN_CLEANUP, CONFIDENCE_PRUNE, COMPONENT_PRUNE, STATS_REFRESH | Requires explicit `enable(factSheetId)` REST call | Off by default with no property to enable; health snapshot NOT persisted during scheduled run; ENTITY_RE_RESOLUTION absent |
| `SnapshotManager` | `kompile-knowledge-graph/.../maintenance/SnapshotManager.java:100` | Creates/lists/restores graph snapshots as JSON + embeddings sidecar; storage at `data/graph/snapshots/<factSheetId>/` | `GraphMaintenanceServiceImpl.createSnapshot()` | Restore is non-atomic: `deleteByFactSheetId` runs before import (line 288) — failure leaves data permanently deleted; no retention policy; no integrity check after restore |
| `GraphHealthSnapshot` | `kompile-app-core/.../maintenance/model/GraphHealthSnapshot.java` | Immutable record: nodeCount, edgeCount, density, averageDegree, maxDegree, orphanCount, orphanRate, lowConfidenceNodeCount, lowConfidenceEdgeCount, connectedComponentCount, largestComponentFraction, conformanceScore | Computed by `GraphHealthService.computeSnapshot()` | No time-series diff in the record; deferred metrics: diameter, clustering coefficient, centralities |
| `GraphHealthService` | `kompile-knowledge-graph/.../maintenance/GraphHealthService.java:93` | O(N+E) single-pass snapshot computation using `UnionFind` for connected components; persists to `data/graph/health/<factSheetId>/<epochMillis>.json`; `compareGraphs` on ENTITY name sets | REST `/api/graph-health`; `persistSnapshot()` must be called explicitly | Not called by `MaintenanceScheduler`; `listHistory` has no cap; `compareGraphs` uses normalized title only (not node ID or type); no automatic trigger |

### 2.2 What EXISTS for Pruning/Removal Today

The following HARD REMOVAL paths exist (verified):

1. **`OrphanPruner.execute()`** (line 113): two-step soft-delete → hard-delete via `knowledgeGraphService.pruneNodes()` and `knowledgeGraphService.hardDeleteStaleNodes()`. Works on live store for ENTITY nodes.

2. **`ConfidencePruner.execute()`** (line 98): soft-deletes low-confidence nodes/edges and edges with `EdgeProvenance.AMBIGUOUS` provenance. Works on live store.

3. **`ComponentPruner`**: soft-deletes small disconnected components.

4. **`GraphCompactionService.mergeComponent()`** (line 1647): `deleteNode()` (hard) when `config.deleteAfterMerge()` is set. Default is soft-delete (stale flag).

5. **`GraphMaintenanceService.pruneByConfidence()`** (interface, line 31): method exists; implementation in `ConfidencePruner`.

6. **`BeliefReviser.retract()`** in `kompile-graph-reasoning`: removes atoms from `InferredFactStore` (in-memory); does NOT remove `EdgeProvenance.INFERRED` edges already materialized to the `@Primary` store.

### 2.3 The Critical Gap

There is NO path today that removes materialized `EdgeProvenance.INFERRED` edges from the `@Primary` store when a supporting derived fact is retracted by `BeliefReviser`. The `InferredFactGraphMaterializer` only writes edges — it never deletes them. This means the "counterbalancing" half is incomplete at the store level: derivation materializes, but retraction does not.

Additionally:
- Compaction is never triggered automatically after crawl or after hydration
- Health snapshots are never persisted by scheduled maintenance
- The `MaintenanceScheduler` is off by default with no property override
- TTL sweep is dead on the live store

---

## 3. Job 2 — Homeostatic Control Loop Design

### 3.1 Architecture Overview

Hydration is a **homeostatic control loop**, not a one-way pipeline:

```
                    ┌─────────────────────────────────────────────────┐
                    │          GRAPH HEALTH MONITOR                   │
                    │  GraphHealthSnapshot (density, orphanRate,      │
                    │  conformanceScore, lowConfidence*, components)  │
                    │  → PruneCompactBudget (growBias/shrinkBias)     │
                    └──────────┬──────────────────────┬───────────────┘
                               │ too sparse/low-conf?  │ too bloated/orphan-heavy?
                               ▼                       ▼
              ┌────────────────────────┐  ┌────────────────────────────┐
              │  DERIVE (GROW)         │  │  PRUNE + COMPACT (SHRINK)  │
              │  Stages 1–11 from      │  │  Stages P1–P5 below        │
              │  graph-hydration-      │  │  (woven into cascade GC)   │
              │  inference-chain-      │  │                            │
              │  design.md             │  │                            │
              └────────────┬───────────┘  └──────────────┬─────────────┘
                           │                             │
                           └──────────┬──────────────────┘
                                      ▼
                          @Primary matrix/vector store
                          (EdgeProvenance: OBSERVED | INFERRED)
                                      │
                                      ▼
                          GraphHealthService.persistSnapshot()
                          (time-series: data/graph/health/<fs>/)
```

The `GraphHealthSnapshot` is both:
- **INPUT**: drives whether the next cascade runs grow-biased (derive more) or shrink-biased (prune/compact more)
- **OUTPUT**: persisted after every cascade as the fitness time-series
- **CONFIDENCE FACTOR**: facts in low-conformance / high-orphan-rate regions are down-weighted

### 3.2 Prune + Compact Stages (P1–P5)

These five stages run AFTER the hydration derivation stages (after Stage 11: Materialization, before the health snapshot) on every cascade invocation. They are woven into the same `GroundingCascadeHook` execution, not a separate nightly job.

```
[After Stage 11 Materialization]
         │
         ▼
┌─────────────────────────┐
│  Stage P1: INFERRED     │  Remove materialized EdgeProvenance.INFERRED edges
│  EDGE RETRACTION        │  whose supporting InferredFact was retracted by
│                         │  BeliefReviser in Stage 9 of this run.
│                         │  NEW: InferredFactGraphPruner.pruneRetracted(runId)
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage P2: COMPACTION   │  Post-derivation entity merge. Reuse existing
│  (MERGE)                │  GraphCompactionService.compact(factSheetId, config).
│                         │  Health-gated: run only if health.orphanRate > ORPHAN_HI
│                         │  OR health.lowConfidenceNodeCount/nodeCount > NOISE_HI.
│                         │  After merge: IdentityGraphService.rematerialize()
│                         │  to fix dangling RESOLVES_TO edges.
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage P3: PROVENANCE-  │  Remove DERIVED-only facts below confidence threshold.
│  GATED PRUNER           │  RULE: NEVER prune observed truth (EdgeProvenance.OBSERVED,
│                         │  _source = "crawl" | "channel" | "barcode").
│                         │  Prune only: EdgeProvenance.INFERRED + confidence < θ_prune.
│                         │  Reuse ConfidencePruner with requireExtractedProvenance=true
│                         │  + new DERIVED-provenance gate.
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage P4: ORPHAN GC    │  Extended OrphanPruner: ALL NodeLevels in ORPHAN_LEVELS
│                         │  (ENTITY + DOCUMENT + SNIPPET + TABLE + ATTACHMENT +
│                         │  IDENTIFIER), not just ENTITY.
│                         │  Additional CANDIDATE node sweep: CANDIDATE nodes older
│                         │  than CANDIDATE_TTL (default 7 days) with no corroborating
│                         │  crawl mention → soft-delete.
│                         │  Health-gated: run only if health.orphanRate > ORPHAN_LO.
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage P5: COMPONENT    │  ComponentPruner: remove disconnected components below
│  SWEEP                  │  minComponentSize (default 2).
│                         │  Health-gated: run only if health.connectedComponentCount
│                         │  > COMPONENT_HI threshold.
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage PH: HEALTH       │  GraphHealthService.persistSnapshot(factSheetId)
│  SNAPSHOT               │  Compute new GraphHealthSnapshot → persist to
│                         │  data/graph/health/<factSheetId>/<epochMillis>.json
│                         │  Update control loop setpoints for NEXT cascade run.
└─────────────────────────┘
```

### 3.3 What Is Eligible to Prune — The Provenance Gate

This is the most important invariant in the design. The pruner must NEVER remove facts whose sole source is observed reality.

**NEVER prune (observed truth)**:
- Any node or edge with `_source` in `{crawl, channel:*, barcode, agent-assertion}` (from `GraphProvenanceKeys`)
- Any node with `EdgeProvenance.OBSERVED`
- Any node with `NodeLevel.SOURCE` or `NodeLevel.CUSTOM`
- Any barcode/GTIN identity node (`NodeLevel.IDENTIFIER`, `IdentifierScheme` kind = "GTIN" or "ISBN") corroborated by a crawl extraction

**Eligible for pruning (derived / low-value)**:
- `EdgeProvenance.INFERRED` edges whose supporting `InferredFact` was retracted (Stage P1)
- `EdgeProvenance.INFERRED` edges below `θ_prune` confidence (Stage P3)
- `NodeLevel.CANDIDATE` nodes created by abduction (Stage 7) that remain uncorroborated for longer than `CANDIDATE_TTL`
- `EdgeProvenance.AMBIGUOUS` edges (already targeted by `ConfidencePruner.requireExtractedProvenance`)
- Orphan ENTITY nodes with `_derived = "true"` (Stage P4)
- Small disconnected components where ALL nodes have `_derived = "true"` (Stage P5)

**Eligible for compaction (merge)**:
- Any duplicate ENTITY pair where `GraphCompactionService.scorePair()` > merge threshold (Stage P2)
- This is provenance-agnostic — two observed entities can still be merged if they are co-referent

### 3.4 "Relentless" — Cascade-Coupled GC

"Relentless" means: every time derivation runs (every `GroundingCascadeHook` invocation), P1–P5 run immediately afterward in the same cascade execution — not as a separate nightly job.

The `GroundingCascadeHook` currently calls `IncrementalReasoningOrchestrator.runFullReground()`. The change:

```
// Current flow (GroundingCascadeHook)
orchestrator.runFullReground(factSheetId)

// New flow (GroundingCascadeHook with homeostatic GC)
HydrationResult result = pipeline.run(graph, hydrateConfig)          // Stages 1–11 (grow)
PruneCompactResult prune = pruner.run(factSheetId, pruneConfig)       // Stages P1–P5 (shrink)
healthService.persistSnapshot(factSheetId)                            // Stage PH
kbGroundingService.markEpoch(factSheetId, result, prune)
```

This interleaving is the definition of "relentless": GC runs as aggressively as derivation. Every cascade that adds facts also triggers compaction and pruning gated by health thresholds.

### 3.5 GraphHealth as the Control/Fitness Function

`GraphHealthSnapshot` becomes the control signal that biases the cascade toward grow or shrink:

```java
// New: PruneCompactBudget computed from current health snapshot
public record PruneCompactBudget(
    boolean runCompaction,          // Stage P2 gate
    boolean runOrphanGc,            // Stage P4 gate
    boolean runComponentSweep,      // Stage P5 gate
    double confidencePruneThreshold, // θ_prune for Stage P3
    int maxMergePairs,              // cap on compaction pairs per cascade
    boolean growBiased              // true = hydration runs full fixpoint; false = single-pass
) {
    public static PruneCompactBudget from(GraphHealthSnapshot health, HealthSetpoints sp) {
        double orphanRate       = health.orphanRate();
        double noiseRate        = health.lowConfidenceNodeCount() / (double) Math.max(1, health.nodeCount());
        double conformance      = health.conformanceScore();
        boolean tooBloated      = orphanRate > sp.orphanHi() || noiseRate > sp.noiseHi();
        boolean tooSparse       = health.density() < sp.densityLo() || conformance < sp.conformanceLo();
        return new PruneCompactBudget(
            /* runCompaction */         orphanRate > sp.orphanLo() || noiseRate > sp.noiseLo(),
            /* runOrphanGc */           orphanRate > sp.orphanLo(),
            /* runComponentSweep */     health.connectedComponentCount() > sp.componentHi(),
            /* confidencePruneThreshold */ tooBloated ? sp.thetaPruneAggressive() : sp.thetaPruneDefault(),
            /* maxMergePairs */         tooBloated ? sp.maxMergePairsAggressive() : sp.maxMergePairsDefault(),
            /* growBiased */            tooSparse && !tooBloated
        );
    }
}
```

**Health as INPUT** (drives decisions):
- `orphanRate > ORPHAN_HI` (e.g., 0.20) → run compaction + orphan GC; lower `θ_prune` to prune more aggressively
- `orphanRate < ORPHAN_LO` (e.g., 0.05) → skip orphan GC; hydration runs full fixpoint (grow-biased)
- `density < DENSITY_LO` (e.g., 0.001) → graph too sparse → grow-biased (more derivation passes)
- `conformanceScore < CONFORMANCE_LO` (e.g., 0.6) → ontology not well-covered → trigger `GraphOntologyBindingService.validateConformance()` before prune (don't prune what might be valid ontology-derived facts)
- `lowConfidenceNodeCount / nodeCount > NOISE_HI` (e.g., 0.30) → noise-heavy → aggressive confidence pruning

**Health as OUTPUT** (fitness time-series):
- `GraphHealthService.persistSnapshot()` runs at Stage PH after every cascade
- Time-series at `data/graph/health/<factSheetId>/` (already implemented, travels with git clone)
- Trend analysis: `orphanRate` should decrease monotonically after compaction; if it increases, the cascade is producing orphans faster than it cleans them — a signal to increase `θ_prune`

**Health as CONFIDENCE FACTOR** (down-weights facts in unhealthy regions):
- Facts in disconnected components (measured by `connectedComponentCount / nodeCount`) are penalized in the PSL program as lower-confidence observations
- Nodes with `conformanceScore = 0` (no ontology binding) get an extra confidence discount factor (e.g., 0.9×) applied to all their outgoing derived edges
- Implementation: `GraphToFactStoreProjector` reads the health snapshot and applies region-based penalties during the graph → FactStore projection step

### 3.6 Loop Stability — Preventing Oscillation

A naive implementation of derive→prune→derive would oscillate: Stage 4 (PSL rule derivation) derives fact F at confidence 0.52; Stage P3 prunes it (θ_prune = 0.55); Stage 4 re-derives it at 0.52 again on the next cascade.

Three stability mechanisms:

**Mechanism 1: Hysteresis bands on health setpoints**

The `HealthSetpoints` record uses distinct thresholds for entering and exiting aggressive pruning:
- Enter aggressive prune mode: `orphanRate > ORPHAN_HI` (e.g., 0.20)
- Exit aggressive prune mode: `orphanRate < ORPHAN_LO` (e.g., 0.10) — the lower threshold prevents flapping

```java
// Hysteresis state is tracked per factSheet in FactSheetKbState
boolean aggressivePruneMode;    // sticky: once entered, only exits when orphanRate < ORPHAN_LO
```

**Mechanism 2: Prune confidence floor above derivation floor**

`θ_prune` (prune threshold) must always be ≤ `θ_derive` (materialization threshold), and both must differ by at least `HYSTERESIS_GAP` (default 0.05):

```
θ_prune_default = θ_derive - HYSTERESIS_GAP
                = 0.50 - 0.05 = 0.45    (default: only facts below 0.45 are pruned)
θ_prune_aggressive = 0.50               (matches θ_derive when bloated)
```

This means a fact derived at 0.52 (CANDIDATE tier) is never pruned in normal mode (0.45 < 0.52), but in aggressive mode it is pruned (0.52 > 0.50 = θ_prune_aggressive is wrong — it is pruned when confidence < θ_prune_aggressive = 0.50... note: need confidence < prune threshold to prune). Setting:

```
Normal:       prune facts with confidence < 0.45   (won't prune CANDIDATE-tier 0.50–0.70)
Aggressive:   prune facts with confidence < 0.55   (will prune CANDIDATE-tier 0.50–0.55)
```

**Mechanism 3: Retraction cooldown on fact atoms**

An `InferredFact` that has been retracted by `BeliefReviser` and then re-derived by the next cascade gets a "rederivation penalty":

```
new_confidence = derived_confidence × (1 - REDERIVATION_PENALTY)^retraction_count
```

Where `REDERIVATION_PENALTY = 0.1` and `retraction_count` comes from `InferredFactStore.history(atomKey).size()`. A fact that has been retracted 3 times and re-derived at 0.52 gets confidence `0.52 × (0.9)^3 = 0.38`, which falls below `θ_derive` (0.50) and is not materialized — the oscillation self-terminates.

This requires `InferredFactStore.history(atomKey)` (already in the SPI at `InferredFactStore.java:29`).

### 3.7 New Component: InferredFactGraphPruner (Stage P1)

This is the only net-new component required. Everything else reuses existing infrastructure.

```java
// Location: kompile-knowledge-graph/.../reasoning/InferredFactGraphPruner.java
// Module: kompile-knowledge-graph (Spring client, can access @Primary store)
@Component
public class InferredFactGraphPruner {

    /**
     * Removes EdgeProvenance.INFERRED edges from the @Primary store
     * whose supporting InferredFact was retracted during this cascade run.
     * Identified by: (a) edge.provenanceType == INFERRED, and
     * (b) the edge's _inferenceRunId is in this cascade's runId, AND
     *     InferredFactStore.latest(atomKey) does not exist or has a later version
     *     with confidence = 0 (the retraction sentinel).
     *
     * @param factSheetId fact sheet to sweep
     * @param runId       the UUID of the hydration run that just completed
     * @param retractedAtomKeys atom keys retracted by BeliefReviser in this run
     * @return PruneResult with edgesDeleted, nodesMarkedStale counts
     */
    public PruneResult pruneRetracted(Long factSheetId, String runId,
                                      Set<String> retractedAtomKeys);

    /**
     * Prunes ALL EdgeProvenance.INFERRED edges with calibrated confidence
     * below the given threshold, regardless of run ID.
     * Used for aggressive prune mode triggered by health control loop.
     */
    public PruneResult pruneByConfidence(Long factSheetId, double confidenceThreshold, boolean dryRun);
}
```

The `KnowledgeGraphService` already has `pruneEdges(Collection<String> edgeIds, boolean softDelete, boolean dryRun)` which this can delegate to after collecting edge IDs via the provenance index.

### 3.8 Integration into GroundingCascadeHook

```
// GroundingCascadeHook.onChangesetCompleted() — NEW execution order:

1. snapshot BEFORE:  health = graphHealthService.computeSnapshot(factSheetId)
2. budget:           budget = PruneCompactBudget.from(health, setpoints)
3. GROW:             if budget.growBiased()
                         pipeline.run(graph, config.withFullFixpoint())
                     else
                         pipeline.run(graph, config.withSinglePass())
   → yields retractedAtomKeys from Stage 9 (BeliefReviser)
   → yields newlyMaterializedRunId from Stage 10

4. SHRINK:
   P1: inferredPruner.pruneRetracted(factSheetId, runId, retractedAtomKeys)
   P2: if budget.runCompaction()
           compaction.compact(factSheetId, CompactionConfig.default())
           identityService.rematerialize(factSheetId)   // fix dangling RESOLVES_TO
   P3: inferredPruner.pruneByConfidence(factSheetId, budget.confidencePruneThreshold(), false)
   P4: if budget.runOrphanGc()
           orphanPruner.execute(factSheetId, ORPHAN_GRACE, false)     // all NodeLevels
           candidatePruner.expireCandidates(factSheetId, CANDIDATE_TTL)  // new
   P5: if budget.runComponentSweep()
           componentPruner.execute(factSheetId, componentPolicy, false)

5. PH: graphHealthService.persistSnapshot(factSheetId)
6.    kbGroundingService.markEpoch(factSheetId, growResult, pruneResult)
```

### 3.9 Unified Bidirectional Picture

```
Observed Graph (G_obs)
    │
    ▼
GROW: Stages 1–11 (graph-hydration-inference-chain-design.md)
    │   Entity Resolution (S2) → Type Inference (S3) → Rule Derivation (S4)
    │   → Link Prediction (S5) → Probabilistic Fusion (S6) → Abduction (S7)
    │   → Temporal Enrichment (S8) → Contradiction/TMS (S9) → Materialization (S10)
    │   → Incremental Trigger (S11)
    │
    ▼ [retractedAtomKeys, materializedRunId]
    │
SHRINK: Stages P1–P5 (this document)
    │   P1: Remove retracted INFERRED edges
    │   P2: Merge duplicate entities (GraphCompactionService)
    │   P3: Prune low-confidence DERIVED facts (InferredFactGraphPruner)
    │   P4: Orphan GC all NodeLevels (OrphanPruner extended)
    │   P5: Component sweep (ComponentPruner)
    │
    ▼
MEASURE: Stage PH — GraphHealthService.persistSnapshot()
    │
    ▼
CONTROL: PruneCompactBudget.from(health, setpoints)
         → biases NEXT cascade toward grow or shrink
         → feeds into DynamicBatchSizer as health pressure signal
```

---

## 4. Phased Build Plan

### Phase 1 (First Buildable Slice): Health-Controlled Maintenance Pass After Hydration

**What**: A single new `InferredFactGraphPruner` bean + wiring of existing `GraphCompactionService`, `ConfidencePruner`, and `OrphanPruner` into a `PruneCompactOrchestrator` that runs after `IncrementalReasoningOrchestrator`. Health snapshot persisted at the end.

**Scope** (all in `kompile-knowledge-graph`):

1. `InferredFactGraphPruner.java` — new class: `pruneRetracted(factSheetId, runId, retractedAtomKeys)` + `pruneByConfidence(factSheetId, threshold, dryRun)`; delegates to `KnowledgeGraphService.pruneEdges()`; provenance gate: only `EdgeProvenance.INFERRED` edges with `_derived = "true"` metadata

2. `PruneCompactOrchestrator.java` — new class: executes P1→P2→P3→P4→P5 in order; reads `PruneCompactBudget`; returns `PruneCompactResult` (edgesRemoved, nodesRemoved, mergesPerfomed, healthSnapshot)

3. `HealthSetpoints.java` — new record: `orphanLo=0.05, orphanHi=0.20, noiseLo=0.15, noiseHi=0.30, densityLo=0.001, conformanceLo=0.6, componentHi=10, thetaPruneDefault=0.45, thetaPruneAggressive=0.55, maxMergePairsDefault=500, maxMergePairsAggressive=2000` — configurable via `graph-extraction-config.json` (hot-reloaded, same pattern as `GraphCompactionService`)

4. `GroundingCascadeHook` change: after `orchestrator.runFullReground()`, call `pruneCompactOrchestrator.run(factSheetId, retractedAtomKeys, runId)` + `graphHealthService.persistSnapshot(factSheetId)`

5. `OrphanPruner` extension: add `executeAllLevels(factSheetId, gracePeriod, dryRun)` that iterates all `GraphHealthService.ORPHAN_LEVELS` (not just ENTITY); delegates each level's policy to the appropriate `OrphanPruningPolicy` variant

6. `IdentityGraphService.rematerialize(factSheetId)` — add a post-merge re-materialization call to fix dangling RESOLVES_TO edges: after `GraphCompactionService.compact()` completes, call `identityService.materialize(factSheetId)` (already idempotent via `edgeExists` check at line 195)

7. `MaintenanceScheduler` fix: add `graphHealthService.persistSnapshot(factSheetId)` to `runMaintenanceFor()` after `runFullMaintenance()` completes; add `ENTITY_RE_RESOLUTION` to the scheduled task list

8. `TtlSweepExecutor` note: document that TTL sweep requires `validUntil` field on `MatrixGraphNode` — out of scope for Phase 1 but the dead code is flagged for Phase 3

**Out of scope for Phase 1**: Rederivation penalty (Mechanism 3), `DynamicBatchSizer` health feedback, `PruneCompactBudget.growBiased` affecting fixpoint iteration depth (Phase 2), full hysteresis state in `FactSheetKbState` (Phase 2)

### Phase 2: Hysteresis State + Grow/Shrink Bias

**What**: Persist hysteresis state (`aggressivePruneMode` flag) in `FactSheetKbState`. Wire `PruneCompactBudget.growBiased` into `HydrationConfig` (fewer fixpoint iterations when shrink-biased). Add rederivation penalty via `InferredFactStore.history()`.

**Key deliverables**:
- `FactSheetKbState` extended with `aggressivePruneMode: boolean` and sticky hysteresis logic
- `HydrationConfig.maxIterations` dynamically set from budget: `budget.growBiased() ? MAX_ITERS : 1`
- Rederivation penalty in `IncrementalReasoningOrchestrator`: read `history(atomKey).size()`, apply `(1 - 0.1)^count` to derived confidence before versioning
- `DynamicBatchSizer` extension: `recordHealthPressure(GraphHealthSnapshot health)` — maps `orphanRate` to additional memory pressure signal alongside JVM heap

### Phase 3: TTL Sweep Repair + CANDIDATE Expiry + Snapshot Safety

**What**: Fix `TtlSweepExecutor` to work on the live store. Add `CANDIDATE_TTL` expiry. Make snapshot restore safe (pre-validate before delete).

**Key deliverables**:
- `MatrixGraphNode` extended with `validUntil: Instant` field; `TtlSweepExecutor` live-store path
- `CandidatePruner.expireCandidates(factSheetId, ttl, dryRun)` — new: finds `NodeLevel.CANDIDATE` nodes older than TTL with `_derived = "true"` and no corroborating crawl mention; soft-deletes
- `SnapshotManager.restoreSnapshot()` made safe: (a) validate import in dry-run first; (b) create a pre-restore snapshot; only then delete-and-import
- Snapshot retention policy: configurable `maxSnapshotsPerFactSheet` (default 10); `SnapshotManager.pruneSnapshots()` called after every `createSnapshot()`

### Phase 4: Health Trend Analysis + Adaptive Setpoints

**What**: Time-series analysis over `GraphHealthService.listHistory()` to detect trends and adapt setpoints automatically.

**Key deliverables**:
- `HealthTrendAnalyzer`: reads the last N health snapshots, computes Δ(orphanRate), Δ(conformanceScore) per cascade; detects "growing orphans" trend (orphan rate increasing monotonically across 3+ cascades)
- Auto-adjustment of `HealthSetpoints` thresholds when trend analysis signals runaway growth or excessive pruning
- REST endpoint: `GET /api/graph-health/{factSheetId}/trend` — returns trend summary + current setpoints + any auto-adjustments

---

## 5. Forks — Flag, Recommend, Tradeoff

### Fork A: Prune Aggressiveness / θ_prune

**Option 1 — Conservative** (θ_prune = 0.30): Only prune very low-confidence SPECULATIVE facts. Preserves most derived facts; risk: noise accumulation.

**Option 2 — Moderate** (θ_prune = 0.45, default recommendation): Prunes SPECULATIVE (0.30–0.50) but keeps CANDIDATE (0.50–0.70) and HIGH_CONFIDENCE (≥0.70). Balanced.

**Option 3 — Aggressive** (θ_prune = 0.55 in health-triggered mode): Also prunes low-CANDIDATE tier facts. Used only when health signals bloat (`orphanRate > ORPHAN_HI` or `noiseRate > NOISE_HI`). Temporary mode, exits when health recovers.

**RECOMMENDATION**: Two-mode system. Normal mode: θ_prune = 0.45 (below θ_derive = 0.50, enforcing HYSTERESIS_GAP = 0.05). Aggressive mode (health-triggered): θ_prune = 0.55. The gap prevents the oscillation where a CANDIDATE-tier fact is derived at 0.52 then immediately pruned.

**TRADEOFF**: Lower θ_prune = higher recall (more facts survive) = more noise for agents; higher θ_prune = higher precision = risk of pruning valid but uncertain inferences. Until a calibration dataset exists (see `graph-hydration-inference-chain-design.md §10 OQ-1`), treat these thresholds as provisional.

### Fork B: Hard-DELETE vs Soft-TOMBSTONE Derived Facts

**Option 1 — Hard delete**: `pruneEdges(..., softDelete=false)` and `deleteNode()` immediately remove derived facts from the `@Primary` store. Clean; no storage growth; not reversible.

**Option 2 — Soft tombstone**: `pruneEdges(..., softDelete=true)` sets a `stale=true` flag; facts are invisible to queries but remain in the store for the grace period (7 days default). Reversible: un-stale via `BeliefReviser` re-assertion if the fact is re-derived.

**RECOMMENDATION**: Soft-tombstone for ALL derived facts pruned by P1 (retracted INFERRED edges) and P3 (low-confidence). Hard-delete only after the grace period (matching existing `OrphanPruner` 7-day grace). This ties cleanly into `graph-hydration-inference-chain-design.md §8 Fork B`: separate overlay keeps the `@Primary` store clean; soft-tombstone lets `InferredFactStore` history drive potential re-assertion before hard-delete.

**TRADEOFF**: Soft tombstone accumulates stale rows in the live store during the grace period, increasing query-time filter overhead. For high-churn graphs (frequent recrawls), set `DERIVED_GRACE_DAYS = 1` instead of 7. For archival graphs (rare crawls), keep 7 days.

**CRITICAL DEPENDENCY**: this fork is tied to `graph-hydration-inference-chain-design.md §8 Fork B` (Materialization Layering). If the user chooses "embedded" materialization (derived facts mixed into the observed graph), then soft-tombstone is mandatory to preserve the ability to distinguish OBSERVED vs. DERIVED after a prune. If "separate overlay" is chosen, hard-delete of INFERRED edges is safe because observed facts are never touched.

### Fork C: Health Setpoints — Global Default vs Per-FactSheet vs Learned

**Option 1 — Global defaults**: One `HealthSetpoints` instance in `graph-extraction-config.json` applies to all factSheets. Simple; may be wrong for specific factSheets.

**Option 2 — Per-factSheet**: Each factSheet can override setpoints in its own config section. Flexible; requires UI for management.

**Option 3 — Learned**: `HealthTrendAnalyzer` (Phase 4) adapts setpoints based on observed health trends for each factSheet. Autonomous; risk of misadaption if the trend analyzer has bugs.

**RECOMMENDATION**: Option 1 for Phase 1 (global defaults in `graph-extraction-config.json`). Option 2 for Phase 2 (per-factSheet override). Option 3 deferred until Phase 4 trend analysis is validated. The global defaults are the right starting point because most factSheets will have similar health profiles at launch.

---

## 6. Open Questions

**OQ-P1: retractedAtomKeys handoff from Stage 9 to Stage P1**

`BeliefReviser.retract()` (in `kompile-graph-reasoning`) currently operates on `InferredFactStore` (in-memory). The retracted atom keys must be handed to `InferredFactGraphPruner` (in `kompile-knowledge-graph`) to delete the materialized edges. The handoff is straightforward IF `BeliefRevisionResult.unsupported()` (the Set of retracted atom keys) is exposed from `IncrementalReasoningOrchestrator` to `GroundingCascadeHook`. Currently it is not — `runFullReground()` returns void. This requires changing the return type of `IncrementalReasoningOrchestrator.runFullReground()` to carry `retractedAtomKeys`.

**OQ-P2: GraphCompactionService 100k node cap**

The hardcoded `searchNodes("", NodeLevel.ENTITY, 100_000)` cap at `GraphCompactionService.java:748` will silently truncate large graphs. For Phase 1, document the limit. For Phase 2, implement paginated compaction: process entities in blocks of 10k, maintaining a cross-block merge map to handle entities that match across block boundaries.

**OQ-P3: OrphanPruner ENTITY-only vs ALL NodeLevels**

`OrphanPruner.evaluatePolicy()` (line 174) only loads `NodeLevel.ENTITY` nodes. The proposed `executeAllLevels()` extension must handle the policy differently for each level: a DOCUMENT node with no edges to ENTITY nodes is an orphan; a SNIPPET node with no CONTAINS edge to DOCUMENT is an orphan; an IDENTIFIER node with no RESOLVES_TO edge is an orphan. These require distinct policies or a parameterized `OrphanPruningPolicy`.

**OQ-P4: CANDIDATE_TTL source**

CANDIDATE latent-cause nodes are created by Stage 7 (Abduction). The proposed `CandidatePruner.expireCandidates()` needs a `createdAt` field on nodes to enforce TTL. `GraphNode.getCreatedAt()` exists but may be null on the matrix store (same issue as `ContradictionDetector`'s NEWER_WINS). For Phase 1, use the snapshot timestamp of the health entry that first observed the CANDIDATE node as a proxy for `createdAt`.

**OQ-P5: IdentityGraphService.rematerialize() efficiency**

Calling `identityService.materialize(factSheetId)` after every compaction is idempotent but potentially expensive (scans all ENTITY nodes for identifier metadata). For Phase 1, only call it when compaction actually performed at least one merge (`CompactionResult.mergesPerformed() > 0`). For Phase 2, implement `identityService.rematerializeForNodes(Set<String> mergedNodeIds)` that only touches the nodes involved in merges.

---

## 7. Appendix: File Path Reference

| Component | Path | Key Method |
|-----------|------|------------|
| `UnifiedCrawlJob` | `kompile-app-core/src/main/java/ai/kompile/core/crawl/graph/UnifiedCrawlJob.java:63` | `toProgressSnapshot()` (line 961) |
| `UnifiedCrawlRequest` | `kompile-app-core/src/main/java/ai/kompile/core/crawl/graph/UnifiedCrawlRequest.java:47` | `enabledSteps`, `archivedSteps` (lines 107, 115) |
| `DynamicBatchSizer` | `kompile-app-core/src/main/java/ai/kompile/core/crawl/graph/DynamicBatchSizer.java:90` | `recordBatchResult(int, long, boolean, double)` (line 160) |
| `EntityResolutionService` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/EntityResolutionService.java:48` | `resolve(List<ExtractionResult>)` (line 70) |
| `GraphCompactionService` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/GraphCompactionService.java:416` | `compact(Long, CompactionConfig)` (line 424) |
| `IdentityGraphService` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/IdentityGraphService.java:195` | `materialize(Long)` |
| `OrphanPruner` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/OrphanPruner.java:82` | `execute(Long, Duration, boolean)` (line 113) |
| `ConfidencePruner` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/ConfidencePruner.java:98` | `execute(Long, ConfidencePrunePolicy, boolean)` |
| `ComponentPruner` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/ComponentPruner.java` | `execute(Long, ComponentPrunePolicy, boolean)` |
| `ContradictionDetector` (maintenance) | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/ContradictionDetector.java:75` | `detect(Long)`, `resolve(Long, Strategy, boolean)` |
| `TtlSweepExecutor` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/TtlSweepExecutor.java:58` | DEAD on live store (documented line 58-65) |
| `GraphMaintenanceServiceImpl` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/GraphMaintenanceServiceImpl.java:248` | `runFullMaintenance(Long, MaintenanceSchedule)` |
| `MaintenanceScheduler` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/MaintenanceScheduler.java:121` | `scheduledMaintenance()` (cron 0 0 3 * * *, off by default) |
| `SnapshotManager` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/SnapshotManager.java:100` | `createSnapshot(Long, String)`, `restoreSnapshot(String)` (line 253) |
| `GraphHealthSnapshot` | `kompile-app-core/src/main/java/ai/kompile/core/graphrag/maintenance/model/GraphHealthSnapshot.java` | record with density, orphanRate, conformanceScore |
| `GraphHealthService` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/GraphHealthService.java:93` | `computeSnapshot(Long)`, `persistSnapshot(Long)` (line 170) |
| `GroundingCascadeHook` | `kompile-graph-change-tracking/src/main/java/ai/kompile/graphchangetracking/hook/GroundingCascadeHook.java:70` | Entry point for cascade; wraps `IncrementalReasoningOrchestrator` |
| `IncrementalReasoningOrchestrator` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/IncrementalReasoningOrchestrator.java:95` | `runFullReground(Long)` — currently returns void; needs to return `retractedAtomKeys` |
| `InferredFactGraphMaterializer` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/InferredFactGraphMaterializer.java:51` | `materialize(Collection<InferredFact>, Long)` — writes only, no delete path |
| `BeliefReviser` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/BeliefReviser.java:34` | `retract(atomKey, factStore, index)` — in-memory only; result must propagate to pruner |
| `InferredFactStore` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/InferredFactStore.java:29` | `history(atomKey)` — supports rederivation penalty |
| `GraphMaintenanceService` (interface) | `kompile-app-core/src/main/java/ai/kompile/core/graphrag/maintenance/GraphMaintenanceService.java:27` | `pruneOrphans(Long, Duration, boolean)`, `pruneByConfidence(Long, ConfidencePrunePolicy, boolean)` |
