# Learning Loop: Deployment, Rules, and Process-Candidate Integration Design

**Scope:** Downstream-integration concerns for the living-KB learning loop.  
Companion passes: (1) core train/update/UI, (2) sources-trigger/MEBN/multi-source/promotion.  
**Status:** Analysis + design — no code changes.

---

## 1. Model-Staging Deployment of Trained Reasoning Models

### 1.1 Current State

**What the staging lifecycle actually does.**  
`StagingService` (`kompile-app/kompile-models/kompile-model-staging/src/main/java/ai/kompile/staging/staging/StagingService.java`) implements a real download-convert-validate-promote pipeline for SameDiff/ONNX/GGUF embedding and VLM models.  
The path is: `stageModel` → download to `.staging/` → convert to `.sdz/.fb` → validate → `promoteModel` → write `ModelEntry` with `ModelStatus.ACTIVE` into the registry JSON (line 279-285).  
`/api/staging/models/{modelId}/activate` calls `registry.setActiveModel(modelId)` which demotes all other entries of the same type (RegistryService line 365).  
Inference model selection: `KompileModelManager` filters by `ModelEntry::isActive` — only entries with `ACTIVE` status are served (RegistryService line 233).

**What `TrainingService` actually does.**  
`TrainingService` (`kompile-app/kompile-models/kompile-model-staging/src/main/java/ai/kompile/staging/training/TrainingService.java`) manages SameDiff fine-tuning jobs with SSE-based live log streaming.  
At completion (line 337-355) it sets `TrainingJobStatus.status="COMPLETED"` and `outputModelPath` but makes **no call to `StagingService.promoteModel`**, no call to `registryService.addModel`, and no connection to any `WeightStore`.  
Checkpoint files land at `<trainingJobsDir>/<jobId>/checkpoint-epoch-N.fb` (line 314-318). They stay there permanently unless the operator manually POSTs the path to `/api/staging/upload-and-stage`.

**How PSL weights are persisted (separate system).**  
`WeightStore` (`kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/learning/WeightStore.java`) holds PSL rule weights only — not neural-network parameters.  
`KbCorrectionService` (`kompile-knowledge-graph/.../grounding/KbCorrectionService.java`) calls `weightLearner.updateOnBatch(program, Map.of(atomKey, effectiveValue), 3)` and `ws.save(factSheetId + ":program", updated.rules())` (lines 137-148).  
The `WeightStore` in production is `InMemoryWeightStore` (line 270 of `KbCorrectionService`) — the `FileBackedWeightStore` exists but is not injected.  
`IncrementalReasoningOrchestrator.buildProgramFromFactStore()` (line 408) builds a fresh program from the `FactStore` every cascade. It never reads from `WeightStore`. The only bridge is `correctionService.registerProgram(factSheetId, program)` (line 320 of `IncrementalReasoningOrchestrator`), which snapshots the current program for the correction service's next mini-batch update — but the **learned weights from the snapshot are never loaded back** into the next cascade.

**KGE embedding training.**  
`KGEmbeddingJobService` (line 179-181) calls `adapter.storeEmbeddings(model, factSheetId, version)` and `storageService.storeEmbeddings(...)` after training.  
`MatrixKgEmbeddingGraphAdapter` writes embedding vectors into each `GraphNode.metadata` under a `kge_embedding_v<version>` key. There is no call to `StagingService.promoteModel` and no interaction with the model registry.  
`KGEmbeddingRetriever` loads embeddings back on demand (line 539) — but it is not used by inference or process discovery.

**Summary — gap table:**

| Model type | Trained? | Persisted? | Staged? | Activated? | Inference loads active version? |
|---|---|---|---|---|---|
| SameDiff LLM/embedding | Yes (TrainingService) | Checkpoint `.fb` file | NO | NO | N/A (orphaned) |
| PSL rule weights | Yes (KbCorrectionService) | In-memory only (FileBackedWeightStore not injected) | NO | NO | NO (orchestrator rebuilds fresh) |
| KGE RotatE/TransE embeddings | Yes (KGEmbeddingJobService) | Graph node metadata | NO | NO | Not consumed by any reasoning or mining |
| SameDiff embedding (pre-trained) | N/A (download) | registry JSON | YES (existing lifecycle) | YES (ModelStatus.ACTIVE) | YES (ModelManager filters ACTIVE) |

### 1.2 Design: Train → Stage → Activate → Serve

**Principle:** Treat PSL weight files and KGE model files as first-class staging artifacts with the same lifecycle as SameDiff embedding models. The trigger is the completion event from each training path.

