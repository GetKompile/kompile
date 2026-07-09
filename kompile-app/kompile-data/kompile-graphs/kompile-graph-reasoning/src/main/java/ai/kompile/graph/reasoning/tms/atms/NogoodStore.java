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

/**
 * Maintains an antichain of <em>nogood</em> environments: sets of assumptions that are
 * jointly inconsistent.
 *
 * <p>Following de Kleer &amp; Williams (AAAI 1987) §2, a nogood is a minimal inconsistent
 * environment. Any environment that is a <em>superset</em> of a stored nogood is itself
 * inconsistent and must be excluded from every {@link Label}. An environment that is a
 * strict <em>subset</em> of a stored nogood is, on its own, consistent — it would only
 * become inconsistent if the remaining assumptions were added.</p>
 *
 * <h2>Antichain invariant</h2>
 * <p>No stored nogood is a subset of another stored nogood. When a new nogood N is added:</p>
 * <ol>
 *   <li>If any existing nogood G ⊆ N, then N is redundant (it implies G already covers N's
 *       superset). Reject N.</li>
 *   <li>Otherwise, remove every existing nogood G ⊇ N (they are now redundant because N
 *       is a more specific — smaller — inconsistency), then store N.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <p>The {@link Atms} engine calls {@link #addNogood} when a contradiction is reported
 * (e.g., two assumptions together derive both a fact and its negation). It then filters
 * all labels via {@link Label#filterNogoods(NogoodStore)}.</p>
 *
 * @see Environment
 * @see Label
 * @see Atms
 */
public final class NogoodStore {

    // The antichain of minimal inconsistent environments.
    private final List<Environment> nogoods = new ArrayList<>();

    /** Construct an empty nogood store. */
    public NogoodStore() {}

    /**
     * Test whether the given environment is nogood (inconsistent).
     *
     * <p>An environment E is inconsistent iff it is a superset of at least one stored
     * nogood G: {@code G ⊆ E}. Equivalently, {@code G.subsumes(E)} is true.</p>
     *
     * @param env the environment to test; must not be null
     * @return {@code true} if {@code env} contains a stored nogood as a subset
     */
    public boolean isNogood(Environment env) {
        Objects.requireNonNull(env, "env must not be null");
        for (Environment g : nogoods) {
            if (g.subsumes(env)) return true; // g ⊆ env → env is inconsistent
        }
        return false;
    }

    /**
     * Add a new nogood environment, maintaining the antichain minimality invariant.
     *
     * <p>If the new nogood is already covered by an existing, smaller nogood, it is silently
     * ignored. Otherwise, all existing nogoods that are supersets of the new one are removed
     * (they become redundant), and the new nogood is stored.</p>
     *
     * @param env the inconsistent environment to record; must not be null
     */
    public void addNogood(Environment env) {
        Objects.requireNonNull(env, "env must not be null");

        // Step 1: check if any existing nogood is already a subset of env
        // (making env redundant).
        for (Environment g : nogoods) {
            if (g.subsumes(env)) {
                // g ⊆ env — env is already covered by the more specific nogood g
                return;
            }
        }

        // Step 2: remove any existing nogood that env subsumes (they become redundant).
        nogoods.removeIf(g -> env.subsumes(g));

        // Step 3: store the new nogood.
        nogoods.add(env);
    }

    /**
     * Add a nogood represented as a collection of assumption ids.
     *
     * @param assumptions the assumption ids forming the inconsistent environment; must not be null
     */
    public void addNogood(Collection<String> assumptions) {
        Objects.requireNonNull(assumptions, "assumptions must not be null");
        addNogood(new Environment(assumptions));
    }

    /**
     * Return the stored minimal nogoods (unmodifiable).
     *
     * @return unmodifiable snapshot of stored nogoods
     */
    public List<Environment> nogoods() {
        return Collections.unmodifiableList(new ArrayList<>(nogoods));
    }

    /** Number of minimal nogood environments stored. */
    public int size() {
        return nogoods.size();
    }

    /** Whether the nogood store is empty (no inconsistencies known). */
    public boolean isEmpty() {
        return nogoods.isEmpty();
    }
}
