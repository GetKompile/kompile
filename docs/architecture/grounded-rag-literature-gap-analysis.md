# Grounded-RAG Literature Review & Kompile Gap Analysis

*2026-07-02. Produced by a deep-research pass (26 sources fetched, 125 claims extracted, 25
adversarially verified: 9 confirmed / 16 killed) crossed with a code-level sweep of kompile's
QA/grounding stack. A session limit killed 11 of the final verification agents, so 5 claims
(GRACE reward mechanics, FAIR-RAG SEA, one CoVe restatement) died by verifier abstention —
"unverified", not "false". Core findings all carried full or majority adversarial votes.*

---

## 1. What the 2023–2026 literature actually establishes (verified findings only)

**F1 — Two distinct grounding failure modes (HIGH, 3-0 / 2-0).**
Hallucination remains unsolved, and grounded-QA systems fail in two structurally different ways:
(a) producing *correct-looking answers without explicit evidence anchors*, and (b) *fabricating
when retrieved context is insufficient to answer*. (CoVe, ACL Findings 2024; GRACE, arXiv
2601.04525.) A grounding architecture must address both: anchor every claim, and know when to
abstain.

**F2 — Naive top-k vector RAG has a hard ceiling on corpus-wide questions (HIGH, 3-0).**
Top-k retrieval cannot aggregate across a corpus, so "what are the main themes"-class questions
structurally fail (best naive baseline 1.51 F1 vs 6.63 F1 for global-aware methods on a 2025
benchmark). This is the *one* graph-RAG-adjacent claim that survived verification. (Microsoft
GraphRAG, arXiv 2404.16130.)

**F3 — Retrieval-quality gating + corrective retrieval works, and the gate should be small
(HIGH, 2-1×3, plus a 2026 independent reproduction).**
CRAG grades the retrieval set into Correct / Incorrect / Ambiguous and acts on the grade
(refine / re-retrieve+web-search / both), gaining +7.0% (PopQA) to +36.6% (PubHealth) over
standard RAG. Its 0.77B fine-tuned T5 evaluator classifies retrieval quality at 84.3% vs 58–65%
for zero/few-shot ChatGPT — a small tuned grader beats a large general LLM as the gate.
(ICLR 2024, arXiv 2401.15884.)

**F4 — Deciding *whether* to retrieve matters (MEDIUM, 2-1×2).**
Self-RAG's ablation shows ~40–46% relative drop on PopQA without retrieval; CoRAG (2026 preprint)
shows cooperative retrieval/generation training generalizing across datasets. Treated as
directional, not load-bearing (preprint + author-reported numbers).

**F5 — Post-generation verification must be independent of the draft (HIGH, 3-0).**
Chain-of-Verification reduces hallucination by planning verification questions about the draft
and answering them *without the draft in context*, so verification isn't biased into confirming
the hallucination. (Meta AI, ACL Findings 2024.)

**Synthesis:** no single technique covers both failure modes. The production state of the art is
a *composed pipeline*: (1) retrieve-or-not decision → (2) retrieval-quality gate → (3) corrective
retrieval loop → (4) generation under a citation contract → (5) independent post-generation claim
verification → (6) abstention anywhere evidence is insufficient — with continuous groundedness
evaluation (RAGAS-family: faithfulness, answer/context relevancy; FActScore) as observability.

## 1b. What did NOT survive adversarial verification — and why it matters

- **Every headline graph-RAG performance claim was refuted (0-3 votes each):** GraphRAG's 72–83%
  comprehensiveness win rates, HippoRAG's "+20% over ColBERTv2" multi-hop gains, PPR-vs-IRCOT
  cost claims, GroundedKG-RAG's ROUGE margins. Verifiers found non-reproducible figures and
  baseline-validity problems. 16 of 25 claims died — benchmark inflation is endemic in this
  literature.
- **Implication for kompile:** the empirically safe justification for the knowledge-graph
  investment is (a) the verified naive-RAG ceiling on global/corpus questions (F2) and (b) the
  thing a KB uniquely enables — *checkable* answers (F5 with a real oracle). Do **not** plan
  around assumed multi-hop factoid win rates; measure them in-house (see Gap 5).
- GRACE (evidence-sufficiency + abstention RL) and FAIR-RAG (structured evidence assessment)
  specifics went unverified due to the session-limit abstentions — directionally interesting,
  cite with care.

---

## 2. Kompile today (code-level map, verified 2026-07-02)

Two QA pipelines exist and are **not unified**:

- **Pipeline A — `AgentChatService`** (primary web-UI path, `kompile-app-main/.../agent/AgentChatService.java`):
  pattern-based `DefaultQueryProcessor` → `buildPromptWithSources()` — graph reasoning
  (CAUSAL/PROBABILISTIC via `GraphReasoningRetriever.retrieveWithTrail`) or GraphRAG
  (`LOCAL|GLOBAL|HYBRID` via `MatrixGraphRagService`) plus vector + keyword retrieval → numbered
  2000-char doc contexts in prompt → CLI subprocess → SSE (`sources`, `rag_metrics`,
  `reasoning_trace`, `chunk`…).
