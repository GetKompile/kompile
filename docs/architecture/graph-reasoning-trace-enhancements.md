# kompile-graph-reasoning: reasoning-trace & claim-verification enhancement analysis

*2026-07-09 — audit of `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning` plus a
literature pass on the source math (provenance semirings, PSL/HL-MRF explanation, BN explanation,
MEBN/SSBN, subjective logic & argumentation, KG fact-checking, calibration/conformal).*

> **IMPLEMENTATION STATUS (same day, 3 waves of opus agents + integration):**
> **Shipped & green** — fixes #1–#4, #6–#10, #12, #13 and enhancements **E1–E12** plus a
> `DossierAdjudicator` bridge (claims dossier → QBAF): Datalog provenance capture w/ per-atom
> derivations, PSL per-atom attribution + `PslTraceAdapter` + ADMM dual forces, opinion+meta-carrying
> `ReasoningTrace.Step` (+REBUTTAL/REVISION kinds, gap-analyzer UNCERTAIN_OPINION/CONTESTED_EVIDENCE),
> counter-evidence + functional-constraint REFUTED + near-miss why-not suggestions through
> verifier→service→controller→`ask_graph_verify`, BeliefReviser purge chain, WoE + SSBN construction
> log + traced queryAll, Jøsang operator suite (averagingFuse/weightedFuse/deduce/uncertaintyMaximized/
> comultiply/FusionMode + base-rate-canonical cumulative fusion), Pearl virtual-evidence soft findings,
> sign-correct exact max-pseudolikelihood (+`GroundRule.templateIndex` fixing equal-weight gradient
> collapse), semiring fixpoint (Viterbi/counting/top-k proofs + soft materialization + proof
> fragility), DF-QuAD QBAF adjudication w/ postulate tests, `VerdictFragility`, Knowledge-Linker path
> evidence + calibrated-KGE + mined-rule dossier with log-odds fusion.
> Suites after wave 3: lib 1542/1542, knowledge-graph grounding 16/16, app-main controller 34/34.
>
> **Wave 4 (same day): E13–E18 + remaining seams ALL SHIPPED.** `tms/atms/` (environments,
> antichain labels, nogood store, retraction impact, FixpointResult bridge) · `fol/grounding/
> calibration/` (PAVA isotonic, per-signal CalibrationHarness with ECE + reliability tables + JSON
> persistence, `ConformalVerdict` with finite-sample quantile + Mondrian + 7-value decision enum;
> `calibrateAggregate` now conservative-min with REFUTED veto) · `tms/inconsistency/` (Belnap
> B-marking, MI-count/contension-like/greedy-min-repair/blame, `ClaimNeighborhoodInconsistency`) ·
> `attribution/shapley/` (Monte-Carlo permutation Shapley, witness-restricted players, source
> aggregation, memoized Datalog evaluator) · opinion-aware `EvidenceAccumulator` (correlated
> engines averaging-fuse, independent channels cumulative-fuse; fused Opinion + operator meta on
> the composite trace) · `explain/ProvSerializer` (PROV-N + PROV-JSON, deduped entities,
> `kompile:rebuts/revises/confidence/belief…`) · E9 rules now flow orchestrator→
> `FactSheetKbState.lastRules`→verifier (near-miss suggestions live in production) · E12
> `FragilityDto` on `VerifyResponse` + CLI rendering.
> **Research refinements (same day): ALL SHIPPED.** `confidence/Opinion.ccFuse` (true Jøsang
> consensus & compromise fusion — router now dispatches CONSENSUS_COMPROMISE here) + `confidence/ds/`
> (MassFunction BBA, Dempster/Yager/**PCR5**/TBM-conjunctive Combination, `HighConflictFusion` facade
> routing SL↔PCR5 by pairwise conflict K with TBM open-world empty-mass signal — resolves Zadeh's
> paradox) · `fol/grounding/DeepWhyNot` (bounded recursive instance-based why-not: WhyNotNode tree +
> ground CompletionSets = minimal witnesses of the missing part; also fixed the one-step explainer
> to bind shared body variables from the satisfiable side) · `bayesian/VariableElimination.
> jointMostProbableExplanation` (true max-product MPE with back-pointers, vs the old marginal-argmax)
> + `bayesian/MostRelevantExplanation` (greedy MRE via Generalized Bayes Factor / CBF, parsimony/
> explaining-away aware) · `argument/QeSemantics` (Potyka quadratic-energy gradual semantics) +
> `argument/QbafWeightLearner` (finite-difference per-kind weight learning with a holdout beatsDefault
> gate).
> **Final suites: lib 1857/1857 · knowledge-graph grounding 20/20 · app-main controller 37/37 ·
> CLI AskGraphToolsTest 35/35.** The E1–E18 plan AND all research refinements are fully implemented.
> Deploy = app restart + CLI native rebuild + MCP reconnect.

