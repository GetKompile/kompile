# Confidence & Evidence Model Design

**Status:** Design (no code changes)
**Date:** 2026-06-22
**Scope:** The complete six-pillar model for how knowledge-graph facts are initialized,
corroborated, scoped in time, domain-parameterized, source-weighted, and presented to
humans. Every code claim below is grounded against the real source with file:line citations
verified against the repository at the time of writing.

---

## The Problem in Plain Terms

Today a single LLM extraction produces a fact stored at confidence `0.8`
(`LlmKnowledgeGraphBuilder.java:387`, fallback
`rel.getConfidence() != null ? rel.getConfidence() : 0.8`; second instance at line 506).
The PSL soft-propagation rule that re-derives it adds another `0.8:` weight
(`IncrementalReasoningOrchestrator.java:799, 804`).
Structural edges from Tika get `1.0` when confidence is null
(`GraphPersistenceHelper.java:255`: `confidence != null ? confidence : weight != null ? weight : 1.0`).
None of these numbers reflect evidence count or source reliability.

`FactPromotionTracker` counts corroborations (`corroborationCount` at `FactPromotionTracker.java:74`,
incremented at line 161, persisted to `InferredFactRow.corroboration_count`), but the count
is a dead side-register: it never feeds back into the `Opinion` that the tracker is supposed
to represent. The tracker detects band promotions by comparing old vs. new PSL MAP scalars,
not an accumulated Beta distribution.

The PSL weight learner (`IncrementalReasoningOrchestrator.java:529`) commits a self-training
error: `softTargets = new HashMap<>(result.values())` passes the model's own MAP posteriors
as the training target instead of the observed facts from the fact store. The gradient
`dist(innerMAP) - dist(outerMAP)` collapses to near-zero because both MAP runs use the same
weights. Weights never move from `0.8`. The same bug appears in the MEBN training path at
line 651: `Map<String, Double> observations = new HashMap<>(result.values())`.

This document specifies the six-pillar model that fixes all of this.

---

## Pillar 1 — Evidence-Based Confidence

### The Primitive

`Opinion` (`ai.kompile.graph.reasoning.confidence.Opinion`, record)
is already the right primitive. Its key API (all confirmed in the source):

```
// Opinion.java:106
public static Opinion fromBetaEvidence(double pos, double neg, double baseRate, double k) {
    double total = pos + neg + k;
    return new Opinion(pos/total, neg/total, k/total, baseRate);
}

// Opinion.java:37
public double expectation() { return belief + baseRate * uncertainty; }

// Opinion.java:135
public Opinion cumulativeFuse(Opinion other) { ... }  // Jøsang §12.2
```

With `k` as the prior strength (non-informative weight):

| pos (observations) | k=2 | k=4 | k=1 |
|---|---|---|---|
| 0 (vacuous) | b=0.00, u=1.00 | b=0.00, u=1.00 | b=0.00, u=1.00 |
| 1 (first mention) | b=0.33, u=0.67 | b=0.20, u=0.80 | b=0.50, u=0.50 |
| 3 | b=0.60, u=0.40 | b=0.43, u=0.57 | b=0.75, u=0.25 |
| 5 | b=0.71, u=0.29 | b=0.56, u=0.44 | b=0.83, u=0.17 |
| 8 | b=0.80, u=0.20 | b=0.67, u=0.33 | b=0.89, u=0.11 |
| 15 (ESTABLISHED) | b=0.88, u=0.12 | b=0.79, u=0.21 | b=0.94, u=0.06 |

**Default W=2.** A single LLM extraction (pos=1, neg=0, W=2) starts at belief≈0.33 —
plausible but not confident. At ~13 high-trust observations the fact enters ESTABLISHED
(`StrengthBand.ESTABLISHED` = expectation≥0.85, u<0.15, confirmed at
`StrengthBand.java:27`). W is tunable via config key `kompile.kb.evidence.priorStrength`
(default 2.0).

