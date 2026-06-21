# Hybrid Reasoning LLM View — Implementable-Gaps Plan

**Date**: 2026-06-21  
**Status**: Scoping / pre-implementation  
**Based on**: `llm-interpretable-hybrid-reasoning.md` (research doc)  
**Module under analysis**: `kompile-graph-reasoning` + `kompile-event-attribution` + `kompile-react-agent`

---

## 1. Field Verification: What the Research Assumed vs. What Exists

This is the most important section — it pins every field reference in the research to an actual Java class.

### 1.1 Confirmed Fields (Research is Correct)

| Research claim | Actual class / field | Notes |
|---|---|---|
| `BayesianInferenceResult.inferenceTrace` | `List<InferenceStep> inferenceTrace` in `BayesianInferenceResult` | EXISTS. Each `InferenceStep` has `eliminatedVariable`, `eliminatedTitle`, `operation`, `priorValue`, `posteriorValue`, `contributionWeight`, `factorsInvolved`, `factorVariables`. |
| `BayesianInferenceResult.posteriors` | `Map<String, Double> posteriors` | EXISTS |
| `BayesianInferenceResult.priors` | `Map<String, Double> priors` | EXISTS |
| `BayesianInferenceResult.variableToTitle` | `Map<String, String> variableToTitle` | EXISTS |
| `BayesianInferenceResult.variableToMebnMeta` | `Map<String, Map<String, String>> variableToMebnMeta` | EXISTS — contains `mfragName`, `nodeRole`, `entityType`, `entityId`, `rvName` per variable. |
| `PslInferenceResult.inferredTruth` | `Map<String, Double> inferredTruth` | EXISTS |
| `PslInferenceResult.topViolations` | `List<String> topViolations` | EXISTS — but see Gap A below: these are rendered strings, NOT structured objects. |
| `PslInferenceResult.atomToTitle` | `Map<String, String> atomToTitle` | EXISTS |
| `PslInferenceResult.priors` | `Map<String, Double> priors` | EXISTS |
| `PslInferenceResult.rules` | `List<String> rules` | EXISTS (rendered strings) |
| `PslInferenceResult.stats` | `Map<String, Object> stats` | EXISTS — contains `converged`, atom/ground-rule counts, objective |
| `AttributionConfidence.fromScore()` | Static method on `AttributionConfidence` enum | EXISTS — five bands: INSUFFICIENT/LOW/MODERATE/HIGH/DEFINITIVE with thresholds 0.0/0.1/0.4/0.7/0.9 |
| `AttributionChain.overallConfidence` | `double overallConfidence` | EXISTS |
| `AttributionChain.hops` | `List<CausalHop> hops` | EXISTS |
| `AttributionChain.rootCauseTitle` | `String rootCauseTitle` | EXISTS |
| `AttributionChain.targetEventTitle` | `String targetEventTitle` | EXISTS |
| `AttributionChain.narrative` | `String narrative` | EXISTS (LLM-generated) |
| `AttributionChain.confidenceBand` | `AttributionConfidence confidenceBand` | EXISTS |
| `CausalHop.causeTitle` / `.effectTitle` | Both fields present | EXISTS |
| `CausalHop.causalType` | `CausalEdgeType causalType` | EXISTS |
| `CausalHop.strength` | `double strength` | EXISTS |
| `CausalHop.evidence` | `List<AttributionEvidence> evidence` | EXISTS |
| `AttributionEvidence.summary` | `String summary` | EXISTS |
| `AttributionEvidence.sourceSnippet` | `String sourceSnippet` | EXISTS |
| `AttributionEvidence.sourceReference` | `String sourceReference` | EXISTS |
| `AttributionEvidence.evidenceType` | `EvidenceType evidenceType` | EXISTS |
| `AttributionEvidence.strength` | `double strength` | EXISTS |
| `EntailmentRecord.groundedRvOrAtomKey` | Record component | EXISTS |
| `EntailmentRecord.posterior` | `double posterior` | EXISTS |
| `EntailmentRecord.supportingFindingKeys` | `List<String>` | EXISTS |
| `EntailmentRecord.activatedRules` | `List<String>` | EXISTS |
| `EntailmentRecord.inferenceRunId` | `String inferenceRunId` | EXISTS |
| `FolInferenceResult.entityLikelihoods()` | Method returning `Map<String, Double>` | EXISTS |
| `FolInferenceResult.pslResult()` | Returns `HlMrfMapInference.Result` | EXISTS |
| `FolInferenceResult.converged()` | Delegates to `pslResult.converged()` | EXISTS |
| `HybridReasoner.ScoredEntity` | Inner `record` with `entityId, score, structuralScore, semanticScore` | EXISTS — note: NO `label` or `type` field. Only `entityId`. |
| `HlMrfMapInference.Result.groundRules()` | `List<GroundRule> groundRules` field in result record | EXISTS |
| `GroundRule.distanceToSatisfaction(Map)` | Method computing Łukasiewicz distance | EXISTS |
| `GroundRule.hard()` | `boolean hard` | EXISTS |
| `GroundRule.display()` | `String display` (human-readable rendering) | EXISTS |
| `InferredFact.atomKey`, `.value`, `.confidence` | Record components | EXISTS |
| `InferredFact.toJson()` / `.fromJson()` | Hand-rolled, no jackson-databind | EXISTS — confirmed pattern for `LlmReasoningView.toJson()` |

