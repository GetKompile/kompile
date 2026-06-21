# Domain-Object Grounding Design
## Verifiability and Calibrated Strength for Every Graph-Derived Artifact

**Status:** DESIGN — 2026-06-21
**Scope:** Cross-cutting grounding layer for every artifact produced FROM the knowledge graph — business processes, derived rules/ontologies, GraphRAG answers, diagrams. The goal is that every element carries a *calibrated* confidence, a *verifiable* KB evidence chain, and a *reasoning trail link*. No element may be present without an answer to "why is this here and how sure are we?"
**Coordinates with:**
- `business-process-generation-design.md` — owns process GENERATION; this doc owns the grounding layer those generators consume
- `reasoning-trail-explainability-design.md` — owns the `ReasoningTrail` WHY record and `POST /api/explain`; this doc is its consumer, not its replacement
- `fact-store-audit-tuning-correction-design.md` — owns audit log and strength-band correction; `StrengthCalibrator` feeds back into that doc's Platt-scaling hook
- `grounding-evaluation-design.md` — owns evaluation harness and threshold sweep; calibration dataset from that harness feeds `StrengthCalibrator`

---

## 0. The Problem Statement in Precise Terms

Graph-derived artifact generators today assign confidence values that are *uncalibrated heuristics*. Examples from the live code:

- `ProcessDiscoveryServiceImpl.java:116` — `confidence = Math.max(0.1, conformance.fitness() * conformance.precision())` (fitness×precision is a process quality metric, not a KB-grounding probability)
- `ProcessDiscoveryServiceImpl.java:259` — `confidence(Math.min(0.5 + (entry.getValue().size() * 0.1), 0.9))` (cluster size heuristic)
- `ProcessDiscoveryServiceImpl.java:353` — `confidence(0.7 + Math.min(formulaCells.size() * 0.05, 0.2))` (formula-cell count heuristic)
- `ProcessSuggestion.java:60` — `confidence` field exists but carries whatever heuristic the generator placed there; no KB verification was run to produce it

These numbers are internally consistent but externally uncalibrated: 0.7 from a formula-cell count means nothing different than 0.7 from a χ²-tested causal dependency, yet they appear in the same field. More critically, **no element of a generated artifact carries a KB evidence chain** — you cannot ask which KB atoms support the existence of a SuggestedStep, what `KbVerifier.verify()` returns for the predicate that step represents, or what `DerivationTree` depth-5 path justifies it.

This design makes grounding a **cross-cutting property**: every generator must call the KB before emitting each element, receive a `VerifyResult` and calibrated confidence, and attach a `GroundedElement` wrapper. The wrapper travels with the artifact from generation through REST serialization to the UI strength badge.

---

## 1. Existing Grounding Primitives (the substrate — do not re-implement)

All primitives are lib-level in `kompile-graph-reasoning` (no Spring):

| Primitive | File | Key contract |
|---|---|---|
| `KbVerifier` | `kompile-graph-reasoning/src/…/fol/grounding/KbVerifier.java:37` | `verify(atomKey) → VerifyResult`; `DEFAULT_THRESHOLD = 0.5` (line 43) |
| `VerifyResult` | `…/fol/grounding/VerifyResult.java:43` | Record: `Status {SUPPORTED,REFUTED,UNKNOWN}`, `double confidence`, `List<String> evidence` |
| `DefaultKbVerifier` | `…/fol/grounding/DefaultKbVerifier.java:42` | 4-step lookup: InferredFactStore → negated key → FactStore → UNKNOWN |
| `DerivationTree` | `…/fol/grounding/DerivationTree.java:57` | `atomKey`, `confidence`, `ruleApplied`, `sourceProvenance`, `children`; `build(atomKey, store, idx, maxDepth)` at line 87 |
| `ConjunctiveQueryEngine` | `…/fol/grounding/ConjunctiveQueryEngine.java:52` | `query(conjuncts, store) → List<QueryBinding>`; confidence = Łukasiewicz T-norm min across matched atoms (line 173) |
| `InferredFact` | `…/fol/InferredFact.java:47` | `value` (PSL soft-truth) + `confidence` ([0,1] separately validated) + `supportingFactKeys` + `supportingRuleIds` + `runId` |
| `KbGroundingService` | `kompile-knowledge-graph/src/…/grounding/KbGroundingService.java:71` | Spring `@Service`; `verify(factSheetId, atomKey)`, `explain(factSheetId, atomKey, maxDepth)`, `query(factSheetId, conjuncts, maxResults)` |

