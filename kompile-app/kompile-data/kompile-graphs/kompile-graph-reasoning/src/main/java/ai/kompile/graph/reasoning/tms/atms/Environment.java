/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.tms.atms;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * An immutable, sorted set of assumption identifiers representing one minimal
 * support environment for a derived node.
 *
 * <p>Following de Kleer (1986) §3, an <em>environment</em> is a set of assumptions
 * sufficient to derive a conclusion. Environments are the atoms of the ATMS lattice:
 * a conclusion <em>holds</em> in an environment E iff E contains a subset (or is equal
 * to) one of the environments in the conclusion's {@link Label}.</p>
 *
 * <p>Assumption ids are the base fact atom-keys recorded by
 * {@link ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine} (e.g.
 * {@code "edge(a, b)"}). Environments are compared structurally (sorted-set equality)
 * so that two independently built environments with the same assumption ids are equal.</p>
 *
 * <p>Subsumption: {@code this.subsumes(other)} iff {@code this ⊆ other}. A smaller
 * environment is strictly stronger — it derives the same conclusion from fewer assumptions.</p>
 *
 * @see Label
 * @see NogoodStore
 * @see Atms
 */
public final class Environment {

    /** The empty environment: the conclusion holds under no assumptions (tautology). */
    public static final Environment EMPTY = new Environment(Collections.emptySortedSet());

    private final SortedSet<String> assumptions; // immutable view

    /**
     * Construct an environment from a collection of assumption ids.
     *
     * @param assumptions the assumption ids; duplicates are silently ignored
     */
    public Environment(Collection<String> assumptions) {
        Objects.requireNonNull(assumptions, "assumptions must not be null");
        SortedSet<String> sorted = new TreeSet<>();
        for (String a : assumptions) {
            Objects.requireNonNull(a, "assumption id must not be null");
            sorted.add(a);
        }
        this.assumptions = Collections.unmodifiableSortedSet(sorted);
    }

    /** Private copy constructor from an already-sorted set. */
    private Environment(SortedSet<String> sorted) {
        this.assumptions = Collections.unmodifiableSortedSet(sorted);
    }

    /**
     * Return an environment containing the single given assumption id.
     *
     * @param assumptionId the assumption id; must not be null
     * @return singleton environment
     */
    public static Environment singleton(String assumptionId) {
        Objects.requireNonNull(assumptionId, "assumptionId must not be null");
        SortedSet<String> s = new TreeSet<>();
        s.add(assumptionId);
        return new Environment(s);
    }

    /**
     * Return an environment that is the union of this environment and another.
     *
     * <p>Used by ADD-JUSTIFICATION to combine antecedent environments: if antecedent A
     * holds in environment E1 and antecedent B holds in E2, then the conjunction holds
     * in E1 ∪ E2.</p>
     *
     * @param other the other environment; must not be null
     * @return a new environment whose assumption set is the union of both
     */
    public Environment union(Environment other) {
        Objects.requireNonNull(other, "other must not be null");
        if (this.assumptions.isEmpty()) return other;
        if (other.assumptions.isEmpty()) return this;
        SortedSet<String> merged = new TreeSet<>(this.assumptions);
        merged.addAll(other.assumptions);
        return new Environment(merged);
    }

    /**
     * Test whether this environment subsumes (is a subset of) another.
     *
     * <p>{@code this.subsumes(other)} means {@code this ⊆ other}: every assumption in
     * {@code this} is also in {@code other}. A smaller environment is more general —
     * it requires fewer assumptions to support a conclusion, so it dominates larger ones
     * in the Label antichain.</p>
     *
     * @param other the candidate superset; must not be null
     * @return {@code true} if every assumption in {@code this} is present in {@code other}
     */
    public boolean subsumes(Environment other) {
        Objects.requireNonNull(other, "other must not be null");
        return other.assumptions.containsAll(this.assumptions);
    }

    /**
     * Test whether the given set of believed assumptions is a superset of this environment,
     * i.e., whether a conclusion holding in this environment holds under the given context.
     *
     * @param believedAssumptions the currently believed assumption ids
     * @return {@code true} if {@code believedAssumptions ⊇ this.assumptions}
     */
    public boolean isSupported(Set<String> believedAssumptions) {
        Objects.requireNonNull(believedAssumptions, "believedAssumptions must not be null");
        return believedAssumptions.containsAll(this.assumptions);
    }

    /**
     * Test whether this environment contains the given assumption id.
     *
     * @param assumptionId the assumption to test
     * @return {@code true} if the assumption is in this environment
     */
    public boolean contains(String assumptionId) {
        return this.assumptions.contains(assumptionId);
    }

    /** Return the (immutable, sorted) set of assumption ids. */
    public SortedSet<String> assumptions() {
        return assumptions;
    }

    /** Number of assumptions in this environment. */
    public int size() {
        return assumptions.size();
    }

    /** Whether this environment is empty (i.e., the tautological environment). */
    public boolean isEmpty() {
        return assumptions.isEmpty();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Environment e)) return false;
        return Objects.equals(assumptions, e.assumptions);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(assumptions);
    }

    @Override
    public String toString() {
        return "Env" + assumptions;
    }
}
