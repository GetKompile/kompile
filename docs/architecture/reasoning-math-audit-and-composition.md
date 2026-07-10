# Reasoning Math Audit + Composition Framework

> Pass 2 of the 2026-07-02 reasoning-stack audit (pass 1 = `reasoning-stack-usage-gaps.md`, wiring).
> This pass extracts the **actual formulas** from code and answers: *does the math compose?* The
> target capability (user-stated): **embeddings find similar concepts → reconcile candidate answers
> against schemas → FOL screens contradictions → build the final evidence trace → synthesize the
> likelihood of a particular answer from everything together.**
>
> Verdict up front: the individual engines are mostly sound (Łukasiewicz HL-MRF, exact VE, correct
> noisy-OR, standard KGE losses, real Perturb-and-MAP marginals, a complete Jøsang Opinion algebra).
> What breaks composition is the **boundaries**: six different uncalibrated score currencies, raw
> values crossing type boundaries (cosine∈[−1,1] fed as PSL truth), evidence double-counting, an
> arithmetic-mean fusion of correlated engines, and the one algebra that could unify it all
> (`Opinion` ⊕/consensus) having **zero production callers**.

---

## 1. Formula inventory (as-built, verified line-by-line)

### 1.1 PSL / HL-MRF (`psl/GroundRule`, `HlMrfMapInference`, `ScalarHlMrfInference`)
- Body (Łukasiewicz AND): `bodyTruth = max(0, Σᵢ tᵢ − (n−1))`, negated literal `t = 1−v` (GroundRule:60-65,53-56).
- Head (Łukasiewicz OR): `headTruth = min(1, Σᵢ tᵢ)`; empty head ≡ `falsehood()` → 0 (GroundRule:67-73).
- Distance to satisfaction: `d_r(y) = max(0, body − head)`; potential `φ_r = w_r·d_r` or `w_r·d_r²` (GroundRule:76-91).
- Energy: `E(y) = Σ_r w_r·d_r(y)^{p_r}` — convex; MAP by projected gradient + backtracking (init 0.5,
  step 0.1, ×0.5 shrink / ×1.5 grow; converge on `‖∇‖² ≤ tol²`) (ScalarHlMrfInference:40-87).
- **Hard constraints are a penalty approximation**: `w = ∞` is substituted with `1e6`
  (HlMrfMapInference:40); violation tolerance 1e-4. (ADMM tier has the exact-hard-constraint
  machinery; the dispatch does not route hard-rule programs to it preferentially.)
- Solver dispatch is real: `<500` ground rules → Scalar; `500–4000` → ADMM; `>4000` & ≤50M dense
  cells & ND4J → Tensor; else SGD (HlMrfMapInference:173-185).
- CWA: `declareClosed` → missing atoms read as observed 0.0 (PslProgram:34-68). Consequence:
  `!Pred(?X)` evaluates to 1.0 for every unknown entity — negation-as-failure without an
  evidence distinction.
- Marginals (`PslMarginalInference`): **Perturb-and-MAP** — Gumbel noise on target priors at
  temperature τ=0.1, 20 samples, 5 burn-in → per-atom empirical mean/variance (lines 24-59,156-161).
  Production-dead; production uses the single MAP point estimate.

### 1.2 Cascade program + consensus (`IncrementalReasoningOrchestrator`, `HybridConsensusTrainer`)
- Observed-vs-target split: value ≥ 0.9 ⇒ observed, else target (orchestrator:99).
- Auto-rule per predicate: `0.8: pred(?X[,?Y]) -> derived_pred(?X[,?Y])` (orchestrator:1795-1800).
- Consensus target per atom: `c = clamp01((1−w)·observed + w·hybrid)` where
  `hybrid = max{minmax-normalized MAP entity score : entity ∈ args}` and `w = kbHybridConsensusWeight`
  (**default 0.5**) (HybridConsensusTrainer:185,203-239). This consensus is the **only** training
  signal (`softTargets` → StructuredPerceptron, maxEpochs=1).