The Spring-side `KbGroundingService` is the entry point for all generators (they run inside app-core or app-main, so Spring is available). Lib-level `KbVerifier`/`DefaultKbVerifier` are for infra-free tests.

The **reasoning trail** for a grounded element reuses `ReasoningTrail` from `reasoning-trail-explainability-design.md §3.1` (not duplicated here). The `POST /api/explain` endpoint from that doc is the surface for on-demand trail fetching. This design adds the mechanism by which generators *create* the grounding attachment at generation time, so the trail is available without a second round-trip.

---

## 2. The `GroundedElement<T>` Model

### 2.1 Lib-level wrapper (kompile-graph-reasoning)

Location: `kompile-graph-reasoning/src/…/grounding/GroundedElement.java` (new)

```java
/**
 * Wraps any graph-derived artifact element T with KB-grounding evidence,
 * a calibrated confidence score, and a link to the reasoning trail.
 *
 * <p>Generators attach this wrapper to each element they emit. The calibrated
 * confidence replaces all raw heuristic confidence values — it is the output
 * of StrengthCalibrator.calibrate(rawScore, VerifyResult) rather than a
 * hand-coded formula.</p>
 *
 * @param element           the wrapped domain object (SuggestedStep, DeclareConstraint, etc.)
 * @param verifyResult      KB verdict for the primary atom this element represents
 * @param calibratedConfidence [0,1] output of StrengthCalibrator; replaces the element's
 *                          own confidence field for downstream display and filtering
 * @param strengthLayer     ESTABLISHED/PROBABLE/SPECULATIVE/SUPPRESSED (from StrengthLayerResolver)
 * @param atomKey           the canonical atom key passed to KbVerifier
 *                          (e.g. "precedes(ApproveInvoice, PayInvoice)")
 * @param trailRef          "runId:<runId>" — resolved lazily via POST /api/explain;
 *                          null when DerivationTree build was skipped (lazy mode)
 * @param derivationTree    eagerly built DerivationTree when mode=EAGER; null in LAZY mode
 * @param generatorId       string identifying the generator ("INDUCTIVE_MINER", "DECLARE_MINER",
 *                          "ONTOLOGY_DERIVE", "GRAPH_RAG", etc.) for audit purposes
 */
public record GroundedElement<T>(
    T element,
    VerifyResult verifyResult,
    double calibratedConfidence,
    StrengthLayer strengthLayer,
    String atomKey,
    String trailRef,
    DerivationTree derivationTree,
    String generatorId
) {
    /** True when the KB actively supports this element. */
    public boolean isVerified() {
        return verifyResult.status() == VerifyResult.Status.SUPPORTED;
    }

    /** True when the KB actively contradicts this element. */
    public boolean isRefuted() {
        return verifyResult.status() == VerifyResult.Status.REFUTED;
    }

    /** Evidence list from the KB (fact atom keys + rule display strings). */
    public List<String> evidence() {
        return verifyResult.evidence();
    }
}
```

### 2.2 Convention for non-wrappable aggregates

For aggregates where per-element wrapping is impractical (e.g., a `ProcessSuggestion` that contains many `SuggestedStep`s), the convention is:
- The aggregate carries a `List<GroundedElement<SuggestedStep>> groundedSteps` alongside (not replacing) the existing `List<SuggestedPhase> phases`.
- The existing `confidence` field on `ProcessSuggestion` (line 60) is replaced by `calibratedConfidence` at read time: the generator sets it from `StrengthCalibrator.calibrateAggregate(groundedSteps)` (geometric mean of SUPPORTED element confidences, 0.0 if any element is REFUTED).
- This is backward-compatible: existing callers reading `confidence` see the calibrated value; the `groundedSteps` list is additive.

### 2.3 Atom-key naming conventions for domain elements

Generators must translate their domain concepts into canonical atom keys that `KbVerifier` can look up:

| Domain concept | Atom key pattern | Example |
|---|---|---|
| Process step (activity) | `activity("<name>")` | `activity("Approve Invoice")` |
| Directly-follows arc | `precedes("<a>","<b>")` | `precedes("Approve Invoice","Pay Invoice")` |
| Causal dependency | `causes("<a>","<b>")` | `causes("Approve Invoice","Pay Invoice")` |
| Ontology class membership | `type("<entity>","<class>")` | `type("alice","Employee")` |
| Derived ontology rule | `implies("<antecedent>","<consequent>")` | `implies("Employee","AuthorizedSpender")` |
| GraphRAG answer claim | `claim("<hash>")` | (hash of the atomic claim string) |
| Diagram node (swimlane role) | `role("<name>","<entity>")` | `role("Finance","PayInvoice")` |

