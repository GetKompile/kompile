/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian;

import ai.kompile.core.events.EmpiricalPriorSource;
import ai.kompile.core.events.ObservedEventStat;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Blends a structural prior (from node confidence / edge weight) with an empirical Beta-Binomial
 * prior learned from observed events. When no observation evidence exists the structural value is
 * returned unchanged, so inference is fully backward-compatible.
 *
 * <p>Nodes use evidence-weighted shrinkage — {@code weight = evidence / (evidence + k)} — so a sparse
 * history leans structural and an abundant one leans empirical. Connections use the gated empirical
 * prior directly (it is only exposed once enough evidence has accumulated).</p>
 */
public final class EmpiricalPriorBlend {

    private EmpiricalPriorBlend() {
    }

    /** Blend a node's structural prior P(node=TRUE) with its observed occurrence probability. */
    public static double forNode(double structural, EmpiricalPriorSource priors, String nodeId) {
        if (priors == null || !priors.isEnabled() || nodeId == null) {
            return structural;
        }
        Optional<ObservedEventStat> stat = priors.statForNode(nodeId);
        if (stat.isEmpty()) {
            return structural;
        }
        // Real evidence beyond the ~uniform prior pseudo-counts (alpha+beta - 2), post-decay.
        double evidence = Math.max(0.0, stat.get().evidenceStrength() - 2.0);
        if (evidence <= 0.0) {
            return structural;
        }
        double w = evidence / (evidence + Math.max(1e-9, priors.blendK()));
        return clamp(w * stat.get().probability() + (1.0 - w) * structural);
    }

    /** Blend a connection's structural strength with its observed-occurrence probability (gated). */
    public static double forConnection(double structuralStrength, EmpiricalPriorSource priors,
                                       String sourceId, String edgeType, String targetId) {
        if (priors == null || !priors.isEnabled()) {
            return structuralStrength;
        }
        OptionalDouble empirical = priors.priorForConnection(sourceId, edgeType, targetId);
        return empirical.isPresent() ? clamp(empirical.getAsDouble()) : structuralStrength;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
