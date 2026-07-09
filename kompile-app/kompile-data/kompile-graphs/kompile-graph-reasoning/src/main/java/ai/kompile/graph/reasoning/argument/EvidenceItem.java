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

import java.util.List;
import java.util.Objects;

/**
 * A single piece of evidence for or against a claim, used by {@link ClaimAdjudicator}.
 *
 * <p>Each item maps to one argument node in the {@link Qbaf} built by the adjudicator.
 * The {@code pro} flag determines whether it becomes a {@link ArgKind#PRO} supporter or
 * {@link ArgKind#CON} attacker of the claim. The {@code kind} string classifies the
 * evidence type (e.g. {@code "direct-fact"}, {@code "rule-derivation"}, {@code "psl"},
 * {@code "path"}, {@code "kge"}, {@code "functional-conflict"}, {@code "contradiction"},
 * {@code "negated-atom"}).</p>
 *
 * @param label      human-readable label for this piece of evidence
 * @param confidence strength/confidence in [0, 1]; used as the base score of the QBAF node
 * @param pro        {@code true} if this is supporting evidence; {@code false} if attacking
 * @param kind       evidence type classifier (free-form string; not null)
 * @param provenance optional list of source references (fact atom keys, rule ids, etc.)
 */
public record EvidenceItem(
        String label,
        double confidence,
        boolean pro,
        String kind,
        List<String> provenance
) {
    public EvidenceItem {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
        provenance = (provenance == null) ? List.of() : List.copyOf(provenance);
    }

    /** Convenience constructor with empty provenance. */
    public EvidenceItem(String label, double confidence, boolean pro, String kind) {
        this(label, confidence, pro, kind, List.of());
    }

    /** Factory for a PRO (supporting) evidence item. */
    public static EvidenceItem pro(String label, double confidence, String kind, List<String> provenance) {
        return new EvidenceItem(label, confidence, true, kind, provenance);
    }

    /** Factory for a CON (attacking) evidence item. */
    public static EvidenceItem con(String label, double confidence, String kind, List<String> provenance) {
        return new EvidenceItem(label, confidence, false, kind, provenance);
    }
}
