# Reasoning Stack Usage Gaps — PSL / MEBN / GNN / FOL / Hybrid Audit

> Audited 2026-07-02 against the working tree (HEAD `ffc966086` + uncommitted contradiction/learning
> work). Companion to `crawl-graph-reasoning-architecture.md` (the intent) — this doc records where
> the *usage* diverges from that intent. Every claim was verified against current code; line numbers
> cite the working tree.
>
> Framing: the stack has five facets — **build** (extraction→graph+Opinion), **enhance** (cascade
> reasoning), **maintain** (prune/contradict/promote), **answer** (verify/query/explain/chat), and
> **learn** (weights/embeddings). The engines are largely built and the cascade genuinely runs; the
> gaps are almost all at the *seams between facets*, plus a large dark inventory in the lib.

---

## Implementation status (updated 2026-07-02)

**Recommendation 1 — "close the loop" — DONE** (module `kompile-knowledge-graph`, all changed-class tests green):

- **§2.2 grounded-confidence ranking is now ON.** `MatrixGraphRagService` reads its weight from managed
  config (`KbConfig.kbGroundedConfidenceWeight`, **default 0.2**) via `KbConfigManager` instead of the
  hard `0.0` field that nothing set; LOCAL/HYBRID/GLOBAL all use the effective weight. The audit's
  "denormalise `inferred.<pred>.confidence` → `confidence`" is moot — `nodeGroundedConfidence()` already
  prefers the namespaced inferred keys and falls back to plain `confidence` (the field Javadoc was stale).
- **§2.1 materialization runs from the cascade (opt-in).** `IncrementalReasoningOrchestrator` calls
  `InferredFactGraphMaterializer` after STEP 5 behind `KbConfig.kbCascadeMaterializeInferredEnabled`
  (**default false** — it mutates the @Primary graph every cascade, mirroring the MEBN-on-crawl flag).
  Three correctness guards make it safe to enable: (1) **idempotent replace** — a new
  `materialize(..., fuseWithExisting=false)` mode overwrites the prior edge's value via `updateEdge`
  instead of Jøsang-fusing, so re-deriving the same fact every cascade cannot inflate confidence toward
  1.0; (2) a **min-confidence gate** (`kbCascadeMaterializeMinConfidence`, default 0.5) so "derived-false"
  facts never become edges; (3) a **feedback-loop guard** — `GraphToFactStoreProjector` skips edges this
  materializer wrote (`isCascadeMaterializedEdge`, keyed on the "Inferred:" description + INFERRED
  provenance) so the next re-ground never re-observes its own inferences. Cross-doc / OWL INFERRED edges
  use different descriptions and still project. The per-fact `getEdgesInFactSheet` rescan was replaced
  with a one-shot index so per-cascade materialization is O(facts), not O(facts×edges).
  **To finish closing the loop end-to-end, set `kbCascadeMaterializeInferredEnabled=true`.**

**Recommendation 2 — "give chat answers evidence" — DONE for the reasoning routes** (app-rag +
app-main, 8 retriever tests green): the CAUSAL / PROBABILISTIC chat routes now surface a structured
`ReasoningTrail` (mode, confidence, top evidence) as a chat trail card, not just folded-in prompt text
(§1). `GraphReasoningRetriever.retrieveWithTrail` builds the trail from the attribution chains / MEBN
posteriors it already computes; `AgentChatService` pushes it through the *existing* `ReasoningTraceStore`
→ `drainSince` → `reasoning_trace` SSE → frontend `ReasoningTrailComponent` path (the same channel the
agent-invoked `kb_verify_explain` tool uses — no frontend change needed). `turnStartMs` is now recorded
before retrieval so the retrieval-time trail is drained. The prompt context string is byte-identical to
before (backward-compatible). Not yet covered: HYBRID/LOCAL/GLOBAL (their evidence is the entity
`sources` cards with confidence), the optional `verify()`-over-claims / enforcer→`ask_graph_verify` wiring,
and the API-agent path (which does not drain traces — a pre-existing limitation).

