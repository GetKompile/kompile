# L4 Domain-Object Generation Design
## Kompile Agent-Grounding KB — First-Class Domain Objects with Confidence Scores

**Date**: 2026-06-21  
**Status**: DESIGN — no code written yet  
**Layer context**: This document deepens the L4 section of [`agent-grounding-infrastructure-design.md`](agent-grounding-infrastructure-design.md) (lines 551–645). Read that doc first; the 5-layer stack and module-hosting table are NOT repeated here.  
**Invariant**: `kompile-graph-reasoning` stays infra-free. Derivation logic that can be expressed without Spring lives there. Spring-managed orchestration lives in `kompile-knowledge-graph` (client of the lib) or `kompile-process-discovery`.

---

## 1. Why a Domain-Object Layer Exists

The `InferredFactStore` (SPI: `fol/InferredFactStore.java:29`) materializes PSL/MEBN posterior values as flat atom→value pairs. That flat representation is what the L1/L2 verify/query tools consume directly. But LLM agents and downstream process engines need higher-order objects: _"what business process does this graph encode?", "what rules govern approval flows?", "what type constraints hold?"_. These questions cannot be answered by atom-level queries alone.

L4 bridges the gap: it reads the materialized `InferredFactStore` plus the live `ReasoningGraph` and produces **versioned, confidence-scored domain objects** that an agent can reason over as structured facts rather than raw atom values. The objects produced here feed directly back into L2 (they become queryable via `ask_graph_query`) and are persisted alongside the graph portability data (Phase-1 pattern: `data/graph/domain-objects.json`).

---

## 2. Domain-Object Type Taxonomy

Five types are specified. The justification for inclusion is that each type:
1. Is already partially derivable from existing code (no new inference engines needed), and
2. Corresponds to a distinct question class an LLM agent will actually ask.

### 2.1 BUSINESS_PROCESS

**What it is**: A discovered, imperative, block-structured model of how activities unfold in a fact sheet — the standard PM4Py-style process tree, translated to `ProcessSuggestion` format.

**Justification**: `MiningProcessDiscoveryService` (`kompile-process-discovery/.../mining/MiningProcessDiscoveryService.java:67`) already derives these from the graph via InductiveMiner → ProcessTree → `ProcessSuggestion`. The gap is that the confidence on `ProcessSuggestion.confidence` (`ProcessSuggestion.java:60`) is currently computed structurally only: `Math.max(0.1, conformance.fitness() * conformance.precision())` (`ProcessTreeToSuggestion.java:116`) — no PSL scores involved. L4's first concrete deliverable is fixing that.

**Existing tools involved**:
- `EventLogExtractor` → `DirectlyFollowsGraph` → `InductiveMiner` → `ProcessTree`
- `DeclareMiner` (`mining/declare/DeclareMiner.java:37`) — produces `DeclareConstraint` list with per-constraint `confidence` from the same EventLog; these become child DECLARE_CONSTRAINT objects
- `HeuristicsMiner` (`mining/miner/HeuristicsMiner.java`) — dependency-net view, complementary to InductiveMiner
- `PerformanceMiner` (`mining/perf/PerformanceMiner.java`) — durations between activities; feeds `evidence` fields

### 2.2 GROUNDED_RULE

**What it is**: A symbolic Datalog-style implication (`head :- body`) derived from consistently co-occurring PSL-inferred atoms. Example: `isEmployedBy(?X, ?Y) :- worksAt(?X, ?Z) & subsidiary(?Z, ?Y)` with confidence = mean MAP value of activating atoms.

**Justification**: `InferredFact.ruleWeights()` (`fol/InferredFact.java:131`) already parses learned-weight prefixes from `supportingRuleIds` into a `Map<String, Double>`. The derivation is a frequency scan over `InferredFactStore.allLatest()` (`fol/InferredFactStore.java:71`) grouped by rule head; rules that consistently fire above a confidence threshold become first-class objects. This requires no new inference — only post-processing of already-materialized facts.

**Existing tools involved**:
- `InferredFactStore.allLatest()` — input scan
- `InferredFact.ruleWeights()` — parse learned weights from `supportingRuleIds`
- `EntailmentEngine.entailFromPslResult()` (`fol/EntailmentEngine.java:55`) — the entailment records that feed `InferredFact.supportingRuleIds` in the first place
- `PslProgram` — the rule templates from which ground instances are lifted back to rule-level objects

### 2.3 ONTOLOGY_CLASS (type hierarchy + binding)

**What it is**: A derived type node in the entity-type hierarchy — a named type, its parent (if declared), its attribute schema, and the confidence that the type is "real" in the sense of having enough member entities with coherent attributes to be meaningfully distinguished.

