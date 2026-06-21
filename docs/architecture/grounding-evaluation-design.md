# Grounding Evaluation Design

**Date**: 2026-06-21  
**Status**: Design-only (no code, no Maven)  
**Scope**: How to measure whether the KB actually grounds LLM agents well and reduces hallucination.  
**Prerequisites**: The P0 grounding primitives are BUILT —
`KbVerifier`/`VerifyResult`, `ConjunctiveQueryEngine`, `ConcurrentFactStore`,
`DerivationTree` all live in
`kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/grounding/`
and are tested in `AgentGroundingPrimitivesTest`.

---

## 1. Why Evaluation Matters Now

The gap document (`docs/architecture/production-fol-kb-agent-grounding-gaps.md`)
identifies six critical missing pieces for agent grounding. P0 primitives have been
shipped. Before building P1 (recursive rules, subscribe) or P2 (production scale), we
need a feedback signal: **does the symbolic KB actually reduce hallucination compared to
text-injection GraphRAG?** Without a harness, every design choice is untethered.

The gap doc's key differentiator is exactly this: GraphRAG injects KB structure as *text*
into the LLM context (Microsoft GraphRAG paper: https://arxiv.org/abs/2404.16130,
Section 2 — "all KB structure is serialised to the prompt"); our `KbVerifier` provides a
**symbolic SUPPORTED/REFUTED/UNKNOWN verdict with calibrated confidence**. The eval
harness is the instrument that proves (or disproves) this matters.

---

## 2. Metrics

### 2.1 Verify Precision/Recall on Labeled Ground Truth

The core metric is a **three-class verdict accuracy** on a labeled dataset where each
(claim, label) pair has a ground-truth label ∈ {SUPPORTED, REFUTED, UNKNOWN}.

**Precision per class c:**

```
Precision(c) = TP(c) / (TP(c) + FP(c))
```

**Recall per class c:**

```
Recall(c) = TP(c) / (TP(c) + FN(c))
```

**Macro-F1:**

```
Macro-F1 = (1/3) * sum_c [ 2 * Precision(c) * Recall(c) / (Precision(c) + Recall(c)) ]
```