**D1-A — SameDiff fine-tune auto-promotion.**  
`TrainingService.runTrainingJob()` already produces `outputModelPath` at `COMPLETED` status (line 354).  
Add a post-training hook:

```
// In TrainingService, after building completedStatus (line 355):
if (autoPromote && stagingService != null) {
    String modelId = request.getModelId() + "-trained-" + jobId;
    ModelType type  = ModelType.fromModelId(request.getModelId());  // resolve from config
    stagingService.promoteFromLocalPath(modelId, type, Path.of(outputPath), null);
    eventPublisher.publishEvent(new TrainedModelPromotedEvent(this, jobId, modelId));
}
```

`StagingService.promoteFromLocalPath(modelId, type, localPath, metadata)` — new overload that skips download, goes directly to the verify/promote step. This path already exists structurally (the promote step copies from `verifiedDir` to `productionDir`); the new overload would stage from a local path.

**D1-B — PSL weight materialization via FileBackedWeightStore.**  
Replace `InMemoryWeightStore` injection in `KbCorrectionService.getWeightStore()` with `FileBackedWeightStore.fileWeightStoreFor(factSheetId)` (already available at `kompile-knowledge-graph/.../persistence/FileBackedWeightStore.java`). No API change — `WeightStore` interface is the same.  
In `IncrementalReasoningOrchestrator.doReground()`, after `buildProgramFromFactStore(factStore)` and before `loadProjectPslRules(program)`, add:

```
// Load last-learned weights for this factSheet
if (correctionService != null) {
    PslProgram withLearnedWeights = correctionService.applyLearnedWeights(factSheetId, program);
    if (withLearnedWeights != null) program = withLearnedWeights;
}
```

`KbCorrectionService.applyLearnedWeights(factSheetId, program)` reads from `WeightStore.latest(factSheetId + ":program")` and overwrites rule weights in `program` where names match. This closes the learn → persist → reload loop without changing the cascade structure.

**D1-C — KGE embedding versioning via staging registry.**  
`KGEmbeddingJobService.storeEmbeddings()` currently writes to graph node metadata (a flat JSON blob). Add a parallel path: after `storeEmbeddings` succeeds, export the model weights to `<dataDir>/models/kge/<factSheetId>-v<version>.json` and call `registryService.addModel(ModelEntry.kgeEntry(...))` with a new `ModelType.KGE`. Mark the entry ACTIVE, demoting any prior KGE entry for the same `factSheetId`.  
Inference selects which embedding version to use by querying `registryService.getActiveModels(ModelType.KGE, factSheetId)`.

**D1-D — Version and rollback.**  
The existing `RegistryService.setActiveModel(modelId)` / `activateModel` + `deactivateModel` pattern already provides rollback: POSTing `/api/staging/models/{olderModelId}/activate` demotes the current active and promotes the prior version. No schema changes needed — rollback is a `setActiveModel` call on any previously-STAGED entry.

---

## 2. Rule Creation and Propagation

### 2.1 Current State

**Hand-authored PSL rules — functional seam, no files.**  
`IncrementalReasoningOrchestrator.loadProjectPslRules(program)` (lines 360-391) reads all `*.psl` files from `<dataDir>/rules/`. The seam works. No such files exist in the repo — the directory is not created on init.

**Auto-generated soft-propagation rules — transient, low information.**  
`buildProgramFromFactStore()` (lines 408-454) adds `0.8: pred(?X) -> derived_pred(?X)` for every observed predicate. These are generic structural stubs, not semantic rules. They are rebuilt identically on every cascade — learned weights on them do not survive (per D1-B gap above).

**ProcessCausalAnalyzer rules — computed, discarded.**  
`ProcessCausalAnalyzer.generatePslRules(dependencies)` (lines 92-104) produces grounded, weighted PSL rules like:

```
0.73: State("approve") & Link("approve","notify") -> State("notify") ^2
```

These derive from χ²-significant directly-follows arcs in the event log and carry the empirical dependency strength as the weight. `MiningDiscoveryController.causal()` (line 80) returns these via `GET /api/process/mining/causal` as a JSON REST response. There is **no code path** that writes these rules to `<dataDir>/rules/*.psl`, injects them into `IncrementalReasoningOrchestrator`, or serializes them to `WeightStore`. They are computed and discarded.