**Justification**: `TypeHierarchy` (`mebn/type/TypeHierarchy.java:68`) is already a snapshot of the full isA graph built from the `ReasoningGraph`. The `OntologicalConstraintBuilder` (`psl/OntologicalConstraintBuilder.java:56`) already emits hard PSL rules for mutual exclusion (line 122), subsumption (line 134), and functional dependency (line 148). These constraint rules, once applied to the `PslProgram`, produce `InferredFact`s with hard confidence = 1.0 for hard constraints (infinite weight, `PslRule.isHard = true`) and soft confidence < 1.0 for probabilistic memberships. The L4 layer turns the materialized `TypeHierarchy` into a `DomainObject` with a confidence score derived from: (a) fraction of members satisfying the declared constraints, and (b) PSL posterior of `Type(X, T)` atoms.

**Existing tools involved**:
- `TypeHierarchy.fromGraph(ReasoningGraph, TypeRegistry)` — snapshot of type structure
- `OntologicalConstraintBuilder.applyTo(PslProgram)` — hard constraint rules emitted into the live program
- `GraphOntologyBindingService` (app-main) — the canonical owner of ontology binding; L4 reads from it, does not duplicate

### 2.4 DECLARE_CONSTRAINT

**What it is**: A single declarative process constraint in the MINERful Declare template language — e.g., `Response(A, B)` ("if A occurs, B must eventually follow"), `Absence(A)` ("A never occurs"), with `support` (fraction of traces satisfying it) and `confidence` (fraction of traces where antecedent holds and constraint is satisfied).

**Justification**: `DeclareMiner.mine(EventLog, minSupport, minConfidence)` (`mining/declare/DeclareMiner.java:37`) already produces `List<DeclareConstraint>` with per-constraint `confidence` values computed from trace-level statistics. These are currently returned only by the `/api/process/mining/declare` endpoint and not stored as first-class versioned objects. Promoting them to `DomainObject` entries in the registry makes them queryable by agents: `ask_graph_query([("type", "DECLARE_CONSTRAINT"), ("predicate", "Response")])`.

**Existing tools involved**:
- `DeclareMiner` — the core producer; `DeclareConstraint` already carries `support` and `confidence`
- `MiningProcessDiscoveryService.declareConstraints(factSheetId)` — existing entry point; L4 calls this and persists the result

**Confidence semantics**: `DeclareConstraint.confidence` is trace-statistical (not PSL-grounded). For the L4 registry entry, a **blended score** is computed: `0.7 * declareConfidence + 0.3 * pslPrior`, where `pslPrior` is the mean PSL posterior of the activity atoms involved. This grounds the statistical constraint in the soft-truth values of the underlying KB.

### 2.5 CAUSAL_ATTRIBUTION

**What it is**: A causal claim of the form `cause(A, B, strength)` — "event type A causes event type B with causal strength s, supported by N observed co-occurrences", backed by PSL HL-MRF soft-truth values and Bayesian posteriors.

**Justification**: `ProcessCausalAnalyzer` (`mining/causal/ProcessCausalAnalyzer.java`) and `ProcessPslInference` already derive causal structure from the fact sheet's graph. The `ProcessSuggestion.bayesianPosteriors` field (`ProcessSuggestion.java:80`) carries per-variable posteriors already. What is missing is a first-class `DomainObject` representing the causal claim so that an agent can ask `ask_graph_verify("causes(budget_approval, payment_release)")`. Promoting causal edges to versioned domain objects closes the gap identified in `production-fol-kb-agent-grounding-gaps.md` for the counterfactual/sensitivity use case.

**Existing tools involved**:
- `ProcessCausalAnalyzer` — derives causal edges from the directly-follows graph + PSL
- `ProcessBayesianInference` — VE posteriors for causal variables
- `AttributionChain` / `AttributionConfidence` (`domain/AttributionChain.java`, `domain/AttributionConfidence.java`) — already in the lib; these are the carrier types; L4 surfaces them as `DomainObject` entries

---

## 3. Domain-Object Schema

All five types share a **common envelope** and carry a **type-specific payload** as a serialized JSON blob.

### 3.1 Common Envelope (`DomainObject` record)

