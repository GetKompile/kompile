# Opinion / Confidence Primitive Design
## A First-Class Epistemic Strength Primitive for kompile-graph-reasoning

**Status:** DESIGN — 2026-06-21
**Author:** design session
**Scope:** Foundational epistemic primitive for the infra-free `kompile-graph-reasoning` library.
All downstream systems — materialization (`InferredFact`), grounding (`VerifyResult`), calibration
(`StrengthCalibrator`), band layering (`StrengthLayer`), fusion (hydration chain), and pruning —
inherit from this single primitive. No new Spring dependencies. No Maven changes.

**Complements (do not duplicate):**
- `fact-store-audit-tuning-correction-design.md` — strength-band layering and correction/PIN surface;
  this doc provides the primitive `StrengthLayerResolver` operates on
- `domain-object-grounding-design.md` — `GroundedElement` + `StrengthCalibrator` + Platt scaling;
  `StrengthCalibrator` feeds into `Opinion` and projects back out to scalars
- `graph-hydration-inference-chain-design.md` — noisy-OR / multiplicative fusion (§4.5); this doc
  provides the fusion algebra that replaces the ad-hoc formulas there
- `reasoning-trail-explainability-design.md` — `ReasoningTrail` / `DerivationTree`; `Opinion` becomes
  the confidence field on each derivation hop

---

## 0. The Problem in Precise Terms

The library currently uses **two scalars** to represent confidence:

| File | Scalars | Problem |
|---|---|---|
| `fol/InferredFact.java:47–56` | `value` (PSL soft-truth / posterior) + `confidence` (separate [0,1]) | Both are set to the same value in every factory (`fromEntailment` line 87, `of()` line 112); the second-order signal is structurally present but never populated |
| `fol/grounding/VerifyResult.java:43–57` | `confidence` + trichotomous `Status` | UNKNOWN carries `confidence=0.0` (line 79) — indistinguishable from REFUTED at `confidence=0.0`; "no evidence at all" and "strong evidence against" are the same double |
| `domain/AttributionConfidence.java:16–71` | 5 qualitative bands (DEFINITIVE/HIGH/MODERATE/LOW/INSUFFICIENT) at thresholds 0.9/0.7/0.4/0.1/0.0 | Parallel band scheme to the ESTABLISHED/PROBABLE/SPECULATIVE/SUPPRESSED 4-band scheme proposed in `fact-store-audit-tuning-correction-design.md §3.1`; two competing vocabularies |

The root epistemic defect: **a scalar `confidence=0.4` is ambiguous**. It can mean:
- "Balanced evidence: some facts support this, some refute it" (epistemic conflict)
- "No evidence at all: we know nothing about this atom" (pure ignorance)

These are opposite epistemic states but map to the same double. Subjective Logic's trinomial
`{belief, disbelief, uncertainty}` separates them cleanly: conflict is high belief *and* high
disbelief (both > 0 despite summing with low uncertainty to 1); ignorance is high uncertainty alone.

The `VerifyResult.Status` trichotomy (`SUPPORTED`/`REFUTED`/`UNKNOWN`) hints at this distinction
but only at query time. `InferredFact` cannot represent "refuted" or "unknown" states — it only
stores a positive soft-truth.

---

## 1. The `Opinion` Primitive — Subjective Logic

### 1.1 Mathematical Structure

Following Jøsang (2016) *Subjective Logic: A Formalism for Reasoning Under Uncertainty*,
an **Opinion** is a 4-tuple:

```
Opinion = (belief b, disbelief d, uncertainty u, base_rate a)

Constraints:
  b + d + u = 1         (the simplex constraint; all in [0,1])
  a ∈ [0,1]            (prior probability used when u = 1)
```

**Projection to probability (expectation):**
```
E[Opinion] = b + a × u
```

This is the "projected probability" — the scalar used in all existing APIs as `value` or `confidence`.

**Evidence-count interpretation (Beta-distribution mapping):**
```
Given r (positive observations) and s (negative observations):
  b = r / (r + s + k)
  d = s / (r + s + k)
  u = k / (r + s + k)    where k is the prior strength (default k=2 for non-informative)
```

This means an `Opinion` with `(b=0.0, d=0.0, u=1.0)` represents **vacuous belief** — no evidence
either way. An `Opinion` with `(b=0.45, d=0.45, u=0.1)` represents **conflicting evidence** — roughly
balanced support and refutation with low residual uncertainty. The scalar projection of both is near
`a` (the base rate), but the `Opinion` structures are entirely different.

### 1.2 Why Subjective Logic, not alternatives

**Alternative 1 — Plain two-scalar `(value, uncertainty)`.** Already structurally present in
`InferredFact` (lines 49, 50) but `value + uncertainty` must equal 1 only if we impose it; nothing
forces `disbelief` to be computed. Without `disbelief`, we cannot represent "balanced evidence"
separately from "ignorance" — they collapse to the same `uncertainty`. Rejected.

**Alternative 2 — Full Beta distribution `Beta(α, β)`.** More expressive than Opinion (continuous
over the probability simplex). But it requires floating-point α/β parameters with no natural
serialization to the existing `InferredFact.toJson()` hand-rolled format, and the mean/variance
interface is less intuitive for rule-derived facts where we have exact vote counts. Opinion is a
Beta distribution's point summary. For non-Dirichlet multi-valued atoms (§6.ii), a full Dirichlet is
justified; for binary predicates (the dominant case in the lib), Opinion is sufficient.

**Alternative 3 — Keep scalars, add a separate `ConfidenceInterval`.** Does not separate
ignorance from conflict. Rejected.

**Decision: binomial Opinion (3-component) for all binary predicates. Full Dirichlet deferred to
multi-valued atoms (§6.ii fork). The mapping to Beta evidence counts is the internal API; the
Opinion tuple is the external contract.**

### 1.3 Java Record

