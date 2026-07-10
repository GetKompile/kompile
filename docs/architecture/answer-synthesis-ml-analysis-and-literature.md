# Answer-Synthesis ML Layer — Code Analysis, Literature Positioning, Build-On Agenda

> 2026-07-02. Companion to `reasoning-math-composition-implementation-plan.md` (WP1–WP50). The
> working tree implements a large slice of that plan plus a NEW ML layer the plan only sketched:
> a featurizer + logistic scorer over the aggregate reasoning signals. This doc: (1) what the new
> code does and how well, (2) implementation status vs the plan, (3) where this sits in the
> literature (novelty verdict), (4) the build-on agenda.

---

## 1. The new ML layer (lib `synthesis/`, 722 lines + kg `AnswerSynthesisService`, 589 lines)

**Pipeline**: `AnswerSynthesisService.buildScoredCandidates(query, fs)` gathers per-candidate
signals — retrieval (HYBRID scored candidates, batch-max normalized), TYPE (ontology RANGE axioms
gated by a query-derived relation, else allowedEntityTypes), ENGINE (KB verify SUPPORTED/REFUTED/
UNKNOWN + two-pass KGE with **per-batch `KgeCalibration.fit()` using KB-supported facts as
positives** + optional MEBN posterior), CONSISTENCY (complement of max competing support). The
grounding context (anchor entity + relation) is **auto-derived from the query** by longest
ontology-relation and longest retrieved-entity name match — no caller schema needed.

**Two scoring paths, correctly ordered**:
1. `AnswerSynthesizer.synthesize()` — the algebraic fold (⊕ within independent groups, consensus
   across correlated engines, ⊙ across groups) — ALWAYS runs; the OpinionTree trace is the answer.
2. `LogisticAnswerScorer` — optional re-rank by learned P(correct), only when a model file is
   configured (`KbConfig.answerScorerModelPath`, JSON-serialized record, memoized load).

**The featurizer** (`AnswerFeatures`, 12-dim): per signal group {mean expectation, mean
uncertainty, present-flag} × {RETRIEVAL, ENGINE, TYPE, CONSISTENCY}. The present-flag design is
right: a missing signal is a first-class input, not a silent zero (absent → E=0, u=1, present=0).

**The trainer** (`LogisticRegressionTrainer`): pure-Java deterministic batch GD, cross-entropy +
L2, zero-init — no ND4J for 12 features, correct call.