- Weight learning: `∇_r = mean_g[d(g, predicted) − d(g, groundTruth)] + λ(w_r − priorMean_r)`
  (band-aware prior means), projected `w ← max(0, w − η∇)`, η=0.1 (PslRuleGradient:82-88,
  StructuredPerceptronLearner:225-229, ProjectedGradientOptimizer:59).

### 1.3 Bayesian / MEBN (`bayesian/`, `mebn/`)
- Noisy-OR (exact, correct): `P(Y=0|x) = (1−leak)·Π_{i:xᵢ=1}(1−λᵢ)`, leak default 0.05
  (NoisyOrCpt:100-113).
- **Causal strength seeding**: `λᵢ = clamp01(weight · confidence · typeMultiplier)`
  (NoisyOrCpt:180; KgMTheoryBuilder:364-370 similarly `weight·confidence·provenanceMult`, plus a
  single-parent linear variant with NO leak and a hardcoded 0.7 `isRelevant→influences` edge).
- VE is exact sum-product with evidence-slice reduction, reverse-topological elimination
  (VariableElimination:98-228). **"MPE" is per-variable marginal argmax, not joint max-product**
  (:302-326).
- SSBN: Cartesian entity grounding, recursion cap 10, recursive transition strength hardcoded 0.9
  (SSBNGenerator:288-305,525). Relevance filter = ancestral-set pruning only (conservative; not
  full Bayes-Ball d-separation) (BayesBallRelevanceFilter:39,67-99).
- MEBN learning: oracle analytic gradient `∂p_c/∂s = p_par(1−p_c)/(1−s·p_par+ε)` exists
  (MebnWeightLearner:156-191) but the **production SameDiff path optimizes a different model**:
  `predicted = s·p_parent` (linear, no noisy-OR saturation, no leak) with MSE loss
  (SameDiffMebnStrengthLearner:48-51,208-215). The two gradients diverge as `s·p_parent` grows.
- Prior blend (sound shrinkage): `w = max(0, evStrength−2)/(·+blendK)`; `p = w·empirical + (1−w)·structural`
  (EmpiricalPriorBlend:33-48).

### 1.4 Causal attribution (`CausalTraversal`, `InfluencePropagation`)
- Hop strength: `min(1, baseWeight·confidence·typeMult)`, typeMult ∈ {CAUSES 1.0 … CORRELATES_WITH 0.3}
  (CausalTraversal:340-357).
- Chain confidence: `Π hops` — **no length normalization** (:126-127): a 5-hop 0.9-chain (0.59)
  loses to a 2-hop 0.77-chain (0.59≈) purely by length.
- Influence: damped reverse propagation, **unnormalized accumulation** (InfluencePropagation:60-113).

### 1.5 Confidence / Opinion (`confidence/Opinion`, stamper, promotion, calibration)
- `Opinion(b,d,u,a)`, `E = b + a·u`; `fromBetaEvidence(p,n,a,W): b=p/(p+n+W), d=n/(p+n+W), u=W/(p+n+W)`
  (Opinion:37,106-109). **W=0 with p=n=0 ⇒ 0/0 NaN** (reachable via `assertedPriorStrength=0`).
- `fromSoftTruth(v, k): u=1/(k+1)` — 1-arg overload hardcodes k=10 ⇒ u≈0.09 regardless of real
  uncertainty (:63-75). `fromBayesianPosterior: u = max(0, 1−2|p−a|)` — conflates distance-from-prior
  with epistemic confidence (:116-119).
- **Fusion operators implemented and tested — zero production callers**: `cumulativeFuse` (Jøsang
  §12.2), `averageFuse`, `consensus` (certainty-weighted centroid) (:135-207). The new opt-in cascade
  materializer explicitly chose replace-not-fuse; the multi-source design doc records
  "fusion is last-write-wins today."
- Extraction stamp: `pos = rawConfidence || sourceTrust`, `neg = 0` always, `a = 0.5`, W per basis
  (STRUCTURAL 0.1, LLM 2.0, ASSERTED 0) (ExtractionConfidenceStamper:120-126). Trust is evidence
  mass, **not** a Jøsang discount.
