# Crawl → Graph → Reasoning Architecture

> End-to-end overview of how Kompile turns a pile of documents into a calibrated, learning knowledge
> graph: the crawl pipeline, the graph data model, the confidence/evidence model, the hybrid
> probabilistic+logical reasoner, the learning algorithms (PSL/MEBN weight learning + knowledge-graph
> embeddings), and how it is all surfaced. Class names are real; see `kompile-graph-reasoning`
> (the infra-free reasoning library), `kompile-knowledge-graph` (Spring/JPA clients), and
> `kompile-crawl-graph` (the crawl pipeline).

---

## 0. Design philosophy

- **Infra-free engine, thin clients.** All learning/reasoning lives in `kompile-graph-reasoning`
  (deps: nd4j-api, jackson, slf4j, lombok only). Spring/JPA/HTTP live in client modules that adapt
  the live graph store onto the engine's store-agnostic `ReasoningGraph` primitive.
- **One confidence currency.** Every fact carries an `Opinion` (subjective logic = Beta-Bernoulli
  MAP). Extraction, reasoning, embeddings, and pruning all read and write the same representation.
- **Hybrid, not single-paradigm.** Soft logic (PSL/HL-MRF), discrete Bayesian inference, multi-entity
  Bayesian networks (MEBN), OWL-RL entailment, FOL/Datalog materialization, and learned embeddings
  are composed — each is evidence to the others, not a competing silo.
- **Learning is continuous and joint.** Rule weights, MEBN parameters, and entity embeddings are
  **co-trained in one step against a single hybrid-reasoner-ranked consensus signal** (not three
  disconnected learners), and fact confidence *climbs* with corroboration.

---

## 1. The crawl → graph pipeline

Entry: `POST /api/unified-crawl/start` → `UnifiedCrawlGraphServiceImpl.executeJob()`. The pipeline is
**modular** (`CrawlStepPlan` + `CrawlPipelineStepRegistry`): with no explicit selection every step
runs; `enabledSteps` whitelists a subset (plus transitive hard-deps + foundational steps); `archivedSteps`
*subtract* from the default (archiving one step never silently skips the rest) and can be resumed later.

```
LOADING ─ CONVERTING ─ (PDF route) ─ ROUTING ─ GRAPH_PREP ─ CHUNKING ─ GRAPH_EXTRACTION
   docs      text                    by type   rule-based    text       LLM entities/rels
                                                 + email      windows
   └────────────────────────────────────────────────────────────┬───────────────────────┘
                                                                  ▼
        ENTITY_RESOLUTION ── EDGE_COMPUTATION ── VECTOR_INDEXING ── ENRICHMENT
        dedup/compaction     cross-doc rels      embeddings        reason + learn + prune
```

| Phase | What happens | Key classes |
|---|---|---|
| **LOADING / CONVERTING / ROUTING** | Load sources, extract text (Tika), classify + route by content type | `CrawlSourceLoadingService`, `TextConversionService`, `ContentTypeRouter` |
| **Per-source preprocessing** | Email/Office/Spreadsheet/Web normalization before extraction | `*NormalizationPreprocessor` |
| **GRAPH_PREP** | Deterministic, no-LLM graph extraction (document structure, tables, email headers) | `RuleBasedDocumentGraphExtractor`, `TikaGenericGraphExtractor`, `EmailGraphExtractor` |
| **GRAPH_EXTRACTION** | LLM entity/relationship extraction over chunks (and `GraphConstructor` path) | `GraphExtractionOrchestrator`, `LlmKnowledgeGraphBuilder` |
| **ENTITY_RESOLUTION** | Merge co-referent entities; barcode/UPC identity; RESOLVES_TO edges | `GraphCompactionService`, `BarcodeIdentityGraphService` |
| **VECTOR_INDEXING** | Chunk embeddings into the vector store | embedding model + vector store |
| **ENRICHMENT** | The reasoning + learning + pruning pass (§4) | `GraphHydrationOrchestrator` |

