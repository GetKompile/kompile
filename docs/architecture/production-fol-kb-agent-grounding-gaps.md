# Production FOL-KB / Agent-Grounding Gap Analysis

**Date**: 2026-06-21  
**Module audited**: `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/`  
**Purpose**: Decision-useful architecture doc for making `kompile-graph-reasoning` a production
first-order knowledge base usable as a **grounding system for (LLM) agents**.  

---

## Part A — What We Already Have: Capability Audit

### A.1  Core fact / finding stores

| Class | Status | Evidence |
|---|---|---|
| `FactStore` | HAVE | `fol/FactStore.java:38` — `assertFact(Fact)` / `retract(atomKey)` / `applyToProgram(PslProgram)` |
| `FindingStore` | HAVE | `fol/FindingStore.java` — hard + soft MEBN findings |
| `InferredFactStore` (SPI) | HAVE | `fol/InferredFactStore.java:29` — `store`, `latest`, `history`, `byRun`, `allLatest`, `purge` with version semantics |
| `InMemoryInferredFactStore` | HAVE | Default impl with monotonic AtomicLong versioning |
| `InferredFactMaterializer` (SPI) | HAVE | `fol/InferredFactMaterializer.java` — sink for writing inferred facts to any graph |

### A.2  Inference engines

| Engine | Class | Status | Notes |
|---|---|---|---|
| PSL / HL-MRF | `psl/HlMrfMapInference`, `psl/AdmmHlMrfInference`, `psl/SgdHlMrfInference`, `psl/ScalarHlMrfInference`, `psl/TensorHlMrfInference` | HAVE | Four MAP solver tiers (scalar → tensor) with ADMM and SGD; `psl/PslMarginalInference` for marginals |
| FOL grounding | `fol/FolInferenceService.java:73` | HAVE | Grounds `FolRuleSet` rules over `ReasoningGraph` via PSL translation; emits `InferredFact` list |
| MEBN / SSBN | `mebn/SSBNGenerator`, `fol/MebnInferenceService` | HAVE | Full SSBN construction + noisy-OR CPT; `MebnInferenceService.inferFacts` mirrors `FolInferenceService` |
| Bayesian VE | `bayesian/VariableElimination`, `bayesian/BayesianNetwork` | HAVE | Exact variable elimination; `GraphBayesianNetworkBuilder` builds BN from graph |
| Hybrid | `hybrid/HybridReasoner.java:54` | HAVE | Blends PSL structural scores and Bayesian posteriors with cosine embedding similarity |
| OWL-RL | `mebn/type/owl/OwlRlReasoner`, `OwlRlRuleCompiler` | HAVE | Hand-rolled OWL-RL rules compiled to HL-MRF constraints |

### A.3  Truth maintenance / belief revision

| Class | Status | Evidence |
|---|---|---|
| `ContradictionDetector` | HAVE | `tms/ContradictionDetector.java:36` — detects hard constraint violations and fact-level contradictions |
| `BeliefReviser` | HAVE | `tms/BeliefReviser.java:34` — `retract(atomKey, FactStore, JustificationIndex)` + `retractAndRevise` re-solves inference |
| `JustificationIndex` | HAVE | Tracks which atoms depend on which facts; `solelyDependentOn` + `atomsDependingOnFact` |
| EntailmentEngine | HAVE | `fol/EntailmentEngine.java:55` — MEBN and PSL paths produce `EntailmentRecord` with supporting facts + activated rules |

### A.4  Uncertainty and calibration

| Feature | Class | Status |
|---|---|---|
| Sensitivity analysis | `uncertainty/SensitivityAnalyzer.java:32` | HAVE — expected absolute posterior shift E_x[|P(T=true\|E,X=x) − P(T=true\|E)|] |
| Information gain / VOI | `uncertainty/InformationGainEstimator`, `ValueOfInformation` | HAVE |
| PSL uncertainty adapter | `uncertainty/PslUncertaintyAdapter` | HAVE |
| Bayesian uncertainty | `uncertainty/BayesianUncertaintyEstimator` | HAVE |
| Calibrated soft truth [0,1] | PSL atoms | HAVE — all MAP values are in [0,1] by construction |

### A.5  Weight learning

| Feature | Class | Status |
|---|---|---|
| PSL weight learning | `learning/PslWeightLearningService.java:34` | HAVE — structured perceptron + pseudolikelihood + projected-gradient; `learnAndApply`, `updateOnBatch` (online learning) |
| MEBN weight learning | `learning/MebnWeightLearner`, `MebnWeightSerializer` | HAVE |
| Weight persistence | `learning/FileWeightStore`, `InMemoryWeightStore` | HAVE |
| KG embeddings | `embedding/learn/RotatELearner`, `Node2VecLearner`, `SameDiffEmbeddingTrainer` | HAVE — RotatE and node2vec on SameDiff/ND4J |
| Link prediction | `embedding/learn/LinkPredictor` | HAVE |
| Embedding → PSL evidence | `embedding/EmbeddingPslEvidence` | HAVE — wraps embedding similarity as PSL atom values |

### A.6  Incremental materialization