### 1.2 Missing Fields (Research Assumed, Code Does Not Have)

| Research claim | Status | Impact / Gap label |
|---|---|---|
| `HybridReasoner.ScoredEntity.label` | **MISSING** — ScoredEntity only has `entityId` (no human label, no type). Label must be resolved externally from the graph (`ReasoningGraph.entities()` → `GraphEntity.label()`). | **Gap A1** — serializer must do a graph-entity lookup by ID to populate `ScoredEntityView.label` and `.type`. |
| `PslInferenceResult.topViolations` as structured objects | **PRESENT as `List<String>` only** — these are rendered display strings, not `{ruleDisplay, distanceToSatisfaction, hard}` objects. The real structured violations are in `HlMrfMapInference.Result.hardViolations()` (returns `List<GroundRule>`). | **Gap A2** — `PslInferenceResult.topViolations` cannot be parsed back into `RuleViolationView` fields. The `fromPslResult()` factory method must accept the raw `HlMrfMapInference.Result` as a second parameter OR `PslInferenceResult` needs a new `List<GroundRule> violatingGroundRules` field. |
| `AttributionConfidence.fromScore` called `fromScore()` | EXISTS. However the research also wrote `AttributionConfidence.fromScore` as if it were a view field name. The actual enum static method signature is `fromScore(double score)`. No gap here — just naming clarity. | OK |
| `PslInferenceResult` having a direct `converged` field | **MISSING as top-level field** — must be read from `stats.get("converged")` or derived. | **Gap A3** — minor: serializer reads `stats.get("converged")` cast to Boolean. |
| `ConfidenceBand` as a separate type from `AttributionConfidence` | The research proposes a new `ConfidenceBand` enum in the `view/` package that wraps/delegates to `AttributionConfidence`. The existing `AttributionConfidence` IS the confidence band. | **Design choice** — simplest: `ConfidenceBand = AttributionConfidence` (type alias or literal reuse). No separate enum needed unless the view package must be truly decoupled. Recommend: reuse `AttributionConfidence` directly in `LlmReasoningView`. |
| PSL marginal distributions (mean ± stddev) for calibration | `PslMarginalInference` EXISTS as a class — but the research's P2 (marginal integration) depends on whether `PslMarginalInference` actually populates mean/stddev per atom. | **Gap B** — needs verification (see Gap B below). |
| `FolInferenceResult` entity label lookup | `FolInferenceResult.entityLikelihoods()` maps entity ID → score. No `entityToTitle` map. Label resolution requires the same `ReasoningGraph` used at inference time. | **Gap A4** — same pattern as A1: serializer needs the `ReasoningGraph` as a parameter for label lookup. |