### Confidence is stamped at the seam, not invented later

Every persisted node/edge gets a real `Opinion` at extraction time via **`ExtractionConfidenceStamper`**,
which writes `_opinion`, `_basisType`, `_sourceTrust`, `_evidencePos`, `_validFrom`. No path emits a hard
`1.0` default. Source trust comes from `SourceTrustResolver` (`trustEmailFrom=0.95` … `trustWebScrape=0.45`),
the Beta prior strength `W` from `KbConfig` per `BasisType`. Structural facts (e.g. *person → email →
organization*) are asserted certain-at-t by `StructuralFactAssertionService`.

---

## 2. The graph as a data structure

- **Store-agnostic seam.** The live graph is the `@Primary` matrix/vector store; everything that must
  travel (provenance, opinions, ontology tags, basis types) rides in `GraphNode.getMetadata()` /
  `metadataJson`, not JPA columns, so it survives `git clone` and store swaps.
- **Dual store.** (1) the **graph store** (nodes/edges, the asset) and (2) the **`InferredFactStore`**
  (PSL soft-truth atoms + accumulated Beta evidence), populated during ENRICHMENT.
- **`ReasoningGraph`** (`MutableReasoningGraph`) is the engine's projection: `GraphEntity` (id, type,
  label, `weight`, `embedding`) + `GraphRelation` (src, dst, type, `weight`). The
  `KnowledgeGraphReasoningAdapter` builds a bounded subgraph from the live store on demand.

---

## 3. The confidence / evidence model

The unit of confidence is the subjective-logic **`Opinion(belief, disbelief, uncertainty, baseRate)`**
with `expectation() = belief + baseRate·uncertainty`. `Opinion.fromBetaEvidence(pos, neg, baseRate, W)`
makes this a **Beta-Bernoulli MAP estimate**: `belief = pos/(pos+neg+W)`, where `W` is the prior
pseudocount (regularization) — MLE is the `W→0` limit.

- **Per-basis priors (`BasisType`).** `STRUCTURAL` (W≈0.1, near-certain from one observation),
  `LLM_EXTRACTION`/`PSL_INFERENCE`/`MEBN_INFERENCE`/`CORROBORATION` (W≈2.0, slow-climb), `ASSERTED` (W=0).
- **Bands, not percentages.** `StrengthBand` (ESTABLISHED → HIGH → PROBABLE → SPECULATIVE → SUPPRESSED)
  is the human-facing rank; the UI shows ranks + "true-at-t", never raw %.
- **Climb with corroboration.** `FactPromotionTracker` accumulates `evidencePos/evidenceNeg` across
  cascades (the 6-arg Beta path) so a fact reaches HIGH after ~8 corroborations — confidence is earned,
  not assigned once.
- **Open-world for sparse graphs.** `SparsityMetrics` + `SparseEvidenceHelper` (via `SparseGraphAssessor`):
  in a structurally sparse graph (e.g. a spreadsheet) an *absent* fact resolves to a **vacuous, base-rate
  Opinion** (uncertainty, not disbelief); in a dense graph closed-world disbelief applies.
- **Prior-based pruning.** `OpinionPruner` + `PrunePolicy` (thresholds in KbConfig) drop only
  INFERRED/AMBIGUOUS edges whose belief/uncertainty/expectation fall below configured bounds.

---

## 4. Enrichment: the hybrid reasoner

`GraphHydrationOrchestrator` runs the ENRICHMENT stages: **DERIVATION → PRUNE_COMPACT →
ONTOLOGY_CONFORMANCE → HEALTH / LEARNING_METRICS**. DERIVATION is the reasoning core
(`IncrementalReasoningOrchestrator.doReground`):

This is the **actual** per-cascade sequence (`doReground`, line refs are real):