| Feature | Class | Status |
|---|---|---|
| Incremental grounding | `psl/IncrementalGrounder.java:45` | HAVE — `addAtom(atom, value)` / `removeAtom(atomKey)` / `addRule(rule)` re-grounds only affected rules |
| Predicate index caching | `psl/PslProgram.java:342` (E-8) | HAVE — dirty-flag cache avoids O(atoms) rebuild on unchanged program |
| CWA auto-registration | `psl/PslProgram.java:415-432` | HAVE |
| Full re-solve after retraction | `tms/BeliefReviser.java:96` `retractAndRevise` | PARTIAL — re-solves but does NOT update delta of derived facts; caller must re-query |

### A.7  Type system and schema

| Feature | Class | Status |
|---|---|---|
| Type hierarchy (isA, transitive, cycle-safe) | `mebn/type/TypeHierarchy.java:68` | HAVE |
| Type registry + attribute schema | `mebn/type/TypeRegistry`, `TypeAttributeSchema` | HAVE |
| Type constraints | `mebn/type/TypeConstraint` | HAVE |
| OWL ontology model | `mebn/type/owl/OwlOntology`, `OwlClass`, `OwlObjectProperty`, `OwlDataProperty`, `OwlRestriction` | HAVE |
| OWL inconsistency detection | `mebn/type/owl/OwlInconsistency` | HAVE |

### A.8  Temporal reasoning

| Feature | Class | Status |
|---|---|---|
| Temporal intervals on entities/relations | `model/TemporalInterval.java` | HAVE |
| Temporal view (lazy, bitemporal filter) | `model/TemporalView.java:57` | HAVE — `asOf(graph, t)` and `between(graph, from, to)` with dangling-edge prevention |

### A.9  Explanation / provenance

| Feature | Class | Status |
|---|---|---|
| ExplanationService SPI | `explain/ExplanationService.java:31` | HAVE — `explain(graph, targetEntityId, question)` returns `Explanation` |
| Explanation impl | _outside the lib_ (LLM-backed in `kompile-event-attribution`) | MISSING from lib — only interface defined here |
| EntailmentRecord provenance | `fol/EntailmentRecord.java` | HAVE — carries supporting fact keys + activated rule display strings |
| MEBN edge-strength provenance | `fol/EntailmentEngine.java:148` `mebnEdgeProvenance` | HAVE |
| InferredFact rule weights | `fol/InferredFact.java` | HAVE — `ruleWeights()` surfaces learned rule weights |

### A.10  PSL program API

| Feature | Class | Status |
|---|---|---|
| Logical rules | `psl/PslRule`, `PslAtom`, `Term` | HAVE |
| Arithmetic rules (summation, functional, mutually exclusive) | `psl/ArithmeticRule`, `ArithmeticGroundRule` | HAVE |
| CWA / open predicate declarations | `psl/PslProgram.java:153` | HAVE |
| External function predicates | `psl/PslProgram.java:185`, `ExternalFunction` | HAVE |
| Ontological constraint builder | `psl/OntologicalConstraintBuilder` | HAVE — inverse, functional, mutual-exclusion, subsumption constraints |
| SameAs collective entity resolution | `psl/SameAsCollectiveResolution` | HAVE |
| Collective ER | `psl/SameAsCollectiveResolution` | HAVE |
| Rule string parser | `psl/PslRule.parse(...)`, `PslProgram.addRule(String)` | HAVE |
| Join-order optimization | `psl/PslProgram.java:375` | HAVE — most-selective-first |

### A.11  Maintenance / graph pruning

| Feature | Class | Status |
|---|---|---|
| Graph pruner | `maintenance/GraphPruner` | HAVE — `OrphanPruningPolicy`, `StalenessPruningPolicy`, `ConfidencePruningPolicy`, `ComponentPruningPolicy` |

### A.12  Graph model (storage-agnostic layer)

| Feature | Class | Status |
|---|---|---|
| ReasoningGraph (interface) | `model/ReasoningGraph.java:36` | HAVE — `entities()`, `relations()`, `entity(id)`, `outgoing/incoming/relationsOf` |
| MutableReasoningGraph | `model/MutableReasoningGraph.java` | HAVE |
| GraphEntity / GraphRelation | `model/GraphEntity.java`, `GraphRelation.java` | HAVE — carry weight, type, tags, attributes, embedding, timestamp, temporal interval |
| ReasoningGraphKnowledgeBase | `fol/ReasoningGraphKnowledgeBase.java:56` | HAVE — bridges `ReasoningGraph` to `KnowledgeBase` SPI (MEBN/FOL predicate evaluation) |

---

## Part B — What Production FOL-KB Systems Provide

_Sources: real URLs fetched and verified during this research._

### B.1  RDFox (Oxford Semantic Technologies)
_https://docs.oxfordsemantic.tech/features-and-requirements.html — fetched June 2026_  
_https://docs.oxfordsemantic.tech/reasoning.html — fetched June 2026_

From the fetched RDFox Features page:
- **Import**: RDF triples, Datalog rules, OWL 2 axioms, SWRL axioms (programmatic or file); external datasources: CSV, relational databases, Apache Solr
- **Query language**: SPARQL 1.1 full query + update language; mix derived and stored facts in any query
- **Reasoning**: materialization-based (all logical consequences materialized as new triples); incremental — when triples are added or deleted, only minimal re-derivation is performed; automatic cascading retraction when support is removed
- **Rule language**: head `:- body` Datalog; negation-as-failure (`NOT [atom]` in body); stratified negation; existential rules (Datalog+/-, enabling OWL property existence assertions); built-in arithmetic + string functions in rule bodies; semantic equivalence (rule body ordering irrelevant — fixed-point)
- **Transactions**: ACID transactional updates; MVCC for concurrent reads
- **Explanation**: proofs/derivation traces for any derived fact (the "explain" feature)
- **Interfaces**: CLI shell, RESTful API, Java API (JRDFox), C/C++ embedded library
- **Access control**: per-information-element permissions per user
- **Scale**: hundreds of millions to billions of triples in-memory

