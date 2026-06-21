# Graph Hydration / Knowledge-Base-Construction Inference Chain Design

**Status**: DESIGN-ONLY (no code, no Maven)
**Date**: 2026-06-21
**Scope**: Deriving graph facts (entities, types, relations, missing links, latent events/causes) from messy, incomplete observed data (crawls + events) — turning an observation-only graph into a completed, confidence-weighted, provenance-tagged KB.
**Complements**:
- `docs/architecture/incremental-cascade-reasoning-design.md` (L3 algorithm detail)
- `docs/architecture/grounding-evaluation-design.md` (threshold sensitivity, calibration harness)
- `docs/architecture/reasoning-trail-explainability-design.md` (ReasoningTrail / derivation provenance)
- `docs/architecture/production-kb-integration-design.md` (crawl→KB push lifecycle)

---

## 1. Problem Statement

Kompile's graph today is an **observation-only graph**: nodes and edges are added exactly as extractors see them. There is no step that asks "what else must be true, or is likely true, given what we see?" This document designs the inference chain that answers that question.

### 1.1 The Gap

A raw crawl graph contains:
- Duplicate entities under different surface forms (Apple vs. Apple Inc.)
- Missing type assignments (entity "Alice" has no type because the LLM extractor omitted it)
- Structurally missing edges (if A KNOWS B and B KNOWS C, perhaps A transitively RELATED_TO C)
- Missing relations between co-extracted entities that LLMs failed to surface
- Causal gaps — an observed "system failure" event with no upstream causes in the graph
- Temporal inconsistencies — a "signed contract" event timestamped before the "negotiation" event

The goal: from an incomplete observation-only graph G_obs, produce a confidence-weighted, provenance-tagged knowledge base KB that includes both what was observed and what can be reliably inferred.

### 1.2 Prior Art and Key Lessons

**Stanford DeepDive / Snorkel** (Niu et al. 2012; Ratner et al. 2017)
Approach: treat every extractor as a noisy labeling function in a factor graph. Facts are not ground-truth — they are noisy evidence. The inference step fuses noisy evidence with rule knowledge to learn calibrated probabilities. Distant supervision provides training signal without hand-labeled data.
Key lesson for kompile: **each extractor (TikaGenericGraphExtractor, MultiAgentExtractionService, BarcodeIdentityGraphService) is a noisy factor, not ground truth. Treat their outputs as evidence with a prior confidence, not as assertions.** Fuse multiple extractor outputs multiplicatively rather than using the last-write-wins merge.

**Google Knowledge Vault** (Dong et al. 2014)
Approach: fused 15+ web extraction systems + 3 link-prediction models + text matching into a single confidence-calibrated KB via Platt scaling. Each raw extractor score was calibrated independently before fusion.
Key lessons for kompile: (a) **calibrate raw confidence scores** — the PSL soft-truth [0,1] and RotatE distance must be mapped to calibrated probabilities via Platt scaling before combining with other signals; (b) **multiplicative fusion for independent signals** (e.g., structural rule + embedding similarity both suggesting "isEmployedBy(Alice, Acme)") produces well-calibrated probabilities.

**NELL — Never-Ending Language Learning** (Carlson et al. 2010)
Approach: coupled bootstrapping — extraction and type inference mutually reinforce each other. Better types → better relation extraction → better types. The feedback loop required a fixpoint iteration.
Key lesson for kompile: **EntityResolution ↔ TypeInference is a natural fixpoint pair**. After type inference, re-running entity resolution with type constraints reduces false merges (two "Alice" entities with type PERSON vs. DOCUMENT should not merge). The system must iterate.

**KGC — Knowledge Graph Completion** (TransE, RotatE, Rule Mining — Bordes et al. 2013; Sun et al. 2019; Galárraga et al. 2013)
Approach: embed entities and relations in geometric space, then predict missing triples by scoring (h, r, t) candidate tuples. Rule mining (AMIE) extracts closed-path rules (e.g., bornIn → livesIn) directly from the graph.
Key lesson for kompile: **RotatELearner + LinkPredictor fill structural gaps** that rules miss. Embedding-based link prediction is especially effective for sparse graphs where rule patterns lack enough support.

**ProbKB / Probabilistic Datalog** (Wang et al. 2014; Suciu et al. 2011)
Approach: Datalog with probabilistic semantics — facts carry probabilities and rules propagate them via the product rule. Fixpoint evaluation converges on a probability assignment.
Key lesson for kompile: **PSL HL-MRF is the probabilistic Datalog analogue** already implemented in kompile, but it computes MAP soft-truth, not a proper Bayesian probability. For downstream agents that need probabilities, PSL outputs must be calibrated.

---

## 2. Chain as a DAG of Derivation Stages

The hydration chain is a directed acyclic graph (DAG) of stages. Within a batch run, stages execute in topological order. Some pairs form feedback loops that require fixpoint iteration.

```
[Raw Crawl / Event Graph]
        │
        ▼
┌─────────────────────────┐
│  Stage 1: INGEST        │  TikaGenericGraphExtractor, MultiAgentExtractionService
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 2: ENTITY        │  EntityResolutionService (Levenshtein + alias),
│  RESOLUTION             │  GraphCompactionService (embedding cosine + merge),
└───────────┬─────────────┘  BarcodeIdentityGraphService (GTIN/UPC identity)
            │     ▲
            │     │ (fixpoint feedback from Stage 3)
            ▼     │
┌─────────────────────────┐
│  Stage 3: TYPE          │  OwlRlReasoner (OWL 2 RL forward-chaining),
│  INFERENCE              │  TypeHierarchy (isA transitive closure)
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 4: RULE          │  FolInferenceService (FOL → PSL grounding),
│  DERIVATION             │  EntailmentEngine (MEBN + PSL paths),
│                         │  HlMrfSolver / HlMrfMapInference (MAP solve)
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 5: LINK          │  RotatELearner (ND4J SameDiff, complex-space KGE),
│  PREDICTION             │  LinkPredictor (topK plausible triples),
│                         │  EmbeddingPslEvidence (cosine → PSL similar(a,b))
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 6: PROBABILISTIC │  MebnInferenceService (SSBN / Bayesian VE),
│  FUSION                 │  Platt-scaling calibration (see §4),
│                         │  Multiplicative signal fusion
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 7: ABDUCTION /   │  TemporalAttributionService (causal chain BFS,
│  CAUSAL                 │  AllenRelation precedence-prune, decay weighting),
│                         │  EntailmentEngine.entailFromMebn (latent causes)
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 8: TEMPORAL      │  TemporalView (filter by window, lazy),
│  ENRICHMENT             │  AllenRelation (Allen 1983 interval algebra),
│                         │  TemporalAttributionService §5a-§5d (precedence,
│                         │  window scope, decay, chain consistency)
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 9: CONTRADICTION │  ContradictionDetector (hard constraint violations
│  / TMS                  │  + fact-level contradictions on atom key conflicts),
│                         │  BeliefReviser (retract + JustificationIndex cascade),
│                         │  JustificationIndex (atom → supporting rules + facts)
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 10:              │  InferredFactGraphMaterializer (InferredFact →
│  MATERIALIZATION        │  EdgeProvenance.INFERRED edges + node attributes),
│                         │  InferredFactStore (versioned atom store),
│                         │  GraphToFactStoreProjector (graph nodes/edges → FactStore)
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  Stage 11: INCREMENTAL  │  GroundingCascadeHook (Spring event listener:
│  TRIGGER                │  GraphChangesetCompletedEvent, ChannelMessageReceivedEvent,
│                         │  AgentFactAssertedEvent),
│                         │  IncrementalReasoningOrchestrator (per-factSheet
│                         │  serialized executor, full re-ground with VERSION_EPSILON)
└─────────────────────────┘
```