```
STEP 0   project live graph → FactStore           (GraphToFactStoreProjector.project)
STEP 2/3 build PSL program from the FactStore      (buildProgramFromFactStore: one soft propagation
                                                     rule per observed predicate, at pslDefaultRuleWeight)
STEP 3b  load project-level *.psl rules
STEP 3b-ont inject ontology DOMAIN/RANGE → PSL      (OntologyToPslRuleCompiler, if an ontology is bound)
STEP 3c  reload persisted learned rule weights      (cascadeWeightStore — warm-start)
STEP 4   HL-MRF MAP solve                           (HlMrfMapInference → Scalar | Tensor[ND4J/GPU])
STEP 5   entail inferred facts → dual store         (EntailmentEngine → InferredFactStore)
STEP 5c  promote bands + ACCUMULATE Beta evidence   (FactPromotionTracker, 6-arg sourceTrust path)
─────────  JOINT TRAINING (HybridConsensusTrainer) — the part that was previously fragmented ──────
  build a ReasoningGraph from the FactStore
  (throttle) co-train embeddings INTO it            (EmbeddingLearner.learnInto → semantic signal)
  rank entities with the HybridReasoner             (structural ⊕ semantic → ranked response)
  derive ONE consensus signal                       (observed targets pulled toward the ranking)
STEP 5b  co-train PSL weights on the consensus       (PslWeightLearningService.updateOnBatch)
STEP 9   co-train MEBN params on the SAME consensus  (MebnWeightLearner.learn, throttled)
STEP 6/7 rebuild JustificationIndex; TMS contradiction scan + retraction
```

**What changed (and why it matters).** Until recently STEP 5b and STEP 9 trained *independently* —
each re-derived its own observed targets — and embeddings were a wholly separate offline job, while
the `HybridReasoner` was used only for explanation. Now, on the throttle, all three learned models
co-train against **one** `HybridReasoner`-ranked consensus signal: embeddings feed the hybrid's
semantic score, the hybrid ranks entities, and that single ranked signal supervises PSL *and* MEBN.
Off-throttle cascades keep the cheap PSL warm-start (the hybrid rank is a full structural inference,
so it is gated to the throttle). The blend weight is `KbConfig.hybridConsensusWeight`. The reusable
primitive is `learning.HybridConsensusTrainer` (it can also run the full retrain→re-rank loop).

The library offers several reasoning paradigms, composed rather than chosen:

### 4.1 PSL / HL-MRF (soft logic) — the workhorse
`PslProgram` of weighted Łukasiewicz rules (`PslRule`, `PslAtom`). MAP minimizes the convex energy
`E(y) = Σ_r w_r · d_r(y)^{p_r}` over soft-truth atoms `y ∈ [0,1]` by **projected gradient descent**
(`HlMrfMapInference`). Solver is chosen by size: plain-Java `ScalarHlMrfInference` for the small
subgraphs that dominate, ND4J-vectorized `TensorHlMrfInference` (GPU-capable) for large programs; also
`SgdHlMrfInference` and `AdmmHlMrfInference`. Marginals + uncertainty via `PslMarginalInference` /
`PslUncertaintyAdapter`; active-learning value via `PslInformationGainApproximator`.

### 4.2 Discrete Bayesian inference
`BayesianNetwork` / `BayesianNode` built from the graph by `GraphBayesianNetworkBuilder`; **exact
variable elimination** posteriors with calibrated uncertainty (`BayesianUncertaintyEstimator`). This is
the discrete counterpart to the soft-truth PSL path.

### 4.3 MEBN — Multi-Entity Bayesian Networks
Templated Bayesian fragments (MFrags) instantiated over graph entities, with **noisy-OR** combination
of parent influences. `MebnInferenceService` does inference; `MebnWeightLearner` learns per-MFrag edge
strengths by finite-difference gradient (run every N=10 cascades), persisted as
`<dataDir>/graph/reasoning/<factSheet>/mebn-weights.json` (composite keys `mfrag|parent->child`,
`MebnWeightSerializer` / `MebnWeightPersistenceAdapter`). MEBN handles relational/first-order uncertainty
the propositional Bayesian net can't template.

