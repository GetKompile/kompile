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

import ai.kompile.graph.reasoning.psl.PslProgram;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A mutable, in-memory store of {@link Fact} objects, keyed by their atom key.
 *
 * <p>Acts as the PSL-side evidence registry. Facts can be applied to a {@link PslProgram}
 * as observations prior to inference.</p>
 */
public class FactStore {

    private final Map<String, LinkedHashMap<String, StoredFact>> store = new LinkedHashMap<>();
    private long assertionSequence;

    private record StoredFact(Fact fact, long sequence) { }

    /**
     * Assert a fact into the store. If a fact with the same atom key already
     * exists, it is replaced (revision semantics).
     *
     * @param fact the fact to assert; must not be null
     */
    public synchronized void assertFact(Fact fact) {
        if (fact == null) throw new IllegalArgumentException("fact must not be null");
        LinkedHashMap<String, StoredFact> sources =
                store.computeIfAbsent(fact.atomKey(), ignored -> new LinkedHashMap<>());
        sources.remove(fact.sourceId());
        sources.put(fact.sourceId(), new StoredFact(fact, ++assertionSequence));
    }

    /**
     * Alias for {@link #assertFact(Fact)} using the conventional {@code assert_} name
     * (since {@code assert} is a reserved keyword in Java).
     *
     * @param fact the fact to assert; must not be null
     */
    public synchronized void assert_(Fact fact) {
        assertFact(fact);
    }

    /**
     * Retract a fact by its atom key.
     *
     * @param atomKey the atom key to retract
     * @return the removed fact, or empty if not present
     */
    public synchronized Optional<Fact> retract(String atomKey) {
        LinkedHashMap<String, StoredFact> removed = store.remove(atomKey);
        return removed == null ? Optional.empty() : effective(removed);
    }

    /**
     * Retract every fact owned by one provenance source, preserving observations asserted by all
     * other sources. Returns the number of removed facts.
     */
    public synchronized int retractBySource(String sourceId) {
        if (sourceId == null) return 0;
        int removed = 0;
        var iterator = store.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LinkedHashMap<String, StoredFact>> atom = iterator.next();
            if (atom.getValue().remove(sourceId) != null) removed++;
            if (atom.getValue().isEmpty()) iterator.remove();
        }
        return removed;
    }

    /** Atomically replace the complete fact set owned by one provenance source. */
    public synchronized int replaceSourceFacts(String sourceId, Collection<Fact> replacements) {
        if (sourceId == null) throw new IllegalArgumentException("sourceId must not be null");
        List<Fact> staged = replacements == null ? List.of() : List.copyOf(replacements);
        for (Fact fact : staged) {
            if (fact == null || !sourceId.equals(fact.sourceId())) {
                throw new IllegalArgumentException("replacement facts must all belong to source " + sourceId);
            }
        }
        int removed = retractBySource(sourceId);
        staged.forEach(this::assertFact);
        return removed;
    }

    /**
     * Find all facts whose atom key starts with the given predicate name followed by "("
     * or equals the predicate name exactly.
     *
     * @param predicate the predicate name to filter by (e.g. "State")
     * @return list of matching facts (may be empty)
     */
    public synchronized List<Fact> factsFor(String predicate) {
        List<Fact> result = new ArrayList<>();
        String prefix = predicate + "(";
        for (Fact f : effectiveFacts()) {
            if (f.atomKey().startsWith(prefix) || f.atomKey().equals(predicate)) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Return all facts as an unmodifiable collection.
     *
     * @return unmodifiable view of all stored facts
     */
    public synchronized Collection<Fact> allFacts() {
        return Collections.unmodifiableList(effectiveFacts());
    }

    /** All source-specific facts in assertion order, for lossless store copies and auditing. */
    public synchronized Collection<Fact> allSourceFacts() {
        List<StoredFact> sourced = store.values().stream()
                .flatMap(sources -> sources.values().stream())
                .sorted(Comparator.comparingLong(StoredFact::sequence))
                .toList();
        return Collections.unmodifiableList(sourced.stream().map(StoredFact::fact).toList());
    }

    /** Source-specific facts for one atom in assertion order. */
    public synchronized List<Fact> sourceFactsFor(String atomKey) {
        Map<String, StoredFact> sources = store.get(atomKey);
        if (sources == null) return List.of();
        return sources.values().stream()
                .sorted(Comparator.comparingLong(StoredFact::sequence))
                .map(StoredFact::fact)
                .toList();
    }

    /**
     * Look up a fact by its atom key.
     *
     * @param atomKey the key to look up
     * @return the fact, or empty if not present
     */
    public synchronized Optional<Fact> factFor(String atomKey) {
        return effective(store.get(atomKey));
    }

    /** @return the number of facts in the store */
    public synchronized int size() {
        return store.size();
    }

    /** @return true if there are no facts in the store */
    public synchronized boolean isEmpty() {
        return store.isEmpty();
    }

    /** Remove all facts from the store. */
    public synchronized void clear() {
        store.clear();
        assertionSequence = 0L;
    }

    /**
     * Apply all stored facts to the given PSL program as observations.
     *
     * <p>Hard facts are observed with their value (1.0 for {@link Fact#observed}).
     * Soft facts are also observed (PSL allows soft priors as observed atoms too,
     * but target atoms can be inferred separately).</p>
     *
     * <p>Atom key format is "Predicate(arg1, arg2)" or "Predicate(arg1,arg2)".
     * This method parses the predicate and args and calls
     * {@link PslProgram#observe(String, double, String...)}.</p>
     *
     * @param program the PSL program to observe facts into
     */
    public synchronized void applyToProgram(PslProgram program) {
        for (Fact fact : effectiveFacts()) {
            String atomKey = fact.atomKey();
            int lp = atomKey.indexOf('(');
            if (lp < 0) {
                // 0-arity predicate
                program.observe(atomKey, fact.value());
            } else {
                int rp = atomKey.lastIndexOf(')');
                String predicate = atomKey.substring(0, lp).trim();
                String inside = (rp > lp) ? atomKey.substring(lp + 1, rp).trim() : "";
                if (inside.isEmpty()) {
                    program.observe(predicate, fact.value());
                } else {
                    String[] argTokens = inside.split(",");
                    String[] args = new String[argTokens.length];
                    for (int i = 0; i < argTokens.length; i++) {
                        args[i] = argTokens[i].trim();
                    }
                    program.observe(predicate, fact.value(), args);
                }
            }
        }
    }

    private List<Fact> effectiveFacts() {
        return store.values().stream()
                .map(FactStore::effective)
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<Fact> effective(Map<String, StoredFact> sources) {
        if (sources == null || sources.isEmpty()) return Optional.empty();
        return sources.values().stream()
                .max(Comparator.comparingLong(StoredFact::sequence))
                .map(StoredFact::fact);
    }
}
