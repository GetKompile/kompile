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
package ai.kompile.graph.reasoning.attribution.shapley;

import java.util.Set;

/**
 * Engine-agnostic seam that determines whether a claim holds given a subset of
 * <em>endogenous</em> (player) fact keys.
 *
 * <p>This interface is the Monte-Carlo Shapley attribution seam described in:</p>
 * <ul>
 *   <li>Livshits, Bertossi, Kimelfeld &amp; Sebag (ICDT 2020) — "The Shapley Value of
 *       Tuple Deletions in Query Answers"</li>
 *   <li>Meliou, Gatterbauer, Moore &amp; Suciu (PVLDB 2010) — "The Complexity of
 *       Causality and Responsibility for Query Answers and non-Answers"</li>
 * </ul>
 *
 * <p>The game-theoretic setup: a claim (ground atom) is the "game value", the base
 * facts that are in some proof are the "players". Facts that are exogenous (always
 * present regardless of the coalition) are fixed outside the coalition and must be
 * supplied by the implementation; this interface receives only the endogenous
 * coalition.</p>
 *
 * <p>Implementations must be <strong>deterministic</strong>: the same {@code presentFactKeys}
 * set must always return the same boolean.</p>
 *
 * <p>Implementations are encouraged to memoize results keyed by subset identity —
 * the Monte-Carlo permutation sampler revisits many prefixes of the same permutation
 * across samples.</p>
 *
 * @see DatalogClaimEvaluator
 * @see ShapleyAttribution
 */
@FunctionalInterface
public interface ClaimEvaluator {

    /**
     * Determine whether the claim holds when only the facts in {@code presentFactKeys}
     * are present in the endogenous part of the knowledge base.
     *
     * <p>Exogenous facts (supplied at construction time of the implementing class) are
     * always treated as present regardless of this argument.</p>
     *
     * @param presentFactKeys the set of endogenous fact atom-keys that are <em>currently
     *                        present</em> in the coalition being evaluated; must not be null;
     *                        may be empty (empty coalition)
     * @return {@code true} if the claim atom is derivable (or already present as a base
     *         fact) given {@code exogenous ∪ presentFactKeys}; {@code false} otherwise
     */
    boolean holds(Set<String> presentFactKeys);
}
