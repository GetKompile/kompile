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

import ai.kompile.core.graphrag.typing.GraphNodeTypes;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps a graph relation to a process-mining activity label when the relation is itself the event.
 *
 * <p>Crawlers often write the event observation as an edge: an email was SENT_BY a person, an email
 * HAS_ATTACHMENT a workbook, a control VALIDATES a step. Node-only event projection loses that lane.
 * This classifier consumes the same semantic type metadata used by ontology conformance and type
 * roll-up ({@link GraphNodeTypes}) so relation events are named by resolved endpoint types plus the
 * semantic relation type, e.g. {@code Email Message Sent By Person}.</p>
 */
@FunctionalInterface
public interface RelationActivityClassifier {

    Set<String> DEFAULT_EVENT_RELATION_TYPES = Set.of(
            "SENT_BY", "SENT_TO", "CC_TO", "BCC_TO", "REPLIED_TO", "REFERENCES",
            "HAS_ATTACHMENT", "SUBMITTED_BY", "FEEDS_INTO", "VALIDATES", "APPROVED_BY",
            "TRIGGERS", "APPLIES_ADJUSTMENT", "SOURCE_OF", "PUBLISHES", "ARCHIVES",
            "ESCALATED_TO", "ASSIGNED_TO", "PERFORMED_BY");

    Set<String> DEFAULT_NON_EVENT_RELATION_TYPES = Set.of(
            "RELATED_TO", "SAME_AS", "BELONGS_TO", "PART_OF", "CONTAINS", "REFERENCES_TAXONOMY",
            "DIRECTLY_FOLLOWS", "PRECEDES");

    Set<String> RELATION_ACRONYM_TOKENS = Set.of("CC", "BCC", "ID", "URL", "URI", "API");

    String activityOf(GraphEdge edge, GraphNode source, GraphNode target);

    static RelationActivityClassifier none() {
        return (edge, source, target) -> null;
    }

    static RelationActivityClassifier byResolvedTypes() {
        return (edge, source, target) -> {
            if (!isEventLike(edge)) {
                return null;
            }
            String relationType = relationType(edge);
            String sourceType = resolvedType(source);
            String targetType = resolvedType(target);
            if (relationType == null || sourceType == null || targetType == null) {
                return null;
            }
            return activityLabel(sourceType, relationType, targetType);
        };
    }

    static boolean isEventLike(GraphEdge edge) {
        String relationType = relationType(edge);
        if (relationType == null) {
            return false;
        }
        String normalized = relationType.trim().toUpperCase(Locale.ROOT);
        if (DEFAULT_NON_EVENT_RELATION_TYPES.contains(normalized)) {
            return false;
        }
        return DEFAULT_EVENT_RELATION_TYPES.contains(normalized) || edge.getOccurredAt() != null;
    }

    static String activityLabel(String sourceType, String relationType, String targetType) {
        return ActivityClassifier.displayLabel(sourceType) + " "
                + displayRelationLabel(relationType) + " "
                + ActivityClassifier.displayLabel(targetType);
    }

    static String displayRelationLabel(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "UNKNOWN";
        }
        String[] parts = relationType.trim().split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        String previous = null;
        for (String part : parts) {
            if (part.isEmpty() || part.equalsIgnoreCase(previous)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String upper = part.toUpperCase(Locale.ROOT);
            if (RELATION_ACRONYM_TOKENS.contains(upper)) {
                sb.append(upper);
            } else {
                String lower = part.toLowerCase(Locale.ROOT);
                sb.append(Character.toUpperCase(lower.charAt(0)));
                if (lower.length() > 1) {
                    sb.append(lower.substring(1));
                }
            }
            previous = part;
        }
        return sb.length() > 0 ? sb.toString() : relationType.trim();
    }

    static String relationType(GraphEdge edge) {
        if (edge == null || edge.getRelationType() == null || edge.getRelationType().isBlank()) {
            return null;
        }
        return edge.getRelationType().trim();
    }

    static String resolvedType(GraphNode node) {
        if (node == null) {
            return null;
        }
        Map<String, Object> metadata = node.getMetadata();
        String declared = GraphNodeTypes.resolveDeclaredType(metadata);
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        String fallback = node.getNodeType() != null ? node.getNodeType().name() : null;
        String resolved = GraphNodeTypes.resolveEntityType(metadata, fallback);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return null;
    }
}
