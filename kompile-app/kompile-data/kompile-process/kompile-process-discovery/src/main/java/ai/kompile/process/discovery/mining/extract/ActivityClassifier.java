/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.Locale;
import java.util.Map;

/**
 * Maps a {@link GraphNode} to the activity label it represents in an event log. This is the one place
 * where "what counts as a step" is decided, and it is intentionally pluggable per domain.
 *
 * <p>The default ({@link #byEntityType()}) uses the node's {@code entity_type} (read through the
 * store-agnostic {@link GraphNode#getMetadata()} seam, so it works identically on the JPA and the
 * {@code @Primary} matrix backends), falling back to the structural {@code NodeLevel} when no
 * {@code entity_type} is present.
 */
@FunctionalInterface
public interface ActivityClassifier {

    String activityOf(GraphNode node);

    /**
     * Spreadsheet/structural entity types excluded from process mining — data scaffolding, not
     * business steps. A classifier returning {@code null} drops the event (see EventLogExtractor),
     * so the mined process reflects the real workflow (documents, emails, business entities) instead
     * of cell-flow (CELL → HEADER_CELL → SPREADSHEET).
     */
    java.util.Set<String> STRUCTURAL_NON_ACTIVITY_TYPES = java.util.Set.of(
            "CELL", "HEADER_CELL", "CELL_VALUE", "TABLE_CELL", "FORMULA_CELL", "CELL_COMMENT",
            "NUMERIC_VALUE", "NUMBER", "VALUE", "ROW", "COLUMN", "RANGE", "NAMED_RANGE",
            "TABLE", "SPREADSHEET", "SPREADSHEET_SHEET", "DATA_VALIDATION", "HYPERLINK");

    /** Default: the node's {@code entity_type} (else {@code NodeLevel}); structural spreadsheet types
     *  return {@code null} so they are dropped from the mined process. */
    static ActivityClassifier byEntityType() {
        return node -> {
            if (node == null) {
                return "UNKNOWN";
            }
            String type = null;
            Map<String, Object> meta = node.getMetadata();
            if (meta != null) {
                Object entityType = meta.get("entity_type");
                if (entityType instanceof String s && !s.isBlank()) {
                    type = s.trim();
                }
            }
            if (type == null && meta != null) {
                // No business entity_type (common for spreadsheet-derived entities, whose subtype is a
                // structural "cell"/"header_cell"). Group by the sheet so the discovered process reads
                // as a meaningful section flow ("Group P&L" → "Detail") instead of a wall of generic
                // "ENTITY" steps. sheetName is already human-readable.
                Object sheet = meta.get("sheetName");
                if (sheet instanceof String sn && !sn.isBlank()) {
                    type = sn.trim();
                }
            }
            if (type == null) {
                type = node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN";
            }
            if (STRUCTURAL_NON_ACTIVITY_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
                return null;
            }
            return displayLabel(type);
        };
    }

    /**
     * Normalize a raw {@code entity_type} into a readable activity label. Clean single-token
     * UPPERCASE types (e.g. {@code INVOICE}, the {@code NodeLevel} names) are kept verbatim.
     * Slug / snake_case junk types that leak from extraction (e.g. {@code entity_entity_number})
     * are prettified: split on {@code _}/whitespace, collapse consecutive duplicate tokens
     * (so {@code entity_entity_number} → {@code "Entity Number"}), and Title-Case each word — so a
     * discovered process never shows raw node-id-shaped labels on its activities or edges.
     *
     * <p>Deterministic: the same input always yields the same output, so event-log / DFG keys stay
     * consistent and equivalent junk types merge into one activity.</p>
     */
    static String displayLabel(String type) {
        if (type == null || type.isBlank()) {
            return "UNKNOWN";
        }
        String t = type.trim();
        // Only normalize snake_case / node-id-shaped junk (entity_entity_number → "Entity Number").
        // Already-human labels — clean types like INVOICE, or sheet names like "Group P&L" — pass
        // through unchanged so spaces / mixed case are not mangled.
        if (!t.contains("_")) {
            return t;
        }
        String[] parts = t.toLowerCase(Locale.ROOT).split("_+");
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String p : parts) {
            if (p.isEmpty() || p.equals(prev)) {
                continue; // skip empties + collapse consecutive duplicate tokens (entity_entity → entity)
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
            prev = p;
        }
        return sb.length() > 0 ? sb.toString() : t;
    }
}
