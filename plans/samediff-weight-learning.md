# SameDiff Weight-Learning Design Plan

**Status:** Design only — no code changes.  
**Date:** 2026-06-23  
**Scope:** PSL HL-MRF weight learning and MEBN noisy-OR strength learning on the current fact-sheet graph (7,863 nodes, 47,537 edges, fact sheet 1).

---

## 1. Current State Analysis

### 1.1 Graph and Atom Dimensions

The live graph for fact sheet 1:

| Dimension | Value | Source |
|-----------|-------|--------|
| Total nodes | 7,863 | given |
| ENTITY nodes | 7,723 | given |
| TABLE nodes | 116 | given |
| DOCUMENT nodes | 24 | given |
| Total edges | 47,537 | given |
| Fact sheets | 1 | given |

`GraphToFactStoreProjector.project()` maps these to PSL atoms as:
- Each node → 1 unary observed atom: `nodeType(externalId)` — e.g. `entity(node_xyz)`
- Each edge → 1 binary observed atom: `edgeType(sourceId, targetId)` — value = edge weight or confidence

So the FactStore receives approximately **7,863 observed atoms + 47,537 observed atoms = ~55,400 total observed atoms** before the MAP solve.

`buildProgramFromFactStore()` then adds soft propagation rules — one unary and one binary rule template per distinct predicate. With ~3 node-type predicates (entity, table, document) plus 1 unique edge-type predicate per distinct relation type — the edge type vocabulary is application-dependent but likely in the range of 10–50 distinct types. Conservatively estimate **15–100 template rules**.

### 1.2 Ground Rule Count

Each template rule `w: pred(?X) -> derived_pred(?X)` grounds once per matching observed atom of that predicate. Each `w: pred(?X, ?Y) -> derived_pred(?X, ?Y)` grounds once per edge of that type.

Estimated ground rules: 7,863 (unary groundings) + 47,537 (binary groundings) ≈ **~55,000–60,000 ground rules** (some predicates may generate double-groundings for both arity-1 and arity-2 templates, but with distinct predicates the overlap is small).

This exceeds the `DEFAULT_TENSOR_THRESHOLD` of 4,000 in `HlMrfMapInference.chooseSolver()`, so the **MAP solve is already routed to `SgdHlMrfInference`** (the sparse SGD solver) since the dense cell budget would be 55,000 × 55,400 ≈ 3 billion cells — far above the 50M `DEFAULT_DENSE_CELL_BUDGET`.

### 1.3 PSL Weight Learning: Where Time Goes

**StructuredPerceptronLearner (full-batch mode, N epochs):**

```
for epoch 0..N:
    HlMrfMapInference.solve(program)     // ONE full MAP inference
    PslRuleGradient.ruleGradient(...)    // O(groundRules × rules) scalar scan
    optimizer.step(weights, gradient)    // O(rules) update, trivially fast
```

Cost is dominated by the MAP solve. `SgdHlMrfInference` runs mini-batch SGD over all ~55K ground rules. Each epoch shuffles and sweeps ground rules in batches of 256 (default), meaning **~215 mini-batch steps per epoch**. The per-step cost is proportional to the batch's total literal count (sparse scatter-add over atom vectors of length ~55K).

With default `maxEpochs=50` in `PslWeightLearningService` and the structured-perceptron requiring 1 full MAP solve per epoch: **50 MAP solves × (200+ mini-batch steps per solve) × per-step literal scatter** is the main bottleneck. Each MAP solve itself runs up to 2,000 SGD iterations (the `SgdHlMrfInference` patience guard exits at 200 no-improve epochs).

The cascade uses only **1 online step** (`updateOnBatch(..., 1)`), so actual online cost is 1 MAP solve of ~55K ground rules. But a full convergence run triggers 50+ MAP solves — that is the 6-hour number.

**Key insight:** each MAP solve is the expensive unit. At ~55K ground rules and ~55K atoms, each MAP-solve iteration is a sparse loop over 55K ground rules with an average of ~2–3 literals each (≈110–165K scalar multiplies per SGD step). At 2,000 SGD steps/MAP × 50 MAP calls = 100,000 inner steps. At ~1 μs per inner step (conservative JVM scalar loops over 55K-rule batches) that is **~100 seconds per weight-learning epoch batch**, compatible with multi-hour runtimes at full convergence.

### 1.4 MEBN Weight Learning: Where Time Goes

**MebnWeightLearner (finite-difference gradient):**

```
for epoch 0..maxEpochs:
    baseLoss = meanLoss(graph, theory, batch)     // 1 SSBN inference
    for each edge e in edges:
        perturb e by +delta
        gradients[i] = (meanLoss(...) - baseLoss) / delta  // 1 SSBN inference
        restore e
    optimizer.step(strengths, gradients)
```

Cost: `1 + |edges|` SSBN inferences per epoch. SSBN inference runs `SSBNGenerator.generate()` + `VariableElimination.queryAll()` over the full MTheory. For a theory over 7,723 entities the Bayesian network is large — variable elimination cost is exponential in treewidth, but for typical MEBN structures with limited conditioning complexity it runs in seconds per query.

