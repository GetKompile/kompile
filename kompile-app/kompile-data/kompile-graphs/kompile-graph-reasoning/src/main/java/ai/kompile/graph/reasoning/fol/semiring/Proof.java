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

import java.util.List;
import java.util.Objects;

/**
 * A single proof tree record for a derived Datalog atom, carrying its overall
 * confidence score, the leaf EDB fact keys that ground it, and the rules that fired.
 *
 * <p>Used as the element type inside a {@link ProofSet} (the annotation type of the
 * {@link TopKProofsSemiring}).  Proofs are compared by {@link #score()} descending —
 * higher score is better.</p>
 *
 * <p>The <em>leaf fact keys</em> ({@link #leafFactKeys()}) form the "footprint" of the
 * proof: two proofs with the same leaf-set are considered <em>logically equivalent</em>
 * (they share the same EDB evidence) and are deduplicated by the semiring's
 * {@code times} operation.</p>
 *
 * @param score        overall confidence of this proof (product of EDB values along the chain);
 *                     must be in [0, 1]
 * @param leafFactKeys sorted, deduplicated EDB atom keys used at the leaves of this proof tree
 * @param ruleDisplays ordered list of rule display strings that fired in this proof
 */
public record Proof(double score, List<String> leafFactKeys, List<String> ruleDisplays)
        implements Comparable<Proof> {

    /** Construct and validate; makes defensive copies of list arguments. */
    public Proof {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Proof score must be in [0,1], got: " + score);
        }
        leafFactKeys = (leafFactKeys == null) ? List.of() : List.copyOf(leafFactKeys);
        ruleDisplays = (ruleDisplays == null) ? List.of() : List.copyOf(ruleDisplays);
    }

    /**
     * Factory for a leaf (EDB) proof: single atom, no rules fired.
     *
     * @param atomKey the EDB atom key
     * @param score   its truth value
     * @return singleton proof
     */
    public static Proof leaf(String atomKey, double score) {
        return new Proof(score, List.of(atomKey), List.of());
    }

    /**
     * Ordering: highest score first (descending).  Stable tie-break by leaf-set
     * string representation to ensure deterministic ordering in tests.
     */
    @Override
    public int compareTo(Proof other) {
        int cmp = Double.compare(other.score, this.score); // descending
        if (cmp != 0) return cmp;
        return this.leafFactKeys.toString().compareTo(other.leafFactKeys.toString());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Proof p)) return false;
        return Double.compare(p.score, score) == 0
                && Objects.equals(p.leafFactKeys, leafFactKeys)
                && Objects.equals(p.ruleDisplays, ruleDisplays);
    }

    @Override
    public int hashCode() {
        return Objects.hash(score, leafFactKeys, ruleDisplays);
    }

    @Override
    public String toString() {
        return "Proof{score=" + score + ", leaves=" + leafFactKeys + ", rules=" + ruleDisplays + "}";
    }
}