### 4.4 OWL-RL entailment (ontology reasoning)
`OwlRlReasoner` + `OwlRlRuleCompiler` apply OWL-RL rules over the typed graph; `OwlTurtleReader/Writer`
+ the full `Owl*` class model give a real ontology layer. The crawl tie-in compiles a bound
`OntologySchema`'s DOMAIN/RANGE axioms into soft PSL rules (`OntologyToPslRuleCompiler`) so ontology
structure constrains the MAP solve.

### 4.5 FOL / Datalog materialization
`RecursiveQueryEngine` (semi-naive Datalog with stratification), `ForwardChainingMaterializer`,
`ConjunctiveQueryEngine`, and `EntailmentEngine` materialize derived facts; `DefaultKbVerifier`
answers verify/query/explain with open-world `UNKNOWN` for absent atoms; `ContradictionDetector` (a TMS)
flags inconsistencies.

### 4.6 Causal / attribution
`CausalEdgeType` / `CausalHop` + the `attribution` package provide event attribution ("explain why?"):
sensitivity analysis, MPE, predict, and What-If over the Bayesian/MEBN/PSL machinery.

### 4.7 The `HybridReasoner` — structural × semantic
Ranks entities by a weighted blend of a **structural** score (PSL/HL-MRF soft-truth activation, *or* a
Bayesian variable-elimination posterior) and a **semantic** score (cosine similarity of each entity's
learned embedding to a query vector). `(structuralWeight·structural + semanticWeight·semantic)` —
set `semanticWeight=0` for pure structure, or blend for neuro-symbolic ranking.

---

## 5. Learning algorithms

All weight/parameter learners are **composed by `HybridConsensusTrainer`** into one joint step (§4):
on the throttle they co-train against a single `HybridReasoner`-ranked consensus signal rather than
each on its own re-derived targets. PSL still runs a cheap warm-start every cascade.

| Learner | Learns | Method | Where |
|---|---|---|---|
| `StructuredPerceptronLearner` (via `PslWeightLearningService`) | PSL rule weights | structured perceptron, **MAP** (L2/Gaussian prior), **band-aware per-rule prior means** (`kbRuleWeight{Established,High,Probable,Speculative}Mean`) | warm-start every cascade; co-trains on the hybrid consensus on the throttle |
| `PseudolikelihoodLearner` | PSL rule weights | pseudolikelihood gradient | alternate weight learner |
| `MebnWeightLearner` | MEBN noisy-OR strengths | finite-difference gradient | co-trained on the hybrid consensus, throttled (N=10) |
| `FactPromotionTracker` | fact confidence | Beta-Bernoulli evidence accumulation → band climb | every cascade |
| `Node2VecLearner` | entity embeddings | biased 2nd-order random walks + skip-gram negative sampling (SGNS) | KGE job |
| `RotatELearner` | entity+relation embeddings | RotatE (complex rotation `e^{iθ}` per relation) | KGE job |
| `SameDiffEmbeddingTrainer` | embedding matrices | mini-batch SGNS on **ND4J SameDiff autodiff** (GPU-capable) | shared substrate |

All weight/parameter learning is **MAP-regularized** (priors, not just MLE) so it behaves at cold start
and isn't dominated by a single noisy cascade.

### 5.1 How a thing becomes a fact / a rule — with a probability

**A FACT** carries a probability at every stage; it is never a bare boolean:

1. **Extraction.** An edge is persisted with a Beta `Opinion` by `ExtractionConfidenceStamper`:
   `Opinion.fromBetaEvidence(pos, neg, baseRate, W)` where `pos` = the source trust
   (`SourceTrustResolver`, e.g. 0.60 for LLM extraction), `W` = the per-`BasisType` prior strength
   (`KbConfig`). A first LLM extraction lands at `expectation ≈ 0.23` → **SPECULATIVE**; an email
   structural fact at `≈ 0.91` → **ESTABLISHED**. The Opinion + `_basisType` ride in node/edge metadata.
2. **Projection.** `STEP 0` projects the edge's confidence into the `FactStore` as a soft fact
   `Fact.soft(value)` (or `Fact.observed` / hard when ≥ 0.99).