```
DomainObject {
    id:             String            // UUID, stable across re-derivations of same object
    type:           ObjectType        // BUSINESS_PROCESS | GROUNDED_RULE | ONTOLOGY_CLASS
                                      // | DECLARE_CONSTRAINT | CAUSAL_ATTRIBUTION
    name:           String            // human-readable label
    factSheetId:    Long              // fact-sheet scope
    confidence:     double            // [0,1] — see §4 per type
    confidenceComponents: Map<String, Double>
                                      // named sub-scores for transparency
                                      // e.g. {"structural":0.72, "psl":0.85, "blended":0.81}
    derivedAt:      Instant           // timestamp of last derivation
    runId:          String            // inference run ID that produced the supporting InferredFacts
    version:        long              // monotonically increasing; bumped on re-derivation
    supportingAtomKeys: List<String>  // InferredFact.atomKey values that contributed
    supportingRuleIds:  List<String>  // PslRule display strings that activated
    payload:        String            // JSON-serialized type-specific object (§3.2–3.6)
    derivedBy:      String            // class simple name: "GroundedRuleDerivationService", etc.
}
```

The `version` field follows the same strict-increase semantics as `InferredFact.version()` (`fol/InferredFactStore.java:26-28`): a re-derivation of the same logical object (same `id`) must carry a strictly higher version.

The `confidenceComponents` map is the transparency mechanism: it exposes the sub-scores so that agents and users can see whether confidence is driven by structural statistics, PSL MAP values, Bayesian posteriors, or a blend.

### 3.2 BUSINESS_PROCESS payload

Serialized `ProcessSuggestion` JSON (already has Lombok `@Builder` + hand-serializable fields; `ProcessSuggestion.java:39`), stripped of `childSuggestions` (those become separate `DomainObject` entries with `parentId` reference). Key fields surfaced in `confidenceComponents`:

```json
{
  "structural": 0.72,    // conformance.fitness() * conformance.precision()
  "psl":        0.85,    // mean MAP value of directly-follows edge atoms
  "bayesian":   0.78,    // mean Bayesian posterior of phase-transition variables
  "blended":    0.81     // 0.4*structural + 0.4*psl + 0.2*bayesian
}
```

### 3.3 GROUNDED_RULE payload

```json
{
  "head":               "isEmployedBy(?X, ?Y)",
  "body":               ["worksAt(?X, ?Z)", "subsidiary(?Z, ?Y)"],
  "ruleTemplate":       "0.9: worksAt(?X,?Z) & subsidiary(?Z,?Y) -> isEmployedBy(?X,?Y) ^2",
  "observedActivations": 142,
  "meanActivationValue": 0.87,
  "learnedWeight":       0.94
}
```

`confidenceComponents`:

```json
{
  "meanMapValue":    0.87,    // mean InferredFact.value() across all activations
  "learnedWeight":  0.94,    // from InferredFact.ruleWeights() — the learned PSL weight
  "activationRate": 0.91,    // observedActivations / totalGroundInstances
  "blended":        0.89     // 0.5*meanMapValue + 0.3*learnedWeight + 0.2*activationRate
}
```

### 3.4 ONTOLOGY_CLASS payload

```json
{
  "typeName":       "Engineer",
  "parentType":     "Person",
  "memberCount":    47,
  "constraintsSatisfied": ["Mutual-Exclusion(Engineer,Organization)", "Subsumption(Engineer,Person)"],
  "conformanceScore": 0.93,
  "attributeSchema": {"department": "STRING", "level": "STRING"}
}
```

`confidenceComponents`:

```json
{
  "pslTypePosterior":    0.91,   // mean Type(X, typeName) atom value across members
  "constraintSatisfied": 0.93,   // fraction of hard-constraint activations that hold
  "memberDensity":       0.68,   // memberCount / totalEntities (relative size signal)
  "blended":             0.85    // 0.5*pslTypePosterior + 0.35*constraintSatisfied + 0.15*memberDensity
}
```

The `conformanceScore` reuses the `GraphHealthSnapshot.conformanceScore` (graph-health module, Phase-7) when the fact sheet has an `ontologySchemaId` binding (Phase-6 `GraphOntologyBindingService`).

### 3.5 DECLARE_CONSTRAINT payload

```json
{
  "template":   "Response",
  "activityA":  "budget_review",
  "activityB":  "payment_release",
  "support":    0.84,
  "statisticalConfidence": 0.91
}
```

`confidenceComponents`:

```json
{
  "statistical":  0.91,   // DeclareConstraint.confidence() from DeclareMiner
  "pslPrior":     0.76,   // mean PSL posterior of activityA/activityB atoms in InferredFactStore
  "blended":      0.87    // 0.7*statistical + 0.3*pslPrior
}
```

### 3.6 CAUSAL_ATTRIBUTION payload

```json
{
  "cause":          "budget_approval",
  "effect":         "payment_release",
  "causalStrength": 0.83,
  "observedCount":  38,
  "bayesianPosterior": 0.79,
  "pslMapValue":    0.85,
  "counterfactualDelta": 0.41
}
```

`confidenceComponents`:

