/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms.inconsistency;

import java.util.Objects;

/**
 * An ordered pair of <em>canonical atom keys</em> that form a minimal inconsistent set (MIS).
 *
 * <p>For the two contradiction kinds handled by {@link BelnapMarking} (negated-pair and
 * functional-predicate clashes), every MIS has exactly 2 members. Each {@code ConflictPair}
 * represents one such MIS.</p>
 *
 * <p>The keys are the canonical form without negation decoration as returned by
 * {@link BelnapMarking.ParsedAtom#canonicalKey()} — for example {@code "CEO|[acme, alice]"}
 * and {@code "CEO|[acme, bob]"} for a functional-predicate clash.</p>
 *
 * @param canonicalKeyA  canonical key of the first conflicting atom
 * @param canonicalKeyB  canonical key of the second conflicting atom
 * @param description    human-readable description of why these atoms conflict
 */
public record ConflictPair(String canonicalKeyA, String canonicalKeyB, String description) {

    public ConflictPair {
        Objects.requireNonNull(canonicalKeyA, "canonicalKeyA must not be null");
        Objects.requireNonNull(canonicalKeyB, "canonicalKeyB must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }

    /**
     * Return true if the given canonical atom key is either side of this conflict.
     *
     * @param canonicalKey the key to test
     * @return true if this pair involves that atom
     */
    public boolean involves(String canonicalKey) {
        return Objects.equals(canonicalKeyA, canonicalKey) || Objects.equals(canonicalKeyB, canonicalKey);
    }

    @Override
    public String toString() {
        return "ConflictPair{" + canonicalKeyA + " ↔ " + canonicalKeyB + " [" + description + "]}";
    }
}
