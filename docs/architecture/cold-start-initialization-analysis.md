# Cold-Start Initialization Analysis

**Date:** 2026-06-22
**Scope:** Read-only audit of a brand-new graph (ingest messy data → extract relations → new graph, no prior learned state). The question is whether the system starts in a state that meaningful learning can proceed from — analogous to SGD needing a good weight initialization.

---

## 1. Fact Confidence at Cold Start

**Verdict: SPARSE / UNINFORMATIVE (not degenerate, but dominated by a 1.0 fallback)**

**Key files:**
- `GraphPersistenceHelper.java:255` — edge confidence assignment:
  ```
  confidence != null ? confidence : weight != null ? weight : 1.0
  ```
- `GraphToFactStoreProjector.java:138–150` — PSL projection threshold:
  ```java
  double value = 1.0;
  if (edge.getConfidence() != null && ...) value = edge.getConfidence();
  else if (edge.getWeight() != null && ...) value = edge.getWeight();
  // value >= 0.99 → Fact.observed(hard=true)
  // value <  0.99 → Fact.soft(value)
  ```

**What happens on a fresh ingest:**

- If the LLM extractor returns a confidence score for a relation, that score propagates correctly all the way to the FactStore.
- If the extractor returns `null` confidence (Tika structural extraction, rule-based relations, or an LLM that omits scores), the edge is stored at **confidence 1.0** — hard-observed, maximally certain.
- Because the threshold is `>= 0.99`, virtually all Tika-structural edges are projected as `Fact.observed` (hard=true, value=1.0), which PINS them as evidence during MAP inference. This is formally correct (they are fixed observations), but it means the PSL solver sees a mix of: true ground-truth facts (the hard=true extraction results) and noise/hallucinations (also hard=true because confidence was null).

**The practical problem:** A fresh graph dominated by Tika structural edges (which have no extractor confidence) starts with an FactStore where almost all atoms are `observed` at value=1.0. This collapses the PSL MAP solve: everything is already observed, so the MAP posteriors on the `derived_` targets are simply whatever the propagation rules push through from the pinned observed atoms. The gradient signal in step 5b then compares two near-identical MAP runs (see Section 5), producing near-zero weight updates.

**Recommendation:** For edges without an extractor confidence score, use a tunable default (e.g., 0.7 for LLM-extracted, 0.5 for Tika structural, 0.3 for rule-inferred) rather than 1.0. This preserves the `Fact.soft` path so they contribute gradient signal.

---

## 2. PSL Rule Weights at Cold Start

**Verdict: SPARSE (uniform 0.8 prior — not degenerate, but uninformative)**

**Key file:** `IncrementalReasoningOrchestrator.java:795–808`

```java
// buildProgramFromFactStore():
program.addRule("0.8: " + pred + "(?X) -> derived_" + pred + "(?X)");
program.addRule("0.8: " + pred + "(?X, ?Y) -> derived_" + pred + "(?X, ?Y)");
```

Every predicate seen in the FactStore gets the same generic propagation rule at weight **0.8**. There is no structure here: all predicates are treated as interchangeable, and the weight says "we are 80% sure a fact propagates to a derived fact of the same type." This is a defensible uniform prior — it is not degenerate — but it carries zero domain knowledge.

**Warm-start path (STEP 3c, line ~410):**
```java
// Reload persisted learned weights into the program (so the solve uses LEARNED
// weights, not 0.8 defaults).
```
If `cascadeWeightStore.latest(programKey)` returns weights, they are applied before the MAP solve. On a brand-new graph this store is empty, so all rules start at 0.8 silently.

**Recommendation:** 0.8 is a reasonable starting point for unknown-domain propagation. The real issue (Section 5) is that the weight-learning update does not improve on it in a principled way.

---

## 3. Rules at Cold Start

**Verdict: DEGENERATE — a fresh graph is nearly ruleless**

**What a fresh graph actually has:**

