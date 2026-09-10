/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts raw PSL atom keys and rule strings into human-readable labels for the
 * grounding-trace UI.
 *
 * <h3>Atom key format (from GraphToFactStoreProjector)</h3>
 * <ul>
 *   <li>Unary: {@code lowercase(nodeType)(sanitizedExternalId)} — e.g.
 *       {@code entity(country_usa)}</li>
 *   <li>Derived unary: {@code derived_<predicate>(arg)} — e.g.
 *       {@code derived_entity(wb_04a)}</li>
 *   <li>Binary: {@code lowercase(edgeType)(srcExternalId, tgtExternalId)}</li>
 * </ul>
 *
 * <h3>Resolution strategy</h3>
 * <p>For unary atoms the predicate (after stripping {@code derived_}) maps directly to a
 * {@link NodeLevel} enum value, so a targeted {@link KnowledgeGraphService#getNodeByExternalId}
 * call is made first. For binary atoms (edge predicates), the NodeLevel of each argument
 * is unknown; the helper tries {@link NodeLevel#ENTITY} first, then iterates through all
 * remaining levels. If no node is found the slug is returned as-is (already human-readable
 * in the reference corpus, e.g. {@code country_usa}).</p>
 *
 * <h3>Special-case keys</h3>
 * <ul>
 *   <li>{@code factSheet:N} / {@code process:factSheet:N} — rendered as "fact sheet N"</li>
 * </ul>
 */
@Component("reasoningTraceHumanizer")
@Slf4j
public class TraceHumanizer {

    /** Matches a well-formed atom key: {@code predicate(args...)}. */
    private static final Pattern ATOM_PAT = Pattern.compile("^([\\w\\-]+)\\((.+)\\)$", Pattern.DOTALL);

    /** Matches the trailing PSL power annotation, e.g. {@code  ^1} or {@code  ^2}. */
    private static final Pattern POWER_SUFFIX = Pattern.compile("\\s+\\^\\d+\\s*$");

    /** Matches a full mined-rule id (no surrounding whitespace). */
    private static final Pattern MINED_ID = Pattern.compile("^mined-[0-9a-f\\-]+$");

    /**
     * NodeLevel lookup order used when the argument's type cannot be derived from the
     * predicate alone (binary atoms / unknown predicates). ENTITY is tried first because
     * the vast majority of PSL-grounded nodes are at the ENTITY level in the reference corpus.
     */
    private static final NodeLevel[] LOOKUP_ORDER = {
        NodeLevel.ENTITY, NodeLevel.SOURCE, NodeLevel.DOCUMENT,
        NodeLevel.SNIPPET, NodeLevel.TABLE, NodeLevel.CUSTOM,
        NodeLevel.ATTACHMENT, NodeLevel.IDENTIFIER
    };

    private final KnowledgeGraphService graphService;

    @Autowired
    public TraceHumanizer(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    /** Matches a canonical 8-4-4-4-12 UUID. */
    private static final Pattern UUID_PAT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Static, graph-free label cleaner — the final display fallback when no graph lookup is
     * available. Strips path/namespace prefixes, replaces underscores with spaces, and never
     * returns a raw UUID (UUIDs are shortened to their first block with an ellipsis).
     *
     * @param raw a node id, external id, or atom argument; null/blank returns as-is
     * @return a display-safe label, or the original null/blank input
     */
    public static String cleanLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String label = raw.trim();
        if (label.length() >= 2
                && ((label.startsWith("\"") && label.endsWith("\""))
                || (label.startsWith("'") && label.endsWith("'")))) {
            label = label.substring(1, label.length() - 1).trim();
        }

        Matcher atom = ATOM_PAT.matcher(label);
        if (atom.matches() && indexOfTopLevelComma(atom.group(2)) < 0) {
            return cleanLabel(atom.group(2));
        }
        if (UUID_PAT.matcher(label).matches()) {
            return label.substring(0, 8) + "…";
        }
        int slash = Math.max(label.lastIndexOf('/'), label.lastIndexOf('\\'));
        if (slash >= 0 && slash < label.length() - 1) {
            label = label.substring(slash + 1);
        }
        int colon = label.lastIndexOf(':');
        if (colon >= 0 && colon < label.length() - 1) {
            label = label.substring(colon + 1);
        }
        if (UUID_PAT.matcher(label).matches()) {
            return label.substring(0, 8) + "…";
        }
        label = label.replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (label.isEmpty()) {
            return label;
        }
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    /**
     * Produce a human-readable label for a single PSL atom key.
     *
     * <ul>
     *   <li>Unary  {@code entity(country_usa)}          → {@code "United States"} (or {@code "entity: country_usa"} if unresolved)</li>
     *   <li>Derived {@code derived_entity(wb_04a)}      → resolves arg, strips {@code derived_} prefix</li>
     *   <li>Binary  {@code contains(wb_04a, item_1)}    → {@code "WB 04A — contains → Item 1"}</li>
     *   <li>Special {@code factSheet:3}                 → {@code "fact sheet 3"}</li>
     * </ul>
     *
     * @param atomKey raw PSL atom key; null/blank returned as-is
     * @return humanized label (never null if input is non-null)
     */
    public String humanizeAtom(String atomKey) {
        if (atomKey == null || atomKey.isBlank()) return atomKey;

        // Special non-graph keys: factSheet:N or process:factSheet:N
        if (atomKey.startsWith("process:factSheet:")) {
            return "fact sheet " + atomKey.substring("process:factSheet:".length());
        }
        if (atomKey.startsWith("factSheet:")) {
            return "fact sheet " + atomKey.substring("factSheet:".length());
        }

        Matcher m = ATOM_PAT.matcher(atomKey.trim());
        if (!m.matches()) {
            // Not atom-shaped — strip derived_ if present and return
            return atomKey.startsWith("derived_") ? atomKey.substring("derived_".length()) : atomKey;
        }

        String rawPredicate = m.group(1);
        String argsStr = m.group(2).trim();

        // Strip leading derived_
        String predicate = rawPredicate.startsWith("derived_")
                ? rawPredicate.substring("derived_".length())
                : rawPredicate;

        // Split on first comma to separate unary vs binary
        int commaIdx = indexOfTopLevelComma(argsStr);

        if (commaIdx < 0) {
            // Unary atom
            String arg = argsStr.trim();
            NodeLevel hint = nodeLevelForPredicate(predicate);
            String title = resolveSlug(arg, hint);
            if (title.equals(arg)) {
                // No graph resolution — show predicate: slug (still readable)
                return predicate + ": " + arg;
            }
            return title;
        } else {
            // Binary atom (edge)
            String arg1 = argsStr.substring(0, commaIdx).trim();
            String arg2 = argsStr.substring(commaIdx + 1).trim();
            String title1 = resolveSlug(arg1, null);
            String title2 = resolveSlug(arg2, null);
            // Use Unicode em-dash and right-arrow for a clean readable label
            return title1 + " — " + predicate + " → " + title2;
        }
    }

    /**
     * Build an {@code atomKey → humanizedLabel} map for every key in the given iterable.
     * Only entries where the humanized label differs from the raw key are included.
     *
     * @param atomKeys the atom keys to process (e.g. from {@link ai.kompile.graph.reasoning.fol.grounding.DerivationTree#allAtomKeys()})
     * @return mutable map suitable for passing to {@code DerivationTree.toJsonWithTitles}
     */
    public Map<String, String> buildAtomKeyToTitle(Iterable<String> atomKeys) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : atomKeys) {
            if (key == null) continue;
            String humanized = humanizeAtom(key);
            if (humanized != null && !humanized.equals(key)) {
                map.put(key, humanized);
            }
        }
        return map;
    }

    /**
     * Build a {@code ruleString → humanizedLabel} map for a list of rule strings.
     *
     * @param rules raw rule strings (may include weights, power suffixes, mined ids)
     * @return mutable map suitable for passing to
     *         {@code DerivationTree.toJsonWithTitlesAndRules}
     */
    public Map<String, String> buildRuleMap(Iterable<String> rules) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String rule : rules) {
            if (rule == null) continue;
            String humanized = humanizeRule(rule);
            if (humanized != null && !humanized.equals(rule)) {
                map.put(rule, humanized);
            }
        }
        return map;
    }

    /**
     * Produce a human-readable label for a PSL rule string.
     *
     * <ul>
     *   <li>Strips the trailing {@code  ^N} power annotation</li>
     *   <li>{@code mined-<uuid>} → {@code "mined rule (process-mining)"}</li>
     *   <li>{@code State(...) &amp; Link(...) -> State(...)} → {@code "confidence propagates along graph links"}</li>
     *   <li>Normal rules: strips {@code derived_} from predicate names, renders weight</li>
     * </ul>
     *
     * @param rule raw rule string; null returned as-is
     * @return humanized label
     */
    public String humanizeRule(String rule) {
        if (rule == null || rule.isBlank()) return rule;

        // 1. Strip trailing power annotation " ^N"
        String stripped = POWER_SUFFIX.matcher(rule).replaceFirst("").trim();

        // 2. mined-<uuid>: process-mining rule
        if (MINED_ID.matcher(stripped).matches()) {
            return "mined rule (process-mining)";
        }

        // 3. Internal confidence-propagation templates containing State and Link predicates
        if (stripped.contains("State(") && stripped.contains("Link(")) {
            return "confidence propagates along graph links";
        }
        // Match pattern: anything containing -> that has State on both sides
        if (stripped.matches(".*State\\([^)]*\\).*->.*State\\([^)]*\\).*")) {
            return "confidence propagates along graph links";
        }

        // 4. Normal rule: strip derived_ from all predicates, render weight readably
        String cleaned = stripped.replace("derived_", "");

        // "0.8: entity(?X) -> entity(?X)" → "weight 0.8: entity(?X) → entity(?X)"
        Matcher wm = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?):\\s*(.+)$").matcher(cleaned);
        if (wm.matches()) {
            String weight = wm.group(1);
            String body = wm.group(2).trim()
                    .replace("->", "→")   // → for readability
                    .replace(":-", "←");   // ← for backward-chaining notation
            return "weight " + weight + ": " + body;
        }

        return cleaned;
    }

    /**
     * Humanize an evidence list in-place, returning a new list where atom-key-shaped
     * entries are replaced with their human-readable labels, and other strings are
     * passed through.
     *
     * @param evidenceAtoms list of atom-key evidence strings
     * @return new list with humanized labels
     */
    public List<String> humanizeEvidenceAtoms(List<String> evidenceAtoms) {
        List<String> result = new ArrayList<>(evidenceAtoms.size());
        for (String ev : evidenceAtoms) {
            result.add(humanizeAtom(ev));
        }
        return result;
    }

    /**
     * Humanize an activated-rules list, returning a new list where each rule is
     * processed through {@link #humanizeRule(String)}.
     *
     * @param rules raw rule strings
     * @return new list with humanized labels
     */
    public List<String> humanizeRules(List<String> rules) {
        List<String> result = new ArrayList<>(rules.size());
        for (String r : rules) {
            result.add(humanizeRule(r));
        }
        return result;
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /**
     * Map a PSL predicate name (post {@code derived_} strip) to a {@link NodeLevel}.
     * Returns {@code null} for edge-type predicates and other non-node-type names.
     */
    private NodeLevel nodeLevelForPredicate(String predicate) {
        if (predicate == null) return null;
        try {
            return NodeLevel.valueOf(predicate.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null; // edge-type or unknown predicate
        }
    }

    /**
     * Resolve a sanitized externalId slug to a node title.
     *
     * <p>If {@code hint} is non-null it is tried first via
     * {@link KnowledgeGraphService#getNodeByExternalId}. Then every level in
     * {@link #LOOKUP_ORDER} is tried in sequence. Returns the slug if no match is found.</p>
     */
    private String resolveSlug(String slug, NodeLevel hint) {
        if (slug == null || slug.isBlank()) return slug;

        // Try hinted level first (exact match for unary atoms whose predicate IS the type)
        if (hint != null) {
            Optional<String> title = fetchTitle(slug, hint);
            if (title.isPresent()) return title.get();
        }

        // Fallback: try all levels in priority order
        for (NodeLevel level : LOOKUP_ORDER) {
            if (level == hint) continue; // already tried
            Optional<String> title = fetchTitle(slug, level);
            if (title.isPresent()) return title.get();
        }

        return slug; // slug is already human-readable (e.g. country_usa)
    }

    private Optional<String> fetchTitle(String externalId, NodeLevel level) {
        try {
            Optional<GraphNode> nodeOpt = graphService.getNodeByExternalId(externalId, level);
            if (nodeOpt.isEmpty()) return Optional.empty();
            String t = nodeOpt.get().getTitle();
            if (t != null && !t.isBlank() && !t.equals(externalId)) {
                return Optional.of(t);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debug("TraceHumanizer: lookup failed externalId='{}' level={}: {}",
                    externalId, level, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Find the index of the first top-level comma in a PSL argument string.
     * Top-level means not nested inside additional parentheses (handles nested atoms,
     * though PSL atom args are generally flat).
     */
    private static int indexOfTopLevelComma(String args) {
        int depth = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }
}
