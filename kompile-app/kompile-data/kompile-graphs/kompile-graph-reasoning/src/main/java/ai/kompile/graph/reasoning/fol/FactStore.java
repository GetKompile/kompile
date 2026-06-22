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

    private final Map<String, Fact> store = new LinkedHashMap<>();

    /**
     * Assert a fact into the store. If a fact with the same atom key already
     * exists, it is replaced (revision semantics).
     *
     * @param fact the fact to assert; must not be null
     */
    public void assertFact(Fact fact) {
        if (fact == null) throw new IllegalArgumentException("fact must not be null");
        store.put(fact.atomKey(), fact);
    }

    /**
     * Alias for {@link #assertFact(Fact)} using the conventional {@code assert_} name
     * (since {@code assert} is a reserved keyword in Java).
     *
     * @param fact the fact to assert; must not be null
     */
    public void assert_(Fact fact) {
        assertFact(fact);
    }

    /**
     * Retract a fact by its atom key.
     *
     * @param atomKey the atom key to retract
     * @return the removed fact, or empty if not present
     */
    public Optional<Fact> retract(String atomKey) {
        return Optional.ofNullable(store.remove(atomKey));
    }

    /**
     * Find all facts whose atom key starts with the given predicate name followed by "("
     * or equals the predicate name exactly.
     *
     * @param predicate the predicate name to filter by (e.g. "State")
     * @return list of matching facts (may be empty)
     */
    public List<Fact> factsFor(String predicate) {
        List<Fact> result = new ArrayList<>();
        String prefix = predicate + "(";
        for (Fact f : store.values()) {
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
    public Collection<Fact> allFacts() {
        return Collections.unmodifiableCollection(store.values());
    }

    /**
     * Look up a fact by its atom key.
     *
     * @param atomKey the key to look up
     * @return the fact, or empty if not present
     */
    public Optional<Fact> factFor(String atomKey) {
        return Optional.ofNullable(store.get(atomKey));
    }

    /** @return the number of facts in the store */
    public int size() {
        return store.size();
    }

    /** @return true if there are no facts in the store */
    public boolean isEmpty() {
        return store.isEmpty();
    }

    /** Remove all facts from the store. */
    public void clear() {
        store.clear();
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
    public void applyToProgram(PslProgram program) {
        for (Fact fact : store.values()) {
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
}