Location: `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/Opinion.java`
(new package `confidence` under the lib root; keeps it orthogonal to `fol`, `psl`, `domain`)

```java
// kompile-graph-reasoning/.../confidence/Opinion.java  (new, infra-free)
/**
 * Subjective Logic opinion: a first-class epistemic strength primitive.
 *
 * <p>Encodes three orthogonal epistemic states for a binary proposition:
 * <ul>
 *   <li>{@code belief}      — degree of positive evidence (b ∈ [0,1])</li>
 *   <li>{@code disbelief}   — degree of negative evidence (d ∈ [0,1])</li>
 *   <li>{@code uncertainty} — residual ignorance (u ∈ [0,1]; u = 1−b−d)</li>
 * </ul>
 * The simplex constraint {@code b + d + u = 1} is enforced at construction.
 *
 * <p>The projected probability (expectation) is {@code E = b + baseRate × u}.
 * When {@code uncertainty = 1.0}, the opinion is <em>vacuous</em> — no evidence
 * has been accumulated; this is distinct from {@code disbelief = 1.0} (certain refutation).
 *
 * @param belief      degree of positive evidence [0,1]
 * @param disbelief   degree of negative evidence [0,1]
 * @param uncertainty residual ignorance [0,1]; must equal 1 − belief − disbelief
 * @param baseRate    prior probability used when uncertainty is high; default 0.5 for
 *                    unknown-base-rate atoms; 1.0 for hard-observed facts
 */
public record Opinion(double belief, double disbelief, double uncertainty, double baseRate) {

    private static final double SIMPLEX_EPSILON = 1e-9;

    public Opinion {
        if (belief < 0 || disbelief < 0 || uncertainty < 0)
            throw new IllegalArgumentException("All opinion components must be ≥ 0");
        double sum = belief + disbelief + uncertainty;
        if (Math.abs(sum - 1.0) > SIMPLEX_EPSILON)
            throw new IllegalArgumentException(
                "belief + disbelief + uncertainty must equal 1.0; got " + sum);
        if (baseRate < 0 || baseRate > 1)
            throw new IllegalArgumentException("baseRate must be in [0,1]");
    }

    // ─── Projections ──────────────────────────────────────────────────────────

    /** Projected probability (expectation): belief + baseRate × uncertainty. */
    public double expectation() { return belief + baseRate * uncertainty; }

    /** Uncertainty mass — how much of the simplex is undecided. */
    public double ignorance() { return uncertainty; }

    /** True when no evidence has been accumulated (u = 1.0). */
    public boolean isVacuous() { return Math.abs(uncertainty - 1.0) < SIMPLEX_EPSILON; }

    /** True when evidence strongly conflicts (both belief and disbelief > threshold). */
    public boolean isConflicted(double threshold) {
        return belief > threshold && disbelief > threshold;
    }

    // ─── Factories ────────────────────────────────────────────────────────────

    /** Vacuous opinion: no evidence, maximum uncertainty, base rate a. */
    public static Opinion vacuous(double baseRate) {
        return new Opinion(0.0, 0.0, 1.0, baseRate);
    }

    /** Vacuous opinion with default base rate 0.5. */
    public static Opinion vacuous() { return vacuous(0.5); }

    /**
     * From positive evidence r, negative evidence s, and prior strength k.
     * Default k=2 (non-informative prior; equivalent to 1 pseudo-positive + 1 pseudo-negative).
     */
    public static Opinion fromEvidence(double r, double s, double baseRate, double k) {
        double total = r + s + k;
        return new Opinion(r / total, s / total, k / total, baseRate);
    }

    public static Opinion fromEvidence(double r, double s) {
        return fromEvidence(r, s, 0.5, 2.0);
    }

    /**
     * From a raw PSL soft-truth value.
     * Uncertainty is derived from the rule-support count:
     * with n supporting rules, u = 1 / (n + 1); b = softTruth × (1 − u); d = (1 − softTruth) × (1 − u).
     * When supportCount = 0 (no rules fired), returns a vacuous opinion with belief = softTruth × 0.5.
     */
    public static Opinion fromPslSoftTruth(double softTruth, int supportCount, double baseRate) {
        double u = 1.0 / (supportCount + 1.0);
        double spread = 1.0 - u;
        return new Opinion(softTruth * spread, (1.0 - softTruth) * spread, u, baseRate);
    }

    /**
     * From a Bayesian posterior (MEBN output).
     * Posteriors are proper probabilities; uncertainty is 0 when evidence is complete.
     * Estimate u from the information gain relative to the prior.
     * When posterior = baseRate (no information), uncertainty is near 1.
     */
    public static Opinion fromBayesianPosterior(double posterior, double baseRate) {
        double infGain = Math.abs(posterior - baseRate);
        double u = Math.max(0.0, 1.0 - 2.0 * infGain);
        double spread = 1.0 - u;
        return new Opinion(posterior * spread, (1.0 - posterior) * spread, u, baseRate);
    }

    /**
     * From an observed hard Fact (value=1.0, hard=true).
     * Certain positive evidence: belief=1.0, disbelief=0.0, uncertainty=0.0.
     */
    public static Opinion fromObservedFact(double value) {
        // Clamp disbelief to avoid floating-point simplex violations when value ≈ 1.0
        double b = Math.min(1.0, Math.max(0.0, value));
        double d = Math.min(1.0 - b, Math.max(0.0, 1.0 - value));
        double u = Math.max(0.0, 1.0 - b - d);
        return new Opinion(b, d, u, value);
    }

    /**
     * From a calibrated embedding / RotatE score (already in [0,1] via Platt scaling).
     * Uncertainty is fixed at a configurable prior (default 0.3 = moderate uncertainty
     * for embedding signals per graph-hydration-inference-chain-design.md §4.3).
     */
    public static Opinion fromEmbeddingScore(double calibratedScore, double embeddingUncertainty) {
        double spread = 1.0 - embeddingUncertainty;
        return new Opinion(
            calibratedScore * spread,
            (1.0 - calibratedScore) * spread,
            embeddingUncertainty,
            0.5
        );
    }

    // ─── Fusion operators (§3) ──────────────────────────────────────────────

    /**
     * Cumulative fusion (Jøsang §12.2): combine two independent opinions about the same
     * proposition from different sources. This is the Dempster–Shafer combination rule
     * adapted for Subjective Logic.
     *
     * Used when: two extractors, or a rule-derived opinion and an embedding opinion,
     * both address the same atom independently (no shared antecedents).
     *
     * Returns a more certain (lower u) opinion when both sources agree.
     * Returns a conflicted (high b AND high d) opinion when they disagree.
     */
    public Opinion cumulativeFuse(Opinion other) {
        double ua = this.uncertainty;
        double ub = other.uncertainty;
        double denom = ua + ub - ua * ub;
        if (denom < 1e-12) {
            // Both certainties at 1.0 (u=0) — average (degenerate case)
            return new Opinion(
                (this.belief + other.belief) / 2,
                (this.disbelief + other.disbelief) / 2,
                0.0,
                (this.baseRate + other.baseRate) / 2
            );
        }
        double b = (this.belief * ub + other.belief * ua) / denom;
        double d = (this.disbelief * ub + other.disbelief * ua) / denom;
        double u = ua * ub / denom;
        double a = (this.baseRate * ub + other.baseRate * ua) / (2.0 - ua - ub);
        return new Opinion(
            clamp(b), clamp(d), clamp(u), clamp(a)
        );
    }

    /**
     * Averaging fusion: arithmetic mean of belief, disbelief, uncertainty, baseRate.
     * Use when: two dependent signals share common antecedents (e.g., two PSL rules
     * both body-depend on the same observed fact). Averaging avoids double-counting.
     * Per graph-hydration-inference-chain-design.md §4.5 ("take the max or average
     * for dependent signals").
     */
    public Opinion averageFuse(Opinion other) {
        return new Opinion(
            (this.belief + other.belief) / 2,
            (this.disbelief + other.disbelief) / 2,
            (this.uncertainty + other.uncertainty) / 2,
            (this.baseRate + other.baseRate) / 2
        );
    }

    /**
     * Consensus (Jøsang §12.5): weight opinions by the inverse of their uncertainty.
     * Use when: N sources of varying reliability, each carrying its own base rate.
     * Returns the weighted centroid on the opinion simplex.
     */
    public static Opinion consensus(List<Opinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return vacuous();
        if (opinions.size() == 1) return opinions.get(0);
        double totalWeight = 0;
        double sumB = 0, sumD = 0, sumA = 0;
        for (Opinion o : opinions) {
            double w = 1.0 - o.uncertainty;  // certainty as weight
            totalWeight += w;
            sumB += w * o.belief;
            sumD += w * o.disbelief;
            sumA += w * o.baseRate;
        }
        if (totalWeight < 1e-12) return vacuous();  // all vacuous → remain vacuous
        double b = clamp(sumB / totalWeight);
        double d = clamp(sumD / totalWeight);
        double u = clamp(1.0 - b - d);
        double a = clamp(sumA / totalWeight);
        return new Opinion(b, d, u, a);
    }

    private static double clamp(double v) { return Math.min(1.0, Math.max(0.0, v)); }

    // ─── Trichotomy projection (§4) ─────────────────────────────────────────

    /**
     * Project to VerifyResult.Status using configurable thresholds.
     * SUPPORTED when belief dominates and expectation ≥ supportThreshold.
     * REFUTED when disbelief dominates and expectation < refuteThreshold.
     * UNKNOWN when uncertainty dominates (ignorance is the primary state).
     *
     * @param supportThreshold  default 0.5 (consistent with KbVerifier DEFAULT_THRESHOLD)
     * @param refuteThreshold   default 0.3
     * @param unknownThreshold  uncertainty above this → UNKNOWN (default 0.6)
     */
    public VerifyStatusProjection projectStatus(double supportThreshold,
                                                double refuteThreshold,
                                                double unknownThreshold) {
        if (uncertainty >= unknownThreshold) return VerifyStatusProjection.UNKNOWN;
        double e = expectation();
        if (e >= supportThreshold) return VerifyStatusProjection.SUPPORTED;
        if (e < refuteThreshold) return VerifyStatusProjection.REFUTED;
        return VerifyStatusProjection.UNKNOWN;
    }

    public enum VerifyStatusProjection { SUPPORTED, REFUTED, UNKNOWN }

    // ─── Band projection (§4, unifying AttributionConfidence and StrengthLayer) ──

    /**
     * Project to a unified StrengthBand using both expectation and uncertainty.
     * A high expectation with high uncertainty is demoted (PROBABLE, not ESTABLISHED)
     * because the uncertainty means we cannot be sure.
     */
    public StrengthBand projectBand() {
        double e = expectation();
        if (e >= 0.85 && uncertainty < 0.15) return StrengthBand.ESTABLISHED;   // DEFINITIVE maps here
        if (e >= 0.70 && uncertainty < 0.30) return StrengthBand.HIGH;           // HIGH maps here
        if (e >= 0.40 && uncertainty < 0.60) return StrengthBand.PROBABLE;       // MODERATE maps here
        if (e >= 0.10) return StrengthBand.SPECULATIVE;                           // LOW maps here
        return StrengthBand.SUPPRESSED;                                           // INSUFFICIENT maps here
    }

    // ─── Hand-rolled JSON serialization ─────────────────────────────────────

    public String toJson() {
        return String.format(
            "{\"belief\":%.6f,\"disbelief\":%.6f,\"uncertainty\":%.6f,\"baseRate\":%.6f}",
            belief, disbelief, uncertainty, baseRate
        );
    }

    public static Opinion fromJson(String json) {
        // Minimal parser matching InferredFact.fromJson() style
        double b = parseField(json, "belief");
        double d = parseField(json, "disbelief");
        double u = parseField(json, "uncertainty");
        double a = parseField(json, "baseRate");
        return new Opinion(b, d, u, a);
    }

    private static double parseField(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) throw new IllegalArgumentException("Missing field: " + field);
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return Double.parseDouble(json.substring(start, end).trim());
    }
}
```

