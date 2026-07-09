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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An antichain of {@link Environment}s representing all <em>minimal</em> support sets
 * for a derived node.
 *
 * <p>Following de Kleer (1986) §4, a label is a set of environments such that no
 * environment in the set subsumes (is a subset of) another. This minimality invariant
 * is the core of the ATMS data structure: if two environments E1 ⊆ E2 both support a
 * conclusion, only E1 is informative — E2 would be redundant. The antichain therefore
 * represents exactly the set of <em>minimal</em> support sets.</p>
 *
 * <h2>Mutable-builder vs immutable-return</h2>
 * <p>This class uses an <strong>immutable-return</strong> design: {@link #add(Environment)}
 * returns a new {@code Label} with the environment incorporated (maintaining minimality)
 * and leaves the receiver unchanged. This makes labels safe to share across the
 * propagation worklist without defensive copies.</p>
 *
 * <h2>Complexity</h2>
 * <p>Adding one environment to a label of size N is O(N²) in the worst case (check
 * whether the new environment is subsumed, then drop supersets). For realistic ATMS
 * labels (tens of environments, bounded by {@link Atms#DEFAULT_MAX_ENVIRONMENTS_PER_LABEL})
 * this is negligible.</p>
 *
 * @see Environment
 * @see NogoodStore
 * @see Atms
 */
public final class Label {

    /** The empty label: the node has no support (it is unknown / not derivable). */
    public static final Label EMPTY = new Label(List.of());

    // The antichain, stored as an unmodifiable snapshot.
    // Environments are sorted by ascending size to make subsumption checks early-exit faster,
    // but the order is otherwise an implementation detail.
    private final List<Environment> envs;

    private Label(List<Environment> envs) {
        this.envs = Collections.unmodifiableList(envs);
    }

    /**
     * Construct a label from an initial collection of environments, maintaining the
     * antichain minimality invariant.
     *
     * @param initial candidate environments; may contain supersets or duplicates, all
     *                of which are removed during construction
     */
    public static Label of(Collection<Environment> initial) {
        Objects.requireNonNull(initial, "initial must not be null");
        Label result = EMPTY;
        for (Environment e : initial) {
            result = result.add(e);
        }
        return result;
    }

    /**
     * Return a label containing the single given environment.
     *
     * @param env the sole environment; must not be null
     * @return singleton label
     */
    public static Label singleton(Environment env) {
        Objects.requireNonNull(env, "env must not be null");
        return new Label(List.of(env));
    }

    /**
     * Return a new label that additionally contains {@code env}, maintaining the antichain
     * minimality invariant.
     *
     * <p>Algorithm (from de Kleer 1986 ADD-LABEL procedure):</p>
     * <ol>
     *   <li>If any existing environment in this label subsumes {@code env} (i.e., is a
     *       subset of {@code env}), then {@code env} is redundant — return {@code this}
     *       unchanged.</li>
     *   <li>Otherwise, remove every existing environment that {@code env} subsumes (i.e.,
     *       every environment that is a superset of {@code env}), then add {@code env}.</li>
     * </ol>
     *
     * @param env the environment to add; must not be null
     * @return a new label incorporating {@code env} with minimality maintained;
     *         returns {@code this} if {@code env} is subsumed by an existing entry
     */
    public Label add(Environment env) {
        Objects.requireNonNull(env, "env must not be null");

        // Step 1: check whether any existing environment subsumed env (makes env redundant).
        for (Environment existing : envs) {
            if (existing.subsumes(env)) {
                // existing ⊆ env — env is already covered, do nothing
                return this;
            }
        }

        // Step 2: build the new list, dropping any existing environment that env subsumes.
        List<Environment> next = new ArrayList<>(envs.size() + 1);
        for (Environment existing : envs) {
            if (!env.subsumes(existing)) {
                // keep: env does NOT subsume existing (existing is not a superset of env)
                next.add(existing);
            }
            // drop: env ⊆ existing — existing is a superset, env is strictly stronger
        }
        next.add(env);
        return new Label(next);
    }

    /**
     * Return a new label that is the union (merge) of this label and another, maintaining
     * the antichain minimality invariant.
     *
     * <p>Used during propagation when a node acquires environments from multiple
     * justifications: the merged label represents all minimal supports across both sources.</p>
     *
     * @param other the label to merge with; must not be null
     * @return a new label whose environments form the minimal antichain over the union of
     *         both labels' environments
     */
    public Label merge(Label other) {
        Objects.requireNonNull(other, "other must not be null");
        Label result = this;
        for (Environment e : other.envs) {
            result = result.add(e);
        }
        return result;
    }

    /**
     * Return a new label with all environments that are supersets of any stored nogood
     * removed.
     *
     * <p>Called after {@link NogoodStore#addNogood} to filter the label: an environment
     * that is inconsistent (it contains a nogood) cannot support any valid conclusion.</p>
     *
     * @param nogoods the nogood store to filter against; must not be null
     * @return a new label containing only the consistent environments; may be
     *         {@link #EMPTY} if all environments became inconsistent
     */
    public Label filterNogoods(NogoodStore nogoods) {
        Objects.requireNonNull(nogoods, "nogoods must not be null");
        List<Environment> consistent = new ArrayList<>(envs.size());
        for (Environment e : envs) {
            if (!nogoods.isNogood(e)) {
                consistent.add(e);
            }
        }
        if (consistent.size() == envs.size()) return this;
        return new Label(consistent);
    }

    /**
     * Test whether this label supports a conclusion under the given set of believed assumptions.
     *
     * <p>A conclusion holds in context C iff at least one environment in its label is a
     * subset of C: {@code ∃E ∈ label: E ⊆ C}.</p>
     *
     * @param believedAssumptions the currently active assumption ids
     * @return {@code true} if the conclusion holds in this context
     */
    public boolean holdsIn(Set<String> believedAssumptions) {
        Objects.requireNonNull(believedAssumptions, "believedAssumptions must not be null");
        for (Environment e : envs) {
            if (e.isSupported(believedAssumptions)) return true;
        }
        return false;
    }

    /**
     * Test whether the conclusion survives retraction of the given assumption.
     *
     * <p>The conclusion survives iff at least one environment in the label does NOT
     * contain the retracted assumption. If every environment contains the assumption, then
     * retracting it eliminates all support for the conclusion.</p>
     *
     * @param assumptionId the assumption being retracted
     * @return {@code true} if at least one environment is free of {@code assumptionId}
     */
    public boolean survivesRetraction(String assumptionId) {
        Objects.requireNonNull(assumptionId, "assumptionId must not be null");
        for (Environment e : envs) {
            if (!e.contains(assumptionId)) return true;
        }
        return false;
    }

    /** Whether this label is empty (the node has no support). */
    public boolean isEmpty() {
        return envs.isEmpty();
    }

    /** Number of environments in the antichain. */
    public int size() {
        return envs.size();
    }

    /**
     * Return the environments in this label (unmodifiable, order is implementation-defined).
     *
     * @return unmodifiable list of environments
     */
    public List<Environment> environments() {
        return envs;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Label l)) return false;
        // Two labels are equal if they contain the same set of environments (order-independent).
        // We rely on antichain minimality: if both are antichains with the same elements they
        // are equal regardless of list order.
        if (envs.size() != l.envs.size()) return false;
        return envs.containsAll(l.envs);
    }

    @Override
    public int hashCode() {
        // Order-independent hash: sum of individual environment hashes
        int h = 0;
        for (Environment e : envs) h += e.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "Label" + envs;
    }
}