- **Promotion double-counts**: `evidencePos += sourceTrust` with **no per-source/run dedup**
  (FactPromotionTracker:262-267). N re-runs on unchanged docs ⇒ `b = N·t/(N·t+W) → 1`:
  3 identical cascades ≈ PROBABLE, 8 ≈ HIGH, with zero new evidence.
- Platt: `σ(w·x+b)` with `w=1,b=0` **never fitted** (no production caller of
  `updateFromLabeledBatch`) ⇒ it *distorts* (σ(0.8)≈0.69), UNKNOWN ceiling hardcoded 0.3
  (PlattCalibrator:38-77,92).
- verify→Opinion: SUPPORTED ⇒ `fromObservedValue(conf)` with **u=0** — epistemic uncertainty
  discarded at the API boundary (KbGroundingService:207-219).
- Open-world absence: sparse ⇒ vacuous(a); dense ⇒ full disbelief (b=0,d=1,u=0) — a hard CWA cliff
  at density 0.10 (SparseGraphAssessor:97-101; SparsityMetrics:66).

### 1.6 TMS / contradictions
- Lib detector: opposite-band test `(v₁≥0.9 ∧ v₂≤0.1)` (ContradictionDetector:213-216); functional
  conflict needs both ≥0.9 and hard. `BeliefReviser.retract` = hard FactStore removal, no
  re-inference, no Opinion downgrade (BeliefReviser:61-68).
- New probabilistic detector: TENSION iff `p_A≥0.55 ∧ p_B≥0.55 ∧ p_A·p_B≥0.30`, or mass overflow
  `Σp > 1.05`, or declared disjoint; `normalizedEntropy = −Σp̂ᵢln p̂ᵢ/ln n`
  (ProbabilisticContradictionDetector:86-90,140,166-177,283-284). Resolution scorecards:
  `severity = clamp01(0.30 + 0.45·avgConf + 0.17·recency + boost)`;
  winner `= 0.55·conf + 0.30·recency + 0.15·trust` (maintenance ContradictionDetector:922-941).
  All thresholds fixed constants; no evidence-count sensitivity (p=0.56 from 1 observation flags the
  same as from 1000).

### 1.7 FOL / query / verify
- `JoinKernel` conjunction confidence = `min(...)` — **Gödel T-norm, while the Javadoc claims
  Łukasiewicz** and the PSL engine actually uses Łukasiewicz for the same connective
  (JoinKernel:75 vs GroundRule:60). Same query, two semantics.
- `RecursiveQueryEngine`: crisp tier-1 Datalog (confidence≡1.0), soft handled only by tier-2 PSL —
  documented and sound.
- `DefaultKbVerifier`: SUPPORTED iff latest(atom).confidence ≥ 0.5; REFUTED iff latest(`~atom`) ≥ 0.5
  (a "~"-prefixed negation-key convention); FactStore hard-fact fallback; else UNKNOWN
  (DefaultKbVerifier:30-36). Threshold uncalibrated (flagged in eval design).
- `EntailmentEngine`/`FolInferenceService`: `InferredFact.confidence = MAP value` — direct copy, no
  calibration (EntailmentEngine:271,294; FolInferenceService:102); pair grounding O(N²) capped 10k.
- OWL-RL: **all compiled rules HARD (∞)** — there is no soft `kbOntologyRuleWeight` path in
  `OwlRlRuleCompiler` (that weight applies to the separate DOMAIN/RANGE compiler); disjointness
  `cax-dw` = falsehood-head hard rule; **no ArithmeticRule (≠ / mutual-exclusion / functional
  cardinality) is ever emitted**, so `bornIn(x,y) ∧ bornIn(x,z) ∧ y≠z → ⊥` is inexpressible today
  (OwlRlRuleCompiler:86,311-317).

