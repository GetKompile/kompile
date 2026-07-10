# Reasoning Math Composition — Implementation Plan

> **Handoff spec.** Self-contained: everything needed to implement is in this doc. Evidence base
> (formulas verified line-by-line, 2026-07-02): `reasoning-math-audit-and-composition.md` (math)
> and `reasoning-stack-usage-gaps.md` (wiring). Read those only if you need justification; do not
> re-audit.
>
> **Mission**: make the reasoning stack mathematically composable on ONE currency (subjective-logic
> `Opinion`) so this pipeline works end to end: **embeddings propose similar concepts → candidate
> answers reconciled against the schema/type system → FOL screens contradictions → an evidence
> trace is built → the likelihood of each answer is synthesized from all signals together** —
> exposed as a REST endpoint + MCP tool with a full per-answer operator trace.

---

## 0. Ground rules for the implementing agent

- **Maven**: `mvn` is NOT on PATH. Use `/home/agibsonccc/dev-apps/mvn/bin/mvn`.
- **Work in the main tree.** No git branches, no worktrees, no commits unless the user asks.
- **Module paths** (repo root `/home/agibsonccc/Documents/GitHub/kompile`):
  | Alias | Path | Package root |
  |---|---|---|
  | lib | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning` | `ai.kompile.graph.reasoning` |
  | kg | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph` | `ai.kompile.knowledgegraph` |
  | crawl | `kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph` | `ai.kompile.crawl.graph` |
  | attr | `kompile-app/kompile-data/kompile-process/kompile-event-attribution` | `ai.kompile.event.attribution` |
  | app | `kompile-app/kompile-app-parent/kompile-app-main` | `ai.kompile.app` |
  | cli | `kompile-cli/kompile-cli-main` | `ai.kompile.cli.main` |
- **Build order is a dependency chain** — serialize installs: lib → kg → (crawl, attr, app).
  Never run two Maven builds concurrently (target/ corruption). Test one module:
  `/home/agibsonccc/dev-apps/mvn/bin/mvn -f <module>/pom.xml test`; install when a downstream
  module needs the new jar. Full lib suite must stay green (baseline ~830+ tests); record
  before/after counts per module you touch.
- **The lib stays infra-free**: deps are nd4j-api, jackson-annotations, slf4j, lombok ONLY. No
  Spring/JPA/HTTP in lib. Spring lives in kg/app clients.
- **Config mandate**: every new tunable is a `kb*` key in `KbConfig`
  (kg `.../confidence/KbConfig.java`) — NO Spring `@Value`, no hardcoded production literals.
  Adding a field requires ALL of: KbConfig field + `toMap()` + `from()` **and** frontend
  `kb-config.service.ts` interface + `createDefaultConfig()` **and** a section in the
  kb-confidence-settings panel (the panel is explicit-binding, not generic).
- **Style**: imports only — never inline FQCNs (including javadoc `{@link}` and param types).
  Match surrounding code. Records extended with new fields keep back-compat via compact
  constructors (existing repo pattern, e.g. `PortableEdge`).
- **Store parity**: `KnowledgeGraphService` has THREE impls — JPA `KnowledgeGraphServiceImpl`,
  matrix `MatrixKnowledgeGraphService` (@Primary path), and the
  `EventPublishingKnowledgeGraphService` decorator. Any interface change updates all three.
- **Testing gotcha**: Mockito strict stubbing trips on path-conditional stubs — use
  `@MockitoSettings(strictness = Strictness.LENIENT)` where needed.
- **Behavior changes are gated**: anything that alters live numbers ships behind a KbConfig flag
  defaulting to current behavior, except pure bug fixes (WP1) which just fix.

---

## 1. Target architecture

**One currency.** Every signal becomes an `Opinion(b, d, u, a)` (`lib confidence/Opinion.java`,
simplex `b+d+u=1`, `E = b + a·u`) through a *named, calibrated* map. Every combination uses the
operator matching its dependence structure:

| Combination type | Operator | Where it comes from |
|---|---|---|
| Same proposition, independent sources (distinct docs/asserters) | `cumulativeFuse` ⊕ | exists (Opinion:135) |
| Same proposition, correlated engines (PSL & MEBN read the same FactStore) | `consensus` | exists (Opinion:190) — never ⊕ |
| Serial derivation / conjunction of requirements | `conjoin` ⊙ | **WP4** |
| Source reliability | `discount` ⊗ | **WP4** |
| Negation | `complement` | **WP4** |
| Conflict measure | `conflict` = b₁d₂ + d₁b₂ | **WP4** |

**The pipeline** (WP12, consuming everything below):

```
query q, factSheetId, [expectedType]
  │ 1. candidates: text-ANN seeds + PPR expand (existing) [+ KGE LinkPredictor when trained]
  │      ω_text = fromEmbeddingScore(calibrated cos, u_margin)
  │      ω_kge  = fromEmbeddingScore(σ((γ−d)/T), u_kge)          →  ω_ret = ω_text ⊕ ω_kge
  │ 2. schema: candidate type t(c) vs expectedType via TypeHierarchy isA / OWL disjointness
  │      ω_type ∈ {near-certain, complement-dominated, vacuous}
  │ 3. FOL contradiction screen (BEFORE trace): same-atom conflict mass; mutex/functional
  │      violations (WP8); hard violation ⇒ drop; else ω_cons
  │ 4. inference: ω_psl (verify + P&M-derived u), ω_mebn (posterior + sensitivity-u)
  │      ω_inf = consensus(ω_psl, ω_mebn)
  │ 5. ω(c) = ω_ret ⊙ ω_type ⊙ ω_cons ⊙ ω_inf   (⊗ discounts at leaves)
  ▼      likelihood = E[ω(c)];  TRACE = the operator tree itself (root computed, never averaged)
ranked answers + per-answer OpinionTree trace
```

---

## 2. Work packages

Execution note: WPs marked **[P]** are file-disjoint and safe to run as parallel edit-only
subagents (absolute paths, no builds inside subagents); the coordinator runs one consolidated
build per phase. All others run sequentially.

---

### WP1 — Boundary correctness fixes (pure bug fixes, no flags) [P]