**GraphRuleConfig (Phase 4) — event triggers, not PSL rules.**  
`GraphRuleConfig` (`kompile-graph-change-tracking/.../domain/GraphRuleConfig.java`) is a threshold-based event trigger (minNodesCreated, minEdgesCreated → LOG/WEBHOOK). It is structurally separate from the PSL reasoning engine and feeds nothing into the PSL program.

**KbCorrectionService weight-learning — partial loop.**  
Correction (`correct()` lines 136-148) calls `weightLearner.updateOnBatch(program, Map.of(atomKey, effectiveValue), 3)` and saves to `WeightStore`. `IncrementalReasoningOrchestrator` registers the program snapshot via `correctionService.registerProgram(factSheetId, program)` (line 320). But the saved weights are never re-read by the orchestrator on the next cascade (confirmed: no `WeightStore` import in `IncrementalReasoningOrchestrator`). Loop is broken at re-load.

**DeclareConstraint.toPslRule() — test-only.**  
`DeclareConstraint` has a `toPslRule()` method returning a string but it is called only in unit tests — not in the cascade or mining pipeline.

**Summary — rule creation matrix:**

| Rule source | Created? | Persisted? | Fed into cascade? | Confidence? |
|---|---|---|---|---|
| ProcessCausalAnalyzer.generatePslRules | YES | NO | NO | YES (dependency strength) |
| KbCorrectionService weight updates | YES (weight delta only) | In-memory only | NO (reload broken) | Implicit (gradient) |
| loadProjectPslRules from *.psl files | External only | Via filesystem | YES | External (weight in file) |
| DeclareConstraint.toPslRule | YES (test only) | NO | NO | NO |
| buildProgramFromFactStore stubs | YES (transient) | NO | YES (same cascade) | 0.8 fixed |
| Association rule mining | NOT IMPLEMENTED | — | — | — |

### 2.2 Design: Rule Creation from the Learned KB + Propagation

**D2-A — Rule persistence from ProcessCausalAnalyzer.**  
`MiningProcessDiscoveryService.causalAnalysis()` (line 197) returns `ProcessCausalModel` with `pslRules()`.  
Add a `persistCausalRules(Long factSheetId, ProcessCausalModel causalModel)` step that:
1. Writes rules to `<dataDir>/rules/<factSheetId>-mined.psl` (replaces file on each run).
2. Records the run timestamp in the filename so rollback can select an older version.
3. Returns the count of rules written.

The next cascade call to `loadProjectPslRules(program)` picks up the file automatically — no orchestrator changes needed because the seam already reads all `*.psl` files from the rules directory.

Trigger: call `persistCausalRules()` from `discoverForFactSheet()` after building the suggestion, and also expose `POST /api/process/mining/causal/persist?factSheetId=X` so operators can do it explicitly.

**D2-B — Learned weight reload in cascade.**  
Close the broken loop (see D1-B). `IncrementalReasoningOrchestrator.doReground()` should call `correctionService.applyLearnedWeights(factSheetId, program)` before `loadProjectPslRules`. This re-applies the saved `WeightStore` rule weights to the freshly-built program, so mini-batch gradient updates from human corrections accumulate across cascade runs.

**D2-C — Association rule mining from InferredFactStore.**  
A new `AssociationRuleMiner` component reads the `InferredFactStore` (all `InferredFact` atoms above a confidence threshold) and applies a minimal Apriori pass over co-occurring predicate/entity pairs to produce candidate implications:

```
confidence: State(A) → State(B)   [support: 0.42, lift: 1.8]
```

Rules above a `minLift` threshold are serialized as PSL rules with the confidence as weight. These go to `<dataDir>/rules/<factSheetId>-associated.psl`. The miner runs post-cascade (as a `GroundingCascadeHook` or triggered from the enrichment pipeline).

**D2-D — Rule versioning and rollback.**  
Rules in `<dataDir>/rules/` should follow the same versioning convention as graph snapshots (Phase 5): each rule file is immutable once written; a symlink or config file (e.g. `active-rules.json`) lists which rule files are active. `loadProjectPslRules` reads from the symlink target. Rollback = update the symlink. This provides the same activate/deactivate pattern as the model registry.

**D2-E — Propagation across fact-sheets.**  
Rules mined from fact-sheet A may be relevant to fact-sheet B (same ontology schema). A `RulePropagationService` reads `active-rules.json` for the source fact-sheet and copies the rule file to `<dataDir>/rules/<targetFactSheetId>-propagated-<sourceId>.psl` for fact-sheets sharing the same `ontologySchemaId` (from the Phase 6 graph-ontology binding). Each target fact-sheet then loads these propagated rules on its next cascade.