At 47,537 MFrag edges (upper bound — most edges are graph structure; actual MEBN edge count depends on how many MFrags reference parent edges), the cost is `O(|MEBN edges| × SSBN inference time)` per epoch. If SSBN takes 0.5s and there are even 1,000 learnable MEBN edges, that is **500 seconds per epoch** — consistent with the 6-hour total across multiple epochs of both learners running in cascade sequence.

### 1.5 Summary: Two Distinct Bottlenecks

| Learner | Bottleneck | Current algorithm | Per-epoch cost at scale |
|---------|-----------|------------------|-------------------------|
| PSL structured-perceptron | MAP solve per epoch | Full SgdHlMrfInference pass over ~55K ground rules | O(epochs × MAP-iters × groundRules × literals) |
| MEBN noisy-OR | Finite-difference: 1 SSBN per edge | Sequential scalar perturbation loop | O(epochs × MEBN-edges × SSBN-inference) |

Both bottlenecks share the same root cause: **gradients are computed by re-running expensive inference once per parameter**, rather than differentiating through inference once to get all gradients simultaneously.

---

## 2. Proposed SameDiff Design

### 2.1 Core Principle

Replace the repeated-inference gradient loop with a **single differentiable forward pass** through the inference objective, then call `SameDiff.calculateGradients()` to obtain all weight/strength gradients at once. This replaces:
- PSL: N MAP solves per epoch → 1 forward pass of the energy function + 1 autodiff backward pass per epoch.
- MEBN: |edges| + 1 SSBN inferences per epoch → 1 vectorized noisy-OR forward pass + 1 autodiff backward pass per epoch.

### 2.2 Component A: SameDiff PSL Weight Gradient

#### Formulation

The PSL HL-MRF energy is a sum of weighted hinge potentials:

```
E(y; w) = Σ_r  w_r · max(0, bodyTruth_r(y) - headTruth_r(y))^{p_r}
```

where `y ∈ [0,1]^A` are atom truth values and `w ∈ R_+^R` are rule weights.

For weight learning via structured perceptron:
```
∂E/∂w_r = max(0, d_r(y_pred))^{p_r} - max(0, d_r(y_gt))^{p_r}
```

where `d_r(y) = max(0, bodyTruth_r(y) - headTruth_r(y))` and `y_pred` comes from MAP inference.

**The key insight:** `d_r(y)` is a function of `y` through the Łukasiewicz body/head aggregations, and `y` (at the MAP solution) is a function of `w` through the energy minimization. In the structured-perceptron approximation, we **hold `y` fixed at the current MAP estimate** and differentiate `E` with respect to `w` analytically. This gives a closed-form gradient requiring no additional MAP solve beyond the one already done.

#### Tensor Layout

Compile the ground rules once into the `SgdHlMrfInference.Compiled` format, extended with SameDiff variables:

```
R = number of ground rules ≈ 55,000
A = number of atoms ≈ 55,400
K_r = number of rule templates (not groundings) ≈ 15–100

Variables (SameDiff trainable scalars):
  w = SDVariable, shape [K_r]          // one weight per template rule (NOT per grounding)

Placeholders (bound once per MAP solve, not trainable):
  y_pred = INDArray, shape [A, 1]     // current MAP atom values
  y_gt   = INDArray, shape [A, 1]     // observed ground truth atom values

Precomputed constants (from compiled ground rules):
  ruleTemplate = int[], shape [R]     // which template rule each grounding belongs to
  distPred     = INDArray, shape [R]  // d_r(y_pred) for each ground rule
  distGt       = INDArray, shape [R]  // d_r(y_gt) for each ground rule
  squared      = boolean[], shape [R] // whether p_r = 2
```

#### SameDiff Graph Construction (Weight Learning)

```java
SameDiff sd = SameDiff.create();

// Trainable weight vector (one scalar per rule TEMPLATE, not per grounding)
SDVariable w = sd.var("w", DataType.FLOAT, K_r);   // shape [K_r]

// Gather per-grounding weights from the template index
SDVariable wPerGrounding = sd.gather(w, sd.constant(ruleTemplateIdx), 0);  // [R]

// Objective contribution of y_pred (the inner MAP solution)
// distPred = d_r(y_pred), precomputed as a constant for this epoch
SDVariable distPredVar = sd.constant("distPred", distPredArray);   // [R]
SDVariable distGtVar   = sd.constant("distGt",   distGtArray);     // [R]
SDVariable squaredMask = sd.constant("sq", squaredArray);           // [R] in {0,1}

// Potential: w_r * (sq*d^2 + (1-sq)*d)
SDVariable potPred = wPerGrounding.mul(
    squaredMask.mul(distPredVar.mul(distPredVar))
    .add(sd.constant(1f).sub(squaredMask).mul(distPredVar))
);  // [R]

SDVariable potGt = wPerGrounding.mul(
    squaredMask.mul(distGtVar.mul(distGtVar))
    .add(sd.constant(1f).sub(squaredMask).mul(distGtVar))
);  // [R]

// Loss = Σ(potPred - potGt) over ground rules (structured-perceptron objective)
SDVariable loss = potPred.sub(potGt).sum();   // scalar
sd.setLossVariables("loss");
```

