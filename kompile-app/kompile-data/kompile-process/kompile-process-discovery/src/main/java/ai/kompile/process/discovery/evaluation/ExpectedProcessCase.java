/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.process.discovery.evaluation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Domain-neutral expected process used to evaluate discovered candidates. */
public record ExpectedProcessCase(
        String processId,
        String processName,
        List<RequiredConcept> requiredConcepts,
        List<PrecedenceConstraint> precedenceConstraints,
        int minimumTraceCount,
        double hitThreshold) {

    public ExpectedProcessCase {
        requireText(processId, "processId");
        requireText(processName, "processName");
        requiredConcepts = requiredConcepts == null ? List.of() : List.copyOf(requiredConcepts);
        precedenceConstraints = precedenceConstraints == null ? List.of() : List.copyOf(precedenceConstraints);
        if (minimumTraceCount < 0) {
            throw new IllegalArgumentException("minimumTraceCount cannot be negative");
        }
        if (!Double.isFinite(hitThreshold) || hitThreshold < 0.0 || hitThreshold > 1.0) {
            throw new IllegalArgumentException("hitThreshold must be in [0, 1]");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (RequiredConcept concept : requiredConcepts) {
            if (!ids.add(concept.id())) {
                throw new IllegalArgumentException("Duplicate required concept: " + concept.id());
            }
        }
        for (PrecedenceConstraint constraint : precedenceConstraints) {
            if (!ids.contains(constraint.beforeConceptId()) || !ids.contains(constraint.afterConceptId())) {
                throw new IllegalArgumentException("Precedence constraints must reference required concepts");
            }
        }
    }

    /**
     * Backward-compatible form for evaluations that do not require a minimum number of traces.
     */
    public ExpectedProcessCase(String processId,
                               String processName,
                               List<RequiredConcept> requiredConcepts,
                               List<PrecedenceConstraint> precedenceConstraints,
                               double hitThreshold) {
        this(processId, processName, requiredConcepts, precedenceConstraints, 0, hitThreshold);
    }

    public record RequiredConcept(String id, String name, Set<String> aliases) {
        public RequiredConcept {
            requireText(id, "concept id");
            requireText(name, "concept name");
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            terms.add(id);
            terms.add(name);
            if (aliases != null) {
                aliases.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).forEach(terms::add);
            }
            aliases = Set.copyOf(terms);
        }

        public static RequiredConcept of(String id, String name, String... aliases) {
            return new RequiredConcept(id, name, aliases == null ? Set.of() : Set.of(aliases));
        }
    }

    public record PrecedenceConstraint(String beforeConceptId, String afterConceptId) {
        public PrecedenceConstraint {
            requireText(beforeConceptId, "beforeConceptId");
            requireText(afterConceptId, "afterConceptId");
            if (beforeConceptId.equals(afterConceptId)) {
                throw new IllegalArgumentException("A concept cannot precede itself");
            }
        }

        @Override
        public String toString() {
            return beforeConceptId + "->" + afterConceptId;
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }
}