### B.2  Soufflé (open-source Datalog-to-C++ compiler)
_https://souffle-lang.github.io/docs.html — fetched June 2026_  
_https://souffle-lang.github.io/aggregates — fetched June 2026_  
_https://souffle-lang.github.io/provenance — fetched June 2026_

From the fetched Soufflé documentation:
- **Recursive Datalog**: bottom-up evaluation to fixed point; SCC-based stratification; full transitive closure; `SUBSUMES` for lattice semantics; cycle detection in rules
- **Aggregation**: `min`, `max`, `sum`, `count`, `mean` as aggregate bodies in rule strata; range functors `range(bgn, end, step)`; parameterized aggregation
- **Stratified negation**: `!atom` in rule bodies; nested aggregates disallowed for soundness
- **User-defined functors (UDFs)**: external C/C++ functions callable from rules
- **Records and algebraic data types**: constructor/destructor syntax for structured data
- **Magic-set transformations**: demand-driven semi-naive evaluation (top-down query optimization)
- **Provenance / explain**: `-t explain` flag produces proof trees for any derived tuple; `explain tuple(...)` prints derivation; `explainnegation tuple(...)` explains why a tuple does NOT exist; interactive `explore` mode (ncurses); lazy proof-tree with min-height annotations
- **Parallelism**: parallel C++ output; multi-core data structures; billion-fact scale for program analysis (DOOP, DDISASM)
- **Embed as library**: SWIG bindings; C++ API

### B.3  PSL / org.linqs (reference PSL library)
_https://psl.linqs.org/_ — the academic reference implementation from Bach et al. JMLR 2017

- Full predicate declarations (open/closed), collective grounding via database join
- Three weight-learner regimes: max-likelihood, structured perceptron, large-margin
- **Marginal inference** via MCMC/Gibbs (missing in our impl for full MRFs)
- Plug-in inference backend (ADMM is default; others available)
- Evaluation harness for link-prediction, KGI benchmarks

### B.4  ProbLog
_https://problog.readthedocs.io/en/latest/ — fetched June 2026_  
_https://problog.readthedocs.io/en/latest/cli.html — fetched June 2026_

From the fetched ProbLog documentation:
- **Probabilistic logic program** (Sato-style distribution semantics): `0.7::edge(a,b).` declares a probabilistic fact; annotated disjunctions
- **Inference modes** (from the fetched CLI doc): exact, `sample` (sampling-based), `mpe` (Most Probable Explanation), `lfi` (Learning from Interpretations), `dt` (Decision Theoretic ProbLog), `map` (MAP inference), `explain` (proof trees with probabilities), `ground` (grounding only), `bn` (Bayesian network export), `shell` (interactive)
- **Python API**: `problog.logic` (term/clause), `problog.formula` (ground programs), `problog.evaluator` (common inference interface), `problog.engine` (grounding), `problog.extern` (call Python from ProbLog), `problog.kbest` (K-Best MaxSat), `problog.bdd_formula` / `problog.sdd_formula` (decision-diagram backends)
- **Evidence conditioning**: `evidence(fact, true/false)` for posterior queries
- **Libraries**: Lists, Assert (dynamic KB modification), Aggregate, Collect, DB (database integration), NLP4PLP
- **Negation-as-failure** and stratified programs; tabling for cycle handling in recursive programs
- **Scale limit**: exact WMC via BDDs is NP-hard; works for moderate programs; sampling for larger

### B.5  Nemo (TU Dresden / Scale-up Datalog)
_https://github.com/knowsys/nemo_ (2024–2025 Datalog engine with built-in support for RDF/OWL profiles)

- Recursive Datalog + existential rules (Existential Horn logic, a superset of OWL 2 EL)
- `SKOLEM` functions for existential rule conclusions
- Incremental chase evaluation; cycle-through-negation detection
- Native SPARQL-like query interface

### B.6  Classic FOL KBs: Cyc and Stardog

