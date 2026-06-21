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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory implementation of {@link WeightStore}.
 *
 * <p>Maintains a version history for each programId. The internal list is indexed such that
 * {@code list.get(version - 1)} returns the weight-set for that version. Defensive copies
 * are made on both input (to prevent external mutation of stored data) and output (to prevent
 * callers from mutating stored data).</p>
 *
 * <h3>Thread safety</h3>
 * <p>Not thread-safe. External synchronization is the caller's responsibility.</p>
 */
public class InMemoryWeightStore implements WeightStore {

    /**
     * Per-programId history: each entry is one saved weight-set, at index {@code version - 1}.
     */
    private final Map<String, List<Map<String, Double>>> historyByProgram = new LinkedHashMap<>();

    @Override
    public int save(String programId, Map<String, Double> weights) {
        if (programId == null) throw new IllegalArgumentException("programId must not be null");
        if (weights == null) throw new IllegalArgumentException("weights must not be null");
        List<Map<String, Double>> history =
                historyByProgram.computeIfAbsent(programId, k -> new ArrayList<>());
        // Defensive copy to prevent external mutation.
        history.add(new LinkedHashMap<>(weights));
        return history.size(); // 1-based version = list size after add
    }

    @Override
    public Optional<Map<String, Double>> latest(String programId) {
        List<Map<String, Double>> history = historyByProgram.get(programId);
        if (history == null || history.isEmpty()) return Optional.empty();
        // Defensive copy on the way out.
        return Optional.of(new LinkedHashMap<>(history.get(history.size() - 1)));
    }

    @Override
    public int latestVersion(String programId) {
        List<Map<String, Double>> history = historyByProgram.get(programId);
        return (history == null) ? 0 : history.size();
    }

    @Override
    public Optional<Map<String, Double>> get(String programId, int version) {
        List<Map<String, Double>> history = historyByProgram.get(programId);
        if (history == null || version < 1 || version > history.size()) return Optional.empty();
        // Defensive copy on the way out.
        return Optional.of(new LinkedHashMap<>(history.get(version - 1)));
    }

    @Override
    public List<Integer> versions(String programId) {
        List<Map<String, Double>> history = historyByProgram.get(programId);
        if (history == null || history.isEmpty()) return List.of();
        List<Integer> vers = new ArrayList<>(history.size());
        for (int i = 1; i <= history.size(); i++) {
            vers.add(i);
        }
        return Collections.unmodifiableList(vers);
    }

    @Override
    public Set<String> programIds() {
        return Collections.unmodifiableSet(historyByProgram.keySet());
    }
}