3. **Inference.** The HL-MRF MAP solve (`STEP 4`) propagates soft-truth over the rules; `EntailmentEngine`
   materializes the result into the `InferredFactStore` as an `InferredFact` with a `[0,1]` confidence.
4. **Promotion.** `FactPromotionTracker` (`STEP 5c`) folds each cascade's evidence into the running Beta
   posterior (`evidencePos/evidenceNeg`) and re-projects it onto a `StrengthBand`. **This is the climb**:
   ~8 corroborations move a fact SPECULATIVE → HIGH; the band is the human-facing rank.
5. **Pruning.** `OpinionPruner` drops only INFERRED/AMBIGUOUS facts whose `Opinion` falls below the
   `PrunePolicy` thresholds. Survivors are what the UI/agents see.

**A RULE** also carries a weight (its strength), learned the same way:

1. **Birth.** `buildProgramFromFactStore` emits one soft propagation rule per observed predicate
   (`w: pred(?X) -> derived_pred(?X)`) at `pslDefaultRuleWeight`; ontology DOMAIN/RANGE axioms add
   typed rules (`OntologyToPslRuleCompiler`); `*.psl` files add hand-authored rules.
2. **Weighting.** The joint trainer (`STEP 5b`/§4) moves each rule weight toward explaining the
   hybrid-ranked consensus, **MAP-regularized toward a band-aware prior mean** so an ESTABLISHED-band
   rule isn't shrunk like a SPECULATIVE one. Weights persist (`cascadeWeightStore`) and warm-start the
   next cascade — so a rule's strength *accumulates* across cascades, exactly like a fact's confidence.

The symmetry is the design: facts and rules are both soft, both start from a calibrated prior, and both
climb with evidence — facts via Beta accumulation, rules via MAP weight learning on the hybrid consensus.

---

## 6. Learned embeddings + neuro-symbolic integration

- **Training (lib).** `EmbeddingLearner` implementations (`Node2VecLearner`, `RotatELearner`) train real
  knowledge-graph embeddings via `SameDiffEmbeddingTrainer` (DL4J SameDiff autodiff backend, gradient
  descent, ND4J/GPU). Output: an `EmbeddingTable` (`Embeddings`, `EmbeddingConfig`, `EmbeddingTableIO`).
- **Embeddings → reasoning (`EmbeddingPslEvidence`).** The neuro-symbolic bridge: cosine similarity
  between learned vectors is observed as PSL atoms (`similar(alice, bob) = 0.92`), letting rules like
  `5.0: similar(X,Y) & Label(X) -> Label(Y)` propagate soft labels over embedding geometry — the
  *learned* weight decides how strongly proximity drives inference.
- **Production wiring (knowledge-graph).** `KGEmbeddingJobService` runs training jobs;
  `KGEmbeddingStorageService` + `EmbeddingModelPersistenceService` + `GraphEmbeddingSidecar` (git-xet
  sidecar) persist models with the graph; `KgEmbeddingGraphAdapter` (`Jpa`/`Matrix`) + `KGEmbeddingRetriever`
  serve retrieval; `KGEmbeddingSchemaBridgeService` ties embeddings to entity types.
- **Graph-RAG retrieval.** `JpaGraphRagService` / `MatrixGraphRagService` combine vector similarity with
  graph-structural retrieval — Personalized PageRank (PPR), HYBRID, PathRAG, sparse-PPR, entity-type-typed
  retrieval — and a causal/MEBN-aware chat router.

---

## 7. Structure mining: communities, subgraphs, sparsity

- **Community detection.** `LouvainDetector` + `LabelPropagationDetector` → `CommunityAssignment`;
  `CommunityViewMaterializer` turns each community into an induced subgraph view; optional LLM summaries.
- **Subgraph materialization.** `SubgraphMaterializer` (seed + radius + edge-type/confidence filters) for
  focal views and per-community modeling.