1. **Auto-generated trivial propagation rules at 0.8** (from `buildProgramFromFactStore`, line 795): `pred(?X) -> derived_pred(?X)` for every predicate projected from the graph. These say nothing about relationships *between* predicates.
2. **User-placed `.psl` files** (`loadProjectPslRules`, line 716): `<dataDir>/rules/*.psl`. These only exist if a user manually wrote and placed PSL rule files. No seed files are created at project init time. At true cold start the `rules/` directory does not exist and this method is a no-op.

**What a fresh graph does NOT have:**

- **OWL-RL rules** (`OwlRlReasoner.java`): OwlRlReasoner is a standalone service operating on OWL TBox+ABox. It is not wired into `IncrementalReasoningOrchestrator`. Its inferred types and inconsistencies are returned via its own REST API but are never injected into the PSL program as rules or facts. The OWL-RL reasoning is therefore siloed and does not contribute structure to the PSL cold start.
- **Ontology-derived rules**: `POST /api/process/ontology/derive` exists and is wired, but it only runs when a user explicitly calls it. The ontology binding (`bindOntology`) is also manual. Nothing fires automatically on project creation.
- **Process-mining rules**: Heuristics Miner, Inductive Miner, Declare/MINERful constraints — all require an event log and are triggered manually. A fresh graph has none of these.

**Consequence:** At cold start the PSL program has only trivial propagation rules with no inter-predicate constraints. A MAP solve over trivial propagation rules with a single type of rule weight produces a near-flat posterior: `derived_pred ≈ 0.8 * pred`. There is essentially no reasoning happening; the "inference" is a weighted copy of the observed facts. This makes the weight-learning gradient signal (Section 5) nearly zero, because the MAP output is a deterministic function of the input.

**Recommendation — the seed-rules path:** Wire `OwlRlReasoner` into the orchestrator startup sequence so that when an ontology binding exists, its inferred relations (subclass/domain/range) are injected as PSL rules before the first MAP solve. For example: `subClassOf(?X, ?Z) :- subClassOf(?X, ?Y), subClassOf(?Y, ?Z)` (OWL transitivity) translated into a PSL rule with weight 0.9. Alternatively, expose a `POST /api/process/ontology/seed-psl-rules` endpoint and call it automatically after project init and ontology binding.

---

## 4. Embeddings at Cold Start

**Verdict: SOUND (standard init) — but INERT on a sparse fresh graph**

**Key file:** `RotatEModel.java:267–276`

```java
private void initializeEmbeddings() {
    entityRealEmbeddings = Nd4j.rand(numEntities, embeddingDim)
                               .muli(2 * embeddingRange).subi(embeddingRange);
    entityImagEmbeddings = Nd4j.rand(numEntities, embeddingDim)
                               .muli(2 * embeddingRange).subi(embeddingRange);
    relationPhaseAngles  = Nd4j.rand(numRelations, embeddingDim)
                               .muli(2 * Math.PI).subi(Math.PI);
}
```

Where `embeddingRange = config.margin() / embeddingDim` (line 143). This is the standard RotatE initialization from Sun et al. 2019. It is a correct, principled random initialization.

**Sparse-graph guard (line 125–126):**
```java
if (triples == null || triples.isEmpty()) {
    return TrainingResult.failure("No triples provided for training");
}
```

On a fresh graph with no triples, `train()` returns failure, `isTrained()` stays `false`, and all `scoreTriple()` / `predictTails()` / `getEntityEmbedding()` calls return `null` or `Double.MAX_VALUE` (lines 464, 475, 517, 551). The KGE subsystem is safely inert on an empty graph — there is no crash or nonsensical output, just a dead model.

**Recommendation:** The sparse-graph initialization is sound. No fix needed here. The issue is upstream (Section 1, 3): until the graph has meaningful structure and relation diversity, training RotatE will learn degenerate embeddings even when `train()` succeeds. The 1.0-confidence fallback causes most edges to look like near-identical observations, collapsing relation diversity.

---

## 5. PSL Weight Learning: The Critical Question

