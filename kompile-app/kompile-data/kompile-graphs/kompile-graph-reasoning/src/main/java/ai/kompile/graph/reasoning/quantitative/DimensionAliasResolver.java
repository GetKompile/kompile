/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import java.util.List;
import java.util.Optional;

/**
 * Host-provided vocabulary bridge for dimension members the graph itself cannot align.
 *
 * <p>Taxonomy tables and context tokens resolve most scope vocabulary ("Direct-to-Consumer" is
 * DTC per the channel taxonomy; "AMER" appears in the workbook name). What they cannot resolve is
 * colloquial vocabulary that never occurs in the sources: a user asking about "US" when every
 * artifact says "AMER". That is a language problem, so it is exposed as an SPI the distribution
 * can back with a language model — a small one suffices, because the question is closed-set
 * selection: one requested value, one bounded list of graph-derived members, answer one member or
 * nothing.</p>
 *
 * <p>Guardrails are enforced by the caller, not trusted to the implementation: the answer must be
 * one of the offered members, must clear a confidence floor, and only ever <em>translates the
 * question's vocabulary</em>. Entity compatibility is still decided by graph evidence afterwards —
 * a resolver can never force a match that the graph does not support, and explicit conflicting
 * dimension values still reject. Resolutions are recorded as assumptions in the reasoning trace
 * with the implementation's provenance string.</p>
 *
 * <p>This library ships no implementation and names no model, backend, or endpoint. Implementations
 * should cache: retrieval may ask the same question for repeated queries.</p>
 */
@FunctionalInterface
public interface DimensionAliasResolver {

    Optional<Resolution> resolve(Question question);

    /**
     * @param dimensionKey   normalized dimension name, e.g. {@code region}
     * @param requestedValue the caller's vocabulary, e.g. {@code US}
     * @param members        graph-derived candidate members, e.g. {@code [AMER, EMEA, APAC]}
     */
    record Question(String dimensionKey, String requestedValue, List<String> members) {

        public Question {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    /**
     * @param member     the chosen member; must be one of {@link Question#members()} to be applied
     * @param confidence 0..1 self-assessed confidence; answers below the caller's floor are ignored
     * @param source     provenance recorded in traces, e.g. {@code llm:lfm2.5-1.2b@staging}
     */
    record Resolution(String member, double confidence, String source) {
    }
}