When the atom key is not yet in the KB (the predicate is graph-derived, not fact-store-backed), the generator asserts it via `KbGroundingService.assertFact(factSheetId, fact)` before calling `verify()`. This makes the element's presence itself a KB fact — subject to TMS contradiction detection and future re-grounding.

---

## 3. StrengthCalibrator — Turning Raw Scores into Calibrated Confidence

### 3.1 The calibration problem

PSL soft-truth [0,1] from `HlMrfMapInference` is NOT a probability (`grounding-evaluation-design.md:385`; `graph-hydration-inference-chain-design.md:402–413`). The Łukasiewicz T-norm `max(0, a+b−1) ≠ a×b`. The same applies to mining-derived scores:
- Heuristics Miner `dependency = (fwd−rev)/(fwd+rev+1)` (`CausalDependency.java:39`) is in (−1,1), not [0,1]
- Inductive Miner fitness×precision (`ProcessDiscoveryServiceImpl.java:116`) is a model-quality metric
- Cluster-size heuristics (`ProcessDiscoveryServiceImpl.java:259`) have no probabilistic interpretation

`VerifyResult.confidence` from `DefaultKbVerifier` inherits the raw `InferredFact.value` (`DefaultKbVerifier.java` lookup step 1), which is the PSL MAP value — also uncalibrated.

**The fix**: a `StrengthCalibrator` in `kompile-graph-reasoning/.../grounding/` applies Platt scaling to each signal type before they are stored in `GroundedElement.calibratedConfidence`. Platt scaling is already cited in `graph-hydration-inference-chain-design.md:390` for RotatE distances; we extend it to all score types.

### 3.2 StrengthCalibrator interface

Location: `kompile-graph-reasoning/src/…/grounding/StrengthCalibrator.java` (new, lib-level)

```java
/**
 * Calibrates raw domain scores into the [0,1] probability space.
 * Uses per-signal-type Platt scaling: calibrated = sigmoid(w × rawScore + b).
 * Parameters (w, b) per signal type are fit on labeled held-out data
 * via the GroundingEvalHarness (grounding-evaluation-design.md §5.1).
 *
 * Until calibration parameters are available, falls back to the
 * signal-type-specific heuristic transforms listed in calibrate().
 */
public interface StrengthCalibrator {

    /** Signal types — each gets its own (w, b) pair. */
    enum SignalType {
        PSL_SOFT_TRUTH,       // HlMrfMapInference MAP value ∈ [0,1]
        MEBN_POSTERIOR,       // VariableElimination posterior ∈ [0,1]
        HEURISTICS_DEPENDENCY, // (fwd-rev)/(fwd+rev+1) ∈ (-1,1); map to [0,1] first
        INDUCTIVE_MINER_FM,   // fitness × precision ∈ [0,1]
        ROTATE_DISTANCE,      // exp(-distance/γ) monotone transform, then Platt
        CLUSTER_SIZE,         // hand-rolled heuristic — calibrate to remove magic constants
        DECLARE_CONFIDENCE    // DeclareMiner support/confidence pair
    }

    /**
     * Calibrate a raw score and a KB verify result into a single [0,1] probability.
     * The VerifyResult's status is used as a veto:
     *   - REFUTED → calibrated = 0.0 regardless of rawScore
     *   - UNKNOWN → calibrated = min(rawScore, threshold) where threshold is the
     *               configured UNKNOWN ceiling (default 0.3)
     *   - SUPPORTED → sigmoid(w × rawScore + b) using the signal-type parameters
     */
    double calibrate(double rawScore, SignalType signalType, VerifyResult verifyResult);

    /**
     * Aggregate calibrated confidence for a set of grounded elements.
     * Returns geometric mean of SUPPORTED-element calibratedConfidences;
     * returns 0.0 if any element is REFUTED.
     */
    double calibrateAggregate(List<GroundedElement<?>> elements);

    /**
     * Update calibration parameters from a labeled batch.
     * Called by GroundingEvalHarness when a new eval run completes.
     * Parameters persisted to <dataDir>/data/graph/calibration/<signalType>.json.
     * (Storage format deferred to model-persistence design; default = plain JSON key-value.)
     */
    void updateFromLabeledBatch(SignalType signalType, List<LabeledScore> batch);
}
```