Call `sd.calculateGradients(feedMap, "w")` to get `∂loss/∂w` — a vector of shape `[K_r]`. Apply with `ProjectedGradientOptimizer` (non-negative projection). No additional MAP solve is needed; `distPred` and `distGt` are computed once using the existing `SgdHlMrfInference.Compiled` sparse distance evaluator and injected as constants.

#### MAP Inference Path (Unchanged for Now)

The MAP solve itself (finding `y_pred`) stays on `SgdHlMrfInference` — it already correctly handles the sparse 55K-rule scale. The SameDiff graph is ONLY for the weight-gradient computation on top of the fixed MAP result. This is the correct decomposition for structured-perceptron learning.

**Later (Phase 3):** the MAP solve can also be expressed in SameDiff (replacing `SgdHlMrfInference`), enabling GPU acceleration of both MAP and weight learning in one graph. But that is not needed for the first speedup.

#### Shape Accounting

```
K_r template rules × 1 weight each:       K_r × 4 bytes ≈ <1 KB
R ground rules, 4 arrays of R floats:      4 × 55K × 4 bytes ≈ 880 KB
y_pred / y_gt:                             2 × 55K × 4 bytes ≈ 440 KB
Gradient output:                           K_r × 4 bytes ≈ <1 KB

Total working set:                         ~1.3 MB (fits in L2 cache on any GPU)
```

The incidence structure (which ground rule belongs to which template) can be encoded as a dense integer array of shape `[R]` and used as a gather index.

### 2.3 Component B: SameDiff MEBN Noisy-OR Strength Learning

#### Formulation

The noisy-OR CPT for a child RV `effectRv(X)` with parent `causeRv(X)` and causal strength `θ ∈ (0,1)` is:

```
P(effectRv(X) = TRUE | causeRv(X) = TRUE)  = θ
P(effectRv(X) = TRUE | causeRv(X) = FALSE) = λ  (leakage, typically small fixed constant)
```

For the batch of entity groundings, we want to learn `θ` (per MFrag edge) so that the predicted posteriors `P(effectRv(X) = TRUE)` match the observed targets.

Under the simple noisy-OR SSBN with a prior `p_cause` on `causeRv(X) = TRUE`:

```
P(effectRv(X) = TRUE) ≈ 1 - (1 - θ)^{p_cause} · (1 - λ)^{(1 - p_cause)}
```

This simplifies considerably when we use the marginal approximation (treating entity groundings as independent):

```
P(effect_X = TRUE) ≈ θ · P(cause_X = TRUE) + λ · (1 - P(cause_X = TRUE))
                    = λ + (θ - λ) · P(cause_X = TRUE)
```

The **mean-squared-error loss** across entities is fully differentiable with respect to `θ`:

```
L(θ) = (1/N) Σ_X  (predicted_X - target_X)^2
     = (1/N) Σ_X  (λ + (θ - λ)·causeProb_X - target_X)^2
```

Gradient:
```
∂L/∂θ = (2/N) Σ_X  (λ + (θ - λ)·causeProb_X - target_X) · causeProb_X
```

This is a **closed-form analytic gradient** requiring NO SSBN inference at all. The cause priors `causeProb_X` are computed once from the current MAP posteriors or from the prior distribution, then held fixed.

#### Tensor Layout

```
E = number of entity groundings per MFrag ≈ 7,723 (all ENTITY nodes)
M = number of learnable MEBN edges

Variables (SameDiff trainable):
  theta = SDVariable, shape [M]       // causal strength per MFrag edge

Constants (set once per epoch from graph state):
  causeProb = INDArray, shape [E, M]  // P(causeRv(X)=TRUE) for entity X, edge M
                                       // broadcast for independent edges
  targets   = INDArray, shape [E, M]  // observed target value per entity per edge
                                       // (replicated across columns from consensus targets)
  lambda    = scalar constant ≈ 0.001  // fixed leakage
```

#### SameDiff Graph Construction (MEBN Strength Learning)