---

## 2. Gaps Map: Research Recommendations → Implementable Units

### Gap 0 (P0-prerequisite): Structured Violations in PslInferenceResult

**Problem**: `PslInferenceResult.topViolations` is `List<String>` (rendered text only). The `LlmReasoningViewSerializer.fromPslResult()` cannot build `RuleViolationView` objects with `distanceToSatisfaction` and `hard` from strings.

**Options**:
1. Add a `List<GroundRule> violatingGroundRules` field to `PslInferenceResult` (populated by `PslReasoningService` from the `HlMrfMapInference.Result`). This is additive and backward-compatible.
2. Require `HlMrfMapInference.Result` as a second parameter to `fromPslResult()`. Clean but callers that only have `PslInferenceResult` are stuck.

**Recommendation**: Option 1 — add `violatingGroundRules` to `PslInferenceResult`. The `PslReasoningService` already has the `HlMrfMapInference.Result` when it builds `PslInferenceResult`; it just needs to call `result.hardViolations()` and store them. One new field, zero API breakage.

**Classes changed**: `PslInferenceResult.java` (one `@Builder.Default List<GroundRule>` field), `PslReasoningService.java` (populate it).  
**Size**: ~15 lines. **Effort**: 30 min. **Tests**: 1 unit test in `PslMarginalInferenceTest` or new `PslInferenceResultTest`.

---

### Gap 1 (P0): LlmReasoningView + LlmReasoningViewSerializer

**Module**: `kompile-graph-reasoning` (lib, infra-free)  
**Package**: `ai.kompile.graph.reasoning.view`

**What**: The core view type and its serializer.

**New classes**:

| Class | Role | Key dependencies |
|---|---|---|
| `LlmReasoningView` | Java `record` holding the full view (as specified in the research doc). Use `AttributionConfidence` directly instead of a separate `ConfidenceBand` enum. | `AttributionConfidence` (existing) |
| `LlmReasoningViewSerializer` | Static factory: `fromHybridResult(List<ScoredEntity>, ReasoningGraph, String question)`, `fromEntailmentRecords(List<EntailmentRecord>, PslInferenceResult, String)`, `fromBayesianResult(BayesianInferenceResult, String)`, `fromFolResult(FolInferenceResult, ReasoningGraph, String)`, `fromAttributionResult(AttributionResult)`, `fromPslResult(PslInferenceResult, String)` | All existing result types; `ReasoningGraph` needed for label lookup (Gaps A1, A4) |
| `VerbalScaleMapper` | Pure verbalization lookup: `softTruthVerbal(double)`, `posteriorVerbal(double)`, `causalQualifier(double)`, `scoreVerbal(double)` using the tables from the research (§3.3) | None |

**Field mapping verified against actual classes**:

- `fromBayesianResult()`: `posteriors` map → pick primary variable → `numericValue`; `priors` → `priorValue`; `variableToTitle` → label lookup; `inferenceTrace` list → `List<ReasoningStepView>` (InferenceStep fields map directly: `eliminatedTitle` → `atomOrVariable`, `operation` → `operation`, `priorValue` → `priorAtStep`, `posteriorValue` → `posteriorAtStep`, `contributionWeight` → `contributionWeight`); `variableToMebnMeta` → MEBN fragment name for the step description; `computationTimeMs` → view field.

- `fromPslResult()`: `inferredTruth` → `topEntities` (after title lookup via `atomToTitle`); `topViolations` (strings) → `ruleViolations[].ruleDisplay` (string-only; `distanceToSatisfaction` and `hard` flag come from the new `violatingGroundRules` field added in Gap 0); `priors` → `priorValue` per atom; `stats.get("converged")` → `converged`.