### 2.1 Stage-by-Stage Detail

#### Stage 1: Ingest

**What it does**: Consumes crawled documents and events; extracts raw entity/relation/attribute triples.
**Existing implementations**:
- `TikaGenericGraphExtractor` — `kompile-app/kompile-data/kompile-loaders/kompile-loader-tika/src/main/java/ai/kompile/loader/tika/TikaGenericGraphExtractor.java:52` — fallback structural extractor for Tika-parsed docs; produces DOCUMENT/PERSON/TOPIC/ORGANIZATION nodes + AUTHORED_BY/HAS_TOPIC/PRODUCED_BY edges
- `MultiAgentExtractionService` — `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/agent/MultiAgentExtractionService.java:52` — orchestrates `RelationExtractionAgent` beans, merges results via `DefaultMultiAgentGraphBuilder`
- `GraphProvenanceKeys` — `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/domain/GraphProvenanceKeys.java:31` — records `_source`, `_crawlRunId`, `_sourceDocumentId`, `_extractionModel`, `_extractedAt` on every node's `metadataJson`
**Input**: raw document text + event stream
**Output**: `GraphNode` + `GraphEdge` records in the `@Primary` vector/matrix store, each tagged with `GraphProvenanceKeys.crawl(...)` metadata
**DeepDive lesson applied here**: each extractor is treated as a noisy labeling function. The initial confidence for an extracted fact is the extractor's confidence score (or 1.0 for structured crawls), NOT 1.0 across the board.

#### Stage 2: Entity Resolution

**What it does**: Deduplicates entity mentions across chunks, documents, and extractor runs. Merges co-referent nodes into canonical nodes.
**Existing implementations**:
- `EntityResolutionService` — `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/EntityResolutionService.java:48` — Levenshtein similarity (threshold 0.85) + alias matching + suffix normalization, operates pre-persistence on `ExtractionResult` lists
- `GraphCompactionService` — `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/GraphCompactionService.java:60` — post-persistence merge on persisted `GraphNode`s; blocking by type, pairwise similarity (title match + alias overlap + Levenshtein + embedding cosine ≥ 0.88 + attribute-behavior scoring), connected components, canonical election, Senzing-style explainability; uses `EmbeddingModel` for vector similarity
- `BarcodeIdentityGraphService` — worktree path `kompile-knowledge-graph/.../resolution/BarcodeIdentityGraphService.java` — GTIN-14 normalization + NodeLevel.IDENTIFIER + EdgeType.RESOLVES_TO; Phase-2 of barcode entity resolution
**Input**: raw `GraphNode` pool with potential duplicates
**Output**: deduplicated canonical nodes with `mergeReason` metadata + RESOLVES_TO edges
**NELL lesson applied**: Entity resolution feeds type-constrained merge rules from Stage 3 back into Stage 2 (fixpoint). Two "Alice" entities with differing types (PERSON vs. DOCUMENT) must not be merged even if name similarity is high.

#### Stage 3: Type Inference

**What it does**: Infers missing or implicit types using OWL 2 RL forward-chaining and transitive closure.
**Existing implementations**:
- `OwlRlReasoner` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/mebn/type/owl/OwlRlReasoner.java:71` — two strategies: (a) BFS transitive closure for `owl:TransitiveProperty` (prp-trp rule); (b) `OwlRlRuleCompiler` translates TBox axioms → `FolRule`s run through `FolInferenceService`. Produces `OwlRlResult.inferredTypes()` and `inconsistencies()`. Stateless, non-mutating.
- `TypeHierarchy` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/mebn/type/TypeHierarchy.java:34` — snapshot of type membership computed from `ReasoningGraph.entities()` grouped by `GraphEntity.type()`; isA reflexive+transitive closure (cycle-safe); merges with a declared `TypeRegistry`
**Input**: `ReasoningGraph` with partial type annotations + TBox axioms (OWL ontology)
**Output**: `OwlRlResult` with `inferredTypes` (soft-truth ≥ 0.5) + `inconsistencies`; updated `TypeHierarchy`
**Fixpoint with Stage 2**: after inferring types, re-run `GraphCompactionService` with type-constrained blocking to prevent cross-type merges

#### Stage 4: Rule-Based Relation Derivation

**What it does**: Derives new relations from existing ones using first-order logic rules compiled to PSL soft-truth inference. Also runs MEBN entailment for probabilistic rule firing.
**Existing implementations**:
- `FolInferenceService` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/FolInferenceService.java:73` — wraps graph in `ReasoningGraphKnowledgeBase`, builds `PslProgram` from graph entities/relations, grounds `FolRule`s into PSL body/head pairs, runs `HlMrfMapInference.solve()`, translates PSL constants back to entity IDs. Infra-free.
- `HlMrfSolver` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/psl/HlMrfSolver.java:30` — strategy interface; implementations: `ScalarHlMrfInference` (plain-Java projected gradient descent) and `TensorHlMrfInference` (ND4J GPU-capable); chosen by problem size in `HlMrfMapInference.chooseSolver(int)`
- `EntailmentEngine` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/EntailmentEngine.java:55` — facade over both MEBN path (`entailFromMebn` → `MebnInferenceService.infer`) and PSL path; maps findings → evidence → `EntailmentRecord`s with `activatedRules`, `supportingFindingKeys`
**Input**: `ReasoningGraph` + `FolRuleSet` (user-defined or mined)
**Output**: `FolInferenceResult` with entity likelihoods; `EntailmentRecord` list with activated rules
**IMPORTANT**: PSL soft-truth [0,1] uses Łukasiewicz T-norm — NOT a Bayesian probability. PSL MAP values must not be directly compared with Bayesian posteriors from Stage 6 without calibration (see §4).

#### Stage 5: Statistical Link Prediction

**What it does**: Predicts missing relations using trained knowledge-graph embeddings (RotatE). For each entity pair, scores plausibility of each relation type and returns top-K candidates above a confidence threshold.
**Existing implementations**:
- `RotatELearner` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/embedding/learn/RotatELearner.java` — ND4J SameDiff autodiff; RotatE complex-space rotation model (Sun et al. 2019); self-adversarial negative sampling; Adam optimizer; trains on `ReasoningGraph` triples
- `LinkPredictor` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/embedding/learn/LinkPredictor.java:44` — wraps `RotatELearner.TrainedRotatE`; `predictTails(head, relation, topK)` and `predictHeads(tail, relation, topK)` returning `ScoredPrediction` list sorted ascending by RotatE distance (lower = more plausible)
- `EmbeddingPslEvidence` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/embedding/EmbeddingPslEvidence.java:56` — bridges `EmbeddingTable` cosine similarity into PSL `PslProgram` as observed `similar(a, b)` atoms (above a threshold), creating the feedback loop where PSL rules propagate labels over embedding-similarity structure
**Input**: trained `EmbeddingTable` + `ReasoningGraph`
**Output**: `ScoredPrediction` candidates for missing edges; `PslProgram` injected with `similar(a,b)` atoms
**Knowledge Vault lesson**: RotatE distance is NOT a probability. Before fusing with PSL or Bayesian signals, calibrate via Platt scaling: `P(link) = sigmoid(w * (-distance) + b)` where `w`, `b` are fit on a held-out labeled triple set.

