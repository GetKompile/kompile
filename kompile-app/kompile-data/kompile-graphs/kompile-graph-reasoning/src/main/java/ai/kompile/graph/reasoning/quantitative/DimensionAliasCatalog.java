/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Dimension member aliases learned from taxonomy tables already present in the graph.
 *
 * <p>Source documents frequently carry their own vocabulary mappings, e.g. a channel taxonomy
 * sheet with a member column ("DTC") and an accepted-names column ("DTC, Direct-to-Consumer,
 * Direct, ecom, EC"). Any text cell whose column header announces synonyms and whose row label
 * names the canonical member contributes one alias group. Entries containing a {@code {placeholder}}
 * are treated as prefix patterns ("Wholesale - {customer}" matches "Wholesale - Target").</p>
 *
 * <p>Learning is evidence-based only: no built-in domain vocabulary ships with this class.</p>
 */
public final class DimensionAliasCatalog {

    private static final Set<String> SYNONYM_HEADER_TOKENS = Set.of(
            "synonym", "synonyms", "alias", "aliases", "acceptable", "accepted", "equivalent");

    private final List<Group> groups;

    private DimensionAliasCatalog(List<Group> groups) {
        this.groups = List.copyOf(groups);
    }

    public static DimensionAliasCatalog empty() {
        return new DimensionAliasCatalog(List.of());
    }

    public static DimensionAliasCatalog learn(ReasoningGraph graph) {
        Objects.requireNonNull(graph, "graph");
        List<Group> groups = new ArrayList<>();
        for (GraphEntity entity : graph.entities()) {
            // Typed master-table members equate their key and display name: "RST-001" IS
            // "Restful Bath Salt 16oz", learned from the source's own SKU master.
            if (DimensionVocabulary.tableMember(entity)) {
                Group memberGroup = memberGroup(entity);
                if (memberGroup != null) {
                    groups.add(memberGroup);
                }
                continue;
            }
            String cellType = QuantitativeGraphSupport.normalizeType(
                    QuantitativeGraphSupport.firstString(entity, "cellType", "valueType"));
            if (!cellType.contains("STRING")) {
                continue;
            }
            String columnLabel = QuantitativeGraphSupport.firstString(entity, "columnLabel");
            String member = QuantitativeGraphSupport.firstString(entity, "rowLabel");
            String value = QuantitativeGraphSupport.firstString(entity, "displayValue");
            if (columnLabel == null || member == null || value == null
                    || (!value.contains(",") && !value.contains(";"))) {
                continue;
            }
            boolean synonymHeader = QuantitativeGraphSupport.tokens(columnLabel).stream()
                    .anyMatch(SYNONYM_HEADER_TOKENS::contains);
            if (!synonymHeader) {
                continue;
            }
            Group group = group(member, value);
            if (group != null) {
                groups.add(group);
            }
        }
        return new DimensionAliasCatalog(groups);
    }

    /** True when both normalized values belong to one learned alias group. */
    public boolean sameGroup(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        String normalizedLeft = QuantitativeGraphSupport.normalizeText(left);
        String normalizedRight = QuantitativeGraphSupport.normalizeText(right);
        if (normalizedLeft.equals(normalizedRight)) {
            return true;
        }
        for (Group group : groups) {
            if (group.contains(normalizedLeft) && group.contains(normalizedRight)) {
                return true;
            }
        }
        return false;
    }

    public int groupCount() {
        return groups.size();
    }

    private static Group memberGroup(GraphEntity entity) {
        Set<String> members = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        addEntry(members, prefixes, QuantitativeGraphSupport.firstString(entity, "memberKey"));
        addEntry(members, prefixes, entity.label());
        Object aliases = entity.attributes().get("aliases");
        if (aliases instanceof java.util.Collection<?> collection) {
            for (Object alias : collection) {
                if (alias != null) {
                    addEntry(members, prefixes, String.valueOf(alias));
                }
            }
        }
        members.remove("");
        prefixes.remove("");
        return members.size() + prefixes.size() < 2 ? null : new Group(members, prefixes);
    }

    private static Group group(String member, String delimitedAliases) {
        Set<String> members = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        addEntry(members, prefixes, member);
        for (String entry : delimitedAliases.split("[,;]")) {
            addEntry(members, prefixes, entry);
        }
        members.remove("");
        prefixes.remove("");
        return members.size() + prefixes.size() < 2 ? null : new Group(members, prefixes);
    }

    private static void addEntry(Set<String> members, Set<String> prefixes, String rawEntry) {
        if (rawEntry == null) {
            return;
        }
        String entry = rawEntry.trim();
        if (entry.isEmpty()) {
            return;
        }
        int placeholder = entry.indexOf('{');
        if (placeholder >= 0) {
            String prefix = QuantitativeGraphSupport.normalizeText(
                    entry.substring(0, placeholder));
            if (!prefix.isEmpty()) {
                prefixes.add(prefix.toLowerCase(Locale.ROOT));
            }
            return;
        }
        members.add(QuantitativeGraphSupport.normalizeText(entry));
    }

    private record Group(Set<String> members, Set<String> prefixes) {

        private boolean contains(String normalizedValue) {
            if (members.contains(normalizedValue)) {
                return true;
            }
            for (String prefix : prefixes) {
                if (normalizedValue.equals(prefix)
                        || normalizedValue.startsWith(prefix + " ")) {
                    return true;
                }
            }
            return false;
        }
    }
}
