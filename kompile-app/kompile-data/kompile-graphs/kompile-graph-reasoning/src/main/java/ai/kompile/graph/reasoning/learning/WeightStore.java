/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.psl.PslRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SPI for persisting and retrieving learned PSL rule weights, keyed per program/ruleset,
 * with monotonic versioning — the weight-learning analog of
 * {@link ai.kompile.graph.reasoning.fol.InferredFactStore}.
 *
 * <p>An implementation may be backed by an in-memory map ({@link InMemoryWeightStore}),
 * a durable JSON-file store ({@link FileWeightStore}), or any other backend. The interface
 * is intentionally simple so it can be implemented without infrastructure dependencies.</p>
 *
 * <h3>Version semantics</h3>
 * <p>Each {@link #save(String, Map)} call assigns the next monotonic version for the given
 * {@code programId}, starting at 1. Prior versions are retained and addressable via
 * {@link #get(String, int)}.</p>
 */
public interface WeightStore {

    /**
     * Store a weight-set under {@code programId}, assign and return the next monotonic
     * version for that programId (starting at 1).
     *
     * @param programId the program/ruleset identifier; must not be null
     * @param weights   map of rule display to weight; must not be null
     * @return the version number assigned to this entry (1-based, monotonically increasing)
     */
    int save(String programId, Map<String, Double> weights);

    /**
     * Convenience: build the {@code ruleDisplay → weight} map from the given rules
     * (using {@link PslRule#toString()} as the display key and {@link PslRule#weight()}
     * as the value), then delegate to {@link #save(String, Map)}.
     *
     * @param programId the program/ruleset identifier; must not be null
     * @param rules     the learned rules; must not be null
     * @return the version number assigned to this entry
     */
    default int save(String programId, List<PslRule> rules) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (PslRule rule : rules) {
            weights.put(rule.toString(), rule.weight());
        }
        return save(programId, weights);
    }

    /**
     * Retrieve the latest (highest-versioned) weight-set for the given programId.
     *
     * @param programId the program/ruleset identifier
     * @return the latest weight-set, or empty if not present
     */
    Optional<Map<String, Double>> latest(String programId);

    /**
     * Return the latest version number for the given programId, or 0 if none exists.
     *
     * @param programId the program/ruleset identifier
     * @return latest version number (1-based), or 0 if the programId has no saved weights
     */
    int latestVersion(String programId);

    /**
     * Retrieve the weight-set for the given programId at the specified version.
     *
     * @param programId the program/ruleset identifier
     * @param version   the 1-based version number
     * @return the weight-set at that version, or empty if not present
     */
    Optional<Map<String, Double>> get(String programId, int version);

    /**
     * Return all saved version numbers for the given programId, in ascending order.
     *
     * @param programId the program/ruleset identifier
     * @return ascending list of version numbers; empty if none
     */
    List<Integer> versions(String programId);

    /**
     * Return all programIds that have at least one saved weight-set.
     *
     * @return set of programIds (order unspecified)
     */
    Set<String> programIds();
}