### 1.8 Embeddings / KGE / GNN
- `Embeddings.cosine ∈ [−1,1]`, zero-vector → 0, **no clip** (Embeddings:58-62).
- `EmbeddingPslEvidence` feeds **raw cosine as the PSL atom value** — a negative cosine becomes a
  negative "truth value" (undefined in HL-MRF) (EmbeddingPslEvidence:86-101,171).
- KGE→plausibility: `score = 1/(1+d)` — no margin/temperature, midpoint pinned at d=1 regardless of
  training γ (LinkPredictorKgeScorer:95). KGE→Opinion: `fromEmbeddingScore(score, u=0.25)`
  (KgeOpinionStoreBridge:64-71).
- **RotatE representation issues** (CORRECTED from the initial agent claim): plain cosine over the
  `[re|im]` concatenation (MatrixGraphRagService:513,531) is *mathematically identical* to the real
  part of the Hermitian cosine — `Re⟨a,b̄⟩ = Σ(aRe·bRe + aIm·bIm)` = the concat dot, same norms —
  so retrieval similarity is NOT broken; it equals `RotatEModel.complexCosineSimilarity` (:930).
  The real defects: (a) lib `RotatELearner.toEmbeddingTable` exports only **real parts** (imaginary
  discarded — half the representation lost for downstream lib consumers) (RotatELearner:680);
  (b) kg RotatE distance is `Σᵢ√(dReᵢ²+dImᵢ²)` (L1-of-moduli, matches the official RotatE release),
  lib RotatE is overall L2 — each internally consistent, mutually incomparable, and neither is
  calibrated to a probability.
- Retrieval blend: `blended = (1−α)·(ppr/maxPpr) + α·(cos/maxSim)`;
  `final = blended + 0.5·kge + gcw·conf` (KGE weight hardcoded; per-query max-normalization ⇒
  **relative ranking only, never an absolute probability**) (MatrixGraphRagService:432-465).
- PathRAG: `reliability = Π edgeWeight` — length-biased like causal chains (:629-642).
- `spectralClustering` is **a stub — connected components**, no eigendecomposition
  (MatrixGraphAlgorithms:893,910). Louvain ΔQ and Newman modularity are standard; PPR is a proper
  column-stochastic power iteration with L1-normalized restart and dangling-mass handling.
- GNN provider: 6 hand-coded features (bias, in/out degree norms, weighted degrees, type-hash/996),
  one tanh message pass (self 0.5/neighbor 0.5), score = `(cos+1)/2` — a *heuristic*, correctly
  range-mapped but not a trained or calibrated quantity (SparseGraphNeuralScoreProvider:68-105,153-163).
- Node2vec/SGNS and TransE margin loss are textbook-correct; SameDiffKgeModel margin-ranking sign
  verified correct.

### 1.9 Cross-engine fusion (`EvidenceAccumulator`, `FusedReasonerService`, `ExplainOrchestrator`)
- `fusedConfidence = arithmetic mean{cᵢ : cᵢ>0, ¬NaN}` over: grounding verify confidence, hybrid
  blend, PSL MAP value, MEBN posterior, max causal chain, **graph-RAG hardcoded 0.5**
  (EvidenceAccumulator:89-101). No weights, no calibration, no independence correction — and every
  engine reads the same subgraph, so the inputs are strongly correlated.
- `HybridReasoner.rank`: `(0.6·structural + 0.4·max(0,cos))/1.0` — but production always calls it
  **without a query embedding ⇒ semantic term ≡ 0**; the 0.6/0.4 shown in ConfidenceBreakdown is a
  hardcoded display constant (ExplainOrchestrator:254-289; HybridReasoner:90-113).

---

## 2. The twelve math defects that block composition