**Direct answer: NO — PSL weight learning does NOT train toward observed ground-truth facts. It is SELF-TRAINING (training toward the model's own MAP output).**

**This is the #1 problem.**

### The Self-Training Loop (STEP 5b)

**File:** `IncrementalReasoningOrchestrator.java:517–531`

```java
// STEP 5b (L1 NEW): PSL weight learning — train on materialized soft targets
// Use the MAP posteriors as soft training targets (atomKey → posterior value).
// ...
Map<String, Double> softTargets = new HashMap<>(result.values());  // line 529
trainedProgram = pslWeightLearner.updateOnBatch(program, softTargets, 1);  // line 531
```

`result.values()` is the output of `HlMrfMapInference.solve(program)` — the MAP posteriors that the model just computed. These are passed as `miniBatchLabels` to `updateOnBatch`, which calls `StructuredPerceptronLearner.learn(program, groundTruth=softTargets, steps=1)`.

### Inside the Learner

**File:** `StructuredPerceptronLearner.java:76–128`

```java
public List<PslRule> learn(PslProgram program, Map<String, Double> groundTruth, int maxEpochs) {
    // ...
    for (epoch = 0; epoch < maxEpochs; epoch++) {
        // Run MAP inference with current weights
        result = HlMrfMapInference.solve(currentProgram);  // line 104
        Map<String, Double> predicted = result.values();   // line 110
        // ...
        double[] gradient = PslRuleGradient.ruleGradient(rules, batch, predicted, groundTruth);
        // ...
    }
}
```

The learner runs MAP inference again (fresh solve on the current weights) to get `predicted`, then calls `PslRuleGradient.ruleGradient(rules, batch, predicted, groundTruth)` where `groundTruth = softTargets = outer MAP posteriors`.

### The Gradient Formula

**File:** `PslRuleGradient.java:46–66`

```java
// gradient[r] = mean over ground rules of:
//    distanceToSatisfaction(predicted) - distanceToSatisfaction(groundTruth)
gradient[ruleIdx] += gr.distanceToSatisfaction(predicted) - gr.distanceToSatisfaction(groundTruth);
```

The structured-perceptron gradient is `dist(predicted) - dist(groundTruth)`. The standard use of this formula is:
- `groundTruth` = externally observed labels (e.g., annotated facts, extraction results)
- `predicted` = current model's MAP output

Here both `groundTruth` (the outer MAP) and `predicted` (the inner MAP) are model outputs. The formula becomes `dist(innerMAP) - dist(outerMAP)`. Since the outer MAP was solved at the same weights as the inner MAP (only one gradient step separates them, starting from the same weight vector), these two values are extremely close. The gradient collapses toward zero — especially with only 1 epoch (`steps=1`).

### What Is and Is Not Used as Ground Truth

The `Fact.hard` distinction (set for edges with confidence >= 0.99 in `GraphToFactStoreProjector.java:147`) does constrain the MAP solve: observed atoms are pinned and do not move during inference. This is correct for PSL MAP. However, this constraint is enforced **inside the solver** — it does not reach the weight-learning gradient as a target signal. `PslWeightLearningService.updateOnBatch` receives a `Map<String, Double>` with no `Fact.hard` metadata. The learner cannot distinguish "this atom is a ground-truth observation we are training toward" from "this atom is a model prediction we are training toward."

**Consequence:** The weight learner sees `groundTruth ≈ predicted` on every call (both are MAP results under nearly-identical weights). The gradient `dist(predicted) - dist(groundTruth)` ≈ 0. Weights converge to whatever they were initialized at (0.8 on a fresh graph) and do not move. Learning is effectively disabled.

### Degenerate Progression

If, despite the near-zero gradient, weights do drift slightly, they drift toward making the 0.8 propagation rules more self-consistent — not toward the extracted facts being better explained. This is analogous to a collapsed variational autoencoder: the model learns to reproduce its own latent state rather than compress the data.

### Fix

Replace:
```java
// WRONG: self-training on MAP posteriors
Map<String, Double> softTargets = new HashMap<>(result.values());
trainedProgram = pslWeightLearner.updateOnBatch(program, softTargets, 1);
```

With:
```java
// CORRECT: train toward the OBSERVED facts projected from the graph
Map<String, Double> observedTargets = new HashMap<>();
for (Fact f : factStore.allFacts()) {
    if (f.isObserved()) {  // Fact.hard=true, i.e. extracted relations with confidence >= 0.99
        observedTargets.put(f.atomKey(), f.value());
    }
}
// Also include soft facts as weakly-observed targets (confidence-weighted)
for (Fact f : factStore.allFacts()) {
    if (!f.isObserved()) {
        observedTargets.putIfAbsent(f.atomKey(), f.value());
    }
}
// Now train: gradient = dist(innerMAP) - dist(observedTargets)
// This pushes rule weights to make the MAP output match the extracted facts,
// not to make two MAP runs agree with each other.
trainedProgram = pslWeightLearner.updateOnBatch(program, observedTargets, 1);
```

The `factStore` reference is available in `doReground` (it is used for `buildProgramFromFactStore(factStore)` at line 400). The observed facts extracted from the graph are the correct training signal: they are the entities and relations that the LLM or Tika identified, and we want the PSL rules' derived conclusions to be consistent with those observations.

---

## 6. MEBN Priors at Cold Start

**Verdict: SOUND defaults — but same self-training problem as PSL**

**Files:**
- `MFrag.java:169`: edge strength default = **0.5** (`getOrDefault(key, 0.5)`) — maximally uncertain, correct prior
- `NoisyOrCpt.java:48`: leak probability = **0.05** — conservative but reasonable background noise
- `MFrag.java:70`: root node prior = uniform over states when `defaultDistribution == null` — correct fallback

The MEBN priors are sensible for a cold start. 0.5 edge strength = no causal knowledge assumed. 0.05 leak = small background activation. Uniform root prior = no bias.

**However, the MEBN weight learner has the same self-training problem as PSL (STEP 9, line ~638):**

```java
// Uses MAP posteriors as training observations (same soft-target signal as PSL training)
Map<String, Double> observations = new HashMap<>(result.values());  // line 651
```

MEBN weight learning at STEP 9 also trains on `result.values()` (the cascade MAP posteriors), not on the observed facts. The same fix applies: replace MAP posteriors with the `factStore.allFacts()` observed targets.

---

## Per-Component Verdict

| Component | Verdict | Key Finding |
|-----------|---------|-------------|
| **Fact confidence (cold start)** | SPARSE | Tika-structural edges default to 1.0 (`GraphPersistenceHelper.java:255`), causing most atoms to become hard-observed. Eliminates gradient signal. |
| **PSL rule weights (cold start)** | SPARSE | All propagation rules initialized at 0.8 (`IncrementalReasoningOrchestrator.java:799,804`). Uniform prior, not degenerate, but no domain knowledge. |
| **Rules at cold start** | DEGENERATE | Only trivial `pred->derived_pred` propagation rules exist. No OWL-RL seeds, no ontology-derived rules, no mining rules. `rules/` dir empty. Zero inter-predicate reasoning. |
| **RotatE embeddings** | SOUND (but INERT) | Standard RotatE init (`RotatEModel.java:272–276`); safely returns null on empty graph (`line:125–126`). Becomes meaningful only when graph has diverse relations. |
| **PSL weight learning** | DEGENERATE | **SELF-TRAINING**: `softTargets = result.values()` (MAP posteriors, not observed facts) at `IncrementalReasoningOrchestrator.java:529`. Gradient ≈ 0. Weights do not improve. |
| **MEBN priors** | SOUND | 0.5 edge strength, 0.05 leak, uniform root prior — all correct for cold start. Same self-training bug in weight update (line ~651). |

---

## The #1 Problem

**PSL weight learning trains toward the model's own MAP output, not toward the extracted ground-truth facts.**

`IncrementalReasoningOrchestrator.java:529`: `softTargets = result.values()` passes the cascade MAP posteriors as the weight-learning target. The StructuredPerceptronLearner gradient formula `dist(innerMAP) - dist(outerMAP)` collapses to near-zero because both MAP runs use the same weights (only 1 step separates them). The `Fact.hard=true` distinction exists in the FactStore but never reaches the learner. The 0.8 rule weights on a fresh graph are therefore stable: learning runs on every cascade but changes nothing.

The second-largest problem is that a fresh graph has only trivial propagation rules (Section 3). Even if the weight learner were fixed, it would only learn the weights of `pred -> derived_pred` rules, which carry no inter-predicate structure. The reasoning output would still be a weighted copy of the input.

---

## Recommended Fixes (Priority Order)

### Fix 1 — Anchor weight learning to observed facts (P0)

In `IncrementalReasoningOrchestrator.doReground`, STEP 5b (line 529):

```java
// Replace:
Map<String, Double> softTargets = new HashMap<>(result.values());

// With:
Map<String, Double> softTargets = buildObservedTargets(factStore);
```

Where `buildObservedTargets` extracts `Fact.isObserved()` atoms (hard=true from `GraphToFactStoreProjector`) as targets with their observed values, and soft atoms as secondary targets with their soft-truth values. This makes the gradient `dist(innerMAP) - dist(observedFacts)`, which is the standard structured perceptron signal and will actually update rule weights.

The same fix applies to MEBN weight learning (STEP 9, line ~651).

### Fix 2 — Seed rules from the ontology binding at project init (P1)

When a project has an ontology binding (`GraphOntologyBindingService.resolveExplicitGraphBinding`), automatically derive a minimal set of PSL rules from the ontology schema at `loadProjectPslRules` time (or in a `seedRulesFromOntology` pre-pass). For example: if the ontology defines `Person --[employs]--> Organization`, inject:
```
0.9: employs(?X, ?Y) -> isEmployerOf(?Y, ?X)  // symmetric
0.9: employs(?X, ?Y) ^ employs(?Y, ?Z) -> transitivelyRelated(?X, ?Z)
```

This gives the MAP solver structure to reason over before any mining has run.

### Fix 3 — Wire OwlRlReasoner into the orchestrator (P1)

`OwlRlReasoner.java` already computes subClassOf/domain/range transitivity closures from an OWL TBox. Inject its output into the PSL program as observed facts (the type hierarchy) and rules (the OWL-RL entailment rules translated to PSL soft rules at weight 0.9) at `loadProjectPslRules` time.

### Fix 4 — Use confidence-stratified defaults for extracted edges (P2)

In `GraphPersistenceHelper.java:255`, replace the raw `1.0` fallback with a per-source-type default:
```java
confidence != null ? confidence
    : weight != null ? weight
    : isLlmExtracted ? 0.7      // LLM extraction, no score
    : isTikaStructural ? 0.5    // Tika structural
    : 0.4;                       // rule-based / unknown
```

This keeps most edges in `Fact.soft` territory (below 0.99) so they contribute gradient signal rather than being pinned as hard observations.

### Fix 5 — Warm-start RotatE with pseudo-negative sampling on a sparse graph (P3)

On a fresh graph with < 100 triples, RotatE training is unreliable. Provide a minimum-triple guard that delays embedding training until at least N triples exist, and log a clear warning (rather than silently returning null to callers).

---

## Is the Cold Start "A State We Can Learn From"?

**No — but for a specific, fixable reason.**

The MEBN priors and RotatE init are sound. The PSL 0.8 uniform prior is uninformative but not degenerate. The graph structure from a first ingest provides real signal.

The system fails to learn from this signal because:
1. The weight learner's target is the model's own MAP output (self-training) — Fix 1.
2. The rule set has no inter-predicate structure to refine weights over — Fix 2 + 3.
3. The 1.0 confidence fallback pins most atoms as hard observations, collapsing the MAP posteriors and further suppressing gradient signal — Fix 4.

After applying Fix 1 alone, the weight learner would produce non-zero gradients on the first cascade and begin adjusting rule weights toward the extracted facts. The system would be learnable. Fixes 2–4 would make early-stage learning progressively more meaningful as graph structure and relation diversity grow.