```json
{
  "psl":            0.85,   // PSL MAP value of the causal edge atom
  "bayesian":       0.79,   // VE posterior from ProcessBayesianInference
  "counterfactual": 0.41,   // |P(effect|cause) - P(effect|~cause)| from sensitivity analysis
  "blended":        0.81    // 0.4*psl + 0.35*bayesian + 0.25*counterfactual
}
```

---

## 4. Derivation from InferredFactStore — Confidence Computation

### 4.1 Shared derivation trigger

Every derivation is triggered by `GroundingMaterializationCompleteEvent` (NEW Spring event published by `IncrementalReasoningOrchestrator` after step 5 of its cascade — see `agent-grounding-infrastructure-design.md:511-516`). Each derivation service receives `factSheetId` + `runId` and scopes all reads to that fact sheet.

### 4.2 BUSINESS_PROCESS — confidence formula

**Current code** (`ProcessTreeToSuggestion.java:116`):
```java
double confidence = Math.max(0.1, conformance.fitness() * conformance.precision());
```

**New formula** (L4 fix, same file):
```java
// 1. Structural score: unchanged
double structural = Math.max(0.1, conformance.fitness() * conformance.precision());

// 2. PSL score: mean MAP value of directly-follows edge atoms from InferredFactStore
//    atom key format: "directlyFollows(<activity_a>, <activity_b>)"
double pslScore = dfEdges.stream()
    .map(e -> inferredFactStore.latest("directlyFollows(" + e.from() + "," + e.to() + ")"))
    .filter(Optional::isPresent)
    .mapToDouble(opt -> opt.get().value())
    .average()
    .orElse(structural);  // fall back to structural if no PSL facts exist yet

// 3. Bayesian score: mean posterior from ProcessBayesianInference
double bayesianScore = bayesianResult.posteriors().values().stream()
    .mapToDouble(Double::doubleValue)
    .average()
    .orElse(structural);

// 4. Blended
double confidence = 0.4 * structural + 0.4 * pslScore + 0.2 * bayesianScore;
```

The `inferredFactStore` reference is passed from `MiningProcessDiscoveryService` (Spring-managed) to `ProcessTreeToSuggestion.convert()` as a new parameter. `ProcessTreeToSuggestion` remains a static utility class; the `InferredFactStore` is passed in, keeping it infra-free.

**Alpha calibration**: 0.4 / 0.4 / 0.2 is the initial default. This should be treated as a configurable property (`kompile.process.mining.confidence.alpha-structural`, `alpha-psl`, `alpha-bayesian`).

### 4.3 GROUNDED_RULE — confidence formula

```
Input:  InferredFactStore.allLatest() — all materialized InferredFact records
Step 1: Group facts by the rule template string extracted from supportingRuleIds
        (InferredFact.ruleWeights() returns Map<String, Double> keyed by rule display string)
Step 2: For each rule template: collect all InferredFacts where that rule fired
Step 3: Compute:
        meanMapValue    = mean(fact.value()) across activating facts
        learnedWeight   = mean(ruleWeights.get(ruleTemplate)) across activating facts
        activationRate  = |activating facts| / |total facts with same head predicate|
Step 4: blended = 0.5 * meanMapValue + 0.3 * learnedWeight_normalized + 0.2 * activationRate
        (learnedWeight is normalized to [0,1] by dividing by max observed weight in program)
Step 5: Emit GroundedRule if blended >= minConfidence threshold (default 0.5)
```

The `minConfidence` threshold is per-invocation configurable; the `GroundedRuleDerivationService` exposes it as a parameter on `deriveRules(Long factSheetId, double minConfidence)`.

### 4.4 ONTOLOGY_CLASS — confidence formula

```
Input:  TypeHierarchy.fromGraph(reasoningGraph)
        InferredFactStore.allLatest() for Type(X, T) atoms
Step 1: For each TypeNode in TypeHierarchy:
        pslTypePosterior = mean value of InferredFacts with atomKey matching "Type(*, typeName)"
        If no Type atoms exist (hierarchy is computed from graph entity_type, not PSL atoms):
            pslTypePosterior = 1.0 for types with memberCount > 0 (observed, not inferred)
            This is the CWA base case.
Step 2: constraintSatisfied = fraction of hard-constraint PslRules (emitted by
        OntologicalConstraintBuilder.applyTo()) that have ground-rule distance < 1e-6
        (full satisfaction). Source: HlMrfMapInference.Result.groundRules() filtered
        by ruleWeight == Double.POSITIVE_INFINITY.
Step 3: memberDensity = memberCount / totalEntities (capped at 1.0)
Step 4: blended = 0.5*pslTypePosterior + 0.35*constraintSatisfied + 0.15*memberDensity
```