---

## 2. Unified Band Primitive — Replacing Two Competing Schemes

`AttributionConfidence.java:16` (5 bands) and the audit design's `StrengthLayer` (4 bands) represent
the same semantic concept with different cardinalities. They must be unified into one enum.

**Decision: 5-band `StrengthBand` that subsumes both, derived from `Opinion.projectBand()`.**
The 4-band scheme collapses DEFINITIVE+HIGH into ESTABLISHED and MODERATE into PROBABLE. The 5-band
scheme preserves the DEFINITIVE distinction, which is meaningful for barcode/hard-observed atoms.

Location: `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/StrengthBand.java`

```java
// kompile-graph-reasoning/.../confidence/StrengthBand.java  (new)
/**
 * Unified epistemic strength band. Derived exclusively from Opinion.projectBand();
 * replaces both AttributionConfidence (5-band, domain/AttributionConfidence.java:16)
 * and the audit design's StrengthLayer (4-band, ESTABLISHED/PROBABLE/SPECULATIVE/SUPPRESSED).
 *
 * Migration:
 *   AttributionConfidence.DEFINITIVE  → StrengthBand.ESTABLISHED (e≥0.85, u<0.15)
 *   AttributionConfidence.HIGH        → StrengthBand.HIGH         (e≥0.70, u<0.30)
 *   AttributionConfidence.MODERATE    → StrengthBand.PROBABLE     (e≥0.40, u<0.60)
 *   AttributionConfidence.LOW         → StrengthBand.SPECULATIVE  (e≥0.10)
 *   AttributionConfidence.INSUFFICIENT → StrengthBand.SUPPRESSED  (e<0.10)
 *   StrengthLayer.ESTABLISHED        → StrengthBand.ESTABLISHED
 *   StrengthLayer.PROBABLE           → StrengthBand.PROBABLE
 *   StrengthLayer.SPECULATIVE        → StrengthBand.SPECULATIVE
 *   StrengthLayer.SUPPRESSED         → StrengthBand.SUPPRESSED
 */
public enum StrengthBand {
    /** e≥0.85 and u<0.15: high-confidence, near-certain positive belief. */
    ESTABLISHED,
    /** e≥0.70 and u<0.30: convergent evidence, moderate uncertainty. */
    HIGH,
    /** e≥0.40 and u<0.60: some evidence but uncertainty dominates or evidence is mixed. */
    PROBABLE,
    /** e≥0.10: speculative; weak evidence or high ignorance. Hidden from default views. */
    SPECULATIVE,
    /** e<0.10: effectively inactive; tombstone range. Not used in grounding or derivation. */
    SUPPRESSED;

    /**
     * Project directly from Opinion (the canonical source).
     */
    public static StrengthBand from(Opinion opinion) {
        return opinion.projectBand();
    }

    /**
     * Legacy bridge: project from a scalar confidence (for callers that do not yet hold an Opinion).
     * Uncertainty is assumed to be 0 (flat prior); this matches the current behavior of
     * StrengthLayerResolver and AttributionConfidence.fromScore() exactly.
     */
    public static StrengthBand fromScalar(double confidence) {
        return Opinion.fromObservedFact(confidence).projectBand();
    }

    /**
     * Bridge back to AttributionConfidence for callers not yet migrated (Phase 2 cleanup).
     */
    public AttributionConfidence toAttributionConfidence() {
        return switch (this) {
            case ESTABLISHED -> AttributionConfidence.DEFINITIVE;
            case HIGH        -> AttributionConfidence.HIGH;
            case PROBABLE    -> AttributionConfidence.MODERATE;
            case SPECULATIVE -> AttributionConfidence.LOW;
            case SUPPRESSED  -> AttributionConfidence.INSUFFICIENT;
        };
    }
}
```

