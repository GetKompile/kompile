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

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.tms.JustificationIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A proof / justification tree for explaining a derived fact.
 *
 * <p>This is the <em>P0-4 derivation-tree primitive</em> (described as P1-3 in the
 * roadmap but promoted to the P0 lib deliverable). Given a derived atom key, this class
 * builds the tree of supporting facts and rules that produced it — analogous to Soufflé's
 * {@code -t explain} feature or ProbLog's {@code explain} mode.</p>
 *
 * <h3>Building the tree</h3>
 * <p>Call {@link #build(String, InferredFactStore, JustificationIndex, int)} with the
 * atom key of interest. The method:
 * <ol>
 *   <li>Looks up the atom in {@link InferredFactStore#latest(String)} to get its
 *       confidence, supporting fact keys, and supporting rule ids ({@link InferredFact}).</li>
 *   <li>For each supporting fact key, recursively builds a child {@link DerivationTree}
 *       up to {@code maxDepth} hops, consulting {@link JustificationIndex#supportingFacts}
 *       and {@link JustificationIndex#supportingRules} at each level.</li>
 *   <li>Terminates recursion at observed (non-derived) leaves, or when {@code maxDepth}
 *       is exhausted, or when a cycle is detected (same atom key appears in the ancestor
 *       path).</li>
 * </ol>
 *
 * <h3>Serialization</h3>
 * <p>{@link #toString()} produces a human-readable indented outline. {@link #toJson()}
 * produces a structural JSON representation suitable for agent-facing explanation APIs.</p>
 *
 * @param atomKey          the atom key of the derived or observed fact at this node
 * @param confidence       soft-truth confidence in [0, 1] (0.0 for nodes not in the store)
 * @param ruleApplied      the rule display string that produced this atom, or {@code null}
 *                         for directly-observed (leaf) facts
 * @param sourceProvenance the run ID or crawl-run provenance, or {@code null} if unknown
 * @param children         the child nodes (supporting facts + recursively derived nodes)
 */
public record DerivationTree(
        String atomKey,
        double confidence,
        String ruleApplied,
        String sourceProvenance,
        List<DerivationTree> children
) {

    /** Maximum default derivation depth. */
    public static final int DEFAULT_MAX_DEPTH = 5;

    public DerivationTree {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
        }
        children = (children == null) ? List.of() : List.copyOf(children);
    }

    // ─── Factory ─────────────────────────────────────────────────────────────────

    /**
     * Build a derivation tree for the given atom key up to {@code maxDepth} hops.
     *
     * @param atomKey   the atom key to explain
     * @param store     the materialized inferred fact store
     * @param index     the justification index from the last MAP solve
     * @param maxDepth  maximum number of derivation hops (must be ≥ 1)
     * @return the derivation tree; a leaf node with confidence 0.0 if the atom is unknown
     */
    public static DerivationTree build(String atomKey,
                                       InferredFactStore store,
                                       JustificationIndex index,
                                       int maxDepth) {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(index, "index must not be null");
        if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be ≥ 1, got: " + maxDepth);

        // Use a Set to detect cycles (same atom appearing in an ancestor path)
        Set<String> ancestors = new LinkedHashSet<>();
        return buildNode(atomKey.trim(), store, index, maxDepth, ancestors);
    }

    /**
     * Convenience overload using {@link #DEFAULT_MAX_DEPTH}.
     *
     * @param atomKey the atom key to explain
     * @param store   the materialized inferred fact store
     * @param index   the justification index
     * @return the derivation tree
     */
    public static DerivationTree build(String atomKey,
                                       InferredFactStore store,
                                       JustificationIndex index) {
        return build(atomKey, store, index, DEFAULT_MAX_DEPTH);
    }

    // ─── Recursive builder ───────────────────────────────────────────────────────

    private static DerivationTree buildNode(String atomKey,
                                            InferredFactStore store,
                                            JustificationIndex index,
                                            int depth,
                                            Set<String> ancestors) {
        // Cycle guard
        if (ancestors.contains(atomKey)) {
            return new DerivationTree(atomKey, 0.0, "[cycle]", null, List.of());
        }
        ancestors.add(atomKey);
        try {
            Optional<InferredFact> factOpt = store.latest(atomKey);
            if (factOpt.isEmpty()) {
                // Not in the inferred store — this is an observed/unknown leaf
                return new DerivationTree(atomKey, 0.0, null, null, List.of());
            }
            InferredFact fact = factOpt.get();
            double confidence = fact.confidence();
            String runId = fact.runId();

            // Determine the primary rule applied (first supporting rule, if any)
            String ruleApplied = fact.supportingRuleIds().isEmpty()
                    ? null : fact.supportingRuleIds().get(0);

            // Collect children from multiple sources with deduplication
            List<DerivationTree> children = new ArrayList<>();
            Set<String> childKeysAdded = new LinkedHashSet<>();

            if (depth > 1) {
                // Source 1: supporting fact keys stored directly in the InferredFact
                for (String childKey : fact.supportingFactKeys()) {
                    if (childKeysAdded.add(childKey)) {
                        children.add(buildNode(childKey, store, index, depth - 1,
                                new LinkedHashSet<>(ancestors)));
                    }
                }

                // Source 2: JustificationIndex.supportingFacts (from GroundRule bodies)
                for (String childKey : index.supportingFacts(atomKey)) {
                    if (childKeysAdded.add(childKey)) {
                        children.add(buildNode(childKey, store, index, depth - 1,
                                new LinkedHashSet<>(ancestors)));
                    }
                }
            }

            // Build a combined rule annotation from all activated rules.
            // When more than one rule contributed, append " | alt: N more" so the primary rule
            // is always first and callers can see that alternatives exist without bloating the field.
            List<String> allRules = new ArrayList<>(fact.supportingRuleIds());
            List<String> indexRules = index.supportingRules(atomKey);
            for (String r : indexRules) {
                if (!allRules.contains(r)) allRules.add(r);
            }
            String combinedRule;
            if (allRules.isEmpty()) {
                combinedRule = null;
            } else if (allRules.size() == 1) {
                combinedRule = allRules.get(0);
            } else {
                // Primary rule is first; remaining count surfaced as " | alt: N more"
                combinedRule = allRules.get(0) + " | alt: " + (allRules.size() - 1) + " more";
            }

            return new DerivationTree(atomKey, confidence, combinedRule, runId, children);

        } finally {
            ancestors.remove(atomKey);
        }
    }

    // ─── Serialization ───────────────────────────────────────────────────────────

    /**
     * Produce a human-readable indented outline of the derivation tree.
     *
     * <p>Format:
     * <pre>
     *   isEmployedBy(Alice, Acme) [confidence=0.87, rule=0.9: worksAt(X,Z) :- ...]
     *     worksAt(Alice, Acme_NYC) [confidence=0.95, source=run-42]
     *     subsidiary(Acme_NYC, Acme) [confidence=0.92, source=run-42]
     * </pre>
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        renderText(sb, 0);
        return sb.toString();
    }

    private void renderText(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent)).append(atomKey);
        sb.append(" [confidence=").append(String.format("%.4f", confidence));
        if (ruleApplied != null) sb.append(", rule=").append(ruleApplied);
        if (sourceProvenance != null) sb.append(", source=").append(sourceProvenance);
        sb.append(']');
        for (DerivationTree child : children) {
            sb.append('\n');
            child.renderText(sb, indent + 1);
        }
    }

    /**
     * Produce a structural JSON representation suitable for agent-facing explanation APIs.
     *
     * <p>Format (hand-rolled, no jackson dependency):
     * <pre>
     * {
     *   "atom": "isEmployedBy(Alice, Acme)",
     *   "confidence": 0.87,
     *   "rule": "0.9: worksAt(?X,?Z) :- worksAt(?X,?Y) &amp; subsidiary(?Y,?Z)",
     *   "source": "run-42",
     *   "children": [
     *     { "atom": "worksAt(Alice, Acme_NYC)", "confidence": 0.95, "children": [] },
     *     ...
     *   ]
     * }
     * </pre>
     *
     * @return JSON string
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        renderJson(sb, 0);
        return sb.toString();
    }

    /**
     * Produce the same structural JSON as {@link #toJson()} but with an optional {@code "title"}
     * field on each node when a mapping exists in {@code atomKeyToTitle}.
     *
     * <p>The title is omitted when absent from the map or when it is identical to the atom key
     * (no new information). This allows callers to pass a title map built by translating PSL
     * synthetic constants to human-readable entity labels without duplicating any output.</p>
     *
     * @param atomKeyToTitle map from atom key to a human-readable title; may be empty but not null
     * @return JSON string
     */
    public String toJsonWithTitles(Map<String, String> atomKeyToTitle) {
        StringBuilder sb = new StringBuilder();
        renderJsonWithTitles(sb, 0, atomKeyToTitle, Map.of());
        return sb.toString();
    }

    /**
     * Produce the same structural JSON as {@link #toJsonWithTitles(Map)} but also humanizes
     * the {@code "rule"} field on each tree node via {@code ruleToHumanized}.
     *
     * <p>When a node's {@code ruleApplied} string is present in {@code ruleToHumanized} the
     * mapped value is emitted instead of the raw PSL rule, so the frontend tooltip shows
     * e.g. {@code "weight 0.8: entity → derived entity"} rather than the opaque
     * {@code "0.8: entity(?X) -> derived_entity(?X) ^1"}.
     * Entries absent from the map fall through to the raw string (safe default).</p>
     *
     * @param atomKeyToTitle  map from atom key to human-readable title; may be empty but not null
     * @param ruleToHumanized map from raw rule string to humanized label; may be empty but not null
     * @return JSON string
     */
    public String toJsonWithTitlesAndRules(Map<String, String> atomKeyToTitle,
                                           Map<String, String> ruleToHumanized) {
        StringBuilder sb = new StringBuilder();
        renderJsonWithTitles(sb, 0, atomKeyToTitle, ruleToHumanized);
        return sb.toString();
    }

    private void renderJsonWithTitles(StringBuilder sb, int indent,
                                      Map<String, String> titleMap,
                                      Map<String, String> ruleMap) {
        String pad = "  ".repeat(indent);
        String inner = "  ".repeat(indent + 1);
        sb.append(pad).append('{').append('\n');
        appendJsonStr(sb, inner, "atom", atomKey);
        String title = titleMap.get(atomKey);
        if (title != null && !title.equals(atomKey)) {
            sb.append(",\n");
            appendJsonStr(sb, inner, "title", title);
        }
        sb.append(",\n");
        sb.append(inner).append('"').append("confidence").append("\": ").append(confidence);
        if (ruleApplied != null) {
            sb.append(",\n");
            String displayRule = ruleMap.getOrDefault(ruleApplied, ruleApplied);
            appendJsonStr(sb, inner, "rule", displayRule);
        }
        if (sourceProvenance != null) {
            sb.append(",\n");
            appendJsonStr(sb, inner, "source", sourceProvenance);
        }
        sb.append(",\n");
        sb.append(inner).append("\"children\": [");
        if (!children.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < children.size(); i++) {
                children.get(i).renderJsonWithTitles(sb, indent + 2, titleMap, ruleMap);
                if (i < children.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append(inner);
        }
        sb.append("]\n");
        sb.append(pad).append('}');
    }

    private void renderJson(StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        String inner = "  ".repeat(indent + 1);
        sb.append(pad).append('{').append('\n');
        appendJsonStr(sb, inner, "atom", atomKey);
        sb.append(",\n");
        sb.append(inner).append('"').append("confidence").append("\": ").append(confidence);
        if (ruleApplied != null) {
            sb.append(",\n");
            appendJsonStr(sb, inner, "rule", ruleApplied);
        }
        if (sourceProvenance != null) {
            sb.append(",\n");
            appendJsonStr(sb, inner, "source", sourceProvenance);
        }
        sb.append(",\n");
        sb.append(inner).append("\"children\": [");
        if (!children.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < children.size(); i++) {
                children.get(i).renderJson(sb, indent + 2);
                if (i < children.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append(inner);
        }
        sb.append("]\n");
        sb.append(pad).append('}');
    }

    private static void appendJsonStr(StringBuilder sb, String indent, String key, String value) {
        sb.append(indent).append('"').append(escapeJson(key)).append("\": \"")
                .append(escapeJson(value)).append('"');
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** Whether this node is a leaf (no children). */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /** Collect all atom keys in the entire subtree (BFS order). */
    public List<String> allAtomKeys() {
        List<String> keys = new ArrayList<>();
        collectKeys(keys);
        return Collections.unmodifiableList(keys);
    }

    private void collectKeys(List<String> acc) {
        acc.add(atomKey);
        for (DerivationTree child : children) {
            child.collectKeys(acc);
        }
    }
}