### The Four Bugs to Kill

**Bug 1: hardcoded 0.8 at extraction time.**
`LlmKnowledgeGraphBuilder.java:387` (and identical at line 506):
```java
Double confidence = rel.getConfidence() != null ? rel.getConfidence() : 0.8;
```
Replace with `Opinion.fromBetaEvidence(sourceTrust, 0.0, 0.5, priorStrength).expectation()`,
where `sourceTrust` comes from the Pillar 5 resolver. Store the full Opinion components
in the edge's `metadataJson` under the `_opinion` key (see Provenance Keys below).

**Bug 2: structural edges default to 1.0.**
`GraphPersistenceHelper.java:255`:
```java
confidence != null ? confidence : weight != null ? weight : 1.0
```
The `1.0` fallback causes `GraphToFactStoreProjector.java:147` (`value >= 0.99`) to pin
structural edges as `Fact.observed` (hard=true), collapsing PSL MAP gradient signal.
Replace the `1.0` with `0.5` for Tika structural and `0.7` for LLM-extracted-without-score,
or better, route all edges through Pillar 2's basis-aware initializer.

**Bug 3: PSL rule weight hardcoded at 0.8.**
`IncrementalReasoningOrchestrator.java:799` and `804`:
```java
program.addRule("0.8: " + pred + "(?X) -> derived_" + pred + "(?X)");
program.addRule("0.8: " + pred + "(?X, ?Y) -> derived_" + pred + "(?X, ?Y)");
```
The initial default becomes configurable via `kompile.kb.psl.defaultRuleWeight=0.8`;
subsequent runs use `PslWeightLearningService`-persisted learned weights (the warm-start
path at line ~410 already exists).

