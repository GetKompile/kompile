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
import java.util.Set;

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
    Set<String> STRUCTURAL_NON_ACTIVITY_TYPES = Set.of(
            "CELL", "HEADER_CELL", "CELL_VALUE", "TABLE_CELL", "FORMULA_CELL", "CELL_COMMENT",
            "NUMERIC_VALUE", "NUMBER", "VALUE", "ROW", "COLUMN", "RANGE", "NAMED_RANGE",
            "TABLE", "SPREADSHEET", "SPREADSHEET_SHEET", "DATA_VALIDATION", "HYPERLINK");

    /**
     * Actor/resource entity types — WHO performs work, never a step of it. On real crawl output
     * (the email extractor emits PERSON per address and ORGANIZATION per sender domain) these are
     * high-degree hubs: as activities they turn every trace into "Person, Email Message, Person,
     * Organization…" soup, and as case-correlation carriers they union every thread that shares an
     * approver into one mega-case. Standard object-centric process mining treats them as resources:
     * excluded from the activity projection here, and their incident edges are excluded from case
     * correlation in {@code EventLogExtractor}. The SAME edges are the resource perspective —
     * {@code ActorResourceObservations} tallies them into per-activity majority performers, which
     * role binding uses as its strongest tier.
     */
    Set<String> ACTOR_RESOURCE_TYPES = Set.of(
            "PERSON", "GOOGLE_PERSON", "EMPLOYEE", "CONTACT", "USER", "AUTHOR", "RECIPIENT",
            "ORGANIZATION", "ORG", "COMPANY", "DEPARTMENT", "TEAM");

    /** True when the type names an actor/resource (see {@link #ACTOR_RESOURCE_TYPES}). */
    static boolean isActorResourceType(String type) {
        return type != null && ACTOR_RESOURCE_TYPES.contains(type.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Communication-carrier activity types. They ARE steps (the flow of a thread is meaningful),
     * but every email-borne workflow contains them, so they carry zero workflow identity: a
     * carriers-only trace (a newsletter) is a Jaccard subset of EVERY cluster and single-link
     * chains unrelated workflows together. {@code TraceClusterer} therefore excludes them from
     * cluster signatures.
     */
    Set<String> COMMUNICATION_SCAFFOLD_TYPES = Set.of(
            "EMAIL_MESSAGE", "EMAIL", "MESSAGE", "CHAT_MESSAGE", "ATTACHMENT", "DOCUMENT", "FILE");

    /** True when the ACTIVITY LABEL (post-{@link #displayLabel}) names a communication carrier. */
    static boolean isCommunicationScaffoldLabel(String label) {
        return label != null && COMMUNICATION_SCAFFOLD_TYPES.contains(
                label.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }

    /** True when the node's {@code entity_type} names an actor/resource. */
    static boolean isActorResource(GraphNode node) {
        if (node == null) {
            return false;
        }
        String declared = ai.kompile.core.graphrag.typing.GraphNodeTypes
                .resolveDeclaredType(node.getMetadata());
        return declared != null && isActorResourceType(declared);
    }

    /** Default: the node's {@code entity_type} (else {@code NodeLevel}); structural spreadsheet types
     *  return {@code null} so they are dropped from the mined process. */
    static ActivityClassifier byEntityType() {
        return node -> {
            if (node == null) {
                return "UNKNOWN";
            }
            Map<String, Object> meta = node.getMetadata();
            // Declared type via the canonical reader (entity_type + camelCase variant) — NOT the
            // category-first resolveEntityType: activities keep the crawl's own declaration and
            // the taxonomy roll-up handles category abstraction explicitly.
            String type = ai.kompile.core.graphrag.typing.GraphNodeTypes.resolveDeclaredType(meta);
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
            String upper = type.toUpperCase(Locale.ROOT);
            if (STRUCTURAL_NON_ACTIVITY_TYPES.contains(upper) || ACTOR_RESOURCE_TYPES.contains(upper)) {
                return null;
            }
            return displayLabel(type);
        };
    }

    /**
     * Normalize a raw {@code entity_type} into a readable activity label:
     * <ul>
     *   <li>Human-authored mixed-case labels without underscores (sheet names like
     *       {@code "Group P&L"}, already-pretty {@code "Email Message"}) pass through unchanged.</li>
     *   <li>Everything machine-shaped — {@code INVOICE}, {@code invoice},
     *       {@code PURCHASE_REQUEST}, {@code entity_entity_number} — is normalized: split on
     *       {@code _}/whitespace, consecutive duplicate tokens collapsed, each word Title-Cased.
     *       Short all-caps tokens (≤3 chars: {@code PO}, {@code HR}, {@code ID}) are kept as
     *       acronyms.</li>
     * </ul>
     *
     * <p>Case variants of the same type ({@code INVOICE} vs {@code Invoice} vs {@code invoice}
     * from different extractors) therefore merge into ONE activity instead of fragmenting the
     * mined process, and suggestions render with one consistent style.</p>
     *
     * <p>Deterministic: the same input always yields the same output, so event-log / DFG keys and
     * the KB atom keys built from these labels stay consistent.</p>
     */
    static String displayLabel(String type) {
        if (type == null || type.isBlank()) {
            return "UNKNOWN";
        }
        String t = type.trim();
        boolean hasLower = t.chars().anyMatch(Character::isLowerCase);
        boolean hasUpper = t.chars().anyMatch(Character::isUpperCase);
        // Human-authored mixed case without underscores: preserve verbatim.
        if (hasLower && hasUpper && !t.contains("_")) {
            return t;
        }
        String[] parts = t.split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String p : parts) {
            if (p.isEmpty() || p.equalsIgnoreCase(prev)) {
                continue; // skip empties + collapse consecutive duplicate tokens (entity_entity → entity)
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (p.length() <= 2) {
                // 1-2 char tokens in machine labels are acronyms/ids (PO, HR, ID, QA) — 3-char
                // tokens are usually real words (JOB, PAY, TAX), so they Title-Case below.
                // Always uppercase so case variants of the same type merge deterministically.
                sb.append(p.toUpperCase(Locale.ROOT));
            } else {
                String lower = p.toLowerCase(Locale.ROOT);
                sb.append(Character.toUpperCase(lower.charAt(0)));
                if (lower.length() > 1) {
                    sb.append(lower.substring(1));
                }
            }
            prev = p;
        }
        return sb.length() > 0 ? sb.toString() : t;
    }
}
