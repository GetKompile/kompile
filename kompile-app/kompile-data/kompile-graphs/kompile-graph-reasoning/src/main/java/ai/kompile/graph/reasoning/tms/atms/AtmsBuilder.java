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

import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.Derivation;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.FixpointResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds an {@link Atms} from a {@link FixpointResult} produced by
 * {@link RecursiveQueryEngine}.
 *
 * <h2>Mapping</h2>
 * <table>
 *   <tr><th>Datalog concept</th><th>ATMS concept</th></tr>
 *   <tr><td>EDB fact (base ground atom)</td><td>Assumption registered via {@link Atms#addAssumption}</td></tr>
 *   <tr><td>{@link Derivation} (consequent, parentAtomKeys, ruleDisplay)</td>
 *       <td>Justification via {@link Atms#addJustification}</td></tr>
 * </table>
 *
 * <p>After the build, {@code atms.label(derivedAtomKey)} returns the set of minimal
 * assumption environments for every derived atom — exactly the minimal EDB subsets
 * sufficient to derive that atom.</p>
 *
 * <h2>Completeness bound</h2>
 * <p>The ATMS label for a derived atom reflects only the derivations recorded in the
 * fixpoint result's {@link FixpointResult#derivationIndex()}. The engine caps derivations
 * per atom at {@link RecursiveQueryEngine#DEFAULT_MAX_DERIVATIONS_PER_ATOM} (default 4).
 * If a program has more than 4 distinct minimal derivations for an atom (e.g. a diamond
 * with 5 parallel paths), some environments may be missing from the label. Callers that
 * require complete labels should pass a higher {@code maxDerivationsPerAtom} to
 * {@link RecursiveQueryEngine#evaluate(List, RecursiveQueryEngine.EdbProvider, int, int, int)}
 * — the recommendation is {@code maxDerivationsPerAtom ≥ expected_alternative_proofs}.</p>
 *
 * <p>Similarly, the ATMS itself caps labels at {@link Atms#DEFAULT_MAX_ENVIRONMENTS_PER_LABEL}
 * environments per node. Raise this cap in the {@link Atms} constructor when expecting
 * many parallel proofs.</p>
 *
 * @see Atms
 * @see RecursiveQueryEngine
 */
public final class AtmsBuilder {

    private AtmsBuilder() {}

    /**
     * Build an {@link Atms} with default caps from a fixpoint result.
     *
     * @param fixpoint    the fixpoint evaluation result; must not be null
     * @param edbFactKeys the atom keys of EDB (base) facts to register as assumptions;
     *                    these are the leaf nodes in the derivation graph — typically
     *                    all keys that appear as {@link Derivation#parentAtomKeys()} in
     *                    the derivation index but do not themselves have a recorded derivation
     * @return a populated {@link Atms} with all EDB facts as assumptions and all recorded
     *         derivations as justifications
     */
    public static Atms fromFixpoint(FixpointResult fixpoint, Collection<String> edbFactKeys) {
        return fromFixpoint(fixpoint, edbFactKeys,
                Atms.DEFAULT_MAX_ENVIRONMENTS_PER_LABEL);
    }

    /**
     * Build an {@link Atms} with a custom environments-per-label cap from a fixpoint result.
     *
     * @param fixpoint                the fixpoint evaluation result; must not be null
     * @param edbFactKeys             the EDB base fact atom keys to register as assumptions;
     *                                must not be null
     * @param maxEnvironmentsPerLabel the label size cap forwarded to the {@link Atms} engine;
     *                                must be &ge; 1
     * @return a populated {@link Atms}
     */
    public static Atms fromFixpoint(FixpointResult fixpoint,
                                     Collection<String> edbFactKeys,
                                     int maxEnvironmentsPerLabel) {
        Objects.requireNonNull(fixpoint, "fixpoint must not be null");
        Objects.requireNonNull(edbFactKeys, "edbFactKeys must not be null");

        Atms atms = new Atms(maxEnvironmentsPerLabel);

        // Step 1: Register all EDB fact keys as assumptions.
        for (String edbKey : edbFactKeys) {
            Objects.requireNonNull(edbKey, "edbFactKey must not be null");
            atms.addAssumption(edbKey);
        }

        // Step 2: Also register any EDB parent keys found in the derivation index that
        // weren't in the supplied edbFactKeys set (defensive — callers may not enumerate
        // every EDB atom). A key in parentAtomKeys that has no recorded derivation of its
        // own is an EDB atom by structural definition.
        Map<String, List<Derivation>> derivationIndex = fixpoint.derivationIndex();
        for (Map.Entry<String, List<Derivation>> entry : derivationIndex.entrySet()) {
            for (Derivation d : entry.getValue()) {
                for (String parentKey : d.parentAtomKeys()) {
                    if (!derivationIndex.containsKey(parentKey)) {
                        // parentKey has no derivation → it is an EDB atom
                        atms.addAssumption(parentKey);
                    }
                }
            }
        }

        // Step 3: Register each recorded derivation as a justification.
        // Ordering: we process derivations in the order they appear in the derivation index.
        // Because the fixpoint is a bottom-up result, antecedent atoms are naturally
        // registered (as assumptions or from prior derivations) before their consequents
        // need them — but the ATMS engine handles out-of-order justifications gracefully
        // via its propagation worklist, so ordering is not a correctness requirement here.
        for (Map.Entry<String, List<Derivation>> entry : derivationIndex.entrySet()) {
            String consequent = entry.getKey();
            for (Derivation d : entry.getValue()) {
                atms.addJustification(consequent, d.parentAtomKeys(), d.ruleDisplay());
            }
        }

        return atms;
    }
}
