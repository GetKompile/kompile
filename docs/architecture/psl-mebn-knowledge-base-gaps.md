# PSL / MEBN Knowledge-Base Gap Analysis

**Date**: 2026-06-21  
**Scope**: `kompile-graph-reasoning` module at  
`kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/`  
**Purpose**: Identify what is missing for a *maintainable* graph knowledge base (KB)  
relative to canonical PSL/HL-MRF and MEBN theory, with particular focus on the  
**Finding/fact + entailment model** that the user identified as first-class.

---

## 1. Canonical Reference Summary

### 1.1  PSL / HL-MRF

**Primary reference**: Bach, Broecheler, Huang, Getoor.  
*"Hinge-Loss Markov Random Fields and Probabilistic Soft Logic."*  
JMLR 18(109): 1–67, 2017.  
PDF: <https://jmlr.org/papers/volume18/15-631/15-631.pdf>  
Arxiv: <https://arxiv.org/abs/1505.04406>

**Knowledge-graph identification (KGI) reference**: Pujara, Miao, Getoor, Cohen.  
*"Knowledge Graph Identification."* ISWC 2013.  
PDF: <https://linqs.org/assets/resources/pujara-slg13.pdf>

A **PSL program** consists of:

| Canonical element | Role |
|---|---|
| **Predicates / templates** | Named relations with arity, declared open (target) or closed (observed) |
| **Weighted FOL rules** | `w: body → head ^{1|2}` — soft implication penalties in [0,1] Łukasiewicz logic |
| **Grounding** | Instantiate every rule against declared ground atoms via database join |
| **HL-MRF** | The resulting graphical model: one hinge-loss potential per ground rule |
| **MAP inference** | Minimize total weighted energy — convex, solved by ADMM or projected gradient |
| **Marginal inference** | Approximate marginals (e.g. Gibbs sampling, belief propagation on the HL-MRF) |
| **Weight learning** | Three regimes: (a) max-likelihood / pseudolikelihood, (b) structured perceptron, (c) large-margin (max-margin); all require labelled training data |
| **Hard constraints** | `body → head .` — enforced as infinite-weight penalties; used for ontological consistency |
| **Knowledge graph tasks** | Collective entity resolution, link prediction, collective classification, ontology constraint enforcement — all jointly in one PSL program (KGI) |
| **Incremental grounding** | *Collective Grounding* (Srinivasan et al., 2023): database-style push-down to avoid re-grounding unchanged parts |

### 1.2  MEBN (Multi-Entity Bayesian Networks)

**Primary reference**: Laskey, K.B.  
*"MEBN: A Language for First-Order Bayesian Knowledge Bases."*  
Artificial Intelligence 172(2–3): 140–178, 2008.  
Author copy: <https://seor.vse.gmu.edu/~klaskey/papers/Laskey_MEBN_Logic.pdf>

**PR-OWL**: Costa, Laskey.  
*"PR-OWL: A Bayesian Ontology Language for the Semantic Web."* URSW 2006.  
<https://pr-owl.org/mebn/index.php>

An **MTheory** has three node roles in each **MFrag**:

| Node role | Description |
|---|---|
| **Resident** | The random variable whose CPT is defined in this MFrag |
| **Input** | RV whose CPT is defined in another MFrag; referenced as a parent here |
| **Context** | Boolean FOL conditions (first-order logic formulas over the KB); determine MFrag applicability for a given entity tuple |

