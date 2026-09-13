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

package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.format.LlmJsonExtractor;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CorpusSchemaResponseParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CorpusSchemaResponseParser() {
    }

    record ParseResult(
            GraphSchema schema,
            List<String> errors) {

        ParseResult {
            errors = errors == null
                    ? List.of()
                    : List.copyOf(errors);
        }

        boolean valid() {
            return schema != null && errors.isEmpty();
        }
    }

    record NodeEvidence(String sourceId, String quote) {}

    /** Evidence is retained separately from proposal identity and is not a semantic truth verdict. */
    record NodeDiscoveryResult(ParseResult parsed,
            Map<CorpusSchemaUnifier.TypeProposal, List<NodeEvidence>> evidence) {
        NodeDiscoveryResult {
            Map<CorpusSchemaUnifier.TypeProposal, List<NodeEvidence>> copy = new LinkedHashMap<>();
            evidence.forEach((proposal, spans) -> copy.put(proposal, List.copyOf(spans)));
            evidence = java.util.Collections.unmodifiableMap(copy);
        }
    }

    static NodeDiscoveryResult parseNodeDiscovery(Map<String, Object> arguments,
            Map<String, String> submittedWindows, java.util.Set<String> authoritativeLabels) {
        try {
            if (arguments == null || !arguments.keySet().equals(java.util.Set.of("nodeTypes"))
                    || !(arguments.get("nodeTypes") instanceof List<?> rows) || rows.size() > 32) {
                throw new IllegalArgumentException("nodeTypes must be the only field, an array of at most 32 objects");
            }
            List<Map<String, String>> definitions = new ArrayList<>();
            Map<CorpusSchemaUnifier.TypeProposal, List<NodeEvidence>> evidence = new LinkedHashMap<>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> fields)
                        || !(fields.get("label") instanceof String label) || label.isBlank()
                        || !(fields.get("parentType") instanceof String parent) || parent.isBlank()) {
                    throw new IllegalArgumentException("[SCHEMA_TYPE_ONLY] nodeTypes entries must contain exactly label and parentType strings, plus evidence for novel proposals");
                }
                boolean authoritative = authoritativeLabels.contains(canonicalSchemaName(label));
                boolean legacy = fields.keySet().equals(java.util.Set.of("label", "parentType"));
                if (!(fields.keySet().equals(java.util.Set.of("label", "parentType", "evidence"))
                        || (authoritative && legacy))) {
                    throw new IllegalArgumentException("novel nodeTypes entries require exactly label, parentType and evidence");
                }
                if (!legacy) {
                    if (!(fields.get("evidence") instanceof List<?> spans) || spans.isEmpty() || spans.size() > 2) {
                        throw new IllegalArgumentException("evidence must contain 1 or 2 sourceId/quote objects");
                    }
                    List<NodeEvidence> validated = new ArrayList<>();
                    for (Object span : spans) {
                        if (!(span instanceof Map<?, ?> item)
                                || !item.keySet().equals(java.util.Set.of("sourceId", "quote"))
                                || !(item.get("sourceId") instanceof String sourceId)
                                || !(item.get("quote") instanceof String quote)
                                || quote.isBlank() || quote.length() > 1024
                                || !submittedWindows.containsKey(sourceId)
                                || !submittedWindows.get(sourceId).contains(quote)) {
                            throw new IllegalArgumentException("evidence requires a known sourceId and exact nonblank bounded quote from its submitted window; no offsets");
                        }
                        NodeEvidence checked = new NodeEvidence(sourceId, quote);
                        if (!validated.contains(checked)) validated.add(checked);
                    }
                    if (!authoritative) {
                        var proposal = new CorpusSchemaUnifier.TypeProposal(
                                canonicalSchemaName(label), canonicalSchemaName(parent));
                        List<NodeEvidence> retained = evidence.computeIfAbsent(proposal, ignored -> new ArrayList<>());
                        for (NodeEvidence span : validated) {
                            if (!retained.contains(span)) {
                                if (retained.size() == 2) {
                                    throw new IllegalArgumentException("a proposal may cite at most 2 distinct evidence spans, including duplicate rows");
                                }
                                retained.add(span);
                            }
                        }
                    }
                }
                // Authoritative metadata is ignored later; repeated legacy labels cannot conflict
                // with one another and thereby erase a valid novel proposal in the same response.
                if (!authoritative || definitions.stream().noneMatch(definition ->
                        canonicalSchemaName(definition.get("label")).equals(canonicalSchemaName(label)))) {
                    definitions.add(Map.of("label", label, "parentType", parent));
                }
            }
            return new NodeDiscoveryResult(parse(Map.of("nodeTypes", definitions)), evidence);
        } catch (IllegalArgumentException invalid) {
            return new NodeDiscoveryResult(new ParseResult(null,
                    List.of(invalid.getMessage().startsWith("[SCHEMA_TYPE_ONLY]")
                            ? conciseErrorMessage(invalid)
                            : "[SCHEMA_NODE_EVIDENCE] " + conciseErrorMessage(invalid))), Map.of());
        }
    }

    static ParseResult parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new ParseResult(null, List.of("[SCHEMA_RESPONSE] Model response was blank"));
        }

        String extractedJson = LlmJsonExtractor.extractJsonObject(rawResponse);
        if (extractedJson == null || extractedJson.isBlank()) {
            return new ParseResult(null, List.of("[SCHEMA_JSON] Model response did not contain extractable JSON"));
        }

        try {
            GraphSchema schema = OBJECT_MAPPER.readValue(extractedJson, GraphSchema.class);
            return new ParseResult(schema, List.of());
        } catch (JsonProcessingException exception) {
            String message = conciseErrorMessage(exception);
            return new ParseResult(null, List.of("[SCHEMA_JSON] " + message));
        }
    }

    static ParseResult parse(Map<String, Object> toolArguments) {
        if (toolArguments == null || toolArguments.isEmpty()) {
            return new ParseResult(
                    null, List.of("[SCHEMA_TOOL_CALL] Schema tool arguments were empty"));
        }
        try {
            GraphSchema schema = OBJECT_MAPPER.convertValue(
                    normalizeStructuredPatterns(toolArguments), GraphSchema.class);
            return new ParseResult(schema, List.of());
        } catch (IllegalArgumentException exception) {
            return new ParseResult(
                    null, List.of("[SCHEMA_TOOL_CALL] " + conciseErrorMessage(exception)));
        }
    }

    private static Map<String, Object> normalizeStructuredPatterns(
            Map<String, Object> toolArguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(toolArguments);
        normalized.put("nodeTypes", normalizeTypeDefinitions(
                toolArguments.get("nodeTypes"), "label", "node type"));
        normalized.put("relationshipTypes", normalizeTypeDefinitions(
                toolArguments.get("relationshipTypes"), "type", "relationship type"));
        Object patterns = toolArguments.get("patterns");
        if (!(patterns instanceof List<?> values)) {
            return normalized;
        }

        List<Object> normalizedPatterns = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof String) {
                normalizedPatterns.add(value);
                continue;
            }
            if (!(value instanceof Map<?, ?> endpoints)) {
                throw new IllegalArgumentException(
                        "patterns entries must be strings or endpoint objects");
            }
            String sourceType = canonicalSchemaName(
                    requiredEndpoint(endpoints, "sourceType"));
            String relationshipType = canonicalSchemaName(
                    requiredEndpoint(endpoints, "relationshipType"));
            String targetType = canonicalSchemaName(
                    requiredEndpoint(endpoints, "targetType"));
            normalizedPatterns.add("(" + sourceType + ")-[:" + relationshipType
                    + "]->(" + targetType + ")");
        }
        normalized.put("patterns", normalizedPatterns);
        return normalized;
    }

    private static Object normalizeTypeDefinitions(
            Object definitions, String nameField, String kind) {
        if (!(definitions instanceof List<?> values)) {
            return definitions;
        }
        List<Object> normalized = new ArrayList<>(values.size());
        Map<String, String> seenClassifications = new LinkedHashMap<>();
        for (Object value : values) {
            Map<String, Object> copy = new LinkedHashMap<>();
            if (value instanceof String name) {
                copy.put(nameField, canonicalSchemaName(name));
            } else if (value instanceof Map<?, ?> definition) {
                definition.forEach((key, fieldValue) ->
                        copy.put(String.valueOf(key), fieldValue));
                Object name = copy.get(nameField);
                if (name != null) {
                    copy.put(nameField, canonicalSchemaName(name.toString()));
                }
            } else {
                normalized.add(value);
                continue;
            }
            canonicalizeOptionalTypeField(copy, "parentType");
            canonicalizeOptionalTypeField(copy, "connectionFamily");
            Object canonicalName = copy.get(nameField);
            if (canonicalName != null) {
                String classificationField = "label".equals(nameField)
                        ? "parentType" : "connectionFamily";
                Object classification = copy.get(classificationField);
                String normalizedClassification = classification == null
                        ? null : classification.toString();
                String name = canonicalName.toString();
                if (seenClassifications.containsKey(name)) {
                    String previous = seenClassifications.get(name);
                    if (!java.util.Objects.equals(previous, normalizedClassification)) {
                        throw new IllegalArgumentException(kind + " " + canonicalName
                                + " has conflicting " + classificationField + " values: "
                                + previous + " and " + normalizedClassification);
                    }
                    continue;
                }
                seenClassifications.put(name, normalizedClassification);
            }
            if (!copy.containsKey("description") || copy.get("description") == null
                    || copy.get("description").toString().isBlank()) {
                copy.put("description", "Corpus-derived " + kind + " " + canonicalName + ".");
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private static void canonicalizeOptionalTypeField(Map<String, Object> definition, String field) {
        Object value = definition.get(field);
        if (value != null && !value.toString().isBlank()) {
            definition.put(field, canonicalSchemaName(value.toString()));
        }
    }

    private static String canonicalSchemaName(String value) {
        return value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_+", "_");
    }

    private static String requiredEndpoint(Map<?, ?> endpoints, String field) {
        Object value = endpoints.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "patterns endpoint object is missing " + field);
        }
        return value.toString().trim();
    }

    private static String conciseErrorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "Unable to parse schema JSON response";
        }
        String trimmed = message.replace('\n', ' ').trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}