#### Stage 6: Probabilistic Fusion

**What it does**: Fuses signals from multiple stages into calibrated confidence values. Runs MEBN/SSBN for proper Bayesian posterior computation.
**Existing implementations**:
- `MebnInferenceService` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/MebnInferenceService.java:59` — complete MEBN loop in the lib: wraps `ReasoningGraph` in `ReasoningGraphKnowledgeBase`, runs `SSBNGenerator.generate()` to produce a `BayesianNetwork`, then `VariableElimination` for posteriors. Returns `Map<String, Double>` (grounded BN variable → posterior P(TRUE)).
**Input**: `ReasoningGraph` + `MTheory` + evidence map; calibrated PSL scores from Stage 4; calibrated RotatE scores from Stage 5
**Output**: `Map<String, Double>` posteriors from MEBN; fused confidence per derived fact
**Fusion formula**:
- Independent signals (PSL rule + RotatE embedding + MEBN): multiplicative fusion (Knowledge Vault): `P_fused = 1 - Π_i(1 - P_i)` — this is the noisy-OR combination, appropriate when signals are independently noisy sources for the same underlying truth
- Dependent signals (two PSL rules sharing an antecedent): averaging or taking the max to avoid double-counting
- All PSL soft-truth values must be calibrated to probabilities BEFORE this step (see §4)
**NOTE**: MEBN SSBN generation is O(exponential in the number of random variables in the SSBN in the worst case). For large graphs (>5000 nodes), limit MEBN to targeted subgraphs around specific query entities rather than running globally.

#### Stage 7: Abduction / Causal Enrichment

**What it does**: Discovers latent causes for observed events by backwards chaining from observed effects through causal relation types. Derives missing causal links.
**Existing implementations**:
- `TemporalAttributionService` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/attribution/TemporalAttributionService.java` — infra-free causal chain BFS; enforces §5a precedence prune (cause must strictly precede effect via `AllenRelation`), §5b temporal window scope (wraps graph in `TemporalView`), §5c decay weighting (NONE/EXPONENTIAL/LINEAR by elapsed time), §5d chain consistency validation; produces `AttributionChain` with `CausalHop` list
- `EntailmentEngine.entailFromMebn(...)` — infers latent causes from MEBN posteriors: if P(cause | evidence) > threshold, emit a derived CAUSES edge with confidence = posterior
**Input**: `ReasoningGraph` + `TemporalAttributionQuery` (target event, temporal window, decay config)
**Output**: `AttributionChain` list; derived CAUSES/PRECEDES/CONTRIBUTES_TO edges with `CausalHop.strength` as confidence
**Abduction**: facts that would, if true, best explain the observed events (minimum-cost explanation). Currently implemented as BFS over causal edge types; latent events are derived as CANDIDATE nodes with `NodeLevel.CANDIDATE`.

#### Stage 8: Temporal Enrichment

**What it does**: Filters and augments the graph with temporal validity, ordering, and Allen interval relations. Derives temporal facts ("A met B before C happened").
**Existing implementations**:
- `TemporalView` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/model/TemporalView.java:57` — lazy filter wrapper over `ReasoningGraph`; `TemporalView.asOf(graph, instant)` includes only valid-at-instant entities; `TemporalView.between(graph, from, to)` includes only entities overlapping `[from, to)`; relation endpoint rule: both endpoints must be included
- `AllenRelation` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/model/AllenRelation.java:39` — Allen 1983 13 mutually-exclusive interval relations (BEFORE, AFTER, MEETS, MET_BY, OVERLAPS, OVERLAPPED_BY, STARTS, STARTED_BY, DURING, CONTAINS, FINISHES, FINISHED_BY, EQUALS); computed from `TemporalInterval` bounds via `AllenRelation.compute(a, b)`
**Input**: `ReasoningGraph` entities with `validTime` intervals or point `timestamp`s
**Output**: `TemporalView` filtered subgraph; derived temporal ordering edges (BEFORE, PRECEDES, etc.) with `AllenRelation` provenance
**Design decision**: temporal enrichment adds edges for ALLEN_BEFORE/ALLEN_MEETS relations between co-temporal entities as derived facts; these propagate into the PSL program as `precedes(a, b)` atoms for causal rule firing

#### Stage 9: Contradiction Detection and TMS

**What it does**: Detects conflicts between inferred facts, between extractors, and between evidence and inferences. Retracts lower-confidence facts via truth-maintenance.
**Existing implementations**:
- `ContradictionDetector` (graph-reasoning) — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/ContradictionDetector.java:36` — scans MAP inference results for hard constraint violations (`GroundRule.distanceToSatisfaction > threshold`) + fact-level contradictions (same atom key, conflicting values 1.0 vs 0.0); returns `Contradiction` list
- `BeliefReviser` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/BeliefReviser.java:34` — `BeliefReviser.retract(atomKey, factStore, index)`: removes fact from `FactStore`, uses `JustificationIndex.solelyDependentOn(key)` to identify unsupported atoms, returns `BeliefRevisionResult` with `unsupported` + `weakened` sets; can also re-run inference after retraction
- `JustificationIndex` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/JustificationIndex.java:33` — maps: atom → supporting rules; atom → observed facts that appear in rule bodies; fact → atoms depending on it; built from `HlMrfMapInference.Result` + `FactStore`; used by `BeliefReviser` for retraction cascade
**NOTE (MEMORY)**: The `ContradictionDetector` in `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/ContradictionDetector.java` is JPA-only and dead on the `@Primary` matrix store. Use only the `kompile-graph-reasoning` version.
**DeepDive lesson**: when two extractors conflict on the same atom, retract the lower-confidence one rather than keeping both. The DeepDive approach is to resolve conflicts at the factor-graph level — represented in kompile as PSL hard constraints whose violations trigger `BeliefReviser.retract` on the losing fact.

#### Stage 10: Materialization

**What it does**: Persists derived facts from the `InferredFactStore` back into the live graph store as `EdgeProvenance.INFERRED` edges and `inferred.<Pred>` node attributes, so they accumulate across sessions and travel via portability/snapshots.
**Existing implementations**:
- `InferredFactGraphMaterializer` — `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/InferredFactGraphMaterializer.java:51` — `materialize(facts, factSheetId)` delegates grammar/classification to infra-free `InferredFactMaterializer`; writes binary predicates as `EdgeProvenance.INFERRED` edges (so `provenanceType` round-trips and edges travel with portability/snapshots); writes unary predicates as `inferred.<Pred>` node attributes
- `InferredFactStore` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/InferredFactStore.java:29` — SPI with versioned atom semantics: `store(InferredFact)` assigns strictly-increasing version; `latest(atomKey)` returns highest-versioned; `history(atomKey)` returns all versions in ascending order; `byRun(runId)` and `allLatest()`; `purge(atomKey)` for retraction
- `GraphToFactStoreProjector` — `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/GraphToFactStoreProjector.java:60` — projects persisted `GraphNode`s → unary type atoms (`lowercase(nodeType)(externalId)`) and `GraphEdge`s → binary predicate atoms (`lowercase(edgeType)(src, tgt)` with edge weight as soft-truth); idempotent (upsert semantics)
**Input**: `Collection<InferredFact>` from `InferredFactStore.allLatest()` above confidence threshold θ_derive
**Output**: `EdgeProvenance.INFERRED` edges in the `@Primary` matrix/vector store; `MaterializationResult(edgesCreated, attributesSet, skipped)`