- **Pipeline B — `KompileRagOrchestratorImpl`** (`kompile-app-rag`): the *architecturally correct*
  7-step flow with `FilterChain` hooks (PRE_RETRIEVAL / POST_RETRIEVAL / PRE_LLM / POST_LLM) where
  guardrails and query transformers adapt in — but it is not the UI path.

**Present and live:** PPR + PathRAG augmentation in HYBRID (`MatrixGraphRagService.java:477,583`,
`MatrixGraphAlgorithms.java:342` — an earlier sweep wrongly reported these absent; verified
present, test-pinned), GLOBAL community summaries (Louvain + `CommunitySummarizer`), KGE
retriever, grounded-confidence re-ranking (`kbGroundedConfidenceWeight`=0.2 additive), rich
source cards with `CitationDto` provenance (chunk-level, `basisType`, `crawlRunId`), reasoning
trails over SSE, `ask_graph_*` MCP tools + `kb_verify_explain` (agent-invoked), FOL
`DefaultKbVerifier` (SUPPORTED/REFUTED/UNKNOWN), `BatchVerifyController`, Opinion/Beta-MAP
confidence + calibration + strength bands.

**Built but dormant (default-off or wrong pipeline):** contextual retrieval
(`ContextualRagConfig.enabled=false`, faithful Anthropic-style implementation),
9-evaluator RAGAS-equivalent suite (`EvaluationProperties.enabled=false`, every evaluator
false, no auto-trigger, no golden set, no recall@k/MRR), `HallucinationGuardrail`
(`GuardrailsProperties.enabled=false`, Pipeline-B only), HyDE / MultiQuery / StepBack /
Expansion / Compressing transformers (not in the live path), cross-encoder + MMR + RRF + RM3 +
Rocchio + BM25-PRF rerankers (inside Anserini path only), freshness scoring (`enabled=false`,
soft blend only), `webSearchFallbackThreshold=0.0` placeholder with nothing behind it,
`DefaultReActAgentService` (bypassed by the UI chat), contradiction detection
(maintenance-time only).

**Absent:** abstention/"insufficient evidence" path of any kind, retrieval-set sufficiency
scoring, corrective re-retrieval loop, automatic post-generation claim verification, inline
citation markers / answer-sentence↔source alignment, answer-level confidence, sub-question
decomposition (IRCOT/FLARE-style), golden QA dataset.

---

## 3. Gap analysis — literature mechanism × kompile status

| # | Literature mechanism (finding) | Kompile status | Gap severity |
|---|---|---|---|
| 1 | Independent post-generation claim verification (F5) | All primitives exist (KB verify, batch verify, calibrated confidence) but only agent-invoked; nothing extracts claims from answers and checks them automatically | **P0 — highest leverage** |
| 2 | Abstention when evidence insufficient (F1b) | No path at all; static similarity threshold only | **P0** |
| 3 | Retrieval-quality gate + corrective loop (F3) | No grader, no corrective actions; parts (rerankers, transformers, web-search flag) all exist disconnected | **P1** |
| 4 | Citation contract: claims anchored to evidence (F1a) | Side-car source cards only; answer text has no anchors; no alignment check | **P1** |
| 5 | Continuous groundedness evaluation (RAGAS/FActScore family) | Full evaluator suite built, 100% disabled, never auto-runs, no golden set, no retrieval metrics | **P1 — prerequisite for validating 1–3** |
| 6 | Query understanding & multi-hop decomposition | Transformers built-not-wired; pattern-based rewriter only; no decomposition planner despite `ConjunctiveQueryEngine` being a natural target | P2 |
| 7 | Answer-level calibrated confidence + query-time contradiction surfacing | Per-fact/per-source only; contradictions maintenance-time (and JPA-only, dead on matrix store) | P2 |
| 8 | One composed pipeline of gates/verifiers (synthesis) | The machinery lives on Pipeline B's filter chain; the UI uses Pipeline A which bypasses it | P2 structural |
| 9 | Adaptive retrieve-or-not (F4) | Always retrieves when enabled | P3 |
| — | Global/corpus-wide questions (F2) | **Covered**: GLOBAL mode + community summaries (with the known "communities unfed to reasoning" caveat from the reasoning-math audit) | strength |
| — | Contextual retrieval (Anthropic-style) | **Built**, default-off — enable + measure, don't build | quick win |

### The meta-gap

Kompile's problem is **composition and activation, not missing components**. Nearly every
mechanism the verified literature prescribes exists in the codebase — disabled by default,
attached to the non-UI pipeline, or waiting on an agent to voluntarily call it. The literature's
central verified lesson (F1+F3+F5: grounding = *composed* gating, correction, and verification
layers) is exactly the part kompile hasn't done: there is no point in the live answer path where
retrieval quality, evidence sufficiency, or answer faithfulness is ever *checked* automatically.