| # | Defect | Where | Effect |
|---|---|---|---|
| D1 | Evidence double-counting (no source/run dedup) | FactPromotionTracker:262 | confidence = f(recomputation count), not corroboration |
| D2 | Six uncalibrated currencies averaged | EvidenceAccumulator:89 | fusedConfidence has no probabilistic meaning |
| D3 | Correlated engines fused as if independent | FusedReasonerService | systematic overconfidence |
| D4 | Raw cosine [−1,1] as PSL truth | EmbeddingPslEvidence:101 | undefined solver behavior on negatives |
| D5 | RotatE half-representation export (real-only EmbeddingTable) + two incomparable distance conventions, neither calibrated | RotatELearner:680; RotatEModel:688 | lib-side KGE similarity loses phase info; distances not probabilities (concat-cosine claim was corrected — it equals Re-Hermitian, see §1.8) |
| D6 | MAP point value ⇒ "confidence"/"probability" | EntailmentEngine:271; verify | uncalibrated verdicts; u discarded (u=0) at verify |
| D7 | Consensus self-training (w=0.5 toward own MAP, min-max renormalized per cascade) | HybridConsensusTrainer:185 | weight drift; targets change scale every cascade |
| D8 | Fixed thresholds, no evidence-count sensitivity (0.5 verify; 0.9/0.1 TMS; 0.55/0.30 tension; 0.88 ER) | multiple | same verdict for n=1 and n=1000 |
| D9 | Trust as evidence mass, not discount; neg evidence never written | ExtractionConfidenceStamper:120 | disbelief only reachable via prune/retract, not evidence |
| D10 | Hard constraints as 1e6 penalties (ADMM exact mode unused); OWL all-hard with no soft tier; no mutex/functional arithmetic rules emitted | HlMrfMapInference:40; OwlRlRuleCompiler:86 | constraint semantics approximate; key contradiction class inexpressible |
| D11 | Length-biased products (causal chains, PathRAG) | CausalTraversal:126; MatrixGraphRagService:629 | short chains structurally favored |
| D12 | Semantic mismatches: Gödel-vs-Łukasiewicz conjunction (query vs solver); MPE that is marginal-argmax; spectral clustering that is components; production MEBN gradient ≠ noisy-OR model | JoinKernel:75; VariableElimination:302; MatrixGraphAlgorithms:893; SameDiffMebnStrengthLearner:48 | wrong-name math misleads consumers |

---

## 3. The composition algebra (make everything one currency)

**Currency**: the existing `Opinion(b,d,u,a)`. Every signal enters as an Opinion via a *calibrated,
documented* map; every combination uses the operator matching its dependence structure:

| Combination type | Operator | Status |
|---|---|---|
| Same proposition, **independent sources** (distinct docs/channels/asserters) | cumulative fusion ⊕ (Opinion:135) | exists, unwired |
| Same proposition, **correlated engines** (PSL & MEBN over the same FactStore) | `consensus` certainty-weighted centroid (Opinion:190) — never ⊕ | exists, unwired |
| **Serial derivation** (conclusion from premises via a rule) | conjunction ⊙: `E = Π Eᵢ`, `u = 1 − Π(1−uᵢ)` (or full Jøsang multiplication) | missing (~20 lines) |
| **Source reliability** | trust discount ⊗: `b′=t·b, d′=t·d, u′=1−t(b+d)` | missing (~5 lines) |
| **Conflict measure** | `c = b₁d₂ + d₁b₂` (binomial conflict mass) | missing (~3 lines) |
| **Negation** | complement: `¬(b,d,u,a) = (d,b,u,1−a)`; unifies the "~atom" key convention | missing (~3 lines) |

Signal → Opinion maps (each with a named calibration id that travels in the trace):
- **Text cosine** s: `p = σ(w·s + b)` (Platt per embedding space) → `fromEmbeddingScore(p, u_margin)`
  where `u_margin` shrinks with top-1/top-2 margin and k-NN density.
- **KGE distance** d: `p = σ((γ − d)/T)` with γ = training margin, T fitted on the free labels the
  trainer already generates (true vs corrupted triples) — replaces `1/(1+d)`.
- **PSL**: use Perturb-and-MAP (exists) → (μ, σ²) → Beta moment-match: `n_eff = μ(1−μ)/σ² − 1` →
  `fromSoftTruth(μ, n_eff)` — replaces both the raw MAP copy and the hardcoded k=10.
- **MEBN/Bayesian posterior** p: keep p, derive u from `SensitivityAnalyzer` posterior-shift (exists)
  instead of `1−2|p−a|`.