#### Stage 11: Incremental Trigger

**What it does**: Reacts to graph mutations from any source and re-runs the grounding cascade so the KB stays current without requiring a manual full re-hydration.
**Existing implementations**:
- `GroundingCascadeHook` — `kompile-app/kompile-data/kompile-graphs/kompile-graph-change-tracking/src/main/java/ai/kompile/graphchangetracking/hook/GroundingCascadeHook.java:70` — Spring `@EventListener` on `GraphChangesetCompletedEvent` (crawl/channel), `ChannelMessageReceivedEvent` (early signal), `AgentFactAssertedEvent`; per-factSheet single-threaded executor (serializes cascades, max 8 pending tasks per factSheet); logs and skips null factSheetId events
- `IncrementalReasoningOrchestrator` — `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/IncrementalReasoningOrchestrator.java:95` — full per-factSheet re-ground (reads `FactStore` from `FactSheetKbState`, builds `PslProgram`, runs `HlMrfMapInference.solve`, materializes `InferredFact`s incrementing versions only when Δvalue > `VERSION_EPSILON`, rebuilds `JustificationIndex`, calls `KbGroundingService.markEpoch`); NOTE: current first-cut is full re-ground (O(|all facts|)); true incremental per `incremental-cascade-reasoning-design.md §3.2` is a TODO requiring `IncrementalGrounder` per factSheet state
**Input**: Spring application event carrying `factSheetId`
**Output**: updated `InferredFactStore` + new KB epoch; signals via `KbGroundingService.markEpoch`

---

## 3. GraphHydrationPipeline Orchestrator

### 3.1 Module Placement

The orchestrator lives in **`kompile-graph-reasoning`** (infra-free library) with the following split:

| Layer | Module | Content |
|-------|--------|---------|
| Lib (infra-free) | `kompile-graph-reasoning` | `HydrationStage` SPI, `GraphHydrationPipeline`, `HydrationConfig`, `HydrationResult`, `HydrationStageReport`, confidence propagation logic, fixpoint termination |
| Spring client (store wiring) | `kompile-knowledge-graph` or `kompile-app-core` | Implementations of `HydrationStage` that call Spring beans; wiring of existing stage classes; REST endpoints; cascade hook integration |

This follows the established lib-vs-client split: `GroundingCascadeHook` (Spring) → `IncrementalReasoningOrchestrator` (Spring) → `HlMrfMapInference` (lib).

### 3.2 Interface Sketch

```java
// kompile-graph-reasoning / src/.../hydration/HydrationStage.java
public interface HydrationStage {
    /** Unique identifier for this stage, used in provenance metadata. */
    String stageId();

    /**
     * Execute this stage. Implementations read from {@code input} and write
     * derived facts into the mutable working graph embedded in {@code context}.
     * Must be idempotent (re-running with the same input produces the same output).
     *
     * @param context carries the mutable working ReasoningGraph, FactStore,
     *                InferredFactStore, confidence map, and config; mutated in-place
     * @return report with derived fact counts, stage duration, and any warnings
     */
    HydrationStageReport execute(HydrationContext context);
}

// kompile-graph-reasoning / src/.../hydration/GraphHydrationPipeline.java
public final class GraphHydrationPipeline {

    /**
     * Run the full hydration chain.
     *
     * @param graph   the initial observation-only ReasoningGraph (not mutated)
     * @param config  HydrationConfig controlling stage selection, thresholds, iterations
     * @return HydrationResult containing the enriched ReasoningGraph, derived FactStore,
     *         per-stage reports, and a provenance index
     */
    public HydrationResult run(ReasoningGraph graph, HydrationConfig config);

    /**
     * Run incremental hydration for a delta of changed atom keys.
     *
     * @param graph        the current full ReasoningGraph
     * @param deltaAtomKeys atom keys added, updated, or retracted since last run
     * @param config       same config as batch; iteration cap still applies
     * @return HydrationResult for the affected subgraph
     */
    public HydrationResult runIncremental(ReasoningGraph graph,
                                          Set<String> deltaAtomKeys,
                                          HydrationConfig config);
}

// kompile-graph-reasoning / src/.../hydration/HydrationConfig.java
public record HydrationConfig(
    List<String> enabledStageIds,       // ordered list of stage IDs to run
    double thetaDerive,                 // materialization confidence threshold (default 0.5)
    double thetaHighConfidence,         // HIGH_CONFIDENCE tier threshold (default 0.7)
    int maxIterations,                  // fixpoint cap (default 5)
    double epsilon,                     // fixpoint convergence: Δ|derived| / |total| < ε
    boolean separateOverlay,            // true = derived facts in InferredFactStore only;
                                        // false = materialize into observed graph
    boolean trainEmbeddings,            // whether to run RotatELearner in Stage 5
    Map<String, Object> stageParams     // per-stage parameters
) {}

// kompile-graph-reasoning / src/.../hydration/HydrationResult.java
public record HydrationResult(
    ReasoningGraph enrichedGraph,       // the working graph after all stages
    InferredFactStore derivedFacts,     // all derived facts with versioning
    List<HydrationStageReport> stages,  // per-stage: facts derived, duration, warnings
    Map<String, Object> provenanceIndex,// atomKey → {stage, ruleId, antecedents, confidence}
    boolean converged,                  // did fixpoint terminate within maxIterations?
    int iterationsRun
) {}
```

### 3.3 Fixpoint Loop

The EntityResolution ↔ TypeInference fixpoint (NELL lesson) is implemented as:

```
iteration = 0
previous_derived_count = 0
do:
    run Stage 2 (EntityResolution with type constraints from TypeHierarchy)
    run Stage 3 (TypeInference → updated TypeHierarchy)
    delta = |derived_facts_this_iteration| - previous_derived_count
    previous_derived_count = |derived_facts_this_iteration|
    iteration++
while (delta / total_facts > epsilon AND iteration < maxIterations)
```

The full-pipeline fixpoint (for PSL iteration) follows the same structure over Stages 4–6.

---

## 4. Confidence-Propagation Model

### 4.1 Observed Fact Confidence

| Source | Initial Confidence | Notes |
|--------|--------------------|-------|
| Structured crawl (barcode/GTIN match) | 1.0 | Identity resolution, not extraction |
| LLM extraction via MultiAgentExtractionService | Extractor's score field, else 0.8 | DeepDive: treat as noisy factor |
| TikaGenericGraphExtractor (structural) | 0.7 | Structural patterns, not semantic |
| Channel/email extraction | 0.6 | Unstructured, high noise |
| Agent assertion via KbGroundingService | 0.9 | Human-in-the-loop assert |

### 4.2 Rule-Derived Fact Confidence

```
confidence_derived = min(antecedent_confidences) × rule_weight
```

Where `rule_weight` is the PSL rule weight normalized to [0,1] (divide by max weight in the rule set). This is the Łukasiewicz product rule, consistent with PSL semantics.

For OWL RL type inference (Stage 3), derived type confidence uses the minimum antecedent confidence (which matches OWL subsumption semantics: a chain of type assertions is only as strong as its weakest link).