---

## 3. Integration with Business-Process Candidate Creation

### 3.1 Current State

**Process candidate pipeline — what is wired.**  
`MiningProcessDiscoveryService.discoverForFactSheet()` (line 138):

```
EventLog eventLog = extractLog(factSheetId, anchorType);
ProcessTree tree  = new InductiveMiner(noiseThreshold).mine(eventLog);
ProcessSuggestion suggestion = ProcessTreeToSuggestion.convertGrounded(
    tree, eventLog, name, kbGroundingService, calibrator, factSheetId);
```

`ProcessTreeToSuggestion.convertGrounded()` (line 86) calls `kbGroundingService.verify(factSheetId, atomKey)` per activity leaf — this queries `InferredFactStore` (output of the cascade). It calibrates with `PlattCalibrator`/`StrengthCalibrator.SignalType.INDUCTIVE_MINER_FM`. Role bindings come from `RoleBindingExtractor.applyRoleBindings()`.

**What the ProcessSuggestion data model holds.**  
`ProcessSuggestion` (`kompile-process-discovery/.../ProcessSuggestion.java`) has:
- `groundedSteps`: `List<GroundedElement<SuggestedStep>>` — populated with KB verify results.
- `bayesianPosteriors` / `bayesianPriors`: `Map<String, Double>` — declared but **not populated** by `discoverForFactSheet()`.
- `structuredEvidence`: `List<StructuredEvidence>` with types CAUSAL/TEMPORAL/STATISTICAL/BAYESIAN.
- `confidence`: calibrated geometric mean of step confidences.

**What is NOT wired into process candidates.**  
Confirmed by reading `MiningProcessDiscoveryService` and `ProcessTreeToSuggestion`:
- **PSL rule weights**: not read. The orchestrator builds its own program per cascade; no weights from `WeightStore` feed into step confidence computation.
- **MEBN priors**: not injected. `bayesianPosteriors`/`bayesianPriors` fields exist but are only populated by `ProcessBayesianInference.infer(dfg, evidenceActive)` via the separate `/api/process/mining/bayesian` endpoint.
- **KGE embeddings (RotatE)**: not used. `EventLogExtractor.extractForFactSheet()` projects graph nodes by entity type/label — no embedding-similarity computation.
- **Mined causal PSL rules**: not fed back. `causalAnalysis()` is a separate endpoint; its rules are not re-read by `discoverForFactSheet()`.
- **Promoted facts as preconditions**: the `InferredFactStore` is read only for binary VERIFIED/UNKNOWN/REFUTED classification of activity atoms. The soft truth value (0.0–1.0) is not used to gate which steps are included — all activities from the event log become steps regardless of inferred confidence.

**One implicit connection — cascade → grounding → confidence.**  
The cascade `IncrementalReasoningOrchestrator.runFullReground()` writes inferred facts into `InferredFactStore`. `KbGroundingService.verify()` reads from `InferredFactStore`. So cascade output does flow into step confidence scores — but only as a binary VERIFIED flag, not as a soft probability that shifts the calibrated confidence.

**Summary — consumption matrix for process candidates:**

| Signal | Present in ProcessSuggestion? | Fed by discoverForFactSheet? | Source |
|---|---|---|---|
| KB grounding (InferredFactStore lookup) | YES (groundedSteps) | YES | KbGroundingService.verify |
| PSL rule weights | NO | NO | — |
| MEBN posteriors | YES (field) | NO (separate endpoint only) | ProcessBayesianInference |
| KGE embeddings | NO | NO | — |
| Causal PSL rules | NO | NO | — |
| Promoted facts (soft truth) | NO | NO | — |

### 3.2 Design: Close the Loop — Learn → Rules → Process Candidates

**D3-A — Use InferredFact soft truth for activity gating.**  
In `ProcessTreeToSuggestion.convertGrounded()`, replace the current binary VERIFIED/UNKNOWN check with a soft-confidence gate:

```java
// Current: sets StrengthBand from VerifyResult only
// Proposed: also read the InferredFact.value() from KbGroundingService.latestValue(factSheetId, atomKey)
double inferredValue = kbGrounding.latestValue(factSheetId, atomKey).orElse(0.5);
// Blend inductive-miner FM score with inferred value:
double blendedScore = 0.6 * rawFmScore + 0.4 * inferredValue;
```

Activities with `inferredValue` below a configurable threshold (e.g. `< 0.1`) become `StrengthBand.REFUTED` even if the event log supports them, acting as a fact-level filter on which steps are credible.

