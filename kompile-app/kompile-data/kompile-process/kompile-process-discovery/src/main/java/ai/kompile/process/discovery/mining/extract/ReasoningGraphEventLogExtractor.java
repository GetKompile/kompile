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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects {@link ReasoningGraph} relations into process-mining events.
 *
 * <p>The production {@link EventLogExtractor} handles persisted {@code GraphNode}/{@code GraphEdge}
 * graphs. This adapter gives the same relation-as-event lane to store-agnostic reasoning graphs and
 * hand-built {@code UnifiedGraph} fixtures: event-like relations become activities named from the
 * resolved endpoint types plus relation type, and cases are inferred from non-actor connected
 * components unless explicit case metadata is present.</p>
 */
public final class ReasoningGraphEventLogExtractor {

    private static final List<String> CASE_KEYS = List.of(
            "caseId", "case_id", "traceId", "trace_id", "processInstanceId", "process_instance_id",
            "conversationId", "conversation_id", "threadId", "thread_id", "workflowId", "workflow_id",
            "businessProcessInstanceId", "business_process_instance_id");

    private static final List<String> EVENT_ID_KEYS = List.of(
            "eventId", "event_id", "activityInstanceId", "activity_instance_id",
            "sourceSystemEventId", "source_system_event_id", "systemEventId", "system_event_id",
            "eventUid", "event_uid", "messageId", "message_id");

    private static final List<String> CANONICAL_RELATION_TYPE_KEYS = List.of(
            "canonicalRelationType", "canonical_relation_type", "normalizedRelationType", "normalized_relation_type");

    private static final Set<String> UNPREFIXED_POLICY_ATTRIBUTE_KEYS = Set.of(
            "caseId", "case_id", "traceId", "trace_id", "processInstanceId", "process_instance_id",
            "workflowId", "workflow_id", "businessProcessInstanceId", "business_process_instance_id",
            "eventId", "event_id", "activityInstanceId", "activity_instance_id",
            "sourceSystemEventId", "source_system_event_id", "systemEventId", "system_event_id",
            "sourceSystem", "source_system", "artifactVersion", "artifact_version", "userId", "user_id",
            "actorId", "actor_id", "processName", "stepOrder", "goldStepId", "goldControlId",
            "expectedStepId", "controlId", "routingPolicy", "approvalPolicy", "threshold",
            "confidenceThreshold", "action", "actionType", "gate", "owner", "sla", "slaHours",
            "normalizedRelation", "normalizationAction", "normalizedRelationType", "normalized_relation_type",
            "originalRelationType", "original_relation_type", "canonicalRelationType", "canonical_relation_type",
            "goldPattern");

    private final Set<String> eventRelationTypes;
    private final Set<String> nonEventRelationTypes;

    public ReasoningGraphEventLogExtractor() {
        this(RelationActivityClassifier.DEFAULT_EVENT_RELATION_TYPES,
                RelationActivityClassifier.DEFAULT_NON_EVENT_RELATION_TYPES);
    }

    public ReasoningGraphEventLogExtractor(Set<String> eventRelationTypes, Set<String> nonEventRelationTypes) {
        this.eventRelationTypes = normalizeSet(eventRelationTypes);
        this.nonEventRelationTypes = normalizeSet(nonEventRelationTypes);
    }

    public static ReasoningGraphEventLogExtractor relationEvents() {
        return new ReasoningGraphEventLogExtractor();
    }