### 3.3 Default (uncalibrated) implementation

`DefaultStrengthCalibrator` (same package, new):
- Ships with unit-variance Platt parameters `(w=1.0, b=0.0)` → `sigmoid(rawScore)` → a reasonable monotone transform but not calibrated to any domain.
- For `HEURISTICS_DEPENDENCY`: pre-maps `(dependency + 1) / 2` to [0,1] before sigmoid (dependency ∈ (−1,1)).
- For `INDUCTIVE_MINER_FM`: identity (fitness×precision already ∈ [0,1]).
- For `ROTATE_DISTANCE`: expects the caller to pre-apply `exp(−distance/γ)` per `graph-hydration-inference-chain-design.md:399`.
- The PSL-soft-truth WARNING from `graph-hydration-inference-chain-design.md:402` is documented in the Javadoc: "PSL MAP values are not probabilities; do not compare directly with MEBN posteriors; use fusion formula in §4.5 of that doc when signals must be combined."

### 3.4 Where calibration parameters are fit and persisted

- **Fit**: the `GroundingEvalHarness` described in `grounding-evaluation-design.md:§5.1` runs a threshold sweep and reports precision/recall curves. After that sweep, logistic regression on the labeled synthetic set produces `(w, b)` per `SignalType`. This is a one-time offline step, re-run when the KB or mining algorithm changes.
- **Persisted**: `<dataDir>/data/graph/calibration/<signalType>.json` (parallel to the `data/graph/` tree used by `GraphPortability` and `GraphHealthSnapshot`). The exact schema is deferred to the model-persistence design but the location is fixed here.
- **Loaded**: `DefaultStrengthCalibrator` reads these files at construction (via `StrengthCalibratorAutoConfiguration` in `kompile-knowledge-graph`); if absent, falls back to unit-variance defaults.

---

## 4. Wiring into Generators

### 4.1 Process mining (the first-slice target)

**Generator**: `ProcessDiscoveryServiceImpl` (and its delegation chain through `InductiveMiner`, `HeuristicsMiner`, `ProcessCausalAnalyzer`, `DeclareMiner`)

**The wiring point** is `ProcessTreeToSuggestion` (`kompile-process-discovery/src/…/mining/convert/ProcessTreeToSuggestion.java:49`), which builds each `SuggestedStep`. This is the surgical insertion point — before it sets `confidence` on the step, it calls:

```java
// NEW: inject KbGroundingService + StrengthCalibrator via constructor
String atomKey = "activity(\"" + step.name() + "\")";
VerifyResult vr = kbGroundingService.verify(factSheetId, atomKey);
double calibrated = calibrator.calibrate(rawFitnessScore, SignalType.INDUCTIVE_MINER_FM, vr);
DerivationTree tree = (mode == EAGER)
    ? kbGroundingService.explain(factSheetId, atomKey, 3)
    : null;
GroundedElement<SuggestedStep> grounded = new GroundedElement<>(
    step, vr, calibrated,
    StrengthLayerResolver.resolve(calibrated),
    atomKey,
    tree != null ? null : "runId:" + currentRunId,
    tree,
    "INDUCTIVE_MINER"
);
```

For directly-follows arcs (the edges between steps), the atom key is `precedes("<from>","<to>")` and the raw score is the `CausalDependency.dependency` field (pre-mapped to [0,1]) with `SignalType.HEURISTICS_DEPENDENCY`.

For causal dependencies from `ProcessCausalAnalyzer.classify()` (`ProcessCausalAnalyzer.java:70`), the atom key is `causes("<from>","<to>")` (or the appropriate `CausalEdgeType` predicate) and the raw score is `dependency` with `SignalType.HEURISTICS_DEPENDENCY`.

**`ProcessSuggestion` change**: add `List<GroundedElement<SuggestedStep>> groundedSteps` alongside the existing `phases` list. The existing `double confidence` field receives `calibrator.calibrateAggregate(groundedSteps)` — no field removal, just value semantics change.

### 4.2 Ontology and rule derivation

**Generator**: `LlmOntologyDerivationService` (at `POST /api/process/ontology/derive`) and the KB-rule generator in `business-process-generation-design.md §3.2 Stage 4` (`ConjunctiveQueryEngine/FolInferenceService` → `GROUNDED_RULE` objects).