```java
SameDiff sd = SameDiff.create();

// Trainable: causal strength vector, one per MFrag edge
SDVariable theta = sd.var("theta", DataType.FLOAT, M);   // [M]

// causeProb: [E, M] broadcast — P(cause_X = TRUE) for each entity, replicated per edge
// For single-edge theories: shape [E, 1] then broadcast
SDVariable causeProbVar = sd.constant("causeProb", causeProbArray);  // [E, M]
SDVariable targetsVar   = sd.constant("targets",   targetsArray);     // [E, M]

// Predicted posteriors via noisy-OR: lambda + (theta - lambda) * causeProb
// theta is [M], broadcast to [E, M] via reshape to [1, M]
SDVariable thetaBcast = theta.reshape(1, M);                          // [1, M]
SDVariable lambdaConst = sd.constant(0.001f);
SDVariable predicted = lambdaConst.add(thetaBcast.sub(lambdaConst).mul(causeProbVar));  // [E, M]

// MSE loss per edge, then mean over entities and edges
SDVariable residual = predicted.sub(targetsVar);                      // [E, M]
SDVariable loss = residual.mul(residual).mean();                      // scalar

sd.setLossVariables("loss");
```

Call `sd.calculateGradients(feedMap, "theta")` to get `∂loss/∂theta ∈ R^M`. Apply with `ProjectedGradientOptimizer` (unit-interval projection). This replaces the entire finite-difference loop over `M` edges with a single forward+backward pass.

#### Shape Accounting for MEBN

```
M MEBN edges (estimate): if the theory has ~1,000 learnable edges across MFrags:
  theta:      M × 4 bytes      ≈ 4 KB
  causeProb:  E × M × 4 bytes  ≈ 7723 × 1000 × 4 ≈ 30 MB  (dense, per-entity per-edge)
  targets:    same              ≈ 30 MB

Total working set: ~60 MB for 1,000 edges × 7,723 entities
```

If `M` is small (say, 10–100 edges for typical MTheory configurations), this reduces to sub-MB. The noisy-OR marginal approximation avoids full SSBN construction entirely — the expensive `SSBNGenerator` + `VariableElimination` is replaced by the simple linear noisy-OR formula above.

---

## 3. The "Too Sparse" Question

### 3.1 Why It Was Rejected Before

The original reasoning was that "the graph is too sparse for matrix operations to pay off." `HlMrfMapInference.chooseSolver()` comments explain this explicitly:

```java
// the default KG subgraphs (maxNodes ≈ 100) sit well under this,
// so they stay on the scalar path.
public static final int DEFAULT_TENSOR_THRESHOLD = 4000;
```

At the earlier scale (100 nodes, ~300 edges), the dense incidence matrix `R × A` would be `300 × 300 = 90K cells` — trivially addressable by scalar loops, and GPU kernel launch overhead (~5–10 μs) would exceed the actual computation time (microseconds of scalar multiply).

### 3.2 What Changed

The graph is now **55× larger in atoms and ~200× larger in ground rules** relative to the original scalar-optimized threshold. The critical crossover points:

| Condition | Old graph (100 nodes) | Current graph (55K atoms) |
|-----------|----------------------|---------------------------|
| Ground rules (R) | ~300 | ~55,000 |
| Atoms (A) | ~100 | ~55,400 |
| Dense matrix R×A | 30K cells (120 KB) | 3 billion cells (12 GB) — IMPOSSIBLE |
| SgdHlMrfInference viable | yes | yes (already active) |
| Autodiff weight gradient | overhead > benefit | batch of K_r ≤ 100 weights, trivially fast |
| MEBN finite-diff | <10 edges | potentially 100–10,000 edges × O(E) SSBN |

The graph is now large enough that:
1. Dense tensor MAP is already infeasible and already bypassed (SgdHlMrfInference handles it correctly).
2. The **weight gradient** (not the MAP solve itself) can be expressed as a SameDiff computation over `K_r ≤ 100` template-weight scalars, regardless of sparsity. The "sparsity" concern applies to the ATOM-space MAP solve, not to the WEIGHT-space gradient.
3. MEBN analytic gradient is `O(E × M)` — no SSBN inference at all.

### 3.3 Sparsity Strategies in SameDiff at This Scale

For the weight-learning SameDiff graph, there are three strategies:

**Option A: Dense gather on template-indexed arrays (RECOMMENDED for PSL)**

Encode `distPred` and `distGt` as dense `[R]` float arrays computed by the existing sparse `SgdHlMrfInference.Compiled.distance()` scanner (which already runs efficiently). Use SameDiff only for the weight-gradient backward pass over `[K_r]` weights. The gather operation `w[ruleTemplateIdx[r]]` maps ground rules to template weights and is SameDiff-native.

- Memory: ~1.3 MB total.
- GPU benefit: marginal for K_r ≤ 100 weights. CPU benefit: automatic gradient vs. manual loop — negligible overhead difference but correctness guarantee.
- Best fit: immediate implementation with minimal risk.

**Option B: Sparse segment-sum over ground rules (for large K_r or SameDiff MAP)**

If the rule template count grows (e.g., user-provided custom PSL rules that are numerous), use `sd.segmentSum(distances, templateIdx)` to aggregate per-template potentials before multiplying by weights. This is SameDiff's idiom for sparse accumulation and runs on GPU via CUDA scatter-add kernels.

- Memory: O(R) for the segment index.
- GPU benefit: significant when K_r > 500 or when the MAP solve is also in SameDiff.
- Trade-off: requires stable integer segment indices and sorted rule ordering.