- `fromAttributionResult()`: `chains` → `causalChains`; each `AttributionChain` → `CausalChainView` (all fields map directly); each `CausalHop` → `HopView` (all fields map directly); `AttributionEvidence` → `EvidenceView` + `SourceCitation`; `targetTitle` → headline.

- `fromHybridResult()`: `ScoredEntity.entityId` → label via `ReasoningGraph.entity(id).label()` (Gap A1); `score`, `structuralScore`, `semanticScore` → direct mapping; `scoreVerbal` from `VerbalScaleMapper`.

- `fromFolResult()`: `entityLikelihoods` → `topEntities` (label via `ReasoningGraph`); `pslResult().groundRules()` → `reasoningTrace` (one step per fired ground rule with `display` as description); `converged()` → view field.

- `fromEntailmentRecords()`: each `EntailmentRecord` → one `ReasoningStepView` (`groundedRvOrAtomKey` → `atomOrVariable`, `posterior` → `posteriorAtStep`, `activatedRules` → `description`, `supportingFindingKeys` → `evidence`).

**`LlmReasoningView.toJson()`**: follow the hand-rolled approach in `InferredFact.toJson()` — no jackson-databind, only jackson-annotations in this module.

**Lib vs. client split**: ALL of `view/` is lib (no Spring, no JPA). The `ExplanationService` interface gains a default `explain(LlmReasoningView)` overload in the lib — the default throws `UnsupportedOperationException`, keeping backward compat.

**Estimated size**: 5 files, ~550 lines total. **Effort**: 1 sprint day.

**Test strategy** (pure unit, no Spring):
- `LlmReasoningViewSerializerTest`: one test per `from*()` method, verify: `engineType`, `headline` non-empty, `overallConfidence` matches `AttributionConfidence.fromScore()`, `reasoningTrace` size matches source list, `toJson()` round-trips without exception.
- `VerbalScaleMapperTest`: boundary values for each scale (0.0, 0.09, 0.10, 0.39, 0.40, 0.69, 0.70, 0.89, 0.90, 1.0).
- Target: 15 unit tests.

---

### Gap 2 (P1): ReasoningToolDefinitions

**Module**: `kompile-graph-reasoning` (lib)  
**Package**: `ai.kompile.graph.reasoning.view`  
**Class**: `ReasoningToolDefinitions`

**What**: A static factory that produces `List<Map<String, Object>>` — the JSON-Schema tool definitions for the five reasoning tools (`reason_over_graph`, `explain_entity`, `get_causal_chain`, `fol_entail`, `check_consistency`) in the format consumed by `Toolkit.getToolSchemas()`.

**Why lib, not client**: The schema definition is pure data (Maps, no Spring). The wiring into `DefaultToolkit` is client-side.

**Wiring point**: The `Toolkit` interface (`kompile-react-agent`) has `registerTool(ToolDefinition)`. A new Spring `@Bean` in `ReActAgentAutoConfiguration` (client-side) creates `ToolDefinition` instances from `ReasoningToolDefinitions.schemas()` and registers them.

**Executor wiring**: Each `ToolDefinition.executor` calls the `LlmReasoningViewController` REST endpoints (via `RestTemplate`) or, for in-process wiring, directly invokes the service beans. The in-process path is cleaner and avoids HTTP overhead for same-JVM calls.

**Estimated size**: 1 lib class (~120 lines), 1 client `@Configuration` class (~80 lines). **Effort**: 3-4 hours.

**Test strategy**: Verify that `ReasoningToolDefinitions.schemas()` returns exactly 5 schemas with `name`, `description`, `parameters` keys; verify schema validates against the tool names expected by the research.

---

### Gap 3 (P1): LlmReasoningViewController (REST adapter)

**Module**: `kompile-event-attribution` controllers OR `kompile-app-main`  
**Package**: Decision needed (see below)

