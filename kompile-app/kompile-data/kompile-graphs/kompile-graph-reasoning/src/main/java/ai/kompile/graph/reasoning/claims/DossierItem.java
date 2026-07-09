/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.claims;

import java.util.List;
import java.util.Objects;

/**
 * A single piece of evidence in a {@link ClaimDossier} — either supporting or refuting the claim.
 *
 * @param kind        evidence kind: one of the {@link Kind} constants
 * @param description human-readable description of this evidence item
 * @param probability probability in {@code [0, 1]} that this item supports the claim
 *                    (for refuting items this is the confidence in the refutation)
 * @param provenance  list of source references (fact keys, rule names, path node ids, etc.)
 */
public record DossierItem(Kind kind, String description, double probability, List<String> provenance) {

    /** Canonical evidence kinds matching the ExFaKT "supporting + refuting chains" framing. */
    public enum Kind {
        /** A direct edge of the claim's predicate type in the graph. */
        DIRECT_EDGE,
        /** A Datalog/FOL derivation proof from the InferredFactStore. */
        DATALOG_PROOF,
        /** A PSL soft-truth inference result. */
        PSL,
        /** A Knowledge-Linker indirect path through the graph. */
        PATH,
        /** A KGE embedding plausibility signal. */
        KGE,
        /** A mined rule that fires for the claim's constants. */
        MINED_RULE,
        /** A functional-constraint conflict (another value fills the same single-valued slot). */
        FUNCTIONAL_CONFLICT,
        /** A negated atom present in the inferred store (explicit refutation). */
        NEGATED_ATOM
    }

    public DossierItem {
        Objects.requireNonNull(kind,        "kind");
        Objects.requireNonNull(description, "description");
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("probability must be in [0,1], got " + probability);
        }
        provenance = (provenance == null) ? List.of() : List.copyOf(provenance);
    }

    /** Whether this item is a refuting item (conflict or negated-atom kind). */
    public boolean isRefuting() {
        return kind == Kind.FUNCTIONAL_CONFLICT || kind == Kind.NEGATED_ATOM;
    }
}
