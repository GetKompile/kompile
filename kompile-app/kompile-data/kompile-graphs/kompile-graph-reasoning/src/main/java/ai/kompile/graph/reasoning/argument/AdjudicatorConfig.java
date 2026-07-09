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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Additive configuration hook that runs {@link ClaimAdjudicator}-style adjudication with:
 * <ol>
 *   <li>A pluggable semantics strategy ({@link QbafWeightLearner.SemanticsStrategy}).</li>
 *   <li>An optional per-kind weight map that scales item confidences before building the QBAF.</li>
 * </ol>
 *
 * <p>The existing {@link ClaimAdjudicator} zero-arg path is completely unchanged.
 * This class is a thin, additive layer for callers that want QE semantics or
 * learned weights without touching the original adjudicator.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // DF-QuAD, no weight scaling (identical result to ClaimAdjudicator):
 * AdjudicatorConfig cfg = AdjudicatorConfig.dfQuad();
 *
 * // QE semantics, no weight scaling:
 * AdjudicatorConfig cfg = AdjudicatorConfig.qe();
 *
 * // QE semantics with learned weights:
 * AdjudicatorConfig cfg = AdjudicatorConfig.qeWithWeights(learner.learn(examples).effectiveWeights());
 *
 * AdjudicatedVerdict v = cfg.adjudicate("claim(X)", 0.5, items);
 * }</pre>
 */
public final class AdjudicatorConfig {

    private final QbafWeightLearner.SemanticsStrategy strategy;
    private final Map<String, Double>                 kindWeights;
    private final double                              supportedAt;
    private final double                              refutedAt;

    private AdjudicatorConfig(QbafWeightLearner.SemanticsStrategy strategy,
                               Map<String, Double> kindWeights,
                               double supportedAt,
                               double refutedAt) {
        this.strategy    = Objects.requireNonNull(strategy,    "strategy");
        this.kindWeights = Map.copyOf(kindWeights);
        this.supportedAt = supportedAt;
        this.refutedAt   = refutedAt;
    }

    // ── Factory methods ───────────────────────────────────────────────────────────

    /** DF-QuAD semantics, unit weights, default thresholds. */
    public static AdjudicatorConfig dfQuad() {
        return new AdjudicatorConfig(QbafWeightLearner.SemanticsStrategy.DF_QUAD,
                Map.of(), ClaimAdjudicator.DEFAULT_SUPPORTED_AT, ClaimAdjudicator.DEFAULT_REFUTED_AT);
    }

    /** QE semantics, unit weights, default thresholds. */
    public static AdjudicatorConfig qe() {
        return new AdjudicatorConfig(QbafWeightLearner.SemanticsStrategy.QE,
                Map.of(), ClaimAdjudicator.DEFAULT_SUPPORTED_AT, ClaimAdjudicator.DEFAULT_REFUTED_AT);
    }

    /**
     * QE semantics with a per-kind weight map (e.g. from {@link QbafWeightLearner.LearnResult#effectiveWeights()}).
     * Default thresholds.
     */
    public static AdjudicatorConfig qeWithWeights(Map<String, Double> kindWeights) {
        return new AdjudicatorConfig(QbafWeightLearner.SemanticsStrategy.QE,
                kindWeights, ClaimAdjudicator.DEFAULT_SUPPORTED_AT, ClaimAdjudicator.DEFAULT_REFUTED_AT);
    }

    /**
     * Fully explicit configuration.
     *
     * @param strategy    semantics strategy to use
     * @param kindWeights per-kind weight map; kinds absent from the map default to 1.0
     * @param supportedAt strength threshold for SUPPORTED; must be in (0.5, 1]
     * @param refutedAt   strength threshold for REFUTED; must be in [0, 0.5)
     */
    public static AdjudicatorConfig of(QbafWeightLearner.SemanticsStrategy strategy,
                                        Map<String, Double> kindWeights,
                                        double supportedAt, double refutedAt) {
        if (supportedAt <= 0.5 || supportedAt > 1.0)
            throw new IllegalArgumentException("supportedAt must be in (0.5,1], got " + supportedAt);
        if (refutedAt < 0.0 || refutedAt >= 0.5)
            throw new IllegalArgumentException("refutedAt must be in [0,0.5), got " + refutedAt);
        return new AdjudicatorConfig(strategy, kindWeights, supportedAt, refutedAt);
    }

    // ── Adjudication ─────────────────────────────────────────────────────────────