`KbGroundingService` needs a `latestValue(long factSheetId, String atomKey)` method that delegates to `InferredFactStore.latest(atomKey).map(InferredFact::value)`.

**D3-B — Inject mined causal rules as structured evidence.**  
After `discoverForFactSheet()` calls `ProcessTreeToSuggestion.convertGrounded()`, call `causalAnalysis(factSheetId, anchorType)` on the same event log (already extracted) and add the resulting `CausalDependency` list to `ProcessSuggestion.structuredEvidence` as `StructuredEvidence.type = "CAUSAL"` entries, with the dependency strength as `score` and the pair of activity names as `description`.

This requires no new fields — `StructuredEvidence.type` already has the `CAUSAL` value (line 173 of `ProcessSuggestion`).

**D3-C — Bayesian posteriors from inline inference.**  
`ProcessBayesianInference.infer(dfg, evidenceActive)` is currently only called via `/api/process/mining/bayesian`. Call it inline from `discoverForFactSheet()` and populate `suggestion.setBayesianPosteriors(result.posteriors())` and `setBayesianPriors(result.priors())`. This is a pure-Java in-process call (no LLM, no I/O) so latency impact is low.

**D3-D — PSL-learned rule weights as step pre-condition scores.**  
After D1-B (learned weights reload) and D2-A (causal rule persistence) are complete:  
`discoverForFactSheet()` triggers `persistCausalRules()` to write mined rules to disk. The next cascade run re-loads them via `loadProjectPslRules()` and the MAP solve produces inferred facts with weights shaped by the mined structure. Because D3-A reads `InferredFact.value()` for step confidence, the loop is:

```
mine log → PSL rules with dependency weights → persist to rules/
  → cascade re-ground with learned weights → InferredFact values updated
  → discoverForFactSheet reads InferredFact.value() per activity
  → step confidence in ProcessSuggestion reflects learned structure
```

This is the complete closed loop without adding new data structures.

**D3-E — KGE embedding similarity as activity synonymy.**  
KGE embeddings enable recognizing that two differently-labeled activities are semantically related (e.g. "send_notification" ≈ "push_alert"). In `EventLogExtractor.extractForFactSheet()`, after projecting graph nodes to activity labels, add an optional clustering pass that merges node IDs within cosine-similarity distance `< threshold` (loaded from `KGEmbeddingRetriever`) into a canonical activity name. This reduces fragmentation in the directly-follows graph, improving Inductive Miner fitness. Gate behind `kompile.process.mining.embedding-merge.enabled=false` (off by default until embeddings are routinely trained).

**D3-F — Created rules as process decision-logic pre-conditions.**  
When a `ProcessSuggestion` is accepted and converted to a `ProcessDefinition` (existing accept path), add a `ruleBindings` list to `ProcessDefinition` that copies the causal PSL rules from the suggestion's `structuredEvidence` (type=CAUSAL). These rule strings can be compiled at decision-gateway evaluation time by `TableDecisionCompiler` or the existing `FolNodeExecutor.executeTabularRule()` path, treating each activity pair rule as a step-activation guard:

```
0.73: State("approve") & Link("approve","notify") -> State("notify") ^2
```

maps to: "step 'notify' is activated if step 'approve' completed with confidence ≥ 0.73."

---

## 4. Prioritized Build List

Priority is ordered by blocking dependencies (lower items depend on higher items being done first).

**P0 — Foundation (unblocks everything else)**

1. **Close PSL weight reload loop** (D1-B + D2-B): swap `InMemoryWeightStore` for `FileBackedWeightStore` in `KbCorrectionService.getWeightStore()`, add `correctionService.applyLearnedWeights(factSheetId, program)` in `IncrementalReasoningOrchestrator.doReground()` before `loadProjectPslRules`. This makes human corrections accumulate across cascade runs.
   - Files: `KbCorrectionService.java` line 270, `IncrementalReasoningOrchestrator.java` line 233.

2. **Persist mined causal rules to disk** (D2-A): add `persistCausalRules(factSheetId, causalModel)` in `MiningProcessDiscoveryService.discoverForFactSheet()` writing to `<dataDir>/rules/<factSheetId>-mined.psl`. The `loadProjectPslRules` seam already picks these up.
   - Files: `MiningProcessDiscoveryService.java` line 148, new `RulePersistenceService`.

**P1 — Signal integration into process candidates**