**1a. `EmbeddingPslEvidence` clamps to [0,1].**
File: lib `embedding/EmbeddingPslEvidence.java` (~:86-101, :138-171). Both
`addSimilarityEvidence` and `addKnnSimilarityEvidence` currently pass raw cosine ∈ [−1,1] to
`program.observe(...)`. Change the observed value to `Math.max(0.0, cosine)` (anti-correlation =
no evidence, matching `HybridReasoner`'s existing `max(0,cos)` convention). Javadoc the choice.
**Also** add a defensive clamp + one-time `log.warn` in `PslProgram.observe` for values outside
[0,1] (solver-level guard).

**1b. `Opinion.fromBetaEvidence` NaN guard.**
File: lib `confidence/Opinion.java` (:106-109). When `pos+neg+W == 0` return `vacuous(a)` instead
of 0/0. Test: `fromBetaEvidence(0, 0, 0.5, 0.0)` → vacuous, simplex holds.

**1c. `JoinKernel` T-norm: keep min, fix the lie.**
File: lib `fol/grounding/JoinKernel.java` (:75) + `ConjunctiveQueryEngine` Javadoc (:39). The code
implements Gödel (min); the Javadoc claims Łukasiewicz. **Keep min** (Łukasiewicz collapses long
conjunctions: 5 conjuncts at 0.8 → max(0, 4.0−4) = 0) and correct both Javadocs, explicitly noting
the deliberate divergence from the solver's Łukasiewicz body semantics.

**1d. Honest names.**
- lib `bayesian/VariableElimination.mostProbableExplanation` (:302-326) is per-variable marginal
  argmax, not joint max-product MPE. Correct the Javadoc ("marginal MAP approximation of MPE");
  do NOT rename (REST `/mpe` depends on it).
- kg `matrix/algorithms/MatrixGraphAlgorithms.spectralClustering` (:893) is a connected-components
  fallback. Correct Javadoc + one-time warn. Follow-on (out of scope): point
  `CommunitySummaryService` at the lib `LouvainDetector` instead.

**1e. `fromSoftTruth` 1-arg documents its k=10 assumption** (Opinion:75) — Javadoc only; WP7
replaces call sites that matter.

Modules: lib, kg. Tests: new `OpinionTest` cases; `EmbeddingPslEvidenceTest` negative-cosine case
(atom absent or 0.0, never negative).

---

### WP2 — KGE representation + distance calibration [P after WP1]

Facts (corrected during audit): concat cosine over `[re|im]` **equals** Re-Hermitian cosine — the
retrieval math is fine. Real defects: half-representation export, uncalibrated `1/(1+d)`, no
model-type awareness.

**2a. Full complex export.** lib `embedding/learn/RotatELearner.toEmbeddingTable` (:680) exports
only `entityRe`. Export `[re|im]` concatenation (dim doubles). Update `RotatELearnerTest`
similarity expectations accordingly.

**2b. Stamp model type.** kg `embedding/adapter/MatrixKgEmbeddingGraphAdapter` (KGE_EMBEDDING_KEY
`"kgeEmbedding"`, ~:61,283): when writing vectors also write `kgeEmbeddingModel` =
`transe|rotate|samediff-rotate|samediff-transe` (from `KGEmbeddingConfigService`/job config).
Readers (`MatrixGraphRagService.kgeVectorOf`) tolerate absence (legacy).

**2c. Calibrated distance→probability.** lib `embedding/kge/LinkPredictorKgeScorer.calibrate`
(:95) replaces `1/(1+d)` with `σ((γ − d)/T)`; γ and T injected via a small
`KgeCalibration(gamma, temperature)` value object (defaults: γ = training margin from config,
T = 1). WP6a fits T. Keep `1/(1+d)` as the documented fallback when no calibration present.

Modules: lib, kg. Acceptance: round-trip test — train tiny RotatE, export table, verify a true
triple's calibrated score > corrupted triple's, and scores ∈ (0,1).

---

### WP3 — Source-keyed evidence ledger (kills double-counting)

File: kg `reasoning/FactPromotionTracker.java` (:262-267 today: `state.evidencePos += sourceTrust`
unconditionally ⇒ N re-runs on unchanged docs ⇒ belief → 1).

**Change the accumulation model**: `PromotionState` gains `Map<String, Double> posBySource` and
`negBySource` (sourceId → trust mass). New core method:
```java
recordEvidence(String atomKey, String sourceId, double trust, boolean positive)
```
semantics: `posBySource.put(sourceId, trust)` — **replace, not add** (idempotent per source);
`evidencePos = Σ posBySource.values()` (recomputed). Distinct sources accumulate; the same source
re-observed is a no-op. The existing 6-arg `checkPromotion(...)` delegates with a sourceId.

**SourceId resolution** (in `IncrementalReasoningOrchestrator` STEP 5c call site, ~:855-861):
prefer the fact's provenance source-document id; the projector (`GraphToFactStoreProjector`)
should carry the edge's source/document id into `Fact` metadata so entailment can pass it through
(add an optional `sourceId` on the projected fact — additive). Fallback when absent:
`"derived:" + factSheetId` — a single idempotent bucket, so cascade re-runs can never climb.

**Negative evidence**: contradiction losers (orchestrator STEP 7 retraction path, ~:1261) and
REFUTED-resolution in the maintenance detector call `recordEvidence(..., positive=false)` — makes
disbelief evidence-reachable (today `neg` is always 0).

**Persistence + back-compat**: persist the maps wherever `PromotionState` persists today (inspect
`LuceneGroundingFactory` / file store); legacy records load as one `{"legacy": evidencePos}` entry.

Config: `kbPromotionSourceKeyedEnabled` (default **true** — this is a correctness fix, but the
flag allows rollback; document in the panel).

Tests: (1) same source × 5 recordEvidence → band unchanged; (2) 3 distinct sources → evidencePos =
Σ trusts, band climbs; (3) legacy state migration; (4) negative evidence lowers expectation.
Modules: kg (+ lib untouched).

---

### WP4 — Complete the Opinion algebra (lib) [P]

File: lib `confidence/Opinion.java`. Add (all pure, all preserve the simplex; Javadoc each with
the Jøsang reference):

```java
/** Binomial multiplication (Jøsang 2016 §7.1) — conjunction of independent propositions. */
public Opinion conjoin(Opinion o) {
    double ax = a, ay = o.a;
    double denom = 1.0 - ax * ay;
    if (denom < 1e-12) {                       // degenerate a≈1 both sides
        double b2 = b * o.b, d2 = d + o.d - d * o.d;
        return new Opinion(b2, d2, Math.max(0, 1 - b2 - d2), 1.0);
    }
    double b2 = b*o.b + ((1-ax)*ay*b*o.u + ax*(1-ay)*u*o.b) / denom;
    double d2 = d + o.d - d*o.d;
    double u2 = u*o.u + ((1-ay)*b*o.u + (1-ax)*u*o.b) / denom;
    return new Opinion(b2, d2, u2, ax*ay);     // normalize residual ε to keep simplex
}
public static Opinion conjoinAll(Opinion... os) { /* left fold */ }

/** Trust discounting (Jøsang §14.3, scalar dogmatic trust t∈[0,1]). */
public Opinion discount(double t) { return new Opinion(t*b, t*d, 1 - t*(b+d), a); }

/** Negation. */
public Opinion complement() { return new Opinion(d, b, u, 1 - a); }

/** Binomial degree of conflict with another opinion on the SAME proposition. */
public double conflict(Opinion o) { return b*o.d + d*o.b; }
```

Key property tests (`OpinionAlgebraTest`): simplex after every op (ε=1e-9); **E(x⊙y) = E(x)·E(y)**
exactly (this identity holds for the Jøsang product — assert to 1e-9); `conjoin` commutative;
`vacuous.conjoin(x)` sane; `discount(1)` = identity, `discount(0)` = vacuous-with-same-a;
`complement().complement()` = identity; `conflict` symmetric, 0 for vacuous pairs, →1 for
dogmatic-opposed. Also hand `conflict()` to the in-flight probabilistic-contradiction workstream
(coordinate — kg `maintenance/ContradictionDetector` is actively being modified; do NOT refactor
that file, just expose the operator).

Modules: lib only.

---

### WP5 — Operator-tree fusion (replaces the arithmetic mean)

**5a. lib `explain/OpinionTree`** (new): immutable node
`{label, Opinion opinion, String calibrationId, List<String> sourceRefs, Op op, List<OpinionTree> children}`
with `enum Op {LEAF, FUSE_INDEPENDENT, CONSENSUS, CONJOIN, DISCOUNT, COMPLEMENT}` and
`static OpinionTree evaluate(...)`/`Opinion value()` computing the root bottom-up
(⊕ / consensus / ⊙ / ⊗ per node). Jackson-serializable (annotations only — lib stays infra-free).

**5b. Rework `EvidenceAccumulator`** (lib `explain/EvidenceAccumulator.java`, mean at :89-101):
keep the public API; internally build an OpinionTree. Per-modality leaf maps:
| Modality | Leaf opinion |
|---|---|
| GROUNDING | from VerifyResult: SUPPORTED → `fromSoftTruth(conf, n_eff or default)`, REFUTED → complement, UNKNOWN → `vacuous(a)` — stop forcing u=0 |
| PSL | `fromSoftTruth(mapValue, n_eff)` (n_eff from WP7 when present, else `kbSoftTruthDefaultEvidenceCount`) |
| MEBN | `fromBayesianPosterior(p, a)` (WP-follow-on: sensitivity-u) |
| HYBRID | `fromSoftTruth(score, default)` |
| CAUSAL | `fromSoftTruth(maxChain, hops)` |
| GRAPH_RAG | **`vacuous(0.5)`** — delete the hardcoded 0.5 confidence |
Combine engine leaves under one **CONSENSUS** node (they share evidence — never ⊕).
`fusedConfidence = root.value().expectation()`.

**5c. Thread through**: `ModalityEvidence` gains optional `(b,d,u)` fields (compact-ctor
back-compat); app `explain/FusedReasonerService` + `ExplainOrchestrator` attach the serialized
tree to `ReasoningTrail`/`ConfidenceBreakdown` (additive field `opinionTreeJson`). Also fix
`KbGroundingService.verifyOpinion` (:207-219) to stop emitting u=0 (use the same GROUNDING map).
Frontend rendering of the tree = optional follow-on (note it in the PR description; do not build).

Config: `kbFusionOperatorTreeEnabled` (default **false** initially; the old mean stays until
flipped), `kbSoftTruthDefaultEvidenceCount` (default 10, replaces the hardcode).

Tests: equal opinions under CONSENSUS → same opinion (no false boost from agreement of correlated
engines); RAG contributes nothing when evidence-free; mean-vs-tree divergence documented in a
table-driven test. Modules: lib, kg, app (serialize builds).

---

### WP6 — Self-calibration loops (no human labels)

**6a. KGE calibration.** After kg `embedding/service/KGEmbeddingJobService.trainSynchronously`
completes: sample K=500 training positives + K corrupted (the negative sampler already exists),
score distances, fit Platt on x = −d (σ(w·x + b) ≡ σ((γ−d)/T) with T = 1/w, γ = b/w — reuse lib
`fol/grounding/PlattCalibrator.updateFromLabeledBatch`, which is currently caller-less). Persist
`{model, gamma, temperature, fittedAt, n}` to `<dataDir>/graph/reasoning/<factSheet>/kge-calibration.json`.
`LinkPredictorKgeScorer` (WP2c) and `MatrixGraphRagService.kgeStructuralSimilarity` read it.

**6b. Promotion-ledger → Platt per BasisType.** New maintenance task `CALIBRATION_REFRESH`
registered in kg `maintenance/GraphMaintenanceServiceImpl` (follow the existing task pattern;
do not touch the in-flight ContradictionDetector): scan promotion states — atoms that later
gained ≥2 distinct sources (WP3 ledger) = positive labels for their *initially stamped*
confidence; retracted/contradiction-loser atoms = negatives. Feed
`PlattCalibrator.updateFromLabeledBatch` per BasisType; persist to
`<dataDir>/graph/reasoning/<factSheet>/platt-calibration.json`; `ExtractionConfidenceStamper` and
the verify controllers read fitted params through the existing `StrengthCalibrator` seam.

Config: `kbCalibrationEnabled` (default **false**), `kbCalibrationMinLabels` (default 50 — below
it, identity calibration), `kbKgeCalibrationSamples` (default 500).

Tests: synthetic separable labels → fitted (w,b) separate them (AUC improves vs identity); below
min-labels → identity; persistence round-trip. Modules: kg (+ lib PlattCalibrator untouched).

---

### WP7 — Perturb-and-MAP uncertainty in the cascade (gated)

File: kg `reasoning/IncrementalReasoningOrchestrator` — insert STEP 4b after the MAP solve
(~:747): when `kbMarginalsEnabled` && `program.groundRuleCount() <= kbMarginalsMaxGroundRules`,
run lib `psl/PslMarginalInference` (samples = `kbMarginalsSamples`, default 20; existing Gumbel
τ=0.1 default) → per-atom `(μ, σ²)`.

**Moment-match to evidence count**: `n_eff = clamp(μ(1−μ)/σ² − 1, 1, 1000)` (σ²→0 ⇒ cap at 1000).
Carry it: `InferredFact` gains an optional nullable `Double variance` (additive record field,
compact ctor + JSON serializer back-compat — there is an existing hand-rolled serializer, update
both directions + a legacy-read test). STEP 5's entailment passes variance through; the WP5
GROUNDING/PSL leaf maps and `KbOpinionBrowserController` tier-3 use
`fromSoftTruth(μ, n_eff)` instead of the k=10 default; `KbGroundingController` verify DTO exposes
`uncertainty` (additive field).

Config: `kbMarginalsEnabled` (default **false** — cost ≈ samples × MAP), `kbMarginalsSamples`
(20), `kbMarginalsMaxGroundRules` (20000).

Tests: deterministic-seed P&M on a toy program → high-σ² atom gets low n_eff → wider-u Opinion;
InferredFact JSON round-trip with and without variance. Modules: lib (field), kg.

---

### WP8 — Mutex/functional constraints + exact hard tier

**8a. Verify the arithmetic path end-to-end first** (it exists but is production-dark): lib
`psl/ArithmeticRule`, `ArithmeticGroundRule` (summation vars, RelOp =/≤/≥ → functional and
mutual-exclusion constraints) and `AdmmHlMrfInference` (exact hard constraints,
`hardViolations()`). Write the sentinel unit test: program with
`Σ_y bornIn(x, y) ≤ 1`, two observed bornIn facts for one x → `hardViolations()` non-empty.

**8b. Emit constraints from the ontology.** kg-side compiler (extend the existing
`OntologyToPslRuleCompiler` used by `injectOntologyRules`, orchestrator ~:1558): for each
relationship with cardinality MANY_TO_ONE / maxCardinality 1 emit the functional ArithmeticRule;
for each disjointWith pair keep the existing OWL falsehood-head hard rule. Gate:
`kbOntologyHardConstraintsEnabled` (default **false**).

**8c. Route hard programs to the exact solver.** lib `psl/HlMrfMapInference.chooseSolver`
(:173-185): if the program contains arithmetic rules or ∞-weight rules and ground-rule count ≤
ADMM cap, choose `AdmmHlMrfInference` (exact constraints) instead of the 1e6-penalty path.
Preserve current dispatch otherwise.

**8d. Surface violations as contradictions**: hard violations found at STEP 4 → publish through
the existing `ContradictionDetectedEvent` path with a `HARD_CONSTRAINT` reason (do not modify the
in-flight maintenance detector; event-level only).

Tests: 8a sentinel; DOMAIN/RANGE regression (soft rules unchanged); solver-routing test.
Modules: lib, kg.

---

### WP9 — Consensus guardrails [P]

Files: lib `learning/HybridConsensusTrainer` (:185 blend, :203-218 min-max), kg orchestrator
(`deriveHybridConsensus`, ~:1877).
1. Replace per-cascade min-max `normalizeScores` with scale-stable aggregation: per-entity score =
   **mean** of its MAP posteriors (already ∈[0,1]) + `clamp01` — no min-max (min-max makes targets
   relative to each cascade's extremes ⇒ scale drift). Keep min-max behind
   `kbConsensusMinMaxLegacy` (default false) for rollback.
2. Enforce `w = min(kbHybridConsensusWeight, kbHybridConsensusMaxWeight)` — new key, default 0.5 —
   so the observed-anchor fraction (1−w) is bounded away from 0.
3. Log per cascade: `consensus anchor: observed=<L1 norm share>, hybrid=<share>, w=<w>` (lands in
   the cascade transcript).
Tests: two cascades with different score ranges produce comparable targets; w capped.
Modules: lib, kg.

---

### WP10 — MEBN gradient fidelity + λ seeding (gated)

**10a. Noisy-OR SameDiff graph.** lib `learning/SameDiffMebnStrengthLearner` (:48-51, :208-215)
currently optimizes `predicted = s·pParent` (linear; correct only for single-parent with no leak,
where true noisy-OR is `leak + (1−leak)·s·p`). Rebuild the graph as full noisy-OR:
`predicted = 1 − (1−leak) · reduceProd(1 − s ⊙ pParents, parentAxis)` — expressible in SameDiff
(sub, mul, prod, rsub). The analytic oracle `MebnWeightLearner.analyticGradient` (:156-191,
`∂p/∂s = p_par(1−p_c)/(1−s·p_par+ε)`) already exists — tighten the oracle-vs-SameDiff tolerance
test to 1e-6 on multi-parent fixtures.

**10b. Stop seeding λ from edge quality.** lib `bayesian/NoisyOrCpt.computeCausalStrength` (:180,
`λ = weight·confidence·typeMult`) and attr `KgMTheoryBuilder` (:364-376): behind
`kbMebnConfidenceAsEvidence` (default **false** = today's behavior), switch to
`λ₀ = clamp01(weight · typeMult)` and carry `confidence` separately on the parent binding as a
soft-finding/evidence weight (edge *quality* is epistemic uncertainty, not causal efficacy).
Validate on the existing MEBN learner tests before recommending the flag flip.

Modules: lib, attr. Tests: oracle-match; flag-off bitwise-identical CPTs.

---

### WP11 — Chain length normalization [P]

Files: attr `algorithm/CausalTraversal` (:126-127, `conf = Π hopStrength`), kg
`matrix/service/MatrixGraphRagService` PathRAG reliability (:629-642, `Π edgeWeight`).
Add ranking score = geometric mean per hop `(Π s_i)^(1/n)` alongside the raw product (keep the
product as `reliability`, add `normalizedScore`; rank/prune by the configured one).
Config: `kbChainLengthNormalization` enum `NONE|GEOMETRIC_MEAN` (default **NONE** — behavior
gated), applied by both consumers. Tests: 5-hop×0.9 chain outranks 2-hop×0.75 under
GEOMETRIC_MEAN, reversed under NONE. Modules: attr, kg.

---

### WP12 — The synthesis pipeline (the feature)

Depends on: WP1, WP2, WP4, WP5 (required); WP3, WP6, WP7, WP8 improve its numbers but are not
blockers (leaf maps fall back to defaults).

**12a. lib `synthesis/` (new, thin — infra-free):**
- `CandidateSignal(String kind, Opinion opinion, String calibrationId, List<String> sourceRefs)`
- `SynthesisPolicy` — which operator per signal kind (defaults: retrieval signals ⊕ each other;
  engine signals CONSENSUS; type/consistency/retrieval groups ⊙; trust ⊗ at leaves)
- `AnswerSynthesizer.synthesize(List<CandidateSignals>) → List<SynthesizedAnswer(entityId,
  Opinion, OpinionTree trace)>` — pure fold over WP4/WP5 operators, unit-testable with no Spring.

**12b. kg `grounding/AnswerSynthesisService` (Spring orchestrator):**
```
synthesize(factSheetId, query, expectedType?, maxCandidates) :
  1. candidates ← MatrixGraphRagService seed+PPR scored nodes (expose a candidate-list variant of
     retrieveHybridContext that returns List<ScoredNode> instead of formatted context — refactor,
     do not duplicate the retrieval logic) ∪ LinkPredictor tails when kgeEmbedding present
  2. ω_text ← fromEmbeddingScore(σ_text(cos), u = f(top1−top2 margin));  ω_kge ← WP2c/WP6a
  3. ω_type ← via app-core OntologyProjectionProvider (already the pipeline-visible ontology seam):
     candidate entity_type ∈ allowedEntityTypes ⇒ b≈0.9 STRUCTURAL; violates a DOMAIN/RANGE axiom
     or disjoint ⇒ complement-dominated; unbound ontology ⇒ vacuous
  4. ω_cons ← screen: (i) same-atom conflicts among the candidate's supporting facts
     (InferredFactStore latest + JustificationIndex supports) via Opinion.conflict > kbConflictMassThreshold;
     (ii) WP8 hard violations touching the candidate ⇒ DROP; else ω_cons = complement of max conflict
  5. ω_psl ← KbGroundingService.verify per supporting atom + candidate atom (WP5 GROUNDING map,
     WP7 u when present), folded ⊙ over required atoms; ω_mebn ← BayesianNetworkService posterior
     when a theory is registered (else absent) ⇒ ω_inf = consensus(present engines)
  6. AnswerSynthesizer.synthesize(...) ⇒ ranked answers + traces
```
Per-fact-sheet read lock via the existing `FactSheetKbState` pattern.

**12c. REST**: `POST /api/kb-grounding/synthesize` on `KbGroundingController`
(request `{factSheetId, query, expectedType?, maxCandidates?}`; response
`{answers: [{entityId, label, likelihood, opinion:{b,d,u,a}, band, dropped?, dropReason?,
trace: <OpinionTree JSON>}], meta:{stale, kbVersion}}`). Wire into GlobalExceptionHandler scope.

**12d. MCP tool**: `ask_graph_synthesize` in cli `chat/tools/grounding/` (copy the
`AskGraphVerifyTool` HTTP pattern; register in `ToolRegistryFactory` next to the other ask_graph
tools; `compactHint()` = one-line usage per the tool-schema convention). Returns top-N with
likelihood + band + 3-line compact trace per answer.

Config: `kbSynthesisMaxCandidates` (25), `kbConflictMassThreshold` (0.25),
`kbSynthesisDropOnHardViolation` (true).

Tests (kg, end-to-end synthetic graph): 3 candidates where (a) the type-violating one ranks below
a conformant equal, (b) a contradicted one is dropped with `dropReason`, (c) the trace root
expectation equals the operator-tree recomputation, (d) UNKNOWN-heavy candidate returns
mid likelihood with high u — never 0. Controller test + one CLI tool test.

---

## 3. Phase plan

| Phase | WPs | Parallelizable? | Builds |
|---|---|---|---|
| A | WP1, WP4 | yes — file-disjoint edit-only subagents, one consolidated build | lib, kg |
| B | WP2, WP3, WP9, WP11 | WP2/WP9/WP11 parallel; WP3 solo (touches orchestrator) | lib → kg → attr |
| C | WP5, WP6, WP7, WP8 | WP5 first (others feed its leaf maps); then WP6/WP7/WP8 sequential in kg | lib → kg → app |
| D | WP12 | solo | lib → kg → app → cli |
| E | WP13, WP14, WP17 (Part II — propagation correctness + speed) | WP17 sub-items parallel; WP13/WP14 sequential in kg | lib → kg |
| F | WP15 + WP16 TOGETHER (propagation needs scoping), then WP19 | no | lib → kg → crawl |
| G | WP18 (tabular shape — crawl-side, independent) | yes | app-core → loaders → crawl |
| H | WP20 + WP21 (Part III — durable writes + non-blocking reads; blockers for agent-maintained KBs) | no | lib → kg → change-tracking |
| I | WP22 + WP23 (retention + eval/telemetry) | sub-items parallel | kg → evaluation |
| J | WP24 (LLM interface: compactHints, discovery, budgets, verbal scale, LlmReasoningView) | yes | lib → kg → app → cli |
| K | WP25 (Part IV — induction-loop bridges) | 25a-e parallel-ish (different beans) | kg → app → process-engine |
| L | WP26 + WP27 (+WP28) — KG rule mining + Knowledge Digest | WP26 then WP27 | lib → kg → crawl → cli |
| M | WP29 + WP30 (Part V — enforcement policy + type-level reasoning; 30e = user decision gate) | WP29/WP30 parallel | lib → kg → process-engine |
| N | WP31 — canonical-slot election + unified resolution trace | no | lib → kg → cli |
| O | WP32 — canonical business processes (election + conformance drift + BPMN trace) | no | kg → process-discovery → app |
| P | WP33 (Part VI — extraction contract fix pack; **33a = P0 bug fix, ship immediately**) | 33a first, rest behind kbExtractionContractV2Enabled | app-core → kg → crawl |
| Q | WP34 + WP36 (vocabulary anchoring + signal balance; WP34 is a prerequisite for WP26/WP31 to be useful) | yes | app-core → kg → crawl |
| R | WP35 (quality feedback loops) | sub-items parallel | kg → crawl → evaluation |
| S | WP37 + WP38 (Part VII — question typology + temporal/measure model; promotes EmailBodyValueExtractor to shared) | yes | lib → app-core → kg → app |
| T | WP39 — aggregation algebra + roll-up reconciliation (needs WP31 + WP38) | no | lib → kg |
| U | WP40 — enumeration + catalog/lifecycle semantics (40a/40b may start with S) | partial | kg → crawl |
| V | WP41 + WP42 (Part VIII — case correlation + ProcessInstance + alignment/cycle times) | WP41 then WP42 | kg → process-discovery → process-engine |
| W | WP43 — unified instance enumeration (engine runs ∪ mined cases) | no | process-engine → app → cli |
| X | WP44 — process evolution (version diff + cohort mining diff + election timeline) | 44a parallel with V | process-discovery → process-engine → app |
| Y | WP45 + WP46 (Part IX — hybrid contributions + SourceRef spine; 45c display bug fixes ship first) | 45c immediate; 45/46 parallel-ish | lib → kg → app (incl. frontend) |
| Z | WP47 — DecisionTrace unified rendering (absorbs WP24's LlmReasoningView) | no | lib → app → cli |
| AA | WP48 (Part X — backend ReferenceResolver + DTO display pairing + atom-key path hygiene) | 48b fixes parallel after 48a | kg → app → cli |
| AB | WP49 — frontend DisplayRefService + pipe + 8 component fixes | yes (per component) | app frontend |
| AC | WP50 — id-leak sentinel harness (write FIRST, red→green across AA/AB) | 50a before AA ideally | kg → app → cli |

After each phase: full test run of every touched module (serialize: lib → kg → crawl/attr → app),
record test counts, and a one-paragraph status note. Do not start Phase D until Phase A+B are
green; C items may land incrementally.

## 4. Out of scope (do not do here)

- Touching kg `maintenance/ContradictionDetector` internals (actively in-flight elsewhere — only
  consume `Opinion.conflict()` as an exposed operator).
- Chat DTO / frontend trail rendering (pass-1 rec #2 — separate effort; WP12's REST+MCP is the
  agent-facing surface).
- True joint max-product MPE, full Bayes-Ball d-separation, trained GNN — documented follow-ons.
- Flipping default-off flags (`kbFusionOperatorTreeEnabled`, `kbMarginalsEnabled`,
  `kbOntologyHardConstraintsEnabled`, `kbMebnConfidenceAsEvidence`, `kbChainLengthNormalization`)
  — ship them off; flag-flip is a user decision after validation on a real crawl.

## 5. Acceptance checklist (whole plan)

- [ ] All touched module suites green; lib suite count strictly increases.
- [ ] No new Spring/JPA deps in lib; no `@Value`; every new tunable in KbConfig + service.ts + panel.
- [ ] Re-running an unchanged cascade N times does not move any StrengthBand (WP3 sentinel test).
- [ ] `POST /api/kb-grounding/synthesize` returns ranked answers whose root likelihood is
      reproducible from the returned trace by an independent OpinionTree evaluation.
- [ ] `ask_graph_synthesize` callable end-to-end against a running app.
- [ ] Every formula-bearing change cites its WP in the commit/PR description.
- [ ] Part II: cascade on an unchanged graph is a near-no-op (WP17 hash skip); alias-unified
      entities share evidence (WP14 sentinel); a propagation rule grounds O(E) not O(N²) (WP15);
      community-scoped solve produces the same MAP objective ±1e-3 as monolithic on a 2-community
      fixture (WP16); PageRank/PPR under subprocess mode no longer computes over an empty matrix
      (WP17e).

---

---

# PART II — End-to-end propagation + topology exploitation (pass-3, 2026-07-02)

> Pass 3 traced how value/uncertainty/provenance actually propagate end-to-end, inventoried every
> topology computation, cost-modeled the cascade and query paths, and characterized the graph shape
> produced from messy data (emails + PDF attachments, Excel/inventory sheets). Goal restated by the
> user: **find similar overlapping ideas and synthesize them with a clear reasoning trace QUICKLY,
> on large graphs over messy unorganized data.**
>
> **The two headline findings that shape everything below:**
> 1. **The PSL program is topology-blind.** The auto-program contains ONLY per-atom copy rules
>    (`w: pred(?X,?Y) -> derived_pred(?X,?Y)`, orchestrator :1795-1803). NO rule propagates truth
>    along graph edges; the one topology-aware inference (OWL prp-trp BFS closure,
>    OwlRlReasoner:90-166) runs on a separate path and never feeds the FactStore. The "graph
>    reasoner" does not reason over the graph.
> 2. **Adding propagation rules without scoping explodes grounding** (2-var rules ground
>    O(A_p1×A_p2); at 50k atoms one transitivity rule ⇒ ~25M candidates, silently truncated at the
>    500k `MAX_GROUND_RULES` cap, PslProgram:47,317). So **WP15 (propagation) and WP16
>    (community/neighborhood scoping) must ship together** — communities are simultaneously the
>    unit of parallel solving, retrieval seeding, evidence independence, and "overlapping ideas."

## II.0 The seven propagation breaks (verified, with fix ownership)

| # | Break | Evidence | Fixed by |
|---|---|---|---|
| B1 | Projector reads only scalar `edge.getConfidence()`; the full Beta `_opinion` metadata never reaches the FactStore | GraphToFactStoreProjector:191 | WP13 |
| B2 | Node-type facts always projected `Fact.observed` (hard 1.0) regardless of node confidence — messy LLM types become certainties | GraphToFactStoreProjector:147 | WP13 |
| B3 | No edge-propagation rules; OWL BFS closure disconnected from the cascade | orchestrator:1795-1803; OwlRlReasoner:90 | WP15 |
| B4 | Promotion state (bands/Beta) write-only — never re-attached to graph or read by retrieval | FactPromotionTracker:307-314 | WP13d |
| B5 | Binary inferred facts land on edges; `nodeGroundedConfidence` reads only node metadata → inferred relations invisible to ranking | InferredFactGraphMaterializer:216-222; MatrixGraphRagService:557-570 | WP13d |
| B6 | Entity merge drops the loser's `_opinion`/evidence (`putIfAbsent`), elects canonical by raw scalar, and orphans promotion + InferredFact atom keys (old externalIds) | GraphCompactionService:2084,1907; no migration exists | WP14 |
| B7 | Alias/`RESOLVES_TO`/`SHARED_ENTITY` edges carry weight=1.0 with no Opinion, and aliased entities remain separate PSL atoms — corroboration never transfers across aliases | GraphEdgeComputationServiceImpl:907-909; EntityResolutionService:394 | WP14 |

(Working-tree note: `RESOLVES_TO`→`RELATED_TO` type-collapse on the matrix store is ALREADY FIXED —
edge types round-trip losslessly; the Javadoc at MatrixKnowledgeGraphService:129-135 describes the
old bug. Do not re-fix. The gap is traversal/unification, not storage.)

---

### WP13 — Opinion-preserving projection + read-back (B1/B2/B4/B5)

**13a.** `GraphToFactStoreProjector` (kg, :183-208): when edge metadata has `_opinion`, parse it
(`Opinion.fromJson`) and project `value = opinion.expectation()` AND carry the opinion onto the
Fact — add optional `Opinion opinion` (or `evidencePos/evidenceNeg/priorStrength` triple) to `Fact`
(lib, additive, nullable, compact-ctor back-compat). Fallback: current scalar behavior. WP5/WP12
leaf maps prefer the carried opinion over reconstruction.
**13b.** Node-type facts (:147): project as `Fact.soft(atomKey, nodeConfidence, sourceId)` when the
node's confidence < 0.99 (read node `_opinion` expectation else `confidence`); keep `observed` for
structural certainty (STRUCTURAL basis).
**13c.** Config: `kbProjectionOpinionEnabled` (default **true** — strictly more information, values
identical when `_opinion` absent).
**13d. Read-back closure (B4/B5):** in the cascade materialization step (Part I context: it exists
behind `kbCascadeMaterializeInferredEnabled`), ALSO write for **binary** facts a node-level rollup on
both endpoint nodes: `inferred.support.confidence = max(existing, fact.confidence)` so
`nodeGroundedConfidence` (which only reads node metadata) sees inferred binary relations; and stamp
the promotion band (`_band`, `_evidencePos/_evidenceNeg`) back onto the materialized edge metadata
each cascade (cheap — the tracker already has the state in hand at :307).
Tests: opinion round-trips projector→Fact→leaf map; node-type fact carries node confidence; binary
inference lifts both endpoints' rollup key; band visible on materialized edge metadata.
Modules: lib (Fact field), kg.

---

### WP14 — Evidence-preserving merge + alias unification at the reasoning boundary (B6/B7)

This is the "**find similar overlapping ideas**" evidence layer: overlap is already DETECTED
(ALIAS hubs from `computeNameBasedCrossDocEdges` with ~11 identity buckets, `RESOLVES_TO` via
IdentifierScheme/GTIN/EMAIL, `SHARED_ENTITY`, embedding-similarity edges) but never CONSOLIDATED.

**14a. Physical merge preserves evidence** (kg `GraphCompactionService`):
- metadata merge (:2082-2109): for `_opinion`/`_evidencePos`/`_evidenceNeg`, replace `putIfAbsent`
  with **fusion** — parse both opinions, `canonical._opinion = winner.cumulativeFuse(loser)` when
  their evidence sources differ (WP3 source-keyed ledger tells you), else keep max-expectation.
- canonical election (:1907): elect by Opinion **expectation** (fallback scalar).
- **atom-key migration**: after merge, rewrite/alias promotion-tracker state and InferredFactStore
  entries from old externalId atoms to canonical atoms (add `migrateAtomKeys(oldId, canonicalId)`
  on both stores; Lucene impl = read-rewrite-write, bounded by atoms mentioning the id). Without
  this every merge orphans accumulated evidence.
**14b. Logical unification WITHOUT physical merge** (the cheap, reversible one — kg
`GraphToFactStoreProjector`): build a union-find over `ALIAS_OF` + `RESOLVES_TO` edges with
confidence ≥ `kbAliasUnifyMinConfidence` (default 0.75); project every atom through
`canon(entityId)` so aliased entities SHARE atoms — corroboration transfers automatically, and
distinct source documents behind different aliases become distinct sources for the WP3 Beta ledger
(⊕ semantics for free). The graph keeps its split nodes (non-destructive, per the resumability
mandate); only the reasoning view unifies. Record the applied mapping on the cascade result +
GroundingProgressEvent so traces can show "unified: {Acme Corp, ACME, acme.com}".
**14b-EMBEDDING extension (SHIPPED 2026-07-03)**: `buildAliasCanon` also unions
`EMBEDDING_SIMILARITY` edges into the same union-find, behind two hard guards — a strict identity
floor `kbAliasEmbeddingMinSimilarity` (default 0.95, deliberately far above the ~0.7 relatedness
threshold those edges are built with: "same topic" must never mean "same entity") and a
same-`entity_type` requirement on both endpoints (a PERSON never merges with an ORGANIZATION
regardless of cosine; unknown types never merge). `kbAliasEmbeddingUnifyEnabled` (default true —
inert without similarity edges clearing the floor, the honest no-artifact path). The projector logs
"N ids onto M canonical ids"; config rides KbConfig + the KB Confidence settings UI (the whole
alias group, previously REST-only, is now a settings section). Tests: `AliasUnifyProjectionTest`
+4 (same-type unify at 0.97, relatedness-level 0.85 rejected, cross-type/unknown-type never merge,
extension-off leaves explicit aliases working).
**14c. Stamp the identity edges**: route `ALIAS_OF`/`SHARED_ENTITY`/`RESOLVES_TO` creation through
`ExtractionConfidenceStamper` (STRUCTURAL basis for identifier-backed, CORROBORATION for
name-bucket) instead of bare weight 1.0 / max-scalar.
Config: `kbAliasUnifyEnabled` (default **false** initially), `kbAliasUnifyMinConfidence` (0.75).
Tests: two aliased entities with 1 source-doc each → unified atom has 2-source Beta evidence and a
higher band than either alone (THE sentinel for "overlapping ideas"); merge migrates promotion
state (no orphan keys); union-find respects the confidence floor.
Modules: kg (+ lib only if Fact needs nothing new — it doesn't).

---

### WP15 — Topology-aware propagation, groundable by construction (B3)

Rules that propagate along edges, each grounded via **indexed joins over EXISTING binary atoms**
(never entity×entity cartesian) — verify `PslProgram.groundInto`'s join path grounds
shared-variable bodies by index (join-order optimization exists, :394-441); add a regression test
asserting ground-rule count is O(|matching edges|).

**15a. Rule templates** (new kg `PropagationRuleTemplates`, injected in `buildProgramFromFactStore`
behind flags, weights are KbConfig keys):
- **similar-transfer** (the overlapping-ideas propagator):
  `w_sim: similar(?X,?Y) & derived_P(?X) -> derived_P(?Y)` — `similar` atoms come from embedding
  kNN via the (Part I WP-pass) `EmbeddingPslEvidence` wire, k capped (`kbSimilarKnnK`, default 5),
  threshold `kbSimilarMinCosine` (0.75) ⇒ ground-rule count ≤ k·N per propagated predicate.
- **co-mention support**: `w_co: co_mentioned(?X,?Y) & derived_P(?X) -> derived_P(?Y)` (see 15b).
- **alias-consistency** (only when WP14b unification is OFF):
  `w_al: alias_of(?X,?Y) & derived_P(?X) -> derived_P(?Y)` — soft version of unification.
Propagated-predicate allowlist `kbPropagationPredicates` (empty = none; NEVER default-propagate
every predicate).
**15b. One-mode bipartite projection** (the messy-data reality is doc→entity stars): new
EDGE_COMPUTATION substep emitting entity–entity `CO_MENTIONED` edges for entity pairs sharing a
document/section, `weight = min(1, count/kbCoMentionSaturation)` (default 5), capped per document
(`kbCoMentionMaxEntitiesPerDoc`, default 50 → ≤ C(50,2) pairs/doc). This exploits the bipartite
structure that `SparsityMetrics.isLikelyBipartite()` already detects but nothing reads — gate the
substep on that flag.
**15c. Bridge the OWL closure into the cascade**: after the OWL enrichment path computes prp-trp /
subClassOf closures (`OwlRlReasoner`), project the inferred `GraphRelation`s into the FactStore as
soft facts with `value = min(chain edge confidences)` and basis `PSL_INFERENCE` — do NOT re-derive
transitivity inside PSL (that's the O(N²)+fixpoint trap; BFS already did it in O(E)).
Config: `kbPropagationRulesEnabled` (default **false**), per-template weight keys.
Tests: k-NN similar-transfer lifts a cold twin entity's derived value (quantified fixture);
ground-rule count linear in edges (regression guard vs cartesian); OWL-closure facts appear in
verify() with min-chain confidence.
Modules: lib (template grounding test only), kg, crawl (co-mention substep).

---

### WP16 — Communities as the unit of speed, scope, and synthesis

Current reality: Louvain/LabelProp are live but consumed only by RAG context text, taxonomy
enrichment, and UI; assignments are heap-cached per JVM, never persisted; the HL-MRF solve is
monolithic; retrieval PPRs the whole graph.

**16a. Persist community assignments as position metadata**: new hydration substage (after
ENRICHMENT/prune, before HEALTH) runs Louvain once per cascade epoch (`kbCommunityAssignEnabled`,
default true; resolution `kbCommunityResolution`) and writes `community.id` + `community.epoch`
into node metadata (travels with the graph asset, store-agnostic seam) + graph `:meta` records
{algorithm, resolution, modularity, count}. `GraphCommunityService` serves persisted assignments
when epoch-fresh; recompute on demand.
**16b. Community-blocked MAP solve**: partition ground rules by the community of their atoms'
entity args — intra-community blocks solve **in parallel** (bounded executor
`kbCommunitySolveParallelism`); cross-community ground rules form the boundary set, solved in
`kbCommunitySolveRounds` (default 2) block-coordinate passes with block interiors warm-started.
Convex objective ⇒ monotone decrease; with today's copy rules the blocks are exactly independent;
with WP15 rules the coupling is exactly the cut edges. Gate: `kbCommunityScopedSolve` (default
false); fall back to monolithic when communities=1 or assignments stale. Acceptance: 2-community
fixture reaches the monolithic MAP objective within 1e-3; wall-clock scales with the largest
community, not the graph.
**16c. Community-first retrieval + synthesis (the QUICK path)**: `CommunitySummaryService` reports
already exist and cache per graphId — add a summary-embedding index: query → cosine against C
community-summary embeddings (C ≪ N) → top-`kbSynthesisCommunities` (default 3) → run PPR/candidate
generation ONLY inside the union of those communities' induced subgraphs (this is what the dark
`CommunityViewMaterializer`/`SubgraphMaterializer` were built for — wire them as the induced-view
provider). Part I WP12's Stage-1 gains a `communityScope` mode. Effect: candidate generation cost
O(E_topCommunities) instead of O(E); trace gains a "communities consulted" node.
**16d. Communities as independence structure for fusion**: in the WP3/WP5 evidence model, treat
corroboration from a DIFFERENT community's documents as more independent: `kbCrossCommunityBoost`
(default 1.0 = off) scales the ⊕-vs-consensus choice — cross-community sources ⊕, same-doc-cluster
sources consensus. (Config-gated heuristic; document as such.)
Persist nothing in-heap-only: assignments + summary embeddings must survive restart (metadata +
existing summary cache persistence path).
Modules: lib (block solver + induced views), kg, crawl (hydration substage).

---

### WP17 — Cost-cliff fixes, caches, and subprocess topology correctness [items parallel]

| Item | Current (evidence) | Fix |
|---|---|---|
| a. Projector node lookups | 2 `getNode` per edge, 300k lookups @150k edges (GraphToFactStoreProjector:175-176) — while `getNodesInFactSheet` was ALREADY fetched | build an id→node map from the fetched list; zero extra store calls |
| b. Unchanged-cascade skip | every cascade re-projects, re-grounds, re-solves, rebuilds JustificationIndex from scratch (no caching of anything, PslProgram cache dies with the instance) | content-hash the projected FactStore (values + atom keys); if unchanged since last runId and rules/weights unchanged → skip steps 2-9, `markEpoch` only. Config `kbSkipUnchangedCascade` (default true). Also neuters double-fire waste |
| c. Conjunctive query index | `ConjunctiveQueryEngine` full-scans `store.allLatest()` and rebuilds a predicate index PER QUERY (:185-192) | build the predicate index once per epoch in `FactSheetKbState`, invalidate on `markEpoch` |
| d. KGE vector decode cache | `kgeStructuralSimilarity` re-decodes every node's metadata vector per query (MatrixGraphRagService:506-528) | per-graph-epoch decoded-vector cache (nodeId → float[]) in the adapter |
| e. **Subprocess empty-matrix bug (CORRECTNESS)** | in subprocess mode `getCombinedAdjacencyMatrix` returns an empty stub (SubprocessMatrixGraphStore:413-414) ⇒ PageRank/PPR/HITS/communities silently compute over an EMPTY graph | either execute algorithms subprocess-side (new bulk `/invoke` ops: pageRank, ppr, communities — algorithms already live next to the matrix) or add a bulk edge-list export the client builds a local CSR from. Add a guard: algorithm entry points throw/warn on stub matrices instead of returning empty results |
| f. Grounding truncation visibility | `MAX_GROUND_RULES` hit = silent `break` (PslProgram:47,307-319) | `log.warn` + `RegroundResult.truncatedGroundRules` + surface on GroundingProgressEvent |
| g. Explain PSL mode fresh-solve | `/api/explain?mode=PSL` builds + MAP-solves a fresh program per request | default to reading the cascade's InferredFactStore + JustificationIndex (KbGroundingService.explain); keep fresh solve as `mode=PSL_FRESH` |
| h. CSR for PPR | sparse PPR walks `getNeighbors()` per iteration; the CSR cache exists but is GNN-only (AdjacencyMatrixGraph:776) | route `personalizedPageRankSparse` through `getCsrForEdgeType` (already invalidation-correct) |

Tests per item (b: sentinel "second cascade on unchanged graph does < 5% of first cascade's store
reads"; e: subprocess-mode PageRank returns non-empty on a seeded graph). Modules: lib, kg.

---

### WP18 — Tabular/inventory graph shape (crawl-side)

Current shape for a 10k-row inventory sheet: TABLE→CELL star, one node per non-empty cell, NO row
entity, NO cross-row SKU dedup (500 dup SKUs = 500 CELL nodes), numeric values as plain strings,
no temporal stamp, `TableCellGraphBuilder` silently returns an EMPTY graph above 5000 cells
(:172-175), and the LLM pass sees only the 200×60 markdown preview.

**18a. ROW + key-entity aggregation**: `TableCellGraphBuilder` (app-core, working-tree-modified —
coordinate) gains ROW nodes (`TABLE —HAS_ROW→ ROW —HAS_CELL→ CELL`, cells beyond budget collapse
into ROW metadata properties instead of nodes); detect a key column (header heuristics: sku/id/
gtin/code + IdentifierScheme match on values) and emit ONE entity per distinct key value with
`rowCount`, first/last row refs, and aggregated numeric columns (sum/min/max/mean per numeric
header) as typed metadata. **Evidence semantics: all rows of one file = ONE source** (WP3 ledger:
single idempotent source entry; `rowCount` is metadata, NOT extra Beta evidence — do not inflate
belief by row repetition).
**18b. Numeric/unit typing**: numeric cells → `value` (double) + `unit` (string, from header or
cell suffix) metadata on CELL/ROW — never entity nodes; TABLE node gets per-column aggregate
metadata. Enables quantity questions without per-cell graph walks.
**18c. Temporal stamping**: propagate file lastModified / sheet metadata date → `occurredAt` on
ROW/key-entity facts (`_validFrom`), so inventory snapshots from different dates are
distinguishable (TemporalView-ready).
**18d. Cap behavior**: above the cell budget, fall back to ROW+key-entity graph (18a) instead of
returning empty; log the downgrade; raise visibility via crawl transcript.
**18e. LLM row-windowing**: table extraction prompt path chunks rows into windows
(`kbTableLlmRowWindow`, default 200) over the SAME schema header, merging entities by key column —
so 10k-row sheets get full coverage where 18a's deterministic path needs semantic help.
Config: `kbTableRowEntities` (default true), budgets. Tests: 10k×5 fixture → ~#distinctKeys
entities + ROW nodes within budget, no empty-graph silent drop; duplicate SKU rows → one entity,
rowCount=500, evidence = one source; numeric aggregates correct.
Modules: app-core, loader-excel, crawl. (Respect the crawl-resumability mandate: the new substeps
are independently re-runnable.)

---

### WP19 — Consolidated topology priors (position as evidence) [small]

Dark/ad-hoc today: HITS implemented with zero callers (MatrixGraphAlgorithms:405); betweenness
on-demand only; `NoisyOrCpt.estimatePrior` degreeBoost (:211) and `KgMTheoryBuilder`
connectivityBonus (:292) are two hand-rolled degree heuristics; no k-core anywhere.
- Compute once per epoch (same hydration substage as WP16a): PageRank, HITS hub/authority, degree
  stats (k-core optional, later) → node metadata `topology.pagerank`, `topology.hub`,
  `topology.authority` (+ graph :meta epoch).
- New `TopologyPriorProvider` tier in `CascadePriorProvider`'s waterfall (after OpinionStore,
  before type-frequency): prior from a calibrated squash of PageRank percentile
  (`kbTopologyPriorWeight`, default 0 = off).
- Replace the two ad-hoc degree heuristics with reads of the same persisted stats (consolidation —
  one positional-signal source).
Modules: lib (provider), kg. Tests: prior tier ordering; heuristic-replacement equivalence at
default config.

---

## II.1 Why this delivers "overlapping ideas, synthesized, with a trace, QUICKLY" (see end of Part II)

---

# PART III — Operational hardening (pass-4, 2026-07-02)

> Pass 4 audited durability/restart, concurrency, storage growth, the correction loop, evaluation,
> and the LLM interface. Good news first: `LuceneInferredFactStore` hydrates on construction so
> **verify() survives restart**; `PinGuard` EXISTS and the orchestrator skips pinned atoms at
> materialize (:817-818); `KbCorrectionService.correct()` is a full 6-step flow INCLUDING
> weight-learning feedback (`updateOnBatch`, 3 steps) — the correction→learning loop is BUILT.
> The gaps are at the edges of those mechanisms.

## III.0 Verified operational defects

| # | Defect | Evidence | Fix |
|---|---|---|---|
| O1 | **Agent asserts are durability-zero**: `assertFact` writes only in-memory FactStore/ConcurrentFactStore — never the graph, never Lucene. Restart wipes all asserts; same-key re-projection overwrites them | KbGroundingService:316-349; FactSheetKbState:99-109 | WP20 |
| O2 | **`FactCorrectedEvent` is a dead letter** — no `@EventListener` anywhere consumes it; a human correction pins + audits but the propagating cascade never runs (the Javadoc claims it does) | GroundingCascadeEventListener.java (3 handlers, none for FactCorrectedEvent); KbCorrectionService:49 | WP20 |
| O3 | Maintenance contradiction resolution **bypasses PinGuard** — a human-pinned atom can be retracted by `resolveContradictions` | GraphMaintenanceServiceImpl:162-171 | WP20 |
| O4 | Queries **block for the entire 17-step cascade** — write lock spans projection→checkpoint; verify/query/explain take the read lock | orchestrator:488-497; KbGroundingService:157,178,259,285 | WP21 |
| O5 | Coalescing gate **drops** events arriving during a running cascade (no depth-1 re-run) — high-frequency ingest leaves the KB stale until the next unrelated mutation | GroundingCascadeHook:153-158,194-195 | WP21 |
| O6 | No boot healing: nothing re-grounds stale/mid-crash fact sheets on startup (checkpoint only seeds a counter); explain() is silently empty after restart until the next cascade | TrainingCheckpointStore read at orchestrator:1033-1045; FactSheetKbState:86-90 | WP20/21 |
| O7 | Unbounded growth ×4: in-heap InferredFact version history O(cascades×atoms); FileWeightStore version files + LuceneWeightStore version docs (never deleted); audit JSONL no rotation | InMemoryInferredFactStore:36,44; FileWeightStore:63; LuceneWeightStore:79-100; FileBackedAuditLog:59 | WP22 |
| O8 | **EXTRACTED edges duplicate on every re-crawl** — `createEdgeWithMetadata` unconditional, no existence check | GraphPersistenceHelper:296 | WP22 |
| O9 | **Stale marks never leave reasoning**: pruning is mark-only (correct per mandate) but the projector has ZERO stale filtering — pruned facts re-enter the FactStore every cascade (verified: no "stale" occurrence in GraphToFactStoreProjector) | InferredFactGraphPruner:55; GraphToFactStoreProjector | WP22 |
| O10 | Zero grounding evaluation code (design-only doc); kompile-evaluation is RAG-only and disabled by default; verify threshold 0.5 never swept | grounding-evaluation-design.md ("Design-only"); kompile-evaluation module | WP23 |
| O11 | Telemetry ephemeral: GroundingProgressEvent → STOMP only; MAP solver stats (iterations/objective/converged) unpersisted; `LearningMetrics.ruleWeightsUpdated` always −1; `ModelTrainedEvent` has no consumer; band distribution point-in-time only (no drift series) | orchestrator:772-778; LearningMetrics:112 | WP23 |
| O12 | LLM interface: no `compactHint()` on any of the 7 ask_graph tools (schemas served COMPACT ⇒ agents get truncated descriptions); **no atom/predicate discovery tool**; no token caps on trail/context injection (depth-5 derivation ≈ 9k tokens into the prompt); band words only in ask_graph_verify (everything else raw floats); `LlmReasoningView`/`VerbalScaleMapper` still unbuilt; ask_graph absent from AGENTS.md | AskGraph*Tool (compactHint null); AgentChatService:467; GraphReasoningRetriever:171,213 | WP24 |
| O13 | stateMap unbounded (no eviction) — ~1-4 GB+ heap at 100 fact sheets × 50k atoms | KbGroundingService:80,555 | WP22 |

### WP20 — Durable, complete agent/human write loop (O1/O2/O3/O6)

**20a. Durable asserts.** `assertFact` additionally persists the asserted fact to the durable
`InferredFactStore` as an ASSERTED-basis versioned fact (it already has the store in hand), and
`getState` re-hydration replays ASSERTED facts into the FactStore/ConcurrentFactStore after the
Lucene hydrate. Re-projection survival: `GraphToFactStoreProjector` re-applies the assert overlay
AFTER projecting graph facts (asserts win over same-key graph values unless the assert is older
than the edge's last update — compare timestamps). Config: `kbAssertsDurable` (default **true**).
**20b. Wire the dead letter**: add the missing `@EventListener(FactCorrectedEvent)` in
`GroundingCascadeEventListener` → `groundingResetPort.schedule(fsId, "correction:"+atomKey,
TRIGGER_MANUAL)`. One method; sentinel test: correct() → cascade runs → dependent derived atom
moves while the pinned atom itself does NOT (PinGuard).
**20c. PinGuard everywhere**: maintenance `resolveContradictions`/`resolveContradictionsByEdgeSelection`
check `pinGuard.isPinned` before staling a pinned atom's edges (skip + report "pinned" in the
resolution result). Coordinate with the in-flight contradiction workstream — this is an additive
guard call, not a refactor.
**20d. Retract ergonomics**: document assert(value=0.0) as the retract idiom in the assert tool
description + add `tombstone` param passthrough; weight-learning cold-start (programSnapshots
empty) logs at WARN with "run a cascade first" instead of silent DEBUG skip.
**20e. Boot healing (optional)**: `kbRegroundStaleOnBoot` (default false) — ApplicationReadyEvent
listener schedules a cascade for fact sheets whose Lucene store is non-empty (cheap existence
probe), bounded by `kbBootRegroundMaxFactSheets`.

### WP21 — Non-blocking reads + lossless coalescing (O4/O5)

**21a. Snapshot-swap cascades**: `doReground` builds the next state OFFLINE (fresh FactStore +
program + solve + new JustificationIndex) while readers keep the current snapshot; swap the
FactSheetKbState member references under a millisecond write-lock at the end. Members become
immutable-per-epoch references. verify/query/explain never block on a running cascade; `meta.stale`
already tells callers a newer epoch is coming. (The InferredFactStore is append-versioned —
already safe for concurrent read.)
**21b. Dirty-flag re-run**: `GroundingCascadeHook` — events arriving while running set a dirty
flag; on completion, if dirty, schedule exactly one follow-up cascade (depth-1 queue). Sentinel:
N events during a run ⇒ exactly 2 cascades total, final state reflects the last event.

### WP22 — Retention + growth control (O7/O8/O9/O13)

- **In-heap version history cap**: `InMemoryInferredFactStore` keeps latest-K versions per atom
  (`kbInferredVersionHistoryK`, default 10) — `purge()` exists, add `trimHistory(k)`; Lucene tier
  is already 1-doc-per-atom (verified — keep it that way).
- **Weight-store caps**: `maxVersions` on FileWeightStore main files (backup pruning pattern
  exists at :75 — extend it) + LuceneWeightStore deletes version docs older than K.
- **Audit rotation**: size-based rotation for `FileBackedAuditLog` (`kbAuditMaxBytes`, gzip
  rolled files — follow the log-aggregation subsystem's conventions).
- **Re-crawl edge dedup (O8)**: `GraphPersistenceHelper.createEdgeWithMetadata` path gains an
  upsert keyed on (srcExternalId, tgtExternalId, relationType, sourceDocId) — update confidence/
  metadata on match instead of duplicating. THE fix for messy-data re-crawls.
- **Stale filtering (O9)**: `GraphToFactStoreProjector` skips stale-marked nodes/edges
  (`kbProjectStaleFacts` default **false**) — pruning finally takes effect on reasoning while
  staying mark-only in the store. Plus an optional TTL hard-GC for stale INFERRED edges
  (`kbStaleInferredGcDays`, default 0 = never — mandate-compliant).
- **State eviction (O13)**: LRU cap on the stateMap (`kbMaxResidentFactSheets`, default 32) —
  eviction is safe because Lucene rehydrates on next access (verified restart path).
- Lucene hygiene: scheduled low-priority `forceMerge` budget via the maintenance registry.

### WP23 — Grounding evaluation + persistent telemetry (O10/O11)

**23a. GroundingEvalHarness** (implements the existing design doc): synthetic employment-graph
fixture (generator + JSONL under the module's test resources), 3-class Macro-F1 for verify,
threshold sweep {0.3..0.8} (answers the uncalibrated-0.5 question with data), calibration curve
(reliability diagram bins) for InferredFact confidences, Hits@k for query. Ship as (1) JUnit
regression tests with floors (Macro-F1 ≥ 0.70 on synthetic) and (2) new grounding evaluators in
kompile-evaluation (module exists; follow its disabled-by-default pattern).
**23b. Telemetry persistence**: a JSONL sink alongside the STOMP bridge — per cascade: stage
durations, MAP {iterations, objective, converged}, ground-rule count + truncation flag (Part II
WP17f), versionsWritten, retractions, band counts, weight-delta summary (fix
`ruleWeightsUpdated=-1` by returning the delta from the learning step). Location: the existing
`data/graph/health/` time-series convention. Add `GET /api/kb-grounding/{fs}/telemetry` (last N
cascades) + band-distribution history (drift = the band-counts series; simple SUPPRESSED-growth
warning in GraphHealthService). Consume or delete `ModelTrainedEvent`.

### WP24 — LLM interface layer (O12)

- **compactHint() on all 7 ask_graph tools** (one dense line each: param format + verdict shape +
  stale semantics) + an `## ask_graph` section in the AGENTS.md template (verify-vs-query-vs-
  explain decision matrix + atom-key grammar `lowercase_predicate(sanitized_id,...)`).
- **Predicate/atom discovery**: `GET /api/kb-grounding/{fs}/predicates` (distinct predicates +
  counts + 3 sample atoms each, from the InferredFactStore index) + `ask_graph_predicates` tool —
  unblocks agents from guessing atom keys.
- **Token budgets**: `kbChatContextMaxChars` cap on graph-context injection (AgentChatService:467);
  `ask_graph_explain` gains `summaryOnly` + `maxTreeChars` (default 4000) — over budget ⇒ NL
  summary + top-3 evidence atoms only.
- **Verbal scale everywhere**: `StrengthBand.fromScalar(c)` words appended wherever floats are
  emitted to LLMs (GraphReasoningRetriever causal/probabilistic contexts :171/:213, explain
  responses, synthesize results). Build the small lib `view/` package at last: `LlmReasoningView`
  (projects ReasoningTrail/PslInferenceResult → {verdict, band word, 1-line why, top evidence}) +
  `VerbalScaleMapper` (band→calibrated phrase) — used by chat, tools, and WP12 synthesize output.
- Stale-flag + remediation hints in tool descriptions; optional enforcer seam (ClaimToAtomKeyMapper
  + VerifyCallback) documented as a follow-on, not built here.

Phases: **H** = WP20+WP21 (write-loop + locking correctness — do first, they're blockers for
agent-maintained KBs), **I** = WP22+WP23, **J** = WP24. Same ground rules as Part I §0.

---

# PART IV — Coherent knowledge synthesis from the crawl (pass-4b, 2026-07-02)

> User goal: *"synthesize the reasoning into something coherent — ideally build
> facts/opinions/rules/types/schemas from the crawl itself."* The audit found more induction
> machinery than any earlier pass knew — the work is mostly CLOSING LOOPS between existing beans
> and adding ONE new engine (KG rule mining) plus ONE unifying artifact (the knowledge digest).

## IV.0 What the crawl already induces (verified)

| Artifact | Status today | Evidence |
|---|---|---|
| **Facts** | ✅ extraction + structural + cross-doc + OWL closure | Parts I–II |
| **Opinions** | ✅ Beta stamping + promotion (fix double-count via WP3) | Part I |
| **Rules** | 🟡 PARTIAL: process-mined PSL rules ARE real (`ProcessCausalAnalyzer` → `MinedRulePersistenceService` → `<fs>-mined.psl` + chi²/dep lineage sidecar, auto-loaded by the PSL rules loader — triggered by process discovery, not crawl); OWL-derived PSL rules live in cascade; `TableDecisionCompiler` compiles decision tables → PslProgram (live via FolNodeExecutor / process-engine steps / BusinessRulesTool); weights learned per cascade. MISSING: general KG rule-structure learning; OWL rule provenance; rule↔vocabulary coherence | MiningProcessDiscoveryService:226; TraceHumanizer maps `mined-<uuid>` |
| **Types/Schema** | 🟡 PARTIAL: `autoProvisionStructuralOntology` derives + binds a structural ontology on crawl (`deriveOntology=true`, UnifiedCrawlGraphServiceImpl:2248); `OntologyTypeInductionService.enrichAfterOwl` adds observed types/aliases + rebinds (the 809b6624a schema-enrichment inference); LLM 4-step `OntologyDerivationService.derive()` produces per-type confidence (0.55 + support×0.4) but returns an UNSAVED draft (manual commit only); `DomainTaxonomyDiscoveryService` (Louvain+LLM 3-level taxonomy) saves to its own table and feeds NOTHING; TypeRegistry/TypeHierarchy built only at MEBN query time, never crawl-materialized | agent audit, file:line in output |
| **Coherence** | ❌ nothing unifies or cross-validates the artifacts | Gaps 4/5/7 below |

Eight gaps: (G1) no KG rule-structure learner; (G2) LLM ontology draft not auto-committed;
(G3) taxonomy orphaned from ontology; (G4) no rule↔graph-vocabulary coherence check; (G5) unknown
types tagged but never fed back to induction; (G6) OWL-derived rules carry no provenance;
(G7) `DomainObjectRegistry`/`GroundedElement<T>` design-only; (G8) TypeRegistry not materialized at
crawl time.

### WP25 — Close the induction loops (bridges between existing beans; G2/G3/G5/G6/G8)

**25a. Auto-commit LLM ontology derivation**: extend the crawl `deriveOntology` path with mode
`kbOntologyDerivationMode ∈ {STRUCTURAL (today), LLM, HYBRID}` — LLM/HYBRID call
`OntologyDerivationService.derive()` and, above `kbOntologyAutoCommitMinConfidence` (default 0.6
mean type-confidence), persist via `processEngineService.createOntology()` + bind (the exact flow
`autoProvisionStructuralOntology` already does); below threshold → save as DRAFT status for review.
Per-type confidence lands in schema metadata (`typeInductionSource` already exists).
**25b. Taxonomy → ontology bridge**: after `DomainTaxonomyDiscoveryService` produces a taxonomy,
pass its leaf types (+ level-2 groups as `superTypeName` candidates) into the derivation candidates
— both are Spring beans in the same module; one bridging call. Communities thus become type
candidates (topology → schema).
**25c. Conformance → induction feedback**: post-conformance hook — when unknown-type count for a
fact sheet exceeds `kbTypeInductionTriggerThreshold` (default 5 distinct), invoke
`OntologyTypeInductionService.enrichAfterOwl` (the loop the conformance tagger currently leaves
open). Audit event per auto-added type.
**25d. OWL rule provenance**: write a lineage sidecar next to OWL-derived rules reusing the
`MinedRuleLineage` shape — {ontologyId, ontologyVersion, axiom (subClassOf/domain/range), weight,
crawlRunId}. Surfaced in the FOL-rules browser tooltip.
**25e. Crawl-materialized TypeRegistry**: hydration substage (post ontology-bind) builds
`OntologySchemaTypeRegistry.toRegistry(schema)` + `TypeHierarchy.fromGraph` and persists the
snapshot via the existing `TypeRegistryIO` (`data/graph/reasoning/<fs>/type-registry.json`);
MEBN/Bayesian query paths load the snapshot instead of rebuilding per query (also fixes G8's
staleness — snapshot re-materializes each crawl).

### WP26 — KG rule mining (the new engine; G1/G4) — rules become first-class induced artifacts

**26a. AMIE-lite path miner** (lib `learning/rulemining/`, infra-free): over the per-relation CSR
(exists) mine two shapes with support counts:
- chain: `relA(X,Y) ∧ relB(Y,Z) → relC(X,Z)` — count paths and closures; PCA-confidence
  `conf = closures / pathsWithSomeRelC(X)`;
- implication: `relA(X,Y) → relB(X,Y)` — same-pair co-occurrence.
Thresholds: `kbRuleMiningMinSupport` (default 10), `kbRuleMiningMinConfidence` (0.4), max rules
per crawl (`kbRuleMiningMaxRules`, 50). **Community-scoped counting** (Part II WP16a assignments)
bounds cost: mine within communities, promote rules that hold in ≥2 communities (cross-community
generality = the analogue of cross-source corroboration).
**26b. Rule lifecycle with Opinions** (symmetry with facts): each mined rule gets
`Opinion.fromBetaEvidence(pos = closures, neg = paths − closures, a = 0.5, W = kbRuleEvidencePrior)`
— rules carry calibrated confidence exactly like facts. Lifecycle: CANDIDATE (mined) → WEIGHTED
(`PslWeightLearningService.learnAndApply` refines against cascade consensus) → RETAINED (weight ≥
floor after `kbRuleProbationCascades`, default 5) or EXPIRED (dropped from the active file, kept in
lineage). Persist via the existing `MinedRulePersistenceService` pattern:
`<fs>-kg-mined.psl` + lineage sidecar {support, pcaConfidence, opinion, exampleTriples[3],
crawlRunId} — auto-loaded by the existing `*.psl` loader, so mined rules enter the cascade with
ZERO new plumbing.
**26c. Vocabulary coherence check (G4)**: at each cascade's program build, validate every loaded
rule's predicates against the FactStore's predicate set; dangling rules are excluded from the
program + reported (`RegroundResult.danglingRules`, digest §WP27) instead of silently grounding to
nothing. Run the same check for `*.psl` project files.
Trigger: hydration substage after ENRICHMENT, gated `kbRuleMiningEnabled` (default **false**).
Tests: synthetic graph with a planted `worksAt∧locatedIn→basedIn` regularity → rule mined with
correct PCA-confidence; probation expiry; dangling-rule exclusion; community-generality promotion.

### WP27 — The Knowledge Digest (the "coherent" artifact)

One durable, versioned, per-fact-sheet artifact that IS the synthesis — regenerated each epoch,
diffable across crawls, readable by humans AND agents:

New hydration substage STAGE_DIGEST (last, after HEALTH) builds `KnowledgeDigest`:
```
{ factSheetId, epoch, crawlRunId, generatedAt,
  facts:     { bandCounts, topByBand: {ESTABLISHED:[{atom, humanized, E, u, sources[]}...], ...},
               promotedThisEpoch[], retractedThisEpoch[] },
  rules:     { active: [{rule, humanized, weight, source: AUTO|FILE|MINED|KG_MINED|OWL|TABLE,
               opinion?, lineageRef}], expiredThisEpoch[], dangling[] },
  schema:    { ontologyId/version, types: [{name, inductionSource, confidence, conformanceRate}],
               unknownTypesPending[], taxonomyRef },
  structure: { communities: [{id, size, summary, topMembers[5]}], aliasClusters: count+top,
               health: GraphHealthSnapshot ref },
  conflicts: { open: [{atoms, conflictMass, severity}], resolvedThisEpoch[] },
  learning:  { cascadesCompleted, weightVersion, meanWeightDelta, mebn: {registered, strengthsRef} },
  coherence: { ruleVocabOk, schemaCoverage, staleFactsExcluded, truncationFlags[] } }
```
- Persist `data/graph/digest/<fs>-v<epoch>.json` (keep latest K = `kbDigestHistoryK`, 20) +
  a rendered **markdown** view via WP24's `LlmReasoningView`/`VerbalScaleMapper` (band words,
  humanized atoms via `TraceHumanizer`).
- Surfaces: `GET /api/kb-grounding/{fs}/digest[?epoch=]` + `GET /digest/diff?from=&to=` (what
  changed: facts promoted/retracted, rules added/expired, types added, conflicts opened/closed);
  MCP tool `ask_graph_digest` (agents ground themselves in ONE call — the compact markdown,
  capped `kbDigestMaxChars` 8000); GraphsHub "Digest" tab = optional follow-on.
- The `coherence` section is the cross-validation the artifacts never had: rule↔vocab (WP26c),
  schema↔observed types (conformance rate + pending unknowns), stale exclusions, grounding
  truncations — each entry actionable ("run classify", "review 3 dangling rules").
Tests: digest builds on the synthetic fixture with every section populated; diff detects a planted
promotion + rule expiry; markdown under the char cap; digest regeneration idempotent at same epoch.

### WP28 — Grounded domain objects (G7, small)

Implement the design's `GroundedElement<T>` wiring for the ONE generator already computing it:
`ProcessTreeToSuggestion.convertGrounded` persists its per-step `VerifyResult` + calibrated
confidence alongside the `ProcessSuggestion` (repository field addition) instead of discarding the
evidence chain; digest `learning` section links them. Full `DomainObjectRegistry` stays deferred.

Phases: **K** = WP25 (bridges, days) → **L** = WP26 + WP27 (+WP28 rider). WP26 profits from Part II
WP16a (persisted communities) but can mine globally without it; WP27 needs WP24's verbalizer for
the markdown view (JSON view has no dependency).

---

# PART V — Enforcement, type-level reasoning, and CANONICAL information (pass-5, 2026-07-02)

> User's driving concern: **reasoning over business processes, and electing what should be
> CANONICAL information, backed by a real reasoning trace.** The unifying idea of this part:
> *"canonical" is not a flag — it is the outcome of a well-defined ELECTION over competing
> candidates for a functional slot, and the election itself is the trace.* Business processes are
> the hardest instance (competing mined variants, step orderings, role bindings), so they get
> their own work package built on the general mechanism.
>
> Existing substrate verified for this part: `ConformanceChecker` (fitness = fraction of observed
> directly-follows behaviour the model permits, precision, simplicity, F1 harmonic mean —
> ConformanceChecker:36-75, ConformanceResult:23-43, surfaced at MiningDiscoveryController:150);
> `ProcessTreeToSuggestion.convertGrounded` (per-step KB verify, REFUTED→0.0, geometric mean;
> fallback `max(0.1, fitness×precision)` — :139-219); file-backed `ProcessSuggestionStore`
> (@Component, :39); `RoleBindingExtractor`; `ProcessBpmnExporter` (BPMN 2.0 + KB evidence);
> `ProcessCausalAnalyzer` → mined rules with lineage; PinGuard + `KbCorrectionService` (Part III);
> the three resolution worlds from pass 1 (TMS retraction / maintenance scorecards+LLM judge /
> human corrections) that this part finally unifies.

### WP29 — Rule ENFORCEMENT policy layer

Today "enforcement" is scattered and mostly observational: STRICT SchemaEnforcementMode filters
labels at extraction; conformance tagging is LENIENT by design; OWL cax-dw rules are hard but only
*reported*; GraphRuleHook fires actions on changesets; nothing enforces at write time (the A-3
write-hook was explicitly deferred).

**29a. EnforcementMode + policy resolution**: `enum EnforcementMode {OBSERVE, FLAG, QUARANTINE,
BLOCK}` with per-rule-class defaults in KbConfig (`kbEnforcementDefaults`: ontology hard axioms =
FLAG, identity/functional constraints = QUARANTINE, mined rules = OBSERVE, `*.psl` = OBSERVE) and
per-ontology / per-rule overrides (OntologySchema validation rules already carry per-type config —
reuse that seam).
**29b. Write-path hook (finish deferred A-3)**: invoke the existing `GraphConformanceChecker` SPI
from the `EventPublishingKnowledgeGraphService` decorator (the one interception point all writes
cross; keeps the 3-impl rule intact) — OBSERVE by default and async (no write-latency cost);
QUARANTINE = persist + stale-mark + `enforcement.quarantined` tag (quarantined facts are excluded
from projection via Part III WP22's stale filter — the two compose); BLOCK = reject, allowed only
for an explicit config allowlist (e.g., a second canonical value for an identifier-backed
functional slot).
**29c. Cascade-path enforcement**: Part II WP8's `hardViolations()` results are mapped through the
policy to actions instead of just being reported.
**29d. Violations as first-class records**: `Violation {ruleRef+lineage, atoms, entities, severity,
action, remediation, epoch}` — persisted to the audit log, surfaced in the digest coherence
section + `GET /api/kb-grounding/{fs}/violations`, and each violation writes **negative Beta
evidence** on the violating fact (WP3) so enforcement feeds the confidence currency.
**29e. GraphRuleHook integration**: enforcement actions (flag/quarantine) become available reactive
rule actions — reuse the existing engine, no new one.
Tests: one write under each mode; quarantined edge absent from the next cascade's FactStore;
violation → neg evidence → band drop; BLOCK only fires from the allowlist.

### WP30 — Type-LEVEL reasoning

**30a. Subtype-aware rule scoping**: `FolRule.entityTypeScope` and the PSL propagation templates
(WP15) match via `TypeHierarchy.isA` instead of exact string equality (the documented gap — a rule
scoped to `Person` fires on `Employee`). Requires the crawl-materialized TypeRegistry (WP25e).
Config `kbSubtypeRuleScoping` (default true when a hierarchy is bound).
**30b. Wire the dead `TypeConstraintFolRuleCompiler`**: compile `TypeConstraint.Relation /
Cardinality / AttributeRequired` into cascade rules — Cardinality(≤1) routes to WP8's functional
`ArithmeticRule`; AttributeRequired becomes a soft rule whose violation FLAGs via WP29. This makes
the lib's type system (TypeAttributeSchema + inheritance merge) load-bearing instead of dark.
**30c. Inheritance as prior**: type-level default attributes (TypeAttributeSchema) materialize as
instance facts with a deliberately weak Opinion (high u, E = the default's confidence — a prior,
not an assertion); instance evidence overrides through normal Beta accumulation. Gated
`kbTypeDefaultsAsPriors` (default false).
**30d. Type-level propositions with instance-backed traces**: facts ABOUT types —
`requires_approval(PURCHASE_ORDER)` — computed by aggregation over instances:
`Opinion.fromBetaEvidence(pos = #satisfying instances, neg = #counterexamples, a, W)`, asserted as
an atom on the type entity, verifiable via `ask_graph_verify` like any fact, with the trace = the
instance sample + the explicit counterexample list. Runs as a hydration substep over the slot
registry's predicates (WP31a), bounded by types×predicates. This is how "all POs require approval"
becomes a checkable, evidence-backed claim rather than prose.
**30e. DECISION GATE**: `EntityTypeDefinition.superTypeName` (nullable String) — without it the
ontology bridge stays a 2-level hierarchy (long-standing pending decision). Implement 30a fully
only behind this field landing; flag to the user, do not decide unilaterally.
**30f.** MEBN subsumption grounding reads the WP25e TypeRegistry snapshot so type reasoning reacts
to re-crawls.

### WP31 — Canonical slots: contradiction RESOLUTION as election with a durable trace

**31a. Slot registry**: per (entityType, predicate) classification `FUNCTIONAL | MULTI_VALUED |
TEMPORAL_FUNCTIONAL`, stored in the type-registry snapshot. Sources, in precedence order: ontology
cardinality (MANY_TO_ONE / maxCardinality 1 ⇒ FUNCTIONAL); **mined functionality** (WP26 extension:
fraction of subjects with exactly one object ≥ `kbFunctionalityMiningThreshold` (0.95) ⇒ candidate
FUNCTIONAL, carrying its own mined Opinion); KbConfig per-predicate overrides. Unclassified ⇒
MULTI_VALUED (today's behavior, unchanged).
**31b. The election** (cascade substep after the contradiction stage; candidates = the detector's
output pairs ∪ a slot scan; gated `kbCanonicalElectionEnabled`, default false): for each functional
slot with >1 live candidate, apply the LEXICOGRAPHIC policy — each stage is recorded, which is what
makes the outcome a trace and not a score:
1. **Human pin wins** (PinGuard) — unconditional until reverted.
2. **Hard-constraint violators excluded** (WP8/WP29 output).
3. **TEMPORAL_FUNCTIONAL ⇒ latest-valid wins** — per the resolved valid-time decision ("truth at t
   = the latest fact valid at t"); superseded candidates get `validUntil` stamped, NOT deleted.
4. **Opinion expectation** (WP13's carried opinions) with corroboration count as tie-break, and an
   optional LLM judge ONLY when the top two are within ε (`kbElectionLlmTieBreak`, default false).
Winner: `canonical.<pred>` metadata on the entity + CORROBORATION evidence. Losers: superseded mark
+ **negative evidence** (WP3) + `validUntil`. Nothing is destroyed (resumability mandate).
**31c. `CanonicalElectionTrace`** — the artifact: `{slot, candidates: [{value, opinion(b,d,u),
band, sources, validFrom, violations}], policy, stagesApplied: [PIN|HARD|TEMPORAL|OPINION|LLM],
winner, resolvedBy: AUTO|LLM|HUMAN, epoch}`, nesting each candidate's OpinionTree (WP5). Persisted
through the audit log — and **the same shape is adopted by all three existing resolution worlds**
(maintenance scorecard resolution, TMS retraction, human corrections), unifying resolution
provenance into one grammar. The four trace types of the whole plan — OpinionTree (evidence
composition), DerivationTree (rule derivations), CanonicalElectionTrace (resolution), lineage
sidecars (induction) — cross-reference by id and all render through WP24's verbalizer.
**31d. Surfaces**: `GET /api/kb-grounding/{fs}/canonical?entity=&predicate=` → value + trace;
`ask_graph_canonical` MCP tool; `verify()` on a functional slot annotates SUPPORTED with
canonical/superseded status; digest gains a canonical-slots section (contested count, recent
elections, slots pending human review).
Coordination: consumes the in-flight probabilistic contradiction detector's OUTPUT (Contradiction
records) — does not touch the detector.
Tests: 3-candidate slot (pinned / fresher / higher-opinion) resolves by stage order with the trace
recording exactly which stage decided; temporal slot supersedes on re-crawl; losers' bands drop via
neg evidence; MULTI_VALUED slots never elect.

### WP32 — Business processes: canonical processes with reasoning traces

**32a. Processes become reasoning subjects**: a hydration bridge projects process-discovery output
into typed facts on PROCESS/STEP/ROLE entities — `follows(stepA, stepB)`, `partOf(step, process)`,
`performs(role, step)`, `requires(step, artifact)` — each with
`Opinion.fromBetaEvidence(pos = directly-follows arc support, neg = observed violations, a, W)` and
lineage refs to the mining run; the process-level opinion comes from `ConformanceResult.f1` (:40).
After this, verify/explain/canonical all work on processes with zero new machinery.
**32b. Canonical process election**: competing mined variants of the same process (cluster
PROCESS entities by label/embedding similarity + shared-step overlap — the WP14 union-find applied
at process level) reconcile per **divergence point**: `successor(step)` within a process is a
FUNCTIONAL slot, so each divergence is a WP31 election whose candidates are the variant orderings
with their arc-support opinions. Output: a canonical `ProcessSuggestion` + retained variants with
support + one election trace per divergence, persisted via `ProcessSuggestionStore` (additive
fields: `canonicalOf`, `electionTraceRef`) alongside WP28's grounded evidence.
**32c. Canonical process → enforcement + drift**: compile the canonical process into conformance
rules (`follows`/`requires` as PSL + type constraints) enforced per WP29 on NEW event data
(deviation ⇒ FLAG/QUARANTINE per policy), and compute rolling drift = `ConformanceChecker.fitness`
of incoming event windows against the canonical model (the checker exists — point it at windows).
Digest gains a process section: {canonical processes, conformance drift, open deviations}.
**32d. BPMN as the deliverable**: `ProcessBpmnExporter` already embeds KB evidence — add the
per-divergence election annotations (winner + trace ref) so the exported BPMN literally is
"canonical information backed by a real reasoning trace."
**32e. Ask surface**: `ask_graph_canonical` on PROCESS entities; chat "what is the canonical
onboarding process?" → canonical suggestion + BPMN link + verbalized trace summary (WP24).
Tests: two synthetic variant logs (A→B→C vs A→C→B) ⇒ divergence detected at successor(A), election
by arc support with trace; deviating event window ⇒ FLAG + drift drop; `requires(step,approval)`
verifiable with instance-backed counterexamples (WP30d).

Phases: **M** = WP29 + WP30 (enforcement + type machinery feed elections) → **N** = WP31 →
**O** = WP32. Dependencies: WP31 requires WP4 (operators) and profits from WP8/WP13/WP26; WP32
requires WP31 + the existing process-discovery stack; WP32c requires WP29. The 30e superType field
is a user decision gate.

---

# PART VI — LLM extraction: building USEFUL graphs with actual signal (pass-6, 2026-07-02)

> Everything in Parts I–V consumes what extraction produces. Pass 6 audited the extraction layer
> for SIGNAL: discriminative entities, typed directional relations, heterogeneous calibrated
> confidence, and source-text provenance. Verdict: the plumbing is unusually good (rich
> `ExtractionLogRecord`, `_extractionModel` provenance on every node/edge, model health-benching +
> a correctness EWMA that genuinely reorders model selection, live ontology-guided prompting,
> entity descriptions feeding embeddings) — but the CONTRACT leaks signal at nearly every field,
> one path has a P0 parse mismatch, vocabulary proliferates unanchored, and every durable quality
> signal dies unread.

## VI.0 Verified signal-loss defects

| # | Defect | Evidence | Fix |
|---|---|---|---|
| E1 | **P0 BUG — prompt/DTO mismatch on the LlmKnowledgeGraphBuilder (ontology-guided P1) path**: prompt instructs `name`/`type`/`relations` (GraphExtractionValidator.getExtractionPromptInstructions, :237-278) but the parse DTO reads `title`/`@JsonProperty("label")`/`relationships` with NO @JsonAlias (ExtractedGraphDTO.java:41-60, verified by direct read) → compliant responses parse to null-titled entities or throw → `parseResponse` swallows `JsonProcessingException` and returns an EMPTY graph (LlmKnowledgeGraphBuilder:437-462) | verified | WP33a |
| E2 | The PRIMARY crawl prompt (`MatrixGraphConstructor.createExtractionPrompt`, :621-671) never requests `confidence` (or aliases/properties) → every entity lands at DEFAULT 0.7, every relation 0.5 — zero epistemic signal; the good rubric (0.9 explicit … <0.5 speculative) exists only on the broken Contract-A path | agent + schema :93-105 | WP33b |
| E3 | Record constructors inject defaults BEFORE the stamper → `ExtractionConfidenceStamper` cannot distinguish "model said 0.7" from "model was silent"; no confidence-variance check anywhere | GraphExtractionSchema defaults; stamper :120 | WP33c |
| E4 | `ExtractedEntity.properties` (schema :96) is **silently dropped** — no builder persists it; LLM-extracted attributes (titles, amounts, dates) discarded at construction | verified by both builders | WP33d |
| E5 | No sentence-level provenance: no evidence-quote field in either contract; `_extractionLogId` DECLARED (GraphProvenanceKeys:46) but **never set** on edges — the rich log (full prompt/response/chunk `inputText`, `~/.kompile/extraction-logs.jsonl`, 50k cap) is unreachable from a trace | verified | WP33e |
| E6 | Predicate proliferation unanchored: only normalization is formatting (`semanticRelationLabel` — punctuation→`_` + uppercase; `worksAt`→`WORKSAT` ≠ `works_at`); no synonym folding/registry/embedding clustering; no ontology ⇒ no run-to-run anchor; stats endpoints count structural EdgeType enums only — relationType cardinality invisible | GraphPersistenceHelper:437-447; FactSheetGraphServiceImpl:270 | WP34 |
| E7 | Relation semantics thin: no relation attributes (edge metadataJson = provenance only), no direction from the LLM (bidirectional unused), **negation/hedging extracted as positive facts** ("did NOT approve" → APPROVED), no n-ary/reified events | agent | WP33b/f |
| E8 | Chunks are COLD (no doc title, no prior-chunk entities) → cross-chunk coreference impossible in-prompt; consolidation is post-hoc Levenshtein-0.85 only; without `retainResultGraph` duplicate entities per chunk persist | agent | WP33g |
| E9 | Zero-yield chunks: AIMD budget shrinks but the chunk is marked COMPLETED with 0 entities — no re-prompt, no retry queue; builder-path parse failures silently yield empty graphs (no retry, unlike the inline path's validation-retry) | orchestrator ~:1001; builder :457 | WP35b |
| E10 | Single-shot extraction: no gleaning pass, no claims/SPO pass | agent | WP36e |
| E11 | Feedback loops open: the correctness EWMA is the ONLY closed loop and is in-memory (resets on restart); merge rate / conformance violations / contradiction rate / zero-yield lists all die unread; the 4 extraction evaluators in kompile-evaluation are orphans; re-running extraction has no chunk-level dedup guard (duplicates calls + edges) | agent | WP35 |
| E12 | Structural swamp: structural edges default weight 1.0 ≥ semantic LLM edges; PPR has NO damping/exclusion of DOCUMENT/SOURCE/SNIPPET hubs (type filter applied only post-ranking); structural `contains` atoms flood the FactStore; no semantic-fraction metric exists | MatrixGraphConstructor:156,230,538; MatrixGraphRagService:426,473 | WP36 |

### WP33 — Extraction contract fix pack (E1-E5, E7, E8)

**33a. Fix the P0 mismatch** (do FIRST, independently shippable): align `ExtractedGraphDTO` with
the instruction contract via `@JsonAlias({"name"})` on title, `@JsonAlias({"type"})` on label,
`@JsonAlias({"relations"})` on relationships — or switch the builder's parse to
`GraphExtractionValidator.fromJson()` (which already matches). Regression test: feed a
contract-A-compliant JSON through the builder → non-null titles/labels. Check git history for when
this regressed and whether prod graphs on this path are silently empty.
**33b. ONE unified prompt contract** (shared builder used by both paths): entity
`{id, name, type, aliases[], description, confidence, properties{}}`, relation
`{source, target, type, direction, description, confidence, properties{}, evidenceQuote,
occurredAt}` + the existing confidence RUBRIC + polarity/modality rule: *"if negated (not, never,
denied) set properties.polarity=negative; if hedged (reportedly, might) set
properties.modality=hedge and confidence < 0.5"*. Ontology injection stays as-is (it works).
**33c. Tri-state confidence**: keep raw `null` through to the stamper (drop the record-constructor
defaulting; stamper applies the default): model-provided → `BasisType.LLM_EXTRACTION` with the
value; absent → new `BasisType.LLM_DEFAULT` with **higher W** (weaker evidence). Add a per-batch
confidence-variance stat: if stddev < 0.02 across a batch, log + flag in the extraction-quality
digest section (model ignoring the rubric).
**33d. Persist `entity.properties`** into node metadata (namespaced `attr.<key>`), and
`relation.properties` into edge metadata — the fields already exist end to end except the two
`putAll` calls.
**33e. Wire provenance to text**: set `_extractionLogId` on every emitted node/edge (the missing
assignment); store `evidenceQuote` (33b) as `_evidenceQuote` edge metadata; `DerivationTree`/
ReasoningTrail leaf rendering (WP24) shows the quote, falling back to
`ExtractionLogStore.byId(logId).inputText` excerpt. This is what makes traces bottom out in source
sentences.
**33f. Direction**: map the new `direction` field onto `bidirectional`; default directed.
**33g. Warm chunks**: prepend a context header per chunk — document title + top-K prior-chunk
entities (`"Known entities so far: …"`) so pronouns/partial names resolve in-prompt; enable
`retainResultGraph`-style in-flight dedup by (type, title-fold) during a document's extraction.
Config: `kbExtractionContractV2Enabled` (default false until validated on FP&A), per-item flags.
Tests: contract round-trip; negation fixture yields polarity=negative + low confidence; properties
land namespaced; quote lands and renders in an explain trace.

### WP34 — Vocabulary anchoring (E6) — prerequisite for WP26 mining + WP31 slots

**34a. Formatting fixes**: camelCase→SNAKE split in `semanticRelationLabel` before uppercasing;
SCREAMING_SNAKE enforcement in `GraphConstants.normalizeEntityType` (case-fold entity types).
**34b. Relation registry** (per fact sheet, persisted with the type-registry snapshot): canonical
predicates + surface-form aliases + counts. New extracted relation folds by: exact → case/snake
fold → **embedding similarity ≥ `kbRelationFoldThreshold` (0.85) against canonical predicate
embeddings** (embed the predicate name + top-3 example triples) → else registers as new, bounded by
`kbMaxNewPredicatesPerCrawl` (default 25; overflow → `RELATED_TO` + logged). When an ontology is
bound: fold INTO `allowedRelationshipTypes` first (the anchor), register extras as
pending-induction (feeds WP25c).
**34c. Observability**: `distinctSemanticRelationTypes`, `topRelationTypes`, and
`genericEdgeFraction` (via the existing `semanticRelationType()==null` test) added to
`getStats()` + the digest schema section.
**34d.** The registry is declared the predicate source of truth for `GraphToFactStoreProjector`,
WP26 rule mining, and WP31's slot registry — mining over unanchored predicates is wasted compute
(state this dependency in WP26).

### WP35 — Close the quality loops (E9, E11)

**35a.** Persist the correctness EWMA + bench state across restarts (alongside the existing
cli-llm-config).
**35b. Low-yield re-extraction queue**: post-job scan of `ExtractionLogStore.findByJobId` for
`entitiesCount==0 && inputText.length() > kbMinSignificantChars` → one retry per chunk with the
next-tier model (respects paid caps); builder-path parse failures join the same queue (fix the
silent-empty catch). Runs as a resumable crawl substep.
**35c. Per-model quality ledger**: aggregate from what's already recorded — yield averages
(ExtractionLogStore), merge rate, conformance-violation rate, contradiction rate (bucketed by
`_extractionModel` provenance) → feed `recordModelCorrectness` and persist the ledger
(`data/graph/extraction-quality/<model>.json`).
**35d.** Wire `EntityPresenceEvaluator`/`RelationshipPresenceEvaluator` as an optional post-crawl
smoke test against a per-project fixture (`data/eval/extraction-fixture.json`); disabled-by-default
per the module's own pattern.
**35e. Re-extraction dedup**: guard extraction-time persistence by (sourceChunkId, entity
title+type) / the WP22 edge upsert key so step re-runs reinforce (evidence) instead of duplicate.
**35f.** Digest gains an extraction-quality section: per-model yield, generic fraction, confidence
variance, zero-yield count, retry outcomes.

### WP36 — Signal balance in the built graph (E10, E12)

**36a.** `kbStructuralEdgeWeight` (default 0.3) applied to CONTAINS/HIERARCHICAL/structural edges
at creation (semantic edges keep confidence-derived weight) — structural edges stop outweighing
semantic ones in PPR/PathRAG.
**36b.** PPR structural damping: teleport-set exclusion (or `kbPprStructuralDamping` 0.1×) for
DOCUMENT/SOURCE/SNIPPET NodeLevels in `personalizedPageRank` callers; entityType filtering moves
BEFORE mass propagation where a type filter is requested.
**36c. Projector predicate diet**: `kbProjectStructuralPredicates` (default **false**) — `contains`
/ structural-enum predicates stay OUT of the FactStore (they're topology, not propositions);
big atom-count reduction compounds with Part II's speed work. Structural facts remain available
opt-in for rules that need them.
**36d.** Per-edgeType Micrometer counters + the semantic-fraction metric (with 34c).
**36e. Gleaning pass** (`kbGleaningEnabled`, default false): one follow-up per chunk — *"Which
named entities or relationships in this text are NOT in this list: {extracted}?"* — merged through
the same validator; claims/SPO pass documented as a follow-on, not built.

Phases: **P** = WP33 (33a immediately — it's a bug fix) → **Q** = WP34 + WP36 → **R** = WP35.
Dependency note added to WP26/WP31: they consume WP34's registry. Positives to PRESERVE while
refactoring: ontology-guided prompting, the model health/EWMA machinery, ExtractionLogRecord,
`_extractionModel` provenance, description-fed embeddings, AIMD batch sizing.

---

# PART VII — Working backwards from real questions: analytical QA (pass-7, 2026-07-02)

> Method: trace two archetypal user questions through the system as designed (Parts I–VI) and mark
> every conceptual hole. Both questions are ANALYTIC, not retrieval: the answer is COMPUTED from
> many facts, not found in one.
>
> Verified negatives grounding this part: `groupBy` = zero hits in the reasoning lib (no
> aggregation operators exist anywhere — ConjunctiveQueryEngine does conjunctive patterns +
> min-confidence only; ArithmeticRule sums are CONSTRAINTS, not query aggregates); "fiscal" appears
> only in test-fixture text (no fiscal-calendar config); `GraphRagQuery` fields (:29-88 — query,
> searchType, k, hopDepth, vectorWeight, factSheetId, includeCommunities, entityType) contain NO
> temporal and NO aggregation fields. Verified positive: `EmailBodyValueExtractor`
> (loader-email-inbox) is a real deterministic value extractor — currency/percent/date/cell-ref/
> key-value regexes ("revenue: 1234") producing `ExtractedValue{type, raw, parsed, context,
> confidence}` — but scoped to email→spreadsheet-cell mapping; its output never becomes graph facts.

## VII.0 Walkthrough 1 — "What were the sales last quarter?"

| Step | Needs | Status |
|---|---|---|
| 1. Recognize the QUESTION TYPE (aggregation: measure=sales, fn=SUM, window=lastQuarter, scope=company) | question typology + plan compiler | **MISSING (A1)** — chat SearchType is a retrieval flavor, not an intent; WP12 ranks entity candidates, can't answer "how much" |
| 2. Resolve "last quarter" → [2026-04-01, 2026-07-01) | relative-time resolver + FISCAL calendar convention | **MISSING (A2)** — valid-time model + occurredAt exist (Parts II/IV stamp them), but nothing resolves relative expressions and no fiscal config exists |
| 3. Find sales FACTS: row-level numbers (Excel), stated figures in emails/decks ("Q3 sales were $4.2M") | a MEASURE model: quantity = {value, unit, currency, measureType, period, scope} | **MISSING (A3)** — WP18b types numeric cells, WP33d keeps LLM properties, EmailBodyValueExtractor parses amounts — three producers, no unifying quantity fact + no unit/currency/period normalization (K/M/bn, $/USD, "Q3-2025"/"July") |
| 4. DEDUP before summing (same figure quoted in 3 docs = one fact) | canonical election per (measure, period, scope) slot | **DESIGNED (WP31)** — but must run BEFORE aggregation; ordering is new |
| 5. AGGREGATE: SUM over deduped members, GROUP BY optional (per region/product) | aggregation algebra over uncertain facts | **MISSING (A4)** — no SUM/COUNT/AVG/GROUP BY/DISTINCT anywhere; and uncertainty must propagate (aggregate opinion from member opinions) |
| 6. RECONCILE stated vs computed ($4.2M stated vs $4.05M computed) | roll-up validation as a derived-level contradiction | **MISSING (A5)** — the contradiction machinery compares stored atoms, never a computed value against a stated one |
| 7. ANSWER with trace | aggregation answer contract + AGGREGATE trace node | **MISSING (A8)** — OpinionTree has ⊕/⊙/consensus, no AGGREGATE node with contributors/excluded |

**Target answer shape** (what the system should return):
```
Sales, 2026 Q2 (fiscal, Apr–Jun): $4.05M computed from 47 line items across 3 sources
  ⚠ a stated figure of $4.2M (board deck, 2026-07-01) differs by 3.7% — both shown
  coverage: 47/47 line items in-window carried amounts; 2 duplicate reports excluded (superseded)
  trace: AGGREGATE[SUM] → 47 contributors (each: value ⊕ sources, validFrom) → canonical
         elections at 2 slots → reconciliation vs stated → Opinion(E=0.81, u=0.12) "probable"
```

## VII.1 Walkthrough 2 — "What types of wines do we carry?"

| Step | Needs | Status |
|---|---|---|
| 1. Recognize ENUMERATION-at-TYPE-level + scope ("we") + currency ("carry" = active NOW) | typology (A1) + lifecycle semantics | **MISSING (A1, A7)** |
| 2. Resolve "types of wine": subtypes of WINE in the type system, OR distinct values of a categorical attribute (varietal)? | TypeRegistry + attribute-domain knowledge to pick the axis | **MISSING (A6)** — taxonomy induction (WP25) covers entity types; nothing profiles ATTRIBUTE value domains |
| 3. Substrate: SKU entities + attributes | WP18a row/key entities + WP33d properties | **DESIGNED** |
| 4. Canonicalize values ("Cab Sauv" = "Cabernet Sauvignon") | value-level alias folding | **MISSING (A6)** — WP34 folds relation/entity TYPES, not attribute values |
| 5. "Carry" = active: exclude discontinued; latest inventory snapshot wins | entity STATUS slot + snapshot semantics | **MISSING (A7)** — WP18c stamps time; nothing derives active/discontinued from snapshot succession |
| 6. Enumerate with COMPLETENESS: distinct + counts + how much of the catalog was classifiable | DISTINCT/GROUP BY (A4) + coverage quantification | **MISSING (A4, A8)** — SparsityMetrics covers absent single facts, not enumeration coverage |

**Target answer shape**:
```
We carry 12 wine types (from 1,847 active SKUs, latest inventory snapshot 2026-06-28):
  Cabernet Sauvignon (312 SKUs; incl. "Cab Sauv", "Cabernet"), Merlot (240), Chardonnay (198), …
  coverage: 94% of active SKUs classified; 108 SKUs lack a varietal (listed); 23 discontinued excluded
  each type: Opinion + example SKUs + source sheets; open-world note: "at least these 12"
```

## VII.2 The conceptual gaps (A1–A8)

A1 question typology/plan compilation · A2 relative-temporal + fiscal resolution · A3 measure/
quantity model + normalizers · A4 aggregation algebra with dedup-first + uncertainty propagation ·
A5 stated-vs-computed reconciliation · A6 attribute-domain profiling + value canonicalization ·
A7 entity lifecycle/status + snapshot semantics · A8 analytic answer contracts + coverage.

### WP37 — Question → plan compiler (A1, A8)

`QuestionIntent ∈ {FACTOID, VERIFY, AGGREGATION, ENUMERATION, COMPARISON, TREND, CAUSAL, PROCESS,
CANONICAL}` + an LLM-compiled `AnalyticPlan {intent, measure?, aggregateFn?, groupBy?, filters[],
timeWindow?, scope, targetType?, attributeAxis?}` (one small LLM call with the type-registry +
measure catalog + slot registry as context; deterministic fallback heuristics). Routing: chat and a
new `ask_graph_ask` tool dispatch by intent — FACTOID/VERIFY → existing retrieval/verify;
CANONICAL → WP31; CAUSAL/PROCESS → existing; AGGREGATION/ENUMERATION/TREND/COMPARISON → the WP39
executor. Per-intent ANSWER CONTRACTS: aggregation `{value, unit, period, scope, method:
computed|stated|reconciled, contributors, excluded, conflicts, coverage, opinion, trace}`;
enumeration `{values: [{canonical, aliases, count, examples, opinion}], coverage, unclassified,
excludedInactive, trace}` — both rendered through the WP24 verbalizer.

### WP38 — Temporal resolution + the measure model (A2, A3)

**38a. `RelativeTimeResolver`** (lib, pure): "last quarter"/"this month"/"YTD"/"last year"/"Q3" →
`TemporalInterval`, anchored on an injected clock + `kbFiscalYearStartMonth` (default 1) +
`kbWeekStart`. Used by WP37 plans and exposed on `GraphRagQuery` (add `timeWindow` — the model has
no temporal field today).
**38b. Quantity facts**: `measure(scopeEntity, measureType, period) = {value, unit, currency}` as
first-class facts (atom form `measure_sales(acme, 2026Q2)` with the quantity in fact metadata; slot
class TEMPORAL_FUNCTIONAL by default). Producers unified behind one `QuantityFactEmitter`:
(1) WP18b typed numeric cells + header→measureType mapping; (2) **promote
`EmailBodyValueExtractor` to a shared app-core text-value extractor** (it already parses
currency/percent/key-value with context — run it on ANY document text, not just email); (3) WP33d
LLM properties. Normalizers: magnitude (K/M/bn), currency symbol→ISO, period parsing
("Q3 2025", "July", "FY25" → intervals) — each normalization recorded in the fact's provenance.
**38c.** Measure catalog per fact sheet (observed measureTypes + units + periods — the digest and
WP37's compiler read it).

### WP39 — Aggregation algebra + roll-up reconciliation (A4, A5)

**39a. Operators**: `SUM/COUNT/AVG/MIN/MAX/DISTINCT` + `GROUP BY` evaluated over the
InferredFact/slot layer (stratified: aggregation reads post-cascade, post-election state — never
inside PSL). **Dedup-first is mandatory**: members pass through WP31 canonical election per
(measure, period, scope) so a figure quoted in three documents contributes ONCE; superseded
members land in `excluded[]` with reasons.
**39b. Uncertainty through aggregates**: aggregate value = Σ/avg of member values; aggregate
Opinion: `u_agg = 1 − Π(1−uᵢ)` capped, `E_agg` from members' expectations weighted by |value|
share (documented approximation); per-member opinions visible in the trace. New `AGGREGATE` node
type in OpinionTree `{fn, groupKey, contributors[], excluded[], coverage}`.
**39c. Roll-up reconciliation (A5)**: when a STATED aggregate fact exists for the same (measure,
period, scope) as a COMPUTED one: within `kbRollupTolerance` (default 5%) → mutual corroboration
(both gain evidence); outside → open a derived-level contradiction whose two candidates are
`stated` (with its source opinion) and `computed` (with its AGGREGATE trace) — resolved by the
WP31 election (policy: computed-from-more-sources generally beats a single stated figure unless
the stated source is pinned/high-trust). The answer shows BOTH when unresolved.
**39d.** Coverage: `contributors / candidates-in-window` + unparsed-value count — every analytic
answer carries it (A8).

### WP40 — Enumeration + catalog semantics (A6, A7)

**40a. Attribute-domain profiler**: per (entityType, attribute): distinct canonical values, counts,
example entities, coverage (fraction of instances with the attribute populated) — computed in the
WP25e type-registry hydration substep, persisted with the snapshot, surfaced in the digest schema
section. This also feeds ontology enrichment (observed value domains → EntityTypeDefinition field
constraints, closing another WP25 loop).
**40b. Value canonicalization**: apply WP34's fold machinery (exact → case/snake → embedding
≥ threshold) to attribute VALUES ("Cab Sauv" → "Cabernet Sauvignon"); alias sets kept on the
domain profile.
**40c. Entity lifecycle/status**: a `status` slot (active/discontinued/superseded) with snapshot
semantics for inventory-style sources: each ingest of the same source stamps a snapshot id +
validFrom; an entity present in snapshot N−1 but absent from snapshot N gets a `status=discontinued`
CANDIDATE (basis STRUCTURAL, resolved by WP31 election; `kbSnapshotAbsenceDiscontinues` default
false — flag on for true inventory feeds). "Do we carry X" = status=active at NOW.
**40d. Enumeration executor**: DISTINCT over the domain profile filtered by status + scope, counts
via GROUP BY, coverage + unclassified list, open-world phrasing ("at least these N") via the
verbalizer when coverage < `kbEnumerationClosedWorldCoverage` (default 0.9).

Phases: **S** = WP37 + WP38 → **T** = WP39 → **U** = WP40 (40a/40b can start with S).
Dependencies: WP39 requires WP31 (dedup) + WP38 (measures, windows); WP40 requires WP34 (fold
machinery) + WP18/WP33d (attributes exist at all); everything profits from Part VI landing first —
**analytic answers over an extraction layer that drops properties and defaults every confidence
would be numerically meaningless.** Sentinel end-to-end tests: the two walkthrough questions,
answered on a synthetic fixture (inventory sheet + emails + a board deck with a deliberately
conflicting stated total), asserting the target answer shapes above including the reconciliation
warning and the coverage lines.

---

# PART VIII — Process instances + process evolution (pass-8, 2026-07-02)

> Target questions: **"What are all the instances of this process?"** and **"How has it changed?"**
>
> Audit verdict (all file:line verified): the two halves of "instances" live in disconnected worlds.
> **Engine side is RICH**: `WorkflowRun` (execution/WorkflowRun.java:40) is first-class —
> processDefinitionId, **processVersion**, status lifecycle (RUNNING/PAUSED_FOR_APPROVAL/
> PAUSED_FOR_HUMAN/COMPLETED/FAILED/CANCELLED), per-step `StepExecution` (:38) with
> startedAt/completedAt, inputs/outputs + SHA-256 hashes, evidenceReliedOn, graphNodeIds; persisted
> `~/.kompile/processes/runs/<id>.json`, restart-safe. `ProcessDefinition` **IS versioned**
> (approveProcess() creates v N+1 and keeps history as `<id>_v<N>.json`,
> ProcessEngineServiceImpl:366; `getProcess(id, version)` :355). The discovered→defined bridge
> exists bidirectionally (accept → DRAFT definition; sourceSuggestionId both ways).
> **Mining side has NO instances**: default case correlation = **weakly-connected components**
> (ConnectedComponentCorrelation:50 — a dense fact sheet collapses to ONE case; `case-N` ordinals
> non-deterministic across runs); NO correlation-key extraction (PO#/invoice#/thread-ids unused —
> `IN_THREAD`/`REPLIED_TO` edges exist and are ignored); `AnchorTypeCorrelation` (:56, entity-type
> anchors + BFS≤3) is the right object-centric idea but opt-in and key-blind; **Trace/Event objects
> are DISCARDED after mining** (only the aggregated ProcessSuggestion persists, under a fresh
> `mined-<UUID>` each run — suggestions accumulate with no versioning/supersede/diff,
> ProcessSuggestionStore:63); no time-windowed mining (no date filter anywhere on the log builder);
> conformance is footprint-aggregate only (no per-trace replay); `Event.attributes` always empty,
> no actor on events; `RoleBindingExtractor` falls back to keyword matching and never uses
> SENT_BY/SENT_TO edges (:151); activity labels have no cross-run canonicalization; engine runs
> compute NO cycle time despite having per-step timestamps (`WorkflowRun.metrics` only ever gets
> `ontologyViolations_<step>`, ProcessEngineServiceImpl:1147); missing REST: list-runs-by-definition,
> list-definition-versions, any diff. Processes are graph-DISCONNECTED (no PROCESS/CASE node types;
> graphNodeIds are opaque strings).

## VIII.0 Target answer shapes

**"What are all the instances of the Invoice Approval process?"**
```
14 instances (9 completed, 2 in-flight, 1 failed, 2 uncertain-correlation):
  ENGINE runs (5): run-… v3, COMPLETED 2026-06-12, cycle 3.2d (approve step 1.9d), 0 deviations
  MINED cases (9): case inv-4471 — 6 events across 4 docs (2 emails, PO.xlsx row, approval.pdf),
    2026-05-03 → 05-11, actors {J.Smith → Finance}, conformance 0.92, membership Opinion(E=.88)
    ⚠ case inv-4488: SKIPPED 'Manager Approval' (deviation → flagged); …
  correlation evidence per case: shared invoice# (IDENTIFIER), email thread, attachment chain
  coverage: 96% of in-window candidate events assigned; 7 events unassigned (listed)
```

**"How has it changed?"**
```
Invoice Approval — 3 change points since 2026-01:
  v2→v3 (DEFINED, approved 2026-04-02): +step 'Compliance Review' after 'Manager Approval'
  MINED drift (Q1→Q2 cohorts, 41 vs 38 instances): arc Manager→Payment support 0.71→0.22 (χ² p<.01),
    new arc Manager→Compliance 0.68; median cycle 2.1d→3.4d (+62%, driven by Compliance step);
    variant 'expedited' share 18%→31%
  CANONICAL elections: successor(ManagerApproval) winner changed 2026-04-05 (election trace ref)
  conformance trend: 0.94 → 0.88 (deviations concentrated in cases missing Compliance)
```

### WP41 — Case correlation + first-class ProcessInstance

**41a. Correlation-rule stack** (replaces blind connected components; each link carries an Opinion
so INSTANCE MEMBERSHIP IS UNCERTAIN, resolved WP14-style by confidence-thresholded union-find):
1. **Identifier keys** (strongest): shared IdentifierScheme values (invoice#, PO#, GTIN) via the
   existing IDENTIFIER nodes / `observedgtins`-style metadata — the machinery from Part II exists,
   it was just never consulted here;
2. **Thread chains**: `IN_THREAD` / `REPLIED_TO` (EmailGraphExtractor already emits them);
3. **Artifact chains**: `HAS_ATTACHMENT` + attachment-name alias buckets + `REFERENCES_DOCUMENT`
   (the cross-document pattern engine's edges become correlation evidence);
4. **Anchor entities** (promote `AnchorTypeCorrelation` from opt-in fallback into the stack, keyed
   per canonical process via its ontology binding);
5. **Weak evidence**: temporal proximity window + shared actors (low-weight, tie-break only).
Stable case keys: `case:<anchorEntityId|identifierValue>` — never ordinal. Config:
`kbCaseCorrelationRules` (ordered enable list), `kbCaseMembershipMinConfidence`.
**41b. Persist instances as graph citizens**: PROCESS_INSTANCE entities (+ PROCESS/PROCESS_STEP for
canonical models — closes the WP32a bridge from the other side): `instanceOf(instance,
process)`, `hasEvent(instance, eventNode)` edges carrying membership opinions, attributes
{startedAt, endedAt (min/max event time), status, actors, artifacts, correlationKeys}. The
EventLog's traces stop being transient — `ProcessInstanceStore` (mirror of ProcessSuggestionStore)
+ graph materialization. Instances get verify/explain/canonical for free.
**41c. Time-windowed extraction**: `EventLogExtractor.extractForFactSheet(fsId, TemporalInterval)`
filtering on occurredAt (WP38a resolver supplies windows); events missing occurredAt fall back to
`_extractedAt` with a trace note (CreationTimeView semantics).
**41d. Label + role hygiene**: activity labels canonicalized through the WP34 fold registry
(same machinery, activity namespace); `RoleBindingExtractor` gains an email-evidence tier
(SENT_BY/SENT_TO actors on the events backing a step) before keyword fallback; `Event.attributes`
populated (actor, sourceDocId).

### WP42 — Per-instance alignment, deviations, and metrics

**42a.** Per-trace **alignment** against the canonical model (WP32b): replay each instance's event
sequence over the canonical directly-follows footprint → {matchedSteps, missingSteps,
unexpectedSteps, orderViolations} + per-instance conformance score (extends the aggregate-only
`ConformanceChecker:45`; footprint replay is set operations — cheap).
**42b.** Deviations feed **WP29 enforcement** (FLAG/QUARANTINE per policy — "instance inv-4488
skipped Manager Approval") and the digest's process section.
**42c. Cycle time everywhere**: compute per-step + end-to-end durations for MINED instances (event
timestamps) AND for ENGINE runs (StepExecution.startedAt/completedAt — the data sits unused);
write into `WorkflowRun.metrics` and instance attributes; `PerformanceMiner` becomes the shared
consumer.
**42d.** Instance status lifecycle for mined cases: complete (reached a terminal canonical step) /
in-flight (last event recent, non-terminal) / stale-incomplete (`kbInstanceStaleAfterDays`) —
WP40c-style status slot, election-resolved.

### WP43 — Unified instance enumeration (the first question)

**43a.** Missing engine queries: `GET /api/process/run?definitionId=&status=&from=&to=` +
`GET /api/process/definition/{id}/versions` (files exist as `<id>_v<N>.json` — just enumerate).
**43b.** Link the worlds: canonical PROCESS entity ↔ ProcessDefinition (via
sourceSuggestionId/acceptedProcessDefinitionId — the bridge fields exist both ways) so one query
plans over both populations.
**43c.** WP37 intent `PROCESS_INSTANCES` → executor: resolve "this process" (conversation referent
or name → canonical PROCESS entity) → enumerate ENGINE runs (43a) ∪ MINED instances (41b) in one
answer contract `{instances: [{id, source: ENGINE|MINED, versionRan?, status, startedAt, endedAt,
cycleTime, conformance, deviations[], membershipOpinion, evidence[]}], statusCounts, coverage
(assigned/candidate events), uncertain[]}` — rendered with WP24; `ask_graph_process_instances`
tool; digest process section lists instance counts per canonical process.

### WP44 — Process evolution (the second question)

**44a. DEFINED ledger**: structural diff between ProcessDefinition versions (steps/transitions/
roles added-removed-changed; the version files exist, the differ doesn't) + `supersedes` metadata +
`GET /definition/{id}/diff?from=&to=`.
**44b. MINED ledger**: cohort mining — run discovery per time window (41c) → `ProcessDiff` between
cohort models: {stepsAdded/Removed, arcsChanged with support proportions + **χ² significance**
(reuse ProcessCausalAnalyzer's test), roleChanges, cycleTimeDeltas (42c), variantShift (variant
share distribution)}. ProcessSuggestionStore gains lineage: {factSheetId, windowStart/End,
supersedesId} instead of accumulating unrelated UUIDs.
**44c. CANONICAL ledger**: the WP31/WP32b election history per divergence slot IS the change log —
surface it as a timeline (winner changes with election trace refs).
**44d.** Answer contract for "how has it changed": `{timeline: [changePoint {when, ledger:
DEFINED|MINED|CANONICAL, whatChanged, evidence/traceRef, significance}], cycleTimeTrend,
conformanceTrend, variantShift, narrative}` (verbalizer) + a `process-evolution` digest section +
`ask_graph_process_evolution` tool. Every change item cites its evidence — version payloads,
cohort instance sets, or election traces.

Phases: **V** = WP41 + WP42 → **W** = WP43 → **X** = WP44 (44a independent, may start with V).
Dependencies: WP41 needs WP14 (opinion union-find), the identifier machinery, WP38a (windows),
WP34 (label folding); WP42 needs WP32 (canonical model); WP44 needs WP41/42 (cohorts, cycle times)
+ WP31 election history. Sentinel e2e: a synthetic fixture of 2 engine runs + 3 email/PDF/xlsx
document clusters sharing invoice numbers and threads, one cluster missing an approval step, mined
in two time windows with a planted new step in window 2 — assert both target answer shapes above,
including the deviation flag, the uncertain-correlation bucket, and the χ²-significant arc shift.

---

# PART IX — The human-readable decision trace with sources (pass-9, 2026-07-02)

> Audit target: how the HYBRID reasoner presents information via the available graph primitives,
> and the design for a decision trace a human can read where every step cites sources.
>
> **What a user literally sees today for a hybrid explain** (reconstructed, verified):
> method badge + `"Entity 'node_42' has a hybrid relevance score of 0.71 (structural=0.71,
> semantic=0.00) over a 12-node subgraph."` + a confidence badge. Nothing else.
>
> The information needed to say more is COMPUTED then DISCARDED at four hops:
> 1. `HlMrfMapInference.Result` is reduced to `Map<id,double>` inside
>    `HybridReasoner.pslActivations()` (:119-128) — ground rules/supports severed;
> 2. the BFS subgraph (labels, types, edge descriptions, timestamps — ALL mapped by
>    `KnowledgeGraphReasoningAdapter`) is discarded after `rank()` (ExplainOrchestrator:259);
> 3. runner-up `ScoredEntity`s are discarded after the target filter;
> 4. frontend field mismatch: Java `ConfidenceBreakdown.ofHybrid` emits structural/semantic, the
>    `ReasoningTrailMapper` (:85-89) collapses to `fusedScore`, the component reads
>    `pslScore/mebnScore/embeddingScore/groundingScore` ⇒ **the breakdown bar NEVER renders for
>    HYBRID** (and kb-context-panel forces compact mode which hides it anyway).
> `ScoredEntity` = {entityId, score, structuralScore, semanticScore} — no label, no type, no
> contributions (HybridReasoner:78). Primitive-by-primitive: only id + collapsed confidence
> survive; label/type/timestamp/tags/attributes/edge-type/edge-provenance/descriptions all ignored.
>
> **Source-linkage verdict** (second audit): the spine is missing but NARROW —
> `Fact.sourceId` EXISTS and is stripped when `DerivationTree` is built (leaves carry only a
> crawl-run string); **`AttributionEvidence` already has `sourceReference` + `sourceSnippet`
> (:60-65) and `causalTrail` discards both** (only `.getSummary()`, ExplainOrchestrator:316-322);
> `ReasoningTrail` has NO sourceRefs field (evidence = flat strings);
> `EntailmentRecord.supportingFacts` is always `List.of()` for PSL/MEBN; no
> `GET /api/documents/{id}` exists (chat fabricates hrefs to it, unified-chat:2259);
> `TraceHumanizer` resolves node titles but never reads `_sourceDocumentId`. REUSABLE:
> `source-citation.component.ts` (complete citation chip: url/basisType/score/crawlRunId/page/
> chunk), the opinion-browser rows and contradiction panel ("candidates + score + source"
> patterns), and the ask_graph markdown formatters.

### WP45 — Make the hybrid reasoner tell its story

**45a. Contributions survive ranking.** `ScoredEntity` gains `label`, `type`, and
`List<Contribution>` where `Contribution = {kind: NEIGHBOR|RULE|SIMILAR, text, value,
relationType?, neighborId?, neighborLabel?, atomKey?}` (additive record fields, compact-ctor
back-compat). Sources: (i) top-k supporting ground rules per entity — keep the
`HlMrfMapInference.Result` and build the small `JustificationIndex` over the subgraph program
(both already exist; the severing at :119-128 becomes a projection instead); (ii) top contributing
NEIGHBOR edges from the subgraph (relation type + neighbor label + edge confidence — the adapter
already mapped them); (iii) for semantic: top-m most-similar entities with cosine.
**45b. Rebuild `hybridTrail`**: entity LABEL in the summary ("Alice Smith", not node_42);
`evidence[]` = humanized contributions; attach top-N runner-ups as a "considered" section;
**embed the question text as the query vector** (the `question` field is right there — kills the
permanent semantic=0.0); breakdown weights read from HybridReasoner config, not the :285 hardcode.
**45c. Fix the display bugs**: mapper emits `structuralScore`/`semanticScore` DTO fields; frontend
`breakdownSegments()` reads them; add a mini-bar for compact mode. Also populate
`EntailmentRecord.supportingFacts` for PSL/MEBN trails (from result + FactStore — currently
hardwired empty) and route MEBN VE-trace + causal narratives through `TraceHumanizer` (PSL-only
today).

### WP46 — The SourceRef spine (every trace leaf → document + quote)

**46a. lib `explain/SourceRef`**: `{documentId, documentTitle, chunkId?, quote?, url?, page?,
extractionModel?, crawlRunId?, occurredAt?}`. `ReasoningTrail` gains `List<SourceRef> sourceRefs`
+ structured `List<EvidenceItem{text, atomKey?, confidence?, sourceRefs[]}>` alongside the legacy
string list (additive; strings become a derived view).
**46b. Propagate what already exists**: `Fact.sourceId` → `DerivationTree` node field (the
projector stamps it; the tree builder drops it — one field + one assignment);
`InferredFact.supportingFactKeys` → leaf facts → their sourceIds; causal mapping keeps
`AttributionEvidence.sourceReference/sourceSnippet` (quote = snippet) instead of discarding;
`TraceHumanizer` reads `_sourceDocumentId`/`_extractionModel`/`_evidenceQuote` (WP33e) off the
resolved node and attaches a SourceRef.
**46c. Document title resolver**: `GET /api/documents/{id}` (title, sourcePath, mime, link — the
endpoint chat already pretends exists) + a server-side `DocumentTitleResolver` that denormalizes
titles into SourceRefs at trail-build time (no client N+1).
**46d.** Wire `source-citation.component` to `EvidenceItem.sourceRefs` in the reasoning-trail
component (the chip exists; the binding doesn't) and to chat `reasoning_trace` cards.

### WP47 — DecisionTrace: one human rendering for every trace grammar

The unifying presentation model (this ABSORBS WP24's `LlmReasoningView` deliverable — implement
once, here): lib `view/DecisionTrace` = ordered steps, each
`{statement (NL), verdict/score + band word, because: List<EvidenceItem>, sources: List<SourceRef>,
consideredAndRejected: [{label, score, reason}], operator?}` — projected FROM: ReasoningTrail (all
five modes), OpinionTree (WP5), CanonicalElectionTrace (WP31), AGGREGATE nodes (WP39), hybrid
contributions (WP45), DerivationTree. Three renderers off one model: **markdown** (chat + ask_graph
tools + digest — extend the existing tool formatters with `[title](url) "quote"` citations),
**JSON** (REST), **Angular** (upgrade reasoning-trail component: steps accordion + citation chips +
considered-and-rejected section — reuse the opinion-browser/contradiction-panel patterns).
Progressive disclosure: L1 = one answer sentence + band + top source; L2 = decision steps with
because/sources; L3 = the full tree (existing derivation JSON). Token budget from WP24 applies to
the markdown renderer.

**Target rendering (the sentinel fixture asserts this shape):**
```
Why is Alice Smith relevant? — PROBABLE (0.71)
1. Strongly connected in the PO-approval cluster (structural 0.71)
   • approves → PO-4471 (0.92) — "Alice approved the final PO" [PO approval thread, email, 2026-05-11]
   • works_at → Acme Finance (0.88) — [org-chart.xlsx · Sheet1]
   • +3 supporting links (rule: confidence propagates along graph links)
2. Similar to the question's subject (semantic 0.42) — nearest: "Finance approver" (0.81)
Considered but ranked lower: Bob Jones (0.55 — no approval link), Finance-Ops (0.41)
Sources: 4 documents — 2 emails, 1 xlsx, 1 pdf
```

Phases: **Y** = WP45 + WP46 (46 is the dependency for citations everywhere; 45c display fixes are
independent bug fixes, ship first) → **Z** = WP47. Dependencies: quotes need WP33e
(`_evidenceQuote`); band words need WP24's `VerbalScaleMapper` (built here as part of WP47);
election/aggregate step projection needs WP31/WP39 when those land — DecisionTrace ships with
ReasoningTrail+DerivationTree projections first, others as they arrive. Sentinel e2e: hybrid
explain on the fixture renders the target block above with ≥2 working document links and one
verbatim quote; the same DecisionTrace JSON re-renders identically through markdown and Angular
paths.

---

# PART X — No raw IDs: real references everywhere (pass-10, 2026-07-02)

> User context: a previous sprint of this kind shipped "a bunch of node ids not real references."
> This part enumerates every place a raw id reaches a human TODAY (two full sweeps, backend +
> frontend, all file:line verified) and installs the structural guards that make the regression
> impossible to reintroduce, not merely fixed once.

## X.0 Verified leak inventory

**Backend / tool / LLM-context leaks (ranked):**
| # | Leak | Where |
|---|---|---|
| L1 | **Query bindings ship raw ground constants** (sanitized externalIds, incl. path-based) with NO title map — straight into LLM context via ask_graph_query | KbGroundingController:197-207; AskGraphQueryTool:163-164 |
| L2 | `Contradiction.entityIdA/B` + `candidateStaleEdgeIds` have no label fields at all | Contradiction.java:22-32 |
| L3 | **Atom keys can embed filesystem paths**: `sanitizeAtomArg` does NOT strip `/` ⇒ `document(/home/.../file.xlsx)` atom keys reach opinions rows, evidence strings, derivation trees | GraphToFactStoreProjector:261-279 |
| L4 | `CommunityResult.nodeToCommunity`/`communityMembers` are raw-nodeId maps (incl. `document_/home/...` ids); matrix `CommunityReport.memberNodeIds` raw into GLOBAL RAG context | GraphCommunityService:60-64; MatrixGraphRagService:708-716 |
| L5 | Document title falls back to the FULL ABSOLUTE PATH when fileName is null on the direct `addDocument` path (crawl path is protected by `shortName`) | MatrixKnowledgeGraphService:500 |
| L6 | ask_graph_verify/explain first line echoes the caller's raw atom (`**SUPPORTED** — document(/home/...)`) — evidence below is humanized, the subject isn't | AskGraphVerifyTool:145; AskGraphExplainTool:142 |
| L7 | Fallbacks that leak on miss: `renderPath .orElse(id)`; community digest `"- [node <id>]"` into the LLM prompt; probabilistic context slug fallback | MatrixGraphRagService:647; CommunitySummaryService:156-160 |
| L8 | `RuleDto.head/body` unhumanized (`derived_entity(?X)` slugs) — `displayText` is humanized, fragments aren't | GraphRulesController:253-273 |

**Frontend leaks (ranked):** community-panel members render `{{nodeId}}` raw (:225);
graph-maintenance-hub renders `{{c.entityIdA}} ↔ {{c.entityIdB}}`, raw `{{edgeId}}` chips, and
`Entity #{{p.entityId}}` (:306,311,387); graph-visualizer attribution influence bars show
`{{nodeId | slice:0:20}}` while its own `getNodeLabel()` (:4073) goes uncalled; causal-attribution
sensitivity tab's `varTitle()` is a stub returning the raw id (:552-554); provenance-panel heading
is `{{p.nodeId}}` (:34); index-browser's primary column is the raw document id/path; unified-chat
retrieved docs fall through `sourceName || sourceId || id`. **No shared title-resolution utility
exists in the frontend** — every component rolls its own (or doesn't).

**Conflict resolved during audit**: opinion-browser's `displayLabel || atomKey` binding IS fed by
the backend (`KbOpinionBrowserController:429-448` populates `displayLabel` via `humanizeAtom`) —
the frontend-side "never populated" claim was wrong (it only grepped .ts files). Verify the JSON
field name matches at implementation; do not rebuild.

**Good patterns to consolidate (they exist!):** `TraceHumanizer` (5 methods, the atom/rule
humanizer), `BayesianInferenceResult.variableToTitle` (+`variableToNodeId` for follow-ups — THE
model pattern), `AttributionChain`/`CausalHop` dual id+title fields, `CrawlDocumentTracker.shortName`
(basename), `KbOpinionBrowserController.displayLabel`, frontend `getNodeLabel()` and
`title || label || id` three-tier fallbacks (graph-canvas, relations list, fol-rules, MPE trace,
source-citation).

### WP48 — Backend `ReferenceResolver` + DTO display-pairing sweep

**48a. One resolver.** `DisplayRef {id, kind: NODE|EDGE|DOCUMENT|ATOM|RULE|COMMUNITY|CASE|VARIABLE,
title, subtitle?, url?}` + a server-side batched `ReferenceResolver` (kg) that ABSORBS the good
patterns: wraps `TraceHumanizer`, the WP46c `DocumentTitleResolver`, `variableToTitle` builders,
and node-title lookups behind one `resolveAll(Collection<RefRequest>) → Map<String, DisplayRef>`
(one batch per response build — no N+1). Consolidation mandate applies: the existing helpers become
delegates, not duplicates.
**48b. The DTO contract**: every human-facing response either pairs each id field with a display
field or ships a `refs: Map<id, DisplayRef>`. Exact fixes: query response gains `refs` for binding
values (L1) and `AskGraphQueryTool` renders `Title (id)` — title for the human, id retained for
tool follow-ups; `Contradiction` gains `entityLabelA/B` + resolved edge descriptions (**additive
fields only — the file is in-flight elsewhere; coordinate**) (L2); `CommunityResult` gains
`memberRefs` (L4); `RuleDto` humanized `headDisplay/bodyDisplay` (L8); verify/explain tool first
line uses the humanized atom with the raw key demoted to a `key:` line (L6); every `orElse(id)`
fallback routes through the resolver and, on true miss, renders `⟨unresolved⟩` + logs a counter
(L7) — misses become observable instead of silent.
**48c. Path hygiene (L3/L5)**: `sanitizeAtomArg` maps path-bearing externalIds to a stable short
hash slug (`doc_ab12cd34`) registered with the resolver — **atom keys must never embed filesystem
paths** (also kills the `%2F` class of bugs at the source); `addDocument` enforces the basename
fallback (`shortName` exists — call it). Migration note: existing path-keyed atoms in stores are
re-keyed by the next full cascade (projection rebuilds atoms), so no store migration is needed;
promotion-state keys migrate via WP14a's `migrateAtomKeys`.

### WP49 — Frontend `DisplayRefService` + pipe + the eight fixes

One shared Angular `DisplayRefService` (consumes `refs` maps shipped in responses; falls back to a
batched `GET /api/graph/labels?ids=` for legacy responses; caches per factSheet+epoch) + a
`displayRef` pipe so `{{ id | displayRef }}` works anywhere. Convention: **a raw id is never the
primary visible text** — ids appear only as secondary muted copy-chips (the unified-chat truncated
badge is the model). Fix list: community-panel members; the three maintenance-hub spots;
visualizer influence bars (call the existing `getNodeLabel`); the `varTitle()` stub;
provenance-panel heading; index-browser gains a Title column (id demoted to tooltip/copy-chip);
unified-chat fallthrough (title → basename → "Document"); verify opinion-browser field mapping.
Dev mode: the pipe renders `⟨unresolved: id⟩` visibly so leaks are caught in development, never
silently shipped.

### WP50 — The id-leak sentinel harness (the regression stopper)

**50a. Backend golden-render tests**: a shared `IdLeakAssert` regex battery — absolute-path
pattern `(/[\w.-]+){2,}`, `document_/`, UUID pattern, `(entity|node|case|doc)_[a-z0-9]{6,}` slugs,
`derived_` in display fields, `case-\d+` ordinals — run against the RENDERED output of every
human-facing formatter: all ask_graph tool outputs, `deterministicSummary`, chat contexts
(GraphReasoningRetriever), community digests, the WP27 digest markdown, the WP47 DecisionTrace
markdown. Fails the build on match; allowlist only explicit `key:`/copy-chip fields.
**50b. Frontend guard**: a static test over templates flagging `{{…Id}}`/`{{…Key}}` bindings
outside allowlisted debug-chip classes + fixture-rendered component tests run through the same
regex battery.
**50c. Observability**: `ReferenceResolver` logs unresolved-count per response (metric), and the
digest coherence section reports "unresolved references this epoch" — drift is visible before
users see it.
Acceptance (whole plan, added to §5): **no id-shaped token appears as primary text in any rendered
human-facing fixture**; the two walkthrough questions (Part VII), the process answers (Part VIII),
and the hybrid DecisionTrace (Part IX) all pass `IdLeakAssert`.

Phases: **AA** = WP48 → **AB** = WP49 → **AC** = WP50 (write 50a's harness FIRST, red, then
48/49 turn it green — TDD is the point of this part). Dependencies: WP48 wraps WP46c's resolver;
WP50 gates Parts VII-IX sentinel fixtures. Coordinate on `Contradiction.java` (in-flight).

- **Overlap**: WP14 unifies alias/identifier/similarity clusters at the reasoning boundary (evidence
  ⊕ across cluster members), WP15a/15b propagate soft truth across similar/co-mentioned entities.
- **Synthesis**: Part I WP12 already defines the operator-tree answer; WP16c scopes its candidate
  stage to top communities and adds community summaries as coarse candidates.
- **Trace**: the OpinionTree gains "unified aliases" and "communities consulted" nodes (WP14b/16c);
  every propagation hop is a ground rule visible in the JustificationIndex.
- **Quickly**: WP17 removes the per-query scans and the redundant full re-solve; WP16b bounds solve
  time by the largest community; WP16c bounds retrieval by top-m communities; WP18 keeps inventory
  sheets from exploding the graph in the first place.
