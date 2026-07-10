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
package ai.kompile.knowledgegraph.service;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Shared node-attribute conventions for aggregation and forecasting: hierarchy-aware type
 * membership (declared type plus the OWL-RL {@code owlInferredTypes} closure), tolerant numeric
 * parsing, timestamp extraction, and graph-scope resolution.
 */
final class NodeAggregationSupport {

    private static final int SCOPE_CAP = 20_000;
    private static final List<String> TIMESTAMP_KEYS = List.of(
            "occurredAt", "occurred_at", "timestamp", "eventDate", "event_date",
            "date", "publishedAt", "published_at", "createdAt", "created_at");

    private NodeAggregationSupport() {
    }

    /** Nodes in scope: a fact sheet when {@code graphId} names one, else all loaded graphs. */
    static List<GraphNode> nodesInScope(KnowledgeGraphService store, String graphId) {
        Long factSheetId = parseFactSheetId(graphId);
        if (factSheetId == null) {
            return store.getAllNodes(SCOPE_CAP);
        }
        List<GraphNode> nodes = new ArrayList<>();
        for (NodeLevel level : NodeLevel.values()) {
            nodes.addAll(store.getNodesByTypeInFactSheet(factSheetId, level));
            if (nodes.size() >= SCOPE_CAP) {
                break;
            }
        }
        return nodes;
    }

    /** {@code "factsheet_42"}, {@code "42"} → 42; anything else → null (all graphs). */
    static Long parseFactSheetId(String graphId) {
        if (graphId == null || graphId.isBlank()) {
            return null;
        }
        String digits = graphId.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Every type name the node belongs to: declared type metadata plus the materialized
     * {@code owlInferredTypes} is-a closure. Lowercased for case-insensitive matching.
     */
    static Set<String> typeMemberships(GraphNode node) {
        Set<String> memberships = new LinkedHashSet<>();
        if (node == null) {
            return memberships;
        }
        Map<String, Object> metadata = node.getMetadata() == null ? Map.of() : node.getMetadata();
        for (String key : List.of("entity_type", "entityType", "type", "memberType")) {
            Object value = metadata.get(key);
            if (value instanceof String text && !text.isBlank()) {
                memberships.add(text.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String key : List.of("owlInferredTypes", "entity_types", "additionalTypes")) {
            Object value = metadata.get(key);
            if (value instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        memberships.add(String.valueOf(item).trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return memberships;
    }

    /** Hierarchy-aware match: the root type equals any of the node's type memberships. */
    static boolean matchesType(GraphNode node, String rootType) {
        return rootType != null
                && typeMemberships(node).contains(rootType.trim().toLowerCase(Locale.ROOT));
    }

    /** The most specific declared type, for per-subtype breakdowns. */
    static String subtype(GraphNode node) {
        Map<String, Object> metadata = node.getMetadata() == null ? Map.of() : node.getMetadata();
        for (String key : List.of("entity_type", "entityType", "type")) {
            Object value = metadata.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN";
    }

    /** Tolerant numeric metadata parse: numbers, or strings with currency/commas/percent. */
    static OptionalDouble numericValue(GraphNode node, String attribute) {
        if (node == null || attribute == null || node.getMetadata() == null) {
            return OptionalDouble.empty();
        }
        Object raw = node.getMetadata().get(attribute);
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        }
        if (raw == null) {
            return OptionalDouble.empty();
        }
        String text = String.valueOf(raw).trim()
                .replace(",", "").replace("$", "").replace("%", "").trim();
        if (text.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** The node's observation date from common timestamp metadata keys. */
    static Optional<LocalDate> observationDate(GraphNode node) {
        if (node == null || node.getMetadata() == null) {
            return Optional.empty();
        }
        for (String key : TIMESTAMP_KEYS) {
            Object raw = node.getMetadata().get(key);
            if (raw == null) {
                continue;
            }
            if (raw instanceof Number number) {
                long epoch = number.longValue();
                long millis = epoch > 100_000_000_000L ? epoch : epoch * 1000L;
                return Optional.of(Instant.ofEpochMilli(millis)
                        .atZone(ZoneOffset.UTC).toLocalDate());
            }
            String text = String.valueOf(raw).trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                return Optional.of(Instant.parse(text).atZone(ZoneOffset.UTC).toLocalDate());
            } catch (Exception notInstant) {
                try {
                    return Optional.of(LocalDate.parse(text.length() > 10
                            ? text.substring(0, 10) : text));
                } catch (Exception notDate) {
                    // fall through to the next key
                }
            }
        }
        return Optional.empty();
    }
}