**Recommendation 4 — "make MEBN real by default" — DONE (Part A, the safer form)** (knowledge-graph +
crawl-graph; KbConfigTest + 34 GraphHydrationOrchestratorTest green): MEBN MTheory registration now
also fires on crawl for any fact sheet with a **bound ontology** — gated by the new
`kbMebnAutoEnableWhenOntologyBound` (**default true**), detected via `OntologyProjectionProvider.hasBoundOntology`
(the existing infra-free binding seam). This makes MEBN real by default for the deliberate,
ontology-governed subset without forcing SSBN onto every crawl (the blanket
`kbMebnTheoryRegistrationOnCrawlEnabled` stays default-off). Safe by construction: the auto-built
MTheory is bounded (≤20 MFrags, edge-gated context constraints — not the N² node-pair product) and
SSBN weight learning is throttled to every `kbMebnLearningInterval` (10) cascades, so the per-crawl
cost is registration only. **Not done — Part B:** the `/mebn/theory` GraphsHub tab. The audit's
"`GET /mebn/theory` returns a rich `MTheoryStructureDto`" is inaccurate — no such endpoint/DTO exists
in the tree, so the tab would require building the read endpoint first.

Recommendations 3, 5–8 below are not yet started. §3 (contradiction unification) overlaps the in-flight
work in §7 — coordinate before touching it.

---

## 0. What is actually live (baseline)

The 17-step `IncrementalReasoningOrchestrator.doReground()` runs on every crawl (inline via
`GraphHydrationOrchestrator` STAGE_DERIVATION at crawl Phase 9, *and* async via
`GraphBuildCompletedEvent → GraphChangesetCompletedEvent → GroundingCascadeHook`, coalesced by the
`pendingFlags` gate): project graph→FactStore → build PSL program (auto propagation rules + `*.psl`
files + ontology DOMAIN/RANGE + OWL-derived) → reload learned weights → HL-MRF MAP solve → entail →
`InferredFactStore` (Lucene-backed) → Beta promotion (`FactPromotionTracker`, 6-arg) → hybrid
consensus → 1-step PSL weight learning → JustificationIndex rebuild → TMS contradiction scan +
`BeliefReviser.retract` → epoch mark → optional MEBN learning → checkpoint. Verify/query/explain and
the ask_graph_* MCP tools sit on `KbGroundingService` over that state. This part matches the design.

---

## 1. THE structural gap: two disconnected answer paths

The product goal is "a graph that answers questions with clear evidence." There are two answer
paths, and the one users actually use doesn't touch the evidence machinery:

| | Chat / graph-RAG (primary UX) | Grounding surfaces (ask_graph_*, /api/explain, GraphsHub) |
|---|---|---|
| Engine | `MatrixGraphRagService` over **raw graph edges** (+ text-embedding ANN, PPR) | `KbGroundingService` over FactStore/InferredFactStore/JustificationIndex |
| Reads InferredFacts? | **No** | Yes |
| Reads Opinions/bands? | **No** (see §2) | Yes (verify → Opinion, bands, Platt) |
| Evidence in the answer | Plain-text context template; `retrievedSources` = entity cards, **no `ReasoningTrail` in the chat DTO** | DerivationTree, activatedRules, evidenceAtoms, Opinion tuple, staleness flag |

- `AgentChatService.retrieveGraphContext` routes LOCAL/HYBRID/GLOBAL to graph-RAG and
  CAUSAL/PROBABILISTIC to `GraphReasoningRetriever` (event-attribution / MEBN) — but even those
  return **formatted strings** into the LLM prompt; no structured trail reaches the chat response,
  so the frontend cannot render evidence for a chat answer (the `<app-reasoning-trail>` component
  exists and is wired into the visualizer + kb-context-panel, but not chat).
- Chat never calls `KbGroundingService.verify/explain`. The judge/enforcer path
  (`DiffPatternEvaluator`) also never consults graph verification — hallucination-control via
  `ask_graph_verify` exists only if the *agent* chooses to call the MCP tool.

**Net effect:** the system computes calibrated confidence + derivations every crawl, then answers
most questions from the raw graph without them.

## 2. The loop never closes back into the graph

Two one-way valves keep reasoning results from enriching what retrieval/visualization read:

1. **Materialization is manual.** `InferredFactGraphMaterializer` is NOT called from the cascade —
   inferred facts live only in the (Lucene) `InferredFactStore` unless someone POSTs
   `/api/knowledge-graph/inferred-facts/{fs}/materialize`. The OWL enrichment path materializes its
   own has-a/is-a closure, but PSL-entailed facts stay invisible to graph consumers.