### 4.3 Link Prediction Confidence (RotatE → Calibrated Probability)

Raw RotatE: `ScoredPrediction.distance()` — lower is more plausible, no natural probability scale.

Calibration (Knowledge Vault lesson):
```
calibrated_score = sigmoid(w × (−distance) + b)
```
where `w` and `b` are learned by fitting a logistic regression on a labeled held-out triple set (see §10 "Open Questions" for where the calibration dataset comes from in kompile).

Until a calibration dataset exists, use the heuristic:
```
calibrated_score = exp(−distance / γ)
```
where `γ` is the RotatE margin (training hyperparameter). This monotone transform places distance=0 → confidence=1.0, distance=γ → confidence=0.37.

### 4.4 PSL Soft-Truth → Probability WARNING

**PSL HL-MRF soft-truth [0,1] IS NOT a Bayesian probability.** The Łukasiewicz T-norm satisfies:
```
max(0, a + b - 1)  ≠  a × b  (Bayesian independence product)
```

For downstream agents using `KbVerifier.verify()`, the soft-truth value is used directly as the confidence score with `DEFAULT_THRESHOLD = 0.5` (see `KbVerifier.java:43`). This is appropriate for the SUPPORTED/REFUTED/UNKNOWN verdict decision but not for multiplicative signal fusion.

**The `grounding-evaluation-design.md` threshold concern applies here**: threshold sensitivity must be swept over {0.3, 0.4, 0.5, 0.6, 0.7, 0.8} (as specified in `grounding-evaluation-design.md:72`) to understand the precision/recall tradeoff before choosing θ_derive.

For fusion with Bayesian MEBN posteriors: apply Platt scaling to PSL outputs before the multiplicative fusion step. Store the calibration parameters in `HydrationConfig.stageParams`.

### 4.5 Fusion Formula

For **N independent signals** each contributing evidence for fact F:
```
P_fused(F) = 1 - Π_{i=1}^{N} (1 - P_i(F))   [noisy-OR / multiplicative]
```

For **dependent signals** (e.g., two PSL rules sharing antecedent atoms):
```
P_fused(F) = max(P_i(F))   [take the strongest evidence]
```

Signals are independent when they originate from different stages (e.g., PSL rule-derived + RotatE embedding similarity). Signals are dependent when they originate from the same PSL program (ground rules sharing atoms in the body).

### 4.6 Confidence Tiers

| Tier | Threshold | Materialization |
|------|-----------|----------------|
| HIGH_CONFIDENCE | ≥ 0.7 | Materialized immediately into `InferredFactStore` + graph overlay |
| CANDIDATE | ≥ 0.5 (θ_derive default) | Materialized as CANDIDATE-tier atoms in `InferredFactStore` |
| SPECULATIVE | 0.3–0.5 | Stored in `InferredFactStore` only, not materialized to graph |
| BELOW_THRESHOLD | < 0.3 | Discarded |

These tiers map to `AttributionConfidence` bands used in `TemporalAttributionService`: INSUFFICIENT/LOW/MODERATE/HIGH/DEFINITIVE (thresholds 0.0/0.1/0.4/0.7/0.9 per `hybrid-reasoning-implementation-plan.md:29`).

---

## 5. Observed-vs-Derived Provenance

### 5.1 Existing Provenance Keys

`GraphProvenanceKeys` (`kompile-knowledge-graph/.../domain/GraphProvenanceKeys.java:31`) defines:
- `_source` — coarse origin: "crawl", "channel:slack", etc.
- `_sourceDocumentId` — document the fact came from
- `_sourceChunkId` — chunk within the document
- `_crawlRunId` — crawl job that produced the fact
- `_extractionModel` — LLM model/provider used for extraction
- `_extractedAt` — ISO timestamp

### 5.2 Required Derivation Provenance Extensions

The hydration chain requires additional keys on derived facts (to be added to `GraphProvenanceKeys` or as a companion `HydrationProvenanceKeys` class):

```java
// Proposed additions for derived facts
public static final String DERIVED = "_derived";           // "true" for inferred facts
public static final String PRODUCING_STAGE = "_producingStage";   // stageId from HydrationStage
public static final String RULE_ID = "_ruleId";           // FolRule id or PSL rule display
public static final String EVIDENCE_NODES = "_evidenceNodes"; // comma-sep antecedent entity IDs
public static final String CALIBRATED_CONFIDENCE = "_calibratedConfidence"; // post-calibration
public static final String RAW_CONFIDENCE = "_rawConfidence"; // pre-calibration extractor score
public static final String INFERENCE_RUN_ID = "_inferenceRunId"; // UUID for this hydration run
public static final String CONFIDENCE_TIER = "_confidenceTier"; // HIGH_CONFIDENCE|CANDIDATE|SPECULATIVE
```

### 5.3 Derivation Trail

Every derived fact must carry a `DerivationTree` (defined at `kompile-graph-reasoning/src/.../fol/grounding/DerivationTree.java:57` per `reasoning-trail-explainability-design.md:22`) with:
- `atomKey` — the derived atom
- `confidence` — calibrated confidence
- `ruleApplied` — rule or model ID
- `sourceProvenance` — `GraphProvenanceKeys` map from the antecedent facts
- `children` — `List<DerivationTree>` (the antecedent derivation trail)

This `DerivationTree` is serialized to `metadataJson` as `_derivationTree` for store-agnostic portability (same pattern as existing provenance: rides in `GraphNode.getMetadata()`).

### 5.4 Tie to Existing Explainability Infrastructure

`reasoning-trail-explainability-design.md §3` specifies a `ReasoningTrail` record that unifies `DerivationTree`, `EntailmentRecord`, `VerifyResult.evidence`, and `activatedRules`. Hydration provenance feeds directly into `ReasoningTrail` when an agent calls `ask_graph_verify` or the frontend calls `KbGroundingController /explain` — the `_producingStage` and `_ruleId` metadata become the `activatedRules` and `evidence` fields.

---

## 6. Messy / Incomplete-Data Robustness

### 6.1 Entity Resolution Under Uncertainty

`EntityResolutionService` (Stage 2) uses a 0.85 Levenshtein threshold by default (`EntityResolutionService.java:53`). `GraphCompactionService` combines: title normalization + alias overlap + Levenshtein + embedding cosine ≥ 0.88 + attribute-behavior scoring (`GraphCompactionService.java:64`).

For the hydration chain:
- Resolution confidence = weighted combination of individual signal scores
- Uncertain merges (0.7 < score < 0.85) are tentative: flagged with `_tentativeMerge = "true"` in metadataJson
- Tentative merges are re-evaluated after type inference (Stage 3): if the types conflict, the merge is reversed via `BeliefReviser.retract` on the merge decision atom
- Barcode/GTIN identity resolution (Stage 2, `BarcodeIdentityGraphService`) is treated as near-certain (confidence 0.99) because GTIN check-digit validation provides cryptographic-strength identity

### 6.2 TMS / Contradiction Resolution

When `ContradictionDetector.detectHard()` returns violations:
1. Identify the two conflicting facts (same atom key, values 1.0 vs 0.0)
2. Compare `_calibratedConfidence` values: retract the lower-confidence fact via `BeliefReviser.retract(lowerConfidenceAtomKey, factStore, justificationIndex)`
3. Log the retraction with both original provenance keys for audit
4. Re-run `HlMrfMapInference.solve()` over the now-consistent program (bounded to the affected neighborhood)

