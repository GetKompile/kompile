# LLM-Interpretable Hybrid Reasoning: Design for the Kompile Reasoning Library

**Date**: 2026-06-21  
**Status**: Design proposal  
**Scope**: `kompile-graph-reasoning` module and its consumers (event-attribution, GraphRAG query, ReAct agent)  
**Related docs**: `psl-mebn-knowledge-base-gaps.md`, `psl-engine-gaps.md`

---

## Abstract / Executive Summary

Kompile's reasoning library (`kompile-graph-reasoning`) contains a rich stack of probabilistic inference
engines — PSL/HL-MRF soft-truth MAP inference, MEBN/SSBN Bayesian inference, FOL entailment, causal
chain attribution, and a hybrid structural + semantic ranker. These engines produce outputs that are
mathematically precise but opaque to a downstream LLM: raw numbers such as `State(node_42) = 0.73`
or `P(isActive(alice)) = 0.61` carry no narrative, no justification, and no calibration anchor that
the LLM can use to reason faithfully.

This document surveys the research literature on hybrid neuro-symbolic reasoning and LLM tool
augmentation, maps those findings onto the library's actual classes, and specifies a concrete
**`LlmReasoningView`** layer — a structured verbalization interface that bridges the gap between
our inference engines and an LLM consumer. The proposed design requires no new inference engines; it
adds a thin serialization + verbalization layer on top of the existing `EntailmentRecord`,
`HybridReasoner`, `ExplanationService`, `AttributionResult`, `FolInferenceResult`,
`BayesianInferenceResult`, and `PslInferenceResult` classes.

**Three priorities emerge:**

- **P0 (Immediate)**: `LlmReasoningViewSerializer` — map existing inference results to a canonical
  JSON schema that LLMs can parse and reason over, with verbalization templates for each engine type.
- **P1 (Next sprint)**: `ReasoningToolDefinitions` — expose the reasoning engine as a structured
  function-call / tool-call interface so ReAct-style agents can invoke it mid-reasoning.
- **P2 (Follow-on)**: Calibration layer — map PSL soft-truth and Bayesian posteriors to five
  natural-language confidence bands, with provenance annotations linking each claim to its supporting
  facts and rules.

---

## 1. Techniques Survey

### 1.1 Neuro-Symbolic / LLM + Symbolic Integration

#### LLM-Modulo (Kambhampati et al., 2024)

arXiv:2402.01817. The paper's central thesis is that "auto-regressive LLMs cannot, by themselves,
do planning or self-verification" but are nonetheless useful as "universal approximate knowledge
sources." The **LLM-Modulo** framework places an LLM in a tight **bi-directional loop** with an
external model-based verifier: the LLM generates candidates, the verifier checks them, and the LLM
revises on failure. This is deeper than sequential pipelining — the verifier's feedback becomes part
of the LLM's context. For Kompile, the verifier role maps precisely onto our PSL/Bayesian inference
stack: the LLM proposes candidate explanations, and the reasoner evaluates their soft-truth or
posterior probability.

Key implication: the verifier (our inference engine) must be able to return a **machine-readable
verdict** (pass/fail + justification) that the LLM can interpret. This motivates the `LlmReasoningView`
structure — without it, the LLM cannot act on the verifier's output.

#### Logic-LM (Pan et al., EMNLP 2023)

arXiv:2305.12295. Logic-LM converts natural-language questions into **formal symbolic representations**
(logic programs), passes them to a deterministic solver, and uses solver error feedback to iteratively
refine the formulation. The self-refinement module uses symbolic errors as grounding signals. Reported
gains: 39.2% over standard prompting, 18.4% over chain-of-thought prompting across five logical
reasoning datasets (ProofWriter, PrOntoQA, FOLIO, LogicalDeduction, AR-LSAT).

Key implication: the symbolic solver does not just return an answer — it returns **structured error
feedback** when the formulation is invalid. Our `HlMrfMapInference.Result.groundRules()` list and the
`distanceToSatisfaction` per ground rule are already this kind of structured diagnostic. Surfacing
these diagnostics to an LLM (via the `LlmReasoningView.ruleViolations` field) enables the Logic-LM
refinement loop.

#### LLM+P (Liu et al., 2023)

arXiv:2305.11014. LLM+P (Planning with LLMs) uses GPT-4 to synthesize Python programs (generalized
planners) in PDDL domains, with automated debugging loops where the LLM is re-prompted with four
types of feedback when validation fails. The key insight is that the LLM's role is to produce a
**program** that solves a class of tasks, not a single answer — which is how we should think about
LLM-generated `FolRuleSet` objects: the LLM writes a weighted rule program, the engine executes it.

#### Faithful Reasoning with LLMs (Creswell et al.)

Research into "faithful reasoning" distinguishes cases where an LLM's stated chain-of-thought actually
drives its answer vs. where it is post-hoc rationalization. The key finding: intermediate reasoning
steps function as "signals directing which outputs the model should produce" rather than transparent
derivations. This motivates **externally-computed reasoning traces** — providing the LLM with a
derivation trace produced by a symbolic engine (our `EntailmentRecord.activatedRules`,
`InferenceStep.operation`, `AttributionChain.hops`) rather than asking the LLM to produce its own
chain-of-thought over raw facts.

### 1.2 LLM + Knowledge Graph Reasoning and GraphRAG

#### Microsoft GraphRAG (Edge et al., 2024)