---

## 1. What's here today (the map)

24 packages. The reasoning-relevant clusters:

| Cluster | Packages | Core artifacts |
|---|---|---|
| Trace/explain | `explain/` | `ReasoningTrace` (canonical Step tree), `ReasoningTrail`, `CompositeReasoningTrail`, `OpinionTree`, `Explanation`, `ConfidenceBreakdown`, `ReasoningTraceRenderer` (step IDs + attribution index + LLM context), `ReasoningTraceGapAnalyzer` (15 gap types) |
| Logical | `fol/`, `fol/grounding/`, `fol/materialization/` | `RecursiveQueryEngine` (semi-naive Datalog, Tarjan-SCC stratified NAF), `ConjunctiveQueryEngine` (Gödel-min), `DerivationTree`, `EntailmentEngine`, `InferredFactStore` (versioned), `DefaultKbVerifier` (SUPPORTED/REFUTED/UNKNOWN), `PlattCalibrator` |
| Probabilistic | `psl/`, `mebn/`, `bayesian/`, `prior/`, `learning/` | Real HL-MRF (Łukasiewicz, linear+squared hinge, 4 solvers incl. consensus ADMM), `GroundRuleResult` (per-rule distance-to-satisfaction + potential + satisfied), `SSBNGenerator` (context nodes, findings termination, Bayes-Ball pruning), `VariableElimination.queryWithTrace()` (`InferenceStep` list), noisy-OR CPTs, perceptron/pseudolikelihood weight learners, `CascadePriorProvider` |
| Uncertainty | `confidence/`, `uncertainty/` | Jøsang binomial `Opinion` algebra (cumulative/averaging/consensus fuse, conjoin, discount, complement, conflict), `OpinionStore`, `StrengthBand`; `SensitivityAnalyzer` (OAT), exact EIG, PSL BALD/EIG approximators, `ValueOfInformation` |
| Belief maintenance | `tms/`, `maintenance/` | `JustificationIndex` (PSL ground rules → premise index), `BeliefReviser` (retract + propagate), `ContradictionDetector` (hard-rule violations, negated pairs, functional predicates), `ProbabilisticContradictionDetector` (MEBN posterior conflicts), pruning policies |
| Synthesis/hybrid | `synthesis/`, `hybrid/` | 12-dim `AnswerFeatures` over Opinion signals, `AnswerSynthesizer` (SL fold: cumulative-fuse groups, consensus for engines, conjoin at root), `LogisticAnswerScorer` + beatsFold gate, `HybridReasoner` (0.6 structural + 0.4 semantic scalar blend) |
| Graph/claims | `unified/`, `model/`, `subgraph/`, `embedding/`, `attribution/` | `UnifiedGraph` (facts()/types()/opinionStore()/embeddingTable()), KGE bridges (`KgeTripleScoreFunction`, `KgePslBulkObserver`, `EmbeddingPslEvidence`), `SubgraphMaterializer` (+provenance), `TemporalAttributionService` (Allen relations, decay) |

Claim path today (`ask_graph_verify` → `KbGroundingController.resolveAtom` → `KbGroundingService.verify`
→ `DefaultKbVerifier`): a claim is a **pre-grounded PSL atom key string**; verify is a **4-step store
lookup** (latest inferred ≥ threshold → `~atom` negation → hard observed fact → UNKNOWN). No inference
runs at verify time.

### The central finding

**The engines already compute most of what a great trace needs — the wiring drops it.** Every
conversion to the canonical `ReasoningTrace` is lossy, and the Datalog materialization path erases
provenance before a trace is ever built:

| Rich artifact | Produced by | What reaches `ReasoningTrace` | What's lost |
|---|---|---|---|
| `GroundRuleResult` (d_r, w_r·d^p, satisfied, display) | `HlMrfMapInference.Result.groundRuleResults()` | nothing automatic — caller must hand-build `EntailmentRecord`s | which ground rules drove a *specific* atom; ADMM duals; near-threshold rules (< 0.1 activation cutoff drops "almost fired") |
| `InferenceStep` (eliminated var, factors, prior→posterior shift) | `VariableElimination.queryWithTrace()` | manual assembly only; `queryAll()` never traces | the whole VE trace for multi-var queries |
| SSBN structure, context-constraint outcomes, CONTEXTUAL-vs-DEFAULT mode, pruning log | `SSBNGenerator` | nothing | *why this CPT* — the MEBN-specific story |
| `Opinion` (b, d, u, a) | fusion everywhere | only `expectation()` scalar | uncertainty mass vs disbelief — (u=0.9, b=0.05) and (u=0.1, b=0.5) become indistinguishable |
| `ReasoningTrail` fields | all engines | derivation tree OR entailments only | question, runId, computedAt, `ConfidenceBreakdown` (8 per-modality scores), evidence strings, activatedRules, humanization maps |
| Datalog derivations | `RecursiveQueryEngine` | **flat leaves** | `ForwardChainingMaterializer` stores `supportingFactKeys=[]`, `supportingRuleIds=["FOL_ENTAILMENT/DEDUCTIVE"]`; variable bindings discarded; only ONE derivation per atom kept (`InferredFactStore.latest`); `DerivationTree` records only `supportingRuleIds().get(0)` |
| Contradictions | both detectors | nothing — never attached to traces, never consulted by `verify` | counter-evidence entirely absent from verdicts |
| Uncertainty artifacts (EIG, VoI, sensitivity) | `uncertainty/` | nothing | "what observation would most change this" |

### Fidelity issues found along the way (fix regardless of enhancements)

1. **`ForwardChainingMaterializer` empty provenance** — every forward-chained fact materializes with
   no supporting facts/rules ⇒ `DerivationTree` over Datalog output is one level deep. This single
   bug flattens most FOL traces. (`fol/materialization/ForwardChainingMaterializer.java:175`)
2. **`BeliefReviser` never purges `InferredFactStore`** — retraction computes `unsupportedAtoms` but
   stale inferred facts survive and `verify` keeps SUPPORTING from them. (`tms/BeliefReviser.java`)
3. **`derivationDepth` in `VerifyResponse` = `evidenceAtoms.size()`**, not tree depth; a 4-hop chain
   and 4 flat observations look identical. (`KbGroundingController.java:208`)
4. **`sourceProvenance` = copy of `evidenceAtoms`** — the real `Fact.sourceId` chain (crawl/document/
   chunk) that `GraphToFactStoreProjector` carefully constructs never surfaces.
5. **`PlattCalibrator` defaults to identity (w=1,b=0) and `calibrateAggregate()` returns 1.0**
   (placeholder) — "calibratedConfidence" is largely cosmetic today.
6. **Sparse-aware UNKNOWN exists but is dead** — `KbGroundingService.verifyOpinion()` routes UNKNOWN
   through `SparseGraphAssessor.absentFactOpinion()`, but the controller only calls `verify()`.
7. **Silent incompleteness**: `FolInferenceService` 10k-pair grounding cap (no flag), EDB excludes
   facts with value < 0.5 (binarization), PSL 500k ground-rule cap (flag exists, unsurfaced).
8. **`PseudolikelihoodLearner` is not pseudolikelihood** — same `distPred − distGT` gradient as the
   perceptron with a cheaper prediction; rename or implement the real block-conditional PL gradient.
9. **MEBN soft findings use argmax, not Jeffrey's rule** (acknowledged TODO in `EntailmentEngine`).
10. **Semantics mismatch across tiers** (document as intentional or unify): query conjunction is
    Gödel min; PSL bodies are Łukasiewicz; `EvidenceAccumulator` fuses modalities by arithmetic mean
    even though the SL fusion algebra sits one package away.
11. `InferredFact.value == confidence` always (set equal in `fromEntailment`) — dead distinction.
12. `VerifyResult` REFUTED is effectively unreachable except hard-0 facts: PSL never produces
    `~atom` keys.
13. **`cumulativeFuse` non-monotone expectation, root cause identified**: fused base rate is an
    uncertainty-weighted average, so fusing sources with *different base rates* can lower `E = b + a·u`
    even when both sources are positive (the prior term `a·u` shrinks faster than `b` grows). With a
    common base rate, expectation is monotone in positive evidence. Mitigations in E7. Also:
    `averageFuse` is a plain component mean and `consensus` uses ad-hoc `(1−u)` weights — neither is
    Jøsang's averaging/weighted fusion; verify against the book when touching these.