- **Deductive OWL-RL/Datalog**: dogmatic (u≈0) *given premises*; the DerivationTree propagates ⊙ over
  premise opinions bottom-up so a chain is only as strong as its weakest premise.
- **Extraction**: keep Beta counts, but key evidence by (atom, sourceId) — re-observation *replaces*
  (idempotent), a new source ⊕-fuses. Trust becomes ⊗ discount on the source's opinion. Negative
  evidence (`neg`) gets written on refutation/contradiction, making disbelief reachable by evidence.

---

## 4. The target pipeline, formalized

*"Use embeddings to find similar concepts → reconcile answers with schemas → FOL contradiction
screen → final evidence trace → synthesized answer likelihood."* Every stage maps to an existing
component plus the algebra above.

**Stage 1 — candidate generation (embeddings).**
Candidates C(q) from text-ANN seeds + PPR expansion (live today) + KGE link-prediction tails
(LinkPredictor, dead today). Per candidate c:
`ω_text = fromEmbeddingScore(σ(w·cos_text+b), u_margin)`; `ω_kge = fromEmbeddingScore(σ((γ−d)/T), u_kge)`
— KGE similarity computed **Hermitian** (or via distance), never concat-cosine.
Language and structure are quasi-independent ⇒ `ω_ret = ω_text ⊕ ω_kge`.

**Stage 2 — schema reconciliation.**
Expected answer type τ(q) (query classifier or rule); candidate type t(c) through `TypeHierarchy`
isA closure. `ω_type =` near-certain STRUCTURAL if `isA(t(c), τ(q))`; complement-dominated if
`disjoint(t(c), τ(q))` (OWL cax-dw); vacuous if unbound. DOMAIN/RANGE conformance of c's supporting
relations likewise (OntologyConformanceValidator exists — today it only *tags*; here it becomes a
**factor**). Combine serially: the answer must be retrieved AND well-typed ⇒ `⊙`.

**Stage 3 — FOL contradiction screen (before the trace is built).**
Over c's supporting fact set F(c) ∪ {c}:
1. Same-atom conflicts by **conflict mass** `c₁₂ = b₁d₂ + d₁b₂ > τ_c` — replaces the crisp 0.9/0.1
   band and the 0.55/0.30 scorecard with one number that already accounts for uncertainty (a
   low-evidence pair has high u ⇒ low conflict mass ⇒ no false alarm at n=1).
2. Mutex/functional violations via Datalog rules compiled from ontology functionality +
   disjointness — requires emitting the missing `ArithmeticRule` mutual-exclusion constraints (or
   crisp tier-1 rules in `RecursiveQueryEngine`), closing the `bornIn(x,y)∧bornIn(x,z)∧y≠z→⊥` gap.
3. Negation as first-class: `ω(¬A) = ω(A).complement()` unifies the verifier's "~atom" convention.
Outcome: `ω_cons` (consistency opinion). Hard violation (OWL falsehood / functional) ⇒ drop c.
Survivors proceed to trace building — contradictions are settled *before* the trace, as specified.

**Stage 4 — model inference (the likelihood core).**
`ω_psl` from Perturb-and-MAP moments over the (cached or targeted) subprogram; `ω_mebn` from VE
posterior + sensitivity-u, when a theory is registered. These share evidence ⇒
`ω_inf = consensus(ω_psl, ω_mebn)` (never ⊕).

**Stage 5 — synthesis + trace.**
```
ω(c) = [ (ω_text ⊕ ω_kge)  ⊙  ω_type  ⊙  ω_cons  ⊙  consensus(ω_psl, ω_mebn) ]   with ⊗ at leaves
likelihood(c) = E[ω(c)] = b + a·u          rank candidates by E, expose u alongside
```
**The trace *is* the operator tree**: each node = (signal, Opinion, calibration id, source refs,
operator). `DerivationTree`/`ConfidenceBreakdown`/`ModalityEvidence` already have the display
shapes — the change is that the root confidence is **computed from the tree** (operator semantics)
instead of `EvidenceAccumulator`'s flat mean, and every segment shows its own (b,d,u).
For **generated LLM answers**: decompose into claims, verify each → `ω_claim`, answer opinion
`= ⊙ over claims` (weakest link visible in the trace); UNKNOWN claims contribute vacuous opinions
(E = baseRate), not 0 — open-world by construction.

