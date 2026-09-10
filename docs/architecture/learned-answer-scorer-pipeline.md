# Learned Answer-Scorer Pipeline

Status: **scorer stack built + integrated** (`AnswerFeatures`, `LogisticAnswerScorer`,
`LogisticRegressionTrainer` in `kompile-graph-reasoning`; re-rank wired in `AnswerSynthesisService`
behind `kbAnswerScorerModelPath`). What's missing is the **data path** that produces a trained model.
This spec is that path.

## The idea

The WP12 synthesize pipeline already grades each candidate answer with six calibrated signals
(ω_text, ω_kge, ω_type, ω_psl, ω_mebn, ω_cons) and fuses them with a *fixed* subjective-logic fold.
A **small learned model** replaces the fixed fold: the six signals become a feature vector, and a
logistic model learns — from labeled `(question, candidate, correct?)` data — how much each signal is
worth. Output is a calibrated `P(correct)` used to re-rank. The algebraic fold stays as the explainable
trace; the model only decides ordering.

Why logistic regression first: needs few labels, cannot overfit 12 features, weights are inspectable
("KB verification is worth 3× retrieval"). It is a drop-in for a small MLP behind the same
`LogisticAnswerScorer.score()` contract once labels are plentiful.

## Label sources

1. **Golden QA set** (primary, per corpus — domain-planning first). A list of `(query, correctAnswerName,
   factSheetId)`. This is the SAME artifact the RAGAS eval (grounded-RAG rec 5) needs — building it
   once unlocks both measurement and training.
2. **Logged accept/reject** (secondary, continuous). When a user/agent accepts or corrects a
   synthesized answer, log `(query, chosenAnswer, correct?)`. Free labels from production traffic.

## Feature generation

For each golden `(query, correctAnswer, factSheetId)`:

1. Run `AnswerSynthesisService.synthesize(factSheetId, query, …)` **instrumented to emit per-candidate
   features** (not just the ranked answers). Each candidate yields
   `AnswerFeatures.extract(signals)` → `double[12]`.
2. Label each row: `label = 1` if the candidate name equals `correctAnswer` (case-insensitive), else `0`.
3. Emit one `TrainingExample{query, candidate, double[] features, int label}` per candidate.

This reuses the exact feature extraction the live re-rank uses, so train/serve features match.

## Dataset format (JSON)

```json
[
  {"query":"who leads Acme?","candidate":"Alice","features":[0.71,0.47,1, 0.86,0.09,1, 0.94,0.08,1, 0,1,0],"label":1},
  {"query":"who leads Acme?","candidate":"Bob","features":[0.66,0.5,1, 0.14,0.09,1, 0.94,0.08,1, 0,1,0],"label":0}
]
```

Feature order is `AnswerFeatures.NAMES` (retrieval_e/u/present, engine_*, type_*, cons_*).

## Train + deploy

1. `LogisticRegressionTrainer.fit(X, y)` → `LogisticAnswerScorer`.
2. Serialize the scorer to JSON (`weights[]`, `bias`) at a stable path,
   e.g. `~/.kompile/models/answer-scorer-<corpus>.json`.
3. Set `kbAnswerScorerModelPath` to that file. `AnswerSynthesisService` loads + memoizes it and re-ranks
   by `P(correct)`. Empty path → algebraic ranking (unchanged).
4. Report `LogisticRegressionTrainer.logLoss` + the per-feature weights so the operator sees what the
   model learned.

## Evaluation (close the loop)

Use `RetrievalMetrics` (`kompile-evaluation`) on a held-out slice of the golden set:
`recall@k`, `MRR`, `nDCG@k` — **algebraic fold vs. learned scorer**. Ship the model only if it beats the
fold on held-out data (guards against overfitting the 12 features). This is also how we finally answer
"is graph retrieval better than dense?" in-house.

## Components to build (module + concurrency-safe order)

| # | Component | Module | Notes |
|---|---|---|---|
| 1 | `TrainingExample` record + JSON (de)serialization | `kompile-graph-reasoning` (lib) or kg | pure |
| 2 | Feature-emission variant of `synthesize` (returns candidates+features, not just answers) | kg `AnswerSynthesisService` | small refactor: expose the per-candidate `double[]` |
| 3 | Golden-set loader + feature-gen batch | kg service | drives #2 over the golden set → dataset JSON |
| 4 | `train-answer-scorer` endpoint/CLI | app-main REST or cli | fit → serialize → report weights + logLoss |
| 5 | Held-out eval (algebraic vs learned) via `RetrievalMetrics` | `kompile-evaluation` | ship-gate |

## Signal improvements = better features

The scorer is only as good as its inputs. These lift the *features* it learns from (ranked):

1. **Derivation** (query→anchor/relation) — decides whether ω_psl/ω_kge/ω_mebn fire at all, and on the
   right atom. Highest leverage. Improve the ontology-relation match (stemming/synonyms) and anchor
   extraction (entity linking vs. substring).
2. **ω_text real retrieval scores** — thread the actual cosine/PPR score through `RetrievalResult`
   instead of the rank proxy `1 − 0.05·rank`.
3. **ω_type real axioms** — use `OntologyProjectionProvider.ontologyAxioms()` DOMAIN/RANGE + disjointness,
   not just expectedType mismatch.
4. **ω_kge held-out calibration** — fit `KgeCalibration` on a held-out split, not the scored batch.
5. **ω_cons relation cardinality** — only penalize competing answers for functional relations.