---

## 2. Enhancement proposals

Ordered by (leverage ÷ effort), grouped in tiers. Citations in §3.

### Tier 1 — surface what already exists (days each, no new machinery)

**E1. Repair Datalog provenance capture.** During `RecursiveQueryEngine` evaluation, record per
derived tuple: the rule that fired and the parent tuples (and optionally the variable binding).
Store them in `InferredFact.supportingFactKeys/supportingRuleIds` (the fields exist; they're just
written empty). This immediately makes `DerivationTree`/`fromDerivation` produce real multi-level
proofs. **Keystone: prerequisite for E8, E9, E10, E12.**

**E2. PSL per-atom rule attribution → trace.** Build a reverse index `atomKey → ground rules
containing it` on `HlMrfMapInference.Result` and add a `toReasoningTrace(targetAtom)` adapter:
for each rule touching the target report (w_r, d_r(y*), direction: pushes-up/pushes-down, satisfied),
sorted by w_r·d_r. The ADMM solver already holds dual variables λ_r at convergence — expose them as
per-rule "force" so an interior atom's value is explained as a *balance of rule forces* (KKT view).
This is the published PSL explanation recipe (Bach et al. §4–5; Kouki et al. TIIS 2020 generated NL
explanations exactly this way). Also lower/parameterize the 0.1 "activated" cutoff in
`EntailmentEngine` so near-miss rules are reportable instead of silently dropped.

**E3. Stop collapsing Opinions; carry operator provenance.** Add optional `opinion` (b,d,u,a) and
`evidenceCount` to `ReasoningTrace.Step` (or a typed confidence union). `OpinionTree.toReasoningTrace`
currently keeps only `expectation()`; the gap analyzer then can't tell "uncertain" from "contested" —
precisely the distinction subjective logic exists to make. Keep `discountTrust` and the fusion
operator per step. Let `ReasoningTrail.toReasoningTrace` attach `ConfidenceBreakdown`, runId,
computedAt, question as root-step metadata instead of dropping them.

