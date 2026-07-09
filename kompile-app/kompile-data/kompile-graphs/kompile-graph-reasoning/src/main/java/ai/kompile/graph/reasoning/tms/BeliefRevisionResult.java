/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms;

import ai.kompile.graph.reasoning.fol.FactStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The result of a belief revision operation: retracting a fact and propagating its effects.
 *
 * @param retractedFactKey  the atom key of the retracted fact
 * @param unsupportedAtoms  atoms whose ONLY support was the retracted fact
 * @param weakenedAtoms     atoms that had the retracted fact as one of many supports
 * @param revisedFactStore  the revised FactStore with the fact removed
 * @param purgedAtoms       atom keys that were actively removed from the {@link ai.kompile.graph.reasoning.fol.InferredFactStore}
 *                          during this revision; empty ({@link List#of()}) for the legacy
 *                          {@link BeliefReviser#retract} path, which does not touch the
 *                          inferred-fact store
 * @param revisedAt         timestamp when this revision was computed
 */
public record BeliefRevisionResult(
        String retractedFactKey,
        Set<String> unsupportedAtoms,
        Set<String> weakenedAtoms,
        FactStore revisedFactStore,
        List<String> purgedAtoms,
        Instant revisedAt
) {

    public BeliefRevisionResult {
        Objects.requireNonNull(retractedFactKey, "retractedFactKey must not be null");
        Objects.requireNonNull(revisedFactStore, "revisedFactStore must not be null");
        Objects.requireNonNull(revisedAt, "revisedAt must not be null");
        unsupportedAtoms = (unsupportedAtoms == null) ? Set.of() : Set.copyOf(unsupportedAtoms);
        weakenedAtoms = (weakenedAtoms == null) ? Set.of() : Set.copyOf(weakenedAtoms);
        purgedAtoms = (purgedAtoms == null) ? List.of() : List.copyOf(purgedAtoms);
    }
}