2. **Grounded-confidence ranking is off.** `MatrixGraphRagService.groundedConfidenceWeight = 0.0`
   (MatrixGraphRagService.java:108) — the confidence-nudged re-ranking seams exist in LOCAL/HYBRID/
   GLOBAL but contribute exactly 0 by default, and per its own Javadoc (lines 104-105) inferred-fact
   confidence under `inferred.<predicate>.confidence` "is NOT reflected here" unless denormalised
   into the plain `confidence` key — which nothing does.

So: graph → FactStore each cascade (projection is live), but InferredFacts → graph only by hand.
"Reasoning ENHANCES the graph" currently ends at a side store.

## 3. Per-engine status vs. the "composed, not competing" claim

The doc claims "each is evidence to the others." Reality per engine:

### PSL — live workhorse, blind to its own uncertainty
Live in every cascade (MAP solve, weight learning, band promotion). But everything that would make
its confidence *calibrated and actionable* is LIB-ONLY dead: `PslMarginalInference`,
`PslUncertaintyAdapter`, `BayesianUncertaintyEstimator`, `InformationGainEstimator`,
`ValueOfInformation`, `SensitivityAnalyzer` (the whole `uncertainty/` package has zero production
imports). Production consumes MAP point estimates only; Platt calibration at read time is the lone
calibration step. Also unwired: `ArithmeticRule` (no grounding path into the solver —
psl-engine-gaps E-2), `OntologicalConstraintBuilder`, `SameAsCollectiveResolution` (collective ER
never used by `GraphCompactionService`), hard constraints approximated as high-weight soft rules.

### MEBN — wired end-to-end, dark by default
Step 9 (MEBN learning) requires a registered MTheory; `MebnTheoryRegistrationService` +
`GraphHydrationOrchestrator:252-254` do register on crawl — but only when
`kbMebnTheoryRegistrationOnCrawlEnabled=true`, and **KbConfig.java:99 defaults it to `false`**. So
in a default install the "PSL AND MEBN co-train every cascade" story is PSL-only. Query surfaces
(`ask_graph_mebn`, `/api/attribution/bayesian/*`, `/api/explain?mode=MEBN`) are live.
`GET /mebn/theory` returns a rich `MTheoryStructureDto` that **no GraphsHub tab renders**.
`MebnProbabilisticScorer` (resolution/) is dead code.

### GNN — a heuristic wearing a GNN's name
STAGE_GNN_SCORING is a live hydration stage, but the only `NeuralScoreProvider` is
`SparseGraphNeuralScoreProvider` — a deterministic 6-feature, one-hop message-passing **heuristic**
(its Javadoc says so; `MODEL_REF = "csr-graphsage-edge-scorer"` names the aspiration, not the
implementation). The ffc966086 work built the training *plumbing* (CSR cache on
`AdjacencyMatrixGraph`, `GraphToSameDiffDataset`, DL4J `sd.graph().rotatE/transE` ops in
`SameDiffKgeModel`) but: no trained GNN exists, and `SameDiffKgeModel` itself sits behind
`useSameDiffKge=false`, falling back to the hand-rolled `TransEModel`/`RotatEModel`.

### KGE / embeddings — two stacks, the neuro-symbolic bridge dark
- **App stack (live):** `KGEmbeddingJobService.trainSynchronously` at crawl end (gated
  `kgeAfterEnrichment`), vectors into node metadata `kgeEmbedding`, consumed by
  `MatrixGraphRagService` HYBRID blend at hardcoded `KGE_RETRIEVAL_WEIGHT = 0.5` — but only after a
  KGE job has run for that fact sheet.
- **Lib stack (dead):** `Node2VecLearner`, `RotatELearner`, `SameDiffEmbeddingTrainer`,
  `LinkPredictor`, `EmbeddingPslEvidence`, and the entire `embedding/kge/` bridge layer
  (`KgeEmbeddingTableBridge`, `KgePslBulkObserver`, `KgeOpinionStoreBridge`, `LinkPredictorKgeScorer`)
  have **zero production imports**. `EmbeddingPslEvidence` — the advertised "embeddings as PSL
  evidence" neuro-symbolic bridge (§6 of the architecture doc) — never runs. Consequence below (§3
  Hybrid).

### FOL / Datalog — wired only into pipeline-flows, evidence stripped at the query API
`FolInferenceService`/`FolRuleSet`/`RecursiveQueryEngine` reach production **only** through
`FolNodeExecutor` (compute-graph-core) — i.e., explicit pipeline graphs, not the KB path. The
cascade builds PSL propagation rules directly and never uses FolRules. Dead in the lib:
`ForwardChainingMaterializer`, `LogicBasedReducer`, `Finding`/`FindingStore` (the MEBN Finding
model), `TypeConstraintFolRuleCompiler`. `ConjunctiveQueryEngine` is live via
`KbGroundingService.query`, but `QueryResponse.BindingRow.matchedAtoms` is hardcoded `List.of()`
(KbGroundingController.java:200, "Phase 1") — conjunctive answers carry **no evidence**.