**E4. Attach contradictions and revisions to traces + verdicts.** New `StepKind.REBUTTAL` (and
`REVISION`). `verify()` should run `ContradictionDetector` against the claim's neighborhood and
return `counterEvidence` alongside `evidence`; `BeliefReviser.retract()` should emit a revision
step/journal entry and purge dependent inferred facts (fix #2). `VerifyResult` grows:
`counterEvidence`, `contradictions`, honest `derivationDepth` (fix #3), real `sourceProvenance`
from `Fact.sourceId` (fix #4).

**E5. Functional-constraint refutation (principled REFUTED under OWA).** The functional-predicate
list already exists in `ContradictionDetector`. At verify time: if `p` is functional and the store
holds a high-confidence competing `p(s, o′)`, o′ ≠ o, return REFUTED with the competing triple as
counter-evidence. This is the standard local-closed-world move (PCA, Galárraga et al.) and the only
cheap, sound source of REFUTED verdicts. ~1 day; transforms the verdict lattice.

**E6. BN/MEBN explanation quick wins.** (a) Weight of evidence per finding: `W(H:eᵢ) =
log P(eᵢ|H)/P(eᵢ|¬H)` — one extra VE pass per finding, gives "this posterior is driven by finding X
(+1.2), finding Y (−0.4)" step annotations (Good 1985). (b) Record MEBN context-constraint outcomes,
MFrag + OV substitution, CONTEXTUAL/DEFAULT/FINDING mode, and the pruning log as trace steps —
`DistributionMode` already exists, it's just never surfaced. (c) Route `queryAll` through
`queryWithTrace` when a trace is requested.

**E7. Subjective-logic operator hygiene.** (a) *Fusion-mode selection*: cumulative fusion is only
correct for independent evidence; correlated observers of the same evidence need averaging fusion
(idempotent), reliability-weighted sources need weighted fusion, deep conflict needs consensus &
compromise. Add a `FusionMode` and route by source relationship — `AnswerSynthesizer` already models
this per group; make it uniform (incl. `EvidenceAccumulator`, see E17). (b) *Base-rate
canonicalization before cumulative fusion* — fixes the non-monotone-expectation gotcha (#13) for the
common case. (c) *Trust discounting* `ω_X^{A;B} = ω_B^A ⊗ ω_X^B` to chain source reliability into
edge opinions (the `discount` primitive exists; wire source-trust opinions through it). (d) *Opinion
deduction* for conditional propagation along edges. (e) *Uncertainty-maximized opinions* when
importing external point probabilities (don't fabricate evidence mass). Verify all formulas against
Jøsang 2016 — current `averageFuse`/`consensus` deviate (#13). Optional later: a Dempster–Shafer
PCR5 path for ≥3-source high-conflict entity resolution and TBM `m(∅)` for open-world signaling —
only where SL's cumulative fusion degrades (conflict mass K → 1).

### Tier 2 — structural upgrades (the real math adoption)

**E8. Semiring-parameterized Datalog fixpoint (provenance semirings).** Parameterize
`RecursiveQueryEngine` on `Semiring<K>`: Viterbi ([0,1], max, ×) reproduces today's confidence;
counting (ℕ) gives "how many independent derivations" (a free corroboration signal); PosBool/
absorptive-Sorp gives **why-provenance: all minimal witness sets per conclusion**, computed *during*
evaluation instead of materializing proof trees post-hoc (Green–Karvounarakis–Tannen PODS 2007;
absorption guarantees termination under recursion). A Scallop-style top-k-proofs semiring (DNF over
fact IDs truncated to k) is the practical middle ground and directly yields **k-best proofs** — the
2nd-best/1st-best ratio is a proof-fragility signal, and multiple independent proofs materially
strengthen a verdict (Huang–Chiang k-best). Retires the one-derivation-per-atom limitation for good.
Medium effort: the fixpoint loop is the only touch point; today's behavior is the Viterbi instance.

**E9. Why-not / near-miss explanation for UNKNOWN.** Users ask "why *doesn't* the graph support
this?" at least as often as "why does it". Start with one-step near-miss: for claim C, find rules
whose head unifies with C; for each, evaluate the body and report which atoms bound and which single
missing fact would complete a proof ("would be SUPPORTED if `subsidiary(orgB, acme)` existed —
currently absent"). Turns UNKNOWN from a dead end into an actionable gap and composes with the crawl
loop (missing facts become crawl/extraction targets). Full instance-based why-not (PUG-style
firing-rule rewriting) later. Pair with the dead sparse-aware UNKNOWN path (fix #6) to distinguish
"entity unknown" / "entity known, no evidence" / "near-miss".

**E10. QBAF gradual-argumentation verdict adjudication.** Make the verdict itself a dialectical
computation instead of a threshold on one scalar: build a bipolar argument graph around the claim —
pro-arguments from derivation paths (base score = path confidence), con-arguments from refuting
derivations, functional-constraint conflicts (E5), and detector output (E4) — then run a gradual
semantics (DF-QuAD first; Quadratic Energy later if differentiability for weight learning is wanted)
to a fixed point and read the verdict off σ(claim) with hysteresis thresholds. The argument graph
**is** the trace: pro/con structure with per-argument strengths is exactly "reasoning about a claim"
made legible, and the gradual-semantics postulates (stability, monotonicity, weakening/strengthening)
give property-based tests. Fits the existing graph infrastructure; the fixed-point loop is ~50
iterations of arithmetic.

**E11. Multi-signal claim dossier.** `verify()` today consults one store. Upgrade the claim surface
to an evidence-dossier over independent signals, each with its own explanation payload:
1. direct edge / Datalog proof (E1/E8 provenance),
2. PSL soft-truth with rule attribution (E2),
3. **path evidence** — Knowledge Linker semantic proximity `∏ 1/log k(vᵢ)` over the existing CSR
   adjacency (~1 day, unsupervised, handles claims with no direct edge; the path IS the explanation),
   later SFE/PredPath typed-path features per predicate,
4. **calibrated KGE plausibility** — the KGE bridges exist (`KgeTripleScoreFunction`) but are unused
   at verify time; Platt-scale scores (Tabacof–Costabello; Safavi et al.) and cap the fused weight —
   a signal, never a verdict,
5. mined-rule firings (AMIE-style PCA-confidence rules read as English; the process-mining layer
   already produces rule-shaped evidence),
6. functional-constraint refutation (E5).
Aggregate via the QBAF (E10) or, minimally, log-linear fusion over per-signal calibrated
probabilities — the existing 12-dim synthesis featurizer/logistic scorer is the natural home.
Output shape: ExFaKT-style dossier with separate supporting and refuting chains.

**E12. Counterfactual verdict sensitivity ("what would change this").** For SUPPORTED claims:
minimal retraction set (via `JustificationIndex.solelyDependentOn` + E8 witness sets — a witness set
of size 1 means one fact flips the verdict); for probabilistic support: one-at-a-time evidence
removal `|P(H|E) − P(H|E∖eᵢ)|` (the `SensitivityAnalyzer` machinery exists, unwired). Report as a
`fragility` block on `VerifyResult`: `minimalSupport`, `wouldFlipIf`, `robustness`.

**E13. Unify justification under an ATMS-lite.** `JustificationIndex` only indexes PSL ground rules;
Datalog, MEBN, and embedding signals are invisible to retraction. An assumption-based TMS label
(antichain of minimal environments per conclusion + nogood store) is the formal object that gives:
all minimal support sets, cheap belief revision on retraction, and contradiction *environments*
(which assumption combos are inconsistent) — the theory underlying what `BeliefReviser` +
`ContradictionDetector` approximate today (de Kleer 1986). E8's PosBool annotations and ATMS labels
are the same object computed two ways; implement once, share.

### Tier 3 — calibration, attribution, polish

**E14. Real calibration + conformal abstention.** Fit `PlattCalibrator` per `SignalType` on labeled
verify outcomes (it's identity today, fix #5); report ECE; isotonic for the sparse clustered scores
symbolic engines emit. Then inductive conformal prediction over `[p_S, p_R, p_U]`: prediction set =
classes above the calibrated quantile; singleton set → verdict, `{S,R}` → CONTRADICTORY, full set →
principled ABSTAIN with a coverage guarantee (`P[true verdict ∈ set] ≥ 1−α`). Mondrian
(class-conditional) once ≥~30 labeled examples per class exist; Venn-Abers intervals while the
calibration set is small.

**E15. Graded inconsistency scores.** Fast path: Belnap 4-valued marking — atoms with conflicting
evidence get value B ("both"), contradictions don't explode, and `#B-atoms` in the claim's r-hop
neighborhood is a cheap "how contested is this region" score to attach to verdicts and gap output.
Deeper: contension I_c / minimum-repair I_hs / per-triple blame `|{MIS ∋ τ}|` (Hunter–Konieczny,
Thimm; the Tweety Java library implements these — use it as a reference or dependency).

**E16. Shapley/responsibility source attribution.** Monte-Carlo Shapley over EDB facts (evaluate the
claim on sampled fact subsets — engine-agnostic) to report "this verdict rests 58% on source
doc-123". Exact computation is #P-hard beyond hierarchical CQs (Livshits et al.), but the sampling
estimator is easy and parallel; witness sets from E8 shrink the player set to facts appearing in
some proof.

**E17. Opinion-aware modality fusion.** Replace `EvidenceAccumulator`'s arithmetic mean with the E7
operator selection (cumulative for independent modalities; averaging/weighted for engines reading
the same fact store — `AnswerSynthesizer` already does this correctly) and record the operator on
the FUSION step (E3).

**E18. Trace metadata + PROV-O export adapter.** runId/computedAt/question on the trace root (E3),
stable step IDs in the core structure (renderer currently invents path IDs), and a small serializer
mapping Step→`prov:Entity`/`prov:Activity`/`wasGeneratedBy`/`used`/`wasAttributedTo` with
`kompile:confidence` annotations, for external audit/lineage consumers. Output adapter only; keep
`ReasoningTrace` as the internal model.

### Sequencing note

Per project convention, everything ships enabled. Suggested order: fixes 1–6 + E1 + E2 (one wave:
trace fidelity), then E4/E5/E7 (verdict honesty + fusion hygiene), then E8+E9 (provenance semiring +
why-not — the deep win), then E10/E11/E12, then Tier 3. E1 is the keystone: nothing downstream is
trustworthy while forward-chained facts have empty provenance.

---

## 3. Literature index

**Provenance/proofs**: Green, Karvounarakis & Tannen, *Provenance Semirings*, PODS 2007 · Bourgaux
et al., *Revisiting Semiring Provenance for Datalog*, KR 2022 · Cheney, Chiticariu & Tan, *Provenance
in Databases: Why, How, Where*, FnT DB 2009 · Lee, Köhler, Ludäscher & Glavic, *PUG: Why & Why-Not
Provenance*, PVLDB 2018 · Huang & Chiang, *Better k-best Parsing*, IWPT 2005 · Meliou et al.,
*Causality and Responsibility for Query Answers*, PVLDB 2010 · Livshits, Bertossi, Kimelfeld & Sebag,
*The Shapley Value of Tuples in Query Answering*, ICDT 2020 · Doyle 1979 (JTMS); de Kleer 1986
(ATMS) · W3C PROV-DM/PROV-O.

**PSL/BN/MEBN explanation**: Bach, Broecheler, Huang & Getoor, *Hinge-Loss MRFs and PSL*, JMLR 2017
(KKT/ADMM duals) · Kouki et al., explanations from active PSL rules, ACM TIIS 2020 · Laskey, *MEBN*,
AIJ 2008; Santos & Carvalho, Bayes-Ball SSBN construction, 2016 · Park & Darwiche, *Complexity of
MAP*, JAIR 2004 · Yuan, Lu & Druzdzel, *Most Relevant Explanation*, JAIR 2011 (GBF/CBF) · Nielsen,
Pellet & Elisseeff, *Explanation Trees for Causal BNs*, UAI 2008 · Good, *Weight of Evidence*, 1985 ·
Chan & Darwiche sensitivity, AAAI 2002 / IJAR 2005 · Wellman, *QPNs*, AIJ 1990 · Fierens et al.,
*ProbLog2* (proofs → d-DNNF WMC; the circuit is the trace), TPLP 2015 · Kimmig, Van den Broeck & De
Raedt, *aProbLog*, AAAI 2011 (semiring-parameterized inference) · Li et al., *Scallop*, NeurIPS 2021
/ PLDI 2023 (top-k-proofs semiring).

**Subjective logic / argumentation / inconsistency**: Jøsang, *Subjective Logic*, Springer 2016
(CBF/ABF/WBF/CCF selection criteria, trust discounting, deduction/abduction, uncertainty
maximization; opinion ↔ Beta evidence mapping) · van der Heijden et al., multi-source SL fusion,
arXiv:1805.01388 · Smarandache & Dezert, PCR5/PCR6; Smets, TBM; Dubois–Prade disjunctive rule
(DS alternatives under high conflict; Zadeh's paradox) · Dung, AIJ 1995 (abstract argumentation) ·
Modgil & Prakken, ASPIC+, 2014 · Besnard & Hunter h-categoriser, 2001 · Rago et al., *DF-QuAD*,
2016 · Potyka, quadratic-energy gradual semantics, 2018 · Baroni, Rago & Toni, gradual-semantics
postulates, AAAI 2018 · Hunter & Konieczny, inconsistency measures, KR 2008 / AIJ 2010 · Thimm,
*Inconsistency Measurement*, 2019 (+ Tweety Java library, tweetyproject.org) · Belnap 4-valued
logic (paraconsistent B-marking).

**KG claim verification**: Ciampaglia et al., *Computational Fact Checking from Knowledge Networks*,
PLoS ONE 2015 (Knowledge Linker) · Shi & Weninger, *PredPath*, 2016 · Gardner & Mitchell, *SFE*,
EMNLP 2015 · Syed et al., *COPAAL*, ISWC 2019 · Galárraga et al., *AMIE* WWW 2013 / *AMIE3* ESWC
2020 (PCA confidence, local closed world) · Meilicke et al., *AnyBURL*, IJCAI 2019 / VLDBJ 2023 ·
Tabacof & Costabello, *Probability Calibration for KGE*, ICLR 2020 · Safavi, Koutra & Meij, KGE
calibration, EMNLP 2020 · Rossi et al., *Kelpie*, VLDB 2022 · Pezeshkpour et al., *CRIAGE*, NAACL
2019 · Arnaout & Razniewski, negative knowledge in Wikidata, WWW/VLDB 2021 · Gad-Elrab et al.,
*ExFaKT* (supporting + refuting explanation dossiers), WSDM 2019 · Röder et al., *HybridFC*, 2024 ·
Qudus et al., *TemporalFC*, ISWC 2023.

**Calibration/conformal**: Guo et al., *On Calibration of Modern Neural Networks*, ICML 2017 ·
Angelopoulos & Bates, *A Gentle Introduction to Conformal Prediction*, 2021 · Mondrian
(class-conditional) CP · Venn-Abers predictors (finite-sample validity for small calibration sets).
