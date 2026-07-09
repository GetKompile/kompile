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
 * The top-k proofs semiring: {@code (ProofSet, merge-truncate, cross-truncate, ∅, {ε})}.
 *
 * <p>Inspired by the Scallop probabilistic logic programming system (Huang et al., 2021),
 * which uses top-k proofs as an approximation to the sum-of-products provenance
 * polynomial. This semiring computes the {@code k} highest-scoring proof trees for each
 * derived fact.</p>
 *
 * <h2>Algebra</h2>
 * <ul>
 *   <li>{@link #zero()} = empty {@link ProofSet} — no derivations.</li>
 *   <li>{@link #one()} = singleton {@link ProofSet} containing the empty proof (score=1.0,
 *       no leaves, no rules) — the multiplicative identity that does not affect cross-products.</li>
 *   <li>{@link #plus(ProofSet, ProofSet)} = merge the two sets, deduplicate by leaf-set
 *       (keep higher-scoring), sort descending, truncate to {@code k}.</li>
 *   <li>{@link #times(ProofSet, ProofSet)} = cross-product: for each pair (p1, p2),
 *       produce a combined proof with score = p1.score × p2.score, leaves = union,
 *       rules = concat; deduplicate by leaf-set; truncate to {@code k}.</li>
 * </ul>
 *
 * <h2>Absorptive property</h2>
 * <p>The semiring is absorptive by bounded construction: once a proof set reaches {@code k}
 * elements, adding a worse proof has no effect (it is dropped). Therefore
 * {@code plus(ps, ps) == ps} always, and the annotation fixpoint terminates.</p>
 *
 * <h2>Interaction with the derivation cap</h2>
 * <p>The {@link ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine} stores at
 * most {@code maxDerivationsPerAtom} derivations per atom (default 4). When using the
 * {@code TopKProofsSemiring} with {@code k > 4}, raise
 * {@code maxDerivationsPerAtom ≥ k} via the full-parameter {@code evaluate} overload to
 * ensure enough derivations are recorded for the semiring to select from.</p>
 *
 * @see Semiring
 * @see ProofSet
 * @see Proof
 */
public final class TopKProofsSemiring implements Semiring<ProofSet> {

    /** Default number of proofs to retain per atom. */
    public static final int DEFAULT_K = 4;

    private static final TopKProofsSemiring DEFAULT_INSTANCE = new TopKProofsSemiring(DEFAULT_K);

    private final int k;

    // The multiplicative identity: singleton proof set containing one proof with score=1.0
    // and no leaves/rules. Crossing with this has no effect.
    private final ProofSet ONE;

    /**
     * Construct a top-k proofs semiring that keeps the {@code k} best proofs.
     *
     * @param k maximum proofs to retain; must be ≥ 1
     */
    public TopKProofsSemiring(int k) {
        if (k < 1) throw new IllegalArgumentException("k must be ≥ 1, got: " + k);
        this.k = k;
        this.ONE = ProofSet.singleton(new Proof(1.0, java.util.List.of(), java.util.List.of()), k);
    }

    /** Return the singleton instance using the default k ({@value #DEFAULT_K}). */
    public static TopKProofsSemiring defaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /** The {@code k} value: maximum proofs retained per atom. */
    public int k() {
        return k;
    }

    @Override
    public ProofSet zero() {
        return ProofSet.EMPTY;
    }

    @Override
    public ProofSet one() {
        return ONE;
    }

    /**
     * Merge two proof sets: union, deduplicate by leaf-set, sort descending, truncate to k.
     *
     * @param a proof set from the first derivation path
     * @param b proof set from the second derivation path
     * @return merged set of at most k proofs
     */
    @Override
    public ProofSet plus(ProofSet a, ProofSet b) {
        return a.merge(b, k);
    }

    /**
     * Cross-product of two proof sets: every pair (p1 ∈ a, p2 ∈ b) produces one proof
     * with score = p1.score × p2.score, leaves = union(p1.leaves, p2.leaves),
     * rules = concat(p1.rules, p2.rules).  Result is deduplicated and truncated to k.
     *
     * @param a proof set from the first conjunct
     * @param b proof set from the second conjunct
     * @return cross-product set of at most k proofs
     */
    @Override
    public ProofSet times(ProofSet a, ProofSet b) {
        return a.cross(b, k);
    }

    /**
     * Absorptive by bounded construction: once a proof set reaches k proofs and a
     * duplicate set is merged in, the result is identical.  {@code plus(ps, ps) == ps}.
     *
     * @return {@code true}
     */
    @Override
    public boolean isAbsorptive() {
        return true;
    }

    @Override
    public String toString() {
        return "TopKProofsSemiring(k=" + k + ")";
    }
}
