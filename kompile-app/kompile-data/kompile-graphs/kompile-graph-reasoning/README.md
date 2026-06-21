# kompile-graph-reasoning

A generic, **infrastructure-free** library for **reasoning over — and maintaining — a graph knowledge
base**. It provides a unified graph model, probabilistic + logical inference engines, first-order
logic inference, hybrid (structural + semantic) reasoning, and graph-maintenance policies, with **no
dependency on Spring, JPA, the knowledge-graph store, or `app-core`**.

Every consumer — the JPA knowledge graph, the vector/matrix store, the Spring services, the
process-mining subsystem — adapts its own world onto the generic `ReasoningGraph` and calls into
these engines. The reasoning, FOL, embeddings, hybrid ranking, and maintenance logic all live here
once; `kompile-knowledge-graph` and the rest are **clients**.

```
                         ┌──────────────────────────────────────┐
   JPA GraphNode/Edge ──▶│  ReasoningGraph (entities+relations,  │──▶ psl/      (HL-MRF, weighted FOL)
   Vector/matrix store ─▶│  weight·confidence·tags·embedding·    │──▶ bayesian/ (VE, noisy-OR)
   Directly-follows DFG ▶│  timestamp + indexed adjacency)       │──▶ mebn/     (MTheory + SSBN)
   test fixtures ───────▶│                                       │──▶ fol/      (weighted rules)
                         └───────────────────────────────────────┘──▶ hybrid/   (structural+semantic)
                                                                  └──▶ maintenance/ (pruning policies)
```

## Unified graph model (`model/`)
- `ReasoningGraph` — entities + relations + O(1) lookup and indexed incoming/outgoing adjacency.
- `GraphEntity` / `GraphRelation` — first-class properties shared with the knowledge graph:
  `id`, `type`, `label`, `weight`, `confidence`, `tags`, `embedding` (dense vector), `timestamp`
  (event time), `attributes`.
- Fluent builders: `GraphEntity.builder(id)…build()`, `GraphRelation.builder(id,src,tgt)…build()`;
  `MutableReasoningGraph` is the in-memory implementation adapters populate; `SimpleGraph*` are
  immutable record values.

## Inference engines
- **`psl/`** — Probabilistic Soft Logic / HL-MRF: weighted first-order rules over soft truth
  `[0,1]`. `PslProgram`, `PslRule`, scalar/tensor(ND4J)/SGD solvers, `GraphPslProgramBuilder`.
- **`bayesian/`** — Bayesian networks: exact variable elimination, noisy-OR CPTs,
  `GraphBayesianNetworkBuilder`.
- **`mebn/`** (+ `mebn/logic/`) — Multi-Entity Bayesian Networks: `MTheory`/`MFrag`/`RandomVariable`,
  `SSBNGenerator`, and a first-order `LogicalConstraint`/`Constraints` language with `KnowledgeBase`.
- **`causal/`** + **`domain/`** — causal edge typing, attribution chains, result/query value types.

## First-order logic inference (`fol/`)
- **`ReasoningGraphKnowledgeBase`** — the in-lib `KnowledgeBase` over a `ReasoningGraph`, so FOL
  constraints and MEBN evaluate with no store.
- **`FolRule` / `FolRuleSet`** — weighted (likelihood-bearing) first-order rules, scopable per type.
- **`FolInferenceService`** — grounds weighted rules over the graph and infers via the PSL HL-MRF engine.
- **`MebnInferenceService`** — one call: SSBN construction + variable elimination over the in-lib KB.

## Knowledge-base maintenance & learning
Toward a maintainable KB (see roadmap below):
- **`fol/` Findings & entailment** — `Finding` (hard, or soft likelihood-weighted evidence + provenance)
  with a `FindingStore` (assert / retract / revise); a PSL-side `Fact`/`FactStore`; and an
  `EntailmentEngine` that entails facts with likelihoods and returns an `EntailmentRecord` audit trail
  (which findings + rules/MFrags supported each conclusion).
- **`tms/` Truth maintenance** — `ContradictionDetector` (reads HL-MRF ground-rule satisfaction),
  `JustificationIndex` (inferred atom → supporting rules/facts), and `BeliefReviser` (retraction +
  belief revision).
- **`learning/` PSL weight learning** — `StructuredPerceptronLearner` / `PseudolikelihoodLearner` tune
  rule weights from findings-as-labels, so the KB adapts instead of relying on hand-set weights.

## Embeddings + hybrid reasoning
- **`embedding/Embeddings`** — cosine / dot / euclidean / normalize + `mostSimilar(graph, query, k)`.
- **`hybrid/HybridReasoner`** — ranks entities by a configurable blend of *structural* inference
  (PSL HL-MRF or Bayesian VE) and *semantic* embedding similarity (mirrors the KG's HYBRID retrieval,
  over the generic graph).

## Graph maintenance (`maintenance/`)
Pure, decision-only pruning policies over a `ReasoningGraph` (no store, no deletion — they return a
`PruneResult` of ids + reasons; a client applies the deletions):
`OrphanPruningPolicy`, `ConfidencePruningPolicy`, `ComponentPruningPolicy`,
`StalenessPruningPolicy` (uses `timestamp()`), and a `GraphPruner` facade.

## Explanation SPI (`explain/`)
`ExplanationService` (interface) + `Explanation` — the contract for "explain why X"; the LLM-backed
implementation is supplied by a client.

## Dependencies
`nd4j-api`, `jackson-annotations`, `slf4j-api`, `lombok` (optional). No Spring, JPA,
`kompile-knowledge-graph`, or `kompile-app-core`.

## Consumers (clients)
| Module | How it uses this library |
|---|---|
| `kompile-knowledge-graph` | `KnowledgeGraphReasoningAdapter` projects `GraphNode`/`GraphEdge` (JPA **and** vector store) → `ReasoningGraph`, populating weight/confidence/tags/embedding/timestamp. The maintenance pruners run the generic policies, then apply deletions via `KnowledgeGraphService`. |
| `kompile-event-attribution` | Spring `@Service`s + REST; the PSL path flows through the adapter + `GraphPslProgramBuilder`; `AttributionLlmService` implements the explanation SPI. |
| `kompile-process-discovery` / `kompile-process-attribution` | Adapt a mined directly-follows graph → `ReasoningGraph` and run the engines. |

## Roadmap
`docs/architecture/psl-mebn-knowledge-base-gaps.md` — gap analysis toward a fully maintainable KB:
a first-class Finding/Fact + entailment model, PSL weight learning, truth maintenance, incremental
update, and persistence/versioning of inferred facts.