    /**
     * Adjudicate the claim using this config's semantics and kind weights.
     * Equivalent to {@link ClaimAdjudicator#adjudicate(String, double, List)} when using
     * {@link #dfQuad()} with an empty weight map.
     *
     * @param claimLabel human-readable claim label
     * @param prior      prior strength in [0, 1]
     * @param items      evidence items
     * @return the adjudicated verdict; trace carries {@code semantics=qe} or {@code semantics=df-quad}
     */
    public AdjudicatedVerdict adjudicate(String claimLabel, double prior, List<EvidenceItem> items) {
        Objects.requireNonNull(claimLabel, "claimLabel");
        Objects.requireNonNull(items,      "items");

        Qbaf.Builder builder = Qbaf.builder();
        builder.argument(Argument.claim("claim", claimLabel, prior));

        List<String>        itemIds     = new ArrayList<>(items.size());
        List<EvidenceItem>  scaledItems = new ArrayList<>(items.size());

        for (int i = 0; i < items.size(); i++) {
            EvidenceItem item   = items.get(i);
            String       id     = "e" + i;
            itemIds.add(id);

            double wKind       = kindWeights.getOrDefault(item.kind(), 1.0);
            double scaledConf  = Math.max(0.0, Math.min(1.0, item.confidence() * wKind));
            EvidenceItem scaled = new EvidenceItem(item.label(), scaledConf, item.pro(),
                    item.kind(), item.provenance());
            scaledItems.add(scaled);

            ArgKind kind = item.pro() ? ArgKind.PRO : ArgKind.CON;
            builder.argument(new Argument(id, item.label(), scaledConf, kind));
            if (item.pro()) builder.support(id, "claim");
            else            builder.attack (id, "claim");
        }

        Qbaf   qbaf         = builder.build();
        double claimStrength = strategy.claimStrength(qbaf);
        claimStrength = Math.max(0.0, Math.min(1.0, claimStrength));

        AdjudicatedVerdict.Status status;
        if      (claimStrength >= supportedAt) status = AdjudicatedVerdict.Status.SUPPORTED;
        else if (claimStrength <= refutedAt)   status = AdjudicatedVerdict.Status.REFUTED;
        else                                   status = AdjudicatedVerdict.Status.UNKNOWN;

        // Build argument strengths — re-evaluate to get per-arg values for the trace
        Map<String, Double> argStrengths = buildArgStrengths(qbaf, claimStrength);

        ReasoningTrace trace = buildTrace(claimLabel, claimStrength, prior, scaledItems, itemIds, argStrengths);

        return new AdjudicatedVerdict(status, claimStrength, argStrengths, trace);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    private Map<String, Double> buildArgStrengths(Qbaf qbaf, double claimStrength) {
        // Re-evaluate via strategy to get the full strength map
        // For the two built-in strategies we can shortcut; for custom strategies
        // we compute claim strength and leave evidence items at their base scores.
        // Use a full QBAF re-evaluation where possible.
        Map<String, Double> map = new LinkedHashMap<>();
        if (strategy == QbafWeightLearner.SemanticsStrategy.DF_QUAD) {
            DfQuadSemantics.Result r = DfQuadSemantics.evaluate(qbaf);
            map.putAll(r.strengths());
        } else if (strategy == QbafWeightLearner.SemanticsStrategy.QE) {
            QeSemantics.Result r = QeSemantics.evaluate(qbaf);
            map.putAll(r.strengths());
        } else {
            // Custom strategy: populate evidence items at base score, claim at computed strength
            for (Argument a : qbaf.arguments()) {
                if (a.kind() == ArgKind.CLAIM) map.put(a.id(), claimStrength);
                else                           map.put(a.id(), a.baseScore());
            }
        }
        return map;
    }

    private String semanticsLabel() {
        if (strategy == QbafWeightLearner.SemanticsStrategy.DF_QUAD) return "df-quad";
        if (strategy == QbafWeightLearner.SemanticsStrategy.QE)      return "qe";
        return "custom";
    }

    private ReasoningTrace buildTrace(String claimLabel, double claimStrength, double prior,
                                       List<EvidenceItem> scaledItems, List<String> itemIds,
                                       Map<String, Double> strengths) {
        List<Step> premises = new ArrayList<>(scaledItems.size());
        for (int i = 0; i < scaledItems.size(); i++) {
            EvidenceItem item        = scaledItems.get(i);
            String       id         = itemIds.get(i);
            double       finalStr   = strengths.getOrDefault(id, item.confidence());

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("kind",          item.kind());
            meta.put("baseScore",     String.valueOf(item.confidence()));
            meta.put("finalStrength", String.valueOf(finalStr));

            String prov = item.provenance().isEmpty() ? null : String.join(", ", item.provenance());

            Step step;
            if (!item.pro()) {
                step = new Step(StepKind.REBUTTAL, item.label(), "attacks:" + claimLabel,
                        finalStr, prov, List.of(), null, meta);
            } else {
                StepKind sk = "direct-fact".equals(item.kind()) ? StepKind.FACT : StepKind.INFERENCE;
                step = new Step(sk, item.label(), item.kind(), finalStr, prov, List.of(), null, meta);
            }
            premises.add(step);
        }

        Map<String, String> rootMeta = new LinkedHashMap<>();
        rootMeta.put("semantics",   semanticsLabel());
        rootMeta.put("prior",       String.valueOf(prior));
        rootMeta.put("supportedAt", String.valueOf(supportedAt));
        rootMeta.put("refutedAt",   String.valueOf(refutedAt));

        Step root = new Step(StepKind.INFERENCE, claimLabel,
                semanticsLabel() + "-adjudication",
                claimStrength, null, premises, null, rootMeta);

        return ReasoningTrace.of(root);
    }
}