**The FINDING concept** (the user's call-out) is the most critical element absent from our implementation:

> In MEBN, a **Finding** is an asserted, observed fact with an associated likelihood.  
> A finding is formally a random variable instance that has been **clamped** to a specific state,  
> plus an optional likelihood weight that modulates its influence during SSBN construction  
> (analogous to virtual evidence / Jeffrey's rule in plain Bayes nets).  
> Findings are the mechanism by which the **FOL context + observed facts → entail** new  
> conditional distributions in the SSBN.  Concretely: when constructing the SSBN,  
> the generator checks whether a resident RV instance has a **Finding** and, if so,  
> instantiates a special **observed node** rather than an unobserved one — the CPT of every  
> **context-descendent** node is conditioned on this clamped value, propagating the  
> observed evidence through the full generative model. This is **entailment**: observed  
> findings entail posterior distributions in reachable RVs via d-separation.  
>  
> **Likelihoods on findings** (PR-OWL "virtual findings") extend this further: a finding  
> can carry a soft likelihood P(observation | state) rather than a hard clamp, allowing  
> uncertain sensor readings, noisy extractions, or partial attestations to be encoded as  
> Bayesian likelihood ratios before SSBN construction. This is **the key difference** between  
> MEBN and a plain Bayesian network: the first-order structure (MTheory + context constraints)  
> determines *which* RV instances are entailed to exist and *which* conditional dependencies  
> apply, while findings supply the evidence that drives inference over that entailed structure.

**SSBN construction and entailment chain**:
1. MTheory + entity population → all valid context-satisfying groundings
2. Findings (clamped observations ± likelihoods) → clamp or soft-condition RV instances
3. SSBN = minimal BN sufficient to answer query (prune d-separated, barren, nuisance nodes)
4. Variable elimination / belief propagation on SSBN → posterior marginals over query RVs

Crucially, the **default/uncertain distribution** fallback: if no Finding exists for an RV instance,
MEBN stipulates a **default CPT** (typically a non-informative prior), so the MTheory still
produces a coherent joint distribution even when evidence is sparse.

---

## 2. What We Have — Inventory

### 2.1  PSL / HL-MRF (`psl/`)

| Class | What it does |
|---|---|
| `PslAtom` | Ground and template atoms; `key()` identity; negation; parse |
| `PslRule` | Weighted/hard rule record; text syntax parser; body/head; `^2`/`^1`; `distinct` guards |
| `PslProgram` | Observed + target atom registry; backtracking conjunctive grounding engine; `MAX_GROUND_RULES = 500 000` |
| `GroundRule` | Łukasiewicz body/head truth + `distanceToSatisfaction` + `potential` |
| `HlMrfMapInference` | Router: scalar vs tensor vs SGD solver by problem size |
| `ScalarHlMrfInference` | Plain-Java projected gradient descent with backtracking line search |
| `TensorHlMrfInference` | ND4J-vectorized MAP inference (GPU-capable) |
| `SgdHlMrfInference` | Mini-batch SGD solver for large sparse problems |
| `GraphPslProgramBuilder` | Builds `State/Link/Prior` atoms from a `ReasoningGraph`; default propagation + abduction rules |

**What works**: single-shot MAP inference over a grounded PSL program; three solver backends; hard-constraint support; abductive rule support; entity-constant mapping.

### 2.2  MEBN (`mebn/`, `mebn/logic/`)

| Class | What it does |
|---|---|
| `MTheory` | Container for MFrags + entity types; resident-home map; `validate()` |
| `MFrag` | Resident/input/context nodes; parent edge map; noisy-OR CPT builder |
| `RandomVariable` | Named, typed, arity-aware template RV; `ground()` → BN variable name |
| `EntityType` | Type name + entity ID set |
| `SSBNGenerator` | Cartesian grounding of entity tuples; context constraint evaluation; full + query-focused SSBN |
| `KnowledgeBase` | Interface over graph: entity/edge/type/weight/metadata predicates |
| `LogicalConstraint` | FOL constraint interface (AND/OR/NOT/IMPLIES/FORALL/EXISTS/atomic) |
| `Constraints` | Factory for all constraint types |

**What works**: full SSBN generation from an MTheory over a `ReasoningGraph`; context-constrained MFrag instantiation; noisy-OR CPT defaults.

### 2.3  Bayesian (`bayesian/`)

`BayesianNetwork`, `BayesianNode`, `Factor`, `NoisyOrCpt`, `VariableElimination` — exact variable elimination; Markov blanket; noisy-OR CPT builder from edge strengths.

### 2.4  FOL bridge (`fol/`)

`FolRule` (weighted FOL rule with antecedent/consequent `LogicalConstraint`), `FolRuleSet`, `FolInferenceService` (explicit FOL → PSL translation; pair-wise grounding), `MebnInferenceService` (MEBN/SSBN convenience service; simple/propagation theory builders), `ReasoningGraphKnowledgeBase` (in-lib `KnowledgeBase` over `ReasoningGraph`).

### 2.5  Model (`model/`)

`GraphEntity` (id/type/label/weight/confidence/tags/embedding/timestamp/attributes), `GraphRelation` (id/source/target/type/weight/confidence/directed/timestamp/attributes), `ReasoningGraph`, `MutableReasoningGraph`, builders, `SimpleGraphEntity/Relation`.

---

## 3. Gap Analysis

For each gap:
- **Canonical**: what the theory defines
- **We have**: what exists in the codebase
- **Gap**: what is missing
- **Maintainability impact**: why it matters for a *living* KB that evolves
- **Priority**: H = critical / M = important / L = nice-to-have

---

### GAP 1 (H) — First-Class Finding / Fact + Entailment Model

**Canonical** (MEBN, §3.3–3.4; Laskey 2008):  
A **Finding** is a distinguished node role in MEBN alongside RESIDENT/INPUT/CONTEXT. A finding asserts that a specific RV instance takes a known value (hard clamp) or carries a likelihood vector (soft/virtual evidence). During SSBN construction, found RV instances are treated as evidence nodes: their CPT is replaced by an identity (or likelihood) function, and all reachable descendants are conditioned on the finding via d-separation. This is **entailment**: the FOL context (MFrag context constraints + entity grounding) plus the set of findings **entail** a particular SSBN topology and a posterior distribution over every query RV.  

In PSL, the analogue is the `observed` predicate set: atoms fixed at truth value 1.0 (hard facts) propagate through the rule network. PSL's "knowledge graph identification" (Pujara 2013) includes **collective entity resolution** — a PSL program where each candidate extraction becomes a ground atom, confidence scores are observed facts, and the program reasons collectively to identify canonical entities.

**We have**:  
- `PslProgram.observe(atom, value)` — atoms with fixed truth values (hard facts in PSL).  
- `SSBNGenerator` evaluates context constraints before instantiating BN nodes.  
- Evidence is passed to `VariableElimination` / `MebnInferenceService.infer()` as `Map<String, Integer>` (clamped state indices).

**Gap**:  
1. **No `Finding` type / node role** in `MFrag`/`RandomVariable`. There are only RESIDENT, INPUT, CONTEXT. A finding should be a fourth kind: a resident whose distribution is replaced by observed evidence.  
2. **No likelihood-weighted (soft) findings** — only hard integer state clamps are accepted. PR-OWL defines "virtual findings" as likelihood ratios P(observation|state) per state, enabling noisy sensor inputs and uncertain text extractions.  
3. **No entailment chain record** — when a finding propagates to change downstream posteriors, there is no record of *which findings entailed which conclusions*. This is the "inference audit trail" needed for KB maintenance.  
4. **No finding lifecycle** — findings are not first-class objects that can be added, retracted, or revised independently of the graph structure. They live only inside inference call arguments.  
5. **PSL equivalent missing**: the `PslProgram.observe()` call exists but is buried in builder code; there is no domain-level `Fact` type with metadata (source, confidence, timestamp, provenance ID) that maps cleanly onto PSL observations *and* MEBN findings.

**Maintainability impact**: Without first-class findings, the KB cannot represent "what we know vs what we inferred", cannot selectively retract a stale observation, and cannot explain why a particular posterior changed. This is the root cause of brittleness in any live KB.

---

### GAP 2 (H) — Weight / Parameter Learning for PSL Rules

**Canonical** (Bach et al. 2017, §5):  
PSL supports three weight learning algorithms, all using labelled training data (ground truth assignments):  
(a) **Maximum-likelihood / pseudolikelihood** — maximise P(truth | observations) using gradient ascent; tractable because MAP inference is a convex subroutine.  
(b) **Structured perceptron** — online algorithm: run MAP inference, compare with truth, increase weights of violated rules, decrease weights of rules that fired but shouldn't have.  
(c) **Large-margin (max-margin)** — find weights that make the true assignment score higher than all alternatives by the largest margin.  
Weight learning is the mechanism by which the KB *tunes itself from data* rather than requiring hand-tuned expert rules.

**We have**:  
All rule weights are set manually (e.g. `propagationWeight = 2.0`, `abductionWeight = 1.0` in `GraphPslProgramBuilder`). The `FolRule.weight()` field exists but is set by the caller with no learning loop.

**Gap**:  
- No `WeightLearner` interface or implementation.  
- No training-data ingestion path (labelled atom assignments).  
- No gradient computation w.r.t. rule weights (only atom-value gradients are computed in `ScalarHlMrfInference`).  
- No pseudolikelihood objective.  
- No structured perceptron update step.

**Maintainability impact**: Hand-tuned weights drift as the KB grows and domain shifts. Without learning, the KB cannot adapt to new evidence patterns, and incorrect weights silently bias all inference.

---

### GAP 3 (H) — Truth Maintenance: Contradiction Detection, Retraction, Belief Revision

**Canonical**:  
A maintainable KB requires a **Truth Maintenance System (TMS)** (Doyle 1979; de Kleer 1986 ATMS):  
- Record *justifications* for each inferred belief (which facts + rules produced it).  
- When a fact is retracted, propagate the retraction to all beliefs that depended on it.  
- Detect **contradictions**: two beliefs that together violate a hard constraint.  
- **Belief revision** (AGM postulates): update the KB consistently when new facts conflict with existing beliefs.  
In PSL, contradictions appear as hard constraints with non-zero distance-to-satisfaction after inference. In MEBN, contradictions are context constraints that evaluate to FALSE for all groundings, making the MFrag vacuously inapplicable.

**We have**:  
- `MTheory.validate()` checks that every input RV has a home MFrag, and detects circular MFrag references. This is *structural* validation, not belief-level contradiction detection.  
- `HlMrfMapInference.Result` includes `objective` (total energy); a non-zero objective after convergence signals unsatisfied constraints, but nothing identifies which constraints are violated or why.  
- No retraction mechanism. `MutableReasoningGraph` supports mutable entity/relation sets, but removing a fact does not trigger re-inference or belief propagation.

**Gap**:  
- No contradiction index: no record of which ground rules have positive `distanceToSatisfaction` after MAP inference.  
- No justification store: no link from inferred PSL atom values or MEBN posteriors back to the observations + rules that produced them.  
- No retraction API: removing a `GraphEntity` or `GraphRelation` does not trigger incremental re-inference.  
- No belief revision policy (e.g. minimum-change revision, priority ordering of beliefs).

**Maintainability impact**: Without TMS, the KB silently accumulates contradictions as new facts are crawled. Stale extractions can corrupt inference results indefinitely. Required for any production KB where data updates continuously.

---

### GAP 4 (M) — Incremental KB Update Without Full Re-Grounding

**Canonical** (Srinivasan et al. 2023, *Collective Grounding*; foxPSL 2015):  
PSL's grounding step (instantiating all rules against all ground atoms) is the dominant cost at scale: O(|entities|^k) for k-ary rules. When the KB changes incrementally (new facts added/removed), only the affected rules need re-grounding. Database-style techniques (push-down selection, index joins, delta grounding) reduce re-grounding to the changed delta. The reference PSL open-source system (<https://psl.linqs.org/>) implements ADMM-based distributed inference that supports streaming data. MEBN's SSBN generation is similarly expensive for large entity populations; incremental SSBN construction avoids re-grounding unchanged MFrags.

**We have**:  
`PslProgram.ground()` re-enumerates all atoms on every call. `SSBNGenerator.generate()` recomputes all groundings on every call. There is no caching, delta tracking, or incremental update path.

**Gap**:  
- No delta grounding: adding a single new entity forces full re-grounding of all rules.  
- No atom index for join acceleration (atoms are in a flat `LinkedHashMap`).  
- No SSBN caching: unchanged MFrag groundings are recomputed every inference call.  
- No streaming / online PSL: the `SgdHlMrfInference` uses mini-batches of ground rules but does not support warm-starting from a previous solution when new atoms arrive.

**Maintainability impact**: At graph scale (tens of thousands of entities), a full re-ground on every KB update takes seconds to minutes. This makes the KB unusable for near-real-time applications (channel ingest, crawl updates, live sensor findings).

---

### GAP 5 (M) — Marginal Inference / Uncertainty Quantification in PSL

**Canonical** (Bach et al. 2017, §4.3):  
PSL's MAP inference produces the *most probable* soft-truth assignment — the mode of the HL-MRF distribution. For uncertainty quantification (confidence intervals, expected values, entropy of the distribution over atom truth values), **marginal inference** is needed. Standard approaches: (a) Gibbs sampling over the HL-MRF; (b) expectation propagation; (c) dual decomposition with marginal oracle. The canonical PSL system supports marginal inference via sampling-based methods.

**We have**:  
`VariableElimination` in the Bayesian path produces exact marginals over the SSBN. But in the PSL path, only MAP is implemented (`HlMrfMapInference`); no marginal or distributional output is available. The `FolInferenceResult` returns `entityLikelihoods` (MAP soft-truth values), which are point estimates, not distributions.

**Gap**:  
- No PSL marginal inference (no `MarginalInference` class or interface).  
- No uncertainty bounds on PSL atom truth values.  
- No sampling-based PSL inference path.  
- The FOL/PSL and Bayesian paths produce different uncertainty representations (continuous soft-truth vs discrete marginals) with no unified API.

**Maintainability impact**: Without marginal uncertainty, the KB cannot express "I'm 60% confident this entity is active, ± 15%". Downstream consumers (risk models, UI, routing) need calibrated uncertainty, not just MAP point estimates.

---

### GAP 6 (M) — KB Persistence, Versioning, and Provenance of Inferred Facts

**Canonical**:  
A maintainable KB distinguishes:  
- **Asserted facts** (directly observed / crawled, with source provenance)  
- **Derived/inferred facts** (produced by inference, with inference provenance: which rules + which input facts + timestamp)  
Both must be persisted, versioned, and linked to their derivation chains so that (a) stale inferences can be invalidated when their basis changes, and (b) audit trails satisfy compliance requirements.

**We have**:  
- `GraphEntity.attributes()` can hold arbitrary metadata including source provenance (crawl run ID, document ID, timestamp).  
- The main graph store (`GraphNode.metadataJson`) holds provenance — see the Phase-3 provenance implementation.  
- `HlMrfMapInference.Result` returns the full `groundRules` list (derivation), but this is an in-memory object with no persistence path.  
- No inferred-fact store distinct from the asserted-fact store.

**Gap**:  
- Inferred PSL soft-truth values and MEBN posteriors are **not persisted** to the graph store. Inference results exist only in `Map<String, Double>` for the duration of the request.  
- No `InferredFact` type with fields: `atomKey`, `value`, `confidence`, `derivationRules[]`, `derivationFacts[]`, `timestamp`, `inferenceRunId`.  
- No version lineage: re-running inference after a KB update cannot compare new inferences to old ones without external tooling.  
- `HlMrfMapInference.Result.groundRules()` is the right hook for derivation tracing, but nothing reads it for persistence.

**Maintainability impact**: Without persisted inferred facts with provenance, the KB is a black box. Debugging incorrect conclusions, fulfilling audit requirements, or detecting when inferences have become stale all require this layer.

---

### GAP 7 (M) — Ontology / Schema Constraints and Collective Entity Resolution in PSL

**Canonical** (Pujara et al. 2013, KGI):  
In knowledge graph identification, PSL programs enforce:  
- **Type constraints**: `Type(X, Person) -> ~Type(X, Organization) ^∞` (hard mutual exclusion)  
- **Functional constraints**: `HasBoss(E, M1) & HasBoss(E, M2) -> (M1 == M2) .` (uniqueness)  
- **Subsumption**: `Type(X, Engineer) -> Type(X, Person) ^∞`  
- **Collective entity resolution**: multiple candidate mentions of the same entity are resolved jointly, using similarity + transitivity rules + ontology constraints in one PSL program.

**We have**:  
`PslRule.hard()` supports infinite-weight constraints. `GraphPslProgramBuilder` adds only propagation/abduction/prior rules — no ontological constraint templates.  
The ontology binding (Phase 6) and schema governance (`GraphOntologyBindingService`) exist in `app-main` but are **not wired into the reasoning module**: the PSL program builder knows nothing about the schema.

**Gap**:  
- No `OntologicalConstraintBuilder` that reads an `OntologySchema` and emits hard PSL rules (type exclusion, subsumption, functional dependencies).  
- No collective entity resolution in the PSL program: duplicate entity candidates from crawl extraction are not jointly resolved via similarity + ontology rules.  
- No link from `kontologicalConstraints` (ontology governance) → `PslProgram` hard rules.

**Maintainability impact**: Without ontological constraints, the KB accumulates type errors and duplicate entities silently. Collective entity resolution is required to maintain entity-level consistency across large crawls.

---

### GAP 8 (L) — Rich FOL Grounding: Ternary+ Rules, Negation-as-Failure, Arithmetic

**Canonical** (Bach et al. 2017, §2):  
PSL supports k-ary predicates for any k, arithmetic comparisons in rules (`Weight(X,Y) > 0.5 -> ...`), and negation-as-failure (closed-world assumption for observed predicates). `FolInferenceService` pairs all entities as (X, Y), which limits expressiveness to binary relations.

**We have**:  
`FolInferenceService.buildPairs()` caps at 10,000 pairs and only handles binary (X, Y) groundings. Higher-arity rules (e.g. three-way relationships) are not supported. Arithmetic predicates are not supported in PSL rule parsing.

**Gap**:  
- Pair-only grounding: ternary and higher-arity rule bodies require triplet enumeration.  
- No arithmetic atom comparisons in rules.  
- No closed-world assumption enforcement (every unobserved atom defaults to 0.0 rather than being treated as absent).

**Maintainability impact**: Lower (L) because most KG reasoning is binary, but becomes important for process-mining rules (A precedes B precedes C) and complex ontological constraints.

---

### GAP 9 (L) — Scalable Query API and Grounding Optimisation

**Canonical** (foxPSL 2015; PSL linqs.org):  
Production PSL systems provide:  
- A **DSL** for declaring predicates with types (to scope grounding to type-compatible joins).  
- **Database-style grounding** with predicate-indexed join evaluation.  
- A **query API**: given a partial atom assignment (evidence), enumerate the relevant ground rules for that query without grounding the full program.  
- **Atom partitioning** for distributed/parallel inference.

**We have**:  
`PslProgram.ground()` does a flat backtracking join over all atoms. No predicate type declarations. No query-specific scoping. Grounding is sequential and single-threaded. The 500,000-rule safety cap protects against blow-ups but does not optimise.

**Gap**:  
- No typed predicate declarations (arity + argument type annotations).  
- No join-order optimisation.  
- No query-specific partial grounding.  
- No predicate-level index (`Map<predicate, List<groundAtoms>>` exists but is rebuilt every `ground()` call).

**Maintainability impact**: Low for current KB sizes; becomes blocking at 100k+ entities.

---

## 4. Prioritised Gap Table

| # | Gap | Priority | Theory | Maintainability Impact |
|---|---|---|---|---|
| 1 | Finding/Fact + entailment model (MEBN findings, PSL observed facts with provenance) | **H** | MEBN §3.3–3.4; PSL observed predicates | Root cause of KB brittleness; cannot explain or retract conclusions |
| 2 | PSL weight/parameter learning | **H** | Bach 2017 §5 | KB weights drift; cannot tune from data |
| 3 | Truth maintenance: contradiction detection, retraction, belief revision | **H** | TMS (Doyle 1979); ATMS; AGM | Silent contradictions accumulate; stale facts corrupt inference |
| 4 | Incremental KB update / delta grounding | **M** | Collective Grounding (2023); foxPSL | Unusable at scale with continuous crawl updates |
| 5 | PSL marginal inference / uncertainty quantification | **M** | Bach 2017 §4.3 | Downstream consumers need calibrated uncertainty |
| 6 | Persistence + versioning of inferred facts with derivation provenance | **M** | Knowledge provenance best practices | Audit trail, debugging, staleness detection |
| 7 | Ontology constraint enforcement + collective entity resolution in PSL | **M** | KGI (Pujara 2013) | Type errors and duplicates accumulate silently |
| 8 | Ternary+ grounding, arithmetic predicates, closed-world semantics | **L** | Bach 2017 §2 | Limits expressiveness of complex rules |
| 9 | Typed predicate declarations + query-specific grounding optimisation | **L** | foxPSL; PSL linqs | Scalability ceiling; not binding at current sizes |

---

## 5. Prioritised Roadmap

### Step 1 (Highest Return): First-Class Finding Model with Entailment Audit

**Why first**: Finding/fact + entailment is the **semantic foundation** on which all other gaps depend.
Truth maintenance (Gap 3) requires knowing which findings support which conclusions.
Persistence of inferred facts (Gap 6) requires knowing which atoms are findings vs inferences.
Weight learning (Gap 2) requires a training signal — labeled findings are the labels.

**What to build**:

1. **`Finding` record** (in `mebn/`):
   ```
   record Finding(String rvName, List<String> entityArgs,
                  int stateIndex,          // hard clamp (-1 = soft)
                  double[] likelihood,     // per-state likelihood (virtual evidence)
                  String sourceId,         // provenance: crawl run / channel / user
                  Instant timestamp,
                  String inferenceRunId)
   ```

2. **`FindingStore`** (in `mebn/`): a per-MTheory registry of `Finding` objects.  
   API: `assert(Finding)`, `retract(rvName, entityArgs)`, `findingsFor(rvName)`.

3. **Update `SSBNGenerator`**:  
   - Accept a `FindingStore` alongside the `KnowledgeBase`.  
   - During node instantiation, check the `FindingStore`; if a finding exists for a resident RV instance, clamp the BN node (hard) or inject a likelihood-ratio factor (soft / virtual evidence).  
   - Soft findings use Jeffrey's rule: `P(X) ← Σ_o P(X|O=o) · λ_o` where `λ` is the likelihood vector.

4. **`EntailmentRecord`**: after SSBN generation, produce a list of `(rvInstance, posterior, supportingFindings[], activatedMFrags[])` — the entailment audit trail.

5. **PSL parallel**: add a `Fact` wrapper over `PslProgram.observe()` with the same provenance fields; expose a `FactStore` that serialises to the `InferredFact` store and supports retraction.

**Estimated scope**: 5–7 new classes, no external dependencies.

---

### Step 2: Contradiction Detection + Retraction (Truth Maintenance)

**Why second**: Once findings are first-class objects, we can detect contradictions (a finding that contradicts a hard PSL constraint or an MEBN context constraint) and implement retraction.

**What to build**:

1. **`ContradictionDetector`** (PSL side):  
   After `HlMrfMapInference.solve()`, scan `groundRules` where `distanceToSatisfaction > threshold` and `hard == true`.  
   Emit a `Contradiction(groundRule, violatingAtoms, currentValues)`.  
   This already has the data it needs from `HlMrfMapInference.Result.groundRules()`.

2. **`JustificationIndex`**:  
   For each inferred target atom, record: `{atomKey → (rules that contributed, observed facts used)}`.  
   This is a lightweight map built during gradient computation in `ScalarHlMrfInference`.

3. **Retraction API** on `FactStore`/`FindingStore`:  
   `retract(factId)` → mark fact as retracted → propagate to `JustificationIndex` → identify beliefs  
   whose *only* support was the retracted fact → mark those beliefs as `UNSUPPORTED`.  
   Re-run inference on the affected sub-graph (incremental, if Gap 4 is implemented; full re-ground otherwise).

4. **Belief revision policy**: minimal-change (retract the belief with fewest total dependencies) vs explicit priority ordering. Start with minimal-change.

**Estimated scope**: 3–4 new classes; changes to `ScalarHlMrfInference` to emit justifications during the gradient loop.

---

### Step 3: PSL Weight Learning (Structured Perceptron)

**Why third**: With findings providing labelled training data (a finding = a ground-truth observation), structured perceptron weight learning becomes feasible.

**What to build**:

1. **`WeightLearner` interface**:
   ```java
   interface WeightLearner {
       List<PslRule> learn(PslProgram program,
                           Map<String, Double> groundTruth,  // observed atom → true value
                           int maxEpochs);
   }
   ```

2. **`StructuredPerceptronLearner`**:  
   Per epoch:
   - Run MAP inference → predicted values.
   - For each rule: compute predicted `distanceToSatisfaction` vs ground-truth `distanceToSatisfaction`.
   - Update: `w_r ← w_r + η · (distGT_r - distPred_r)` (increase weight when rule was violated in prediction but not in truth; decrease otherwise).
   - Project: `w_r ← max(0, w_r)` (non-negativity).

3. **`PseudolikelihoodLearner`** (simpler, no MAP loop):  
   Maximise the product of local conditional pseudo-likelihoods.  
   This is cheaper and often sufficient for moderate-size KB programs.

4. Wire `FactStore` / `FindingStore` as the source of ground truth: findings with hard clamps are the labels.

**Estimated scope**: 2–3 new classes; no changes to existing inference.

---

### Steps 4–6 (Subsequent Milestones)

| Step | Gap | Key Deliverable |
|---|---|---|
| 4 | Gap 6: Inferred fact persistence | `InferredFactStore` backed by graph metadata (`GraphNode.metadataJson`); write posteriors + derivation after each inference run |
| 5 | Gap 7: Ontology constraints in PSL | `OntologicalConstraintPslBuilder` reads `OntologySchema` → emits hard PSL rules for type exclusion, subsumption, functional deps; wire into `GraphPslProgramBuilder` |
| 6 | Gap 4: Incremental grounding | `DeltaGrounder`: maintain predicate-indexed atom lists; on fact add/remove, re-ground only rules that mention the changed predicate |
| 7 | Gap 5: PSL marginal inference | `GibbsMarginalInference` sampler over the HL-MRF; expose alongside `HlMrfMapInference` |
| 8 | Gap 8: Ternary rules | Extend `FolInferenceService.buildPairs()` to triplets; extend PSL parser to arithmetic atoms |

---

## 6. Summary: The Five-Sentence Version

The kompile PSL/MEBN implementation has **solid MAP inference** (three solver backends, backtracking conjunctive grounding, noisy-OR SSBN generation) but is **missing the connective tissue** that makes a KB maintainable over time. The most critical absence is a **first-class Finding concept**: MEBN's mechanism by which asserted facts with likelihoods entail posterior distributions through FOL-contextualised SSBN topology — without it, the KB cannot distinguish what it knows from what it infers, cannot retract stale observations, and cannot explain its conclusions. The second-most critical gap is **weight learning**: all PSL rule weights are hand-set, meaning the KB cannot tune from data and will silently degrade as the domain drifts. Third, the absence of **truth maintenance** means contradictions accumulate invisibly. The recommended build order is: (1) `Finding`/`FactStore` + entailment audit trail, (2) `ContradictionDetector` + `JustificationIndex` + retraction, (3) structured perceptron weight learning — these three steps together constitute a genuinely maintainable probabilistic KB.

---

## References

1. Bach, S., Broecheler, M., Huang, B., Getoor, L. *Hinge-Loss Markov Random Fields and Probabilistic Soft Logic.* JMLR 18(109), 2017. [PDF](https://jmlr.org/papers/volume18/15-631/15-631.pdf) | [arXiv](https://arxiv.org/abs/1505.04406)

2. Laskey, K.B. *MEBN: A Language for First-Order Bayesian Knowledge Bases.* Artificial Intelligence 172(2–3): 140–178, 2008. [Author copy](https://seor.vse.gmu.edu/~klaskey/papers/Laskey_MEBN_Logic.pdf) | [ScienceDirect](https://www.sciencedirect.com/science/article/pii/S0004370207001312)

3. Pujara, J., Miao, H., Getoor, L., Cohen, W. *Knowledge Graph Identification.* ISWC 2013. [PDF](https://linqs.org/assets/resources/pujara-slg13.pdf)

4. Costa, P.C.G., Laskey, K.B. *PR-OWL: A Bayesian Ontology Language for the Semantic Web.* URSW 2006. [pr-owl.org](https://pr-owl.org/mebn/index.php)

5. Bach, S., Broecheler, M., Huang, B., Getoor, L. *Hinge-Loss Markov Random Fields: Convex Inference for Structured Prediction.* arXiv 2013. [arXiv:1309.6813](https://arxiv.org/pdf/1309.6813)

6. Kotnis, B., Nastase, V. *foxPSL: A Fast, Optimized and eXtended PSL Implementation.* IJAR, 2015. [ScienceDirect](https://www.sciencedirect.com/science/article/pii/S0888613X15000845)

7. Pryor, C., et al. *NeuPSL: Neural Probabilistic Soft Logic.* 2022. [arXiv:2205.14268](https://arxiv.org/pdf/2205.14268)

8. Laskey, K.B., da Costa, P.C.G. *Of Klingons and Starships: Bayesian Logic for the 23rd Century.* UAI 2005. (MEBN findings and virtual evidence formalisation.)

9. Doyle, J. *A Truth Maintenance System.* Artificial Intelligence 12(3): 231–272, 1979. (Foundational TMS reference for Gap 3.)

10. de Kleer, J. *An Assumption-Based Truth Maintenance System.* Artificial Intelligence 28(2): 127–162, 1986. (ATMS for Gap 3.)

11. Park, C., Laskey, K.B. *MEBN-RM: A Mapping between Multi-Entity Bayesian Network and Relational Model.* arXiv:1806.02455, 2018. [arXiv](https://arxiv.org/pdf/1806.02455)

12. PSL open source project: <https://psl.linqs.org/> (linqs/psl on GitHub)