### Hybrid reasoner — one consumer, and its semantic half is empty
`HybridReasoner` has exactly one production consumer: `ExplainOrchestrator` (HYBRID mode for bare
entity ids). Its semantic score reads `GraphEntity.embedding`, which only the **dead lib learners**
populate — `KnowledgeGraphReasoningAdapter` fills embeddings from the store where present, but the
KGE vectors live under node metadata `kgeEmbedding` on the matrix store, a seam the adapter does not
read. In practice HybridReasoner degenerates to structural-only. The *real* hybrid in production is
`HybridConsensusTrainer` (the per-cascade consensus signal) — a training-time device, not an
answering reasoner.

## 4. Overlapping components (the duplication inventory)

| Concern | Overlapping implementations | Status |
|---|---|---|
| Contradictions | (1) lib `tms/ContradictionDetector` + `BeliefReviser` — live in cascade step 7, retracts FactStore facts at fixed \|Δ\|>0.2; (2) `knowledgegraph/maintenance/ContradictionDetector` — REST-driven, stales graph *edges*, delegates to lib `ProbabilisticContradictionDetector`; (3) app-core `GraphMaintenanceService`+`Contradiction` model (being extended in-flight: probabilistic-tension fields, `resolveContradictionsByEdgeSelection`) | The two resolution worlds don't talk: FactStore retraction ≠ edge staling; **neither downgrades Opinions** or feeds the weight learner |
| Confidence | (1) `ExtractionConfidenceStamper` write-time Beta Opinion; (2) `FactPromotionTracker` cascade Beta accumulation; (3) Platt + `StrengthBand` at read-time controllers; (4) raw edge-confidence scalar consumed by the projector. No persistent OpinionStore — Opinions re-derived on the fly | Graph-edge confidence and InferredFactStore values can drift; the reconciliation point (materializer) is manual |
| Fact/data stores | graph store; per-state in-memory `FactStore`; `ConcurrentFactStore`; `InferredFactStore` (Lucene/in-mem); weight stores (Lucene/file) | 5 tiers; projection one-way |
| PSL rule sources | auto per-predicate propagation; `<dataDir>/rules/*.psl`; `OntologyToPslRuleCompiler`; `OwlDerivedRuleProvider.owlDerivedPslRules` | All injected into one program with **no dedup** |
| Fixpoint/materialization | `ForwardChainingMaterializer` (dead), `RecursiveQueryEngine` (FolNodeExecutor only), `IncrementalGrounder` (dead — built for the incremental cascade that never got wired) | 3 engines for one job; the one the cascade needs is the dead one |
| Variable elimination | `bayesian/VariableElimination` vs the MEBN-internal VE | 2 copies |
| Pruning | lib `GraphPruner` facade (dead) vs 5 per-policy KG pruners + `OpinionPrunePass` (live) | parallel hierarchies |
| Explain surfaces | chat CAUSAL (text); `/api/attribution/explain` (chains + LLM synthesis); `/api/explain?mode=CAUSAL` (trail, `useLlm=false` → blank synthesis); `ask_graph_explain` and `graph_reason` (same endpoint, alias) | Same question, 3 engines/response shapes; evidence depth depends on which door you knock |
| Similarity | lib `Embeddings.cosine(double[])`; `SameDiffKgeModel.cosineSimilarity(INDArray)`; `MatrixGraphRagService.kgeStructuralSimilarity`; entity-resolution scorer; vector-store ANN | cosine reimplemented ≥3× |
| Explanation SPI | lib `ExplanationService` SPI unimplemented; `AttributionLlmService` wired directly; `ExplainOrchestrator` builds trails ad-hoc from data types | The seam meant to unify explanations is bypassed |

## 5. Evidence-quality gaps on the surfaces that DO answer

1. `ask_graph_query` → `matchedAtoms` empty (KbGroundingController.java:200).
2. `ask_graph_subscribe` → POST /subscribe returns **501**; tool silently degrades to one-shot
   polling (AskGraphSubscribeTool.java:34).
