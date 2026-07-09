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
import ai.kompile.graph.reasoning.fol.InferredFactStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deep (multi-step) why-not explainer — a bounded recursive instance-based variant of the
 * PUG why-not framework (Lee, Köhler, Ludäscher &amp; Glavic, PVLDB 2018).
 *
 * <h2>Overview</h2>
 * <p>The one-step {@link WhyNotExplainer#explain(String)} identifies <em>near-misses</em>:
 * rules that almost prove a claim but for one or more missing body atoms.  When those missing
 * atoms are themselves derivable by rules (i.e. they are not base / EDB facts), the useful
 * diagnosis is not "assert this derived atom" but rather "assert the base facts that would
 * let the missing atom be derived".  {@link #explainDeep(String, int)} performs this
 * recursively up to a configurable depth, building a tree of why-not nodes.</p>
 *
 * <h2>Relation to PUG</h2>
 * <p>Full PUG instance-based why-not rewrites every missing intermediate relation into a
 * witness query on the source database (Theorem 3 in Lee et al. 2018).  This implementation
 * is a <em>bounded</em> approximation that sidesteps view rewriting:
 * <ol>
 *   <li>At each node, run {@link WhyNotExplainer#explain} on the missing atom (one-step
 *       near-miss over recorded rule normal forms).</li>
 *   <li>If a rule head matches the missing atom's predicate the atom is <em>derivable</em>;
 *       otherwise it is a <em>base missing fact</em> that must come from observation / crawl
 *       (the actionable leaf).</li>
 *   <li>Recurse on the missing atoms of the best near-misses (up to
 *       {@link Budget#maxChildrenPerNode} per level) until {@code maxDepth} or
 *       {@link Budget#maxNodes} is reached, with a cycle guard on the recursion path.</li>
 * </ol>
 * Completion sets — sets of ground base facts whose joint assertion would close a derivation
 * chain — are the "minimal witnesses of the missing part" in PUG terminology, restricted to
 * base (EDB) atoms.  Non-ground missing atoms (free variables surviving grounding) are
 * reported in the tree but excluded from completion sets because they cannot be asserted
 * as-is; see {@link CompletionSet}.</p>
 *
 * <h2>Infra</h2>
 * <p>Infra-free: no Spring, no JPA.  Delegates one-step logic entirely to
 * {@link WhyNotExplainer}; adds no new store access.</p>
 */
public final class DeepWhyNot {

    // ── Public result types ──────────────────────────────────────────────────────

    /**
     * A node in the deep why-not tree for one atom.
     *
     * @param atomKey            canonical atom key this node explains
     * @param nearMisses         one-step near-misses (from {@link WhyNotExplainer#explain})
     * @param childByMissingAtom for each missing atom that was recursed into, the child node;
     *                           keyed by the missing atom string as it appears in the near-miss
     * @param baseMissing        {@code true} when no rule head matches this atom's predicate —
     *                           it can only enter the KB via observation / crawl (a leaf action)
     * @param cyclic             {@code true} when this atom was already on the current recursion
     *                           path; children will be empty to prevent infinite loops
     * @param budgetTruncated    {@code true} when recursion into this node's children was cut
     *                           short by the node budget or depth limit
     */
    public record WhyNotNode(
            String atomKey,
            List<WhyNotExplainer.NearMiss> nearMisses,
            Map<String, WhyNotNode> childByMissingAtom,
            boolean baseMissing,
            boolean cyclic,
            boolean budgetTruncated
    ) {
        public WhyNotNode {
            Objects.requireNonNull(atomKey);
            nearMisses = (nearMisses == null) ? List.of() : List.copyOf(nearMisses);
            childByMissingAtom = (childByMissingAtom == null)
                    ? Map.of() : Map.copyOf(childByMissingAtom);
        }
    }

    /**
     * A set of ground base facts whose joint assertion would complete some derivation chain
     * for the top-level claim.
     *
     * <p>Only fully-ground atoms are included.  Non-ground missing atoms (atoms that still
     * contain "?" variables after binding) appear in the tree nodes but are omitted here
     * because they cannot be directly asserted — the variable must be resolved to a constant
     * first.</p>
     *
     * @param facts      ground base-fact atom keys (no "?" variables)
     * @param totalDepth sum of depths reached to collect these facts (tie-break: prefer shallower)
     */
    public record CompletionSet(Set<String> facts, int totalDepth) {
        public CompletionSet {
            Objects.requireNonNull(facts);
            facts = Set.copyOf(facts);
        }
    }

    /**
     * Full report from a deep why-not analysis.
     *
     * @param claimAtom       the atom queried
     * @param root            the root node of the why-not tree
     * @param completionSets  ranked completion sets (smallest first, then shallowest)
     * @param flatSuggestions human-readable lines describing missing atoms and how to complete them
     * @param nodesExplored   total nodes visited (for telemetry / debugging)
     * @param budgetExhausted {@code true} when node budget was hit before full exploration
     */
    public record DeepWhyNotReport(
            String claimAtom,
            WhyNotNode root,
            List<CompletionSet> completionSets,
            List<String> flatSuggestions,
            int nodesExplored,
            boolean budgetExhausted
    ) {
        public DeepWhyNotReport {
            Objects.requireNonNull(claimAtom);
            Objects.requireNonNull(root);
            completionSets  = (completionSets  == null) ? List.of() : List.copyOf(completionSets);
            flatSuggestions = (flatSuggestions == null) ? List.of() : List.copyOf(flatSuggestions);
        }

        /**
         * Human-readable lines (bounded), e.g.:
         * <pre>
         * worksAt(alice, acme) [present] + locatedIn(acme, london) [MISSING — would itself be
         *   provable if officeOf(acme, hq1) existed]
         * </pre>
         * Returns a copy of {@link #flatSuggestions()}.
         */
        public List<String> toSuggestionLines() { return flatSuggestions; }
    }

    // ── Budget knobs ─────────────────────────────────────────────────────────────

    /**
     * Immutable budget / knob holder for {@link #explainDeep}.
     *
     * @param maxDepth           max recursion depth (default 3)
     * @param maxNodes           total node budget across the whole tree (default 64)
     * @param maxChildrenPerNode how many missing atoms of the best near-misses to recurse into
     *                           at each node (default 4)
     * @param maxCompletionSets  max completion sets to return in the report (default 5)
     */
    public record Budget(int maxDepth, int maxNodes, int maxChildrenPerNode, int maxCompletionSets) {
        /** Default budget: depth=3, nodes=64, children=4, completionSets=5. */
        public static final Budget DEFAULT = new Budget(3, 64, 4, 5);

        public Budget {
            // maxDepth == 0 is meaningful (root only, no recursion); only a negative is invalid.
            if (maxDepth           <  0) maxDepth           = 3;
            if (maxNodes           <= 0) maxNodes           = 64;
            if (maxChildrenPerNode <= 0) maxChildrenPerNode = 4;
            if (maxCompletionSets  <= 0) maxCompletionSets  = 5;
        }
    }

    // ── Core fields ───────────────────────────────────────────────────────────────

    private final WhyNotExplainer oneStep;
    private final List<WhyNotExplainer.RuleNf> rules;

    /**
     * Construct a DeepWhyNot over the same rule set and stores as the one-step explainer.
     *
     * @param rules         rule normal forms (same list used for one-step and for derivability check)
     * @param inferredStore materialized inferred-fact store
     * @param factStore     observed-fact store
     */
    public DeepWhyNot(List<WhyNotExplainer.RuleNf> rules,
                      InferredFactStore inferredStore,
                      FactStore factStore) {
        Objects.requireNonNull(inferredStore, "inferredStore must not be null");
        Objects.requireNonNull(factStore,     "factStore must not be null");
        this.rules    = (rules == null) ? List.of() : List.copyOf(rules);
        this.oneStep  = new WhyNotExplainer(this.rules, inferredStore, factStore);
    }

    /**
     * Construct with explicit one-step knobs (binding cap and rule cap forwarded to the
     * embedded {@link WhyNotExplainer}).
     */
    public DeepWhyNot(List<WhyNotExplainer.RuleNf> rules,
                      InferredFactStore inferredStore,
                      FactStore factStore,
                      int maxBindingsPerRule,
                      int maxRules) {
        Objects.requireNonNull(inferredStore, "inferredStore must not be null");
        Objects.requireNonNull(factStore,     "factStore must not be null");
        this.rules   = (rules == null) ? List.of() : List.copyOf(rules);
        this.oneStep = new WhyNotExplainer(this.rules, inferredStore, factStore,
                maxBindingsPerRule, maxRules);
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Deep why-not with default {@link Budget}.
     *
     * @param claimAtomKey e.g. {@code "basedIn(alice, london)"}
     * @param maxDepth     max recursion depth ({@link Budget#DEFAULT} uses 3)
     * @return the deep report
     */
    public DeepWhyNotReport explainDeep(String claimAtomKey, int maxDepth) {
        return explainDeep(claimAtomKey, new Budget(maxDepth,
                Budget.DEFAULT.maxNodes(),
                Budget.DEFAULT.maxChildrenPerNode(),
                Budget.DEFAULT.maxCompletionSets()));
    }

    /**
     * Deep why-not with a full {@link Budget} knob object.
     *
     * @param claimAtomKey e.g. {@code "basedIn(alice, london)"}
     * @param budget       budget / knob configuration
     * @return the deep report
     */
    public DeepWhyNotReport explainDeep(String claimAtomKey, Budget budget) {
        Objects.requireNonNull(claimAtomKey, "claimAtomKey must not be null");
        Objects.requireNonNull(budget,       "budget must not be null");
        String claim = claimAtomKey.trim();

        // Mutable node counter (shared across recursion via single-element array trick)
        int[] nodeCount = {0};
        boolean[] budgetExhausted = {false};

        // Cycle guard: the recursion path of canonical atom keys currently on the call stack
        Set<String> onPath = new LinkedHashSet<>();

        WhyNotNode root = buildNode(claim, budget, 0, onPath, nodeCount, budgetExhausted);

        // Build completion sets from the tree
        List<CompletionSet> completionSets = computeCompletionSets(root, budget);

        // Build flat human-readable suggestion lines
        List<String> flatSuggestions = buildFlatSuggestions(root, completionSets);

        return new DeepWhyNotReport(claim, root, completionSets, flatSuggestions,
                nodeCount[0], budgetExhausted[0]);
    }

    // ── Recursive tree builder ────────────────────────────────────────────────────

    /**
     * Build a {@link WhyNotNode} for {@code atomKey} at the given depth.
     */
    private WhyNotNode buildNode(String atomKey,
                                 Budget budget,
                                 int depth,
                                 Set<String> onPath,
                                 int[] nodeCount,
                                 boolean[] budgetExhausted) {
        String canonKey = canonicalize(atomKey);

        // Cycle detection
        if (onPath.contains(canonKey)) {
            nodeCount[0]++;
            return new WhyNotNode(atomKey, List.of(), Map.of(),
                    false, /*cyclic=*/true, false);
        }

        // Node budget
        if (nodeCount[0] >= budget.maxNodes()) {
            budgetExhausted[0] = true;
            return new WhyNotNode(atomKey, List.of(), Map.of(),
                    isBaseMissing(atomKey), false, /*budgetTruncated=*/true);
        }
        nodeCount[0]++;

        // Derivability shortcut: if no rule head matches the predicate → base missing leaf
        boolean base = isBaseMissing(atomKey);

        // One-step near-miss
        WhyNotExplainer.WhyNotReport oneStepReport = oneStep.explain(atomKey);
        List<WhyNotExplainer.NearMiss> nearMisses = oneStepReport.nearMisses();

        // Recurse into children only while the next level stays below maxDepth: a node at
        // `depth` builds children at depth+1, allowed iff depth+1 < maxDepth. So maxDepth=0/1
        // → root only; maxDepth=3 → root + child + grandchild (depths 0,1,2).
        boolean atDepthCap = (depth + 1) >= budget.maxDepth();
        if (atDepthCap || nearMisses.isEmpty()) {
            boolean truncated = atDepthCap && !nearMisses.isEmpty()
                    && !nearMisses.get(0).missingAtoms().isEmpty();
            return new WhyNotNode(atomKey, nearMisses, Map.of(),
                    base, false, truncated);
        }

        // Choose which missing atoms to recurse into: take the top near-misses (by fewest
        // missing atoms, then highest closeness — already sorted by the one-step explainer),
        // and collect up to maxChildrenPerNode distinct missing atoms across them.
        Set<String> toRecurse = new LinkedHashSet<>();
        for (WhyNotExplainer.NearMiss miss : nearMisses) {
            for (String missing : miss.missingAtoms()) {
                if (toRecurse.size() >= budget.maxChildrenPerNode()) break;
                // Skip non-ground missing atoms (contain "?")
                // We still put them in the tree's nearMiss lists; just don't recurse
                if (!missing.contains("?")) {
                    toRecurse.add(missing);
                }
            }
            if (toRecurse.size() >= budget.maxChildrenPerNode()) break;
        }

        // Recurse
        onPath.add(canonKey);
        Map<String, WhyNotNode> children = new HashMap<>();
        for (String missingAtom : toRecurse) {
            if (nodeCount[0] >= budget.maxNodes()) {
                budgetExhausted[0] = true;
                break;
            }
            WhyNotNode child = buildNode(missingAtom, budget, depth + 1, onPath,
                    nodeCount, budgetExhausted);
            children.put(missingAtom, child);
        }
        onPath.remove(canonKey);

        boolean truncated = (toRecurse.size() < countDistinctMissingGround(nearMisses,
                budget.maxChildrenPerNode() * 2))
                && nodeCount[0] >= budget.maxNodes();

        return new WhyNotNode(atomKey, nearMisses, children, base, false, truncated);
    }

    /**
     * Count how many distinct, ground missing atoms appear across the given near-misses,
     * up to {@code limit} (used to detect truncation without enumerating the full set).
     */
    private static int countDistinctMissingGround(List<WhyNotExplainer.NearMiss> misses,
                                                    int limit) {
        Set<String> seen = new HashSet<>();
        for (WhyNotExplainer.NearMiss m : misses) {
            for (String atom : m.missingAtoms()) {
                if (!atom.contains("?")) seen.add(atom);
                if (seen.size() >= limit) return seen.size();
            }
        }
        return seen.size();
    }

    // ── Derivability check ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if no rule head predicate matches the predicate of {@code atomKey}.
     * In that case the atom can only enter the KB via observation / crawl — it is a "base"
     * (EDB) missing fact: the actionable leaf of the why-not tree.
     *
     * <p>Matching is case-insensitive on the predicate name.</p>
     */
    private boolean isBaseMissing(String atomKey) {
        if (atomKey == null || atomKey.isBlank()) return true;
        String pred = extractPredicate(atomKey);
        if (pred == null) return true;
        String predLc = pred.toLowerCase(Locale.ROOT);
        for (WhyNotExplainer.RuleNf rule : rules) {
            if (rule.headPredicate().equalsIgnoreCase(predLc)) return false;
        }
        return true;
    }

    /** Extract the predicate name (the part before the first '('). */
    private static String extractPredicate(String atomKey) {
        int lp = atomKey.indexOf('(');
        if (lp <= 0) return null;
        return atomKey.substring(0, lp).trim();
    }

    // ── Completion set computation ────────────────────────────────────────────────

    /**
     * Walk the why-not tree and derive {@link CompletionSet}s — minimal sets of ground base
     * facts whose joint assertion would close some derivation chain for the root claim.
     *
     * <p>Strategy: for each near-miss at the root (and recursively at each node), compute the
     * "completion" of that near-miss as the union of completions of each of its missing atoms.
     * The completion of a missing atom is:
     * <ul>
     *   <li>If the atom is ground, base-missing, and has a child node with
     *       {@code baseMissing=true}: the singleton {@code {atom}}.</li>
     *   <li>If the atom has a child node and that node is not base-missing: delegate to the
     *       child's completions (recursively).</li>
     *   <li>If the atom has no child (truncated / depth cap): use the atom itself if ground,
     *       otherwise skip (non-ground).</li>
     * </ul>
     * Completion sets that contain non-ground atoms are dropped (per spec: non-ground atoms are
     * excluded because they cannot be directly asserted).
     */
    private List<CompletionSet> computeCompletionSets(WhyNotNode root, Budget budget) {
        List<CompletionSet> sets = new ArrayList<>(collectCompletions(root, 0, budget));

        // Deduplicate by fact content
        List<CompletionSet> deduped = new ArrayList<>();
        Set<Set<String>> seen = new HashSet<>();
        for (CompletionSet cs : sets) {
            if (seen.add(cs.facts())) deduped.add(cs);
        }

        // Sort: smallest set first, then shallowest (lowest totalDepth)
        deduped.sort(Comparator.comparingInt((CompletionSet cs) -> cs.facts().size())
                .thenComparingInt(CompletionSet::totalDepth));

        // Cap
        int limit = Math.min(budget.maxCompletionSets(), deduped.size());
        return Collections.unmodifiableList(deduped.subList(0, limit));
    }

    /**
     * Recursively collect completion sets from a node.
     *
     * @param node  current node
     * @param depth current depth (for totalDepth accounting)
     */
    private List<CompletionSet> collectCompletions(WhyNotNode node, int depth, Budget budget) {
        List<CompletionSet> result = new ArrayList<>();

        if (node.baseMissing() && !node.cyclic()) {
            // This atom is a base-missing leaf
            if (!node.atomKey().contains("?")) {
                // Ground and base → this IS a completion set by itself
                result.add(new CompletionSet(Set.of(node.atomKey()), depth));
            }
            return result;
        }

        if (node.nearMisses().isEmpty()) return result;

        // For each near-miss, try to build a completion by combining the completions
        // of each of its missing atoms.
        for (WhyNotExplainer.NearMiss miss : node.nearMisses()) {
            if (miss.missingAtoms().isEmpty()) continue; // fully satisfied (stale mat.)

            // Compute completion sets for this near-miss = cartesian product of per-missing-atom completions
            List<List<CompletionSet>> perAtomSets = new ArrayList<>();
            boolean anySkipped = false;

            for (String missingAtom : miss.missingAtoms()) {
                WhyNotNode child = node.childByMissingAtom().get(missingAtom);
                if (child != null) {
                    List<CompletionSet> childCompletions = collectCompletions(child, depth + 1, budget);
                    if (childCompletions.isEmpty()) {
                        // Child has no completions (non-ground or cyclic) → skip this near-miss
                        anySkipped = true;
                        break;
                    }
                    perAtomSets.add(childCompletions);
                } else {
                    // No child (truncated / non-ground): use the missing atom directly if ground
                    if (!missingAtom.contains("?")) {
                        perAtomSets.add(List.of(new CompletionSet(Set.of(missingAtom), depth + 1)));
                    } else {
                        // Non-ground: cannot form a ground completion set — skip this near-miss
                        anySkipped = true;
                        break;
                    }
                }
            }

            if (!anySkipped) {
                // Cross-product of per-atom completion sets (capped to avoid explosion)
                List<CompletionSet> crossed = cartesianUnion(perAtomSets, budget.maxCompletionSets());
                result.addAll(crossed);
            }
        }

        return result;
    }

    /**
     * Cartesian union of completion-set lists: for each combination (one from each list),
     * produce the union of facts and the sum of depths.
     * Capped at {@code cap} results to prevent combinatorial explosion.
     */
    private static List<CompletionSet> cartesianUnion(List<List<CompletionSet>> lists, int cap) {
        if (lists.isEmpty()) return List.of();
        List<CompletionSet> acc = new ArrayList<>();
        acc.add(new CompletionSet(Set.of(), 0));

        for (List<CompletionSet> group : lists) {
            List<CompletionSet> next = new ArrayList<>();
            for (CompletionSet left : acc) {
                for (CompletionSet right : group) {
                    if (next.size() >= cap) break;
                    Set<String> merged = new HashSet<>(left.facts());
                    merged.addAll(right.facts());
                    next.add(new CompletionSet(merged, left.totalDepth() + right.totalDepth()));
                }
                if (next.size() >= cap) break;
            }
            acc = next;
        }
        return acc;
    }

    // ── Flat suggestion rendering ─────────────────────────────────────────────────

    /**
     * Build human-readable suggestion lines from the root node's near-misses and the
     * completion sets.  Lines look like:
     * <pre>
     * worksAt(alice, acme) [present] + locatedIn(acme, london) [MISSING — would itself be
     *   provable if officeOf(acme, hq1) existed]
     * </pre>
     *
     * <p>Bounded: at most 8 lines, one per top near-miss (already sorted by fewest missing).</p>
     */
    private List<String> buildFlatSuggestions(WhyNotNode root,
                                               List<CompletionSet> completionSets) {
        List<String> lines = new ArrayList<>();

        // Line 1 type: completion set summary
        if (!completionSets.isEmpty()) {
            CompletionSet best = completionSets.get(0);
            if (!best.facts().isEmpty()) {
                // Sort for determinism
                List<String> sorted = new ArrayList<>(best.facts());
                Collections.sort(sorted);
                lines.add("To complete the derivation, assert: " + String.join(" AND ", sorted));
            }
        }

        // Lines per near-miss
        for (WhyNotExplainer.NearMiss miss : root.nearMisses()) {
            if (lines.size() >= 8) break;
            StringBuilder sb = new StringBuilder();

            // Satisfied atoms
            List<String> sat = miss.satisfiedAtoms();
            for (int i = 0; i < sat.size(); i++) {
                if (i > 0) sb.append(" + ");
                sb.append(sat.get(i)).append(" [present]");
            }
            if (!sat.isEmpty() && !miss.missingAtoms().isEmpty()) sb.append(" + ");

            // Missing atoms, annotated with child info
            List<String> missing = miss.missingAtoms();
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) sb.append(" + ");
                String missingAtom = missing.get(i);
                sb.append(missingAtom).append(" [MISSING");

                WhyNotNode child = root.childByMissingAtom().get(missingAtom);
                if (child != null && !child.nearMisses().isEmpty()) {
                    // Describe how the child could be proved
                    String childAnnotation = describeChildCompletion(child);
                    if (childAnnotation != null) {
                        sb.append(" — would itself be provable if ").append(childAnnotation);
                    }
                } else if (child != null && child.baseMissing()) {
                    sb.append(" — base fact; must be observed/crawled");
                } else if (missingAtom.contains("?")) {
                    sb.append(" — non-ground; variable must be bound");
                }
                sb.append("]");
            }

            if (sb.length() > 0) lines.add(sb.toString());
        }

        // If no near-miss lines but we have completion sets, add them
        if (lines.size() <= 1 && !completionSets.isEmpty()) {
            for (CompletionSet cs : completionSets) {
                if (lines.size() >= 8) break;
                List<String> sorted = new ArrayList<>(cs.facts());
                Collections.sort(sorted);
                lines.add("Completion (depth=" + cs.totalDepth() + "): "
                        + String.join(", ", sorted));
            }
        }

        return Collections.unmodifiableList(lines);
    }

    /**
     * Produce a short annotation for a child node's best near-miss, e.g.
     * {@code "officeOf(acme, hq1) existed"}.
     */
    private static String describeChildCompletion(WhyNotNode child) {
        if (child.nearMisses().isEmpty()) return null;
        WhyNotExplainer.NearMiss best = child.nearMisses().get(0); // already sorted
        List<String> childMissing = best.missingAtoms();
        if (childMissing.isEmpty()) return null;
        // Describe the first one or two missing atoms
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(2, childMissing.size()); i++) {
            parts.add(childMissing.get(i));
        }
        if (childMissing.size() > 2) parts.add("…");
        return String.join(" AND ", parts) + " existed";
    }

    // ── Utility ───────────────────────────────────────────────────────────────────

    /**
     * Canonical form of an atom key for cycle detection: lowercase predicate, trimmed args.
     */
    private static String canonicalize(String atomKey) {
        if (atomKey == null) return "";
        int lp = atomKey.indexOf('(');
        if (lp < 0) return atomKey.toLowerCase(Locale.ROOT).trim();
        String pred = atomKey.substring(0, lp).trim().toLowerCase(Locale.ROOT);
        String rest = atomKey.substring(lp); // "(args)" as-is (args are already constants)
        return pred + rest;
    }
}