For soft violations (constraint distance > threshold but no hard fact conflict):
- Apply penalty to the violating atoms' confidence: `new_confidence = old_confidence × (1 - violation_distance)`
- Record as `_softViolation = "true"` in metadata

### 6.3 Partial Evidence

PSL handles partial truth natively: an observed atom with value 0.6 (partial evidence for the fact being true) contributes its soft-truth value to PSL rules that fire over it. The Łukasiewicz T-norm `max(0, a + b - 1)` produces lower soft-truth for the conclusion when antecedents are partially true — this is the correct behavior.

For MEBN: missing random variables (entities with no observed timestamp, no type, no attributes) cause the `SSBNGenerator` to produce nodes with the MFrag's prior distribution. This is correct Bayesian behavior under missing data. The resulting posterior uncertainty is reflected in the `MebnInferenceService.infer()` output as lower confidence posteriors.

### 6.4 Conflicting Extractor Fusion

DeepDive lesson: treat each extractor as an independent noisy factor.

Implementation in kompile:
- `MultiAgentExtractionService` aggregates multiple `RelationExtractionAgent` outputs via `DefaultMultiAgentGraphBuilder` with a `GraphMergeStrategy`
- For the hydration chain, the merge strategy should be **multiplicative combination** (noisy-OR, §4.5) rather than majority vote or last-write-wins
- When two extractors produce conflicting entity types for the same surface form, lower the confidence of both typed facts proportionally: `confidence_A_given_conflict = confidence_A / (confidence_A + confidence_B)`

---

## 7. Batch vs. Incremental

### 7.1 Batch Hydration

**Trigger**: post-crawl (when `GraphBuildCompletedEvent` fires, see `production-kb-integration-design.md §1.1`) or manual REST call.
**Execution**: full chain DAG in topological order; EntityResolution ↔ TypeInference fixpoint; then full PSL solve over all facts.
**Termination**: fixpoint: `Δ|derived_facts| / |total_facts| < ε` (default ε = 0.01) OR `iterations ≥ maxIterations` (default 5).

**Convergence guarantee** (from `incremental-cascade-reasoning-design.md §3.2`): for acyclic rule sets, the Datalog fixpoint converges in at most `depth(ruleset)` iterations. For PSL programs (not pure Datalog), the HlMrfMapInference convergence tolerance `HlMrfMapInference.DEFAULT_TOLERANCE` governs the inner solve; the outer fixpoint tracks changes in the materialized fact set.

### 7.2 Incremental Hydration

**Trigger**: `GroundingCascadeHook.onChangesetCompleted` (Graph changeset) or `onAgentFactAsserted` (KB assert).
**Algorithm**: hybrid semi-naive forward + justification-based retraction per `incremental-cascade-reasoning-design.md §2.2`:
- **Delta set Δ**: atom keys added/updated/deleted in the changeset
- **Affected atom set A(Δ)**: Δ ∪ `JustificationIndex.atomsDependingOnFact(key)` for each key in Δ
- **Unsupported set U(Δ)**: atoms for which the deleted key was the sole justification (`JustificationIndex.solelyDependentOn(key)`) → retract via `BeliefReviser`
- **Weakened set W(Δ) = A(Δ) \ U(Δ)**: retain but recompute via bounded re-ground
- Re-run `HlMrfMapInference` over the affected subgraph only (depth-2 neighborhood of Δ atoms)

**Current implementation note**: `IncrementalReasoningOrchestrator` currently implements full per-factSheet re-ground (correct but O(|all facts|)). The true incremental is a planned follow-up requiring `IncrementalGrounder` per `FactSheetKbState`. The `GraphHydrationPipeline.runIncremental()` method in the proposed orchestrator should also implement full re-ground in its first cut, with incremental optimization as Phase 4 (see §9).

**Concurrency model** (from `GroundingCascadeHook.java:48`):
- Per-factSheet single-threaded executor serializes cascades for the same factSheet
- Different factSheets cascade in parallel
- Maximum 8 pending tasks per factSheet (`MAX_PENDING_PER_FACTSHEET = 8`); overflow tasks are dropped with a warning

### 7.3 Bounded Fixpoint

The `VERSION_EPSILON` constant in `IncrementalReasoningOrchestrator` (line ~100) governs the inner PSL convergence. The outer hydration fixpoint uses `HydrationConfig.epsilon` on the derived-fact delta fraction:
```
converged = (|derived_facts_iter_k| - |derived_facts_iter_{k-1}|) / max(1, |total_facts|) < epsilon
```

---

## 8. The Forks — Flag and Recommend

### Fork A: Derivation Aggressiveness (θ_derive Threshold)

**Option 1 — High-confidence only**: θ_derive = 0.7. Only facts with calibrated confidence ≥ 0.7 are materialized. Conservative: low false-positive rate, high precision, lower recall.

**Option 2 — All plausible**: θ_derive = 0.3. Materialize all facts with calibrated confidence ≥ 0.3. Aggressive: catches more missing links, higher recall, more false positives.

**RECOMMENDATION**: Two-tier overlay.
- HIGH_CONFIDENCE tier: θ ≥ 0.7, materialized as `EdgeProvenance.INFERRED` edges immediately available to agents via `KbVerifier.verify()`.
- CANDIDATE tier: 0.5 ≤ θ < 0.7, stored in `InferredFactStore` but not in the graph overlay — agents must explicitly query candidates.
- Default θ_derive = 0.5 for materialization (HIGH + CANDIDATE go to `InferredFactStore`; only HIGH goes to graph edges).

**TRADEOFF**: Lower threshold improves recall for downstream agents but risks false positives accumulating in the KB (compounding errors on future runs). The evaluation harness from `grounding-evaluation-design.md §2.1` should be used to measure precision/recall at each threshold before changing the default.

**DEPENDENCY**: threshold choice depends on whether a calibration dataset exists (see §10 Open Question 1). Without calibration, PSL soft-truth [0,1] and probability are conflated — the threshold 0.5 is a conventional choice, not an empirically validated one.

### Fork B: Materialization Layering (Separate Overlay vs. Embedded)

**Option 1 — Separate overlay**: derived facts live in `InferredFactStore` only; they are NOT written back as `EdgeProvenance.INFERRED` edges in the `@Primary` store unless explicitly triggered by `InferredFactGraphMaterializer.materialize()`. The observed graph stays pure.

**Option 2 — Embedded**: derived facts are materialized into the `@Primary` matrix/vector store as `EdgeProvenance.INFERRED` edges immediately after derivation.

**RECOMMENDATION**: Separate overlay (Option 1) with **on-demand materialization** via `InferredFactGraphMaterializer`. This is already the existing design (`InferredFactGraphMaterializer.java:49` — "persists probabilistic inferences back into the knowledge graph so they accumulate across sessions").

**RATIONALE**:
- Reversibility: `BeliefReviser.retract` operates on the `InferredFactStore`; if derived facts are embedded, retracting requires deleting graph edges which is more expensive
- Provenance cleanness: the observed graph (crawl-produced) is a clean source of truth; embedding derived facts mixes layers and makes provenance queries harder
- Avoids compounding: derived facts in the graph become inputs to the next crawl's extractor, potentially compounding errors across hydration runs
- The portability path (`graph-as-asset`) already handles `EdgeProvenance.INFERRED` edges traveling via snapshots (`InferredFactGraphMaterializer.java:48`)