**What**: 5 REST endpoints returning `LlmReasoningView` JSON:
- `POST /api/llm-reasoning/hybrid` → calls `HybridReasoner`, serializes via `LlmReasoningViewSerializer.fromHybridResult()`
- `POST /api/llm-reasoning/explain` → calls `EventAttributionService.explain()` + `LlmReasoningViewSerializer.fromAttributionResult()`
- `POST /api/llm-reasoning/causal` → same attribution path, filters to causal chains only
- `POST /api/llm-reasoning/fol` → calls `FolInferenceService.infer()` + `LlmReasoningViewSerializer.fromFolResult()`
- `POST /api/llm-reasoning/consistency` → calls `PslReasoningService.infer()` + `LlmReasoningViewSerializer.fromPslResult()`, returns only `ruleViolations`

**Package decision**: The existing attribution controllers live in `ai.kompile.event.attribution.controller` (inside `kompile-event-attribution`, which is a Spring module with its own package). The `GlobalExceptionHandler` in `kompile-app-main` already covers `ai.kompile.event.attribution.controller` in its `basePackages`. Therefore `LlmReasoningViewController` SHOULD go in `ai.kompile.event.attribution.controller` (same module as `PslController`, `BayesianNetworkController`, `EventAttributionController`). This avoids adding a new package to `GlobalExceptionHandler.basePackages`.

Alternatively, putting it in `ai.kompile.app.web.controllers` (app-main) requires duplicating service dependencies into app-main's compile scope. The event-attribution module is the better home.

**Dependencies**: Requires Gap 1 (`LlmReasoningView` + serializer). The controller injects existing services (`EventAttributionService`, `PslReasoningService`, `FolInferenceService`, `HybridReasoner` — the last is infra-free but needs a `ReasoningGraph` provider, which must come from `KnowledgeGraphService`).