**Ontology elements**: each derived `OntologyClass` and `OntologyRelation` gets an atom key `type("<entitySample>","<className>")` and `relation("<relName>","<domain>","<range>")`. `KbGroundingService.verify()` checks whether the KB supports at least one grounding of this class or relation. The `GroundedElement<OntologyClass>` replaces the inline confidence the LLM prompt returned.

**Derived rules**: each `FolRule` produced by `FolInferenceService` already carries `supportingRuleIds` in its `InferredFact` backing. The rule's `GroundedElement` wraps the rule text, uses the inferred fact's atom key, and sets `derivationTree = DerivationTree.build(atomKey, store, justificationIndex)`. These rules are the highest-grounding-confidence elements in the system — they are derived by the engine itself.

### 4.3 GraphRAG answers

**Generator**: `GraphRagQuery` (`kompile-app-core/src/…/graphrag/query/GraphRagQuery.java`) produces retrieved text passages. The FActScore decomposition into atomic claims (each claim = one sentence) is the unit that gets grounded.

**Wiring**: after retrieval, before assembly into the LLM prompt, each candidate claim is:
1. Mapped to an atom key via predicate-extraction heuristic (or an existing NL→atom parser if available)
2. Passed to `KbGroundingService.verify(factSheetId, atomKey)`
3. REFUTED claims are **dropped** (not sent to LLM) — this is the hallucination veto described in `grounding-evaluation-design.md:§4.3`
4. SUPPORTED claims carry a `GroundedElement<String>` (claim text) in the RAG result; the `calibratedConfidence` populates the answer confidence

This is the highest-ROI wiring: it directly reduces the REFUTED Survival Rate metric from `grounding-evaluation-design.md:§4.3`.

### 4.4 Diagrams (Mermaid / BPMN)

**Generator**: `ProcessMermaidExporter` (`kompile-process-discovery/src/…/mining/export/ProcessMermaidExporter.java:32`) and the planned `ProcessBpmnExporter`.

Diagrams are derived from already-mined `ProcessSuggestion` objects, so grounding is inherited: if `groundedSteps` is present on the suggestion, the exporter annotates each node with its `calibratedConfidence` and `StrengthLayer` in a comment or a data attribute. For Mermaid:

```
ApproveInvoice[Approve Invoice ●●●●○ 0.87]:::ESTABLISHED
```

For BPMN, the `<documentation>` element on each `<task>` node carries the `VerifyResult.evidence()` list as a structured annotation. This makes diagrams inspectable — clicking a node in the UI shows its grounding.

---

## 5. Verifiability Surface

### 5.1 Backend: per-element verify endpoint

Add a `POST /api/grounding/verify-element` endpoint (new, in the existing `KbGroundingController` or a sibling `GroundingElementController`):

```
POST /api/grounding/verify-element
Request:
{
  "factSheetId": "...",
  "atomKey": "precedes(\"Approve Invoice\",\"Pay Invoice\")",
  "generatorId": "INDUCTIVE_MINER",
  "maxDepth": 3
}

Response:
{
  "verifyResult": { "status": "SUPPORTED", "confidence": 0.87, "evidence": [...] },
  "calibratedConfidence": 0.84,
  "strengthLayer": "ESTABLISHED",
  "derivationTree": { /* DerivationTree JSON */ },
  "trailRef": "runId:run-42"
}
```

Internally this calls `KbGroundingService.verify()` + `KbGroundingService.explain()` + `StrengthCalibrator.calibrate()` — no new logic, just a new REST shape optimized for per-element UI calls.

### 5.2 Frontend: strength badge + "Why?/Verify" affordance

**Strength badge component** (`strength-badge.component.ts` — new, standalone):

```typescript
@Component({ selector: 'app-strength-badge', standalone: true })
export class StrengthBadgeComponent {
  @Input() calibratedConfidence: number = 0;
  @Input() strengthLayer: 'ESTABLISHED' | 'PROBABLE' | 'SPECULATIVE' | 'SUPPRESSED' = 'PROBABLE';
  @Input() verifyStatus: 'SUPPORTED' | 'REFUTED' | 'UNKNOWN' = 'UNKNOWN';
  @Input() compact = true;   // single dot cluster when true; pill with label when false
  @Output() whyClicked = new EventEmitter<void>();
  // Renders: ●●●●○ 0.87 [ESTABLISHED] [▶ Why?]
}
```

