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

import java.util.List;
import java.util.Map;

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
}