**`AttributionConfidence.java` is NOT deleted in Phase 1.** It gains one deprecation note and one
bridge method `static AttributionConfidence from(StrengthBand)` so the many existing callers can
migrate incrementally. The authoritative source becomes `StrengthBand` in Phase 2.

---

## 3. Projection into Existing APIs — Non-Breaking Strategy

### 3.1 `InferredFact` — no record change in Phase 1

`InferredFact.java:47–56` is a record with hand-rolled JSON and 473 downstream tests
(252+214+7 per MEMORY.md). The record contract must not change.

**Strategy: `Opinion` lives alongside `InferredFact`, not inside it, for Phase 1.**

An `OpinionStore` (SPI, lib-level) maps `atomKey → Opinion`. It is populated by inference engines
before they emit `InferredFact`s. Downstream callers who want the full epistemic detail call
`OpinionStore.get(atomKey)`. Callers that only need scalars continue to read `InferredFact.value()`
and `InferredFact.confidence()`.

The scalars remain authoritative for serialization and existing tests. The `Opinion` is the richer
companion that is computed alongside and stored separately.

For Phase 2, a **`withOpinion(Opinion o)`** method is added to `InferredFact` (not to the record
fields — to a builder companion `InferredFactWithOpinion` that wraps the record). The hand-rolled
JSON gains an optional `"opinion"` key (deserialized if present, ignored if absent by the existing
`fromJson` which defaults missing `confidence` to `value` at line 197). This is fully backward-
compatible.

### 3.2 `VerifyResult` — projection, not replacement

`VerifyResult.java:43–57` keeps its existing fields. `KbVerifier` implementations learn to compute
an `Opinion` and project it to `Status` + `confidence` using `Opinion.projectStatus()` (§1.3 above).
The trichotomy `Status.SUPPORTED/REFUTED/UNKNOWN` maps onto `Opinion.VerifyStatusProjection` 1:1:

| `Opinion` state | `VerifyResult.Status` |
|---|---|
| `uncertainty ≥ 0.6` (ignorance-dominated) | `UNKNOWN` |
| `expectation ≥ 0.5` (belief-dominated) | `SUPPORTED` |
| `expectation < 0.3` (disbelief-dominated) | `REFUTED` |
| otherwise (conflicted or intermediate) | `UNKNOWN` |

`VerifyResult` gains an optional `Opinion opinion()` accessor via a record companion in Phase 2.
For Phase 1, `KbVerifier.verify()` returns the existing record unchanged; an overload
`verifyWithOpinion(atomKey) → VerifyResultWithOpinion` is added without breaking the old signature.

### 3.3 Scalars as derived quantities

After Phase 2 wiring is complete:
- `InferredFact.value()` = `opinion.expectation()`
- `InferredFact.confidence()` = `1.0 − opinion.uncertainty()` (one natural scalar projection;
  a second projection is `opinion.belief()` for cases where the caller wants the pure positive mass)

This ensures `value` and `confidence` are no longer always equal (the bug at lines 87 and 112 of
`InferredFact.java`) — `confidence` now carries real information (the complement of ignorance)
while `value` carries the projected truth value (belief + baseRate × uncertainty).

---

## 4. Source Adapters — Threading Opinion Through the Engine Chain

Each inference source maps to a specific `Opinion` factory. This is the threading chain:

### 4.1 PSL soft-truth → Opinion (`fromPslSoftTruth`)

**Source**: `HlMrfMapInference.Result` → `EntailmentRecord.posterior()`
**Adapter**: `Opinion.fromPslSoftTruth(posterior, activatedRuleCount, baseRate=0.5)`
- `activatedRuleCount` = `EntailmentRecord.activatedRules().size()`; more rules = lower uncertainty
- This is the fix for the PSL-is-not-a-probability warning in `graph-hydration-inference-chain-design.md:402–413`:
  the Opinion's `expectation()` is still the soft-truth for existing scalar consumers, but the
  uncertainty component captures the rule-support thinness that makes PSL values unreliable at low
  support counts.

**Where to insert**: `EntailmentEngine.java:55` area, after the MAP solve, before
`InferredFact.fromEntailment()` is called at `InferredFact.java:82`. The `Opinion` is written to
`OpinionStore.put(atomKey, opinion)` before the `InferredFact` is stored.

### 4.2 MEBN posterior → Opinion (`fromBayesianPosterior`)

**Source**: `MebnInferenceService.infer() → Map<String, Double>`
**Adapter**: `Opinion.fromBayesianPosterior(posterior, baseRate=0.5)`
- Information gain `|posterior − 0.5|` drives down uncertainty. When SSBN has sparse evidence,
  posteriors cluster near the prior → high uncertainty Opinion, correctly signaling low confidence.
- **Where to insert**: `EntailmentEngine.entailFromMebn()` (line area near MEBN call in
  `EntailmentEngine.java:55`), wrapping the `infer()` output before creating `EntailmentRecord`s.

### 4.3 Embedding score → Opinion (`fromEmbeddingScore`)

**Source**: `LinkPredictor.predictTails()` → `ScoredPrediction.distance()` → Platt-calibrated score
**Adapter**: `Opinion.fromEmbeddingScore(calibratedScore, embeddingUncertainty=0.30)`
- Default `embeddingUncertainty=0.30` reflects the inherent model uncertainty of KGE predictions
  (per `graph-hydration-inference-chain-design.md:390`, even a well-calibrated RotatE is uncertain)
- `calibratedScore` is the Platt-scaled score from `StrengthCalibrator` in
  `domain-object-grounding-design.md §3.2`; not the raw distance.
- **Where to insert**: `EmbeddingPslEvidence.java:56` area, after Platt scaling, before injecting
  `similar(a,b)` atoms into the `PslProgram`.

### 4.4 Observed hard Fact → Opinion (`fromObservedFact`)

**Source**: `Fact.java:52` `observed()` factory (value=1.0, hard=true)
**Adapter**: `Opinion.fromObservedFact(1.0)` → `(b=1.0, d=0.0, u=0.0, a=1.0)`
- Hard observations have zero uncertainty. Soft facts (`Fact.soft()` line 64) get
  `Opinion.fromEvidence(value * k, (1-value) * k)` where k is the source confidence prior.
- **Where to insert**: `GraphToFactStoreProjector.java:60` (projects graph nodes/edges to
  `FactStore` atoms), adding an `OpinionStore.put()` call alongside each `FactStore.put()`.

### 4.5 Multi-extractor fusion — replacing noisy-OR scalars

The hydration chain's fusion formula at `graph-hydration-inference-chain-design.md:418–427`
(`P_fused = 1 − Π(1−P_i)` for independent signals) is now expressed as:

```java
// Independent sources (PSL rule + RotatE): cumulative fusion
Opinion pslOpinion  = opinionStore.get(atomKey + ":psl");
Opinion embedOpinion = opinionStore.get(atomKey + ":embed");
Opinion fused = pslOpinion.cumulativeFuse(embedOpinion);  // §1.3 above

// Dependent sources (two PSL rules sharing an antecedent): averaging
Opinion ruleA = opinionStore.get(atomKey + ":rule_A");
Opinion ruleB = opinionStore.get(atomKey + ":rule_B");
Opinion merged = ruleA.averageFuse(ruleB);  // avoids double-counting

// Many sources (N extractors): consensus
Opinion result = Opinion.consensus(List.of(opinions...));
```

**Why this is better than noisy-OR scalars**: noisy-OR `1 − Π(1−P_i)` has no way to represent
"both sources partially refute this" — the minimum product always drives the fused score upward.
`cumulativeFuse` correctly produces a conflicted opinion (high b AND high d) when one source
believes strongly and another disbelieves strongly, capturing the epistemic reality that evidence
is divided. The scalar projection `expectation()` of a conflicted opinion falls near 0.5, but the
full `Opinion` exposes the conflict to callers who need it (e.g., for `ContradictionDetector`
integration).