This is directly analogous to the **FEVER benchmark** (Thorne et al. NAACL 2018,
https://arxiv.org/abs/1803.05355) which evaluates fact verification across
{SUPPORTS, REFUTES, NOT ENOUGH INFO} using Label Accuracy (LA) and FEVER Score
(LA conditioned on evidence sentence recall). Dataset: 185,445 claims; original paper
dev/test = 3,333/class (9,999 total each); the FEVER Shared Task variant uses 6,666/class
— these must not be conflated when comparing numbers. Fleiss κ = 0.6841; best shared task
system (2018) FEVER score = 64.21%. For our purposes:
- LA → verdict accuracy (3-class)
- Evidence recall → `VerifyResult.evidence()` items matching the labeled supporting fact keys

**Target baselines** (from FEVER literature):
- Human performance: ~89% label accuracy
- Best shared task system (2018): 64.21% FEVER score
- Our initial target: ≥70% macro-F1 on synthetic graph (achievable given exact symbolic match);
  ≥55% on heterogeneous real-world KB (ambitious but realistic for PSL soft-truth)

**Threshold sensitivity**: `DefaultKbVerifier` accepts a configurable threshold (default
0.5). The harness must sweep threshold ∈ {0.3, 0.4, 0.5, 0.6, 0.7, 0.8} and report
precision/recall curves (PR curves) for SUPPORTED and REFUTED classes separately.

### 2.2 Hallucination Reduction Rate

**Definition**: given an LLM that produces N claims in a response, and a `KbVerifier`
that vetoes (replaces/redacts) claims labeled REFUTED before the response is finalized:

```
Hallucination Rate (no KB) = |claims labeled REFUTED or UNSUPPORTED| / N
Hallucination Rate (with KB) = |remaining REFUTED claims after veto| / N
Hallucination Reduction = 1 - (HRate_with / HRate_without)
```

**Measurement protocol** (adapted from Self-RAG, Asai et al. ICLR 2024,
https://arxiv.org/abs/2310.11511):
1. Generate LLM responses to a fixed query set (no KB grounding).
2. Decompose each response into atomic claims (FActScore decomposition, Min et al. 2023,
   https://arxiv.org/abs/2305.14251 — split on "and", "but", "because", one fact per
   sentence).
3. Label each atomic claim against the KB using `KbVerifier.verify(atomKey)`.
4. Record the label distribution (SUPPORTED/REFUTED/UNKNOWN).
5. Repeat with KB-grounded generation (agent calls `verify` before including a claim).
6. Compute `HallucinationReduction` as above.

**FActScore precision** (from Min et al. EMNLP 2023, https://arxiv.org/abs/2305.14251):
```
FActScore = |{claims where KB returns SUPPORTED}| / |total claims generated|
```
Higher is better. The KB baseline (text-only GraphRAG) produces claims that are
semantically plausible but often symbolically false; the symbolic `KbVerifier` should
increase FActScore by blocking REFUTED claims before emission.

### 2.3 Faithfulness / Citation Accuracy

**Faithfulness** measures whether the evidence the agent cites actually supports the claim.
Adapted from **ALCE** (Gao et al. 2023, https://arxiv.org/abs/2305.11441 — "Enabling
LLMs to Generate Text with Citations"):

```
Citation Precision = |claims where VerifyResult.evidence() ⊇ labeled supporting facts| / |SUPPORTED claims|
Citation Recall    = |labeled supporting facts actually in VerifyResult.evidence()| / |total labeled supporting facts|
```

`DerivationTree.allAtomKeys()` provides the full evidence chain — each atom key in the
tree is a potential citation. The harness checks whether the derivation tree contains
every gold-standard supporting fact from the labeled dataset.

**Evidence quality levels**:
- Level 0 (baseline): evidence list is non-empty (any evidence)
- Level 1 (citation): at least one evidence item is the directly supporting observed fact
- Level 2 (chain): full `DerivationTree` path from observation to derived claim matches
  gold standard chain

### 2.4 Query Correctness (Conjunctive Query Engine)

For `ConjunctiveQueryEngine.query(conjuncts, store)`:

```
Query Recall@k     = |correct answers in top-k results| / |total correct answers|
Query Precision@k  = |correct answers in top-k results| / k
MRR                = (1/|Q|) * sum_q [1 / rank_of_first_correct_answer(q)]
Hits@k             = |{q : correct answer in top-k}| / |Q|
```

These metrics are identical to the standard KGQA evaluation protocol used on
WebQuestions (https://worksheets.codalab.org/worksheets/0xba659fe363cb46e7a505c5b6a774dc8a),
MetaQA (Zhang et al. 2018, https://arxiv.org/abs/1709.04071 — 3-hop KG QA with 400K+
question-answer pairs), and GrailQA (Gu et al. 2021,
https://arxiv.org/abs/2011.07743 — compositional generalization, 64,331 SPARQL-like
questions over Freebase).

For our purposes: query the PSL-inferred `InferredFactStore` with patterns from the
labeled benchmark and measure Hits@1/3/10 and MRR against gold bindings.

### 2.5 Latency

All metrics are reported alongside latency percentiles (p50, p95, p99) measured in
milliseconds. The `DefaultKbVerifier` does O(1) InferredFactStore lookups; the target
is:

| Operation | p50 target | p99 target |
|---|---|---|
| `verify(atomKey)` — cache hit | < 1 ms | < 5 ms |
| `verify(atomKey)` — cold (full MAP) | < 500 ms | < 2000 ms |
| `query(2-conjunct)` on 100k facts | < 20 ms | < 100 ms |
| `DerivationTree.build()` depth=5 | < 10 ms | < 50 ms |

Latency is critical because Self-RAG and IRCoT require per-step KB interaction during
chain-of-thought generation (Trivedi et al. 2023, https://arxiv.org/abs/2212.10509 —
"Interleaving Retrieval with Chain-of-Thought Reasoning"). A 2-second verify call
makes per-step grounding unusable.

---

## 3. Benchmark Methodology

### 3.1 Dataset A: Synthetic from a Known Graph

**Construction protocol**:
1. Define a small domain graph with 500–1000 entities and 3000–5000 relations (e.g.,
   employment domain from `AgentGroundingPrimitivesTest`: persons, companies,
   subsidiaries, skills).
2. Load into `MutableReasoningGraph` + `FactStore`.
3. Run `FolInferenceService.inferFacts()` (PSL MAP solve) to materialize all derivable
   atoms.
4. Generate labeled triples: for each derived atom, label SUPPORTED; for each atom
   provably contradicted by a hard constraint, label REFUTED; for atoms with no
   supporting chain, label UNKNOWN.
5. Add adversarial negatives: take SUPPORTED atoms and swap one argument (e.g.,
   `isEmployedBy(Alice, Acme)` → `isEmployedBy(Alice, RivalCorp)`) — these should be
   labeled REFUTED or UNKNOWN, depending on what the KB knows about RivalCorp.

**Advantages of synthetic**: ground truth is exact (derived by the same symbolic engine);
zero annotation cost; reproducible; controllable for noise/sparsity.

**Size**: target 2000 labeled triples (1000 SUPPORTED, 500 REFUTED, 500 UNKNOWN) from
the synthetic graph. This is sufficient for statistical significance across the 3-class
problem and mirrors the FEVER dev set structure.

**File structure** (flat JSONL, no JPA, no Spring):
```
benchmark/synthetic/graph.json            -- the PortableNamedGraph
benchmark/synthetic/labeled-claims.jsonl  -- {atomKey, label, evidence_keys[]}
benchmark/synthetic/queries.jsonl         -- {id, conjuncts[], gold_bindings[]}
```

### 3.2 Dataset B: Public KGQA — FB15k-237

**FB15k-237** (Toutanova and Chen 2015,
https://www.microsoft.com/en-us/research/publication/observed-versus-latent-features-for-knowledge-base-and-text-inference/)
is the canonical KGC/KGQA benchmark: 237 relation types, 14,541 entities, 310,116
training triples, 17,535 test triples. The task: given a graph subset, predict held-out
edges.

**Adaptation to our harness**:
1. Load a 10k-triple FB15k-237 subset into `MutableReasoningGraph`.
2. Run PSL inference with generic transitivity + inverse rules.
3. Held-out test triples → SUPPORTED labels; corrupted triples (random head/tail swap) →
   REFUTED candidates.
4. Evaluate `KbVerifier.verify()` on the held-out set; compute Hits@1/3/10, MRR.

**Why FB15k-237 and not WebQuestions**: FB15k-237 is purely graph-structural (no NL
question → SPARQL step needed), which isolates the KB verification quality from the
NL-parsing quality. NL-grounded benchmarks (WebQuestions, GrailQA) belong in Phase 2
after the NL→atom parser is built.

**Alternative for relational complexity**: **MetaQA** (https://arxiv.org/abs/1709.04071)
tests 1-hop, 2-hop, 3-hop chains on a movie KG. The 3-hop subset is a direct test of
`ConjunctiveQueryEngine`'s multi-conjunct join depth.

### 3.3 Dataset C: FActScore-Style Hallucination Set

To measure hallucination reduction in LLM generation (section 2.2), we need:
1. A fixed set of 100 factual questions answered by an LLM without KB (GPT-4, Claude 3.5,
   or any model accessible via the kompile CLI).
2. Each answer decomposed into atomic claims (automated: one sentence per claim, split
   compound sentences).
3. Each claim manually or semi-automatically labeled against the synthetic graph KB.
4. Stored as:
   ```
   benchmark/hallucination/questions.jsonl  -- {id, question, reference_entities[]}
   benchmark/hallucination/llm-answers.jsonl -- {id, model, answer, claims[]}
   benchmark/hallucination/claim-labels.jsonl -- {id, claim, atomKey, label, evidence_keys[]}
   ```

This dataset enables the core hallucination-reduction experiment: LLM without KB vs LLM
with KB veto (where veto = `KbVerifier.verify()` returning REFUTED blocks the claim).

---

## 4. Symbolic KB vs Text-Injection GraphRAG Baseline

This is the key differentiator from the gap doc (Section C.1):

> "GraphRAG injects all KB structure as *text into the LLM context window* — it does NOT
> expose a symbolic query interface."

### 4.1 Defining the Baseline

**GraphRAG baseline** (the `GraphRagQuery`-based system at
`kompile-app/kompile-app-parent/kompile-app-core/src/main/java/ai/kompile/core/graphrag/query/GraphRagQuery.java`):
- Takes a `SearchType` (LOCAL, GLOBAL, HYBRID) and returns text passages.
- The LLM receives the retrieved text passages and generates a response.
- No symbolic verify step; the LLM is free to generate any claim.

**Symbolic KB system** (the P0 primitives):
- Before including a claim in a response, the agent calls `KbVerifier.verify(atomKey)`.
- REFUTED claims are vetoed or flagged.
- The `DerivationTree` provides the exact evidence chain as a citation.

### 4.2 Controlled A/B Experiment Protocol

For each query in the benchmark:
1. **Condition A (GraphRAG only)**: run `GraphRagQuery` with `SearchType.HYBRID`, k=10,
   hopDepth=2. Feed retrieved text to the LLM. Collect output claims.
2. **Condition B (Symbolic KB only)**: run `ConjunctiveQueryEngine.query()` to retrieve
   answer candidates. Filter by `KbVerifier.verify()` threshold. Return only SUPPORTED
   atoms.
3. **Condition C (Hybrid — our target)**: use GraphRAG to retrieve candidate entity
   context, then pass candidate claims through `KbVerifier.verify()` before emission.
   REFUTED candidates are dropped; UNKNOWN candidates are flagged with low confidence.

**Metrics to compare across A/B/C**:
- FActScore (section 2.2) — higher is better
- Citation Precision/Recall (section 2.3) — higher is better
- Hallucination Rate (section 2.2) — lower is better
- Hits@1 on KGQA queries (section 2.4) — higher is better
- p95 latency (section 2.5) — lower is better

**Expected hypothesis**: Condition C (hybrid) outperforms A on FActScore and
hallucination rate with acceptable latency overhead, while B (symbolic only) is
highest-precision but lowest-recall (UNKNOWN rate is high when the KB is sparse).

### 4.3 Specific Measurable Gap: REFUTED Claim Survival Rate

The most concrete differentiator: after KB veto, how many REFUTED claims survive into
the final LLM output?

```
REFUTED Survival Rate = |claims labeled REFUTED that appear in final output| / |total claims labeled REFUTED|
```

GraphRAG baseline (Condition A): high REFUTED survival rate (LLM generates plausible but
false claims unchecked). Symbolic KB (Condition B/C): near-zero REFUTED survival rate by
construction (veto is synchronous before emission). This is the single most
decision-useful number for whether the KB grounding system is doing its job.

---

## 5. Lib-Level Test Harness vs Client/Offline Eval

### 5.1 Lib-Level Test Harness (JUnit, infra-free)

**Scope**: tests that run with `mvn test` in `kompile-graph-reasoning` module, no Spring,
no external services, deterministic.

**What to test at lib level**:
- `KbVerifier` precision/recall on the synthetic labeled set (Dataset A) — this is a pure
  Java test: load graph, run PSL inference, call `verify()`, compare to labels.
- `ConjunctiveQueryEngine` Hits@1/MRR on the synthetic query set.
- `DerivationTree` citation recall: for each SUPPORTED atom, verify that
  `DerivationTree.allAtomKeys()` contains the labeled supporting fact keys.
- Threshold sweep: precision/recall curves for threshold ∈ {0.3..0.8}.
- Latency micro-benchmarks: `verify()` on 1000 random atoms from a 10k-fact store.

**Implementation hint**: `AgentGroundingPrimitivesTest` already provides the fixture
pattern (build PSL program, run inference, populate `InMemoryInferredFactStore`). The
eval harness is a parallel test class `GroundingEvalHarness` that:
1. Loads `benchmark/synthetic/labeled-claims.jsonl` as a classpath resource.
2. Iterates through labeled claims, calls `verifier.verify(atomKey)`.
3. Accumulates per-class TP/FP/FN.
4. Asserts Macro-F1 >= threshold (e.g., 0.70 for synthetic).

**Not at lib level**: LLM calls, hallucination rate measurement, the GraphRAG baseline
(those require the Spring app context and an LLM endpoint).

### 5.2 Client/Offline Eval (Spring + CLI)

**Scope**: runs against a live kompile instance (or a recorded fixture). Spring context
is loaded; LLM can be mocked or real.

**What to test at client level**:
- Full hallucination reduction experiment (section 2.2/section 4.2): requires LLM
  generation.
- FActScore on real LLM outputs: requires claim decomposition (an LLM step itself, or a
  rule-based splitter).
- GraphRAG vs symbolic KB A/B comparison: requires `GraphRagQuery` endpoint.
- End-to-end latency (wall-clock): requires a running server.

**Implementation**: a standalone `GroundingEvalCli` command (could be a Spring Boot
`ApplicationRunner` or a `kompile eval grounding` CLI subcommand) that:
1. Reads benchmark JSONL files.
2. Runs both GraphRAG and symbolic KB paths.
3. Outputs a Markdown/JSON results report.

This intentionally does NOT need to be part of the library or the main app — it is an
offline evaluation tool used by developers to gate P1/P2 work.

### 5.3 Separation Summary

| Concern | Location | Spring required | LLM required |
|---|---|---|---|
| Verify precision/recall (synthetic) | lib JUnit | No | No |
| Query Hits@MRR (synthetic) | lib JUnit | No | No |
| Citation recall (DerivationTree) | lib JUnit | No | No |
| Latency micro-benchmarks | lib JUnit | No | No |
| Hallucination rate (LLM outputs) | client offline eval | Yes | Yes |
| GraphRAG A/B comparison | client offline eval | Yes | Optional (mock LLM OK) |
| End-to-end wall-clock latency | client offline eval | Yes | No |

---

## 6. Candidate Benchmark Datasets (Summary)

| Dataset | Source | Size | Task | Relevant metric |
|---|---|---|---|---|
| Synthetic Employment Graph | Built from `AgentGroundingPrimitivesTest` domain | 2000 labeled claims | Verify precision/recall | Macro-F1 |
| FB15k-237 subset | https://www.microsoft.com/en-us/research/publication/observed-versus-latent-features | 10k triples | Link prediction → verify | Hits@1/3/10, MRR |
| MetaQA 3-hop | https://arxiv.org/abs/1709.04071 | ~26k 3-hop Q&A | Multi-hop conjunctive query | Hits@1 |
| FActScore wiki claims | https://arxiv.org/abs/2305.14251 | 500 atomic facts | Hallucination rate | FActScore precision |
| FEVER dev subset | https://arxiv.org/abs/1803.05355 | 19,998 claims | SUPPORTED/REFUTED/NEI | Label Accuracy, FEVER Score |

**Which to build first**: the Synthetic Employment Graph is zero-cost and already 80%
specified by `AgentGroundingPrimitivesTest`. Build it first (2–3 days of benchmark
construction). FB15k-237 is the second priority (well-understood, loader is ~200 lines).
FActScore-style is highest-value for the LLM-hallucination story but requires the most
manual annotation work.

---

## 7. Open Questions

**Q1 (Critical — threshold calibration)**: The `DefaultKbVerifier.DEFAULT_THRESHOLD = 0.5`
is arbitrary. PSL soft-truth [0,1] MAP values are not calibrated probabilities — a value
of 0.5 from an ADMM MAP solver does not mean 50% confidence. The calibration question is:
**what threshold on `InferredFact.confidence()` maximizes Macro-F1 on the synthetic
labeled set?** This cannot be determined without running the eval harness. Until
calibrated, all hallucination-rate numbers are threshold-sensitive.

**Q2 (GraphRAG baseline gap)**: `GraphRagQuery` returns text passages, not atom keys.
The A/B comparison requires mapping retrieved passages back to atom keys to compute
REFUTED claim survival rate. This NL→atom mapping step is either (a) an LLM call (noisy)
or (b) a string-matching heuristic against `InferredFactStore` predicates. Neither is
clean. A third option is to use `ConjunctiveQueryEngine` to check whether any inferred
atom subsumes the passage — but this requires the NL→atom translation that is
explicitly out of scope for the lib.

**Q3 (Open-world assumption)**: `VerifyResult.Status.UNKNOWN` conflates two different
cases: (a) the claim is genuinely false but not yet in the KB (CWA: should be REFUTED);
(b) the claim is true but not yet derived (OWA: should be SUPPORTED with low confidence).
The harness labels need to distinguish CWA-UNKNOWN from OWA-UNKNOWN. For the synthetic
graph (where we control all facts) this is resolvable; for real-world KB queries it is
not without additional information about KB completeness.

**Q4 (Recursive inference gap)**: FB15k-237 has multi-hop chains (e.g., transitiveRole
over 3+ hops). `FolInferenceService` caps pair-wise grounding at 10,000 pairs (gap doc,
`FolInferenceService.java:310`) and has no fixpoint recursion. This means FB15k-237
transitive queries will produce UNKNOWN instead of SUPPORTED for deep chains — the
benchmark will under-estimate the symbolic KB's true capability until P1-1 (recursive
Datalog) is built.

**Q5 (Latency of MAP re-inference)**: The fast path (`DefaultKbVerifier`) is O(1) on
the materialized `InferredFactStore`. But the `InferredFactStore` is only populated after
a full batch MAP solve (`HlMrfMapInference.solve()`). The latency test for "cold verify"
(atom not yet materialized) triggers a full batch re-solve — potentially 500ms+ for large
programs. The harness should separately measure warm (materialized) and cold (re-inference
required) latency, and the warm path is what matters for per-step agent grounding.

**Q6 (FEVER-score evidence sentence alignment)**: FEVER's secondary metric (evidence
sentence recall) maps directly to `VerifyResult.evidence()` / `DerivationTree`. But FEVER
evidence is Wikipedia sentences; our evidence is atom keys (`"worksAt(Alice, AcmeNYC)"`).
There is no natural NL alignment. If we want to compare against FEVER systems directly,
we need a verbalization step (`Logic→NL` from the gap doc, Part C.5). Currently
unimplemented in the lib.

---

## 8. Citations

1. **Asai et al. — "Self-RAG: Learning to Retrieve, Generate, and Critique" (ICLR 2024)**  
   https://arxiv.org/abs/2310.11511  
   _Establishes the per-step SUPPORTED/REFUTED/UNKNOWN verification loop during
   chain-of-thought generation. Defines four inline reflection tokens (Retrieve/ISREL/
   ISSUP/ISUSE); removing ISSUP ("fully supported / partially supported / no support")
   drops ASQA str-EM from 32.1→18.1 (−14 pts). FActScore: Self-RAG 7B = 81.2 vs.
   Ret-Llama2-chat 73.1. Primary justification for why `verify()` must be sub-100ms._

2. **Min et al. — "FActScoring: Fine-grained Atomic Evaluation of Factual Precision in
   Long Form Text Generation" (EMNLP 2023)**  
   https://arxiv.org/abs/2305.14251  
   _Defines atomic claim decomposition (ChatGPT: avg 4.4 facts/sentence; InstructGPT: 4.24
   — figures are model-specific, not universal) and FActScore precision metric. Critical
   finding: 40% of sentences contain a mix of supported and unsupported facts, making
   sentence-level citation checks insufficient. Human-written Wikipedia ≈88% upper bound.
   The decomposition protocol is directly reusable for our hallucination-rate measurement._

3. **Thorne et al. — "FEVER: A Large-Scale Dataset for Fact Extraction and VERification"
   (NAACL 2018)**  
   https://arxiv.org/abs/1803.05355  
   _The canonical 3-class fact verification benchmark (SUPPORTS/REFUTES/NOT ENOUGH INFO,
   185,445 claims; original paper dev/test = 3,333/class each; FEVER Shared Task variant
   = 6,666/class — note this distinction when comparing systems). Defines Label Accuracy
   and FEVER Score (LA conditioned on evidence sentence recall). The 3-class structure maps
   directly to our {SUPPORTED, REFUTED, UNKNOWN}. Fleiss κ = 0.6841; best shared task
   system (2018) FEVER score = 64.21%._

4. **Edge, Trinh, et al. — "From Local to Global: A Graph RAG Approach to Query-Focused
   Summarization" (2024)**  
   https://arxiv.org/abs/2404.16130  
   _Microsoft GraphRAG paper: establishes that all KB structure is serialized to the LLM
   prompt (text injection), not symbolic verification. Evaluation is LLM-as-judge pairwise
   win rates (Comprehensiveness/Diversity/Empowerment) — no ROUGE, no BERTScore, no
   symbolic correctness metric. GraphRAG C0 wins 72–83% on comprehensiveness vs. semantic
   RAG. Caution: 2025 analysis (arXiv:2506.06331) shows position bias alone inflates
   LLM-judge win rates by 30%+. FinReflectKG (arXiv:2603.20252) found 32% of GraphRAG
   outputs hallucinated on financial QA. Fundamental motivation for section 4 A/B._

5. **Gao et al. — "Enabling LLMs to Generate Text with Citations" (ALCE, 2023)**  
   https://arxiv.org/abs/2305.11441  
   _Defines Citation Precision and Citation Recall for grounded generation; the exact
   formulas used in section 2.3. Shows that retrieval-augmented systems frequently cite
   irrelevant passages (low citation precision), motivating the symbolic evidence chain
   (`DerivationTree`) as a citation source._

6. **Toutanova and Chen — "Observed vs Latent Features for Knowledge Base and Text
   Inference" (2015)**  
   https://www.microsoft.com/en-us/research/publication/observed-versus-latent-features-for-knowledge-base-and-text-inference/  
   _FB15k-237 benchmark: 237 relation types, 14,541 entities, 310,116 triples. The
   standard KGQA link-prediction benchmark with Hits@k/MRR as the evaluation protocol.
   The subset used in section 3.2 is the most widely-reproduced KB-completion benchmark._

7. **Zhang et al. — "Variational Reasoning for Question Answering with Knowledge Graphs"
   (MetaQA, 2018)**  
   https://arxiv.org/abs/1709.04071  
   _MetaQA movie KG benchmark: 1-/2-/3-hop questions (407,513 total). The 3-hop subset
   directly exercises `ConjunctiveQueryEngine`'s multi-conjunct join and is the cleanest
   available test of multi-hop query correctness without NL parsing._

8. **Trivedi et al. — "Interleaving Retrieval with Chain-of-Thought Reasoning for
   Knowledge-Intensive Multi-Step Questions" (IRCoT, 2023)**  
   https://arxiv.org/abs/2212.10509  
   _IRCoT shows that KB interaction must be per-step during chain-of-thought, not
   one-shot at query time. Used to justify the p95 latency targets in section 2.5._

9. **Pan et al. — "Unifying Large Language Models and Knowledge Graphs: A Roadmap"
   (2023)**  
   https://arxiv.org/abs/2306.08302  
   _Survey; section 4.3 identifies KB-grounded generation as requiring both a
   fast-path fact lookup and an evidence serialization format. Confirms that
   `VerifyResult.evidence()` + `DerivationTree.toJson()` are the right seam points for
   agent-KB integration._

10. **2024–2025 empirical KB-grounding vs. hallucination papers** (selected):
    - Luo et al. "GCR" (2024) https://arxiv.org/abs/2410.13080 — KG-Trie constraints:
      100% faithful reasoning path ratio on WebQSP+CWQ vs. 48.1% unconstrained.
    - Luo et al. "RoG" (ICLR 2024) https://arxiv.org/abs/2310.01061 — ChatGPT KGQA:
      66.8% → 85.7% (+18.9 pp) with KG augmentation.
    - MultiHal (2025) https://arxiv.org/abs/2505.14101 — KG-RAG vs. vanilla QA: NLI
      entailment +16–36 pp; HHEM hallucination consistency +29–42 pp (25,905 paths).
    - FinReflectKG https://arxiv.org/abs/2603.20252 — 32% GraphRAG outputs hallucinated on
      SEC 10-K financial QA; LLM-judge hallucination detectors degrade 59% MCC when KG
      triplets are present (embedding-based detectors only 9% degradation).
    _Together these establish that symbolic KB constraints reliably reduce hallucination
    by 15–40 pp vs. unconstrained generation, but LLM-as-judge evaluation of GraphRAG is
    unreliable due to position bias. Use precision/recall/F1 on labeled claims, not
    LLM-judge win rates._

---

## 9. Appendix: Metric Quick-Reference

```
Macro-F1(verify)  = mean_c F1(c)  for c ∈ {SUPPORTED, REFUTED, UNKNOWN}
FActScore         = |SUPPORTED claims| / |total claims generated by LLM|
Halluc. Reduction = 1 - HRate(with KB) / HRate(without KB)
Citation Prec     = |SUPPORTED claims with correct evidence| / |SUPPORTED claims|
Citation Recall   = |gold evidence atoms in VerifyResult.evidence()| / |gold evidence atoms|
Hits@k            = |queries with correct answer in top-k| / |total queries|
MRR               = mean over queries of [1 / rank_of_first_correct_answer]
REFUTED Survival  = |REFUTED claims in final LLM output| / |total REFUTED claims|
```

All metrics except latency are dimensionless and in [0, 1]. Higher is better except
Hallucination Rate and REFUTED Survival Rate (lower is better).