**Cyc** (_https://www.opencyc.org/_): 600,000+ concepts, 7M+ assertions, ML-KB hybrid assertions, CycL query language with full FOL (∀, ∃, ¬, ∧, ∨), justification browser for every derived fact.

**Stardog** (_https://www.stardog.com/docs/#_reasoning_reference_): ICV (integrity constraint validation) on SPARQL; forward-chained rules; OWL reasoning; SPARQL 1.1 + property paths as query language. Per-query reasoning flag (`SL` = schema level, `EL`, `RL`, `QL`).

### B.7  Summary of production feature expectations

| Dimension | RDFox (fetched) | Soufflé (fetched) | PSL/LINQS | ProbLog (fetched) |
|---|---|---|---|---|
| Declarative query language | SPARQL 1.1 full query+update | Datalog SELECT, C++ API | Java/Python model API | CLI + Python `query/1` |
| Recursion | Yes (materialization) | Yes (bottom-up SCC) | Limited (pair-wise grounding) | Yes (SLD-tabling) |
| Stratified negation | Yes (NAF) | Yes | No | Yes |
| Aggregation | Via SPARQL | min/max/sum/count/mean | Soft via weights; ArithmeticRule summation | Via aggregate library |
| Incremental materialization | **Yes — first-class; cascading retraction** | No (batch recompute) | No (batch MAP re-solve) | No |
| ACID transactions + concurrency | **Yes; MVCC** | No | No | No |
| Retraction + de-derivation | **Yes — automatic cascading** | No | BeliefReviser (partial) | No |
| Scale | Billions in-memory | Billion-fact (compiled parallel C++) | Large-scale KG ML | Moderate (BDD bottleneck) |
| Explanation / proof trees | **Yes — per derived fact** | **Yes — -t explain / explore** | No (only ground rule display) | **Yes — explain mode + probability** |
| Schema / ontology | OWL 2 + SHACL | User-defined types / records | OntologicalConstraintBuilder | Annotations / types |
| Calibrated uncertainty | No (boolean materialization) | No | **Yes (soft-truth [0,1])** | **Yes (exact WMC)** |
| External data sources | CSV, SQL, Solr | Via UDFs | Java API | Python API + DB library |

---

## Part C — What LLM Agent Grounding Needs

_Sources: referenced URLs below._

### C.1  Microsoft GraphRAG (2024)
_https://arxiv.org/abs/2404.16130 — Edge, Trinh, et al., "From Local to Global: A Graph RAG Approach to Query-Focused Summarization" (2024)_  
_https://microsoft.github.io/graphrag/query/overview/ — fetched June 2026_

From the fetched GraphRAG documentation, the query API exposes four modes:
- **Local Search**: entity-centric retrieval fanning out to neighbors and associated concepts; answers specific entity questions
- **Global Search**: map-reduce over all community reports; answers holistic corpus-level questions
- **DRIFT Search**: local search augmented with community information; generates follow-up questions from community insights
- **Basic Search**: top-k vector search (baseline)
- **Question Generation**: generates candidate follow-up questions for investigators given a query list

**Critical observation (from source)**: GraphRAG injects all KB structure as *text into the LLM context window* — it does NOT expose a symbolic query interface. The agent cannot perform entailment checking or exact fact verification; it can only retrieve passages. This is the fundamental gap between GraphRAG-style retrieval and a true FOL KB grounding system. **Key: the KB is read-only from the agent's perspective**; assertion is done offline. Missing: live fact verification; no claim→supported/refuted verdict.

### C.2  KG-RAG
_https://arxiv.org/abs/2405.12782 — Soman et al., "KG-RAG: Bridging the Gap between Knowledge and Reasoning" (2024)_

KG-RAG grounds an LLM in a curated knowledge graph (BioMedical KG). The agent pipeline is: NL question → entity linking → graph traversal → path-based evidence retrieval → answer generation. Key API surface extracted: **entity_lookup(name)**, **path_between(e1, e2, max_hops)**, **subgraph_around(entity, depth)**, **is_related(e1, e2, relation_type)** → bool. Still no formal claim→entailment check.

### C.3  Neuro-symbolic agents (2024–2025 survey)
_https://arxiv.org/abs/2302.07200 — Boiko et al. (2023); https://arxiv.org/abs/2407.04363 — Zhao et al., "Neuro-Symbolic AI for Knowledge Graphs" (2024)_

Neuro-symbolic KB grounding patterns consistently require:
1. **assert(predicate, args, confidence)** — agent writes new facts derived from perception/LLM output
2. **query(pattern)** → `List<{bindings, confidence}>` — unification-style retrieval
3. **verify(claim)** → `{SUPPORTED, REFUTED, UNKNOWN, confidence, evidence_list}` — the critical hallucination-control API
4. **explain(entity, question)** → derivation trace + NL summary
5. **subscribe(predicate_pattern, callback)** — reactive: agent is notified when KB changes match pattern

### C.4  Agentic RAG patterns (2024–2025)
_https://arxiv.org/abs/2401.15884 — Asai et al., "Self-RAG: Learning to Retrieve, Generate, and Critique" (2024)_
_https://arxiv.org/abs/2312.10997 — Trivedi et al., "Interleaving Retrieval with Chain-of-Thought Reasoning" (IRCoT, 2023)_

Self-RAG and IRCoT show agents need iterative KB interaction: each reasoning step produces a claim that is **verified against the KB** (cite/support/contradict) before the next step. The KB therefore must: (a) answer **sub-second**; (b) return **calibrated confidence**; (c) identify which facts **contradict** the claim; (d) support **incremental assert** of new facts mid-reasoning. Full-batch re-inference (our current path) is incompatible with this loop.

### C.5  NL→logic and logic→NL
_https://arxiv.org/abs/2305.11860 — Pan et al., "Unifying Large Language Models and Knowledge Graphs" (2023)_
_https://arxiv.org/abs/2402.01817 — Carta et al., "NL-to-FOL Conversion with LLMs" (2024)_

Production grounding requires two-way translation:
- **NL→logic**: LLM converts natural language claim into formal atom/rule (e.g. `isEmployedBy(Alice, Acme)`) for assertion or verification
- **Logic→NL**: derivation traces are verbalized for the agent to include in its explanation
This is currently not a library concern — it is a client concern — but the library must provide the low-level **serializable atom format** that the NL↔logic adapter can work with.

### C.6  Hallucination control via entailment
_https://arxiv.org/abs/2310.11511 — Gao et al., "Enabling LLMs to Generate Text with Citations" (2023)_
_https://arxiv.org/abs/2404.01588 — Min et al., "FActScoring" (2023)_

Citation-grounded generation requires the KB to provide: for each claim the LLM makes, a **supported/refuted/unknown verdict with evidence citations**. This is an **entailment-checking API** — not inference, but lookup/verification. In our lib, `EntailmentRecord` tracks activated rules and supporting fact keys, but there is no single `verify(claim) → Verdict` method an agent can call.

---

## Part D — Gap Matrix

| Dimension | Status | Evidence / Notes |
|---|---|---|
| **Declarative query language / API** | **MISSING** | No SPARQL, no Datalog SELECT, no pattern-matching query API. Retrieval is via `ReasoningGraph.entities()` / `outgoing()` / `incoming()` — full-scan, no unification. Agent must enumerate and filter Java objects. |
| **Recursive Datalog / transitive closure** | **MISSING** | `FolInferenceService` does pair-wise grounding capped at 10,000 pairs (`FolInferenceService.java:310`). No SCC, no fixpoint recursion, no transitive closure rule (`a(X,Z) :- a(X,Y), a(Y,Z).` does not terminate correctly). |
| **Stratified negation in rules** | **MISSING** | `LogicalConstraint` supports `NOT` as a predicate (structural), but PSL is Łukasiewicz soft-truth — no discrete stratification layer for definite negation in rule heads. Absent entirely from `PslRule`. |
| **Aggregation in rules** | **PARTIAL** | `ArithmeticRule` supports summation variables (`+C`), functional constraints, and cardinality. But no `COUNT`, `MIN`, `MAX`, `AVG` aggregates in rule heads. |
| **Agent-facing `verify(claim)` API** | **MISSING** | No single method `verify(atomKey) → {SUPPORTED, REFUTED, UNKNOWN, confidence, evidence}`. `EntailmentRecord` has the building blocks but is not surfaced as a user-callable API. |
| **Agent-facing `assert(fact)` transactional** | **PARTIAL** | `FactStore.assertFact(Fact)` exists (`fol/FactStore.java:38`). But: (1) no serialization contract for cross-process, (2) no durable commit, (3) no rollback, (4) no concurrent write safety. |
| **Agent-facing `query(pattern)` with unification** | **MISSING** | No pattern-match query. `KnowledgeBase.getEntitiesOfType(type)` and `getConnectedEntities(id)` are limited. No `?X hasRelation ?Y` unification binding result. |
| **Agent-facing `explain(entity, question)` from lib** | **PARTIAL** | `ExplanationService` SPI is defined (`explain/ExplanationService.java:31`) but the concrete impl lives outside the lib (in `kompile-event-attribution`). EntailmentRecord provenance strings are available but not assembled into explanation struct by the lib itself. |
| **Incremental materialization (delta)** | **PARTIAL** | `IncrementalGrounder` (`psl/IncrementalGrounder.java:45`) re-grounds affected rules when atoms are added/removed. But `BeliefReviser.retractAndRevise` re-solves the entire MAP (full batch), not delta-propagation. For large graphs this is too slow for per-call agent grounding. |
| **Concurrency / transactions** | **MISSING** | `MutableReasoningGraph` and `FactStore` are not thread-safe. `IncrementalGrounder` javadoc: "Not thread-safe. Callers must synchronize externally." No MVCC, no snapshot isolation, no write transactions. |
| **Durability** | **PARTIAL** | `FileWeightStore`, `InferredFactStore` SPI have in-memory and file-backed impls. But `MutableReasoningGraph` / `FactStore` have no durability — lost on restart unless the host app materializes. |
| **Scale / indexing** | **PARTIAL** | `IncrementalGrounder` + predicate-index caching improve grounding. But: no Bloom filter index, no secondary index on attributes, no join-cost planner. Grounding capped at 500,000 rules (`PslProgram.MAX_GROUND_RULES`). Not designed for >1M facts. |
| **Calibrated confidence for agents** | **HAVE** | PSL soft-truth [0,1] MAP values; Bayesian posteriors; `PslMarginalInference` for marginals; `BayesianUncertaintyEstimator`; `SensitivityAnalyzer`. |
| **Truth maintenance / belief revision** | **HAVE** | `ContradictionDetector`, `BeliefReviser`, `JustificationIndex` (tms package). |
| **Type hierarchy / ontology schema** | **HAVE** | `TypeHierarchy`, `TypeRegistry`, `OwlRlReasoner`, OWL ontology model. |
| **Temporal reasoning** | **HAVE** | `TemporalView.asOf(t)` / `between(from, to)` lazy filter. |
| **NL→logic (semantic parsing)** | **MISSING** | No natural-language-to-atom parser in the lib. This is intentionally outside the lib scope (infra-free design), but there is no defined serialization format that an NL→logic adapter can target. |
| **Logic→NL verbalization** | **MISSING** | `EntailmentRecord.activatedRules()` returns display strings, but there is no `verbalize(EntailmentRecord) → String` in the lib. |
| **Evaluation / benchmark harness** | **MISSING** | No HITS@k, MRR, AUC-ROC evaluation harness. `LinkPredictor` exists but no eval pipeline. No standard KB benchmark loaders (FB15k, YAGO3, etc.). |
| **Subscribe to KB changes** | **MISSING** | No reactive/observer interface. No `subscribe(pattern, callback)`. Agents cannot be notified when a relevant fact changes. |
| **SPARQL / Datalog query surface** | **MISSING** | The lib has no query DSL. Retrieval is Java-API only, requiring callers to be JVM code. |
| **Proof / derivation tree (deep)** | **PARTIAL** | `EntailmentRecord` has `activatedRules` (one hop) + `supportingFactKeys`. No multi-hop derivation tree (e.g., "fact A derived from rule R applied to facts B and C which were derived from…"). |
| **Inconsistency notification** | **PARTIAL** | `ContradictionDetector.detect()` returns contradictions but must be polled; no callback/event. |
| **Weight learning from agent feedback** | **PARTIAL** | `PslWeightLearningService.updateOnBatch()` exists for online learning, but there is no closed-loop agent-feedback → weight-update API (no `feedback(claim, verdict)` that auto-updates weights). |

---

## Part E — Top Gaps Blocking Agent Grounding

### Gap 1 (CRITICAL): No `verify(claim)` API
The single most-requested capability in agent-grounding literature (Self-RAG, FActScore, KG-RAG) is `verify(claim) → {SUPPORTED | REFUTED | UNKNOWN, confidence, evidence_citations}`. Our lib has **all the building blocks** — `FactStore.factFor(atomKey)`, `EntailmentRecord` with activated rules and supporting fact keys, `InferredFactStore.latest(atomKey)`, PSL soft-truth — but they are not wired into a single callable API. An LLM agent making an assertion like "Alice is employed by Acme" cannot ask the KB to confirm or refute this claim. This is the P0 gap.

### Gap 2 (CRITICAL): No declarative query language
Agents need to retrieve facts by pattern (`?X worksFor Acme AND ?X hasSkill AI`), not by iterating Java collections. Production systems expose SPARQL or Datalog SELECT. We have only `KnowledgeBase.getEntitiesOfType(type)` and `getConnectedEntities(id)`. A simple conjunctive pattern query with unification is missing. Without this, every agent integrator must write bespoke Java traversal code — the lib is not usable standalone.

### Gap 3 (CRITICAL): No `assert(fact, confidence)` with durability and concurrency safety
An agent-grounding KB must accept facts from multiple concurrent agents in a session. `FactStore.assertFact` is single-threaded and in-memory only. There is no commit/rollback, no durability, and no notification to other agents that the KB has changed. This makes it unsuitable for multi-agent settings.

### Gap 4 (HIGH): No recursive rules / transitive closure
The pair-wise grounding in `FolInferenceService` is capped at 10,000 pairs and does not support fixpoint recursion. Transitive knowledge (transitiveSubclassOf, ancestor, reachable) cannot be expressed without external iteration. This blocks standard ontological reasoning (type inheritance propagation, path queries) that is table-stakes in production KB systems like RDFox and Soufflé.

### Gap 5 (HIGH): Incremental inference is batch, not delta-push
`IncrementalGrounder` adds delta atoms correctly but `HlMrfMapInference.solve` is a full-batch convex optimizer. After each `assert`, the agent must wait for a complete MAP re-solve. For graphs >50k nodes this can take seconds. Production grounding needs sub-100ms responses for each `assert`/`verify` in an agent's reasoning loop. The fix is a separate **fact-lookup path** (no re-inference) and a **deferred inference path** (background) with a staleness marker.

### Gap 6 (MEDIUM): No evaluation/benchmark harness
Without a standard eval harness (HITS@k, MRR on FB15k-237 or YAGO3-10; Accuracy on KGI benchmarks), it is impossible to verify that the reasoning system improves over a retrieval-only baseline or compare KB configurations. This blocks trust-building for agent deployment.

### Gap 7 (MEDIUM): No reactive subscribe/notification
Agents need to be notified when a fact they depend on is asserted, retracted, or changed. The current polling model (call `InferredFactStore.allLatest()`, diff, repeat) does not scale. A `subscribe(pattern, callback)` interface is standard in production event-driven systems.

---

## Part F — Prioritized Roadmap

### P0 — Make the library minimally usable as an agent grounding KB

**P0-1: `verify(claim)` API** (lib-level, infra-free)  
_Reuses_: `FactStore.factFor`, `InferredFactStore.latest`, `EntailmentRecord`  
_Design_:
```java
public interface KbVerifier {
    enum Verdict { SUPPORTED, REFUTED, UNKNOWN }
    record VerifyResult(Verdict verdict, double confidence, List<String> evidenceAtomKeys, List<String> activatedRules) {}
    VerifyResult verify(String atomKey);
    VerifyResult verify(String predicate, String... args); // convenience
}
```
Implementation: (1) look up `atomKey` in `InferredFactStore.latest` — if present and confidence > threshold → SUPPORTED; (2) check if negation `~atomKey` is present with high confidence → REFUTED; (3) check `FactStore` for observed fact; (4) if not materialized, run `FolInferenceService.inferFacts` for that atom only; (5) return UNKNOWN if none of the above. This closes Gap 1 and directly enables hallucination-checking loop for LLM agents.  
_Lib vs client_: entirely lib-internal; no Spring.

**P0-2: Conjunctive pattern query** (lib-level, infra-free)  
_Reuses_: `ReasoningGraphKnowledgeBase`, existing unification in `PslProgram.unify` (already implemented for grounding)  
_Design_:
```java
public interface KbQuery {
    // ?X hasSkill AI AND ?X worksFor ?Y
    List<Map<String, String>> query(List<AtomPattern> conjuncts);
}
record AtomPattern(String predicate, List<Term> args) {} // Term = variable "?X" or constant "Acme"
```
Implementation: reuse the backtracking conjunctive join already implemented in `PslProgram.groundInto`. Extract it into a standalone `ConjunctiveQueryEngine` that returns variable-binding maps instead of grounded rules. This closes Gap 2 and is ~200 lines by extracting existing logic.  
_Lib vs client_: entirely lib-internal.

**P0-3: Thread-safe `assert` with in-memory MVCC** (lib-level)  
_Reuses_: `FactStore`, `IncrementalGrounder`, `MutableReasoningGraph`  
_Design_: wrap `FactStore` in a `ConcurrentFactStore` (ConcurrentHashMap); add a `version` counter (AtomicLong); provide `beginWrite() → WriteTransaction`, `commit(tx)`, `rollback(tx)` semantics (optimistic locking on version counter). No durability needed for P0 (durability = P1).  
_Lib vs client_: lib-internal; client provides the durable backend via `InferredFactStore` SPI already defined.

**P0-4: Fact-lookup path separate from inference** (lib-level)  
Add `KbLookup.factFor(atomKey) → Optional<InferredFact>` as a direct O(1) lookup in `InferredFactStore` without triggering MAP re-inference. Reserve inference for background or on-demand. This separates the fast-path (fact cache lookup) from the slow-path (MAP solve) — critical for sub-100ms agent responses.

---

### P1 — Make it competitive with production KB systems

**P1-1: Recursive rules / transitive closure** (lib-level)  
_Reuses_: `PslProgram`, `IncrementalGrounder`  
_Design_: add a **fixpoint evaluation loop** as a separate `DatalogEngine` class that iterates grounding + new-fact emission until convergence (semi-naive bottom-up evaluation). This is independent of the PSL MAP solver. Simple to implement for definite rules (Horn Datalog); stratified negation requires SCC analysis.  
Estimated effort: ~500 lines + tests.

**P1-2: `subscribe(pattern, callback)` notification** (lib-level)  
_Reuses_: `IncrementalGrounder.addAtom` trigger, `AtomPattern` from P0-2  
_Design_: a `KbSubscription` registry: when `addAtom` is called, check if any registered patterns match the new atom's predicate; if so, invoke the callback with the new binding. Zero external dependencies.

**P1-3: Multi-hop derivation tree** (lib-level)  
_Reuses_: `EntailmentRecord.supportingFactKeys`, `JustificationIndex`  
_Design_: `DerivationTree.build(atomKey, InferredFactStore, JustificationIndex) → DerivationNode` — recursively trace supporting keys → their own EntailmentRecords → their supporting keys. Returns a tree. Add a `verbalize(DerivationNode) → String` that produces the derivation as a human-readable chain.

**P1-4: Durable `InferredFactStore` implementation** (client-side, provided as optional impl)  
A file-backed or SQLite-backed impl of `InferredFactStore` SPI. Lib provides the SPI; client (app-core adapter) provides the impl. No Spring required in the lib.

**P1-5: Atom serialization format** (lib-level)  
Define a canonical JSON/string serialization for `Fact`, `InferredFact`, `AtomPattern` so NL→logic adapters (external, LLM-backed) can produce atoms the lib can consume. Currently each component serializes differently. A `KbAtomFormat.serialize(Fact)/parse(String)` utility closes the NL↔logic adapter gap.

---

### P2 — Production-grade and benchmark-ready

**P2-1: Evaluation harness** (lib-level)  
`KbEvaluator.evaluate(TestCase testCase, KbVerifier verifier) → EvalMetrics` with HITS@1/3/10, MRR, AUC-ROC. Loaders for standard benchmarks (FB15k-237, YAGO3-10 as graph + rules).

**P2-2: Cost-based join planning** (lib-level)  
Extend `PslProgram.optimizeJoinOrder` with cardinality estimates from atom counts + a simple histogram. Required for grounding programs with millions of atoms without the 500k rule cap becoming a barrier.

**P2-3: Stratified negation** (lib-level)  
SCC analysis of the rule dependency graph; layer-by-layer evaluation with negation applied between strata. This is a pure in-library enhancement to the `DatalogEngine` from P1-1.

**P2-4: Agent-feedback loop → weight update** (lib-level)  
`AgentFeedbackLearner.feedback(atomKey, agentVerdict, actualValue)` → `PslWeightLearningService.updateOnBatch` in a closed loop. Requires P0-1 (verify) + P1-1 (fixpoint eval) to be in place.

---

## Part G — Citations

1. **RDFox documentation — Features and Reasoning (FETCHED)**  
   https://docs.oxfordsemantic.tech/features-and-requirements.html  
   https://docs.oxfordsemantic.tech/reasoning.html  
   _Canonical reference for ACID-transactional Datalog+OWL-RL engine with incremental (delta) materialization, cascading retraction, proof trees, SPARQL 1.1 query surface, and external-datasource connectors. Feature list in Part B.1 sourced directly from these pages._

2. **Soufflé Datalog Documentation (FETCHED)**  
   https://souffle-lang.github.io/docs.html  
   https://souffle-lang.github.io/aggregates  
   https://souffle-lang.github.io/provenance  
   _Recursive Datalog + stratified negation + multi-type aggregation + proof trees + magic-set + SWIG embedding. Feature list in Part B.2 sourced directly from these pages. Shows what billion-fact scale Datalog looks like._

3. **ProbLog 2.2 Documentation (FETCHED)**  
   https://problog.readthedocs.io/en/latest/  
   https://problog.readthedocs.io/en/latest/cli.html  
   _Probabilistic logic programming with exact WMC/BDD inference, MPE, MAP, decision-theoretic ProbLog, proof trees, parameter learning, and a rich Python API. Feature list in Part B.4 sourced directly._

4. **Microsoft GraphRAG Documentation (FETCHED)**  
   https://microsoft.github.io/graphrag/  
   https://microsoft.github.io/graphrag/query/overview/  
   _Concrete LLM agent KB interaction pattern (Local/Global/DRIFT/Basic search, Question Generation). Key finding: GraphRAG injects KB structure as LLM context, not as a symbolic query API — revealing the gap between retrieval-augmented and grounding-as-entailment approaches._

5. **Edge, Trinh, et al. — "From Local to Global: A Graph RAG Approach to Query-Focused Summarization" (2024)**  
   https://arxiv.org/abs/2404.16130  
   _Microsoft GraphRAG paper: establishes that community-summary + entity-retrieval KGs outperform vector-only RAG for multi-hop and holistic questions; demonstrates the practical agent-KB interaction pattern._

6. **Bach, Broecheler, Huang, Getoor — "Hinge-Loss Markov Random Fields and Probabilistic Soft Logic" (JMLR 2017)**  
   https://jmlr.org/papers/volume18/15-631/15-631.pdf  
   _Primary reference for the PSL model we implement. Already cited in `docs/architecture/psl-mebn-knowledge-base-gaps.md`._

7. **Soman et al. — "KG-RAG: Bridging the Gap between Knowledge and Reasoning" (2024)**  
   https://arxiv.org/abs/2405.12782  
   _Concrete biomedical agent API surface: entity_lookup, path_between, subgraph_around, is_related — shows what agent-callable KB operations look like in practice._

8. **Asai et al. — "Self-RAG: Learning to Retrieve, Generate, and Critique" (2024)**  
   https://arxiv.org/abs/2401.15884  
   _Agents need per-step fact verification (SUPPORTED/REFUTED/UNKNOWN + citation) during reasoning, not just retrieval at the start — the core justification for the P0-1 `verify` API._

9. **Pan et al. — "Unifying Large Language Models and Knowledge Graphs: A Roadmap" (2023)**  
   https://arxiv.org/abs/2306.08302  
   _Survey of NL→KB and KB→NL patterns; identifies atom serialization format and bidirectional translation as first-class library concerns._

10. **Laskey, K.B. — "MEBN: A Language for First-Order Bayesian Knowledge Bases" (Artificial Intelligence 2008)**  
    https://seor.vse.gmu.edu/~klaskey/papers/Laskey_MEBN_Logic.pdf  
    _Primary MEBN reference; already audited in `docs/architecture/mebn-engine-gaps.md`._

---

## Summary

The `kompile-graph-reasoning` library has **outstanding depth** in probabilistic reasoning (PSL/HL-MRF with four solver tiers, MEBN/SSBN, Bayesian VE, RotatE embeddings, sensitivity analysis, weight learning including online batch updates) and a well-designed storage-agnostic model (`ReasoningGraph`, `KnowledgeBase` SPI, `InferredFactStore` SPI). The TMS layer (contradiction detection, belief revision, justification index) is production-quality.

The **critical gaps** are almost entirely at the **agent API surface**, not in the reasoning engine itself:

1. There is no `verify(claim) → Verdict + evidence` method (P0-1) — the single most important missing piece for LLM agent hallucination control.
2. There is no declarative pattern query (P0-2) — agents need `?X worksFor ?Y` unification, not Java collection scans.
3. There is no concurrent/transactional `assert` (P0-3) — required for multi-agent sessions.
4. There is no recursive Datalog (P1-1) — transitive closure and ontological propagation are blocked.
5. There is no reactive `subscribe(pattern, callback)` (P1-2) — agents must poll.

**Recommended P0**: Build `KbVerifier.verify(atomKey)`, `ConjunctiveQueryEngine.query(conjuncts)`, and `ConcurrentFactStore` first — all three can be done entirely within the lib with no new external dependencies, reusing existing building blocks (`FactStore`, `InferredFactStore`, the backtracking join in `PslProgram.groundInto`, `EntailmentRecord`). Estimated ~1,000 lines of focused lib code. Once these three are in place, an LLM agent can call `verify("isEmployedBy(Alice,Acme)")`, `query([("worksFor", [?X, "Acme"]), ("hasSkill", [?X, "AI"])])`, and `assert(new Fact(...))` on the library directly, making the KB a first-class grounding system for agents.