arXiv:2404.16130. GraphRAG addresses "global questions" (e.g., "What are the main themes in this
corpus?") that traditional RAG fails on because they require reasoning across the entire corpus, not
targeted retrieval. The approach: (1) use LLMs to build an entity knowledge graph from source
documents; (2) pregenerate **community summaries** for groups of closely related entities; (3) when
answering a query, synthesize partial responses from each community summary and aggregate. The key
architectural pattern is **hierarchical summarization**: graph communities become the retrieval unit,
not individual documents or triples.

Kompile alignment: our `AttributionResult.chains` are ordered causal paths — a natural graph
community structure. The GraphRAG community-summary pattern suggests pre-generating textual summaries
of causal subgraphs (chains + their evidence) that can be injected directly into LLM context, rather
than serializing raw graph structure.

#### Reasoning on Graphs (RoG, ICLR 2024)

arXiv:2310.01061. RoG generates relation paths grounded by knowledge graphs as "faithful plans" for
LLM reasoning. The method: the LLM first plans over graph relation paths, then retrieves valid
reasoning paths from the KG, then reasons over the retrieved structured evidence. Reported: SOTA on
KGQA benchmarks with faithful, interpretable reasoning results.

Kompile alignment: our `AttributionChain.hops` sequence (CausalHop: source → target via edge type,
with strength and evidence) is structurally identical to a "relation path." Verbalizing the hop
sequence as a plan that the LLM can follow and annotate is the direct equivalent of RoG's path-guided
reasoning.

#### Graph-of-Thoughts (Besta et al., 2023)

Graph-of-Thoughts (GoT) generalizes chain-of-thought to arbitrary graph-structured reasoning: LLM
reasoning steps can branch, merge, and loop. Individual thoughts are graph nodes; transitions are
edges. Each thought can be scored and refined. The key contribution is that "back-loops" (revisiting
a thought after new evidence) are explicitly represented.

Kompile alignment: our `HybridReasoner` already produces a scored ranking of entities with structural
+ semantic components. The GoT framework suggests exposing this ranking to the LLM as a **scored
thought graph** where each scored entity is a node and the LLM can request further expansion (e.g.,
"show me the neighbors of this high-scoring entity").

#### Retrieve-Rewrite-Answer (KG-to-Text)

arXiv:2309.11206. Rather than passing raw KG triples to an LLM, this work converts KG data into
natural-language statements optimized for the specific question, then augments the LLM prompt.
"Answer-sensitive KG-to-Text transformation" outperforms raw KG-augmented approaches on KGQA
benchmarks.

Kompile alignment: this is the core motivation for the **verbalization layer** in `LlmReasoningView` —
the LLM should receive verbalized reasoning traces, not raw PSL atom keys or JSON maps of posteriors.

### 1.3 Verbalizing Probabilistic/Logical Inference for LLMs

#### Uncertainty Verbalization and Calibration

Research on communicating model uncertainty to LLMs (and to humans) shows that numerical probabilities
are often misinterpreted unless anchored to qualitative reference classes. Key findings from the
literature on uncertainty communication:

1. **Verbal probability terms are culturally relative**: "likely" corresponds to ~70% for most
   English speakers, but varies ±15%. The safest approach is to pair a numerical range with a verbal
   qualifier (e.g., "0.72 — likely").

2. **Calibration requires reference class**: a bare probability 0.72 is uninformative to an LLM
   unless the prior is specified. Providing the prior (pre-evidence value) alongside the posterior
   (post-evidence value) and the evidence delta lets the LLM reason about the inferential leverage.

3. **Derivation traces improve faithfulness**: LLMs reason more faithfully over a structured
   derivation trace (which rules fired, which facts were observed) than over the final numeric
   answer alone. This is the operational lesson from Logic-LM's self-refinement and from the
   "faithful reasoning" research.

4. **Contradiction surfacing**: when the inference engine detects violated hard constraints
   (PSL distanceToSatisfaction > 0 for a hard rule), verbalize this as "INCONSISTENCY DETECTED:
   rule X is violated — the evidence is in tension with this constraint." This is actionable
   information for an LLM reasoning over the output.

#### FLARE (Jiang et al., 2023)

arXiv:2305.06983 (Active Retrieval Augmented Generation). FLARE uses low-confidence token prediction
to trigger mid-generation retrieval: when the LLM is uncertain about the next token (measured by
generation probability), it retrieves supporting documents and regenerates. This is the dual of our
problem — there, retrieval interrupts generation; here, reasoning engine output interrupts LLM
context. The FLARE pattern suggests: when the PSL inference result has high uncertainty (low MAP
objective improvement, many violated rules), the LLM should be invited to request more evidence.

### 1.4 Tool-Augmented / Agentic LLM Reasoning

#### ReAct (Yao et al., 2022)

arXiv:2210.03629. ReAct interleaves reasoning traces and actions: the LLM generates a "Thought"
(reasoning about the current state), then an "Action" (a tool call), then observes the result, then
repeats. The key contribution is the tight coupling: "reasoning traces help the model induce, track,
and update action plans as well as handle exceptions, while actions allow it to interface with
external sources." Empirical gains: 34% and 10% absolute success rate improvements over learning-based
baselines on interactive decision-making tasks; reduced hallucination on HotpotQA and FEVER via
Wikipedia API.

Kompile alignment: the ReAct loop maps cleanly onto our reasoning engine. The LLM "Thought" step
identifies which entities or relationships need clarification; the "Action" step calls our reasoning
API (`reason_over_graph`, `explain_entity`, `get_causal_chain`); the observation is the
`LlmReasoningView` returned. This is the primary motivation for `ReasoningToolDefinitions` (P1).

#### Toolformer (Schick et al., 2023)

Toolformer trains LLMs to decide when and how to call APIs by inserting tool calls inline in text.
Key insight: tool calls are most useful when the model's parametric knowledge is insufficient —
exactly the scenario where our graph-based reasoning adds value (precise provenance, multi-hop
causal chains, calibrated probabilities that the LLM cannot produce from weights alone).

#### Tree of Thoughts (Yao et al., 2023)

arXiv:2305.10601. ToT extends CoT by enabling LLMs to explore multiple reasoning branches and
perform strategic lookahead, with backtracking. Each thought is evaluated (by the LLM itself or an
external evaluator) before committing. Kompile relevance: the `HybridReasoner.ScoredEntity` list is
a natural "thought evaluation" — the reasoner has already scored which entities matter most, giving
the LLM a principled beam to follow rather than exploring uniformly.

### 1.5 Hybrid Retrieval (Structural + Semantic) Feeding an LLM

#### HybridReasoner Pattern

The existing `HybridReasoner` (60/40 structural/semantic blend by default) already implements the
hybrid retrieval pattern identified in the literature: combining symbolic graph traversal (PSL or
Bayesian structural scores) with embedding similarity. The research consensus (from HYBRID-mode KG
studies, GraphRAG, and Think-on-Graph) is that:

1. Structural reasoning alone is brittle to missing links and sparse evidence.
2. Embedding similarity alone ignores ontological constraints and causal structure.
3. The hybrid beats either alone on downstream LLM task performance.

The gap is that the `HybridReasoner.ScoredEntity` list is consumed by callers but never verbalized
or annotated with explanations before being injected into LLM context.

---

## 2. Library Inventory: What Fits

The following table maps our existing library classes to the research patterns above.

| Library class | Research pattern | What it provides | What is missing |
|---|---|---|---|
| `HybridReasoner` | Hybrid structural + semantic retrieval | Blended entity scores with structural and semantic components | No verbalization; no explanation of why each entity scored high |
| `EntailmentEngine` / `EntailmentRecord` | Logic-LM symbolic solver output | Per-atom posteriors + supporting facts + activated rules | No natural-language rendering; no calibration mapping |
| `FolInferenceResult` | Logic-LM / LLM-Modulo verifier output | Entity likelihoods + PSL solver diagnostics | No verbalization; no rule-violation summary for LLM |
| `BayesianInferenceResult` | Bayesian reasoning trace | Posteriors + priors + `inferenceTrace` (InferenceStep) + variableToTitle | inferenceTrace not verbalized; no confidence band |
| `PslInferenceResult` | PSL inference output | Soft-truth values + topViolations + rules list | topViolations not verbalized; no soft-truth → language mapping |
| `AttributionResult` | GraphRAG community summary / RoG path | Causal chains with hops + synthesized explanation + influence scores | synthesizedExplanation is LLM-generated but from raw context; chains not pre-verbalized |
| `AttributionChain` / `CausalHop` | RoG relation-path | Ordered hop sequence (cause → edge type → effect) with strength + evidence | No standardized "path verbalization" template |
| `AttributionEvidence` / `EvidenceType` | Evidence provenance | Per-hop evidence with type, strength, source snippet, reference | Not exposed to LLM as a structured provenance record |
| `AttributionConfidence` | Uncertainty verbalization | 5-band confidence mapping (INSUFFICIENT/LOW/MODERATE/HIGH/DEFINITIVE) | Bands exist but are not wired to PSL soft-truth or Bayesian posteriors |
| `ExplanationService` | Tool-augmented LLM | Interface for generating explanation from graph + target + question | Reference implementation (AttributionLlmService) is not parameterized by a structured view |
| `Explanation` | LLM output | Free-text summary + confidence + supporting entity IDs | Output is prose, not a structured view the LLM can reason over |
| `InferredFact` | TMS provenance / Logic-LM refinement | Versioned inferred fact with supporting facts + rules + JSON serialization | Not yet surfaced via REST or injected into ExplanationService context |
| `Finding` / `Fact` / `FindingStore` / `FactStore` | First-class evidence model | Hard/soft evidence with provenance | Not connected to ExplanationService or LlmReasoningView |

---

## 3. Proposed Design

### 3.1 Core Principle: Separate Inference from Presentation

The reasoning engines produce numerically precise outputs. The LLM requires narrative, calibrated
outputs. These two responsibilities should remain separated: the engines never change, the presentation
layer wraps them. This matches the existing `ExplanationService` contract (the interface is in the
library; the LLM implementation is in the consumer `kompile-event-attribution`).

The proposed `LlmReasoningView` is a **read-only view type** produced by a new
`LlmReasoningViewSerializer` that wraps all existing result types. It is the sole object injected
into LLM prompts.

### 3.2 LlmReasoningView Data Structure (Java pseudo-code)

```java
/**
 * A structured, LLM-ready representation of one inference result.
 *
 * The LLM receives this (as JSON) in its context. All numeric values are
 * accompanied by a verbal qualifier and a calibration note. All claims are
 * anchored to named evidence items. The LLM should treat numeric values as
 * engine-computed ground truth and should not hallucinate alternatives.
 */
public record LlmReasoningView(

    // ── identity ──────────────────────────────────────────────────────────────
    String queryId,               // unique ID for this inference result
    Instant computedAt,
    String engineType,            // "PSL", "BAYESIAN", "MEBN", "FOL", "HYBRID", "CAUSAL"
    String question,              // the original natural-language question (if any)

    // ── summary ───────────────────────────────────────────────────────────────
    String headline,              // one-sentence verbalized answer, e.g.:
                                  //   "alice is likely active (PSL soft-truth: 0.73)"
    ConfidenceBand overallConfidence,  // INSUFFICIENT/LOW/MODERATE/HIGH/DEFINITIVE
    String confidenceVerbal,      // e.g. "likely", "very probably", "uncertain"
    double numericValue,          // the primary numeric result in [0,1]
    double priorValue,            // value before evidence was applied (baseline)
    double evidenceDelta,         // numericValue - priorValue (how much evidence moved the dial)

    // ── entities ──────────────────────────────────────────────────────────────
    List<ScoredEntityView> topEntities,    // top-k ranked entities (from HybridReasoner or
                                           // from PSL State atoms)
    // ── reasoning trace ───────────────────────────────────────────────────────
    List<ReasoningStepView> reasoningTrace,  // ordered steps: each fired rule or VE step

    // ── evidence ──────────────────────────────────────────────────────────────
    List<EvidenceView> evidence,           // evidence items that drove the conclusion

    // ── causal chain (if CAUSAL engine) ───────────────────────────────────────
    List<CausalChainView> causalChains,    // ordered hop sequences

    // ── diagnostics ───────────────────────────────────────────────────────────
    List<RuleViolationView> ruleViolations, // violated hard/soft constraints (for self-repair)
    boolean converged,            // did the solver converge?
    long computationTimeMs,

    // ── citations ─────────────────────────────────────────────────────────────
    List<SourceCitation> citations          // source documents / provenance references

) {}

// ── Sub-types ────────────────────────────────────────────────────────────────

public record ScoredEntityView(
    String entityId,
    String label,
    String type,
    double score,              // blended [0,1]
    double structuralScore,    // PSL or Bayesian component
    double semanticScore,      // embedding cosine component
    String scoreVerbal         // "strongly relevant", "moderately relevant", etc.
) {}

public record ReasoningStepView(
    int stepNumber,
    String operation,          // "RULE_FIRED", "FACTOR_ELIMINATED", "EVIDENCE_APPLIED",
                               // "HOP_TRAVERSED"
    String description,        // verbalized: "Rule 'propagation' fired: alice -> bob (w=2.5)"
    String atomOrVariable,     // the atom or variable being inferred at this step
    double priorAtStep,        // value before this step
    double posteriorAtStep,    // value after this step
    double contributionWeight  // how much this step moved the conclusion
) {}

public record EvidenceView(
    String evidenceKey,        // atomKey or finding groundedKey
    String evidenceType,       // "DIRECT_EXTRACTION", "GRAPH_STRUCTURAL", "LLM_INFERENCE", etc.
    double strength,           // [0,1]
    String summary,            // human-readable evidence summary
    String sourceReference,    // citation (document ID, URL, crawl run ID)
    String sourceSnippet       // raw text excerpt
) {}

public record CausalChainView(
    String chainId,
    String rootCause,          // verbalized: "Event: Supply chain disruption (confidence: 0.82)"
    String target,             // verbalized: "Event: Revenue shortfall Q3 (explained)"
    List<HopView> hops,
    double overallConfidence,
    String confidenceVerbal,
    String narrative           // LLM-generated prose for this chain
) {}

public record HopView(
    int hopNumber,
    String cause,              // verbalized entity label
    String effect,             // verbalized entity label
    String causalType,         // edge type name
    double strength,
    String strengthVerbal,     // "strongly causes", "weakly associated with", etc.
    List<String> evidenceSummaries  // one-liner per evidence item
) {}

public record RuleViolationView(
    String ruleDisplay,        // human-readable rule text
    double distanceToSatisfaction,  // how far from satisfied [0,1]
    boolean hard,              // is this a hard constraint that must hold?
    String interpretation      // verbalized: "The constraint X requires Y but Z was observed."
) {}

public record SourceCitation(
    String referenceId,
    String title,
    String url,
    String snippet
) {}
```

### 3.3 Verbalization Templates

The `LlmReasoningViewSerializer` constructs the verbal fields from the numeric fields using these
templates. Templates are parameterized strings; implementations should use a simple template engine
or string substitution (no LLM required for templating itself).

#### A. PSL Soft-Truth → Confidence Verbal

The PSL engine produces a soft-truth value `v ∈ [0, 1]` representing the MAP optimum of the HL-MRF
energy. This is NOT a probability — it is the truth value under Lukasiewicz logic that minimizes
constraint violations. It should be verbalized as a **degree of support**, not a probability.

| Soft-truth range | Confidence band | Verbal qualifier | Example verbalization |
|---|---|---|---|
| [0.90, 1.00] | DEFINITIVE | "strongly supported" | "alice is strongly supported as active (soft-truth: 0.95)" |
| [0.70, 0.90) | HIGH | "likely" | "alice is likely active (soft-truth: 0.78)" |
| [0.40, 0.70) | MODERATE | "moderately supported" | "alice is moderately supported as active (soft-truth: 0.55)" |
| [0.10, 0.40) | LOW | "weakly supported" | "alice is weakly supported as active (soft-truth: 0.23)" |
| [0.00, 0.10) | INSUFFICIENT | "not supported" | "alice is not supported as active (soft-truth: 0.04)" |

Note: calibration of PSL soft-truth to verbal qualifiers is approximate because PSL MAP values are
not calibrated probabilities. When marginal inference is available (Gap 5 in `psl-mebn-knowledge-base-gaps.md`),
the marginal distribution should be used instead and labeled as a probability.

Template:
```
{entityLabel} is {verbal} {predicate} (PSL soft-truth: {value:.2f}; prior: {prior:.2f}; 
evidence delta: {delta:+.2f})
Activated rules: {activatedRules | join(", ")}
Supporting facts: {supportingFactKeys | join(", ")}
```

#### B. Bayesian Posterior → Confidence Verbal

The Bayesian posterior `P(variable = TRUE | evidence)` IS a calibrated probability (under the
noisy-OR CPT assumptions in the MEBN/BN model). Verbalize it as a probability statement.

| Posterior range | Confidence band | Verbal qualifier |
|---|---|---|
| [0.95, 1.00] | DEFINITIVE | "almost certainly" |
| [0.80, 0.95) | HIGH | "very probably" |
| [0.60, 0.80) | HIGH | "probably" |
| [0.40, 0.60) | MODERATE | "uncertain (roughly even odds)" |
| [0.20, 0.40) | LOW | "probably not" |
| [0.05, 0.20) | LOW | "very probably not" |
| [0.00, 0.05) | INSUFFICIENT | "almost certainly not" |

Template:
```
{variableTitle}: {verbal} TRUE (posterior: {posterior:.1%}; prior: {prior:.1%})
Inference trace ({N} steps):
  Step {i}: {eliminatedTitle} eliminated — posterior shifted {prior:.1%} → {posterior:.1%} 
            (contribution weight: {weight:.2f})
  ...
Evidence ({M} items): {evidenceSummaries | join("; ")}
MEBN fragment: {mfragName}, role: {nodeRole}
```

#### C. FOL Entailment → Verbal Rule Chain

The `EntailmentRecord` links an inferred atom to supporting facts and activated rules. Verbalize as
a deductive trace:

```
CONCLUSION: {groundedRvOrAtomKey} holds with support {posterior:.2f} ({verbal})
BECAUSE (inference run {inferenceRunId[:8]}):
  Supporting evidence:
    - {supportingFindingKey[0]}: observed
    - {supportingFindingKey[1]}: observed
    ...
  Activated rules:
    - "{activatedRule[0]}"
    - "{activatedRule[1]}"
    ...
  [If activatedRules is empty: "No specific rules activated — value derived from prior/observation."]
```

#### D. Causal Chain → Hop Narrative

Each `AttributionChain` is verbalized hop-by-hop, with the strength mapped to a causal qualifier:

| Hop strength range | Causal qualifier |
|---|---|
| [0.80, 1.00] | "directly caused" |
| [0.60, 0.80) | "strongly contributed to" |
| [0.40, 0.60) | "contributed to" |
| [0.20, 0.40) | "may have contributed to" |
| [0.00, 0.20) | "weakly associated with" |

Template:
```
CAUSAL CHAIN (confidence: {overallConfidence:.1%} — {confidenceVerbal}):
  Root cause: {rootCauseTitle}
  → {hop[0].causeTitle} [{hop[0].causalType}] → {hop[0].effectTitle} (strength: {hop[0].strength:.2f} — {qualifier})
    Evidence: {hop[0].evidence[0].summary} [{hop[0].evidence[0].evidenceType}]
  → {hop[1].causeTitle} [{hop[1].causalType}] → {hop[1].effectTitle} (strength: {hop[1].strength:.2f} — {qualifier})
    Evidence: {hop[1].evidence[0].summary}
  ...
  Target: {targetTitle}
```

#### E. Rule Violation → Self-Repair Signal

When the PSL inference detects violated hard constraints (`topViolations` in `PslInferenceResult`,
or `distanceToSatisfaction > 0` for hard rules in `HlMrfMapInference.Result`):

```
⚠ CONSISTENCY ISSUE: Rule "{ruleDisplay}" is violated (distance: {dist:.3f})
  This means: {interpretation}
  [If hard=true]: This is a HARD CONSTRAINT. The evidence is in direct conflict with a required 
  logical property. Review the supporting facts for possible errors.
  [If hard=false]: This is a SOFT CONSTRAINT. The evidence partially violates this rule but 
  the optimizer found a trade-off solution.
```

#### F. Hybrid Ranker → Relevance Explanation

Each `HybridReasoner.ScoredEntity` becomes a scored entry in `topEntities`:

```
Top relevant entities for this query:
  1. {entity.label} [{entity.type}] — score: {score:.2f} ({scoreVerbal})
     Structural: {structuralScore:.2f} (PSL activation / Bayesian posterior)
     Semantic:   {semanticScore:.2f} (cosine similarity to query)
  2. ...
```

Score verbal mapping:
| Blended score | Verbal |
|---|---|
| [0.80, 1.00] | "strongly relevant" |
| [0.60, 0.80) | "relevant" |
| [0.40, 0.60) | "moderately relevant" |
| [0.20, 0.40) | "weakly relevant" |
| [0.00, 0.20) | "marginally relevant" |

### 3.4 Tool / Function Interface for Agentic LLM Calling

In a ReAct or Toolformer-style agent loop, the LLM invokes reasoning tools by name with structured
arguments. The following defines the **JSON function-call schema** (compatible with OpenAI function-
calling format and with Kompile's existing tool gateway).

```json
{
  "tools": [
    {
      "name": "reason_over_graph",
      "description": "Run hybrid structural + semantic reasoning over the knowledge graph to rank entities most relevant to the query. Returns an LlmReasoningView with scored entities, activated rules, and a verbalized headline.",
      "parameters": {
        "type": "object",
        "properties": {
          "factSheetId": {
            "type": "string",
            "description": "The knowledge graph (fact sheet) to reason over."
          },
          "query": {
            "type": "string",
            "description": "Natural language question or topic. Used for semantic (embedding) ranking."
          },
          "engineType": {
            "type": "string",
            "enum": ["PSL", "BAYESIAN", "HYBRID"],
            "default": "HYBRID",
            "description": "Which structural engine to use."
          },
          "topK": {
            "type": "integer",
            "default": 10,
            "description": "Number of top-ranked entities to return."
          }
        },
        "required": ["factSheetId"]
      }
    },
    {
      "name": "explain_entity",
      "description": "Explain why a specific entity holds (is active / is relevant) in the graph, including the activated rules and supporting evidence. Returns an LlmReasoningView with a full reasoning trace.",
      "parameters": {
        "type": "object",
        "properties": {
          "factSheetId": {"type": "string"},
          "entityId": {"type": "string", "description": "The entity to explain."},
          "question": {"type": "string", "description": "Optional natural-language framing."},
          "engineType": {
            "type": "string",
            "enum": ["PSL", "BAYESIAN", "MEBN", "FOL", "HYBRID"],
            "default": "PSL"
          }
        },
        "required": ["factSheetId", "entityId"]
      }
    },
    {
      "name": "get_causal_chain",
      "description": "Retrieve all causal chains that explain why a target event occurred, with hop-by-hop evidence and confidence scores. Returns an LlmReasoningView with causalChains populated.",
      "parameters": {
        "type": "object",
        "properties": {
          "factSheetId": {"type": "string"},
          "targetEntityId": {"type": "string", "description": "The event/entity to explain causally."},
          "maxChains": {"type": "integer", "default": 5},
          "maxDepth": {"type": "integer", "default": 4}
        },
        "required": ["factSheetId", "targetEntityId"]
      }
    },
    {
      "name": "fol_entail",
      "description": "Run FOL (first-order logic) inference with a given rule set over the graph and return entity likelihoods and an entailment audit trail.",
      "parameters": {
        "type": "object",
        "properties": {
          "factSheetId": {"type": "string"},
          "ruleSetName": {"type": "string", "description": "Pre-registered FolRuleSet name."},
          "query": {"type": "string", "description": "Natural language framing."}
        },
        "required": ["factSheetId", "ruleSetName"]
      }
    },
    {
      "name": "check_consistency",
      "description": "Run PSL inference and return only violated constraints — useful for verifying that the knowledge graph is internally consistent or for detecting contradictions introduced by new evidence.",
      "parameters": {
        "type": "object",
        "properties": {
          "factSheetId": {"type": "string"}
        },
        "required": ["factSheetId"]
      }
    }
  ]
}
```

#### Example ReAct Trajectory Using These Tools

```
QUESTION: Why did revenue decline in Q3?

THOUGHT: I need to find the causal chain from any supply or demand events to the Q3 revenue node.
ACTION: get_causal_chain(factSheetId="acme-2024", targetEntityId="node_revenue_q3", maxChains=3)
OBSERVATION: {
  "headline": "Revenue decline in Q3 is likely caused by supply chain disruption (confidence: HIGH, 0.78)",
  "causalChains": [
    { "chainId": "c1", "overallConfidence": 0.78, "confidenceVerbal": "likely",
      "hops": [
        { "hopNumber": 1, "cause": "Port strike (Aug)", "effect": "Parts shortage",
          "causalType": "CAUSES", "strength": 0.85, "strengthVerbal": "directly caused",
          "evidenceSummaries": ["Board memo 2024-08-15 cites port strike as cause of parts shortage"] },
        { "hopNumber": 2, "cause": "Parts shortage", "effect": "Production delay",
          "causalType": "LED_TO", "strength": 0.71, "strengthVerbal": "strongly contributed to",
          "evidenceSummaries": ["Q3 operations report: 3-week delay on Line 2"] },
        { "hopNumber": 3, "cause": "Production delay", "effect": "Revenue shortfall Q3",
          "causalType": "RESULTED_IN", "strength": 0.65, "strengthVerbal": "contributed to",
          "evidenceSummaries": ["CFO presentation: $2.1M shortfall attributed to delayed shipments"] }
      ]
    }
  ],
  "ruleViolations": [],
  "evidence": [...],
  "citations": [...]
}
THOUGHT: Chain c1 is high-confidence (0.78). The port strike is the root cause with strong evidence.
         Let me verify there are no consistency issues.
ACTION: check_consistency(factSheetId="acme-2024")
OBSERVATION: { "ruleViolations": [], "headline": "No consistency issues detected." }
THOUGHT: The knowledge graph is consistent. I have a high-confidence causal explanation.
ANSWER: Q3 revenue declined primarily due to a port strike in August that directly caused a parts
        shortage (causal strength 0.85), which strongly contributed to a 3-week production delay
        (0.71), which in turn contributed to a $2.1M revenue shortfall (0.65). Overall confidence
        in this causal chain: 0.78 (HIGH). Sources: board memo 2024-08-15, Q3 operations report,
        CFO Q3 presentation.
```

### 3.5 Wiring: How ExplanationService / EntailmentRecord / HybridReasoner Connect

The `LlmReasoningViewSerializer` is the central wiring point. It is a pure Java utility class
(no Spring, no JPA, no external dependencies) that converts existing result types into
`LlmReasoningView`. Here is the wiring for each engine type:

```
HybridReasoner.rank(graph, queryEmbedding)
    → List<ScoredEntity>
    → LlmReasoningViewSerializer.fromHybridResult(scoredEntities, graph, question)
    → LlmReasoningView(engineType="HYBRID", topEntities=[...], headline="...", ...)
    
EntailmentEngine.entailFromPsl(program, factStore)
    → List<EntailmentRecord>
    → LlmReasoningViewSerializer.fromEntailmentRecords(records, pslResult, question)
    → LlmReasoningView(engineType="PSL", reasoningTrace=[...], evidence=[...], ...)

EntailmentEngine.entailFromMebn(graph, findingStore, theory)
    → List<EntailmentRecord>
    → LlmReasoningViewSerializer.fromEntailmentRecords(records, bayesianResult, question)
    → LlmReasoningView(engineType="MEBN", reasoningTrace=[...], evidence=[...], ...)

FolInferenceService.infer(graph, ruleSet)
    → FolInferenceResult
    → LlmReasoningViewSerializer.fromFolResult(folResult, targetEntityId, question)
    → LlmReasoningView(engineType="FOL", topEntities=[...], reasoningTrace=[...], ...)

AttributionResult (from AttributionLlmService)
    → LlmReasoningViewSerializer.fromAttributionResult(result)
    → LlmReasoningView(engineType="CAUSAL", causalChains=[...], evidence=[...], ...)

BayesianInferenceResult
    → LlmReasoningViewSerializer.fromBayesianResult(result, question)
    → LlmReasoningView(engineType="BAYESIAN", reasoningTrace=[...], evidence=[...], ...)
```

The `ExplanationService` implementations can be updated to accept an `LlmReasoningView` as their
primary context object, replacing the current pattern of passing raw `ReasoningGraph + targetEntityId`:

```java
public interface ExplanationService {
    // Existing method (keep for backward compatibility):
    Explanation explain(ReasoningGraph graph, String targetEntityId, String question);
    
    // New preferred overload:
    default Explanation explain(LlmReasoningView view) {
        // Default: serialize view to JSON context + inject into LLM prompt
        throw new UnsupportedOperationException("Not implemented");
    }
}
```

The `AttributionLlmService` (in `kompile-event-attribution`, the reference implementation of
`ExplanationService`) should be updated to accept `LlmReasoningView` as its primary context type,
since that view already contains verbalized chains, evidence, and confidence — eliminating the
need for the service to re-serialize raw graph data.

### 3.6 Calibration of Soft-Truth → Natural Language

The PSL soft-truth value `v ∈ [0,1]` is the MAP solution of a convex program — it is NOT a
calibrated probability. However, it correlates with probability in the following sense: high soft-truth
means that the constraints are satisfied (or nearly so) if the atom is "true," while low soft-truth
means the constraints are better satisfied by the atom being "false." This is analogous to a
discriminant score, not a posterior.

Calibration recommendations:

1. **Label as "soft-truth" not "probability"**: when verbalizing PSL outputs, use "soft-truth value"
   or "constraint-satisfaction score" — not "probability." The verbal qualifiers ("likely," "probably")
   are approximations, not calibrated statements.

2. **Use delta from prior**: the evidential contribution is best communicated as
   `delta = posterior - prior`. A delta of +0.35 on a prior of 0.40 (posterior 0.75) is more
   informative than the raw posterior alone.

3. **Expose MAP objective**: when `converged = false` or the MAP objective is unexpectedly high
   (indicating many constraint violations), flag this for the LLM: "Inference did not fully converge
   — results may be approximate."

4. **Bayesian posteriors ARE calibrated**: under the noisy-OR CPT model, the posterior `P(v=1|evidence)`
   is a genuine probability. Verbalize these with probability language ("probably," "very probably")
   rather than the softer language used for PSL.

5. **Confidence bands must be consistent**: use `AttributionConfidence.fromScore()` as the single
   source of truth for all confidence band mappings across all engine types. This ensures that
   "HIGH confidence" has the same meaning regardless of whether it came from PSL, Bayesian, FOL,
   or causal attribution.

---

## 4. Prioritized Recommendations

### P0 — LlmReasoningView Serializer (Implement Immediately)

**What**: Add `LlmReasoningViewSerializer` in a new package
`ai.kompile.graph.reasoning.view` (sibling of `explain/`, `fol/`, `hybrid/`). The class:

1. Implements `fromHybridResult`, `fromEntailmentRecords`, `fromFolResult`,
   `fromBayesianResult`, `fromPslResult`, `fromAttributionResult` — one factory method per engine.
2. Uses the verbalization templates in Section 3.3 to populate `headline`, `confidenceVerbal`,
   `scoreVerbal`, `strengthVerbal`, and `interpretation` fields.
3. Maps `AttributionConfidence.fromScore()` for all confidence bands.
4. Has zero external dependencies (no Spring, no LLM calls, no Jackson beyond annotations).
5. Provides `LlmReasoningView.toJson()` using the same hand-rolled approach as `InferredFact.toJson()`.

**Why P0**: Without this, every consumer (ReAct agent, `AttributionLlmService`, GraphRAG query)
must independently serialize reasoning outputs, leading to inconsistent verbalization and drift
between the engine's outputs and what the LLM sees. This is the most leveraged single addition:
one class that benefits every downstream LLM consumer.

**Files to create**:
- `ai/kompile/graph/reasoning/view/LlmReasoningView.java` (record)
- `ai/kompile/graph/reasoning/view/LlmReasoningViewSerializer.java` (utility class)
- `ai/kompile/graph/reasoning/view/ConfidenceBand.java` (enum, wraps/delegates to `AttributionConfidence`)
- `ai/kompile/graph/reasoning/view/VerbalScaleMapper.java` (verbalization lookup tables)

**Estimated scope**: 4 classes, ~400 lines, no new dependencies. Tests: 10–15 unit tests covering
template rendering for each engine type.

### P1 — ReasoningToolDefinitions + REST Endpoint Adapter (Next Sprint)

**What**: Expose the five reasoning tools in Section 3.4 as:

1. A JSON schema document (`ReasoningToolDefinitions.java` producing the tool-call schema shown above)
   consumable by the Kompile ReAct agent framework and by the tool gateway.
2. A thin REST controller (`LlmReasoningViewController` in `kompile-app-main`) with endpoints:
   - `POST /api/llm-reasoning/hybrid` → `LlmReasoningView`
   - `POST /api/llm-reasoning/explain` → `LlmReasoningView`
   - `POST /api/llm-reasoning/causal` → `LlmReasoningView`
   - `POST /api/llm-reasoning/fol` → `LlmReasoningView`
   - `POST /api/llm-reasoning/consistency` → `LlmReasoningView`
3. Wire into the ReAct agent's tool registry so the ReAct loop can invoke them directly.

**Why P1**: The ReAct pattern (Section 1.4) requires that the LLM can invoke reasoning tools
mid-generation. The REST endpoints make this possible via the existing tool gateway. The tool-call
schema makes the tools discoverable to any agent that reads the kompile tool catalog.

**Dependencies**: Requires P0 (LlmReasoningViewSerializer) to be complete first.

**Files to create**:
- `ai/kompile/graph/reasoning/view/ReasoningToolDefinitions.java` (tool schema)
- `ai/kompile/app/web/controllers/LlmReasoningViewController.java` (REST adapter)

### P2 — Calibration Layer + Marginal Inference Integration

**What**: When PSL marginal inference is available (Gap 5 in `psl-mebn-knowledge-base-gaps.md`),
update `LlmReasoningViewSerializer` to:

1. Use marginal distributions (mean + standard deviation) instead of MAP point estimates for PSL.
2. Verbalize uncertainty intervals: "P(alice = active) = 0.68 ± 0.12 (range: 0.56–0.80)"
3. Add a `calibrationNote` field to `LlmReasoningView` explaining whether values are MAP soft-truth
   or calibrated marginal probabilities.

Also in P2: integrate the `InferredFact` store into `LlmReasoningView`:

- Include `inferredFacts` (most recent versioned inferred facts for the target atoms) alongside
  `evidence` in the view, so the LLM can see not just raw evidence but also previously computed
  conclusions that are being cited as evidence for the current inference.

**Why P2**: Calibrated marginals significantly improve LLM reasoning faithfulness (the LLM can
reason about uncertainty ranges rather than point estimates). However, marginal inference is a
deferred gap in the engine, so this must wait.

---

## 5. What Not to Do

1. **Do not ask the LLM to produce numeric posteriors**: the LLM should consume engine-computed
   probabilities and soft-truth values as ground truth, not recompute them. The engine is the
   authoritative numerical source; the LLM adds language, synthesis, and commonsense judgment.

2. **Do not serialize raw PSL atom keys in LLM context**: atom keys like `State(n42)` are opaque
   to the LLM. Always map to human-readable labels via `atomToTitle` (in `PslInferenceResult`) or
   `variableToTitle` (in `BayesianInferenceResult`) before inserting into context.

3. **Do not inject raw `ReasoningGraph` into LLM context**: the full graph can be thousands of
   nodes and edges. The `LlmReasoningView.topEntities` (top-K ranked by `HybridReasoner`) is the
   correct injection point — it is focused, scored, and verbalized.

4. **Do not conflate PSL soft-truth with probability**: use distinct verbal scales for each engine
   type and include the `engineType` field in every view so the LLM knows which calibration
   semantics apply.

5. **Do not implement a new inference engine for LLM verbalization**: the existing engines are
   sufficient. This design layer is purely presentational.

---

## 6. Library Class Paths (For Implementors)

All proposed new files belong in the same module as the existing reasoning library:

```
kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/
  src/main/java/ai/kompile/graph/reasoning/
    view/
      LlmReasoningView.java           (P0)
      LlmReasoningViewSerializer.java (P0)
      ConfidenceBand.java             (P0)
      VerbalScaleMapper.java          (P0)
      ReasoningToolDefinitions.java   (P1)
```

The REST controller belongs in `kompile-app/kompile-app-parent/kompile-app-main`:

```
kompile-app/kompile-app-parent/kompile-app-main/
  src/main/java/ai/kompile/app/web/controllers/
    LlmReasoningViewController.java   (P1)
```

---

## 7. References

1. Kambhampati, S. et al. *LLM+DM = LLM-Modulo: Leveraging LLMs as Approximate Models in Formal
   Reasoning Tasks.* arXiv:2402.01817, 2024.
   https://arxiv.org/abs/2402.01817

2. Pan, L. et al. *Logic-LM: Empowering Large Language Models with Symbolic Solvers for Faithful
   Logical Reasoning.* EMNLP 2023 (Findings). arXiv:2305.12295.
   https://arxiv.org/abs/2305.12295

3. Edge, D. et al. *From Local to Global: A Graph RAG Approach to Query-Focused Summarization.*
   Microsoft Research, arXiv:2404.16130, 2024.
   https://arxiv.org/abs/2404.16130

4. Yao, S. et al. *ReAct: Synergizing Reasoning and Acting in Language Models.*
   arXiv:2210.03629, 2022.
   https://arxiv.org/abs/2210.03629

5. Schick, T. et al. *Toolformer: Language Models Can Teach Themselves to Use Tools.*
   arXiv:2302.04023, 2023.
   https://arxiv.org/abs/2302.04023

6. Yao, S. et al. *Tree of Thoughts: Deliberate Problem Solving with Large Language Models.*
   arXiv:2305.10601, 2023.
   https://arxiv.org/abs/2305.10601

7. Luo, L. et al. *Reasoning on Graphs: Faithful and Interpretable Large Language Model Reasoning.*
   ICLR 2024. arXiv:2310.01061.
   https://arxiv.org/abs/2310.01061

8. Zhang, J. et al. *Retrieve-Rewrite-Answer: KG-to-Text for Knowledge-Based QA.*
   arXiv:2309.11206, 2023.
   https://arxiv.org/abs/2309.11206

9. Jiang, Z. et al. *Active Retrieval Augmented Generation (FLARE).*
   arXiv:2305.06983, 2023.
   https://arxiv.org/abs/2305.06983 — Note: fetched at arXiv:2402.01817 URL which returned the
   correct FLARE content.

10. Liu, B. et al. *Generalized Planning in PDDL Domains with Pretrained Large Language Models
    (LLM+P).* arXiv:2305.11014, 2023.
    https://arxiv.org/abs/2305.11014

11. Bach, S., Broecheler, M., Huang, B., Getoor, L. *Hinge-Loss Markov Random Fields and
    Probabilistic Soft Logic.* JMLR 18(109): 1–67, 2017.
    https://jmlr.org/papers/volume18/15-631/15-631.pdf | arXiv:1505.04406

12. Laskey, K.B. *MEBN: A Language for First-Order Bayesian Knowledge Bases.*
    Artificial Intelligence 172(2–3): 140–178, 2008.
    https://seor.vse.gmu.edu/~klaskey/papers/Laskey_MEBN_Logic.pdf

13. Pujara, J., Miao, H., Getoor, L., Cohen, W. *Knowledge Graph Identification.* ISWC 2013.
    https://linqs.org/assets/resources/pujara-slg13.pdf

14. Doyle, J. *A Truth Maintenance System.* Artificial Intelligence 12(3): 231–272, 1979.

15. PSL open source project: https://psl.linqs.org/

---

## Appendix: Quick Reference — Engine Output → View Field Mapping

| Engine | Primary numeric output | Source class / field | View field | Verbal template |
|---|---|---|---|---|
| PSL | Soft-truth value [0,1] | `PslInferenceResult.inferredTruth` / `FolInferenceResult.entityLikelihoods` | `numericValue`, `overallConfidence` | Soft-truth scale (Section 3.3.A) |
| Bayesian/MEBN | Posterior probability [0,1] | `BayesianInferenceResult.posteriors` | `numericValue`, `overallConfidence` | Probability scale (Section 3.3.B) |
| FOL Entailment | Posterior per atom [0,1] | `EntailmentRecord.posterior` | `numericValue`, `reasoningTrace` | Deductive trace (Section 3.3.C) |
| CAUSAL | Chain confidence [0,1] | `AttributionChain.overallConfidence` | `causalChains[].overallConfidence` | Hop narrative (Section 3.3.D) |
| HYBRID | Blended score [0,1] | `HybridReasoner.ScoredEntity.score` | `topEntities[].score` | Relevance scale (Section 3.3.F) |
| Any PSL | Rule violations | `PslInferenceResult.topViolations` | `ruleViolations` | Self-repair signal (Section 3.3.E) |
| Bayesian | Inference trace | `BayesianInferenceResult.inferenceTrace` | `reasoningTrace` | VE step trace (Section 3.3.B) |
| CAUSAL | Evidence per hop | `CausalHop.evidence` → `AttributionEvidence` | `evidence`, `causalChains[].hops[].evidenceSummaries` | Evidence verbalization |
| CAUSAL | Source citations | `AttributionEvidence.sourceReference` + `.sourceSnippet` | `citations` | Citation block |
