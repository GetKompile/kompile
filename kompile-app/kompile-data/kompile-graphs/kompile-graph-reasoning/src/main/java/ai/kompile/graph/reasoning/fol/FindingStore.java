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

/**
 * A mutable, in-memory store of {@link Finding} objects, keyed by their
 * {@link Finding#groundedKey() grounded key}.
 *
 * <p>Thread safety: not thread-safe. External synchronization is the caller's responsibility.</p>
 */
public class FindingStore {

    private final Map<String, Finding> store = new LinkedHashMap<>();

    /**
     * Assert a finding into the store. If a finding with the same grounded key already
     * exists, it is replaced (revision semantics).
     *
     * @param finding the finding to assert; must not be null
     */
    public void assertFinding(Finding finding) {
        if (finding == null) throw new IllegalArgumentException("finding must not be null");
        store.put(finding.groundedKey(), finding);
    }

    /**
     * Retract a finding by its random variable name and entity arguments.
     *
     * @param rvName     random variable name
     * @param entityArgs entity argument IDs
     * @return the removed finding, or empty if not present
     */
    public Optional<Finding> retract(String rvName, List<String> entityArgs) {
        String key = rvName + "(" + String.join(",", entityArgs) + ")";
        return retract(key);
    }

    /**
     * Retract a finding by its precomputed grounded key.
     *
     * @param groundedKey the key (e.g. "isActive(alice)")
     * @return the removed finding, or empty if not present
     */
    public Optional<Finding> retract(String groundedKey) {
        return Optional.ofNullable(store.remove(groundedKey));
    }

    /**
     * Find all findings whose {@code rvName} matches the given value.
     *
     * @param rvName the random variable name to filter by
     * @return list of matching findings (may be empty)
     */
    public List<Finding> findingsFor(String rvName) {
        List<Finding> result = new ArrayList<>();
        for (Finding f : store.values()) {
            if (rvName.equals(f.rvName())) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Return all findings as an unmodifiable collection.
     *
     * @return unmodifiable view of all stored findings
     */
    public Collection<Finding> allFindings() {
        return Collections.unmodifiableCollection(store.values());
    }

    /**
     * Look up a finding by its precomputed grounded key.
     *
     * @param groundedKey the key to look up
     * @return the finding, or empty if not present
     */
    public Optional<Finding> findingFor(String groundedKey) {
        return Optional.ofNullable(store.get(groundedKey));
    }

    /** @return the number of findings in the store */
    public int size() {
        return store.size();
    }

    /** @return true if there are no findings in the store */
    public boolean isEmpty() {
        return store.isEmpty();
    }

    /** Remove all findings from the store. */
    public void clear() {
        store.clear();
    }
}