If the fact sheet has an ontology binding (`ontologySchemaId` from Phase-6), the `GraphHealthSnapshot.conformanceScore` replaces `constraintSatisfied` as the more authoritative conformance signal.

### 4.5 DECLARE_CONSTRAINT — confidence formula

`DeclareMiner.mine()` already returns `DeclareConstraint` with `support` and `confidence` computed from trace statistics. The L4 layer adds PSL grounding:

```
Step 1: Run DeclareMiner.mine(log, minSupport=0.3, minConfidence=0.5)
Step 2: For each DeclareConstraint(template, activityA, activityB):
        pslPrior = mean of:
            - InferredFactStore.latest("activity(" + activityA + ")").map(f -> f.value())
            - InferredFactStore.latest("activity(" + activityB + ")").map(f -> f.value())
        If no PSL facts: pslPrior = 0.5 (uninformative prior)
Step 3: blended = 0.7 * constraint.confidence() + 0.3 * pslPrior
```

The 0.7/0.3 weighting strongly favors the trace-statistical confidence because Declare constraints are inherently trace-level propositions; PSL atom values are a secondary grounding signal.

### 4.6 CAUSAL_ATTRIBUTION — confidence formula

```
Step 1: ProcessCausalAnalyzer derives causal edges (cause, effect, pslEdgeValue)
Step 2: ProcessBayesianInference.Result: bayesianPosterior for the effect node given cause
Step 3: Sensitivity analysis (existing /api/attribution/sensitivity endpoint via
        SensitivityResult in domain/SensitivityResult.java):
        counterfactualDelta = |P(effect | do(cause=1)) - P(effect | do(cause=0))|
Step 4: blended = 0.4 * pslEdgeValue + 0.35 * bayesianPosterior + 0.25 * counterfactualDelta
```

The counterfactual delta uses the existing `SensitivityResult` (`domain/SensitivityResult.java`) already computed by the attribution endpoints. This avoids a new inference call.

---

## 5. Versioning and Lifecycle

### 5.1 Temporal anchoring via InferredFact versioning

`InferredFact` carries `version` (monotonically increasing per `atomKey`) and `inferredAt` (`fol/InferredFact.java:47-55`). The `InferredFactStore` contract (`fol/InferredFactStore.java:35-37`) mandates strictly higher versions on re-store. Each `DomainObject` records:

- `runId` — the inference run that produced the supporting `InferredFact`s, linking it to the temporal snapshot of the KB at derivation time
- `version` — the `DomainObject`-level version, bumped on every re-derivation (independent counter per `id`)
- `derivedAt` — `Instant.now()` at derivation time

**Point-in-time access**: the `asOf` parameter on `ask_graph_verify` / `ask_graph_query` propagates to `InferredFactStore.history(atomKey)` to select the fact with the highest version whose `inferredAt <= asOf`. `DomainObject` versioning supports the same pattern: the registry retains all versions; a query with `asOf` returns the highest-version object whose `derivedAt <= asOf`.

### 5.2 Lifecycle states

```
PENDING    → object ID allocated, derivation scheduled but not yet run
CURRENT    → latest version, derivation succeeded, confidence above threshold
SUPERSEDED → a newer version exists for the same logical object (same id)
INVALIDATED → the supporting InferredFacts were retracted (via BeliefReviser) or the
              fact sheet was deleted; the object is no longer valid
```

The `INVALIDATED` transition is triggered by `BeliefReviser` (`tms/BeliefReviser.java:34`) retracting the supporting atom keys listed in `DomainObject.supportingAtomKeys`. The registry listens for `BeliefRevisionEvent` (NEW, published by `BeliefReviser`) and marks affected objects `INVALIDATED`.

### 5.3 Re-derivation cadence

- **Full cascade** (after a crawl changeset): all five types are re-derived for the affected fact sheet. This is the common path. Cost: dominated by `MiningProcessDiscoveryService.discoverForFactSheet()` (EventLog construction + InductiveMiner), which is already measured at <2s for graphs with ≤10k nodes.
- **Delta cascade** (after an agent `assert`): only GROUNDED_RULE and CAUSAL_ATTRIBUTION are re-derived (the others require a full EventLog rebuild, which is not triggered by a single atom assert). BUSINESS_PROCESS and ONTOLOGY_CLASS remain at their last CURRENT version.
- **Explicit re-derive**: `POST /api/kb-grounding/domain-objects/derive?factSheetId=&types=` forces re-derivation of specified types, ignoring the cascade trigger.

### 5.4 Tie to graph portability (Phase-1 pattern)