**Option C: Dense batched per-entity for MEBN (RECOMMENDED for MEBN)**

The noisy-OR gradient is naturally expressed as a dense `[E, M]` matrix multiply (Jacobian of predicted posteriors w.r.t. `theta` is `causeProb`, a dense matrix). No sparsity handling needed. At E=7,723 and M=1,000, this is a 30 MB dense matrix — trivially GPU-batched.

**Decision for This Scale:**

Use Option A for PSL (the weight space is small: K_r ≤ 100, benefit is correctness and code simplicity) and Option C for MEBN (E×M dense is the correct shape and avoids all SSBN inference). Both avoid the pathological O(R×A) dense MAP incidence matrix that made the original TensorHlMrfInference impractical.

---

## 4. Reuse: Extending the Existing SameDiff Infrastructure

### 4.1 What Already Exists

The codebase already has significant SameDiff infrastructure:

| Component | Location | Relevance |
|-----------|----------|-----------|
| `TensorHlMrfInference` | `kompile-graph-reasoning/psl/` | Dense ND4J MAP inference — same math, different scope |
| `SgdHlMrfInference.Compiled` | `kompile-graph-reasoning/psl/` | Sparse ground-rule compiled form with `distance()` — the distance extractor we call before SameDiff |
| `SameDiffEmbeddingTrainer` | `kompile-graph-reasoning/embedding/learn/` | Full SameDiff training loop with `calculateGradients` — DIRECT TEMPLATE for the weight learner |
| `RotatELearner` | `kompile-graph-reasoning/embedding/learn/` | SameDiff + Adam updater pattern — shows `sd.var()` / `sd.gather()` / `sd.calculateGradients()` idiom |
| `SameDiffModelIO` | `kompile-graph-reasoning/embedding/learn/` | Flatbuffer serialization of SameDiff graphs |
| `SameDiffCheckpointService` | `kompile-model-staging/` | isAvailable guard + save/load with availability fallback |
| `ProjectedGradientOptimizer` | `kompile-graph-reasoning/learning/` | Shared parameter update step — reused as-is |
| `nd4j-api` (compile) | `kompile-graph-reasoning/pom.xml` | SameDiff is already on the compile classpath |

The SameDiff autodiff API (`org.nd4j.autodiff.samediff.SameDiff`, `SDVariable`) is already a **compile-time dependency** of `kompile-graph-reasoning` (via `nd4j-api`). No new dependency is needed.

### 4.2 What to Reuse vs. Build New

**Reuse directly:**
- `SgdHlMrfInference.Compiled` — call `compiled.distance(ri, yPredValues)` for all R ground rules to build the `distPred` array. The inner loop already runs in ~milliseconds for 55K rules.
- `ProjectedGradientOptimizer.nonNegative()` and `.unitInterval()` — unchanged weight/strength update steps.
- `SameDiffEmbeddingTrainer.fitBatch()` pattern — build the SameDiff graph once, bind constants per epoch, call `calculateGradients`, extract INDArray gradient, apply.
- `TensorHlMrfInference.isAvailable()` — reuse as the backend guard.

**Extend with new classes (do NOT touch concurrent-editing files):**

| New class | Location | Purpose |
|-----------|----------|---------|
| `SameDiffPslWeightGradient` | `kompile-graph-reasoning/learning/` | Builds and caches the SameDiff graph for PSL weight gradient; called by `StructuredPerceptronLearner` (or a new subclass) |
| `SameDiffMebnStrengthLearner` | `kompile-graph-reasoning/learning/` | SameDiff noisy-OR strength learning replacing finite-difference in `MebnWeightLearner` |

These classes are new and do not touch the files currently being concurrently edited (`MebnWeightLearner`, `StructuredPerceptronLearner`, `PslRuleGradient`, `IncrementalReasoningOrchestrator`).

### 4.3 Integration Points

`SameDiffPslWeightGradient` is called from `PslRuleGradient.ruleGradient()` (or a new override in `StructuredPerceptronLearner`) as a drop-in replacement of the scalar gradient loop. The entry point:

```java
// In StructuredPerceptronLearner (or a new SameDiffStructuredPerceptronLearner):
double[] gradient;
if (SameDiffPslWeightGradient.isAvailable() && groundRules.size() > SAMEDIFF_THRESHOLD) {
    gradient = SameDiffPslWeightGradient.compute(rules, groundRules, predicted, groundTruth);
} else {
    gradient = PslRuleGradient.ruleGradient(rules, groundRules, predicted, groundTruth);
}
```

`SameDiffMebnStrengthLearner` replaces the finite-difference loop in `MebnWeightLearner.learn()`:

```java
// In MebnWeightLearner (or a new SameDiffMebnWeightLearner):
if (SameDiffMebnStrengthLearner.isAvailable() && edges.size() > SAMEDIFF_MEBN_THRESHOLD) {
    return SameDiffMebnStrengthLearner.learn(theory, graph, observations, maxEpochs, learningRate);
} else {
    // existing finite-difference path
}
```

