/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.argument;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.Step;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adjudicates whether a claim is SUPPORTED, REFUTED, or UNKNOWN by building a
 * {@link Qbaf} from a list of {@link EvidenceItem}s, evaluating it under
 * {@link DfQuadSemantics} (DF-QuAD), and applying verdict thresholds.
 *
 * <h3>Default thresholds</h3>
 * <ul>
 *   <li>strength ≥ {@value #DEFAULT_SUPPORTED_AT} → {@link AdjudicatedVerdict.Status#SUPPORTED}</li>
 *   <li>strength ≤ {@value #DEFAULT_REFUTED_AT}   → {@link AdjudicatedVerdict.Status#REFUTED}</li>
 *   <li>otherwise → {@link AdjudicatedVerdict.Status#UNKNOWN}</li>
 * </ul>
 *
 * <h3>QBAF construction</h3>
 * <p>The CLAIM node has id {@code "claim"} and {@code baseScore = prior} (default 0.5).
 * Each {@link EvidenceItem} becomes one argument node:
 * <ul>
 *   <li>PRO items → {@link ArgKind#PRO} with a SUPPORT edge to the claim.</li>
 *   <li>CON items → {@link ArgKind#CON} with an ATTACK edge to the claim.</li>
 * </ul>
 * The API is open for extended graphs (see {@link #adjudicate(String, double, List, List)} and
 * the {@link Qbaf.Builder} it exposes via construction helpers) — callers may build arbitrary
 * QBAFs and pass them directly to {@link DfQuadSemantics#evaluate(Qbaf)}.</p>
 *
 * <h3>Trace construction</h3>
 * <p>The returned {@link ReasoningTrace} has:
 * <ul>
 *   <li>Root: {@link StepKind#INFERENCE} step for the claim, carrying the final strength
 *       as confidence, and meta {@code {semantics=df-quad, supportedAt=…, refutedAt=…}}.</li>
 *   <li>Premises: one step per {@link EvidenceItem} —
 *       PRO items use their natural step kind
 *       ({@code direct-fact} → {@link StepKind#FACT}; others → {@link StepKind#INFERENCE});
 *       CON items use {@link StepKind#REBUTTAL}.</li>
 *   <li>Each step carries meta {@code {kind=…, baseScore=…, finalStrength=…}} and
 *       provenance as source.</li>
 * </ul>
 */
public final class ClaimAdjudicator {

    /** Default threshold above which the claim is considered SUPPORTED. */
    public static final double DEFAULT_SUPPORTED_AT = 0.65;

    /** Default threshold below which the claim is considered REFUTED. */
    public static final double DEFAULT_REFUTED_AT = 0.35;

    private final double supportedAt;
    private final double refutedAt;

    /** Create an adjudicator with default thresholds. */
    public ClaimAdjudicator() {
        this(DEFAULT_SUPPORTED_AT, DEFAULT_REFUTED_AT);
    }

    /**
     * Create an adjudicator with custom thresholds.
     *
     * @param supportedAt strength at or above which the claim is SUPPORTED; must be in (0.5, 1]
     * @param refutedAt   strength at or below which the claim is REFUTED; must be in [0, 0.5)
     */
    public ClaimAdjudicator(double supportedAt, double refutedAt) {
        if (supportedAt <= 0.5 || supportedAt > 1.0) {
            throw new IllegalArgumentException("supportedAt must be in (0.5,1], got " + supportedAt);
        }
        if (refutedAt < 0.0 || refutedAt >= 0.5) {
            throw new IllegalArgumentException("refutedAt must be in [0,0.5), got " + refutedAt);
        }
        this.supportedAt = supportedAt;
        this.refutedAt = refutedAt;
    }

    // ── Core adjudication ────────────────────────────────────────────────────────

    /**
     * Adjudicate the claim with the given label and prior strength.
     *
     * @param claimLabel human-readable label for the claim
     * @param prior      prior strength of the claim in [0, 1] (base score before evidence)
     * @param items      flat list of evidence items (both PRO and CON)
     * @return the adjudicated verdict
     */
    public AdjudicatedVerdict adjudicate(String claimLabel, double prior, List<EvidenceItem> items) {
        return adjudicate(claimLabel, prior, items, List.of());
    }

    /**
     * Adjudicate the claim. The {@code extraEdges} list allows callers to supply additional
     * QBAF edges (e.g. items attacking other items — meta-argumentation) in addition to the
     * flat evidence list. Each extra edge is a {@code String[3]} of
     * {@code [sourceId, targetId, "SUPPORT"|"ATTACK"]}, where {@code sourceId/targetId} are
     * the ids assigned by the adjudicator (evidence item ids are {@code "e0"}, {@code "e1"}, …).
     *
     * @param claimLabel  human-readable claim label
     * @param prior       prior strength in [0, 1]
     * @param items       evidence items (in order; ids assigned as e0, e1, …)
     * @param extraEdges  additional QBAF edges as {@code [sourceId, targetId, type]} triples;
     *                    may be empty
     * @return the adjudicated verdict
     */
    public AdjudicatedVerdict adjudicate(String claimLabel, double prior,
                                          List<EvidenceItem> items,
                                          List<String[]> extraEdges) {
        Objects.requireNonNull(claimLabel, "claimLabel");
        Objects.requireNonNull(items, "items");

        Qbaf.Builder builder = Qbaf.builder();
        builder.argument(Argument.claim("claim", claimLabel, prior));

        List<String> itemIds = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            EvidenceItem item = items.get(i);
            String id = "e" + i;
            itemIds.add(id);
            ArgKind kind = item.pro() ? ArgKind.PRO : ArgKind.CON;
            builder.argument(new Argument(id, item.label(), item.confidence(), kind));
            if (item.pro()) {
                builder.support(id, "claim");
            } else {
                builder.attack(id, "claim");
            }
        }

        // Extra inter-argument edges (meta-argumentation)
        for (String[] triple : extraEdges) {
            if (triple.length < 3) continue;
            Qbaf.EdgeType type = "SUPPORT".equalsIgnoreCase(triple[2])
                    ? Qbaf.EdgeType.SUPPORT : Qbaf.EdgeType.ATTACK;
            builder.edge(triple[0], triple[1], type);
        }

        Qbaf qbaf = builder.build();
        DfQuadSemantics.Result eval = DfQuadSemantics.evaluate(qbaf);
        Map<String, Double> strengths = eval.strengths();

        double claimStrength = strengths.getOrDefault("claim", prior);
        AdjudicatedVerdict.Status status = toStatus(claimStrength);

        // Build trace
        ReasoningTrace trace = buildTrace(claimLabel, claimStrength, prior, items, itemIds, strengths);

        return new AdjudicatedVerdict(status, claimStrength,
                new LinkedHashMap<>(strengths), trace);
    }

    // ── Convenience bridge ────────────────────────────────────────────────────────

    /**
     * Build an adjudication from a {@link VerifyResult}.
     *
     * <p>PRO items come from {@link VerifyResult#evidence()} with confidence =
     * {@link VerifyResult#confidence()}.  CON items come from
     * {@link VerifyResult#counterEvidence()} using the same confidence; if
     * {@link VerifyResult#refutationBasis()} is non-null its value is used as the kind.</p>
     *
     * <p>The claim prior defaults to 0.5.</p>
     *
     * @param claimAtom the atom key being verified (used as claim label)
     * @param vr        the verify result (uses accessors status/confidence/evidence/
     *                  counterEvidence/refutationBasis — all stable)
     * @return the adjudicated verdict
     */
    public AdjudicatedVerdict fromVerify(String claimAtom, VerifyResult vr) {
        Objects.requireNonNull(claimAtom, "claimAtom");
        Objects.requireNonNull(vr, "vr");

        List<EvidenceItem> items = new ArrayList<>();

        // PRO items from evidence()
        for (String ev : vr.evidence()) {
            items.add(new EvidenceItem(ev, vr.confidence(), true, "direct-fact", List.of(ev)));
        }

        // CON items from counterEvidence()
        String conKind = (vr.refutationBasis() != null && !vr.refutationBasis().isBlank())
                ? vr.refutationBasis() : "contradiction";
        for (String ce : vr.counterEvidence()) {
            items.add(new EvidenceItem(ce, vr.confidence(), false, conKind, List.of(ce)));
        }

        return adjudicate(claimAtom, 0.5, items);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    private AdjudicatedVerdict.Status toStatus(double strength) {
        if (strength >= supportedAt) return AdjudicatedVerdict.Status.SUPPORTED;
        if (strength <= refutedAt)   return AdjudicatedVerdict.Status.REFUTED;
        return AdjudicatedVerdict.Status.UNKNOWN;
    }

    private ReasoningTrace buildTrace(String claimLabel, double claimStrength, double prior,
                                       List<EvidenceItem> items, List<String> itemIds,
                                       Map<String, Double> strengths) {
        List<Step> premises = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            EvidenceItem item = items.get(i);
            String id = itemIds.get(i);
            double finalStrength = strengths.getOrDefault(id, item.confidence());

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("kind", item.kind());
            meta.put("baseScore", String.valueOf(item.confidence()));
            meta.put("finalStrength", String.valueOf(finalStrength));

            String provenance = item.provenance().isEmpty() ? null
                    : String.join(", ", item.provenance());

            Step step;
            if (!item.pro()) {
                // CON items → REBUTTAL
                step = new Step(StepKind.REBUTTAL, item.label(), "attacks:" + claimLabel,
                        finalStrength, provenance, List.of(), null, meta);
            } else {
                // PRO items — natural kind
                StepKind kind = "direct-fact".equals(item.kind()) ? StepKind.FACT : StepKind.INFERENCE;
                step = new Step(kind, item.label(), item.kind(),
                        finalStrength, provenance, List.of(), null, meta);
            }
            premises.add(step);
        }

        Map<String, String> rootMeta = new LinkedHashMap<>();
        rootMeta.put("semantics", "df-quad");
        rootMeta.put("prior", String.valueOf(prior));
        rootMeta.put("supportedAt", String.valueOf(supportedAt));
        rootMeta.put("refutedAt", String.valueOf(refutedAt));

        Step root = new Step(StepKind.INFERENCE, claimLabel, "df-quad-adjudication",
                claimStrength, null, premises, null, rootMeta);

        return ReasoningTrace.of(root);
    }
}
