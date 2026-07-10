/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Graph-derived member vocabulary for one dimension key.
 *
 * <p>Members come from two evidence sources only: explicit dimension values already stamped on
 * entities, and member tables — text cells sitting under a column header that names the dimension
 * (a "Region" column listing AMER, EMEA, APAC). Delimited lists are excluded because those are
 * alias rows, not members. The result is the closed candidate set handed to a
 * {@link DimensionAliasResolver}, which keeps that resolution a bounded selection task rather than
 * open-ended generation.</p>
 */
public final class DimensionVocabulary {

    private static final int MAX_MEMBERS = 24;
    private static final int MAX_MEMBER_LENGTH = 48;

    private DimensionVocabulary() {
    }

    public static List<String> members(ReasoningGraph graph, String dimensionKey) {
        Objects.requireNonNull(graph, "graph");
        if (QuantitativeGraphSupport.blank(dimensionKey)) {
            return List.of();
        }
        Map<String, String> byNormalized = new LinkedHashMap<>();
        for (GraphEntity entity : graph.entities()) {
            addExplicitValue(byNormalized, entity, dimensionKey);
            addTabularMember(byNormalized, entity, dimensionKey);
            addTypedTableMember(byNormalized, entity, dimensionKey);
            if (byNormalized.size() >= MAX_MEMBERS) {
                break;
            }
        }
        return List.copyOf(byNormalized.values());
    }

    /**
     * Typed members projected from inferred master tables ("Corp SKU" table rows typed SKU)
     * contribute their key and display name when any of their type memberships names the
     * dimension. Memberships include OWL-inferred superclasses ({@code owlInferredTypes}), so a
     * declared {@code SKU rdfs:subClassOf Product} axiom makes SKU members answer for the
     * "product" dimension through the schema rather than lexical luck.
     */
    private static void addTypedTableMember(
            Map<String, String> members, GraphEntity entity, String dimensionKey) {
        if (!tableMember(entity)) {
            return;
        }
        Set<String> keyTokens = QuantitativeGraphSupport.tokens(dimensionKey);
        Set<String> memberships = new java.util.LinkedHashSet<>(entity.typeMemberships());
        String memberType = QuantitativeGraphSupport.firstString(entity, "memberType");
        if (memberType != null) {
            memberships.add(memberType);
        }
        boolean matches = false;
        for (String membership : memberships) {
            for (String token : QuantitativeGraphSupport.tokens(membership)) {
                if (QuantitativeGraphSupport.matchesAnyToken(token, keyTokens)) {
                    matches = true;
                    break;
                }
            }
            if (matches) {
                break;
            }
        }
        if (!matches) {
            return;
        }
        add(members, QuantitativeGraphSupport.firstString(entity, "memberKey"));
        add(members, entity.label());
    }

    static boolean tableMember(GraphEntity entity) {
        Object marker = entity.attributes().get("tableMember");
        return Boolean.TRUE.equals(marker)
                || "true".equalsIgnoreCase(String.valueOf(marker));
    }

    private static void addExplicitValue(
            Map<String, String> members, GraphEntity entity, String dimensionKey) {
        for (Map.Entry<String, String> dimension
                : QuantitativeGraphSupport.dimensions(entity).entrySet()) {
            if (dimension.getKey().equalsIgnoreCase(dimensionKey)) {
                add(members, dimension.getValue());
            }
        }
    }

    private static void addTabularMember(
            Map<String, String> members, GraphEntity entity, String dimensionKey) {
        String cellType = QuantitativeGraphSupport.normalizeType(
                QuantitativeGraphSupport.firstString(entity, "cellType", "valueType"));
        if (!cellType.contains("STRING")) {
            return;
        }
        String columnLabel = QuantitativeGraphSupport.firstString(entity, "columnLabel");
        String value = QuantitativeGraphSupport.firstString(entity, "displayValue");
        if (columnLabel == null || value == null
                || value.length() > MAX_MEMBER_LENGTH
                || value.contains(",") || value.contains(";")
                || !headerMatches(columnLabel, dimensionKey)) {
            return;
        }
        add(members, value);
    }

    /** A column header names the dimension when one of its tokens matches the key. */
    private static boolean headerMatches(String columnLabel, String dimensionKey) {
        Set<String> keyTokens = QuantitativeGraphSupport.tokens(dimensionKey);
        if (keyTokens.isEmpty()) {
            return false;
        }
        for (String token : QuantitativeGraphSupport.tokens(columnLabel)) {
            if (QuantitativeGraphSupport.matchesAnyToken(token, keyTokens)) {
                return true;
            }
        }
        return false;
    }

    private static void add(Map<String, String> members, String value) {
        if (QuantitativeGraphSupport.blank(value)) {
            return;
        }
        String normalized = QuantitativeGraphSupport.normalizeText(value);
        if (!normalized.isEmpty()) {
            members.putIfAbsent(normalized, value.trim());
        }
    }
}
