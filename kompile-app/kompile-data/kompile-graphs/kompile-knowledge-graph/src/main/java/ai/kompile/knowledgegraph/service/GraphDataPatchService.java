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
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based patching for persisted graph data.
 *
 * This intentionally keeps business-domain categories in request data instead
 * of hard-coding them into entity resolution.
 */
@Service
public class GraphDataPatchService {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int SAMPLE_LIMIT = 25;

    private GraphNodeRepository nodeRepository;
    private ObjectMapper objectMapper;

    public GraphDataPatchService(GraphNodeRepository nodeRepository, ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.objectMapper = objectMapper;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected GraphDataPatchService() {}


    @Transactional
    public PatchResult patchNodeMetadata(PatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Patch request is required");
        }
        List<PatchRule> rules = nonNullRules(request.rules());
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("At least one patch rule is required");
        }
        if (request.factSheetId() == null && !Boolean.TRUE.equals(request.allowGlobal())) {
            throw new IllegalArgumentException("factSheetId is required unless allowGlobal is true");
        }

        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        Integer updateLimit = request.limit() != null && request.limit() > 0 ? request.limit() : null;
        Set<NodeLevel> nodeTypes = nodeTypesFor(rules);

        int scannedCount = 0;
        int matchedCount = 0;
        int changedCount = 0;
        int updatedCount = 0;
        int unchangedCount = 0;
        int skippedByLimitCount = 0;
        List<PatchSample> samples = new ArrayList<>();

        for (NodeLevel nodeType : nodeTypes) {
            List<GraphNode> nodes = request.factSheetId() != null
                    ? nodeRepository.findByFactSheetIdAndNodeType(request.factSheetId(), nodeType)
                    : nodeRepository.findByNodeType(nodeType);

            for (GraphNode node : nodes) {
                scannedCount++;
                LinkedHashMap<String, Object> before = parseMetadata(node.getMetadataJson());
                PatchOutcome outcome = applyMatchingRules(node, before, rules);
                if (outcome.matchedRuleNames().isEmpty()) {
                    continue;
                }

                matchedCount++;
                if (!outcome.changed()) {
                    unchangedCount++;
                    continue;
                }

                changedCount++;
                boolean withinLimit = updateLimit == null || changedCount <= updateLimit;
                if (!withinLimit) {
                    skippedByLimitCount++;
                    continue;
                }

                if (samples.size() < SAMPLE_LIMIT) {
                    samples.add(new PatchSample(
                            node.getNodeId(),
                            node.getTitle(),
                            List.copyOf(outcome.matchedRuleNames()),
                            before,
                            outcome.afterMetadata()
                    ));
                }

                if (!dryRun) {
                    node.setMetadataJson(serializeMetadata(outcome.afterMetadata()));
                    nodeRepository.save(node);
                    updatedCount++;
                }
            }
        }

