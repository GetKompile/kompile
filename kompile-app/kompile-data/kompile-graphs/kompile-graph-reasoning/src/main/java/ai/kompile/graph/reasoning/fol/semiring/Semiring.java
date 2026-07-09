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
package ai.kompile.graph.reasoning.fol.semiring;

/**
 * Algebraic semiring over provenance annotations for Datalog facts.
 *
 * <p>A semiring {@code (K, ⊕, ⊗, 0, 1)} provides:</p>
 * <ul>
 *   <li>{@link #zero()} — the additive identity (annotation for "no derivation")</li>
 *   <li>{@link #one()} — the multiplicative identity (annotation for an axiom / EDB fact
 *       with full weight, e.g. counting annotation 1 for a base fact)</li>
 *   <li>{@link #plus(Object, Object)} — combining two annotations for the <em>same</em>
 *       derived fact reached via <em>different</em> derivations (proof union / aggregation)</li>
 *   <li>{@link #times(Object, Object)} — combining annotations along a <em>single</em>
 *       derivation chain (rule firing / proof product)</li>
 * </ul>
 *
 * <p>This algebraic structure is exactly the provenance semiring framework introduced by</p>
 * <blockquote>
 *   T. J. Green, G. Karvounarakis, and V. Tannen,<br>
 *   "Provenance Semirings," <em>Proceedings of the 26th ACM SIGACT-SIGMOD-SIGART Symposium
 *   on Principles of Database Systems (PODS)</em>, 2007, pp. 31–40.
 * </blockquote>
 * <p>Instances packaged here ({@link ViterbiSemiring}, {@link CountingSemiring},
 * {@link TopKProofsSemiring}) correspond to the «tropical», «counting», and
 * «top-k» semirings respectively, matching the Scallop PLP system (Huang et al., 2021)
 * for top-k.</p>
 *
 * <p>The annotation fixpoint in
 * {@link ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine#evaluateAnnotated}
 * terminates for absorptive semirings ({@link #isAbsorptive()} returns {@code true}).
 * For the Viterbi semiring, the fixpoint iterates until annotations change by less than
 * {@code 1e-12} or a round cap is hit.</p>
 *
 * @param <K> the annotation type (e.g. {@link Double}, {@link Long}, {@link ProofSet})
 * @see ViterbiSemiring
 * @see CountingSemiring
 * @see TopKProofsSemiring
 */
public interface Semiring<K> {

    /**
     * The additive identity: {@code plus(zero(), k) == k} for all {@code k}.
     *
     * <p>Represents the annotation of a fact with <em>no</em> known derivation.</p>
     *
     * @return the additive identity element
     */
    K zero();

    /**
     * The multiplicative identity: {@code times(one(), k) == k} for all {@code k}.
     *
     * <p>Represents the annotation of an axiom (base EDB fact) with no composition cost.</p>
     *
     * @return the multiplicative identity element
     */
    K one();

    /**
     * Add two annotations: combine the evidence from two independent derivation paths
     * that both conclude the same ground atom.
     *
     * <p>Must be commutative, associative, with {@link #zero()} as identity.</p>
     *
     * @param a first annotation
     * @param b second annotation
     * @return the combined annotation
     */
    K plus(K a, K b);

    /**
     * Multiply two annotations: compose along a single derivation step (rule firing).
     *
     * <p>Must be associative, with {@link #one()} as identity, and must distribute over
     * {@link #plus}. {@link #zero()} must be an annihilator: {@code times(zero(), k) == zero()}.</p>
     *
     * @param a annotation of the first conjunct (or running product along the chain so far)
     * @param b annotation of the second conjunct
     * @return the composed annotation
     */
    K times(K a, K b);

    /**
     * Whether this semiring is absorptive (idempotent addition):
     * {@code plus(a, a) == a} for all {@code a}.
     *
     * <p>Absorptive semirings guarantee that the annotation fixpoint converges in a finite
     * number of rounds over a finite derivation graph.  For non-absorptive semirings (e.g.
     * counting), the fixpoint still terminates when the underlying crisp derivation graph
     * is acyclic or when cycle annotations remain bounded.</p>
     *
     * @return {@code true} if {@code plus} is idempotent
     */
    default boolean isAbsorptive() {
        return false;
    }
}