---

## 5. Self-calibration (no human labels needed)

The system already generates the labels required to fit every calibrator it ships:
1. **KGE**: the trainer's corrupted-triple negatives + observed positives ⇒ fit T (and validate γ)
   for `σ((γ−d)/T)` at the end of every KGE job.
2. **PSL**: Perturb-and-MAP variance ⇒ per-atom `n_eff` (replaces k=10) — no labels at all.
3. **Extraction/promotion**: facts later corroborated by *new distinct sources* = positives; facts
   later contradicted/retracted = negatives ⇒ feed `PlattCalibrator.updateFromLabeledBatch`
   per BasisType. The promotion ledger is the label stream; it is currently discarded.
4. **Verifier threshold**: sweep 0.5 against the same ledger for max Macro-F1 (eval doc already
   prescribes this).

---

## 6. Ordered fix list (smallest first, each independently shippable)

1. **Boundary clamps + honest names** (hours): clip/`(s+1)/2` in `EmbeddingPslEvidence`; fix
   JoinKernel Javadoc or switch to Łukasiewicz; rename marginal-argmax "MPE"; rename/implement
   `spectralClustering`; guard `fromBetaEvidence` 0/0.
2. **KGE representation + calibration hygiene** (hours): `RotatELearner.toEmbeddingTable` exports
   the full `[re|im]` vector (concat cosine ≡ Re-Hermitian, so downstream consumers are already
   correct once given the full vector); stamp `kgeEmbeddingModel` metadata alongside `kgeEmbedding`;
   per-model calibrated distance→probability `σ((γ−d)/T)` replaces `1/(1+d)` (D5).
3. **Source-keyed evidence** (1–2 days): (atom, sourceId)-keyed Beta ledger in
   `FactPromotionTracker`; replace-on-same-source, ⊕ on new source; write `neg` on
   contradiction/refutation (kills D1, makes D9 evidence-real).
4. **Opinion algebra completion** (~1 day): add ⊙ conjunction, ⊗ discount, complement, conflict
   mass to `Opinion`; adopt conflict mass in the in-flight probabilistic contradiction work.
5. **Fusion policy** (1–2 days): `EvidenceAccumulator` → operator tree (⊕ sources / consensus
   engines / ⊙ derivations) with per-node calibration ids; root computed, not averaged (D2, D3).
6. **Calibration wiring** (2–3 days): the three self-calibration loops of §5 (D6, D8).
7. **Perturb-and-MAP in the cascade** (1–2 days, gated — ~20× MAP cost; run top-K atoms only):
   per-atom (μ, σ²) → `n_eff` → honest u end to end.
8. **Mutex/functional constraint emission** (2–3 days): ontology functionality + disjointness →
   ArithmeticRule / crisp Datalog rules; route hard-rule programs to the ADMM exact-constraint tier
   (D10) — this is what makes Stage-3 contradiction screening complete.
9. **Consensus guardrails** (day): cap `w`, anchor normalization to a fixed scale (not per-cascade
   min-max), log the observed-anchor fraction (D7).
10. **MEBN gradient fidelity** (1–2 days): port the analytic noisy-OR gradient into the SameDiff
    graph (`1−(1−leak)·Π(1−s·p)` is expressible); stop seeding λ from edge *quality* confidence —
    initialize from typeMultiplier priors and let the learner move them; treat extraction confidence
    as soft-evidence on findings instead (D12, category error).
11. **Length normalization** (hours): geometric-mean-per-hop or exponential decay for causal chains
    and PathRAG (D11).

With 1–5 in place, Stage-5 synthesis is implementable directly; 6–8 make its numbers *mean*
something; 9–11 clean up the learning/attribution corners.