`DomainObjectRegistry` persists objects as `data/graph/domain-objects.json` under the fact sheet's data directory, following the Phase-1 portability pattern (same file convention as `data/graph/*.json`). The file travels on `git clone`, meaning a collaborator opening the fact sheet sees the domain objects derived by the original user, with their confidence scores, without needing to re-run inference.

On startup, `DomainObjectRegistry` loads persisted objects (if the file exists) and marks them `CURRENT` (until the next cascade re-derives them). The `InferredFactStore` file-backed impl (Phase-1 of the L2 persistence plan) is the prerequisite for restart-survivable confidence scores; without it, confidence components cannot be recomputed on restart without re-running MAP inference.

---

## 6. DomainObjectRegistry

### 6.1 Interface

```java
// kompile-knowledge-graph/.../domain/DomainObjectRegistry.java  (NEW)
// Package: ai.kompile.knowledgegraph.domain
// Spring @Component; no Spring required for the data record itself.

public interface DomainObjectRegistry {

    // Registration
    void register(DomainObject obj);
    void registerAll(Collection<DomainObject> objects);

    // Query
    List<DomainObject> byType(ObjectType type, Long factSheetId);
    List<DomainObject> aboveConfidence(double threshold, Long factSheetId);
    Optional<DomainObject> latest(String id);
    List<DomainObject> history(String id);                      // all versions, asc
    List<DomainObject> asOf(Long factSheetId, Instant instant); // point-in-time

    // Lifecycle
    void invalidate(String id);
    void invalidateByAtomKeys(Collection<String> atomKeys, Long factSheetId);

    // Persistence
    void flush(Long factSheetId);   // write to data/graph/domain-objects.json
    void load(Long factSheetId);    // read from data/graph/domain-objects.json on startup
}
```

### 6.2 In-memory implementation

`InMemoryDomainObjectRegistry` uses a `ConcurrentHashMap<String, List<DomainObject>>` keyed by `id` (each list is the version history, sorted ascending by version). A secondary `Map<Long, Map<ObjectType, List<String>>>` maintains per-factSheet, per-type index of IDs for `byType()` queries. Thread-safety: same `ReentrantReadWriteLock` as `FactSheetKbState` (held in `KbGroundingService`) — registry reads acquire the read lock; re-derivation acquires the write lock.

### 6.3 Persistence format

