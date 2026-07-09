/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.tms.JustificationIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Counterfactual verdict fragility analysis (E12).
 *
 * <p>Given an atom key and its derivation provenance (recorded in {@link InferredFact}s),
 * {@link #assess} answers two questions:
 * <ol>
 *   <li><b>What is the minimal support set?</b> Walk the provenance chain recursively down
 *       to base {@link FactStore} facts — the leaf set of the recorded derivation.</li>
 *   <li><b>Which facts would flip the verdict if removed?</b> Every leaf fact {@code f}
 *       such that removing {@code f} would leave the atom without support.</li>
 * </ol>
 * The {@link FragilityReport#robustness()} score is a heuristic ordinal in [0, 1]:
 * <ul>
 *   <li>0 — every supporting leaf is load-bearing (|wouldFlipIf| &gt; 0 AND
 *       |wouldFlipIf| == |minimalSupport|): no redundancy at all.</li>
 *   <li>1 — no single leaf is load-bearing (|wouldFlipIf| == 0): the verdict is redundantly
 *       supported.</li>
 *   <li>Intermediate — {@code 1 − |wouldFlipIf| / max(1, |minimalSupport|)}: the share of
 *       non-load-bearing leaves.</li>
 * </ul>
 *
 * <p><b>Important:</b> robustness is a heuristic proxy for fragility, not a probability.
 * It ranks verdicts by how many of their leaf supports are load-bearing, but does not
 * model joint retraction or downstream effects beyond the recorded provenance chain.</p>
 *
 * <h3>Two channels for "would flip if"</h3>
 * <ol>
 *   <li><b>JustificationIndex channel (preferred)</b>: if a non-null {@link JustificationIndex}
 *       is provided, a leaf fact {@code f} is load-bearing when the atom key is in
 *       {@link JustificationIndex#solelyDependentOn(String) index.solelyDependentOn(f)}.
 *       This is rule-level precise: it requires that EVERY supporting rule that heads the
 *       atom has {@code f} in its body.</li>
 *   <li><b>Provenance-only approximation (fallback)</b>: when no index is available, a leaf
 *       fact {@code f} is considered load-bearing when it is the <em>only</em> leaf in the
 *       recorded provenance chain. In other words: single-support chains flag their sole leaf;
 *       multi-leaf chains are treated as redundant (robustness = 1). This is conservative —
 *       it under-counts fragility for wide, shallow chains — but avoids false certainty when
 *       no PSL grounding result is available (e.g. pure FOL/Datalog derivations).</li>
 * </ol>
 */
public final class VerdictFragility {

    /** Maximum provenance chain depth to guard against malformed cycles. */
    private static final int MAX_DEPTH = 1000;

    private VerdictFragility() {}

    /**
     * Fragility report for a single inferred atom.
     *
     * @param minimalSupport the leaf base-fact atom keys the derivation rests on
     * @param wouldFlipIf    the subset of {@code minimalSupport} whose retraction
     *                       would leave the atom without support
     * @param robustness     heuristic robustness score in [0, 1]; see class javadoc
     */
    public record FragilityReport(
            List<String> minimalSupport,
            List<String> wouldFlipIf,
            double robustness
    ) {
        public FragilityReport {
            minimalSupport = Collections.unmodifiableList(new ArrayList<>(minimalSupport));
            wouldFlipIf    = Collections.unmodifiableList(new ArrayList<>(wouldFlipIf));
            if (robustness < 0.0 || robustness > 1.0) {
                throw new IllegalArgumentException("robustness must be in [0,1], got " + robustness);
            }
        }
    }

    /**
     * Assess the fragility of the verdict for the given atom key.
     *
     * <p>If no {@link InferredFact} is found in {@code inferredStore} for {@code atomKey},
     * returns a report with empty support, empty wouldFlipIf, and robustness 1.0 (vacuously
     * robust — nothing to retract).</p>
     *
     * @param atomKey       the atom key whose verdict fragility is being assessed
     * @param inferredStore the inferred-fact store holding derivation provenance
     * @param factStore     the base fact store (determines which atoms are base facts / leaves)
     * @param indexOrNull   optional {@link JustificationIndex} for rule-level fragility
     *                      analysis; when null the provenance-only approximation is used
     * @return the fragility report
     */
    public static FragilityReport assess(String atomKey,
                                          InferredFactStore inferredStore,
                                          FactStore factStore,
                                          JustificationIndex indexOrNull) {
        Objects.requireNonNull(atomKey, "atomKey");
        Objects.requireNonNull(inferredStore, "inferredStore");
        Objects.requireNonNull(factStore, "factStore");

        Optional<InferredFact> root = inferredStore.latest(atomKey);
        if (root.isEmpty()) {
            return new FragilityReport(List.of(), List.of(), 1.0);
        }

        // Walk provenance chain to base facts
        Set<String> visited  = new LinkedHashSet<>();
        Set<String> leafSet  = new LinkedHashSet<>();
        walkProvenance(root.get(), inferredStore, factStore, visited, leafSet, 0);

        List<String> minimalSupport = new ArrayList<>(leafSet);

        // Determine which leaves are load-bearing
        List<String> wouldFlipIf = new ArrayList<>();
        if (indexOrNull != null) {
            // JustificationIndex channel: precise rule-level check
            Set<String> solelyDependent = indexOrNull.solelyDependentOn(atomKey);
            for (String leaf : minimalSupport) {
                if (solelyDependent.contains(leaf)) {
                    wouldFlipIf.add(leaf);
                }
            }
        } else {
            // Provenance-only approximation: a leaf is load-bearing iff it is the sole leaf
            if (minimalSupport.size() == 1) {
                wouldFlipIf.addAll(minimalSupport);
            }
            // else: multiple leaves → treat as independently redundant (conservative)
        }

        double robustness = computeRobustness(minimalSupport.size(), wouldFlipIf.size());
        return new FragilityReport(minimalSupport, wouldFlipIf, robustness);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    /**
     * Recursively walk the provenance chain starting from {@code fact}.
     *
     * <p>For each supporting fact key in {@link InferredFact#supportingFactKeys()}:
     * <ul>
     *   <li>If the key exists in the {@link FactStore} and NOT in the inferred store
     *       (i.e., it is a base observed fact), add it to {@code leafSet}.</li>
     *   <li>If the key exists in the inferred store (another derived fact), recurse.</li>
     *   <li>If the key exists in both (unusual but allowed), treat as a base fact (leaf)
     *       — conservatively, since the base fact is the ultimate ground truth.</li>
     *   <li>If the key exists in neither (stale provenance), add it to leaves as an
     *       "orphan" — the chain still records it as a dependency.</li>
     * </ul>
     */
    private static void walkProvenance(InferredFact fact,
                                        InferredFactStore inferredStore,
                                        FactStore factStore,
                                        Set<String> visited,
                                        Set<String> leafSet,
                                        int depth) {
        if (depth > MAX_DEPTH) return; // cycle-guard

        for (String key : fact.supportingFactKeys()) {
            if (!visited.add(key)) {
                // Already visited — cycle detected. The key is a cycle participant:
                // if it is not a base fact and not already in leafSet, record it as a
                // cycle-leaf so the report always has at least one support entry
                // (rather than returning an empty minimalSupport for pure cycles).
                if (!factStore.factFor(key).isPresent() && !leafSet.contains(key)) {
                    leafSet.add(key);
                }
                continue;
            }

            boolean isBaseFact = factStore.factFor(key).isPresent();

            if (isBaseFact) {
                // Base observed fact → leaf
                leafSet.add(key);
            } else {
                // Try to recurse into the inferred store
                Optional<InferredFact> child = inferredStore.latest(key);
                if (child.isPresent()) {
                    walkProvenance(child.get(), inferredStore, factStore, visited, leafSet, depth + 1);
                } else {
                    // Key in neither store — orphan provenance reference → leaf (conservative)
                    leafSet.add(key);
                }
            }
        }

        // If this fact has no supporting keys at all (atom is self-grounded / root),
        // record the atom key itself as its own leaf.
        if (fact.supportingFactKeys().isEmpty()) {
            leafSet.add(fact.atomKey());
        }
    }

    /**
     * Heuristic robustness: clamped to [0, 1].
     *
     * <ul>
     *   <li>0  when |wouldFlipIf| &gt; 0 AND |wouldFlipIf| == |minimalSupport| (fully fragile).</li>
     *   <li>Else {@code 1 − |wouldFlipIf| / max(1, |minimalSupport|)} (partial fragility).</li>
     * </ul>
     */
    private static double computeRobustness(int supportSize, int flipSize) {
        if (flipSize > 0 && flipSize == supportSize) return 0.0;
        if (flipSize == 0) return 1.0;
        double raw = 1.0 - (double) flipSize / Math.max(1, supportSize);
        return Math.max(0.0, Math.min(1.0, raw));
    }
}
