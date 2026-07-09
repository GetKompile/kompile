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
 * The counting semiring: {@code (Long, +, ×, 0L, 1L)} with a saturation cap.
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>{@link #plus(Long, Long)} = {@code min(a + b, cap)} — count of distinct derivation
 *       paths that conclude the same ground atom (union of proof sets); saturates at {@link #cap()}
 *       to avoid overflow on large derivation graphs.</li>
 *   <li>{@link #times(Long, Long)} = {@code min(a * b, cap)} — product of path counts along
 *       one derivation chain (Cartesian product of sub-proofs).</li>
 *   <li>{@link #zero()} = {@code 0L} — no derivations.</li>
 *   <li>{@link #one()} = {@code 1L} — one EDB fact (exactly one way to be a base fact).</li>
 * </ul>
 *
 * <p>The count corresponds to the number of <em>distinct</em> proof trees for the atom.
 * For a diamond graph (two 2-hop routes from {@code a} to {@code d}), the count is
 * {@code 2}, which is the corroboration signal used to distinguish atoms supported by
 * multiple independent evidence chains from atoms supported by a single chain.</p>
 *
 * <h2>Interaction with the derivation cap</h2>
 * <p>The {@link ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine} caps the
 * number of stored derivations per atom at
 * {@link ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine#DEFAULT_MAX_DERIVATIONS_PER_ATOM}
 * (default 4).  The counting semiring computes its annotation from the <em>recorded</em>
 * derivations only, so the count is a lower bound when more derivations were dropped.
 * Raise {@code maxDerivationsPerAtom} to get exact counts for atoms with many proofs.</p>
 *
 * @see Semiring
 * @see ViterbiSemiring
 */
public final class CountingSemiring implements Semiring<Long> {

    /** Default saturation cap: {@value} distinct derivations before saturation. */
    public static final long DEFAULT_CAP = 1_000_000L;

    /** Singleton using the default cap. */
    public static final CountingSemiring INSTANCE = new CountingSemiring(DEFAULT_CAP);

    private final long cap;

    /**
     * Construct a counting semiring with the given saturation cap.
     *
     * @param cap maximum count value; must be ≥ 1
     */
    public CountingSemiring(long cap) {
        if (cap < 1L) throw new IllegalArgumentException("cap must be ≥ 1, got: " + cap);
        this.cap = cap;
    }

    /** The saturation cap for this semiring instance. */
    public long cap() {
        return cap;
    }

    @Override
    public Long zero() {
        return 0L;
    }

    @Override
    public Long one() {
        return 1L;
    }

    /**
     * Union of proof counts: {@code min(a + b, cap)}.
     *
     * @param a count from the first derivation path
     * @param b count from the second derivation path
     * @return saturating sum
     */
    @Override
    public Long plus(Long a, Long b) {
        long sum = a + b;
        // Guard against long overflow before comparing to cap
        if (sum < 0 || sum > cap) return cap;
        return sum;
    }

    /**
     * Cartesian product of proof counts: {@code min(a * b, cap)}.
     *
     * @param a count from the first sub-derivation
     * @param b count from the second sub-derivation
     * @return saturating product
     */
    @Override
    public Long times(Long a, Long b) {
        if (a == 0L || b == 0L) return 0L;
        // Safe overflow check: if a > cap/b, product exceeds cap
        if (a > cap / b) return cap;
        long product = a * b;
        return Math.min(product, cap);
    }

    /**
     * Addition is NOT idempotent for counting ({@code 1 + 1 = 2 ≠ 1}).
     *
     * @return {@code false}
     */
    @Override
    public boolean isAbsorptive() {
        return false;
    }

    @Override
    public String toString() {
        return "CountingSemiring(+, ×, 0L, 1L, cap=" + cap + ")";
    }
}