        return new PatchResult(
                dryRun,
                request.factSheetId(),
                Boolean.TRUE.equals(request.allowGlobal()),
                scannedCount,
                matchedCount,
                changedCount,
                updatedCount,
                unchangedCount,
                skippedByLimitCount,
                samples
        );
    }

    private List<PatchRule> nonNullRules(List<PatchRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.stream()
                .filter(Objects::nonNull)
                .peek(this::validateRule)
                .toList();
    }

    private void validateRule(PatchRule rule) {
        boolean hasSet = rule.setMetadata() != null && !rule.setMetadata().isEmpty();
        boolean hasRemove = rule.removeMetadataKeys() != null && !rule.removeMetadataKeys().isEmpty();
        if (!hasSet && !hasRemove) {
            throw new IllegalArgumentException("Patch rule must set or remove metadata: " + ruleName(rule));
        }
        if (rule.titleRegex() != null && !rule.titleRegex().isBlank()) {
            Pattern.compile(rule.titleRegex());
        }
    }

    private Set<NodeLevel> nodeTypesFor(List<PatchRule> rules) {
        Set<NodeLevel> result = new LinkedHashSet<>();
        for (PatchRule rule : rules) {
            String rawType = rule.nodeType();
            result.add(rawType == null || rawType.isBlank()
                    ? NodeLevel.ENTITY
                    : NodeLevel.valueOf(rawType.trim().toUpperCase(Locale.ROOT)));
        }
        return result;
    }

    private PatchOutcome applyMatchingRules(GraphNode node,
                                            LinkedHashMap<String, Object> before,
                                            List<PatchRule> rules) {
        LinkedHashMap<String, Object> after = deepCopy(before);
        List<String> matchedRuleNames = new ArrayList<>();

        for (PatchRule rule : rules) {
            NodeLevel ruleType = rule.nodeType() == null || rule.nodeType().isBlank()
                    ? NodeLevel.ENTITY
                    : NodeLevel.valueOf(rule.nodeType().trim().toUpperCase(Locale.ROOT));
            if (node.getNodeType() != ruleType || !matchesRule(node, before, rule)) {
                continue;
            }
            matchedRuleNames.add(ruleName(rule));
            if (rule.setMetadata() != null) {
                rule.setMetadata().forEach((key, value) -> setPath(after, key, value));
            }
            if (rule.removeMetadataKeys() != null) {
                rule.removeMetadataKeys().forEach(key -> removePath(after, key));
            }
        }

        return new PatchOutcome(!Objects.equals(before, after), after, matchedRuleNames);
    }

    private boolean matchesRule(GraphNode node, Map<String, Object> metadata, PatchRule rule) {
        if (rule.entityType() != null && !rule.entityType().isBlank()) {
            String actualType = extractEntityType(metadata);
            if (!normalizedToken(rule.entityType()).equals(normalizedToken(actualType))) {
                return false;
            }
        }

        if (rule.titleEquals() != null && !rule.titleEquals().isEmpty()) {
            String title = node.getTitle();
            boolean titleMatches = title != null && rule.titleEquals().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(expected -> title.equals(expected));
            if (!titleMatches) {
                return false;
            }
        }

        if (rule.titleRegex() != null && !rule.titleRegex().isBlank()) {
            String title = node.getTitle();
            if (title == null || !Pattern.compile(rule.titleRegex()).matcher(title).find()) {
                return false;
            }
        }

        if (rule.metadataExists() != null) {
            for (String key : rule.metadataExists()) {
                if (key == null || readPath(metadata, key).isEmpty()) {
                    return false;
                }
            }
        }

        if (rule.metadataEquals() != null) {
            for (Map.Entry<String, Object> entry : rule.metadataEquals().entrySet()) {
                Optional<Object> actual = readPath(metadata, entry.getKey());
                if (actual.isEmpty() || !metadataValuesEqual(actual.get(), entry.getValue())) {
                    return false;
                }
            }
        }

        return true;
    }

    private String extractEntityType(Map<String, Object> metadata) {
        List<String> keys = List.of(
                "entity_category",
                "entityCategory",
                "custom_category",
                "customCategory",
                "resolution_category",
                "resolutionCategory",
                "entity_type",
                "entityType",
                "properties.entity_category",
                "properties.entityCategory",
                "properties.custom_category",
                "properties.customCategory",
                "properties.resolution_category",
                "properties.resolutionCategory",
                "properties.entity_type",
                "properties.entityType"
        );
        for (String key : keys) {
            Optional<Object> value = readPath(metadata, key);
            if (value.isPresent() && value.get() != null && !value.get().toString().isBlank()) {
                return value.get().toString();
            }
        }
        return null;
    }

    private boolean metadataValuesEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (actual instanceof String || expected instanceof String) {
            return actual.toString().equals(expected.toString());
        }
        return Objects.equals(actual, expected) || actual.toString().equals(expected.toString());
    }

    private String normalizedToken(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String ruleName(PatchRule rule) {
        return rule.name() == null || rule.name().isBlank() ? "unnamed-rule" : rule.name();
    }

    private Optional<Object> readPath(Map<String, Object> metadata, String path) {
        if (metadata == null || path == null || path.isBlank()) {
            return Optional.empty();
        }
        String[] parts = path.split("\\.");
        Object cursor = metadata;
        for (String part : parts) {
            if (!(cursor instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return Optional.empty();
            }
            cursor = map.get(part);
        }
        return Optional.ofNullable(cursor);
    }

    @SuppressWarnings("unchecked")
    private void setPath(Map<String, Object> metadata, String path, Object value) {
        if (path == null || path.isBlank()) {
            return;
        }
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = metadata;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private void removePath(Map<String, Object> metadata, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = metadata;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                return;
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.remove(parts[parts.length - 1]);
    }

    private LinkedHashMap<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, Object> parsed = objectMapper.readValue(metadataJson, MAP_TYPE);
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to parse node metadata JSON", e);
        }
    }

    private LinkedHashMap<String, Object> deepCopy(Map<String, Object> metadata) {
        return objectMapper.convertValue(metadata, MAP_TYPE);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize patched metadata", e);
        }
    }

    private record PatchOutcome(
            boolean changed,
            LinkedHashMap<String, Object> afterMetadata,
            List<String> matchedRuleNames
    ) {}

    public record PatchRequest(
            Long factSheetId,
            Boolean allowGlobal,
            Boolean dryRun,
            Integer limit,
            List<PatchRule> rules
    ) {}

    public record PatchRule(
            String name,
            String nodeType,
            String entityType,
            List<String> titleEquals,
            String titleRegex,
            Map<String, Object> metadataEquals,
            List<String> metadataExists,
            Map<String, Object> setMetadata,
            List<String> removeMetadataKeys
    ) {}

    public record PatchResult(
            boolean dryRun,
            Long factSheetId,
            boolean allowGlobal,
            int scannedCount,
            int matchedCount,
            int changedCount,
            int updatedCount,
            int unchangedCount,
            int skippedByLimitCount,
            List<PatchSample> samples
    ) {}

    public record PatchSample(
            String nodeId,
            String title,
            List<String> matchedRules,
            Map<String, Object> beforeMetadata,
            Map<String, Object> afterMetadata
    ) {}
}