Embedding in generators' output panels:
- **Mining tab** (`process-mining.component.ts`): DFG arc table rows show badge per arc; clicking "Why?" calls `POST /api/grounding/verify-element` with the `precedes(a,b)` atom key and opens `<app-reasoning-trail>` (from `reasoning-trail-explainability-design.md §5.1`).
- **Process map Mermaid**: node click → side panel with badge + `<app-reasoning-trail compact=false>`
- **Ontology derivation panel**: each derived class/relation row shows badge
- **GraphRAG chat**: compact badge beneath each answer bubble (reuse `compact=true` trail chip from `reasoning-trail-explainability-design.md §5.2`)

**REFUTED element rendering**: elements with `verifyStatus=REFUTED` render in a warning style (amber/red border, `●○○○○` dots) with a "Contradicted by KB" tooltip showing `VerifyResult.evidence()`. Generators should normally *not emit* REFUTED elements (they should be filtered before assembly), but if they do (e.g., to show the user what was found and rejected), the badge makes it explicit.

### 5.3 MCP tool exposure

The existing `ask_graph_verify` tool (`kompile-cli-main/src/…/tools/grounding/AskGraphVerifyTool.java`) already calls `KbGroundingService.verify()`. Extend it to return `calibratedConfidence` and `strengthLayer` alongside `verifyResult` — no signature change, just add fields to the JSON response. This makes grounding available to CLI agents without a second tool call.

---

## 6. Calibration Data Flow (the closed loop)

```
Generator emits GroundedElement
       ↓
StrengthCalibrator.calibrate(rawScore, signalType, verifyResult)
  → sigmoid(w×raw+b)  [unit-variance defaults until fit]
       ↓
GroundedElement.calibratedConfidence
       ↓
Human sees badge in UI; corrects via POST /api/kb-grounding/{id}/corrections
  → FactCorrectedEvent (fact-store-audit-tuning-correction-design.md §2.1)
  → PinRecord (atom pinned to corrected value)
  → PslWeightLearningService.updateOnBatch()
       ↓
GroundingEvalHarness runs threshold sweep on labeled set
  (grounding-evaluation-design.md §5.1)
       ↓
StrengthCalibrator.updateFromLabeledBatch()
  → new (w, b) per SignalType
  → written to <dataDir>/data/graph/calibration/<signalType>.json
       ↓
Next generation cycle uses new parameters
```

The loop is intentionally asynchronous: calibration parameters are not updated per-correction (that would be online learning with runaway overfitting risk). They are updated batch-wise after each `GroundingEvalHarness` run. The number of corrections that trigger a re-calibration run is configurable (`kompile.grounding.calibration.minCorrectionsBatch`, default 20).

---

## 7. Phased Implementation Plan

### Phase 1 — GroundedElement + StrengthCalibrator + process-mining wiring (first slice)

**What ships:**
1. `GroundedElement<T>` record in `kompile-graph-reasoning/src/…/grounding/GroundedElement.java`
2. `StrengthCalibrator` interface + `DefaultStrengthCalibrator` (unit-variance Platt defaults) in same package
3. `StrengthCalibratorAutoConfiguration` in `kompile-knowledge-graph` — exposes `StrengthCalibrator` as a `@Bean`, reads calibration JSON from `<dataDir>/data/graph/calibration/` if present
4. Wire `ProcessTreeToSuggestion` to accept `KbGroundingService` + `StrengthCalibrator` (constructor injection); emit `List<GroundedElement<SuggestedStep>> groundedSteps` on `ProcessSuggestion`
5. Wire `ProcessCausalAnalyzer` output into `GroundedElement<CausalDependency>` list, replacing raw `confidence` heuristic
6. `POST /api/grounding/verify-element` endpoint in `KbGroundingController` (or sibling)
7. `strength-badge.component.ts` standalone Angular component (compact mode only)
8. Mining tab: strength badge on DFG arc table and Process Tree node list (compact)

**Tests:**
- `GroundedElementTest` — wrapping, isVerified/isRefuted, evidence delegation
- `DefaultStrengthCalibratorTest` — sigmoid transforms per SignalType, REFUTED veto, aggregate
- `ProcessTreeToSuggestionGroundingTest` — mock `KbGroundingService`; verify `groundedSteps` populated for a 3-node tree
- `VerifyElementEndpointIT` — integration test with seeded `FactStore`, verify response shape

**Does not require:** calibration fit (uses defaults), UI trail panel (Phase 2), ontology/GraphRAG wiring (Phase 3).

### Phase 2 — Strength badge "Why?" → ReasoningTrail integration

