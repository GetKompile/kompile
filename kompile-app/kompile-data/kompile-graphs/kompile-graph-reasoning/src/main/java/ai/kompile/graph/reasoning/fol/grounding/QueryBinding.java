/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One variable-binding row returned by {@link ConjunctiveQueryEngine#query}.
 *
 * <p>A binding maps each query variable (e.g. {@code "?X"}) to the ground constant
 * (e.g. {@code "Alice"}) that satisfies the conjunctive pattern for this row.
 * The {@link #confidence()} is the minimum soft-truth value across all matched atoms
 * in the row (Gödel / minimum T-norm; WP1c — previously mislabelled "Łukasiewicz", which is
 * {@code max(0, Σsᵢ − (n−1))} and would collapse long conjunctions to 0).</p>
 *
 * @param bindings   variable → ground-constant map (query variables as keys, constants as values)
 * @param confidence minimum confidence across matched atoms, in [0, 1]
 */
public record QueryBinding(Map<String, String> bindings, double confidence) {

    public QueryBinding {
        Objects.requireNonNull(bindings, "bindings must not be null");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
        }
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    /**
     * Look up the ground constant bound to a query variable.
     *
     * @param variable the variable name (e.g. {@code "?X"} or {@code "X"})
     * @return the bound constant, or {@code null} if the variable is not in this binding
     */
    public String get(String variable) {
        // Support both "?X" and "X" as variable names
        String val = bindings.get(variable);
        if (val == null && variable.startsWith("?")) {
            val = bindings.get(variable.substring(1));
        }
        return val;
    }
}