---

## 5. `OpinionStore` SPI

Location: `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/OpinionStore.java`

```java
// kompile-graph-reasoning/.../confidence/OpinionStore.java  (new, lib-level SPI)
/**
 * Companion store for Opinion objects, keyed by the same atom keys as InferredFactStore.
 * Lifecycle: parallel to InferredFactStore — one OpinionStore per factSheet.
 * NOT persisted to disk in Phase 1 (in-memory only; recreated on cascade re-run).
 * Phase 2: persisted as factsheet-<id>-opinions.jsonl alongside history.jsonl.
 */
public interface OpinionStore {
    void put(String atomKey, Opinion opinion);
    Opinion get(String atomKey);                // returns vacuous() if absent
    boolean has(String atomKey);
    void clear();
    Collection<Map.Entry<String, Opinion>> entries();
}
```

Default implementation: `InMemoryOpinionStore` (thread-safe `ConcurrentHashMap`, lib-level, no Spring).
Spring-wired implementation: `ConcurrentOpinionStore` in `kompile-knowledge-graph` (matches the
pattern of `ConcurrentFactStore` at `kompile-graph-reasoning/.../fol/grounding/ConcurrentFactStore.java:55`).

---

## 6. Unifying VerifyResult Trichotomy and Band Schemes

### 6.1 Trichotomy as Opinion projection

`VerifyResult.Status` (SUPPORTED/REFUTED/UNKNOWN at `VerifyResult.java:50–57`) is now a **derived
projection** of `Opinion.projectStatus()` rather than an independent classification:

| `Opinion` shape | Projected `Status` | Rationale |
|---|---|---|
| `u ≥ 0.6` | `UNKNOWN` | Ignorance dominates; not enough evidence to classify |
| `E ≥ 0.5`, `b > d` | `SUPPORTED` | Consistent with `KbVerifier.DEFAULT_THRESHOLD = 0.5` at `KbVerifier.java:43` |
| `E < 0.3`, `d > b` | `REFUTED` | Disbelief dominates; consistent with negated-atom inference in `DefaultKbVerifier.java` |
| `0.3 ≤ E < 0.5` or `b ≈ d` | `UNKNOWN` (conflicted sub-case) | Ambiguous evidence; safer to signal UNKNOWN than to guess |

This resolves the UNKNOWN `confidence=0.0` ambiguity (VerifyResult line 79): the vacuous Opinion
`(b=0, d=0, u=1)` projects to `UNKNOWN` with `u=1.0`, while the refuted Opinion
`(b=0, d=1, u=0)` projects to `REFUTED` with `confidence=0.0` — same scalar but different epistemic
meaning, now distinguishable.

### 6.2 Single band vocabulary — `StrengthBand` is canonical

The two existing schemes (§0) are replaced:

| Old enum | Status | Replacement |
|---|---|---|
| `AttributionConfidence` (5-band) | **Deprecated in Phase 1; bridge method added** | `StrengthBand.from(opinion)` |
| Audit design's `StrengthLayer` (4-band, not yet implemented) | **Superseded before implementation** | `StrengthBand.from(opinion)` |

`StrengthBand.fromScalar(double)` provides backward-compat for the `StrengthLayerResolver` design
(`fact-store-audit-tuning-correction-design.md §3.2`) — it maps the 4 audit bands to 4 of the 5
`StrengthBand` values. The audit design's proposed `StrengthLayerResolver` class should now be
`StrengthBandResolver` that calls `Opinion.projectBand()` rather than doing scalar comparison.

The `StrengthCalibrator` in `domain-object-grounding-design.md §3.2` emits a calibrated scalar;
that scalar is wrapped in an `Opinion.fromObservedFact(calibrated)` (zero uncertainty — calibration
has already absorbed the epistemic uncertainty) before calling `StrengthBand.from(opinion)`.

---

## 7. Phased Implementation Plan

### Phase 1 — `Opinion` record + `StrengthBand` + `OpinionStore` SPI + source adapters (non-breaking)

**Scope**: infra-free lib only; no Spring; no record changes to `InferredFact` or `VerifyResult`.

**What ships:**
1. `kompile-graph-reasoning/.../confidence/Opinion.java` — record, factories, fusion operators,
   JSON serialization, trichotomy and band projections
2. `kompile-graph-reasoning/.../confidence/StrengthBand.java` — unified band enum with
   `toAttributionConfidence()` bridge; `StrengthBand.fromScalar()` covers existing `StrengthLayerResolver`
   callers before they are migrated
3. `kompile-graph-reasoning/.../confidence/OpinionStore.java` — SPI
4. `kompile-graph-reasoning/.../confidence/InMemoryOpinionStore.java` — default implementation
5. Source adapters: static factory methods on `Opinion` for PSL, MEBN, embedding, observed Fact
   (all in `Opinion.java` itself — factories, not separate adapter classes, to keep the lib lean)
6. Deprecation note on `AttributionConfidence.java:16` + `static AttributionConfidence from(StrengthBand)` bridge
7. **No changes to `InferredFact`, `VerifyResult`, `Fact`** — all existing tests pass unchanged

**Tests (lib-only, no Spring):**
- `OpinionTest`: simplex constraint enforcement, factory round-trips, `isVacuous()`, `isConflicted()`,
  `expectation()` for vacuous / certain / conflicted opinions
- `OpinionFusionTest`: `cumulativeFuse` agreement (lower u) and disagreement (conflicted); `averageFuse`
  idempotent; `consensus` on N=0,1,3 opinions
- `StrengthBandTest`: all 5 bands project correctly from canonical Opinion examples; `fromScalar`
  matches `AttributionConfidence.fromScore()` for the 5 threshold boundaries
