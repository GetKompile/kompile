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
package ai.kompile.core.graphrag.typing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical resolution of a knowledge-graph node's semantic entity type from its metadata map.
 *
 * <p>The graph stores the semantic type under several keys depending on which producer wrote the
 * node ({@code entity_category}, {@code entity_type}, {@code entity_subtype}, plus camelCase
 * variants). Historically each consumer re-implemented this lookup with a different precedence —
 * notably the enrichment normalizer read only {@code entity_type} and so missed types stored under
 * {@code entity_category}. This is the single agreed precedence so enrichment, ontology conformance,
 * and compaction all resolve a node's type identically.
 *
 * <p>Operates on the store-agnostic metadata {@link Map} (e.g. {@code GraphNode.getMetadata()}), so
 * it carries no dependency on the graph module and lives in {@code kompile-app-core}.
 */
public final class GraphNodeTypes {

    private GraphNodeTypes() {}

    private static final List<String> CATEGORY_KEYS =
            List.of("entity_category", "entityCategory", "resolution_category", "resolutionCategory");
    private static final List<String> TYPE_KEYS = List.of("entity_type", "entityType");
    private static final List<String> SUBTYPE_KEYS = List.of("entity_subtype", "entitySubtype");

    /**
     * The metadata keys carrying an OWL is-a CLOSURE (most-specific-first list, e.g.
     * {@code [Chianti, RedWine, Wine]}), as written by OWL classification
     * ({@code owlInferredTypes}) and its historical variants. The SINGLE definition — hierarchy
     * retrieval ({@code matchesEntityType}), aggregation, and process-mining taxonomy roll-up all
     * read these same keys; per-consumer copies of this list drift.
     */
    public static final List<String> TYPE_CLOSURE_KEYS = List.of(
            "owlInferredTypes", "ontology.inferredTypes", "owl.inferredTypes",
            "inferredTypes", "inferred_types", "typeClosure");

    /** Specific-to-broad is-a edge extracted from crawl type metadata. */
    public record TypeHierarchyEdge(String type, String parentType, String typeKey, String parentKey) {
    }

    /**
     * The node's materialized is-a closure from the first populated {@link #TYPE_CLOSURE_KEYS}
     * entry (blank elements skipped); empty when OWL classification never touched the node.
     */
    public static List<String> resolveTypeClosure(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        for (String key : TYPE_CLOSURE_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                List<String> closure = new ArrayList<>(list.size());
                for (Object element : list) {
                    if (element instanceof String s && !s.isBlank()) {
                        closure.add(s.trim());
                    }
                }
                if (!closure.isEmpty()) {
                    return List.copyOf(closure);
                }
            }
        }
        return List.of();
    }

    /**
     * The DECLARED entity type only ({@code entity_type}/{@code entityType}) — no category or
     * subtype fallback. This is the reading activity classification, actor typing, and relation
     * domain/range induction want: the crawl's own type declaration, with
     * {@link #resolveEntityType} reserved for the category-first conformance precedence.
     */
    public static String resolveDeclaredType(Map<String, Object> metadata) {
        return firstNonBlank(metadata, TYPE_KEYS);
    }

    /**
     * Resolve the semantic entity type from a node's metadata, in precedence order:
     * category keys → type keys → subtype keys → {@code fallback}.
     *
     * @param metadata the node's metadata map (may be {@code null} or empty)
     * @param fallback value returned when no type key is present (e.g. the structural NodeLevel name, or {@code null})
     * @return the resolved type, or {@code fallback} when none is present
     */
    public static String resolveEntityType(Map<String, Object> metadata, String fallback) {
        String value = firstNonBlank(metadata, CATEGORY_KEYS);
        if (value == null) {
            value = firstNonBlank(metadata, TYPE_KEYS);
        }
        if (value == null) {
            value = firstNonBlank(metadata, SUBTYPE_KEYS);
        }
        return value != null ? value : fallback;
    }

    /**
     * Return crisp type memberships present on a graph node, ordered from most specific to broadest.
     *
     * <p>Unlike {@link #resolveEntityType(Map, String)}, which preserves the legacy category-first
     * conformance type, this method is intended for reasoning. If all three crawl keys are present,
     * the order is {@code entity_subtype}, {@code entity_type}, {@code entity_category}.</p>
     */
    public static List<String> resolveTypeMemberships(Map<String, Object> metadata) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        addIfPresent(types, firstNonBlank(metadata, SUBTYPE_KEYS));
        addIfPresent(types, firstNonBlank(metadata, TYPE_KEYS));
        addIfPresent(types, firstNonBlank(metadata, CATEGORY_KEYS));
        return List.copyOf(types);
    }

    /**
     * Extract specific-to-broad hierarchy links from crawl metadata. Examples:
     * {@code entity_type=RedWine, entity_category=Wine} yields {@code RedWine -> Wine};
     * {@code entity_subtype=Cabernet, entity_type=RedWine} yields {@code Cabernet -> RedWine}.
     */
    public static List<TypeHierarchyEdge> resolveTypeHierarchy(Map<String, Object> metadata) {
        String category = firstNonBlank(metadata, CATEGORY_KEYS);
        String type = firstNonBlank(metadata, TYPE_KEYS);
        String subtype = firstNonBlank(metadata, SUBTYPE_KEYS);

        List<TypeHierarchyEdge> hierarchy = new ArrayList<>(2);
        if (different(subtype, type)) {
            hierarchy.add(new TypeHierarchyEdge(subtype, type, "entity_subtype", "entity_type"));
        }
        if (different(type, category)) {
            hierarchy.add(new TypeHierarchyEdge(type, category, "entity_type", "entity_category"));
        } else if (type == null && different(subtype, category)) {
            hierarchy.add(new TypeHierarchyEdge(subtype, category, "entity_subtype", "entity_category"));
        }
        return List.copyOf(hierarchy);
    }

    private static String firstNonBlank(Map<String, Object> metadata, List<String> keys) {
        if (metadata == null) {
            return null;
        }
        for (String key : keys) {
            Object o = metadata.get(key);
            if (o instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static void addIfPresent(Set<String> types, String value) {
        if (value != null && !value.isBlank()) {
            types.add(value);
        }
    }

    private static boolean different(String child, String parent) {
        return child != null && parent != null && !child.equalsIgnoreCase(parent);
    }
}