After `reasoning-trail-explainability-design.md` Phase 2 ships (`ReasoningTrailComponent` + `POST /api/explain`):
- Wire "Why?" click on strength badge → `ReasoningTrailService.explain(atomKey)` → `<app-reasoning-trail>`
- Add LAZY/EAGER mode toggle to `ProcessTreeToSuggestion` (EAGER = build `DerivationTree` at generation time; LAZY = set `trailRef` only, build on demand)
- Mermaid node click → side panel with badge + trail

### Phase 3 — Ontology/rule derivation + GraphRAG + calibration fit

- Wire ontology derivation service to attach `GroundedElement<OntologyClass>` / `GroundedElement<FolRule>`
- Wire `GraphRagQuery` REFUTED-claim veto + answer confidence via `GroundedElement<String>`
- Run `GroundingEvalHarness` on synthetic dataset → fit (w, b) per `SignalType` → write calibration JSON
- Deploy calibrated `DefaultStrengthCalibrator` (non-unit-variance) → strength badges reflect real precision/recall tradeoffs

### Phase 4 — Diagram annotations + closed correction loop

- Mermaid/BPMN exporter: annotate nodes with calibrated confidence + strength layer
- Wire `FactCorrectedEvent` → batch accumulation → `StrengthCalibrator.updateFromLabeledBatch()` → re-persist calibration JSON
- `StrengthCalibratorAutoConfiguration` watches calibration JSON for changes (file watcher or configurable poll interval) → hot-reload

---

## 8. Forks and Decisions

### Fork A (CRITICAL): Grounding eagerness — EAGER vs LAZY

**EAGER**: `ProcessTreeToSuggestion` calls `KbGroundingService.verify()` and `KbGroundingService.explain(depth=3)` for every SuggestedStep at generation time. Full `DerivationTree` in each `GroundedElement`.
- Pro: trail is immediately available for UI display; no second round-trip
- Con: for a 50-step process, this is 50 × `verify` + 50 × `explain(depth=3)` = ~100 KB-lookups + 50 tree-builds; p50 for `verify` is <1ms (warm cache per `grounding-evaluation-design.md:§2.5`) but `explain(depth=3)` is ~10ms → 500ms total for a 50-step process at generation time

**LAZY**: Generate with `verify` only (cheap); set `trailRef = "runId:<runId>"`. Build `DerivationTree` on demand when user clicks "Why?".
- Pro: generation latency stays sub-50ms; tree only built for elements the user inspects
- Con: `InferredFactStore` must still be accessible when the user clicks (durability concern from `reasoning-trail-explainability-design.md §11.1`)

**Recommendation**: LAZY for Phase 1. Add EAGER as an explicit flag (`kompile.grounding.generation.eagerness=LAZY|EAGER`) defaulting to LAZY. Upgrade default to EAGER in Phase 3 after the eval harness confirms the warm-cache latency target is met.

### Fork B: What counts as "verified" for a derived artifact element

Three options:
- (i) KB-entailment only: element is verified iff `KbVerifier.verify(atomKey) == SUPPORTED`. Elements with UNKNOWN status are still emitted (open-world assumption) but flagged.
- (ii) Evidence-count threshold: SUPPORTED requires ≥ N evidence atoms in `VerifyResult.evidence()` (N=1 is the minimum useful threshold; N=3 aligns with ALCE citation standards from `grounding-evaluation-design.md:§2.3`).
- (iii) Calibrated-confidence cutoff: element is "verified" iff `calibratedConfidence >= ESTABLISHED_THRESHOLD` (0.85 per `fact-store-audit-tuning-correction-design.md:160`).

**Recommendation**: (i) as the primary gate (KB-entailment is binary and deterministic); (iii) as the display layer (strength badge shows the calibrated tier). (ii) is too noisy for process-mining elements where the KB may have sparse coverage. REFUTED always means "do not emit"; UNKNOWN means "emit with SPECULATIVE badge."

### Fork C: Calibration label source — human corrections vs held-out set

- (i) Human corrections from the audit log (`fact-store-audit-tuning-correction-design.md §4.1`): every `FactCorrectedEvent` is a labeled pair (rawScore → pinnedValue). Pro: labels come from actual domain expert feedback. Con: corrections are sparse and biased toward wrong predictions (survivorship bias).
- (ii) Held-out set from `GroundingEvalHarness` (`grounding-evaluation-design.md §3.1`): synthetic labeled dataset with known SUPPORTED/REFUTED/UNKNOWN. Pro: balanced, reproducible, covers all three classes. Con: synthetic — may not match real domain distribution.
- (iii) Both: held-out set for initial calibration; corrections update parameters incrementally via `updateFromLabeledBatch()`.