**TRADEOFF**: separate overlay adds complexity — agents must query both the graph AND `InferredFactStore`; `ConjunctiveQueryEngine` operates on `InferredFactStore`, while graph queries (PPR, community detection) operate on the `@Primary` store. This gap requires a unified query facade (not yet built, noted as an open gap in `production-fol-kb-agent-grounding-gaps.md`).

### Fork C: Iteration Depth (Single-Pass vs. Fixpoint)

**Option 1 — Single-pass**: run each stage once in topological order. Fast, predictable, but misses derived facts that enable further derivations (e.g., a type derived in Stage 3 that enables a new PSL rule to fire in Stage 4 on the next pass).

**Option 2 — Full fixpoint**: iterate until Δ|derived_facts| / |total_facts| < ε. Correct in theory; potentially expensive on large graphs.

**RECOMMENDATION**: Fixpoint with `maxIterations = 5`, `epsilon = 0.01`.

**RATIONALE**: Most KBC fixpoint iterations converge in 2–3 rounds for well-structured rule sets (DeepDive observation: convergence after 3 iterations on typical web-scale KBs). The `maxIterations = 5` cap ensures termination in adversarial cases. The `epsilon = 0.01` threshold (1% change in derived fact count) is empirically motivated by the PSL convergence tolerances already in the codebase (`HlMrfMapInference.DEFAULT_TOLERANCE`).

**TRADEOFF**: fixpoint iteration is O(maxIterations × stage_cost). For large graphs (>100K nodes), each iteration of Stage 4 (PSL solve) can be expensive. Mitigation: run the fixpoint only over Stages 2–4 (EntityResolution ↔ TypeInference ↔ RuleDerivation); Stages 5–10 run once after convergence.

---

## 9. Phased Build Plan

### Phase 1: First Buildable Slice (Core Pipeline + Provenance)

**Scope**: Wire existing stages into a `GraphHydrationPipeline` orchestrator; add confidence/provenance model; implement batch mode.

**Deliverables**:
1. `HydrationStage` SPI + `HydrationContext` + `HydrationConfig` + `HydrationResult` in `kompile-graph-reasoning/.../hydration/`
2. `GraphHydrationPipeline.run(ReasoningGraph, HydrationConfig)` — calls existing stages in topological order with the fixpoint loop over Stages 2–4
3. `HydrationProvenanceKeys` — extension of `GraphProvenanceKeys` with `_derived`, `_producingStage`, `_ruleId`, `_evidenceNodes`, `_calibratedConfidence`, `_confidenceTier`
4. Adapter implementations in `kompile-knowledge-graph` wiring Stage 2 (`EntityResolutionService`, `GraphCompactionService`) + Stage 3 (`OwlRlReasoner`, `TypeHierarchy`) + Stage 4 (`FolInferenceService`) + Stage 9 (`ContradictionDetector`, `BeliefReviser`) + Stage 10 (`InferredFactGraphMaterializer`, `InferredFactStore`)
5. `GraphToFactStoreProjector` already exists and handles the graph → FactStore projection (no changes needed)
6. REST endpoint: `POST /api/graph/hydrate/{factSheetId}` — triggers batch hydration; returns `HydrationResult` summary

**Out of scope for Phase 1**: RotatE training (Stage 5), MEBN global inference (Stage 6 full), Platt calibration, incremental integration

### Phase 2: Link Prediction Integration + Calibration

**Scope**: Integrate `RotatELearner` + `LinkPredictor` + `EmbeddingPslEvidence` as Stage 5; add Platt-scaling calibration.

**Deliverables**:
1. `EmbeddingHydrationStage` — wraps `RotatELearner` training + `LinkPredictor.predictTails` + `EmbeddingPslEvidence.observe` injection; configurable via `HydrationConfig.stageParams` (`embeddingDim`, `margin`, `epochs`, `topK`, `similarityThreshold`)
2. `PlattCalibrator` — logistic regression fit on a held-out triple set; `calibrate(rawScore) → probability`; stored as `PlattParams` in `HydrationConfig.stageParams`; heuristic fallback `exp(−distance / γ)` when no calibration data
3. Fusion step in `GraphHydrationPipeline` — multiplicative noisy-OR fusion of calibrated PSL + calibrated RotatE scores; stores `_calibratedConfidence` in provenance

### Phase 3: Abduction / Causal Chain + Temporal Enrichment

**Scope**: Integrate `TemporalAttributionService` (Stage 7) and `TemporalView` / `AllenRelation` (Stage 8) into the hydration pipeline; derive latent cause nodes.

**Deliverables**:
1. `CausalHydrationStage` — wraps `TemporalAttributionService.attribute(graph, query)` for each observed "effect" entity; emits derived CAUSES/CONTRIBUTES_TO edges above causal confidence threshold; creates CANDIDATE latent-cause nodes
2. `TemporalEnrichmentStage` — wraps `TemporalView.between(graph, from, to)` + `AllenRelation.compute(a, b)` for co-temporal entity pairs; emits derived ALLEN_BEFORE/ALLEN_MEETS/ALLEN_OVERLAPS edges as observed atoms for PSL rules
3. Integrate derived causal edges back into `InferredFactStore` with `_producingStage = "causal"` and `_confidenceTier` based on `AttributionConfidence` bands

### Phase 4: Full Incremental Cascade Integration + True Fixpoint

**Scope**: Replace full re-ground in `IncrementalReasoningOrchestrator` with true incremental cascade; integrate `GraphHydrationPipeline.runIncremental()`.

**Deliverables**:
1. `IncrementalGrounder` per `FactSheetKbState` (as described in `IncrementalReasoningOrchestrator.java:75` TODO) — stores the grounder alongside the `FactStore` so predicate-scoped re-grounding is possible
2. `GraphHydrationPipeline.runIncremental(graph, deltaAtomKeys, config)` — hybrid semi-naive + JustificationIndex retraction per `incremental-cascade-reasoning-design.md §3.2`
3. Update `GroundingCascadeHook` to call `pipeline.runIncremental()` instead of `orchestrator.runFullReground()`
4. True fixpoint convergence tracking across incremental runs (epoch-based)

### Phase 5: Contradiction / TMS Full Integration + Belief Revision

**Scope**: Make contradiction detection and belief revision first-class steps in the hydration pipeline.

**Deliverables**:
1. `ContradictionResolutionStage` — wraps `ContradictionDetector.detect(result, threshold)` + `BeliefReviser.retractAndRerun(atomKey, factStore, index, program)` for all detected contradictions; logs retractions with full provenance
2. Auditable retraction log — JSONL at `~/.kompile/sessions/<id>/retractions.jsonl` per the pattern established by `durable judgements JSONL` in the enforcer system
3. REST endpoint: `GET /api/graph/hydrate/{factSheetId}/contradictions` — returns currently-active contradictions with the winning and losing facts and their provenance
4. Cross-extractor conflict policy — configurable via `HydrationConfig.stageParams`: `ON_CONFLICT = retract_lower_confidence | keep_higher | keep_both_with_confidence | log_and_pause`

---

## 10. Open Questions

### OQ-1: Calibration Dataset for Platt Scaling