---

## 5. Estimated Speedup

### 5.1 PSL Weight Gradient

**Current scalar loop (`PslRuleGradient.ruleGradient`):**
- For R=55,000 ground rules and K_r=50 template rules:
  - Inner loop: R iterations × (1 `distanceToSatisfaction(predicted)` + 1 `distanceToSatisfaction(groundTruth)`) = 110K Java virtual calls to `GroundRule.distanceToSatisfaction()`, each a Łukasiewicz conjunction/disjunction scan over 2–3 literals.
  - Total scalar ops: ~330K per gradient call.
  - Estimated time at JVM map-lookup overhead per call: ~50–200 ms per gradient computation (HashMap lookups in `distanceToSatisfaction` dominate at 55K atom keys).

**SameDiff path (once graph is built):**
- `Compiled.distance()` for all 55K rules in tight scalar array loops: ~5–10 ms to build `distPred[]` and `distGt[]` (no HashMap, just int-indexed array access).
- SameDiff forward + backward over K_r=50 weight scalars using precomputed constants: ~1–5 ms (GPU kernel overhead would dominate; CPU nd4j-native runs the tiny weight-space graph efficiently).
- **Total: ~10–15 ms vs. 50–200 ms current.**
- **Speedup: 5–20×** per gradient call.

Since each MAP solve (the remaining bottleneck) costs ~seconds for 55K ground rules, the gradient step is already a small fraction. The primary win is **correctness** (no HashMap-based distance recomputation) and **readability** — not a wall-time breakthrough for the online 1-step cascade.

**For full-convergence offline training** (50 epochs, each epoch = 1 MAP solve + 1 gradient step):
- Current: 50 × (MAP-solve: ~60s) + 50 × (gradient: ~0.1s) = ~50 min.
- SameDiff gradient: saves ~5 seconds. Not significant against the MAP-solve cost.
- Real speedup requires also converting the MAP solve — see Phase 3.

### 5.2 MEBN Strength Learning: Large Speedup

**Current finite-difference (`MebnWeightLearner`):**
- Per epoch: (M + 1) SSBN inferences.
- If M = 1,000 MEBN edges and SSBN takes 1 second: **~1,001 seconds per epoch**.
- maxEpochs=1 for online: 1,001s. That is the dominant cost item in the 6-hour run.

**SameDiff analytic gradient:**
- Build `causeProb` array from MAP posteriors: ~1 ms (read from `result.values()` map).
- SameDiff forward + backward over [E=7723, M=1000] tensors: ~10–50 ms on CPU (dense matrix multiply); ~1–5 ms on GPU.
- **Total: ~50 ms vs. ~1,001 s current.**
- **Speedup: ~20,000× per learning epoch.**

This is the transformative improvement. MEBN learning would drop from hours to under a second per cascade.

**Important caveat:** the analytic gradient uses the **linear noisy-OR marginal approximation** (Section 2.3, Option C). For theories where SSBN builds highly interdependent grounded BN structures (multiple parent paths, context constraints that activate/deactivate MFrags per entity), the approximation may underfit or converge to a different solution than the full SSBN-based gradient. See Risk 5.3b.

### 5.3 GPU Scaling

With the CUDA backend active:

| Component | CPU baseline | GPU estimate | When GPU wins |
|-----------|-------------|-------------|---------------|
| PSL weight gradient (SameDiff) | 10–15 ms | 2–5 ms | M > 100 template rules |
| MEBN strength gradient (SameDiff) | 10–50 ms | 1–3 ms | M > 100 edges, E > 1,000 entities |
| MAP solve (SgdHlMrfInference, unchanged) | 60 s | N/A (sparse irregular access, GPU helps less) | Only with SameDiff MAP (Phase 3) |

GPU is immediately useful for the MEBN strength learning at scale. For PSL weights it offers modest benefit given K_r is small.

---

## 6. Phased Roadmap

### Phase 1 (Smallest Viable — Online Learning Fix)

**Target:** Eliminate the MEBN finite-difference per-epoch bottleneck. Implement `SameDiffMebnStrengthLearner` with the analytic noisy-OR gradient. Gate behind `TensorHlMrfInference.isAvailable()` with a scalar fallback.

**Scope:**
- New class: `SameDiffMebnStrengthLearner` in `kompile-graph-reasoning/learning/`.
- Modified class: `MebnWeightLearner` — add an `if (SameDiffMebnStrengthLearner.isAvailable())` branch (do not change the finite-difference path; it remains the fallback).
- No changes to `IncrementalReasoningOrchestrator`, `StructuredPerceptronLearner`, or `PslRuleGradient`.

**Tests:** A unit test in `kompile-graph-reasoning` that builds a 3-entity 2-edge causal theory, runs `SameDiffMebnStrengthLearner.learn()` for 5 epochs, and asserts the learned strengths move in the correct direction (toward the given targets). Skip if ND4J unavailable.