**KG → ReasoningGraph adapter gap**: The `HybridReasoner` takes a `ReasoningGraph` (the lib's graph model). The live graph is `GraphNode/GraphEdge` in the `KnowledgeGraphService`. A `KgToReasoningGraphAdapter` (converting `KnowledgeGraphService` output to `ReasoningGraph`) likely already exists inside `kompile-event-attribution` (the `KgPslProgramBuilder` and `BayesianNetworkBuilder` already do this for PSL/Bayes). The `LlmReasoningViewController` can reuse whatever conversion path those builders use.

**Estimated size**: 1 controller (~200 lines), 5 request record types (~50 lines). **Effort**: half a sprint day.

**Test strategy**: `@WebMvcTest` or `@SpringBootTest` slice, mock service responses, verify: HTTP 200, response body parses as `LlmReasoningView` JSON, `engineType` field is correct per endpoint.

---

### Gap 4 (P1): ReAct Agent Tool Registration

**Module**: `kompile-react-agent`  
**Class to modify**: `ReActAgentAutoConfiguration`

**What**: Register the 5 reasoning tools from `ReasoningToolDefinitions` into the `DefaultToolkit`. Each executor calls the `LlmReasoningViewController` endpoints (via `RestTemplate` @Bean already present in the Spring context) and returns the JSON view as the tool observation string.

**Pre-condition**: Gap 3 (REST controller) must be running.

**Estimated size**: ~50 lines in `ReActAgentAutoConfiguration` + a `ReasoningToolExecutors` helper class (~80 lines). **Effort**: 2-3 hours.

**Test strategy**: Integration test with a mock `LlmReasoningViewController` (MockMvc), verify that a `DefaultToolkit` with the registered tools can `execute()` a `ToolCall(name="reason_over_graph", ...)` and receive a JSON observation string back.

---

### Gap 5 (P1): AttributionLlmService — Accept LlmReasoningView as Context

**Module**: `kompile-event-attribution`  
**Class**: `AttributionLlmService`

**What**: Add an overload of `synthesizeExplanation()` that accepts an `LlmReasoningView` instead of raw `List<AttributionChain>`. The view's `causalChains`, `evidence`, and `citations` fields provide a structured, verbalized context that replaces the hand-rolled `formatChainForLlm()` formatting currently in `AttributionLlmService`. The existing `formatChainForLlm()` can be kept for backward compatibility.

**Why**: The existing `formatChainForLlm()` re-implements verbalization that `LlmReasoningViewSerializer` now owns centrally. Feeding the serializer's output to the LLM ensures consistent verbalization.

**Estimated size**: ~40 lines (new overload + `LlmReasoningView → prompt` conversion). **Effort**: 1-2 hours.

**Test strategy**: Unit test with a mock `LanguageModel` (capture the prompt string), verify the prompt contains verbalized confidence bands and hop qualifiers from the view.

---

### Gap 6 (P0-complement): ExplanationService — LlmReasoningView Overload

**Module**: `kompile-graph-reasoning` (lib)  
**Class**: `ExplanationService`

**What**: Add the default overload `explain(LlmReasoningView view)` that the research proposes. Default implementation throws `UnsupportedOperationException` so existing implementors need not change.

**Estimated size**: 5 lines. Done in the same commit as Gap 1.

---

### Gap 7 (P2 — deferred): PSL Marginal Calibration

**Module**: `kompile-graph-reasoning`  
**Class**: `PslMarginalInference` (already exists)

**Pre-condition**: `PslMarginalInference` must already compute per-atom mean and stddev. Verify with `PslMarginalInferenceTest`.

**What**: When `PslMarginalInference` is used (not just `HlMrfMapInference`), `LlmReasoningViewSerializer.fromPslResult()` can populate a `calibrationNote` field and an uncertainty interval `numericValue ± stddev`.

**Dependency on engine gap**: If `PslMarginalInference.AtomMarginal` (the `AtomMarginal` class exists in `psl/`) already carries mean and variance, this is a serializer change. If marginal inference is incomplete, this must wait.

**Estimated size**: ~30 lines in serializer + `calibrationNote` field in `LlmReasoningView`. **Effort**: 1-2 hours once the engine gap is resolved.

---

### Gap 8 (P2 — deferred): InferredFact Integration in LlmReasoningView

**What**: Add `List<InferredFact> inferredFacts` to `LlmReasoningView`, populated from `InferredFactStore` for the atoms involved in the current inference. This lets the LLM see previously computed conclusions that are being cited as evidence.

**Dependencies**: Requires a `InferredFactStore` lookup at serialization time. The serializer is currently infra-free (no Spring). Two options:
1. Pass `List<InferredFact>` as a parameter to the serializer factory methods (keeps lib infra-free).
2. Move this enrichment to the client-side controller (Gap 3) which has Spring context and can inject `InferredFactStore`.

**Recommendation**: Option 2 — the controller queries `InferredFactStore` and passes the results into `fromPslResult()` / `fromBayesianResult()` via an overload parameter. This preserves the lib's zero-infra constraint.

**Estimated size**: ~20 lines serializer + ~30 lines controller. **Effort**: 1 hour once Gap 1 and Gap 3 are complete.

---

## 3. Things in the Research That Are NOT Cleanly Implementable (and Why)

### 3.1 `RuleViolationView` with `distanceToSatisfaction` and `hard` fields from `PslInferenceResult`

**Problem**: `PslInferenceResult.topViolations` is `List<String>` — pre-rendered text, not structured. The `distanceToSatisfaction` and `hard` Boolean are unavailable from the domain object alone.

**Workaround**: Gap 0 above — add `List<GroundRule> violatingGroundRules` to `PslInferenceResult`. Without this fix, the `RuleViolationView` can only populate `ruleDisplay` (from the string) and must leave `distanceToSatisfaction` as 0.0 and `hard` as unknown.

**Cleanly implementable?** Yes, with Gap 0. NOT without Gap 0.

### 3.2 `ScoredEntityView.label` and `.type` for HybridReasoner output

**Problem**: `HybridReasoner.ScoredEntity` is a record with only `entityId, score, structuralScore, semanticScore`. No label, no type.

**Workaround**: The serializer `fromHybridResult()` must receive the `ReasoningGraph` as a second parameter and call `graph.entity(entityId)` to get `GraphEntity.label()` and `GraphEntity.type()`. `ReasoningGraph` is an interface; `GraphEntity` is also an interface with `label()` and `type()` accessors. This works cleanly as long as the caller provides the same graph used for ranking.

**Cleanly implementable?** Yes, with the `ReasoningGraph` parameter. The research pseudo-code omitted this parameter but it's a minor adjustment.

### 3.3 `FolInferenceResult` entity label lookup

Same problem as 3.2 — `entityLikelihoods()` returns entity IDs only. Same fix: pass `ReasoningGraph` to `fromFolResult()`.

### 3.4 `PslInferenceResult.converged` as a top-level field

**Problem**: Not a direct field. Must read `stats.get("converged")` (type `Object`, likely a `Boolean`).

**Workaround**: `Boolean converged = (Boolean) result.getStats().getOrDefault("converged", false)`. Works but fragile on type cast.

**Cleanly implementable?** Yes with a null-safe cast. A cleaner fix: add a convenience `converged()` method to `PslInferenceResult` that reads the stats map. One-liner addition to the domain class.

### 3.5 ReAct Integration Requires an In-Process `ReasoningGraph` Builder

The P1 REST tools (`reason_over_graph`, `explain_entity`) need to build a `ReasoningGraph` from the live `KnowledgeGraphService` for a given `factSheetId`. The existing `KgPslProgramBuilder` and `GraphBayesianNetworkBuilder` already do this conversion internally for PSL/Bayesian inference. However there is no standalone public `KgToReasoningGraph` converter. The controller (Gap 3) can either call the existing service paths (which internally build the `ReasoningGraph`) and pass the result to the serializer, or extract the conversion logic into a shared utility.

**Cleanly implementable?** Yes — the conversion path exists, it just needs to be exposed or the controller should call through the existing service layer (which is the right architectural approach anyway: `EventAttributionService.explain()` → serializer, not raw graph surgery).

### 3.6 P2 Marginal Calibration (PSL Uncertainty Intervals)

**Problem**: The research proposes verbalizing `P(alice = active) = 0.68 ± 0.12`. This requires `PslMarginalInference` to produce per-atom mean and standard deviation. `PslMarginalInference` exists as a class and `AtomMarginal` exists as a domain type — but whether it actually computes stddev needs verification from the `PslMarginalInferenceTest`. This is a deferred gap.

**Cleanly implementable now?** Conditional — depends on engine completeness. Safe to defer to P2.

---

## 4. Priority / Build Order

```
Step 1 (P0 — 1 day):
  - Gap 0: Add `violatingGroundRules` to PslInferenceResult (30 min)
  - Gap 0b: Add `converged()` method to PslInferenceResult (10 min)
  - Gap 1: view/ package — LlmReasoningView, LlmReasoningViewSerializer, VerbalScaleMapper (1 day)
  - Gap 6: ExplanationService.explain(LlmReasoningView) default (5 min, in same commit)
  Test: 15 unit tests in kompile-graph-reasoning

Step 2 (P1 — 1 day):
  - Gap 2: ReasoningToolDefinitions (lib, 3 hours)
  - Gap 3: LlmReasoningViewController in kompile-event-attribution (4 hours)
  Test: @WebMvcTest for 5 endpoints

Step 3 (P1 — 0.5 day):
  - Gap 4: ReAct tool registration in ReActAgentAutoConfiguration (2 hours)
  - Gap 5: AttributionLlmService.synthesizeExplanation(LlmReasoningView) (1 hour)
  Test: integration test with mock controller

Step 4 (P2 — deferred):
  - Gap 7: PSL marginal calibration (after PslMarginalInference engine verified)
  - Gap 8: InferredFact integration in view (controller-side enrichment)
```

**Total Steps 1–3**: ~2.5 sprint days, 0 new dependencies, no breaking changes.

---

## 5. Lib vs. Client Split Summary

| Component | Layer | Module | Rationale |
|---|---|---|---|
| `LlmReasoningView` (record) | **LIB** | `kompile-graph-reasoning` → `view/` | Pure data; no Spring/JPA |
| `LlmReasoningViewSerializer` | **LIB** | `kompile-graph-reasoning` → `view/` | No Spring; takes result types + ReasoningGraph |
| `VerbalScaleMapper` | **LIB** | `kompile-graph-reasoning` → `view/` | Pure verbalization tables |
| `ReasoningToolDefinitions` | **LIB** | `kompile-graph-reasoning` → `view/` | Pure Map/String schema; no Spring |
| `ExplanationService.explain(LlmReasoningView)` overload | **LIB** | `kompile-graph-reasoning` → `explain/` | Default no-op method on existing interface |
| `LlmReasoningViewController` | **CLIENT** | `kompile-event-attribution` (controller package) | Needs Spring, KnowledgeGraphService, existing services |
| `ReActAgentAutoConfiguration` additions | **CLIENT** | `kompile-react-agent` | Spring @Configuration, needs RestTemplate |
| `AttributionLlmService.synthesizeExplanation(LlmReasoningView)` | **CLIENT** | `kompile-event-attribution` | Spring @Service, needs LanguageModel |
| `PslInferenceResult.violatingGroundRules` (new field) | **LIB** | `kompile-graph-reasoning` (domain) | Pure domain model change |

---

## 6. Dependencies Summary

No new Maven dependencies are required for any of the gaps. The `view/` package uses:
- `jackson-annotations` (already in `kompile-graph-reasoning/pom.xml`) — for `@JsonIgnore` if needed
- Lombok (already present) — for builder pattern on view subtypes if not using records
- The existing `kompile-graph-reasoning` internal classes (all in same module)

The REST controller (Gap 3) uses:
- Spring MVC (already in `kompile-event-attribution`)
- `kompile-graph-reasoning` (already a dependency of `kompile-event-attribution`)

The ReAct registration (Gap 4) uses:
- `RestTemplate` (Spring, already available)
- `kompile-graph-reasoning` (new `view/` API becomes visible — may need adding `kompile-graph-reasoning` to `kompile-react-agent` pom if not already there)

---

## 7. `kompile-react-agent` pom — Dependency Gap for ReasoningToolDefinitions

**Verified**: `kompile-react-agent` depends on: `kompile-cli-common`, `kompile-app-core`, `kompile-orchestrator`, `kompile-filter-chain`, `kompile-evaluation`. It does NOT depend on `kompile-graph-reasoning` or `kompile-event-attribution`.

**Consequence**: The `ReasoningToolDefinitions` class (in `kompile-graph-reasoning/view/`) is NOT on the classpath of `kompile-react-agent`. Two options:

**Option A** (cleaner): Add `kompile-graph-reasoning` as a `<scope>compile</scope>` dependency to `kompile-react-agent/pom.xml`. This is additive and the module is infra-free (no Spring, no JPA), so the transitive dependency is light.

**Option B** (no pom change): Move `ReasoningToolDefinitions` to `kompile-event-attribution` and have the `ReActAgentAutoConfiguration` pick up the tool schemas via a Spring `@Autowired List<ToolDefinition>` that `kompile-event-attribution` exposes as `@Bean`s. `kompile-app-main` aggregates both modules and `@Autowired` wiring handles the rest at runtime.

**Recommendation**: Option A for the schema class (it's pure data — no reason to put it in the Spring module). Option B for the executor wiring (the executors need Spring services anyway). Use both together: `ReasoningToolDefinitions.schemas()` visible via Option A; executor `@Bean`s wired via Option B.

---

## Document Path

`/home/agibsonccc/Documents/GitHub/kompile/docs/architecture/hybrid-reasoning-implementation-plan.md`