- `SourceAdapterTest`: each factory (`fromPslSoftTruth`, `fromBayesianPosterior`, `fromEmbeddingScore`,
  `fromObservedFact`) round-trips through `expectation()` back to a sensible scalar; vacuous input
  produces vacuous output; certainty inputs produce near-zero uncertainty

**Does not touch:** `InferredFact`, `VerifyResult`, any Spring bean, `kompile-knowledge-graph`, UI.

### Phase 2 — Wire `Opinion` into inference engines; `InferredFact` gets optional Opinion companion

**What ships:**
1. `OpinionStore` populated by `EntailmentEngine` (PSL + MEBN paths) and `GraphToFactStoreProjector`
2. `KbVerifier` overload `verifyWithOpinion(atomKey) → VerifyResultWithOpinion` (additive, does not
   change existing `verify()` signature)
3. `InferredFactWithOpinion` wrapper (not a record change — a companion class): `of(InferredFact, Opinion)`
4. `InferredFact.fromJson` gains optional `"opinion"` field: if present, validates it; if absent,
   defaults to `Opinion.fromObservedFact(value)` (backward-compat)
5. `InferredFact.fromEntailment()` (line 82) now sets `confidence = 1.0 - opinion.uncertainty()`
   instead of `confidence = record.posterior()` — this is the first real population of the second-order
   `confidence` field after years of it being a copy of `value`
6. `InferredFact.of()` (line 108) similarly sets `confidence = 1.0 - Opinion.fromEvidence(value * 2, (1-value) * 2).uncertainty()`
7. Replace `StrengthLayerResolver` design with `StrengthBandResolver` calling `Opinion.projectBand()`

**Test delta:** `InferredFactTest` gains a round-trip test that verifies `confidence ≠ value` after
Phase 2 wiring for a PSL fact with 1 supporting rule (low support → high uncertainty → confidence < value).

### Phase 3 — Fusion refactor; OpinionStore persistence; multi-band filtering

**What ships:**
1. Hydration chain Stage 6 fusion (`graph-hydration-inference-chain-design.md §4.5`) rewritten as
   `Opinion.cumulativeFuse` / `Opinion.averageFuse` / `Opinion.consensus` calls
2. `OpinionStore` persistence: `factsheet-<id>-opinions.jsonl` alongside `history.jsonl`
   (hand-rolled JSON, matching `InferredFact.toJson()` style)
3. `InferredFactStore.byLayer(StrengthBand)` filter returns `InferredFact`s whose Opinion projects to
   that band (reads from `OpinionStore` keyed to the same atomKey)
4. `StrengthCalibrator.calibrate()` (`domain-object-grounding-design.md §3.2`) output wrapped as
   `Opinion.fromObservedFact(calibrated)` before `StrengthBand.from()` is called in `GroundedElement`

### Phase 4 — `AttributionConfidence` removal; full trichotomy alignment; ContradictionDetector integration

**What ships:**
1. Delete `AttributionConfidence.java` (or move to a `@Deprecated` shim); all callers migrated to `StrengthBand`
2. `ContradictionDetector` (`tms/ContradictionDetector.java:36`) gains conflict detection via
   `Opinion.isConflicted()`: when the fused Opinion for an atom has both `b > 0.3` and `d > 0.3`,
   it is flagged as an epistemic conflict even before the PSL hard constraint violation threshold is reached
3. `BeliefReviser.retract()` uses the Opinion's `belief` and `disbelief` (not just the scalar value)
   to choose which fact to retract in a conflict: retract the one with lower `belief` (not lower `value`)
4. Angular: strength badge (`domain-object-grounding-design.md §5.2`) gains uncertainty indicator
   (e.g., a grey blur ring around the dot cluster when `u > 0.4`)

---

## 8. Forks — Decisions and Recommendations

### Fork (i): Opinion field on `InferredFact` vs. computed alongside (CRITICAL)

**Option A — Opinion field on `InferredFact` record.**
- Add `Optional<String> opinionJson` to the record; serialize in `toJson()`; deserialize in `fromJson()` (optional; absent → no opinion)
- Pros: single object carries everything; portability automatic; no separate store
- Cons: record signature change breaks all existing tests that call the 8-arg constructor directly
  (the 252+214+7 test count); hand-rolled JSON `fromJson` must be updated

**Option B — Computed alongside (recommended for Phase 1, then migrate).**
- `InferredFact` record unchanged. `OpinionStore` is a parallel in-memory store keyed by `(factSheetId, atomKey)`.
- Pros: zero test disruption in Phase 1; non-breaking; can validate the Opinion model against real data
  before committing to the record contract
- Cons: two things to pass around; portability requires explicit serialization of `OpinionStore` contents
  (addressed in Phase 3 with `factsheet-<id>-opinions.jsonl`)

**RECOMMENDATION: Option B for Phase 1–2; migrate to Option A in Phase 4 after the Opinion model
is validated on real inference runs.** The migration path is: add an optional `"opinion"` JSON
field that `fromJson` reads if present; existing serialized files without the field work unchanged.
The 8-arg constructor can be deprecated in favor of a builder in Phase 4 to absorb the new field
without touching every call site.

### Fork (ii): Binomial Opinion vs. multinomial Dirichlet for multi-valued atoms

**Option A — Binomial Opinion** (this design): `{belief, disbelief, uncertainty}` for binary predicates
(`isActive(alice)`, `related(a,b)`). Covers 95%+ of the lib's current use cases (PSL/MEBN predicates
are binary).

**Option B — Dirichlet Opinion** (`α₁, α₂, ..., αₖ` for k-valued atoms): required for typed attributes
(`department(alice) ∈ {Finance, Engineering, HR}`) and multi-class type predicates
(`entityType(node) ∈ TypeHierarchy.leaves()`).