    public EventLog extract(ReasoningGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Map<String, GraphEntity> entities = entitiesById(graph);
        List<GraphRelation> eventRelations = graph.relations().stream()
                .filter(this::isEventLike)
                .toList();
        if (eventRelations.isEmpty()) {
            return new EventLog(List.of());
        }

        UnionFind components = nonActorComponents(entities, eventRelations);
        Map<String, List<Event>> byCase = new LinkedHashMap<>();
        int relationOrdinal = 0;
        for (GraphRelation relation : eventRelations) {
            GraphEntity source = entities.get(relation.sourceId());
            GraphEntity target = entities.get(relation.targetId());
            String caseId = caseId(relation, source, target, components);
            if (caseId == null) {
                continue;
            }
            Event event = new Event(caseId,
                    activityLabel(relation, source, target),
                    timestamp(relation, source, target),
                    eventId(relation, source, target, relationOrdinal++),
                    attributes(relation, source, target));
            byCase.computeIfAbsent(caseId, ignored -> new ArrayList<>()).add(event);
        }

        List<Trace> traces = new ArrayList<>();
        for (Map.Entry<String, List<Event>> entry : byCase.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                traces.add(new Trace(entry.getKey(), entry.getValue()));
            }
        }
        traces.sort(Comparator.comparing(Trace::caseId));
        return new EventLog(traces);
    }

    private Map<String, GraphEntity> entitiesById(ReasoningGraph graph) {
        Map<String, GraphEntity> entities = new LinkedHashMap<>();
        for (GraphEntity entity : graph.entities()) {
            entities.put(entity.id(), entity);
        }
        return entities;
    }

    private boolean isEventLike(GraphRelation relation) {
        if (relation == null) {
            return false;
        }
        String effectiveType = effectiveRelationType(relation);
        if (effectiveType == null || effectiveType.isBlank()) {
            return false;
        }
        String type = normalize(effectiveType);
        if (nonEventRelationTypes.contains(type) || type.startsWith("CANDIDATE_")) {
            return false;
        }
        return eventRelationTypes.contains(type) || relation.timestamp() != null;
    }

    private UnionFind nonActorComponents(Map<String, GraphEntity> entities, List<GraphRelation> relations) {
        UnionFind components = new UnionFind();
        for (GraphEntity entity : entities.values()) {
            if (!isActorResource(entity)) {
                components.add(entity.id());
            }
        }
        for (GraphRelation relation : relations) {
            GraphEntity source = entities.get(relation.sourceId());
            GraphEntity target = entities.get(relation.targetId());
            boolean sourceActor = isActorResource(source);
            boolean targetActor = isActorResource(target);
            if (!sourceActor && source != null) {
                components.add(source.id());
            }
            if (!targetActor && target != null) {
                components.add(target.id());
            }
            if (!sourceActor && !targetActor && source != null && target != null) {
                components.union(source.id(), target.id());
            }
        }
        return components;
    }

    private String caseId(GraphRelation relation, GraphEntity source, GraphEntity target, UnionFind components) {
        String explicit = firstCaseId(relation.attributes(), source, target);
        if (explicit != null) {
            return explicit;
        }
        String sourceCase = source == null || isActorResource(source) ? null : components.find(source.id());
        String targetCase = target == null || isActorResource(target) ? null : components.find(target.id());
        boolean sourceActor = isActorResource(source);
        boolean targetActor = isActorResource(target);
        if (sourceActor && targetActor) {
            return null;
        }
        if (sourceCase != null && sourceCase.equals(targetCase)) {
            return "case:" + sourceCase;
        }
        if (sourceActor && targetCase != null) {
            return "case:" + targetCase;
        }
        if (targetActor && sourceCase != null) {
            return "case:" + sourceCase;
        }
        if (sourceCase != null && targetCase == null) {
            return "case:" + sourceCase;
        }
        if (targetCase != null && sourceCase == null) {
            return "case:" + targetCase;
        }
        return relation.id() == null || relation.id().isBlank() ? null : "relation:" + relation.id();
    }

    private static String firstCaseId(Map<String, Object> relationAttributes, GraphEntity source, GraphEntity target) {
        String fromRelation = firstString(relationAttributes, CASE_KEYS);
        if (fromRelation != null) {
            return fromRelation;
        }
        String fromSource = source == null ? null : firstString(source.attributes(), CASE_KEYS);
        if (fromSource != null) {
            return fromSource;
        }
        return target == null ? null : firstString(target.attributes(), CASE_KEYS);
    }

    private static String firstString(Map<String, Object> attributes, List<String> keys) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = attributes.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static String activityLabel(GraphRelation relation, GraphEntity source, GraphEntity target) {
        return RelationActivityClassifier.activityLabel(bestType(source), effectiveRelationType(relation), bestType(target));
    }

    private static String effectiveRelationType(GraphRelation relation) {
        String canonical = firstString(relation.attributes(), CANONICAL_RELATION_TYPE_KEYS);
        return canonical == null ? relation.type() : canonical;
    }

    private static String bestType(GraphEntity entity) {
        if (entity == null) {
            return "UNKNOWN";
        }
        String first = null;
        for (String type : entity.typeMemberships()) {
            if (type == null || type.isBlank()) {
                continue;
            }
            if (first == null) {
                first = type;
            }
            if (!isGenericType(type)) {
                return type;
            }
        }
        if (first != null) {
            return first;
        }
        return entity.type() == null || entity.type().isBlank() ? "UNKNOWN" : entity.type();
    }

    private static boolean isGenericType(String type) {
        String normalized = normalize(type);
        return normalized.equals("ENTITY")
                || normalized.equals("NODE")
                || normalized.equals("GRAPH_NODE")
                || normalized.equals("OBJECT")
                || normalized.equals("RESOURCE")
                || normalized.equals("UNKNOWN");
    }

    private static boolean isActorResource(GraphEntity entity) {
        if (entity == null) {
            return false;
        }
        for (String type : entity.typeMemberships()) {
            if (ActivityClassifier.isActorResourceType(type)) {
                return true;
            }
        }
        return false;
    }

    private static LocalDateTime timestamp(GraphRelation relation, GraphEntity source, GraphEntity target) {
        if (relation.timestamp() != null) {
            return LocalDateTime.ofInstant(relation.timestamp(), ZoneOffset.UTC);
        }
        LocalDateTime sourceTime = source == null || source.timestamp() == null
                ? null : LocalDateTime.ofInstant(source.timestamp(), ZoneOffset.UTC);
        LocalDateTime targetTime = target == null || target.timestamp() == null
                ? null : LocalDateTime.ofInstant(target.timestamp(), ZoneOffset.UTC);
        return earliest(sourceTime, targetTime);
    }

    private static LocalDateTime earliest(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static String eventId(GraphRelation relation, GraphEntity source, GraphEntity target, int ordinal) {
        String explicit = firstEventId(relation.attributes(), source, target);
        if (explicit != null) {
            return explicit;
        }
        if (relation.id() != null && !relation.id().isBlank()) {
            return relation.id();
        }
        return "relation-event:" + ordinal;
    }

    private static String firstEventId(Map<String, Object> relationAttributes, GraphEntity source, GraphEntity target) {
        String fromRelation = firstString(relationAttributes, EVENT_ID_KEYS);
        if (fromRelation != null) {
            return fromRelation;
        }
        String fromSource = source == null ? null : firstString(source.attributes(), EVENT_ID_KEYS);
        if (fromSource != null) {
            return fromSource;
        }
        return target == null ? null : firstString(target.attributes(), EVENT_ID_KEYS);
    }

    private static Map<String, Object> attributes(GraphRelation relation, GraphEntity source, GraphEntity target) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, "eventProjection", "RELATION");
        put(attributes, "relationType", effectiveRelationType(relation));
        put(attributes, "rawRelationType", relation.type());
        put(attributes, "sourceNodeId", relation.sourceId());
        put(attributes, "targetNodeId", relation.targetId());
        put(attributes, "sourceType", bestType(source));
        put(attributes, "targetType", bestType(target));
        attributes.put("weight", relation.weight());
        attributes.put("confidence", relation.confidence());
        copySelectedUnprefixed(attributes, relation.attributes());
        copyPrefixed(attributes, "relation.", relation.attributes());
        if (source != null) {
            copyPrefixed(attributes, "source.", source.attributes());
        }
        if (target != null) {
            copyPrefixed(attributes, "target.", target.attributes());
        }
        return attributes;
    }

    private static void copySelectedUnprefixed(Map<String, Object> out, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (String key : UNPREFIXED_POLICY_ATTRIBUTE_KEYS) {
            Object value = source.get(key);
            if (value != null) {
                out.putIfAbsent(key, value);
            }
        }
    }

    private static void copyPrefixed(Map<String, Object> out, String prefix, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && !key.isBlank() && value != null) {
                out.putIfAbsent(prefix + key, value);
            }
        }
    }

    private static void put(Map<String, Object> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }

    private static Set<String> normalizeSet(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(normalize(value));
                }
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new LinkedHashMap<>();

        void add(String id) {
            if (id != null && !id.isBlank()) {
                parent.putIfAbsent(id, id);
            }
        }

        void union(String left, String right) {
            add(left);
            add(right);
            String leftRoot = find(left);
            String rightRoot = find(right);
            if (leftRoot == null || rightRoot == null || leftRoot.equals(rightRoot)) {
                return;
            }
            String root = leftRoot.compareTo(rightRoot) <= 0 ? leftRoot : rightRoot;
            String child = root.equals(leftRoot) ? rightRoot : leftRoot;
            parent.put(child, root);
        }

        String find(String id) {
            if (id == null || !parent.containsKey(id)) {
                return null;
            }
            String current = id;
            while (!current.equals(parent.get(current))) {
                current = parent.get(current);
            }
            String root = current;
            current = id;
            while (!current.equals(parent.get(current))) {
                String next = parent.get(current);
                parent.put(current, root);
                current = next;
            }
            return root;
        }
    }
}