**Question**: Platt calibration (`sigmoid(w × (−RotatE_distance) + b)`) requires a labeled held-out triple set of (entity, relation, entity) triples labeled POSITIVE/NEGATIVE. Where does this come from in kompile?

**Options**:
- A) Use `GraphCompactionService` merge decisions as distant supervision: merged entities confirm positive identity links; rejected merges confirm negative links
- B) Use crawl-derived facts as positive examples; randomly sampled non-facts as negatives (closed-world assumption)
- C) Use `KbVerifier.verify()` on a curated set of known facts from the ontology as positives + known-false negatives from `ContradictionDetector` output

**Recommendation**: Option B first (simplest to automate from crawl data), with Option A for identity relation calibration (where merge decisions provide reliable signal).

### OQ-2: MEBN SSBN Scalability for Large Graphs

**Question**: `SSBNGenerator.generate()` (called by `MebnInferenceService.infer`) is O(exponential in the SSBN size). For graphs with >5000 nodes, global MEBN inference is intractable.

**Mitigation options**:
- A) Limit MEBN to targeted subgraph: only run SSBN inference for the depth-2 neighborhood of the query entity (same approach as `IncrementalReasoningOrchestrator`'s bounded re-ground)
- B) Approximate SSBN generation: limit `SSBNGenerator` to the top-K most influential MFrags per the graph community structure
- C) Fall back to PSL for global inference; use MEBN only for targeted calibration of specific high-value entities

**Recommendation**: Option A — scoped MEBN already matches the grounding pattern (`KnowledgeGraphReasoningAdapter.maxDepth`). Global MEBN is reserved for small factSheets.

### OQ-3: Graph Overlay Storage Backend

**Question**: Where does the SEPARATE overlay of derived facts live in the `@Primary` matrix/vector store? The `InferredFactStore` (in-memory per `FactSheetKbState`) is not durable across restarts.

**Current state**: `InferredFactGraphMaterializer` writes derived facts as `EdgeProvenance.INFERRED` edges into the `@Primary` store — this IS durable. The in-memory `InferredFactStore` is the working buffer that gets materialized.

**Gap**: `InferredFactStore` implementations must survive restart. The `InMemoryInferredFactStore` does not persist. Need a persistent implementation backed by `metadataJson` on a dedicated derived-facts graph node, or backed by a separate JSONL sidecar (like the graph portability Phase-1 approach).

### OQ-4: Batch Hydration Trigger

**Question**: Who triggers batch hydration? Three options:
- A) Post-crawl hook: `GroundingCascadeHook.onChangesetCompleted` already handles this for the incremental case; extend to call `GraphHydrationPipeline.run()` after a crawl completes
- B) Manual REST: `POST /api/graph/hydrate/{factSheetId}` — user-triggered, useful for experimentation
- C) Scheduled: cron-triggered nightly hydration of all factSheets

**Recommendation**: All three, configured per factSheet: `hydrateMode = POST_CRAWL | MANUAL | SCHEDULED` in `graph-extraction-config.json`. Default to POST_CRAWL for crawled factSheets, MANUAL for manually-asserted KB factSheets.

### OQ-5: Derived Edge Loop Prevention

**Question**: After `InferredFactGraphMaterializer` writes `EdgeProvenance.INFERRED` edges into the `@Primary` store, will the next crawl or hydration run re-extract those derived edges as if they were observed facts? This would create compounding errors.

**Mitigation**: `GraphToFactStoreProjector` projects `GraphEdge`s into the `FactStore` with the edge confidence as soft-truth value. Derived edges (confidence < 1.0, `EdgeProvenance.INFERRED`) should be projected with their `_calibratedConfidence` value, not as hard facts. The PSL solver naturally handles these as soft evidence, preventing overfitting to previously-derived facts.

The `_derived = "true"` provenance key should cause `GraphToFactStoreProjector` to project inferred edges as target atoms (not observed evidence), so the next hydration run can update them rather than treating them as ground truth.

---

## Appendix: File Path Index

| Engine | File Path | Key Class / Interface |
|--------|-----------|----------------------|
| Graph ingest (structural) | `kompile-app/kompile-data/kompile-loaders/kompile-loader-tika/src/main/java/ai/kompile/loader/tika/TikaGenericGraphExtractor.java:52` | `TikaGenericGraphExtractor` |
| Graph ingest (multi-agent) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/agent/MultiAgentExtractionService.java:52` | `MultiAgentExtractionService` |
| Provenance keys | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/domain/GraphProvenanceKeys.java:31` | `GraphProvenanceKeys` |
| Entity resolution (pre-persist) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/EntityResolutionService.java:48` | `EntityResolutionService` |
| Graph compaction (post-persist) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/resolution/GraphCompactionService.java:60` | `GraphCompactionService` |
| OWL 2 RL type inference | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/mebn/type/owl/OwlRlReasoner.java:71` | `OwlRlReasoner` |
| Type hierarchy | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/mebn/type/TypeHierarchy.java` | `TypeHierarchy` |
| FOL → PSL inference | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/FolInferenceService.java:73` | `FolInferenceService` |
| PSL HL-MRF solver | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/psl/HlMrfSolver.java:30` | `HlMrfSolver` (interface) |
| FOL + MEBN entailment | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/EntailmentEngine.java:55` | `EntailmentEngine` |
| RotatE KGE learner | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/embedding/learn/RotatELearner.java` | `RotatELearner` |
| Link prediction | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/embedding/learn/LinkPredictor.java:44` | `LinkPredictor` |
| Embedding → PSL bridge | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/embedding/EmbeddingPslEvidence.java:56` | `EmbeddingPslEvidence` |
| MEBN / SSBN inference | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/MebnInferenceService.java:59` | `MebnInferenceService` |
| Temporal attribution / causal | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/attribution/TemporalAttributionService.java` | `TemporalAttributionService` |
| Temporal graph filter | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/model/TemporalView.java:57` | `TemporalView` |
| Allen interval algebra | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/model/AllenRelation.java:39` | `AllenRelation` |
| Contradiction detection | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/ContradictionDetector.java:36` | `ContradictionDetector` |
| Belief revision / TMS | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/BeliefReviser.java:34` | `BeliefReviser` |
| Justification index | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/tms/JustificationIndex.java:33` | `JustificationIndex` |
| Inferred fact materialization | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/InferredFactGraphMaterializer.java:51` | `InferredFactGraphMaterializer` |
| Inferred fact store (SPI) | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/InferredFactStore.java:29` | `InferredFactStore` |
| Graph → FactStore projection | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/GraphToFactStoreProjector.java:60` | `GraphToFactStoreProjector` |
| Incremental trigger (Spring) | `kompile-app/kompile-data/kompile-graphs/kompile-graph-change-tracking/src/main/java/ai/kompile/graphchangetracking/hook/GroundingCascadeHook.java:70` | `GroundingCascadeHook` |
| Incremental re-ground (Spring) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/IncrementalReasoningOrchestrator.java:95` | `IncrementalReasoningOrchestrator` |
| Reasoning graph (lib) | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/model/ReasoningGraph.java:36` | `ReasoningGraph` (interface) |
| KB verifier | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/grounding/KbVerifier.java:37` | `KbVerifier` |
| Conjunctive query engine | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/grounding/ConjunctiveQueryEngine.java:52` | `ConjunctiveQueryEngine` |
| Concurrent fact store | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/grounding/ConcurrentFactStore.java:55` | `ConcurrentFactStore` |
