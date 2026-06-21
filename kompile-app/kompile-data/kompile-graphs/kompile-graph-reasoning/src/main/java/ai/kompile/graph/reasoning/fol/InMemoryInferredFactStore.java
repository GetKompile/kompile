/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link InferredFactStore}.
 *
 * <p>Maintains a version history for each atom key. The {@link #store(InferredFact)} method
 * auto-assigns the next version if the caller supplies version 0 (or any version {@code ≤} the
 * current maximum for the same atom key), so callers may pass {@code version=0} and let the
 * store assign the canonical version.</p>
 *
 * <h3>Thread safety</h3>
 * <p>Not thread-safe. External synchronization is the caller's responsibility.</p>
 */
public class InMemoryInferredFactStore implements InferredFactStore {

    /** History list per atom key: sorted in ascending version order (last = latest). */
    private final Map<String, List<InferredFact>> historyByKey = new LinkedHashMap<>();

    /** Global version counter — guarantees strict monotonic increase across the store. */
    private final AtomicLong versionSequence = new AtomicLong(1L);

    @Override
    public void store(InferredFact fact) {
        if (fact == null) throw new IllegalArgumentException("fact must not be null");
        List<InferredFact> history = historyByKey.computeIfAbsent(fact.atomKey(), k -> new ArrayList<>());

        // Auto-assign version if caller supplied 0 or a version ≤ current max.
        long maxVersion = history.isEmpty() ? 0L : history.get(history.size() - 1).version();
        long assignedVersion;
        if (fact.version() <= maxVersion) {
            assignedVersion = versionSequence.getAndIncrement();
        } else {
            assignedVersion = fact.version();
            // Ensure the global counter stays ahead.
            versionSequence.updateAndGet(v -> Math.max(v, assignedVersion + 1));
        }

        // Rebuild with the canonical version if it changed.
        InferredFact stored = (assignedVersion == fact.version()) ? fact
                : new InferredFact(fact.atomKey(), fact.value(), fact.confidence(),
                fact.supportingFactKeys(), fact.supportingRuleIds(),
                fact.runId(), assignedVersion, fact.inferredAt());
        history.add(stored);
    }

    @Override
    public Optional<InferredFact> latest(String atomKey) {
        List<InferredFact> history = historyByKey.get(atomKey);
        if (history == null || history.isEmpty()) return Optional.empty();
        return Optional.of(history.get(history.size() - 1));
    }

    @Override
    public List<InferredFact> history(String atomKey) {
        List<InferredFact> h = historyByKey.get(atomKey);
        return (h == null) ? List.of() : Collections.unmodifiableList(h);
    }

    @Override
    public Collection<InferredFact> byRun(String runId) {
        if (runId == null) return List.of();
        List<InferredFact> result = new ArrayList<>();
        for (List<InferredFact> history : historyByKey.values()) {
            for (InferredFact fact : history) {
                if (runId.equals(fact.runId())) {
                    result.add(fact);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Collection<InferredFact> allLatest() {
        List<InferredFact> result = new ArrayList<>();
        for (List<InferredFact> history : historyByKey.values()) {
            if (!history.isEmpty()) {
                result.add(history.get(history.size() - 1));
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public void purge(String atomKey) {
        historyByKey.remove(atomKey);
    }

    @Override
    public int size() {
        return historyByKey.size();
    }

    @Override
    public boolean isEmpty() {
        return historyByKey.isEmpty();
    }

    /**
     * Serialize all latest inferred facts to newline-delimited JSON (JSONL format).
     *
     * @return JSONL string, one JSON object per line
     */
    public String toJsonl() {
        StringBuilder sb = new StringBuilder();
        for (InferredFact fact : allLatest()) {
            sb.append(fact.toJson()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Load all facts from a JSONL string, appending them to this store.
     *
     * @param jsonl the JSONL string produced by {@link #toJsonl()}
     */
    public void fromJsonl(String jsonl) {
        if (jsonl == null || jsonl.isBlank()) return;
        for (String line : jsonl.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                store(InferredFact.fromJson(trimmed));
            }
        }
    }
}