3. **InferredFact soft-truth gating** (D3-A): add `latestValue()` to `KbGroundingService`, use it in `ProcessTreeToSuggestion.convertGrounded()` to blend inferred confidence into step scoring.
   - Files: `ProcessTreeToSuggestion.java` line 86, `KbGroundingService`.

4. **Inline Bayesian posteriors** (D3-C): call `ProcessBayesianInference.infer()` inside `discoverForFactSheet()` and populate `bayesianPosteriors`/`bayesianPriors` on the suggestion.
   - Files: `MiningProcessDiscoveryService.java` line 148, `ProcessSuggestion.java` line 81.

5. **Causal evidence in structuredEvidence** (D3-B): add CAUSAL entries from `causalAnalysis()` to the suggestion's `structuredEvidence` list.
   - Files: `MiningProcessDiscoveryService.java` line 148.

**P2 — Model-staging lifecycle**

6. **SameDiff auto-promotion hook** (D1-A): add `stagingService.promoteFromLocalPath()` call in `TrainingService` on COMPLETED status. Requires new `StagingService.promoteFromLocalPath(modelId, type, localPath, metadata)` overload.
   - Files: `TrainingService.java` line 337, `StagingService.java`.

7. **KGE embedding staging registry entry** (D1-C): call `registryService.addModel(ModelEntry.kgeEntry(...))` after `KGEmbeddingJobService.storeEmbeddings()` completes. Requires `ModelType.KGE`.
   - Files: `KGEmbeddingJobService.java` line 179.

**P3 — Rule versioning and propagation**

8. **Rule versioning with active-rules.json** (D2-D): make `loadProjectPslRules` read from an `active-rules.json` index file (list of active filenames) rather than scanning all `*.psl` files. Write a new entry to the index on each `persistCausalRules()` call.

9. **Cross-fact-sheet rule propagation** (D2-E): `RulePropagationService` copies active rule files from source to target fact-sheets sharing the same `ontologySchemaId`.

10. **Process decision-logic from rules** (D3-F): copy causal PSL rules from accepted `ProcessSuggestion` into `ProcessDefinition.ruleBindings` at accept time.

**P4 — KGE embedding merge (optional)**

11. **Activity synonymy via KGE similarity** (D3-E): add embedding-cluster merge pass in `EventLogExtractor`, off by default.

---

## Key File Inventory

| File | Purpose | Key lines |
|---|---|---|
| `kompile-model-staging/.../training/TrainingService.java` | Training lifecycle; no post-completion staging call | 337–355 (COMPLETED status), 314–318 (checkpoint save) |
| `kompile-model-staging/.../staging/StagingService.java` | Stage/promote/activate pipeline | 241–292 (promoteModel), 279 (ModelStatus.ACTIVE), 285 (registryService.addModel) |
| `kompile-model-manager/.../registry/RegistryService.java` | Active version selection | 233 (getActiveModels filtered by ACTIVE), 365 (setActiveModel demotes others) |
| `kompile-graph-reasoning/.../learning/WeightStore.java` | PSL weight versioning interface | `latest(programId)` |
| `kompile-knowledge-graph/.../grounding/KbCorrectionService.java` | Correction + weight update | 137–148 (mini-batch update + ws.save), 270 (InMemoryWeightStore — must swap to File), 228 (registerProgram) |
| `kompile-knowledge-graph/.../reasoning/IncrementalReasoningOrchestrator.java` | Cascade entry point | 233 (buildProgramFromFactStore), 239 (loadProjectPslRules), 320 (registerProgram), 360–391 (rule file seam) |
| `kompile-process-discovery/.../mining/causal/ProcessCausalAnalyzer.java` | Mined PSL rules from DFG | 92–104 (generatePslRules) |
| `kompile-process-discovery/.../mining/MiningProcessDiscoveryService.java` | Process mining orchestrator | 138–168 (discoverForFactSheet), 197–198 (causalAnalysis — separate, not fed into discover) |
| `kompile-process-discovery/.../mining/convert/ProcessTreeToSuggestion.java` | Tree → suggestion with grounding | 86–92 (convertGrounded signature), grounding via KbGroundingService.verify |
| `kompile-process-discovery/.../ProcessSuggestion.java` | Suggestion data model | 81 (bayesianPosteriors — empty), 96 (structuredEvidence), 121 (groundedSteps — populated) |
| `kompile-knowledge-graph/.../embedding/service/KGEmbeddingJobService.java` | KGE training + storage | 179–181 (storeEmbeddings — no staging call) |