`data/graph/domain-objects.json` is a JSON array of serialized `DomainObject` records. Serialization uses `InferredFact.toJson()` as a pattern (hand-rolled, no Jackson-databind, consistent with the lib's no-infra constraint). The registry implementation that writes this file IS Spring-managed (it uses `@Value("${kompile.data.dir}")` to locate the data directory), but the `DomainObject` record itself is infra-free.

### 6.4 REST exposure

```
GET  /api/kb-grounding/domain-objects?factSheetId=&type=&minConfidence=&asOf=
GET  /api/kb-grounding/domain-objects/{id}
GET  /api/kb-grounding/domain-objects/{id}/history
POST /api/kb-grounding/domain-objects/derive?factSheetId=&types=
DELETE /api/kb-grounding/domain-objects/{id}   (marks INVALIDATED)
```

These endpoints live in `KbGroundingController` (L2 Spring MVC, `kompile-app-main`), alongside the verify/query/explain/assert endpoints already specified in the L2 design. The `GlobalExceptionHandler` scope issue applies: add `ai.kompile.app.web.controllers.grounding` to `basePackages` (existing memory: `reference_global_exception_handler_scope`).

### 6.5 Integration with ask_graph_query (L1)

`ask_graph_query` supports `("type", "BUSINESS_PROCESS")` as a conjunct predicate. `ConjunctiveQueryEngine` (`fol/grounding/ConjunctiveQueryEngine.java`) treats `DomainObject` entries as virtual atoms with predicate = `ObjectType.name()` and args = `[id, name, confidence]`. The engine resolves these by querying `DomainObjectRegistry.byType()` rather than `InferredFactStore`, then returns binding maps consistent with the standard conjunctive query format.

---

## 7. Lib-vs-Client Split

The infra-free constraint on `kompile-graph-reasoning` is absolute. The split is as follows:

### 7.1 Lib additions (infra-free, in `kompile-graph-reasoning`)

| Component | Package | What it does |
|---|---|---|
| `DomainObject` record | `ai.kompile.graph.reasoning.domain` (NEW sub-package) | Plain Java record; payload as String; `toJson()`/`fromJson()` hand-rolled |
| `ObjectType` enum | same | BUSINESS_PROCESS, GROUNDED_RULE, ONTOLOGY_CLASS, DECLARE_CONSTRAINT, CAUSAL_ATTRIBUTION |
| `DomainObjectDeriver` interface | same | SPI: `List<DomainObject> derive(ReasoningGraph, InferredFactStore, long factSheetId)` |
| `GroundedRuleDeriver` | same | Lib-level impl of `DomainObjectDeriver` for GROUNDED_RULE (pure scan of `InferredFactStore`) |
| `OntologyClassDeriver` | same | Lib-level impl for ONTOLOGY_CLASS (uses `TypeHierarchy` + `OntologicalConstraintBuilder`) |

The `DomainObjectDeriver` SPI follows the same pattern as `InferredFactStore` — it is an interface so that tests can inject deterministic impls and Spring clients can provide full-featured impls.

`GroundedRuleDeriver` and `OntologyClassDeriver` are pure lib because their inputs (`InferredFactStore`, `ReasoningGraph`, `TypeHierarchy`) are all infra-free. They contain the actual confidence formulae from §4.3 and §4.4 — no Spring, no file I/O, no HTTP.

### 7.2 Spring clients (in `kompile-knowledge-graph` or `kompile-process-discovery`)

| Component | Module | What it does |
|---|---|---|
| `GroundedDomainObjectService` | `kompile-knowledge-graph` | Spring `@Service` orchestrating all derivers; owns the `DomainObjectRegistry`; listens for `GroundingMaterializationCompleteEvent` |
| `DomainObjectRegistry` (impl) | `kompile-knowledge-graph` | Spring `@Component`; in-memory + file persistence |
| `ProcessSuggestionDeriver` | `kompile-process-discovery` | Spring `@Service`; calls `MiningProcessDiscoveryService` + blends PSL scores into `ProcessSuggestion.confidence` via the new `ProcessTreeToSuggestion.convert()` signature |
| `DeclareConstraintDeriver` | `kompile-process-discovery` | Spring `@Service`; calls `DeclareMiner` + blends PSL prior |
| `CausalAttributionDeriver` | `kompile-process-discovery` | Spring `@Service`; calls `ProcessCausalAnalyzer` + `ProcessBayesianInference` + reads `SensitivityResult` |

`GroundedDomainObjectService` is the single Spring entry point. It holds refs to all five derivers (lib impls injected directly; Spring impl beans `@Autowired`). On `GroundingMaterializationCompleteEvent`, it dispatches to the appropriate derivers based on the `CascadeScope` (FULL vs DELTA as per §5.3).

**Why this split matters for the native image**: The lib impls (`GroundedRuleDeriver`, `OntologyClassDeriver`) can run in the CLI native image without Spring. This means a future `kompile derive-rules` CLI command can operate offline against a saved `data/graph/domain-objects.json` + `InferredFact` JSON files — no server needed.

---

## 8. Phased Implementation Plan

### Phase A — GROUNDED_RULE (foundational, unblocks all others)

1. Add `DomainObject` record + `ObjectType` enum to `kompile-graph-reasoning/ai.kompile.graph.reasoning.domain` (infra-free)
2. Add `DomainObjectDeriver` SPI (infra-free interface)
3. Implement `GroundedRuleDeriver` (lib) — pure scan of `InferredFactStore.allLatest()`, confidence formula §4.3
4. Implement `InMemoryDomainObjectRegistry` (Spring, `kompile-knowledge-graph`)
5. Implement `GroundedDomainObjectService` (Spring) — wires deriver + registry + event listener
6. Wire `GroundingMaterializationCompleteEvent` publication into `IncrementalReasoningOrchestrator` (L3)
7. Expose via `KbGroundingController` REST endpoints (§6.4)

**Milestone A**: `GET /api/kb-grounding/domain-objects?type=GROUNDED_RULE&factSheetId=1` returns PSL-grounded rules with confidence scores. `ask_graph_query([("type","GROUNDED_RULE")])` works.

### Phase B — BUSINESS_PROCESS confidence fix + DECLARE_CONSTRAINT

8. Modify `ProcessTreeToSuggestion.convert()` signature to accept `InferredFactStore` (nullable; fallback to structural-only if null)
9. Update `MiningProcessDiscoveryService.discoverForFactSheet()` to pass the `InferredFactStore` to `ProcessTreeToSuggestion`
10. Implement `ProcessSuggestionDeriver` (Spring, `kompile-process-discovery`)
11. Implement `DeclareConstraintDeriver` (Spring)
12. Register both with `GroundedDomainObjectService`

**Milestone B**: `ProcessSuggestion.confidence` reflects PSL MAP scores. DECLARE_CONSTRAINT domain objects are persisted and queryable.

### Phase C — ONTOLOGY_CLASS

13. Implement `OntologyClassDeriver` (lib, `kompile-graph-reasoning`) — uses `TypeHierarchy` + `OntologicalConstraintBuilder`
14. Wire with Spring: `GroundedDomainObjectService` injects `OntologyClassDeriver` via the `DomainObjectDeriver` SPI
15. Optionally integrate `GraphHealthSnapshot.conformanceScore` for fact sheets with ontology bindings

**Milestone C**: `ONTOLOGY_CLASS` domain objects available; agents can ask `ask_graph_verify("Type(Alice,Engineer)")` and get a grounded verdict backed by type-hierarchy constraints.

### Phase D — CAUSAL_ATTRIBUTION + persistence

16. Implement `CausalAttributionDeriver` (Spring, `kompile-process-discovery`)
17. Add file persistence to `InMemoryDomainObjectRegistry` (`flush`/`load` via `data/graph/domain-objects.json`)
18. Wire `BeliefRevisionEvent` → `DomainObjectRegistry.invalidateByAtomKeys()`

**Milestone D**: All five types live; domain objects survive restarts; causal attribution is agent-queryable; retracted facts invalidate dependent domain objects.

---

## 9. Open Questions

**Q1 — PSL atom key naming convention for process predicates**

The confidence formulae for BUSINESS_PROCESS (§4.2) and DECLARE_CONSTRAINT (§4.5) depend on `InferredFact` atom keys of the form `"directlyFollows(activityA, activityB)"` and `"activity(activityA)"`. These atoms must be populated into the `InferredFactStore` by the cascade pipeline (step 7 of `IncrementalReasoningOrchestrator`), which means `GraphPslProgramBuilder` (`psl/GraphPslProgramBuilder.java`) must emit `State` and `Link` predicates that map to activity labels, not just node IDs. Currently `GraphPslProgramBuilder` maps entity IDs to synthetic constants `n0, n1, ...` — the activity labels are lost. **Decision needed**: should activity atoms be added as a separate predicate layer on top of the existing `State/Link/Prior` triples, or should `GraphPslProgramBuilder` be extended to emit `Activity(n0, "budget_review")` atoms alongside the existing ones? The former is cleaner (no breaking change); the latter is more direct.

**Q2 — Confidence blending weights: where should they live?**

The α weights (0.4/0.4/0.2 for BUSINESS_PROCESS, 0.5/0.3/0.2 for GROUNDED_RULE, etc.) are currently hardcoded in the design. They should be configurable per-type per-fact-sheet. Options: (a) `graph-extraction-config.json` (already shared by `GraphExtractionConfigService` and `CrawlRuntimeConfigManager`; precedent for shared config); (b) new `domain-object-config.json` under `data/graph/`; (c) Spring `@ConfigurationProperties` only (no per-fact-sheet config). The choice affects whether the weights can be tuned per domain without a restart.

**Q3 — DomainObject IDs: stable vs content-addressed?**

If `id` is a UUID assigned on first derivation and reused on re-derivation, we need a stable identity function: "this GROUNDED_RULE object is the same logical rule as the one derived in the previous run." Options: (a) `id = UUID(hash(type + factSheetId + name))` — deterministic content-address; re-derivation of the same rule reuses the same ID and bumps version; (b) opaque UUID assigned first time and stored in the persisted JSON. Option (a) is cleaner for the portability story (the ID travels on `git clone` and is stable across machines) but requires a stable `name` for each object type, which for GROUNDED_RULE means the rule head + body string must be canonicalized.

**Q4 — Staleness during active agent assert/verify loops (inherited from L2 Q2)**

The L2 design's open question Q2 applies directly to domain objects: if an agent asserts `worksAt(Alice, Acme_NYC)` and immediately queries `ask_graph_query([("type","GROUNDED_RULE")])`, the GROUNDED_RULE objects may not yet reflect the new fact (cascade is async). The delta cascade (§5.3) re-derives GROUNDED_RULE synchronously on delta asserts, which partially mitigates this, but BUSINESS_PROCESS and DECLARE_CONSTRAINT remain stale until the next full cascade. A `"stale": true` flag in the query response (populated when `DomainObject.derivedAt` is before the most recent `InferredFact.inferredAt` for the same fact sheet) would let agents decide whether to wait.

**Q5 — Should domain objects be indexable in the vector store?**

The dual-store architecture (vector + HSQLDB) is used throughout. Domain objects currently persist only as JSON files (graph-portability pattern). To enable semantic search over domain objects ("find all rules about employment"), their `name + payload` text could be embedded and stored in the vector store. This would make `ask_graph_query` with a natural-language conjunct possible. The question is whether the vector-store embedding path (which goes through `kompile-app-main` and the embedding service) should be added in Phase D or deferred to a Phase E.