**Bug 4: self-training (the #1 problem).**
`IncrementalReasoningOrchestrator.java:529`:
```java
Map<String, Double> softTargets = new HashMap<>(result.values());  // MAP posteriors
trainedProgram = pslWeightLearner.updateOnBatch(program, softTargets, 1);
```
`result.values()` is the MAP output — training on your own output produces
`dist(innerMAP) - dist(outerMAP) ≈ 0`. Fix:
```java
// Train toward the OBSERVED facts projected from the graph
Map<String, Double> softTargets = buildObservedTargets(factStore);
// buildObservedTargets: factStore.allFacts() → atomKey → f.value()
```
The `factStore` reference is available at the call site (it is used in
`buildProgramFromFactStore(factStore)` at line 400). The same fix applies to MEBN weight
learning at line 651 (same pattern, `result.values()` → `factStore.allFacts()`).

### Wiring the Corroboration Counter

`FactPromotionTracker.checkPromotion()` increments `state.corroborationCount` at line 161
but never updates any weight. Fix: make `checkPromotion` also accumulate `evidencePos`:

```java
// In FactPromotionTracker.checkPromotion:
double pos = state.evidencePos + sourceTrust;   // sourceTrust from caller
double neg = state.evidenceNeg;
Opinion current = Opinion.fromBetaEvidence(pos, neg, 0.5, priorStrength);
state.evidencePos = pos;
// persist current.expectation() as the new edge confidence via InferredFactRowRepository
// StrengthBand.from(current) → check for promotion
```

`InferredFactRow` (entity at `InferredFactRow.java`) currently has `corroboration_count INT`
(line 130) but no `evidence_pos` or `evidence_neg` columns. These two `DOUBLE` columns are
the slice-1 schema addition; `corroboration_count` becomes a count-only integer (number of
independent observations, kept for diagnostics).

### Provenance Keys

`GraphProvenanceKeys.java` (at `ai.kompile.knowledgegraph.domain.GraphProvenanceKeys`) currently
defines `_source`, `_sourceDocumentId`, `_sourceChunkId`, `_crawlRunId`, `_extractionModel`,
`_extractedAt`, `_extractionLogId` (lines 34–51). Add:

| Key constant | String value | Type | Meaning |
|---|---|---|---|
| `OPINION` | `_opinion` | JSON | Full Opinion {belief,disbelief,uncertainty,baseRate} at write time |
| `EVIDENCE_POS` | `_evidencePos` | double | Cumulative positive evidence weight |
| `EVIDENCE_NEG` | `_evidenceNeg` | double | Cumulative negative evidence weight |
| `PRIOR_STRENGTH` | `_priorStrength` | double | W used in fromBetaEvidence |
| `SOURCE_TRUST` | `_sourceTrust` | double | Trust of the source that created/updated this observation |
| `BASIS_TYPE` | `_basisType` | String | STRUCTURAL / LLM_EXTRACTION / PSL_INFERENCE / MEBN_INFERENCE / CORROBORATION |
| `CORROBORATION_COUNT` | `_corroborationCount` | int | Number of independent observations |
| `VALID_FROM` | `_validFrom` | ISO-8601 | Start of the fact's validity window (Pillar 3) |
| `VALID_TO` | `_validTo` | ISO-8601 | End of the validity window; null = still valid (Pillar 3) |

`_opinion` is already written for INFERRED edges by
`InferredFactGraphMaterializer.buildMetaJson()` (fusedBelief/fusedDisbelief/fusedUncertainty).
Extend this pattern to EXTRACTED and STRUCTURAL edges.

---

## Pillar 2 — Basis Taxonomy

Confidence dynamics are per-basis, not governed by one global W.

### Three Basis Classes

**DEDUCTIVE / STRUCTURAL**
Facts that are derivable from domain structure with near-certainty from a single observation:
reporting-line implies manages; `From:` header implies authored-by; GTIN-14 barcode implies
product-identity. One observation gives HIGH or ESTABLISHED belief immediately. No slow climb.
These are Pillar 4's domain-induction output: each domain names which predicates are
deductive in its context.

Examples (with confirmed initializers):
- Email `From:` header → `person_has_email` (belief≈0.92, u≈0.05)
- Email `From:` domain → `person_belongs_to_org` (belief≈0.70, u≈0.20; excludes free providers)
- `HAS_BARCODE` with GTIN-14 → `product_has_gtin` (belief≈0.96, u≈0.02)
- Heading-derived concept (Tika) → `document_covers_concept` (belief≈0.78, u≈0.18)

Implementation: a new `StructuralFactAssertionService` builds
`new Opinion(belief, 0.0, uncertainty, 0.5)` directly from the catalog table and writes
`_basisType=STRUCTURAL` in provenance. No Beta accumulation on first write; subsequent
corroborating extractions can fuse in via `cumulativeFuse`.

**CORROBORATIVE / INFERRED**
Soft LLM-extracted signals, PSL-inferred relations, MEBN posteriors. These start LOW and
accumulate via the Beta/W path from Pillar 1. THIS is the only class where "how many
observations before certain" applies. W=2 by default; per-predicate W is Pillar 4's output.

**ASSERTED / PINNED**
Human corrections via `KbCorrectionService.correct()` (endpoint
`POST /api/kb-grounding/{factSheetId}/corrections`, `KbGroundingAuditController.java:88`).
These are certain until explicitly reverted
(`DELETE /api/kb-grounding/{factSheetId}/corrections/{atomKey}`, line 108). They receive
`_basisType=ASSERTED` and are not subject to Beta accumulation.

### Basis Determines W and the Trust Discount

The `W` parameter in `fromBetaEvidence(pos, neg, baseRate, W)` should be per-basis:
- STRUCTURAL: W=0.1 (one observation gives near-certainty)
- CORROBORATIVE: W=2.0 (default, configurable per-predicate by induction)
- ASSERTED: W=0 (certainty is absolute; implemented as `belief=1.0, u=0.0`)

This means the same `Opinion.fromBetaEvidence` call unifies all three classes — only W
and the initial pos vary.

---

## Pillar 3 — Validity-Time

Confidence and validity-time are **separate axes**. A fact can be ESTABLISHED (high
confidence) at time t and invalid at time t+1 (e.g., a reporting relationship ended).
These must not be conflated.

### Design

`_validFrom` and `_validTo` ride in `GraphNode.getMetadata()` and edge `metadataJson`,
using the store-agnostic metadata seam already established by provenance (see Pillar 1's
provenance key table). Do NOT introduce new JPA columns for this — the live store is the
`@Primary` matrix/vector store, and new JPA columns are dead on that path.

Semantics:
- `_validFrom`: ISO-8601 instant when the fact became true. For extraction-time facts, set
  to the document's authorship date if available, else the extraction timestamp.
- `_validTo`: ISO-8601 instant when the fact ceased to be true. `null` = still valid.
- "TRUE AS OF t" query: a fact is valid at query time T if
  `_validFrom <= T` and (`_validTo == null` or `_validTo > T`).

Staleness is handled by decay / `BeliefReviser` / `ContradictionDetector`, NOT by lowering
confidence at current time. Confidence = "how sure we are this was true during the validity
window." Staleness = "the validity window has ended."

### Where Valid-Time Is Written

1. At extraction: when a document has a date field (email `Date:` header, PDF creation date,
   CSV timestamp column), write `_validFrom` from that date. Write `_validTo=null`.
2. At contradiction: when `ContradictionDetector` / `BeliefReviser` determines a fact has
   been superseded (new value contradicts old), set `_validTo=now()` on the old fact before
   creating the new one. The old fact is not deleted — it becomes a historical record.
3. At human correction: `KbCorrectionService.correct()` sets `_validTo=now()` on the
   superseded fact when `tombstone=true`.

This is slice-1 work: the keys are added to `GraphProvenanceKeys` and written at
extraction/correction time. Query-time filtering is a follow-on.

---

## Pillar 4 — Domain Induction

The model is parameterized by the domain, not hardcoded for email. Email rules are merely
what induction emits for an email corpus — not a hardcoded email catalog.

### What Induction Must Emit

Extend `OntologyDerivationService`
(`ai.kompile.app.ontology.OntologyDerivationService`) and
`OntologyDerivationController`
(`ai.kompile.app.web.controllers.OntologyDerivationController`) — which expose
`POST /api/process/ontology/derive` and `GET /api/process/ontology/derive/candidates` — to
additionally emit:

**(a) Domain structural rules.** Which predicates are DEDUCTIVE in this domain (Pillar 2).
For an email corpus, induction sees From/To/Cc headers and should assert that From-header
predicates are STRUCTURAL. For an HR corpus it should assert that job-title → role predicates
are STRUCTURAL. This is emitted as a `DomainStructuralRules` block alongside the `OntologySchema`.

**(b) Per-source trust priors.** Assess each document type's reliability for this domain.
For a financial corpus, CSV extracts from accounting systems get higher trust than email prose.
Emitted as `SourceTrustPriors` (source-type → trust scalar map).

**(c) Per-predicate evidence dynamics.** For each predicate class in the schema, is it
DEDUCTIVE (W small) or CORROBORATIVE (W=2.0)? What is the per-predicate W? Emitted as
`PredicateDynamics` (predicate-type → W).

**(d) Domain identity (for cold-start template matching).** A domain classifier that matches
the induced schema against the starting catalog (see next section).

### Starting Catalog of Domain Templates

A thin set of common-domain templates that induction MATCHES then refines. This prevents
cold-start from being entirely blank. Templates:

| Template | Key predicates | DEDUCTIVE rules | Default W |
|---|---|---|---|
| Communication (email, chat) | authored_by, sent_to, replied_to, belongs_to_thread | From-header predicates | 0.1 |
| Org / HR | reports_to, manages, belongs_to_dept, has_role | org-chart edges, job-title derivations | 0.1 |
| Financial | transaction_of, belongs_to_account, approved_by | ledger entries, account containment | 0.1 |
| Process / workflow | precedes, triggers, belongs_to_process | activity-log order constraints | 0.1 |

Induction matches the observed predicate distribution against these templates (cosine
similarity over predicate names + their arity). The best-matching template's DEDUCTIVE rules
become the starting structural catalog, refined by induction's own output.

### Domain-Agnostic Floor (Always On)

Two structural rules apply regardless of domain, because they are implemented code
already in production:
- Identity resolution: `EntityResolutionService` (467 lines) + `RESOLVES_TO` edges via
  `BarcodeIdentityGraphService`. Already materializes IDENTIFIER nodes.
- Document containment: Tika structural extraction → `document_contains_entity` edges.

These need no induction step — they fire on every crawl.

---

## Pillar 5 — Layered Source Trust

Trust is not a single number. It is a composable resolver:

```
trust(source, domain) =
    flat baseline (type default)
  + schema per-type override (induction output, Pillar 4)
  + LLM domain assessment (induction step proposes weights by assessing the data)
  + feedback adjustment (corrections shift it upward/downward)
```

All four layers coexist and stay independently updatable. The resolved trust feeds:
1. The Beta evidence weighting: a source contributes `trust` to `pos`, not `1.0`.
2. Output ranking: higher-trust-sourced facts surface first in the presentation tier.

### Default Trust Table

These are the flat baseline defaults (layer 1), configurable via
`kompile.kb.source-trust.*` properties:

| Source type | Trust t | Rationale |
|---|---|---|
| Email `From:` header | 0.95 | Protocol field; RFC 5322 authoritative |
| Email `To:`/`Cc:` header | 0.90 | Same protocol certainty; slight aliasing risk |
| Structured document (CSV/JSON/XML) | 0.85 | Machine-readable, minimal interpretation |
| PDF / Office document | 0.70 | Human-authored, high fidelity |
| Email body text | 0.65 | Prose; primary source |
| Web scrape | 0.45 | Publisher-controlled, possible noise |
| LLM extraction from text | 0.60 | Model can hallucinate; evidence is indirect |
| LLM extraction from structured format | 0.70 | Structure constrains the model |
| PSL/MEBN inference | (MAP posterior directly) | Not an independent observation |

Discounting rule: when a new corroborating observation arrives,
`evidencePos += sourceTrust` (not `+= 1.0`). This is Jøsang's opinion discounting.

### Contradiction Evidence

When two sources disagree (`worksAt(Alice, Acme)=true` vs. `=false`), the negative
source contributes `sourceTrust` to `evidenceNeg`. `Opinion.fromBetaEvidence(pos, neg, 0.5, W)`
handles this: if `pos ≈ neg`, the fact becomes uncertain (high `u`) even with many observations
— which is correct. `ContradictionDetector` and `BeliefReviser` in
`IncrementalReasoningOrchestrator` already handle TMS retraction; they should also update
`evidenceNeg` when contradiction is confirmed.

### Layered Resolver Implementation

New class `SourceTrustResolver` (in `kompile-knowledge-graph`) consulted by:
- `LlmKnowledgeGraphBuilder` at extraction time
- `FactPromotionTracker.checkPromotion` when accumulating evidence
- `GraphPersistenceHelper` when writing structural edges

The resolver accepts `(sourceType, factSheetId, domain)` and returns the composed trust
scalar. It reads layer-1 from config, layer-2 from the induction output stored alongside
the ontology schema, layer-3 from `SourceWeightRepository` (existing `baseWeight` column,
re-interpreted as user-adjusted trust delta).

---

## Pillar 6 — Presentation Contract

**Humans never see the confidence number.**

This is the most important pillar. The numeric confidence is an internal sort key only.
The surface speaks two human languages.

### Language A: "TRUE AS OF t" — Deductive Facts

For facts whose `_basisType=STRUCTURAL` and whose validity window covers now
(`_validTo==null` or `_validTo > now()`): display a binary certainty badge.
"This reporting relationship is known with certainty (from org-chart data, as of 2024-Q1)."
No percentage. No uncertainty disclosure. The user asked what is true — a deductive fact IS
true by construction.

### Language B: Ranked List — Everything Else

For CORROBORATIVE and INFERRED facts: display a ranked list ordered by `expectation()`,
descending. The rank position IS the confidence signal — position 1 is most supported,
not "84% confident." Coarse ordinals are acceptable ("strongly supported," "supported,"
"weakly supported") — these map directly to `StrengthBand` (ESTABLISHED→"strongly supported",
HIGH→"supported", PROBABLE→"weakly supported", SPECULATIVE→"uncertain").
Never show a percentage.

The existing `KbGroundingAuditController.java` (`GET /api/kb-grounding/{factSheetId}/facts`,
line 137) and the facts-by-tier frontend panel serve as the display surface. The panel
already receives `StrengthBand`-filtered lists. The change is presentation only: replace
any numeric rendering with band labels and rank positions.

### Contested Facts and Perspectival Knowledge

Most knowledge is lossy or subjective. "How something is done" varies by role, reporting
line, and what a person can observe. Terminology is local. These are features, not bugs.

**Core rule: sources keep their OWN Opinion attributed by provenance. Do not prematurely
fuse across sources that disagree.**

When two sources give different values for the same predicate:
- If `evidencePos ≈ evidenceNeg`: `Opinion.fromBetaEvidence` already yields high `u` (uncertainty).
  Surface as: "CONTESTED — N views" with per-source positions listed.
  Do NOT show a false-precise blend as if it were a settled fact.
- If one source clearly dominates (`pos >> neg`): surface the dominant view with a footnote
  "N source(s) disagree."

Identity resolution (via `EntityResolutionService` / `RESOLVES_TO`) reconciles referents
across sources (Alice = alice@corp.com = "A. Smith"). But a term's MEANING stays tied to
its source. The fact that two people use the word "approval" differently is information that
must be preserved in per-source provenance, not collapsed into one merged definition.

**Implementation:** each source's Opinion is stored as a separate provenance-attributed
edge (or a `_perSourceOpinions` JSON array in the edge's metadata). The fused Opinion is
computed on read for display, but the per-source opinions are the durable record. Disagreement
is surfaced when the fused `uncertainty > 0.40` (PROBABLE or below) and `disbelief > 0.15`.

---

## Phased Build Plan

### Slice 1 — Opinion-from-evidence base + validity-time metadata

**Pillar 1 base bug-fixes + Pillar 3 valid-time keys.**
No new architecture. Fix the four bugs. Add the schema columns. Add the provenance keys.

| File | Location | What changes |
|---|---|---|
| `LlmKnowledgeGraphBuilder.java` | kompile-knowledge-graph/.../builder/impl/ | Line 387: replace `0.8` fallback with `Opinion.fromBetaEvidence(sourceTrust, 0.0, 0.5, priorStrength).expectation()`. Line 506: same. Write `_opinion`, `_evidencePos`, `_basisType=LLM_EXTRACTION`, `_sourceTrust` to edge metadata. |
| `GraphPersistenceHelper.java` | kompile-crawl-graph/.../ | Line 255: replace `1.0` fallback with `0.5` (Tika structural); supply `_basisType` based on caller context. |
| `IncrementalReasoningOrchestrator.java` | kompile-knowledge-graph/.../reasoning/ | Line 529: replace `result.values()` with `buildObservedTargets(factStore)` (extracts `factStore.allFacts()` → atomKey → value). Line 651: same fix for MEBN training. Lines 799/804: make `0.8` configurable via `kompile.kb.psl.defaultRuleWeight`. |
| `FactPromotionTracker.java` | kompile-knowledge-graph/.../reasoning/ | Line 161 (`corroborationCount.incrementAndGet()`): also accumulate `state.evidencePos += sourceTrust`. Add `sourceTrust` parameter to `checkPromotion()`. Compute `Opinion.fromBetaEvidence(state.evidencePos, state.evidenceNeg, 0.5, W)` and persist `expectation()` as the new edge confidence. |
| `InferredFactRow.java` | kompile-knowledge-graph/.../persistence/dual/ | Add `@Column evidence_pos DOUBLE` and `evidence_neg DOUBLE` columns. |
| `GraphProvenanceKeys.java` | kompile-knowledge-graph/.../domain/ | Add constants: `OPINION`, `EVIDENCE_POS`, `EVIDENCE_NEG`, `PRIOR_STRENGTH`, `SOURCE_TRUST`, `BASIS_TYPE`, `CORROBORATION_COUNT`, `VALID_FROM`, `VALID_TO`. |
| New: `SourceTrustResolver.java` | kompile-knowledge-graph/.../confidence/ | Tier-1 trust lookup by source type. Consulted by `LlmKnowledgeGraphBuilder` + `FactPromotionTracker`. Config-backed defaults from table in Pillar 5. |
| `application.properties` | kompile-app-lite/src/main/resources/ | Add `kompile.kb.evidence.priorStrength=2.0`, `kompile.kb.psl.defaultRuleWeight=0.8`, and `kompile.kb.source-trust.*` keys. |

Slice-1 test contracts:
- A single LLM-extracted fact must have `expectation() < 0.50` on first write (W=2, t=0.6 → ≈0.23).
- After 8 corroborations at trust t=1.0, the same fact must be in `StrengthBand.HIGH` (expectation≥0.70).
- PSL weight learner gradient must be non-zero after slice-1 fix (verified by asserting
  that rule weights differ from 0.8 after one cascade on a graph with observed facts).
- `_validFrom` must be present on every new edge after this slice.

### Slice 2 — Basis taxonomy + domain induction + starting catalog

**Pillar 2 + Pillar 4.**

| File | Location | What changes |
|---|---|---|
| New: `StructuralFactAssertionService.java` | kompile-crawl-graph or kompile-knowledge-graph | Takes (sourceType, predicateKey, belief, uncertainty), builds `new Opinion(belief, 0.0, uncertainty, 0.5)`, writes `_basisType=STRUCTURAL`. Called from `EmailGraphExtractor` after person/email node creation (currently creates edges at weight `1.0` with no Opinion). |
| `OntologyDerivationService.java` | kompile-app-main/...ontology/ | Extend `derive()` to also return `DomainStructuralRules` (predicate → basis class), `SourceTrustPriors` (source-type → trust), `PredicateDynamics` (predicate → W). |
| `OntologyDerivationController.java` | kompile-app-main/.../web/controllers/ | Update `POST /api/process/ontology/derive` response DTO to include the three new blocks. |
| New: `DomainTemplateCatalog.java` | kompile-knowledge-graph/.../domain/ | Static catalog of four domain templates (Communication, Org/HR, Financial, Process/Workflow). Induction matches against these via predicate-distribution cosine similarity. |
| `SourceTrustResolver.java` | (created in slice 1) | Add layer-2 support: read per-domain trust priors from the induction output stored alongside the ontology binding. |
| Personal email exclusion list | config or new `PersonalEmailDomains.java` | Lookup used by `StructuralFactAssertionService` to gate `person_belongs_to_org` from email domain. Configurable via `kompile.kb.personal-email-domains`. |

### Slice 3 — Layered source trust (full resolver)

**Pillar 5 full implementation.**

| File | Location | What changes |
|---|---|---|
| `SourceTrustResolver.java` | (created in slice 1, extended in slice 2) | Add layer-3 (LLM assessment from induction) and layer-4 (feedback delta from `SourceWeightRepository`). Wire full 4-layer composition. |
| `SourceWeightRepository` / `SourceWeightingServiceImpl` | kompile-knowledge-graph | Re-interpret `baseWeight` as trust delta (layer-4 adjustment). Document this contract. |
| `FactPromotionTracker.java` | (updated in slice 1) | Pass `resolver.resolve(sourceType, factSheetId, domain)` as `sourceTrust` to `checkPromotion`. |
| `ContradictionDetector.java` / `BeliefReviser.java` | kompile-knowledge-graph | On confirmed contradiction, add `sourceTrust` to `evidenceNeg` in the fact's metadata (update via `InferredFactRowRepository`). |

### Slice 4 — Presentation contract + perspectival facts UI

**Pillar 6.**

| File | Location | What changes |
|---|---|---|
| `KbGroundingAuditController.java` | kompile-knowledge-graph/.../grounding/controller/ | `GET /api/kb-grounding/{factSheetId}/facts` response: add `basisType`, `validFrom`, `validTo`, `isContested` (fused uncertainty > 0.40 and disbelief > 0.15), `perSourceOpinions` list. |
| Facts-by-tier Angular panel | kompile-app-main frontend | Replace numeric confidence display with band labels (ESTABLISHED→"strongly supported", etc.) and rank-position ordinals. Show binary certainty badge for `_basisType=STRUCTURAL` with valid-at-now. Show "CONTESTED — N views" when `isContested=true`. |
| New: per-source Opinion storage | Edge metadataJson | Add `_perSourceOpinions: [{source, trust, belief, disbelief, uncertainty}]` JSON array alongside the fused `_opinion`. Written at extraction time; appended on each new corroboration. |
| New endpoint: `GET /api/kb-grounding/{factSheetId}/facts/{atomKey}/sources` | `KbGroundingAuditController` | Returns per-source opinions for a single fact, for the contested-views panel. |

---

## How the Full Model Composes (End-to-End Flow)

```
Observation arrives (LLM extraction, Tika structural, or human assertion):
  1. Resolve sourceTrust t from SourceTrustResolver (4-layer)
  2. Determine basisType from source/predicate context (Pillar 2)
  3. If STRUCTURAL: build Opinion(belief, 0, uncertainty, 0.5) from catalog; skip to step 6
     If ASSERTED:   build Opinion(1, 0, 0, 1); skip to step 6
     If CORROBORATIVE: proceed with Beta accumulation below
  4. If first observation: Opinion = fromBetaEvidence(t, 0, 0.5, W)
     If corroboration:     evidencePos += t; Opinion = fromBetaEvidence(evidencePos, evidenceNeg, 0.5, W)
  5. Write Opinion.expectation() as edge confidence
  6. Write full Opinion components + basisType + sourceTrust + validFrom in metadataJson
  7. Write per-source Opinion entry in _perSourceOpinions
  8. FactPromotionTracker.checkPromotion() reads Opinion, computes StrengthBand.from(Opinion),
     emits FactPromotedEvent if band improved
  9. Graph portability (Phase 1 export) carries _opinion etc. in GraphNode.metadataJson

PSL weight learning (AFTER slice 1 fix):
  1. buildObservedTargets(factStore): extract observed facts as training targets
  2. updateOnBatch(program, observedTargets, 1): gradient = dist(innerMAP) - dist(observedFacts)
  3. Weights converge toward making derived facts consistent with extracted observations

Valid-time query:
  Filter edges where (_validFrom <= T) AND (_validTo IS NULL OR _validTo > T)
  Then rank by Opinion.expectation(), descending

Presentation:
  STRUCTURAL + valid-now → binary "TRUE AS OF t" badge
  CORROBORATIVE + contested (uncertainty > 0.40 AND disbelief > 0.15) → "CONTESTED — N views"
  Everything else → ranked list with band label
  NEVER show a percentage
```
