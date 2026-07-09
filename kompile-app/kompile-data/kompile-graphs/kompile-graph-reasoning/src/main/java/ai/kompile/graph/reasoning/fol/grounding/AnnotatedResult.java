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
package ai.kompile.graph.reasoning.fol.grounding;

import ai.kompile.graph.reasoning.fol.semiring.Proof;
import ai.kompile.graph.reasoning.fol.semiring.ProofSet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of a semiring-annotated fixpoint evaluation, combining the underlying
 * {@link RecursiveQueryEngine.FixpointResult} with a per-atom semiring annotation map.
 *
 * <p>Produced by
 * {@link RecursiveQueryEngine#evaluateAnnotated(List, RecursiveQueryEngine.EdbProvider, ai.kompile.graph.reasoning.fol.semiring.Semiring, java.util.function.Function)}.
 * The annotation for each derived atom key is computed by iterating the derivation graph
 * (recorded in the underlying {@link RecursiveQueryEngine.FixpointResult#derivationIndex()})
 * to a fixpoint over the semiring operations.</p>
 *
 * <h2>EDB atoms</h2>
 * <p>EDB atom keys that appear as parents in derivations receive annotations from the
 * caller-supplied {@code edbAnnotator} function.  IDB atoms start at {@code semiring.zero()}
 * and accumulate via the plus/times operations over recorded derivations.</p>
 *
 * <h2>Derivation cap interaction</h2>
 * <p>Annotations are computed from the <em>recorded</em> derivations only (capped at
 * {@link RecursiveQueryEngine#DEFAULT_MAX_DERIVATIONS_PER_ATOM} per atom by default).
 * For {@link ai.kompile.graph.reasoning.fol.semiring.CountingSemiring} this yields a
 * lower bound on the true count; for
 * {@link ai.kompile.graph.reasoning.fol.semiring.TopKProofsSemiring} the cap limits
 * the number of distinct proofs visible to the semiring.
 * Raise {@code maxDerivationsPerAtom} when accurate counts or k &gt; 4 proofs are needed.</p>
 *
 * @param <K> the semiring annotation type
 */
public final class AnnotatedResult<K> {

    private final RecursiveQueryEngine.FixpointResult fixpointResult;
    private final Map<String, K> annotations; // atomKey → semiring annotation
    private final K zero;

    /**
     * Package-private constructor called by
     * {@link RecursiveQueryEngine#evaluateAnnotated}.
     *
     * @param fixpointResult the underlying crisp fixpoint result
     * @param annotations    per-atom-key semiring annotation (IDB + EDB atoms referenced in derivations)
     * @param zero           the semiring zero (returned for unknown atoms)
     */
    AnnotatedResult(RecursiveQueryEngine.FixpointResult fixpointResult,
                    Map<String, K> annotations,
                    K zero) {
        Objects.requireNonNull(fixpointResult, "fixpointResult must not be null");
        Objects.requireNonNull(annotations, "annotations must not be null");
        this.fixpointResult = fixpointResult;
        this.annotations = Collections.unmodifiableMap(new LinkedHashMap<>(annotations));
        this.zero = zero;
    }

    /**
     * The underlying crisp fixpoint result (derived facts, rounds, completion status,
     * derivation index, etc.).
     *
     * @return the underlying {@link RecursiveQueryEngine.FixpointResult}
     */
    public RecursiveQueryEngine.FixpointResult fixpointResult() {
        return fixpointResult;
    }

    /**
     * The semiring annotation for the given atom key, or {@link #zeroAnnotation()} if
     * the atom is unknown (no derivation and no EDB annotation recorded).
     *
     * @param atomKey the canonical atom key (e.g. {@code "path(a, c)"})
     * @return the annotation, never null
     */
    public K annotation(String atomKey) {
        K ann = annotations.get(atomKey);
        return (ann != null) ? ann : zero;
    }

    /**
     * The full annotation map: atom key → semiring value for all atoms that appear in
     * the derivation graph (EDB parents and IDB derived atoms).
     *
     * @return unmodifiable map
     */
    public Map<String, K> allAnnotations() {
        return annotations;
    }

    /**
     * The semiring zero value: the annotation returned for atoms with no derivation.
     *
     * @return the additive identity of the semiring
     */
    public K zeroAnnotation() {
        return zero;
    }

    /**
     * Convenience: return the top-k proofs for the given atom key.
     *
     * <p>Only valid when the annotation type is {@link ProofSet} (i.e. the semiring is a
     * {@link TopKProofsSemiring}).  Calling this on a non-ProofSet annotation type will throw
     * {@link ClassCastException}.</p>
     *
     * @param atomKey the atom key to look up
     * @param k       maximum number of proofs to return
     * @return the top-k proofs, sorted by score descending; empty list if atom is unknown
     * @throws ClassCastException if the annotation type is not {@link ProofSet}
     */
    @SuppressWarnings("unchecked")
    public List<Proof> proofs(String atomKey, int k) {
        K ann = annotations.get(atomKey);
        if (ann == null) return List.of();
        ProofSet ps = (ProofSet) ann;
        return ps.top(k);
    }
}