**RECOMMENDATION: Option A for the infra-free lib in Phase 1–3. Add `DirichletOpinion` as a
companion record in Phase 4 for multi-class use cases.** The `TypeHierarchy` (`mebn/type/TypeHierarchy.java:34`)
is the primary driver for Dirichlet; it is a reasonable Phase 4 scope. For now, multi-class type
inferences are handled as independent binary opinions per class.

### Fork (iii): Evidence-count provenance — how much to persist

**Option A — Full evidence counts `(r, s, k)` persisted.**
The `Opinion.fromEvidence(r, s, baseRate, k)` factory stores `r`, `s`, `k` explicitly. These can
be used to update the opinion incrementally when new evidence arrives: `r' = r + Δr`, `s' = s + Δs`.
Pro: true Bayesian update; corrections accumulate correctly. Con: per-atom provenance overhead; the
existing `InferredFact.supportingFactKeys` already carries provenance implicitly.

**Option B — Opinion tuple only persisted; `(r, s, k)` recomputed on demand.**
Invert the factory: given `(b, d, u, a)`, `r = b/u * k`, `s = d/u * k`. This is only an approximation
(k is ambiguous) but is good enough for the aggregation use cases.

**RECOMMENDATION: Option B for Phase 1–3.** The `supportingFactKeys` list (`InferredFact.java:51`)
already captures the evidence identity; the `Opinion` captures the aggregated epistemic state. True
incremental Bayesian update (Option A) is a Phase 4 concern when the correction-loop
(`fact-store-audit-tuning-correction-design.md §4.3`) wants to accumulate human corrections as
evidence increments to the Opinion rather than as weight-learning signals.

---

## 9. Open Questions

1. **Base rate source**: `Opinion` requires a `baseRate` per atom. Currently defaulted to 0.5 (maximum
   entropy prior). For typed predicates, the base rate should be the empirical frequency in the KB
   (e.g., if 30% of entities are of type PERSON, `type(x, PERSON)` has baseRate=0.3). An
   `AtomBaseRateEstimator` that reads `InferredFactStore.allLatest()` counts would provide this.
   Not designed yet; 0.5 default is a safe placeholder.

2. **Conflict detection threshold**: `Opinion.isConflicted(threshold)` defaults threshold to some
   value. What value? If threshold=0.3, a fact with `(b=0.35, d=0.35, u=0.30)` is conflicted.
   The right value depends on the KB domain (sparse KBs have more conflicts by construction).
   Recommend making this configurable via `HydrationConfig` in Phase 3.

3. **Subjective Logic vs. Dempster-Shafer**: Jøsang's cumulative fusion is equivalent to
   Dempster-Shafer combination for binary frame of discernment. For N > 2 hypotheses (multi-valued
   atoms, Phase 4), the DS orthogonal sum has well-known problems with high-conflict evidence
   (Zadeh's paradox). A Yager or PCR5 combination rule may be preferable. Deferred to Phase 4
   Dirichlet extension.

4. **Performance**: `Opinion.cumulativeFuse()` on N opinions is O(N) chained calls. For a highly-
   connected atom in a dense KB (hundreds of rules firing), this is fine. For the embedding
   `EmbeddingPslEvidence` which may inject thousands of `similar(a,b)` atoms, batch fusion via
   `Opinion.consensus()` (O(N) single pass) is more appropriate. The hydration chain design should
   specify which fusion variant each stage uses.

5. **Negative atoms and disbelief**: PSL represents `~atom` as a negated atom key (e.g.,
   `"~State(alice)"`). Currently `DefaultKbVerifier.java` checks for negated keys explicitly.
   With Opinion, a verified `~State(alice)` with high belief becomes the same information as
   `State(alice)` with high disbelief. The `fromPslSoftTruth` adapter for negated atoms should
   invert `belief` and `disbelief` before storing. This unification is clean but needs explicit
   handling in the adapter to avoid double-registering the same atom under two keys.

6. **`StrengthBand.HIGH` vs existing tier names**: the `graph-hydration-inference-chain-design.md §4.6`
   uses `HIGH_CONFIDENCE` (≥0.7) and `CANDIDATE` (≥0.5) tier names that differ from
   `StrengthBand.HIGH` (≥0.70, u<0.30) and `StrengthBand.PROBABLE` (≥0.40). The `HydrationConfig`
   thresholds and the `StrengthBand` boundaries must be reconciled in Phase 3 when the hydration
   chain is refactored to use `Opinion` fusion. Until then, the hydration chain's tier names are
   independent; `StrengthBand.from()` is the canonical lookup for external callers.

---

## 10. File Paths — Authoritative Index

| New artifact | Location |
|---|---|
| `Opinion` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/Opinion.java` |
| `StrengthBand` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/StrengthBand.java` |
| `OpinionStore` (SPI) | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/OpinionStore.java` |
| `InMemoryOpinionStore` | `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/InMemoryOpinionStore.java` |
| Calibration parameters | `<dataDir>/data/graph/calibration/<SignalType>.json` (existing per domain-object-grounding-design.md §3.4) |
| `factsheet-<id>-opinions.jsonl` | `<dataDir>/data/graph/inferred/factsheet-<id>-opinions.jsonl` (Phase 3) |

| Existing file | Change |
|---|---|
| `fol/InferredFact.java:82,112` | Phase 2: `confidence` ← `1.0 − opinion.uncertainty()` instead of copy of `value` |
| `fol/grounding/VerifyResult.java` | Phase 2: additive overload `verifyWithOpinion()` on `KbVerifier`; no record change |
| `domain/AttributionConfidence.java` | Phase 1: deprecation note + `from(StrengthBand)` bridge; Phase 4: delete |
| `fol/EntailmentEngine.java` | Phase 2: populate `OpinionStore` after PSL/MEBN solve |
| `fol/grounding/DefaultKbVerifier.java` | Phase 2: use `Opinion.projectStatus()` for `Status` classification |
| `tms/ContradictionDetector.java` | Phase 4: `isConflicted()` check alongside hard constraint violations |