**Recommendation**: (iii). Fit initial `(w, b)` on the synthetic dataset from `grounding-evaluation-design.md §3.1` (the Employment Graph, 2000 labeled claims). Accumulate human corrections; re-fit when `minCorrectionsBatch` (default 20) is reached. This matches the correction-cascade architecture already designed in `fact-store-audit-tuning-correction-design.md`.

---

## 9. Open Questions

1. **Atom-key coverage gap**: many process-mining atoms (`activity("Approve Invoice")`) are not naturally in the KB post-crawl — they are derived by the miner from graph co-occurrence, not from asserted facts. Calling `verify()` on them returns UNKNOWN. Resolution: generators **assert** their derived atoms as facts via `KbGroundingService.assertFact(factSheetId, fact)` before calling `verify()`. This makes the KB authoritative over the miner's own output. Risk: assertion flood for large event logs. Mitigation: batch-assert only high-dependency arcs (dependency > threshold per `ProcessCausalAnalyzer.java:70`).

2. **`InferredFactStore` warm-cache assumption**: the LAZY mode LAZY relies on the `InferredFactStore` being warm when the user clicks "Why?". If the server restarts between generation and inspection, the tree cannot be built. See `reasoning-trail-explainability-design.md §11.1` for the durability design. Until that is resolved, EAGER mode is the safe default for critical artifacts (ontology, derived rules).

3. **Calibration bootstrap**: `DefaultStrengthCalibrator` ships with unit-variance defaults. Until the `GroundingEvalHarness` is run and produces domain-specific `(w, b)` values, the "calibrated confidence" in `GroundedElement` is not actually calibrated — it is a monotone transform of the raw score. The strength badge must display a disclaimer when using default parameters (e.g., a grey `[est.]` suffix on the layer label).

4. **BPMN diagram grounding annotations**: the planned `ProcessBpmnExporter` does not yet exist. The `<documentation>` element approach works for any XML exporter. When BPMN export ships, follow the convention defined in §4.4 without further design.

5. **GraphRAG claim decomposition**: the FActScore-style claim-level grounding in §4.3 requires splitting multi-fact sentences into atomic claims. A rule-based splitter (split on "and", "but", "because") suffices for Phase 3; an LLM-backed decomposer is Phase 4. The REFUTED veto works at any granularity — even sentence-level vetoing reduces REFUTED Survival Rate substantially per `grounding-evaluation-design.md §4.3`.

6. **GroundedElement serialization**: `GroundedElement<T>` is a generic record. Jackson generic type serialization requires `@JsonTypeInfo` or explicit TypeReference at the REST layer. `DerivationTree.toJson()` (`DerivationTree.java:228`) is hand-rolled; the `GroundedElement` wrapper should use the same pattern (hand-rolled JSON or a registered Jackson module) to avoid a new heavy serialization dependency.

---

## 10. File Paths — Authoritative Index

| New artifact | Location |
|---|---|
| `GroundedElement<T>` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/grounding/GroundedElement.java` |
| `StrengthCalibrator` (interface) | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/grounding/StrengthCalibrator.java` |
| `DefaultStrengthCalibrator` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/grounding/DefaultStrengthCalibrator.java` |
| `StrengthCalibratorAutoConfiguration` | `kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/grounding/StrengthCalibratorAutoConfiguration.java` |
| Calibration parameters | `<dataDir>/data/graph/calibration/<SignalType>.json` |
| `verify-element` endpoint | extend `KbGroundingController` at `kompile-app-main/src/main/java/ai/kompile/app/web/controllers/grounding/KbGroundingController.java` |
| `strength-badge.component.ts` | `kompile-app-main/src/main/frontend/src/app/components/strength-badge/` |

| Existing file modified | Change |
|---|---|
| `ProcessTreeToSuggestion.java` | inject `KbGroundingService` + `StrengthCalibrator`; emit `groundedSteps` |
| `ProcessSuggestion.java:60` | add `List<GroundedElement<SuggestedStep>> groundedSteps`; `confidence` semantics → calibrated aggregate |
| `ProcessCausalAnalyzer.java` | wrap `CausalDependency` output in `GroundedElement`; replace raw `dependency` confidence |
| `ask_graph_verify` response | add `calibratedConfidence` + `strengthLayer` fields |
