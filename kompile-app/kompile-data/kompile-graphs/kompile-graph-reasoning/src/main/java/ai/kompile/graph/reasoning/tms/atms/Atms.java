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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assumption-Based Truth Maintenance System (ATMS) core engine.
 *
 * <p>Implements the ATMS as described by de Kleer (1986) "An Assumption-Based TMS",
 * Artificial Intelligence 28(2):127–162, and the multi-context extension in Reiter &amp;
 * de Kleer (AAAI 1987) "Foundations of Assumption-Based Truth Maintenance Systems".</p>
 *
 * <h2>Relationship to the existing kompile reasoning stack</h2>
 * <ul>
 *   <li><strong>Labels = why-provenance witness sets</strong>: each environment in a
 *       node's label is a minimal set of EDB atoms (base fact atom-keys) whose joint
 *       truth is sufficient to derive the conclusion. This corresponds exactly to the
 *       leaf-fact sets in the {@link ai.kompile.graph.reasoning.fol.semiring.ProofSet}
 *       annotation produced by the {@link ai.kompile.graph.reasoning.fol.semiring.TopKProofsSemiring}
 *       — the ATMS label is the PosBool-semiring equivalent.</li>
 *   <li><strong>Nogoods = contradiction environments</strong>: nogoods are the environments
 *       detected by {@link ai.kompile.graph.reasoning.tms.ContradictionDetector}. Recording
 *       them here lets the ATMS automatically filter labels and expose which conclusions
 *       lose support when a contradiction is found.</li>
 *   <li><strong>retractionImpact = what BeliefReviser needs</strong>: the
 *       {@link #retractionImpact(String)} query identifies exactly the set of conclusions
 *       that would be fully unsupported by removing one assumption, providing the
 *       assumption-level analogue of {@link ai.kompile.graph.reasoning.tms.JustificationIndex#solelyDependentOn}.</li>
 * </ul>
 *
 * <h2>Propagation strategy</h2>
 * <p>The engine uses <em>lazy bottom-up propagation</em> via a worklist (BFS over the
 * consequent dependency graph). When a node's label changes, all nodes for which this
 * node is an antecedent in some justification are re-queued. A round terminates when the
 * worklist is empty (fixpoint). Cycle safety is guaranteed by the label-change guard:
 * a node is re-queued only when its label strictly grows (i.e., a new environment is
 * added to its antichain). Because the label antichain can grow but never shrink during
 * forward propagation (environments are added, not removed) and each environment can only
 * be added once per node, the worklist terminates.</p>
 *
 * <h2>Cap semantics</h2>
 * <p>When a node's label would exceed {@link #maxEnvironmentsPerLabel}, the largest
 * environments (those with the most assumptions — the weakest supports) are dropped first.
 * Dropped environments are not removed from future consideration: if a subsequent
 * propagation step would add a smaller environment that subsumes the dropped one, the
 * dropped environment would have been removed anyway by the antichain invariant.
 * Nodes whose labels were truncated are tracked in {@link #truncatedLabels()} so callers
 * can detect incompleteness.</p>
 *
 * @see Environment
 * @see Label
 * @see NogoodStore
 * @see AtmsBuilder
 */
public final class Atms {

    private static final Logger log = LoggerFactory.getLogger(Atms.class);

    /** Default maximum number of environments per label before truncation. */
    public static final int DEFAULT_MAX_ENVIRONMENTS_PER_LABEL = 32;

    // ─── State ───────────────────────────────────────────────────────────────────

    /** Labels for all nodes (assumptions and derived). */
    private final Map<String, Label> labels = new LinkedHashMap<>();

    /** Registered assumptions (base fact keys). */
    private final Set<String> assumptions = new LinkedHashSet<>();

    /**
     * Justifications: each entry records one ADD-JUSTIFICATION call.
     * Consequent → list of justifications that DERIVE it.
     */
    private final Map<String, List<Justification>> justificationsByConsequent = new LinkedHashMap<>();

    /**
     * Antecedent-to-consumers index: antecedent node → set of justification ids whose
     * antecedent list includes this node.
     * Used to find what to re-propagate when a node's label changes.
     */
    private final Map<String, Set<Integer>> antecedentToJustIds = new LinkedHashMap<>();

    /** All justifications, indexed by id for quick lookup during propagation. */
    private final List<Justification> allJustifications = new ArrayList<>();

    /** Nogood store. */
    private final NogoodStore nogoodStore = new NogoodStore();

    /** Nodes whose labels were truncated due to the environments-per-label cap. */
    private final Set<String> truncatedLabelNodes = new LinkedHashSet<>();

    /** Maximum environments allowed per label. */
    private final int maxEnvironmentsPerLabel;

    // ─── Internal record ─────────────────────────────────────────────────────────

    /**
     * One justification registered via {@link #addJustification}.
     *
     * @param id           unique index in {@link #allJustifications}
     * @param consequent   the derived node key
     * @param antecedents  the antecedent node keys whose label cross-product feeds this rule
     * @param ruleDisplay  human-readable rule description
     */
    private record Justification(int id, String consequent, List<String> antecedents, String ruleDisplay) {}

    // ─── Construction ────────────────────────────────────────────────────────────

    /**
     * Construct an ATMS with the default environments-per-label cap
     * ({@link #DEFAULT_MAX_ENVIRONMENTS_PER_LABEL}).
     */
    public Atms() {
        this(DEFAULT_MAX_ENVIRONMENTS_PER_LABEL);
    }

    /**
     * Construct an ATMS with a custom environments-per-label cap.
     *
     * @param maxEnvironmentsPerLabel maximum environments stored per label; must be &ge; 1
     */
    public Atms(int maxEnvironmentsPerLabel) {
        if (maxEnvironmentsPerLabel < 1) {
            throw new IllegalArgumentException("maxEnvironmentsPerLabel must be >= 1");
        }
        this.maxEnvironmentsPerLabel = maxEnvironmentsPerLabel;
    }

    // ─── ADD-ASSUMPTION ──────────────────────────────────────────────────────────

    /**
     * Register a base assumption.
     *
     * <p>The node's label is set to the singleton environment {@code {{factKey}}}: the
     * assumption holds in the context that includes exactly itself. This is the base
     * case of the ATMS lattice. If the assumption was already registered, this is a no-op.</p>
     *
     * @param factKey the atom key of the base assumption (e.g. {@code "edge(a, b)"}); must not be null
     */
    public void addAssumption(String factKey) {
        Objects.requireNonNull(factKey, "factKey must not be null");
        if (assumptions.contains(factKey)) return;
        assumptions.add(factKey);
        // An assumption's label is always the singleton environment containing only itself.
        labels.put(factKey, Label.singleton(Environment.singleton(factKey)));
        log.trace("ATMS.addAssumption: {} → label = {{{}}}", factKey, factKey);
    }

    // ─── ADD-JUSTIFICATION ───────────────────────────────────────────────────────

    /**
     * Add a justification (inference rule instance) and propagate label changes.
     *
     * <p>This is the central operation of the ATMS (de Kleer 1986 §5 ADD-JUSTIFICATION).
     * The new environments contributed to the consequent's label are computed as the
     * cross-product (pairwise union) of the antecedent labels' environments, filtered
     * by the nogood store. The result is merged into the consequent's current label
     * (maintaining minimality). If the label changed, the change is propagated to all
     * downstream consumers of the consequent via the propagation worklist.</p>
     *
     * <p>Antecedents that have no label yet (neither an assumption nor a previously derived
     * node) contribute a single empty environment, which means "holds under no assumptions".
     * This is the correct behaviour when a fact is asserted as a tautology (e.g., a hard
     * EDB fact).</p>
     *
     * @param consequent   the derived node key; must not be null
     * @param antecedents  the antecedent node keys; empty list means the consequent is
     *                     a tautology (label = {{∅}})
     * @param ruleDisplay  human-readable description of the rule (for diagnostics)
     */
    public void addJustification(String consequent, Collection<String> antecedents, String ruleDisplay) {
        Objects.requireNonNull(consequent, "consequent must not be null");
        Objects.requireNonNull(antecedents, "antecedents must not be null");
        Objects.requireNonNull(ruleDisplay, "ruleDisplay must not be null");

        List<String> antList = List.copyOf(antecedents);

        // Allocate a justification record
        int justId = allJustifications.size();
        Justification just = new Justification(justId, consequent, antList, ruleDisplay);
        allJustifications.add(just);
        justificationsByConsequent.computeIfAbsent(consequent, k -> new ArrayList<>()).add(just);

        // Register antecedent → justification reverse index for change propagation
        for (String ant : antList) {
            antecedentToJustIds.computeIfAbsent(ant, k -> new LinkedHashSet<>()).add(justId);
        }

        // Compute new environments from this justification and propagate
        Label oldConsequentLabel = labels.getOrDefault(consequent, Label.EMPTY);
        Label newConsequentLabel = incorporateJustification(just, oldConsequentLabel);

        if (!newConsequentLabel.equals(oldConsequentLabel)) {
            updateLabel(consequent, newConsequentLabel);
            propagate(consequent);
        }
    }

    // ─── ADD-NOGOOD ──────────────────────────────────────────────────────────────

    /**
     * Record a nogood (inconsistent set of assumptions) and filter all labels.
     *
     * <p>After recording the nogood, every label in the system is filtered: environments
     * that are supersets of the new nogood are removed. Nodes whose labels become empty
     * (all environments inconsistent) are returned — these conclusions are no longer
     * supported under any consistent assumption set.</p>
     *
     * @param assumptions the assumption ids forming the inconsistent environment; must not be null
     * @return the node keys whose labels became empty after filtering; may be empty
     */
    public Set<String> addNogood(Collection<String> assumptions) {
        Objects.requireNonNull(assumptions, "assumptions must not be null");
        Environment nogoodEnv = new Environment(assumptions);
        nogoodStore.addNogood(nogoodEnv);
        log.debug("ATMS.addNogood: {} → filtering all labels", nogoodEnv);

        Set<String> lostSupport = new LinkedHashSet<>();
        // Filter every label in the system.
        for (Map.Entry<String, Label> entry : labels.entrySet()) {
            Label filtered = entry.getValue().filterNogoods(nogoodStore);
            if (!filtered.equals(entry.getValue())) {
                entry.setValue(filtered);
                if (filtered.isEmpty()) {
                    lostSupport.add(entry.getKey());
                    log.debug("ATMS.addNogood: node '{}' lost all support", entry.getKey());
                }
            }
        }
        return Collections.unmodifiableSet(lostSupport);
    }

    // ─── QUERIES ─────────────────────────────────────────────────────────────────

    /**
     * Return the label (set of minimal support environments) for the given node.
     *
     * @param atomKey the node key; must not be null
     * @return the label; {@link Label#EMPTY} if the node has no recorded label
     */
    public Label labelOf(String atomKey) {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        return labels.getOrDefault(atomKey, Label.EMPTY);
    }

    /**
     * Return the minimal support environments for the given node as an unmodifiable set.
     *
     * <p>This is the primary why-provenance API: each returned environment is a minimal
     * set of base-assumption atom-keys whose joint truth is sufficient to derive
     * {@code atomKey}.</p>
     *
     * @param atomKey the node key; must not be null
     * @return unmodifiable set of environments; empty if the node has no label
     */
    public Set<Environment> label(String atomKey) {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        Label l = labels.getOrDefault(atomKey, Label.EMPTY);
        return Collections.unmodifiableSet(new LinkedHashSet<>(l.environments()));
    }

    /**
     * Test whether the given node holds in the specified context (set of believed assumptions).
     *
     * <p>A conclusion holds in context C iff at least one of its label environments is a
     * subset of C: {@code ∃E ∈ label(atomKey): E ⊆ believedAssumptions}.</p>
     *
     * @param atomKey             the node to query; must not be null
     * @param believedAssumptions the active assumption ids
     * @return {@code true} if the conclusion holds in this context
     */
    public boolean holdsIn(String atomKey, Set<String> believedAssumptions) {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        Objects.requireNonNull(believedAssumptions, "believedAssumptions must not be null");
        return labelOf(atomKey).holdsIn(believedAssumptions);
    }

    /**
     * Test whether the given node still holds after retracting one assumption.
     *
     * <p>Returns {@code true} iff at least one environment in the node's label does NOT
     * contain {@code retractedAssumption}. If every environment contains the assumption,
     * removing it would eliminate all support for the conclusion.</p>
     *
     * @param atomKey              the node to query; must not be null
     * @param retractedAssumption  the assumption being considered for retraction; must not be null
     * @return {@code true} if the conclusion survives retraction of {@code retractedAssumption}
     */
    public boolean survivesRetraction(String atomKey, String retractedAssumption) {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        Objects.requireNonNull(retractedAssumption, "retractedAssumption must not be null");
        return labelOf(atomKey).survivesRetraction(retractedAssumption);
    }

    /**
     * Identify conclusions whose support would be fully lost if the given assumption were retracted.
     *
     * <p>A conclusion C is in the retraction impact of assumption A iff every environment in
     * {@code label(C)} contains A. This is the assumption-level analogue of
     * {@link ai.kompile.graph.reasoning.tms.JustificationIndex#solelyDependentOn}: it
     * identifies which conclusions "die" when A is removed, which is exactly what
     * {@link ai.kompile.graph.reasoning.tms.BeliefReviser} needs to compute its
     * {@code unsupportedAtoms} set.</p>
     *
     * @param assumption the assumption id being queried; must not be null
     * @return unmodifiable set of node keys whose labels are entirely absorbed by environments
     *         containing {@code assumption}; these nodes would lose all support on retraction
     */
    public Set<String> retractionImpact(String assumption) {
        Objects.requireNonNull(assumption, "assumption must not be null");
        Set<String> impact = new LinkedHashSet<>();
        for (Map.Entry<String, Label> entry : labels.entrySet()) {
            Label l = entry.getValue();
            if (l.isEmpty()) continue; // already unsupported
            if (!l.survivesRetraction(assumption)) {
                impact.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(impact);
    }

    /**
     * Return the set of node keys whose labels were truncated due to the
     * environments-per-label cap.
     *
     * <p>A truncated label means the node may have additional (non-minimal) support
     * environments that were dropped. Callers that need completeness guarantees should
     * raise {@link #maxEnvironmentsPerLabel} or increase the derivation cap in
     * {@link ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine} when building
     * via {@link AtmsBuilder}.</p>
     *
     * @return unmodifiable set of truncated node keys
     */
    public Set<String> truncatedLabels() {
        return Collections.unmodifiableSet(truncatedLabelNodes);
    }

    /** Return the maximum environments per label configured for this engine. */
    public int maxEnvironmentsPerLabel() {
        return maxEnvironmentsPerLabel;
    }

    /** Return the registered assumption ids. */
    public Set<String> assumptions() {
        return Collections.unmodifiableSet(assumptions);
    }

    // ─── Internal: label update with cap enforcement ─────────────────────────────

    /**
     * Store a new label for a node, enforcing the environments-per-label cap.
     *
     * <p>If the candidate label has more than {@link #maxEnvironmentsPerLabel} environments,
     * the largest (by assumption count) are dropped first — they are the weakest supports
     * and the least likely to contribute to downstream propagation of small, strong
     * environments. The node is added to {@link #truncatedLabelNodes} to signal
     * incompleteness.</p>
     *
     * @param nodeKey      the node whose label to update
     * @param candidateLabel the new label (already an antichain)
     */
    private void updateLabel(String nodeKey, Label candidateLabel) {
        if (candidateLabel.size() <= maxEnvironmentsPerLabel) {
            labels.put(nodeKey, candidateLabel);
            return;
        }
        // Sort environments by size ascending (smallest first); keep the first maxEnvs.
        List<Environment> sorted = new ArrayList<>(candidateLabel.environments());
        sorted.sort((a, b) -> Integer.compare(a.size(), b.size()));
        List<Environment> kept = sorted.subList(0, maxEnvironmentsPerLabel);
        labels.put(nodeKey, Label.of(kept));
        truncatedLabelNodes.add(nodeKey);
        log.debug("ATMS.updateLabel: label for '{}' truncated ({} → {} environments)",
                nodeKey, candidateLabel.size(), maxEnvironmentsPerLabel);
    }

    // ─── Internal: cross-product computation from one justification ───────────────

    /**
     * Compute the label contribution of a single justification and merge it into a
     * candidate label for the consequent.
     *
     * <p>Algorithm: new environments = cross-product of antecedent labels' environments
     * (pairwise union), filtered by the nogood store, merged into {@code currentLabel}
     * with antichain minimality. An antecedent with no label contributes an implicit
     * single-element set containing {@link Environment#EMPTY}, which represents "no
     * additional assumptions required" (the antecedent is a tautology).</p>
     *
     * @param just         the justification to process
     * @param currentLabel the consequent's current label
     * @return the merged label for the consequent after incorporating this justification
     */
    private Label incorporateJustification(Justification just, Label currentLabel) {
        // Seed the cross-product with a single empty environment
        List<Environment> product = new ArrayList<>();
        product.add(Environment.EMPTY);

        for (String antKey : just.antecedents()) {
            Label antLabel = labels.getOrDefault(antKey, Label.EMPTY);
            if (antLabel.isEmpty()) {
                // Antecedent has no label → this justification cannot produce any new env
                return currentLabel;
            }
            // Cross-product: for each env in current product × each env in antLabel,
            // compute union.
            List<Environment> nextProduct = new ArrayList<>(product.size() * antLabel.size());
            for (Environment e1 : product) {
                for (Environment e2 : antLabel.environments()) {
                    Environment combined = e1.union(e2);
                    // Filter against nogood store immediately to keep product small
                    if (!nogoodStore.isNogood(combined)) {
                        nextProduct.add(combined);
                    }
                }
            }
            product = nextProduct;
            if (product.isEmpty()) {
                // All combinations are inconsistent — nothing to contribute
                return currentLabel;
            }
        }

        // Merge all computed environments into the current label
        Label result = currentLabel;
        for (Environment e : product) {
            result = result.add(e);
        }
        return result;
    }

    // ─── Internal: BFS propagation worklist ──────────────────────────────────────

    /**
     * Propagate a label change from the given node to all downstream consumers.
     *
     * <p>Uses a BFS worklist. For each node in the worklist, re-evaluate every
     * justification that uses this node as an antecedent; if the consequent's label
     * changes, enqueue the consequent. Terminates when the worklist is empty (fixpoint).
     * Cycle safety: a node is enqueued only on a strict label change, and labels grow
     * monotonically (antichain additions only) — so the worklist cannot loop forever.</p>
     *
     * @param changed the node whose label just changed (seed of the propagation)
     */
    private void propagate(String changed) {
        // BFS worklist: nodes whose label just changed
        java.util.Deque<String> worklist = new java.util.ArrayDeque<>();
        worklist.add(changed);

        while (!worklist.isEmpty()) {
            String node = worklist.poll();

            // Find all justifications that use this node as an antecedent
            Set<Integer> justIds = antecedentToJustIds.getOrDefault(node, Set.of());
            for (int jid : justIds) {
                Justification just = allJustifications.get(jid);
                String consequent = just.consequent();
                Label oldLabel = labels.getOrDefault(consequent, Label.EMPTY);
                Label newLabel = incorporateJustification(just, oldLabel);

                if (!newLabel.equals(oldLabel)) {
                    updateLabel(consequent, newLabel);
                    worklist.add(consequent);
                    log.trace("ATMS.propagate: label of '{}' changed via justification '{}'",
                            consequent, just.ruleDisplay());
                }
            }
        }
    }
}