### Kompile's structural advantage the literature doesn't have

CoVe verifies claims against the same parametric model that hallucinated them. Kompile can verify
against an **explicit KB with FOL grounding, calibrated confidence, and derivation trees** — a
strictly stronger oracle wherever the KB covers the domain, and one that satisfies CoVe's
independence constraint by construction (a KB lookup never sees the draft). No verified system in
this review closes the loop LLM-answer → per-claim KB verdict → annotated/corrected answer.
Kompile is unusually well positioned to ship that; it is Gap 1.

---

## 4. Recommendations (ranked)

1. **P0 — Grounded-answer verification loop** (Gap 1). Post-LLM stage in `AgentChatService`:
   extract factual claims from the answer (existing `LlmJsonExtractor` machinery) → map to
   atoms/entities → `BatchVerify` → emit per-claim SUPPORTED/REFUTED/UNKNOWN verdicts on the
   existing `reasoning_trace`/`sources` SSE channel → policy: caveat or redact REFUTED, mark
   UNKNOWN as ungrounded. This is CoVe-with-a-real-oracle and reuses ~all existing parts.
2. **P0 — Evidence-sufficiency gate + abstention** (Gap 2). Score the retrieval set (top-k score
   distribution + query-entity coverage + grounded confidence + Opinion uncertainty); below
   threshold → answer "insufficient evidence" (with what *was* found) instead of generating, or
   trigger recommendation 3. Cheap, addresses failure mode F1b directly.
3. **P1 — CRAG-style corrective actions** (Gap 3). Grade retrieval with a *small* scorer (the
   existing cross-encoder rerankers, not an LLM judge — F3's T5 lesson); Ambiguous → HyDE/
   MultiQuery rewrite + re-retrieve, escalate LOCAL→HYBRID→GLOBAL; Incorrect → abstain or make
   `webSearchFallbackThreshold` real.
4. **P1 — Citation contract** (Gap 4). Prompt-contract inline `[n]` markers keyed to the already-
   numbered context docs + post-hoc sentence↔source alignment; unattributed factual sentences
   feed recommendation 1. UI already renders source cards — link markers to them.
5. **P1 — Turn evaluation on** (Gap 5). Async sampled scoring of live traffic (faithfulness,
   hallucination, context relevancy — already `async=true` capable), a golden QA set per corpus
   (FP&A first), retrieval recall@k/MRR. Given the 64% claim-kill-rate in the literature itself,
   in-house measurement is the only trustworthy arbiter of 1–4 (and of the graph-vs-dense
   question the review left open).
6. **P2 —** wire the query transformers + decomposition via `ConjunctiveQueryEngine` (Gap 6);
   answer-level confidence + query-time contradiction surfacing (Gap 7); converge Pipeline A onto
   the filter-chain architecture (Gap 8); enable contextual retrieval and measure (quick win).

**Interaction with existing plans:** WP33a (P0 extraction JSON-alias bug → silent empty graphs)
directly undermines every grounding mechanism above — an empty graph verifies nothing; it stays
first in line. The reasoning-math plan's stale-fact filtering and EXTRACTED-edge dedup (WP list,
Parts I–VII) are evidence-quality prerequisites for trustworthy verdicts in recommendation 1.

---

## 5. Sources

Primary (claims verified against): Microsoft GraphRAG arXiv:2404.16130 · HippoRAG
arXiv:2405.14831 · CRAG arXiv:2401.15884 (OpenReview JnWJbrnaUE; 2026 reproduction
arXiv:2603.16169) · Self-RAG arXiv:2310.11511 (selfrag.github.io) · CoVe ACL Findings 2024
(arXiv:2309.11495) · CoRAG arXiv:2602.18734 (preprint) · GRACE arXiv:2601.04525 (preprint,
partially unverified) · FAIR-RAG arXiv:2510.22344 (unverified) · GraphRAG-vs-dense benchmark
arXiv:2604.09666 (claims refuted) · GroundedKG-RAG arXiv:2604.04359 (claims refuted) · eval
benchmarks arXiv:2505.04847, arXiv:2605.11330, arXiv:2601.04196 · production
citation/attribution arXiv:2606.07130, arXiv:2605.06635, arXiv:2604.03173. Surveys:
arXiv:2506.00054, 2407.13193, 2510.15253, 2412.17558, Springer 10.1007/s10462-025-11454-w.

*Verification transparency: 25 claims adversarially verified (3 skeptic votes each, ≥2 refutes
kill). 9 confirmed, 16 killed — of which 5 killed by abstention when a session limit stopped 11
verifier agents (GRACE/FAIR-RAG/CoVe-restatement claims). Refuted-claim list preserved in the
workflow output (`wf_d4b5f378-004`).*
