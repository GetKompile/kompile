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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, score-sorted set of {@link Proof}s, truncated to at most {@code k} entries.
 *
 * <p>This is the annotation type {@code K} of the {@link TopKProofsSemiring}.  Proofs are
 * stored in descending score order (highest first).  The set is bounded: once it reaches
 * {@code k} elements, lower-scoring proofs are dropped.</p>
 *
 * <p>Two proofs with the same <em>leaf-fact-key set</em> are considered logically
 * equivalent; only the higher-scoring one is kept during deduplication.</p>
 */
public final class ProofSet {

    /** The empty proof set (annotation for "no derivation"). */
    public static final ProofSet EMPTY = new ProofSet(List.of(), Integer.MAX_VALUE);

    private final List<Proof> proofs; // descending by score, deduplicated by leaf-set
    private final int k;

    /**
     * Construct a proof set from a (possibly unsorted, possibly larger than k) list of proofs.
     * The constructor deduplicates by leaf-set and truncates to {@code k}.
     *
     * @param proofs candidate proofs
     * @param k      maximum number of proofs to retain
     */
    public ProofSet(List<Proof> proofs, int k) {
        this.k = k;
        if (proofs == null || proofs.isEmpty()) {
            this.proofs = List.of();
            return;
        }
        // Sort descending and deduplicate by leaf-set (keep highest-scoring for each leaf-set)
        List<Proof> sorted = new ArrayList<>(proofs);
        Collections.sort(sorted); // Proof.compareTo = descending by score
        List<Proof> deduped = dedup(sorted);
        // Truncate to k
        this.proofs = List.copyOf(deduped.size() > k ? deduped.subList(0, k) : deduped);
    }

    /** Singleton: exactly one proof. */
    public static ProofSet singleton(Proof proof, int k) {
        return new ProofSet(List.of(proof), k);
    }

    /**
     * Merge (union) two proof sets, deduplicating by leaf-set and truncating to the
     * lower of the two ks (the semiring's own k governs, not the ProofSet's).
     *
     * @param other the other set to merge with
     * @param k     max proofs to retain in the result
     * @return merged set truncated to k
     */
    public ProofSet merge(ProofSet other, int k) {
        if (this.proofs.isEmpty()) return other.truncate(k);
        if (other.proofs.isEmpty()) return this.truncate(k);
        List<Proof> combined = new ArrayList<>(this.proofs.size() + other.proofs.size());
        combined.addAll(this.proofs);
        combined.addAll(other.proofs);
        return new ProofSet(combined, k);
    }

    /**
     * Cross-product combine: each proof in {@code this} × each proof in {@code other}
     * produces one combined proof:
     * <ul>
     *   <li>score = {@code p1.score() * p2.score()}</li>
     *   <li>leafFactKeys = union of {@code p1.leafFactKeys()} and {@code p2.leafFactKeys()}</li>
     *   <li>ruleDisplays = concatenation of {@code p1.ruleDisplays()} and {@code p2.ruleDisplays()}</li>
     * </ul>
     *
     * <p>The result is deduplicated by leaf-set and truncated to {@code k}.</p>
     *
     * @param other the proof set to cross-product with
     * @param k     max proofs to retain
     * @return cross-product proof set
     */
    public ProofSet cross(ProofSet other, int k) {
        if (this.proofs.isEmpty() || other.proofs.isEmpty()) return EMPTY;
        List<Proof> products = new ArrayList<>(this.proofs.size() * other.proofs.size());
        for (Proof p1 : this.proofs) {
            for (Proof p2 : other.proofs) {
                double combinedScore = p1.score() * p2.score();
                // Union of leaf keys, sorted and deduplicated
                List<String> leaves = unionSorted(p1.leafFactKeys(), p2.leafFactKeys());
                // Concatenate rule displays
                List<String> rules = new ArrayList<>(p1.ruleDisplays().size() + p2.ruleDisplays().size());
                rules.addAll(p1.ruleDisplays());
                rules.addAll(p2.ruleDisplays());
                products.add(new Proof(Math.max(0.0, Math.min(1.0, combinedScore)),
                        leaves, List.copyOf(rules)));
            }
        }
        return new ProofSet(products, k);
    }

    /** Return the proofs in descending score order (immutable). */
    public List<Proof> proofs() {
        return proofs;
    }

    /** Return the top-{@code n} proofs (or all if fewer than n). */
    public List<Proof> top(int n) {
        if (n <= 0) return List.of();
        return proofs.size() <= n ? proofs : proofs.subList(0, n);
    }

    /** Whether this set is empty (no derivations). */
    public boolean isEmpty() {
        return proofs.isEmpty();
    }

    /** Number of proofs retained. */
    public int size() {
        return proofs.size();
    }

    /** Truncate to at most {@code newK} proofs (returns this if already within limit). */
    ProofSet truncate(int newK) {
        if (proofs.size() <= newK) return this;
        return new ProofSet(proofs.subList(0, newK), newK);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Deduplicate proofs by leaf-set: for two proofs with identical leaf-fact-key sets,
     * keep only the higher-scoring one.  Input must already be sorted descending by score.
     */
    private static List<Proof> dedup(List<Proof> sorted) {
        // Since input is sorted descending by score, first occurrence for each leaf-set wins
        List<Proof> out = new ArrayList<>(sorted.size());
        java.util.Set<List<String>> seen = new java.util.LinkedHashSet<>();
        for (Proof p : sorted) {
            // Normalize the leaf key set to a sorted list for dedup key
            List<String> leafKey = sorted(p.leafFactKeys());
            if (seen.add(leafKey)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Return a sorted copy of a list of strings (for leaf-set dedup key). */
    private static List<String> sorted(List<String> keys) {
        if (keys.isEmpty()) return List.of();
        List<String> copy = new ArrayList<>(keys);
        Collections.sort(copy);
        return copy;
    }

    /** Sorted union of two string lists (for cross-product leaf combination). */
    private static List<String> unionSorted(List<String> a, List<String> b) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>(a);
        set.addAll(b);
        List<String> result = new ArrayList<>(set);
        Collections.sort(result);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ProofSet ps)) return false;
        return Objects.equals(proofs, ps.proofs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proofs);
    }

    @Override
    public String toString() {
        return "ProofSet{k=" + k + ", proofs=" + proofs + "}";
    }
}