**The harness** (`AnswerScorerTrainingHarness`) is the strongest piece: query-level held-out split
(no candidate leakage), and a THREE-way comparison per query — learned MRR vs retrieval-only MRR
vs **algebraic-fold MRR** — with `beatsFold()` as the explicit deploy gate ("ship the model only if
it beats the hand-coded fold"). Also recall@1/@3, nDCG@3, logLoss. This is exactly the
low-label-regime posture: fold as safe default, model must earn its place.

**Surfaces**: `POST /api/kb-grounding/synthesize` + `ask_graph_synthesize` MCP tool, both tested.
Training entry: `trainAnswerScorer(List<GoldenQA>)` (caller-supplied golden set only, today).

### Review notes (small, worth fixing while building on it)
- **N1** Doc/impl drift: Javadoc says "six signals"; the vector is 4 group-mean triples. Per-signal
  detail (text vs kge; psl vs mebn) is averaged away — see R1.
- **N2** KGE calibration circularity: per-batch `fit()` uses KB-supported candidates as positives,
  and KB support (verify) feeds the same ENGINE group the calibrated KGE joins. Document; prefer
  corrupted-triple negatives from the KGE trainer (plan WP6a) for the calibration set.
- **N3** Presentation contract: learned re-rank reorders the fold's answers but each answer keeps
  its fold Opinion — order and displayed likelihood can disagree. Attach the learned P(correct) as
  a visible "learned score" + a re-rank node in the trace.
- **N4** No calibration eval of the scorer output (logLoss only) — add ECE/reliability bins to the
  TrainingReport.
- **N5** One global model path — per-fact-sheet (per-domain) model with global fallback.

---

## 2. Implementation status vs the plan (working tree, verified on disk)

**Landed**: Opinion algebra (conjoin/discount/complement/conflict, Opinion.java:229-276) +
OpinionAlgebraTest; OpinionTree + operator semantics; KgeCalibration (+test, live in synthesis);
source-keyed promotion ledger (FactPromotionTracker srcKey + SourceKeyedPromotionTest); opinion-
preserving projection (+test); cascade hash skip (CascadeHashTest); subprocess empty-matrix guard
(MatrixGraphShellGuardTest); tabular ROW/key-entity work (TableCellGraphBuilder); WP12 synthesis
end-to-end (this doc §1); ProbabilisticContradictionDetector; TypeConstraintFolRuleCompiler
(standalone, NOT cascade-wired); PslHardRuleSemanticsTest (ArithmeticRule semantics; ADMM routing
not wired); EmbeddingPslEvidence clamps (+test).

**Landed but UNPLANNED (build on these)**:
- `GraphForecastService` (+controller +MCP tool): temporal fact series → least-squares trend →
  N-bucket projection — Part VII analytics arriving early.
- `HierarchicalAggregationService`: numeric roll-ups across parent/child entity hierarchies — the
  WP39 aggregation seed.
- `SourceWeightStore`: JSONL per-source learned trust weights — the trust-⊗ input the plan wanted.
- `TripleProposalStore`: human review queue for proposed triples — a LABEL SOURCE (see R2).
- `ExtractionJobStore`, `OccurredAtParser` (lenient multi-format), `InMemoryNamedGraphStore`,
  `GraphFocalViewController`, neural `NeuralScoreProvider` SPI.

**In flux** (staged tests deleted from disk — implementing agent mid-rewrite): FactDurabilityTest,
persistence/dual (DualStore*). **Absent still**: Perturb-and-MAP in cascade, propagation rule
templates, community-scoped solve, FactCorrectedEvent listener, KnowledgeDigest, KG rule mining,
canonical elections (WP31), process instances (Part VIII), SourceRef/DecisionTrace (Part IX),
ReferenceResolver/IdLeakAssert (Part X), question typology/measure model (most of Part VII).

---

## 3. Literature positioning (web survey 2026-07-02, ~40 papers/systems 2014–2026)

Six schools today: (1) LLM-prompted open extraction (GraphRAG, iText2KG, Docs2KG, AutoSchemaKG) —
no uncertainty, no reasoning; (2) ontology-guided extraction (ODKE+, KARMA) — schema as input, not
co-evolving; (3) probabilistic/uncertain KGs (Knowledge Vault 2014, DeepDive, UKGE 2019, BEUrRE)
— confidence but pre-LLM or embedding-only; (4) neurosymbolic engines (PSL/HL-MRF, NeuPSL
2022/23) — PSL+neural, never MEBN/OWL too; (5) GraphRAG/KG-RAG QA (GNN-RAG 2025 +8.9-15.5 F1) —
retrieval scoring, not reasoning-engine fusion; (6) enterprise/process-mining KGs (event knowledge
graphs, van der Aalst) — no LLM extraction, no confidence.

**Novelty verdict — greenfield (unpublished in the reviewed literature):**
- **G1** Subjective-logic b/d/u Opinion accumulation per source on LLM-extracted triples (Jøsang's
  algebra used in trust networks/IoT; zero KG-construction papers 2023–2025 apply it).
- **G2/G3** MEBN in a production KG pipeline at all; the four-engine cascade (PSL + MEBN + OWL-RL
  + KGE) co-trained online — no published system combines these.
- **G4 (the strongest claim, = this code)**: a learned scorer over features from multiple
  symbolic+neural REASONING engines (PSL soft-truth, MEBN posterior, KGE plausibility, opinion
  E/u, type-conformance, consistency) for answer ranking on auto-built enterprise KGs. Closest
  ancestor: **Knowledge Vault (KDD 2014)** — logistic regression fusing four TEXT-extractor
  confidences; its features are extraction features, not reasoning outputs, and it has no trace.
  A 2025 graph-reranking survey explicitly finds no system fusing symbolic scores + embeddings
  into a reranking feature vector.
- **G5** Canonical election AFTER the reasoning cascade (Vault elects at extraction time).

**Well-trodden (no claim)**: LLM extraction itself, PSL, KGE, OWL-RL, entity resolution.

**Import candidates from the field**: conformal prediction wrappers for coverage guarantees on
link prediction (2024); GNN-RAG-style path retrieval as an extra RETRIEVAL feature; span-level
attribution as table stakes for 2025 attribution benchmarks (= plan WP33e/WP46); box embeddings
(BEUrRE) for natively calibrated KGE confidence; standardized KGQA benchmarks (WebQSP/CWQ) for
credible ablations; minimal-revision inconsistency repair (Donatello 2025) for the election step.

Key refs: Knowledge Vault (research.google/pubs/knowledge-vault), NeuPSL (arxiv 2205.14268), UKGE
(AAAI'19), GNN-RAG (arxiv 2405.20139), Uncertainty-in-KG-Construction survey (TGDK 2025,
Dagstuhl), graph-reranking survey (arxiv 2503.14802), iText2KG (2409.03284), conformal-UKG
(2510.24754).

---

## 4. Build-on agenda (ordered)

**R1. Featurizer v2 (per-signal + subjective-logic + provenance features).** Replace group means
with per-signal features: {E, u, b, d, present} × {text, kge, psl, mebn, type, consistency} +
corroboration count, distinct-source count (WP3 ledger), source-trust mean (SourceWeightStore),
contradiction/conflict mass, retrieval margin, community features (cross-community corroboration
count), topology prior (pagerank percentile) ⇒ ~40 dims, still trivially LR-trainable. Keep
present-flags. This is the feature set the literature says is unpublished — v1 implements a
subset.

**R2. Label acquisition loops (the binding constraint is labels, not model capacity).**
(a) Promotion-ledger weak labels: later-corroborated-by-new-source ⇒ positive, later-contradicted/
retracted ⇒ negative (plan §5 — the ledger now exists); (b) `TripleProposalStore` human
accept/reject = extraction-level labels; (c) accepted/rejected answers logged from chat/synthesize
(needs a thumbs endpoint); (d) golden QA (exists). Weak-label training with the harness's
beatsFold gate makes this safe.

**R3. Scorer calibration + per-domain models.** ECE/reliability bins in TrainingReport; isotonic
post-hoc if miscalibrated; per-fact-sheet model path with global fallback (N5); persist
TrainingReport JSON next to the model (provenance for "why these weights").

**R4. Fix N2/N3** (calibration circularity documentation + corrupted-triple negatives; learned-
score presentation + re-rank trace node).

**R5. Model upgrade path — only after R1/R2 saturate LR**: small MLP behind the same `score()`
contract (Javadoc already anticipates it); THEN the research-grade step: score the OpinionTree
STRUCTURE (tree-LSTM/GNN over the operator tree) instead of a flattened vector — nobody has
published learned scoring over reasoning-trace structure; monotonicity constraints (score
monotone in engine E) preserve inspectability.

**R6. The paper.** The ablation table the harness already emits (retrieval-only vs algebraic fold
vs learned, MRR/recall@k/nDCG) IS the core experiment. Add: WebQSP/CWQ or an enterprise-messy-data
benchmark (FP&A fixture), the G1 opinion-accumulation story, and the trace-reproducibility
property (likelihood recomputable from the OpinionTree). Title-shaped claim: "calibrated answer
synthesis over multi-engine neurosymbolic reasoning signals on LLM-built enterprise knowledge
graphs." Knowledge Vault is the citation anchor; NeuPSL and GNN-RAG the contrasts.

**R7. Wire the two unplanned analytics services into Part VII** (GraphForecastService +
HierarchicalAggregationService are the WP38/39 seeds — connect to the measure model and the
AGGREGATE trace node when those land) and finish the in-flux Part III durability work before
training on production labels (labels must survive restarts).