3. `/api/explain?mode=CAUSAL` sets `useLlm=false` → `synthesizedExplanation` blank; the LLM
   synthesis exists only on `/api/attribution/explain` directly.
4. `KbGroundingService.verify` is cache-only: the designed two-tier "targeted MAP on miss" was never
   implemented — anything not in the last cascade's store is UNKNOWN (staleness flag does surface).
5. `DefaultKbVerifier`'s 0.5 threshold remains uncalibrated (flagged in grounding-evaluation-design;
   PSL soft-truth ≠ probability).
6. Chat responses carry no trail (§1).

## 6. Cascade mechanics

- **"Incremental" is aspirational.** `IncrementalReasoningOrchestrator` Javadoc admits full
  per-fact-sheet re-ground; `IncrementalGrounder` never instantiated; `JustificationIndex` fully
  rebuilt each cascade. The email-arrives→delta-cascade vision is O(graph) per event today.
- **Double-fire.** Hydration runs inline at crawl Phase 9 AND via the async event bridge; the
  coalescing gate collapses it, but the redundant path is load-bearing only for the
  `graphWholesaleFailure` case.
- **TMS is disconnected from the confidence currency.** Retraction removes facts but never writes
  disbelief evidence into an Opinion or informs edge staling (and vice versa).

## 7. In-flight uncommitted work (recognized, not re-recommended)

The working tree is already moving on parts of this: probabilistic contradiction modeling
(`Contradiction` +7 fields, `PROBABILISTIC_TENSION`, maintenance detector ~+1000 lines,
`resolveContradictionsByEdgeSelection`), `ReasoningLearningExecutor` subprocess seam for PSL/MEBN
learning, JPA-path removals (`JpaGraphRagService`, `KGEmbeddingJobRepository`,
`FileBackedInferredFactStore` deleted — Lucene store is the durable tier now), extraction model
routing/budget in `GraphExtractionConfig`.

## 8. Prioritized recommendations

1. **Close the loop (highest leverage, small):** call `InferredFactGraphMaterializer` from the
   cascade (post step 5, behind a KbConfig flag) and ship a default `groundedConfidenceWeight > 0`
   (+ denormalise `inferred.<pred>.confidence` → `confidence`). Retrieval and the visualizer start
   seeing reasoned facts with zero new machinery.
2. **Give chat evidence:** have HYBRID/CAUSAL/PROBABILISTIC chat routes attach the existing
   `ReasoningTrail` to the response DTO (component already exists) and/or run `verify()` over
   claims; consider wiring the enforcer to `ask_graph_verify`.
3. **Unify contradictions on the Opinion currency:** one detector stack; resolution writes negative
   Beta evidence (opinion downgrade) + edge staling + FactStore retraction consistently. The
   in-flight probabilistic-tension work is the natural vehicle.
4. **Make MEBN real by default:** flip `kbMebnTheoryRegistrationOnCrawlEnabled` (or auto-enable when
   an ontology is bound), and give `/mebn/theory` its GraphsHub tab.
5. **One wire for the neuro-symbolic bridge:** feed the app KGE job's vectors through
   `KgeEmbeddingTableBridge`/`EmbeddingPslEvidence` into `doReground` (behind a flag) — makes
   "embeddings as evidence" real and un-darkens the lib kge/ package; also fixes HybridReasoner's
   empty semantic half (adapter should read `kgeEmbedding` metadata).
6. **Consolidate explain:** route `/api/attribution/explain` through `ExplainOrchestrator` as the
   CAUSAL mode (with LLM synthesis), implement the `ExplanationService` SPI with
   `AttributionLlmService`, and fill `matchedAtoms` in query responses.
7. **Wire or delete the dark inventory:** uncertainty/ (surface `ValueOfInformation` as
   "what to verify next" in the grounding console — pairs with active learning), `IncrementalGrounder`
   (the delta cascade), `ForwardChainingMaterializer`, `SameAsCollectiveResolution`,
   `ArithmeticRule`, `Finding/FindingStore`, `TemporalView`/`CreationTimeView`/
   `TemporalAttributionService`, `SubgraphMaterializer`/`CommunityViewMaterializer`,
   `MebnProbabilisticScorer`. Anything still dark after a quarter should be deleted per the
   library's own "proper library" bar.
8. **GNN honesty:** either train the SameDiff edge scorer (dataset plumbing is ready) or rename the
   heuristic provider so STAGE_GNN_SCORING doesn't overclaim.
