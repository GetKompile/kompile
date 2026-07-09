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

import java.util.Map;
import java.util.Objects;

/**
 * The output of a {@link BelnapMarking} pass over a collection of facts.
 *
 * <p>Holds a per-canonical-atom {@link Mark} and the count of atoms marked {@link Mark#B}
 * (both true and false — the paraconsistently contested atoms). The {@code bCount} is the
 * most frequently cited "how contested is this neighbourhood" scalar and is the cheap first
 * signal to attach to {@code UNKNOWN} or contested verdicts.</p>
 *
 * <p>The map key is the <em>canonical</em> atom identifier, i.e., the predicate + args form
 * <strong>without</strong> any negation prefix — {@code CEO(acme,alice)} rather than
 * {@code NOT_CEO(acme,alice)}. This allows a single entry to record that both a positive and
 * a negative form of the same ground atom were observed.</p>
 *
 * @param byCanonicalAtom  immutable map from canonical atom string to its {@link Mark}
 * @param bCount           number of atoms marked {@link Mark#B} (paraconsistent conflicts)
 */
public record MarkingResult(Map<String, Mark> byCanonicalAtom, int bCount) {

    public MarkingResult {
        Objects.requireNonNull(byCanonicalAtom, "byCanonicalAtom must not be null");
        if (bCount < 0) {
            throw new IllegalArgumentException("bCount must be non-negative, got: " + bCount);
        }
        byCanonicalAtom = Map.copyOf(byCanonicalAtom);
    }

    /**
     * Return the mark for a specific canonical atom, or {@link Mark#N} if the atom
     * is not present in this result (i.e., it had no evidence).
     *
     * @param canonicalAtom the canonical atom key (without negation prefix)
     * @return the mark, never null
     */
    public Mark markFor(String canonicalAtom) {
        return byCanonicalAtom.getOrDefault(canonicalAtom, Mark.N);
    }

    /**
     * Return the number of atoms with any strong evidence ({@link Mark#T}, {@link Mark#F},
     * or {@link Mark#B}).
     */
    public int strongEvidenceCount() {
        return (int) byCanonicalAtom.values().stream()
                .filter(m -> m != Mark.N)
                .count();
    }
}