**Expected outcome:** MEBN per-cascade cost drops from O(MEBN-edges × SSBN-inference) to O(E × M) matrix multiply (~50 ms). This alone would eliminate the multi-hour component from the cascade.

### Phase 2 (PSL Weight Gradient via SameDiff)

**Target:** Replace the HashMap-based scalar `PslRuleGradient.ruleGradient()` call with `SameDiffPslWeightGradient.compute()` for programs with more than 1,000 ground rules. The MAP solve itself stays unchanged.

**Scope:**
- New class: `SameDiffPslWeightGradient` in `kompile-graph-reasoning/learning/`.
- Modified class: `StructuredPerceptronLearner` — call the SameDiff path when available and `groundRules.size() > 1000`.
- No changes to `HlMrfMapInference`, solver hierarchy, or orchestrator.

**Expected outcome:** PSL gradient step goes from ~100 ms (HashMap virtual dispatch) to ~10 ms (SameDiff over K_r floats). Wall-time impact is small because the MAP solve dominates, but this removes a performance cliff as the rule count grows.

### Phase 3 (SameDiff MAP Solve — Full GPU Pipeline)

**Target:** Express the `SgdHlMrfInference` SGD inner loop as a SameDiff graph, enabling GPU-accelerated MAP inference for programs exceeding the current CPU ceiling.

**Scope:** New class `SameDiffSgdHlMrfInference` implementing `HlMrfSolver`. Uses `sd.nn().relu()` / `sd.math().clipByValue()` / sparse gather/scatter (SegmentSum) for the batch gradient step over [R] ground rules.

**Why this is Phase 3:** The sparse scatter-add access pattern over 55K ground rules has irregular memory access that limits GPU efficiency. The current `SgdHlMrfInference` scalar Java loop achieves ~2–3 ns/op JVM throughput for array access — GPU would help significantly only when R > 500K or when GPU memory bandwidth makes up for kernel launch overhead. This is the right optimization for when the graph grows 10× from current scale.

**Expected outcome:** MAP solve time drops from ~60 s to ~2–5 s on GPU (for 55K ground rules). Full convergence offline training drops from 6 hours to ~15 minutes.

### Phase 4 (End-to-End SameDiff Weight Learning Loop)

Unify Phases 1–3: build a single `SameDiffHlMrfWeightLearner` that expresses the full structured-perceptron loop (MAP solve + weight gradient) as a persistent SameDiff computation graph. The atom values `y` become trainable variables jointly with weights `w` under a bilevel optimization (MAP inner + weight outer). This is the full "energy-based model" formulation and enables end-to-end autodiff.

**Risk:** bilevel optimization is numerically fragile. Gate behind an experimental config flag.

---

## 7. Risks and Mitigations

### 7.1 Numerical Stability

**Risk:** The analytic noisy-OR gradient in `SameDiffMebnStrengthLearner` assumes the linear marginal approximation. For strongly correlated parent RVs (e.g., multiple parents of one child all conditioning on the same entity through dense graph paths), the approximation overfits the marginal and the learned `theta` may diverge from the true posterior-consistent strength.

**Mitigation:** Initialize `theta` from the existing strengths (warm-start). Add gradient clipping (`|∂L/∂theta| < 1.0`) in the `ProjectedGradientOptimizer` step. Monitor per-cascade loss — if it increases, fall back to finite-difference for that cascade.

**Risk:** The PSL weight gradient holds `y_pred` fixed (structured-perceptron approximation). For very high-weight rules, the MAP solution changes dramatically with each weight update, and the fixed-`y` approximation is no longer accurate.

**Mitigation:** Keep the learning rate small (the existing `KbConfig.getPslLearningRate()` default of 0.1 or lower). The mini-batch online 1-step cascade already limits weight changes per cascade.

### 7.2 ND4J / Native Image Backend

**Risk:** The CUDA backend requires JavaCPP native libraries (`nd4j-cuda-12.x`). These are not currently wired into the production `kompile-cli` native image. Adding SameDiff graph execution to `kompile-graph-reasoning` could cause native-image hints to fail for graph serialization.

**Mitigation:** Guard ALL SameDiff paths behind `TensorHlMrfInference.isAvailable()` (which already exists and is tested). If ND4J returns false (no native backend), fall through to the scalar path. The native image builds without ND4J native if the reflection hints are absent — the existing `SameDiffCheckpointService.isAvailable()` pattern handles this.

The class `SameDiffPslWeightGradient` and `SameDiffMebnStrengthLearner` should be structured like `TensorHlMrfInference`: static `isAvailable()` + lazy-init of the SameDiff instance only on first use, so class-loading does not trigger ND4J in environments where it is absent.

### 7.3 Sparsity at This Scale (Realistic Assessment)

**Risk:** The `[E, M]` MEBN tensor for large theories (M=10,000 edges, E=7,723 entities) is 300 MB — potentially exceeding GPU VRAM on consumer hardware.