- **Sparsity.** `SparsityMetrics` (density/degree/bipartite checks) gates the open-world handling in §3.

---

## 8. Ontology governance (the crawl ↔ ontology tie-in)

Bridged without the pipeline depending on process-engine, via the app-core SPI
**`OntologyProjectionProvider`** (implemented by the app-main bridge `GraphOntologyBindingService`,
which resolves `NamedGraph.ontologySchemaId → OntologySchema`):

- **P1 — guided extraction.** When an ontology is bound, its entity/relationship types constrain the LLM
  prompt + Tika filter (`kbOntologyGuidedExtractionEnabled`).
- **P2 — conformance.** `OntologyConformanceTagger` (a hydration stage) tags non-conforming nodes
  (`ontology.conformant` / `ontology.violation`) — LENIENT, never drops.
- **P3 — OWL → PSL.** `OntologyToPslRuleCompiler` injects DOMAIN/RANGE axioms as soft PSL rules into the
  MAP solve (weight `kbOntologyRuleWeight`).

Unbound = permissive everywhere (extraction free-form, no tagging, no ontology rules).

---

## 9. Configuration & surfacing

- **Config.** Every learner/evidence/trust/prune/ontology tunable lives in **`KbConfig`** (kompile-managed
  JSON at `~/.kompile/data/config/kb-confidence-config.json`, hot-reloaded by `KbConfigManager`), exposed
  at `/api/kb-config` and the KB-confidence web panel. No Spring `@Value`, no hardcoded literals on the
  production path.
- **UI (GraphsHub tabs + graph-visualizer).** Communities (overlay + LLM summaries), per-fact Opinion
  browser (BasisType badge, b/d/u simplex, search), Facts-by-Tier (band bar + temporal filter),
  ontology-conformance overlay + focal-view builder, MEBN theory inspector, KB weights + Confidence-Model
  card, FOL/ontology rule browser. A cold-graph banner + SPECULATIVE annotations make the early state legible.

---

## Module map

| Module | Role |
|---|---|
| `kompile-graph-reasoning` | infra-free engine: PSL/HL-MRF, Bayesian, MEBN, OWL-RL, FOL/Datalog, causal, embeddings (Node2Vec/RotatE/SameDiff), community, subgraph, sparse, pruning, confidence/Opinion, `HybridReasoner`, `KbVerifier` |
| `kompile-knowledge-graph` | Spring/JPA clients: reasoning orchestrator, grounding service, dual store, KGE jobs/retrieval, graph-RAG, confidence stamping + config, ontology bridge |
| `kompile-crawl-graph` | the crawl pipeline, modular step plan, per-source preprocessing, hydration orchestrator |
| `kompile-app-core` | store-agnostic models + SPIs (`OntologyProjectionProvider`, `GraphConformanceChecker`, `DocumentGraphExtractor`) |
| `kompile-app-main` | REST + Angular UI |

---

## End-to-end data flow

```
documents ─▶ crawl (load/convert/route/prep/chunk/extract) ─▶ entities+edges, each with an Opinion
   │                                                                     │
   │                                              ENTITY_RESOLUTION + VECTOR_INDEXING + KGE training
   ▼                                                                     ▼
 graph store (asset) ──project──▶ ReasoningGraph ──▶ ENRICHMENT (doReground):
                                                       PSL/HL-MRF MAP ⊕ ontology rules ⊕ embeddings-as-evidence
                                                       → entail facts (dual store) → climb bands (Beta)
                                                       → JOINT train: hybrid-rank → consensus →
                                                         co-train PSL + MEBN + embeddings → prune
   │                                                                     │
   ▼                                                                     ▼
 graph-RAG retrieval (vector ⊕ PPR ⊕ hybrid)              HybridReasoner ranking (structural ⊕ semantic)
   │                                                                     │
   └──────────────────────────────▶ UI / API / agent grounding ◀────────┘
```

The loop is the point: each crawl adds observed facts; reasoning derives more; corroboration climbs
confidence; weights and embeddings learn from the result; the next crawl starts smarter.
