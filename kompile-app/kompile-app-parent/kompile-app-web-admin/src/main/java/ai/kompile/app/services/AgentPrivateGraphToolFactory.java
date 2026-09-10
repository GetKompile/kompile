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
package ai.kompile.app.services;

import ai.kompile.agent.graph.AgentGraphRevision;
import ai.kompile.agent.graph.AgentGraphSnapshot;
import ai.kompile.agent.graph.AgentPrivateGraphSession;
import ai.kompile.agent.graph.IndeterminateAgentGraphCommitException;
import ai.kompile.agent.graph.StaleAgentGraphRevisionException;
import ai.kompile.app.services.agent.ProvisionedAgentRuntime;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.react.model.ToolDefinition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Creates one selector-free ReAct tool bound to exactly one private graph session. */
public final class AgentPrivateGraphToolFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AgentPrivateGraphToolFactory.class);
    public static final String TOOL_NAME = "agent_private_graph";
    public static final String TOOL_DESCRIPTION =
            "Read, search, upsert, or retract data in this execution's bound private graph. "
                    + "Writes require the revision returned by a prior read or search.";
    public static final int MAX_RESULTS = 20;
    public static final int MAX_RESULT_CHARACTERS = 24_000;

    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_TYPE_LENGTH = 128;
    private static final int MAX_LABEL_LENGTH = 512;
    private static final int MAX_ATTRIBUTES = 16;
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 512;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,127}");
    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,63}");

    private final ObjectMapper mapper;

    public AgentPrivateGraphToolFactory(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public ToolDefinition create(AgentPrivateGraphSession session) {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameters(schema())
                .parallelizable(false)
                .executor(arguments -> executeBound(session, arguments))
                .build();
    }

    /** Provider-neutral descriptor. Its input schema contains no owner or graph selector. */
    public ProvisionedAgentRuntime.ToolDescriptor descriptor() {
        return new ProvisionedAgentRuntime.ToolDescriptor(
                TOOL_NAME, TOOL_DESCRIPTION, schema(), true);
    }

    /** Execute against the server-bound session; callers cannot replace its owner or agent. */
    public String executeBound(
            AgentPrivateGraphSession session,
            Map<String, Object> arguments) {
        try {
            String action = requiredString(arguments, "action", 32);
            rejectUnexpectedArguments(arguments, action);
            return switch (action) {
                case "read" -> read(session, optionalString(arguments, "query"), limit(arguments));
                case "search" -> read(session, requiredString(arguments, "query", 512), limit(arguments));
                case "upsert_entity" -> upsertEntity(session, arguments);
                case "upsert_relation" -> upsertRelation(session, arguments);
                case "retract_entity" -> retractEntity(session, arguments);
                case "retract_relation" -> retractRelation(session, arguments);
                default -> error(session, "invalid_action", "Unsupported action: " + action);
            };
        } catch (IndeterminateAgentGraphCommitException indeterminate) {
            Map<String, Object> result = base(false, "indeterminate_commit");
            result.put("candidateRevision", indeterminate.candidateRevision().sha256());
            try {
                result.put("currentRevision", session.currentRevision().sha256());
            } catch (IOException rereadFailure) {
                result.put("rereadRequired", true);
            }
            result.put("message", "The write may have committed; reread before retrying.");
            return json(result);
        } catch (StaleAgentGraphRevisionException stale) {
            Map<String, Object> result = base(false, "stale_revision");
            result.put("expectedRevision", stale.expected().sha256());
            result.put("currentRevision", stale.actual().sha256());
            return json(result);
        } catch (IllegalArgumentException invalid) {
            return error(session, "invalid_arguments", invalid.getMessage());
        } catch (IOException ioFailure) {
            return opaqueError(session, "graph_io_error", ioFailure);
        } catch (RuntimeException failure) {
            return opaqueError(session, "graph_operation_failed", failure);
        }
    }

    private String read(AgentPrivateGraphSession session, String query, int limit) throws IOException {
        AgentGraphSnapshot snapshot = session.read();
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);

        List<GraphEntity> entities = new ArrayList<>(snapshot.graph().entities());
        entities.removeIf(entity -> !normalizedQuery.isEmpty()
                && !entitySearchText(entity).contains(normalizedQuery));
        entities.sort(Comparator.comparing(GraphEntity::id));
        entities = entities.subList(0, Math.min(limit, entities.size()));

        Set<String> visibleIds = entities.stream().map(GraphEntity::id)
                .collect(java.util.stream.Collectors.toSet());
        List<GraphRelation> relations = new ArrayList<>(snapshot.graph().relations());
        relations.removeIf(relation -> !visibleIds.contains(relation.sourceId())
                || !visibleIds.contains(relation.targetId()));
        relations.sort(Comparator.comparing(GraphRelation::id));
        relations = relations.subList(0, Math.min(limit, relations.size()));

        Map<String, Object> result = base(true, query == null || query.isBlank() ? "read" : "search");
        result.put("revision", snapshot.revision().sha256());
        result.put("entityCount", snapshot.graph().entityCount());
        result.put("relationCount", snapshot.graph().relationCount());
        List<Map<String, Object>> entityViews = new ArrayList<>(
                entities.stream().map(this::entityView).toList());
        List<Map<String, Object>> relationViews = new ArrayList<>(
                relations.stream().map(this::relationView).toList());
        result.put("entities", entityViews);
        result.put("relations", relationViews);
        return boundedReadJson(result, entityViews, relationViews);
    }

    private String upsertEntity(AgentPrivateGraphSession session, Map<String, Object> arguments)
            throws IOException {
        AgentGraphRevision expected = expectedRevision(arguments);
        String id = validatedId(requiredString(arguments, "entityId", MAX_ID_LENGTH), "entityId");
        String type = validatedType(requiredString(arguments, "type", MAX_TYPE_LENGTH));
        String label = requiredString(arguments, "label", MAX_LABEL_LENGTH);
        Map<String, Object> attributes = validatedAttributes(arguments.get("attributes"));

        AgentGraphRevision revision = session.mutate(expected, graph -> {
            GraphEntity existing = graph.entity(id).orElse(null);
            graph.addEntity(new SimpleGraphEntity(
                    id,
                    type,
                    label,
                    existing == null ? 1.0 : existing.weight(),
                    1.0,
                    existing == null ? Set.of() : existing.tags(),
                    null,
                    existing == null ? null : existing.timestamp(),
                    attributes));
        });
        return writeResult("upsert_entity", revision, "entityId", id);
    }

    private String upsertRelation(AgentPrivateGraphSession session, Map<String, Object> arguments)
            throws IOException {
        AgentGraphRevision expected = expectedRevision(arguments);
        String id = validatedId(requiredString(arguments, "relationId", MAX_ID_LENGTH), "relationId");
        String sourceId = validatedId(requiredString(arguments, "sourceId", MAX_ID_LENGTH), "sourceId");
        String targetId = validatedId(requiredString(arguments, "targetId", MAX_ID_LENGTH), "targetId");
        String type = validatedType(requiredString(arguments, "type", MAX_TYPE_LENGTH));
        double weight = optionalDouble(arguments, "weight", 1.0);
        boolean directed = optionalBoolean(arguments, "directed", true);
        Map<String, Object> attributes = validatedAttributes(arguments.get("attributes"));
        requireProbability(weight, "weight");

        AgentGraphRevision revision = session.mutate(expected, graph -> {
            if (!graph.containsEntity(sourceId) || !graph.containsEntity(targetId)) {
                throw new IllegalArgumentException(
                        "Both relation endpoints must already exist in the bound graph");
            }
            graph.addRelation(new SimpleGraphRelation(
                    id,
                    sourceId,
                    targetId,
                    type,
                    weight,
                    1.0,
                    directed,
                    Set.of(),
                    null,
                    null,
                    attributes));
        });
        return writeResult("upsert_relation", revision, "relationId", id);
    }

    private String retractEntity(AgentPrivateGraphSession session, Map<String, Object> arguments)
            throws IOException {
        AgentGraphRevision expected = expectedRevision(arguments);
        String id = validatedId(requiredString(arguments, "entityId", MAX_ID_LENGTH), "entityId");
        AgentGraphRevision revision = session.mutate(expected, graph -> {
            if (!graph.containsEntity(id)) {
                throw new IllegalArgumentException("Entity does not exist: " + id);
            }
            graph.removeEntityById(id);
        });
        return writeResult("retract_entity", revision, "entityId", id);
    }

    private String retractRelation(AgentPrivateGraphSession session, Map<String, Object> arguments)
            throws IOException {
        AgentGraphRevision expected = expectedRevision(arguments);
        String id = validatedId(requiredString(arguments, "relationId", MAX_ID_LENGTH), "relationId");
        AgentGraphRevision revision = session.mutate(expected, graph -> {
            boolean exists = graph.relations().stream().anyMatch(relation -> id.equals(relation.id()));
            if (!exists) {
                throw new IllegalArgumentException("Relation does not exist: " + id);
            }
            graph.removeRelationById(id);
        });
        return writeResult("retract_relation", revision, "relationId", id);
    }

    private String writeResult(String action, AgentGraphRevision revision, String idField, String id) {
        Map<String, Object> result = base(true, action);
        result.put(idField, id);
        result.put("revision", revision.sha256());
        return json(result);
    }

    private Map<String, Object> entityView(GraphEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", bounded(entity.id(), MAX_ID_LENGTH));
        result.put("type", bounded(entity.type(), MAX_TYPE_LENGTH));
        result.put("label", bounded(entity.label(), MAX_LABEL_LENGTH));
        result.put("confidence", entity.confidence());
        result.put("attributes", boundedAttributes(entity.attributes()));
        return result;
    }

    private Map<String, Object> relationView(GraphRelation relation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", bounded(relation.id(), MAX_ID_LENGTH));
        result.put("sourceId", bounded(relation.sourceId(), MAX_ID_LENGTH));
        result.put("targetId", bounded(relation.targetId(), MAX_ID_LENGTH));
        result.put("type", bounded(relation.type(), MAX_TYPE_LENGTH));
        result.put("weight", relation.weight());
        result.put("confidence", relation.confidence());
        result.put("directed", relation.directed());
        result.put("attributes", boundedAttributes(relation.attributes()));
        return result;
    }

    private Map<String, Object> boundedAttributes(Map<String, Object> attributes) {
        Map<String, Object> bounded = new TreeMap<>();
        attributes.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_ATTRIBUTES)
                .forEach(entry -> bounded.put(
                        bounded(entry.getKey(), 64),
                        bounded(attributeText(entry.getValue()), MAX_ATTRIBUTE_VALUE_LENGTH)));
        return bounded;
    }

    private Map<String, Object> validatedAttributes(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("attributes must be an object");
        }
        if (map.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("attributes exceeds " + MAX_ATTRIBUTES + " entries");
        }
        Map<String, Object> validated = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!ATTRIBUTE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid attribute key: " + key);
            }
            Object value = entry.getValue();
            if (value != null && !(value instanceof String) && !(value instanceof Number)
                    && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("Attribute values must be scalar JSON values");
            }
            if (value instanceof String string && string.length() > MAX_ATTRIBUTE_VALUE_LENGTH) {
                throw new IllegalArgumentException("Attribute value exceeds "
                        + MAX_ATTRIBUTE_VALUE_LENGTH + " characters: " + key);
            }
            validated.put(key, value);
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(validated));
    }

    private String entitySearchText(GraphEntity entity) {
        StringBuilder text = new StringBuilder()
                .append(bounded(entity.id(), MAX_ID_LENGTH)).append(' ')
                .append(bounded(entity.type(), MAX_TYPE_LENGTH)).append(' ')
                .append(bounded(entity.label(), MAX_LABEL_LENGTH)).append(' ');
        entity.attributes().entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_ATTRIBUTES)
                .forEach(entry -> text.append(bounded(entry.getKey(), 64)).append(' ')
                        .append(bounded(attributeText(entry.getValue()),
                                MAX_ATTRIBUTE_VALUE_LENGTH)).append(' '));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private String attributeText(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Set<?> set) {
            return set.stream().map(String::valueOf).sorted()
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException unsupported) {
            return "<" + value.getClass().getSimpleName() + ">";
        }
    }

    private static AgentGraphRevision expectedRevision(Map<String, Object> arguments) {
        return new AgentGraphRevision(requiredString(arguments, "expectedRevision", 64));
    }

    private static void rejectUnexpectedArguments(
            Map<String, Object> arguments,
            String action) {
        Set<String> allowed = switch (action) {
            case "read", "search" -> Set.of("action", "query", "limit");
            case "upsert_entity" -> Set.of(
                    "action", "expectedRevision", "entityId", "type", "label", "attributes");
            case "upsert_relation" -> Set.of(
                    "action", "expectedRevision", "relationId", "sourceId", "targetId", "type",
                    "weight", "directed", "attributes");
            case "retract_entity" -> Set.of("action", "expectedRevision", "entityId");
            case "retract_relation" -> Set.of("action", "expectedRevision", "relationId");
            default -> Set.of("action");
        };
        List<String> unexpected = arguments.keySet().stream()
                .filter(key -> !allowed.contains(key))
                .sorted()
                .toList();
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unexpected argument(s) for " + action + ": " + String.join(", ", unexpected));
        }
    }

    private static String validatedId(String value, String field) {
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match " + ID.pattern());
        }
        return value;
    }

    private static String validatedType(String value) {
        if (!TYPE.matcher(value).matches()) {
            throw new IllegalArgumentException("type must match " + TYPE.pattern());
        }
        return value;
    }

    private static String requiredString(Map<String, Object> arguments, String field, int maximum) {
        Object raw = arguments.get(field);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> arguments, String field) {
        Object raw = arguments.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.length() > 512) {
            throw new IllegalArgumentException(field + " must be a string of at most 512 characters");
        }
        return value;
    }

    private static int limit(Map<String, Object> arguments) {
        Object raw = arguments.get("limit");
        if (raw == null) {
            return 10;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("limit must be an integer");
        }
        int value = number.intValue();
        if (value < 1 || value > MAX_RESULTS || number.doubleValue() != value) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_RESULTS);
        }
        return value;
    }

    private static double optionalDouble(Map<String, Object> arguments, String field, double fallback) {
        Object raw = arguments.get(field);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        return number.doubleValue();
    }

    private static boolean optionalBoolean(
            Map<String, Object> arguments, String field, boolean fallback) {
        Object raw = arguments.get(field);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value;
    }

    private static void requireProbability(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be in [0, 1]");
        }
    }

    private String error(AgentPrivateGraphSession session, String code, String message) {
        Map<String, Object> result = base(false, code);
        result.put("message", Optional.ofNullable(message).orElse(code));
        try {
            result.put("currentRevision", session.currentRevision().sha256());
        } catch (IOException ignored) {
            // The primary error remains deterministic even when the graph cannot be reread.
        }
        return json(result);
    }

    private String opaqueError(
            AgentPrivateGraphSession session,
            String code,
            Exception failure) {
        String reference = UUID.randomUUID().toString();
        LOG.warn("Private graph tool failure reference={} code={}", reference, code, failure);
        Map<String, Object> result = base(false, code);
        result.put("message", "Private graph operation failed; reference=" + reference);
        try {
            result.put("currentRevision", session.currentRevision().sha256());
        } catch (IOException ignored) {
            // The opaque reference remains sufficient when the graph cannot be reread.
        }
        return json(result);
    }

    private String boundedReadJson(
            Map<String, Object> result,
            List<Map<String, Object>> entities,
            List<Map<String, Object>> relations) {
        boolean truncated = false;
        String encoded = json(result);
        while (encoded.length() > MAX_RESULT_CHARACTERS && !relations.isEmpty()) {
            relations.remove(relations.size() - 1);
            truncated = true;
            encoded = json(result);
        }
        while (encoded.length() > MAX_RESULT_CHARACTERS && !entities.isEmpty()) {
            entities.remove(entities.size() - 1);
            truncated = true;
            encoded = json(result);
        }
        if (truncated) {
            result.put("truncated", true);
            encoded = json(result);
        }
        if (encoded.length() > MAX_RESULT_CHARACTERS) {
            throw new IllegalStateException("Bounded private graph result metadata exceeds limit");
        }
        return encoded;
    }

    private static Map<String, Object> base(boolean success, String operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("operation", operation);
        return result;
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not serialize private graph tool result", impossible);
        }
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("read", "search", "upsert_entity", "upsert_relation",
                        "retract_entity", "retract_relation")));
        properties.put("query", Map.of("type", "string", "maxLength", 512));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", MAX_RESULTS));
        properties.put("expectedRevision", Map.of(
                "type", "string", "pattern", "^[0-9a-f]{64}$",
                "description", "Required for every write; obtain it from read or search."));
        properties.put("entityId", Map.of("type", "string", "pattern", ID.pattern()));
        properties.put("relationId", Map.of("type", "string", "pattern", ID.pattern()));
        properties.put("sourceId", Map.of("type", "string", "pattern", ID.pattern()));
        properties.put("targetId", Map.of("type", "string", "pattern", ID.pattern()));
        properties.put("type", Map.of("type", "string", "pattern", TYPE.pattern()));
        properties.put("label", Map.of("type", "string", "maxLength", MAX_LABEL_LENGTH));
        properties.put("weight", Map.of("type", "number", "minimum", 0.0, "maximum", 1.0));
        properties.put("directed", Map.of("type", "boolean"));
        properties.put("attributes", Map.of(
                "type", "object",
                "maxProperties", MAX_ATTRIBUTES,
                "additionalProperties", Map.of(
                        "type", List.of("string", "number", "integer", "boolean", "null"))));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        schema.put("allOf", List.of(
                requiredWhen("search", "query"),
                requiredWhen("upsert_entity", "expectedRevision", "entityId", "type", "label"),
                requiredWhen("upsert_relation", "expectedRevision", "relationId", "sourceId",
                        "targetId", "type"),
                requiredWhen("retract_entity", "expectedRevision", "entityId"),
                requiredWhen("retract_relation", "expectedRevision", "relationId")));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> requiredWhen(String action, String... requiredFields) {
        return Map.of(
                "if", Map.of("properties", Map.of("action", Map.of("const", action))),
                "then", Map.of("required", List.of(requiredFields)));
    }
}