**Mitigation:** Batch the entity dimension. Process `B` entities at a time (e.g. B=512, similar to the embedding training batch size in `SameDiffEmbeddingTrainer`). The SameDiff graph is built for batch size B; `causeProb` and `targets` are re-bound each batch. Final gradient is summed across batches before the optimizer step. This exactly mirrors the pattern in `SameDiffEmbeddingTrainer.fitBatch()`.

### 7.4 Coexistence with the Concurrent Cheaper-Path Fixes

The current concurrent agent editing `MebnWeightLearner` / `StructuredPerceptronLearner` / `PslRuleGradient` / `IncrementalReasoningOrchestrator` is making cheaper-path fixes (mini-batch SGD, batch-size throttling, online 1-step accumulation). These changes land as the **fallback path** that this plan gates behind the SameDiff availability check.

Concretely:
- The concurrent fixes make the **scalar path faster** (smaller batches, fewer SSBN calls per epoch).
- This plan makes the **gradient computation faster** (SameDiff autodiff replaces loops).
- They are additive: Phase 1 of this plan ADDS a new code path gated behind `isAvailable()`. The scalar code being edited concurrently is the `else` branch. No conflict at merge time, assuming both agents do not touch `SameDiffMebnStrengthLearner` or `SameDiffPslWeightGradient` (which do not yet exist).

**Protocol:** merge the concurrent cheaper-path PR first (or in parallel), then add the SameDiff classes as new files in a separate PR. The integration points (`if (SameDiff.isAvailable()) { ... } else { existing scalar path }`) are single-line additions at well-defined call sites.

### 7.5 MEBN Approximation Quality

The analytic noisy-OR gradient skips full SSBN inference. For the current MTheory in production (built via `MebnInferenceService.buildCausalTheory()` or `buildPropagationTheory()`), the linear marginal approximation is exact when parent RVs have independent priors (the case for simple unary theories). For multi-parent theories where entity-specific context constraints activate different MFrags per entity, the approximation introduces error proportional to the correlation between parent posteriors.

**Decision gate:** Include a diagnostic in `SameDiffMebnStrengthLearner` that computes the loss using the approximation AND using one full SSBN inference (on a small random sample of 50 entities) per epoch and logs the discrepancy. If the discrepancy exceeds 10%, log a warning and optionally fall back. This provides an empirical signal for when the approximation is valid.

---

## 8. File and Module Map

All new code lives in `kompile-graph-reasoning` (module `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning`). No new Maven dependency is required — `nd4j-api` is already a compile dependency.

```
kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/
  learning/
    SameDiffPslWeightGradient.java     [NEW — Phase 2]
    SameDiffMebnStrengthLearner.java   [NEW — Phase 1]
  psl/
    SameDiffSgdHlMrfInference.java     [NEW — Phase 3]

kompile-graph-reasoning/src/test/java/ai/kompile/graph/reasoning/
  learning/
    SameDiffMebnStrengthLearnerTest.java   [NEW — Phase 1]
    SameDiffPslWeightGradientTest.java     [NEW — Phase 2]
```

Integration call sites (single-line additions to existing files, do NOT touch until concurrent edits settle):
- `MebnWeightLearner.learn()` — add `SameDiffMebnStrengthLearner` branch (Phase 1)
- `StructuredPerceptronLearner.learn()` — add `SameDiffPslWeightGradient` branch (Phase 2)
- `HlMrfMapInference.chooseSolver()` — add `SameDiffSgdHlMrfInference` tier (Phase 3)

---

## 9. Decision Summary

| Question | Answer |
|----------|--------|
| Is SameDiff already on the classpath? | Yes — `nd4j-api` is a compile dependency of `kompile-graph-reasoning`. `SameDiffEmbeddingTrainer` and `RotatELearner` already use it. |
| Does PSL weight learning benefit from SameDiff? | Modestly for the gradient step (5–20× faster gradient, but MAP solve dominates). Large benefit in Phase 3 (GPU MAP solve). |
| Does MEBN learning benefit from SameDiff? | Dramatically — replaces O(edges × SSBN) per epoch with O(E × M) tensor multiply (~20,000× speedup estimate). |
| Does sparsity prevent SameDiff use? | No. The sparsity concern applies to the ATOM-space MAP solve (already handled by SgdHlMrfInference). The weight/strength space is dense and small (K_r ≤ 100, M ≤ 10,000). |
| What changed since "too sparse"? | Graph grew from ~100 to ~55,000 atoms — now using SgdHlMrfInference anyway. MEBN edges × SSBN cost is now the wall-time dominator. Analytic gradient sidesteps both. |
| Can this coexist with the concurrent scalar fixes? | Yes — new code is new files; integration is gated `if (isAvailable())` branches added only after concurrent edits merge. |
| Biggest risk? | MEBN approximation error for multi-parent theories. Mitigated by per-epoch discrepancy diagnostic and fallback. |
| Recommended start? | Phase 1 (SameDiffMebnStrengthLearner) first — largest speedup, smallest blast radius. |
