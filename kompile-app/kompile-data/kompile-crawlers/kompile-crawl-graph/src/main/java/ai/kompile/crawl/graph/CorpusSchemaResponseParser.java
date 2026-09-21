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
import ai.kompile.core.graphrag.model.schema.RelationshipType;
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

    private static final int MAX_ENTITY_CLASSIFICATIONS = 8;
    private static final int MAX_ENTITY_NAME_CHARS = 32;

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
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Object row = rows.get(rowIndex);
                String rowPath = "nodeTypes[" + rowIndex + "]";
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
                if (authoritative) {
                    // Discovery cannot redefine trusted types. Retain one label for outcome
                    // accounting only; the unifier removes these before overlay validation.
                    // Neither supplied evidence nor parent metadata becomes authoritative.
                    if (definitions.stream().noneMatch(definition ->
                            canonicalSchemaName(definition.get("label")).equals(canonicalSchemaName(label)))) {
                        definitions.add(Map.of("label", label, "parentType", parent));
                    }
                    continue;
                }
                if (!legacy) {
                    if (!(fields.get("evidence") instanceof List<?> spans) || spans.isEmpty() || spans.size() > 2) {
                        throw new IllegalArgumentException("evidence must contain 1 or 2 sourceId/quote objects");
                    }
                    List<NodeEvidence> validated = new ArrayList<>();
                    for (int spanIndex = 0; spanIndex < spans.size(); spanIndex++) {
                        Object span = spans.get(spanIndex);
                        if (!(span instanceof Map<?, ?> item)
                                || !item.keySet().equals(java.util.Set.of("sourceId", "quote"))
                                || !(item.get("sourceId") instanceof String sourceId)
                                || !(item.get("quote") instanceof String quote)
                                || quote.isBlank() || quote.length() > 1024
                                || !submittedWindows.containsKey(sourceId)
                                || !submittedWindows.get(sourceId).contains(quote)) {
                            throw new IllegalArgumentException(evidenceDiagnostic(
                                    rowPath + ".evidence[" + spanIndex + "]", label, span, submittedWindows));
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

    private static String evidenceDiagnostic(String path, String label, Object span,
                                             Map<String, String> windows) {
        Map<?, ?> fields = span instanceof Map<?, ?> map ? map : Map.of();
        Object sourceValue = fields.get("sourceId"), quoteValue = fields.get("quote");
        String sourceId = sourceValue instanceof String text ? text : "";
        String quote = quoteValue instanceof String text ? text : "";
        String source = windows.get(sourceId);
        String reason = !fields.keySet().equals(java.util.Set.of("sourceId", "quote"))
                || !(sourceValue instanceof String) || !(quoteValue instanceof String)
                ? "INVALID_EVIDENCE_SHAPE"
                : quote.isBlank() ? "EMPTY_QUOTE"
                : quote.length() > 1024 ? "QUOTE_TOO_LONG"
                : source == null ? "UNKNOWN_SOURCE_ID" : "QUOTE_NOT_IN_CITED_WINDOW";
        String matches = quote.isBlank() || quote.length() > 1024 ? "[]" : windows.entrySet().stream()
                .filter(entry -> entry.getValue().contains(quote))
                .map(Map.Entry::getKey).sorted().limit(3)
                .map(id -> diagnosticString(id, 24)).toList().toString();
        return path + " reason=" + reason + " label=" + diagnosticString(label, 48)
                + " sourceId=" + diagnosticString(sourceId, 24)
                + " quote=" + diagnosticString(quote, 80)
                + " exactMatchSourceIds=" + matches
                + " citedWindowExcerpt=" + diagnosticString(source, 100);
    }

    /** Values are bounded JSON strings: never embed raw source commands as repair instructions. */
    private static String diagnosticString(String value, int limit) {
        if (value == null) return "null";
        String bounded = value.length() > limit ? value.substring(0, limit) + "…" : value;
        try {
            return OBJECT_MAPPER.writeValueAsString(bounded);
        } catch (JsonProcessingException impossible) {
            return "\"<unavailable>\"";
        }
    }

    /**
     * One extraction-side entity classification from the bounded classification-sample pass.
     * The name is the exact named-entity span copied from a submitted window; the category is the
     * model's own general noun for what that entity is; the parent is the trusted baseline parent
     * it falls under. This is extraction data, not schema design, so nothing here claims truth.
     */
    record EntityClassification(String name, String category, String parentType) {}

    /** Parsed classification sample: valid rows plus non-fatal drop reasons for malformed ones. */
    record EntityClassificationResult(List<EntityClassification> classifications,
            List<String> parseErrors) {
        EntityClassificationResult {
            classifications = classifications == null
                    ? List.of() : List.copyOf(classifications);
            parseErrors = parseErrors == null ? List.of() : List.copyOf(parseErrors);
        }
    }

    /**
     * Parses the {@code submit_entity_classifications} tool call. Unknown, malformed, or duplicate
     * rows are dropped individually with a parse error instead of failing the whole sample; the
     * classification pass is extraction-side and must never break schema discovery on one bad row.
     */
    static EntityClassificationResult parseEntityClassifications(
            Map<String, Object> arguments,
            java.util.Set<String> trustedParentTypes) {
        List<EntityClassification> accepted = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            if (arguments == null
                    || !arguments.keySet().equals(java.util.Set.of("classifications"))
                    || !(arguments.get("classifications") instanceof List<?> rows)) {
                throw new IllegalArgumentException(
                        "classifications must be the only field and an array");
            }
            if (rows.size() > MAX_ENTITY_CLASSIFICATIONS) {
                throw new IllegalArgumentException(
                        "classifications must contain at most " + MAX_ENTITY_CLASSIFICATIONS
                                + " objects");
            }
            java.util.Set<String> seenNames = new java.util.LinkedHashSet<>();
            for (Object row : rows) {
                try {
                    if (!(row instanceof Map<?, ?> fields)) {
                        throw new IllegalArgumentException(
                                "classifications entries must be objects");
                    }
                    if (!fields.keySet().equals(java.util.Set.of("name", "category", "parentType"))) {
                        throw new IllegalArgumentException(
                                "classifications entries must contain exactly name, category and parentType");
                    }
                    if (!(fields.get("name") instanceof String name) || name.isBlank()) {
                        throw new IllegalArgumentException("name must be a nonblank string");
                    }
                    if (name.length() > MAX_ENTITY_NAME_CHARS) {
                        throw new IllegalArgumentException("name must be at most "
                                + MAX_ENTITY_NAME_CHARS + " characters");
                    }
                    if (!(fields.get("category") instanceof String category)
                            || category.isBlank()
                            || !canonicalSchemaName(category).matches("^[A-Z][A-Z0-9_]*$")) {
                        throw new IllegalArgumentException(
                                "category must be a general UPPER_SNAKE_CASE noun");
                    }
                    if (!(fields.get("parentType") instanceof String parentRaw) || parentRaw.isBlank()) {
                        throw new IllegalArgumentException("parentType must be a nonblank string");
                    }
                    String parent = canonicalSchemaName(parentRaw);
                    if (!trustedParentTypes.contains(parent)) {
                        throw new IllegalArgumentException(
                                "parentType must be one of the trusted baseline types: " + parent);
                    }
                    String trimmedName = name.trim();
                    if (!seenNames.add(trimmedName)) {
                        // Duplicate names contribute no new extraction facts; silently dedupe.
                        continue;
                    }
                    accepted.add(new EntityClassification(
                            trimmedName, canonicalSchemaName(category), parent));
                } catch (IllegalArgumentException invalidRow) {
                    errors.add("[SCHEMA_ENTITY_CLASSIFICATION] "
                            + conciseErrorMessage(invalidRow));
                }
            }
        } catch (IllegalArgumentException invalidCall) {
            // Whole-call malformation still yields the rows parsed so far plus one reason.
            errors.add("[SCHEMA_ENTITY_CLASSIFICATION] " + conciseErrorMessage(invalidCall));
        }
        return new EntityClassificationResult(accepted, errors);
    }

    /** One consolidated relationship predicate with its host-verified witness citations. */
    record SupportedRelationship(RelationshipType type, List<String> witnessIds) {
        SupportedRelationship {
            witnessIds = witnessIds == null ? List.of() : List.copyOf(witnessIds);
        }
    }

    /**
     * Parses a witness-aware consolidation response with deterministic structural reduction:
     * validated rows are grouped by exact type label; same-family duplicates merge their
     * unique citations; a label proposed under conflicting trusted families is quarantined
     * (never resolved by first/last/majority vote) while unrelated valid labels survive.
     * Structural checks prove reference integrity and conflict freedom, never semantic truth.
     */
    static WitnessConsolidationResult parseWitnessConsolidation(
            Map<String, Object> arguments,
            java.util.Set<String> knownWitnessIds,
            java.util.Set<String> trustedFamilies,
            java.util.Set<String> authoritativeLabels) {
        if (arguments == null || !arguments.keySet().equals(java.util.Set.of("relationshipTypes"))
                || !(arguments.get("relationshipTypes") instanceof List<?> rows)) {
            return new WitnessConsolidationResult(List.of(), List.of(
                    "[WITNESS_CONSOLIDATION] relationshipTypes must be the only field and an array"));
        }
        if (rows.size() > 32) {
            return new WitnessConsolidationResult(List.of(), List.of(
                    "[WITNESS_CONSOLIDATION] relationshipTypes must contain at most 32 objects"));
        }
        // label -> (family -> unique citations), insertion-ordered for determinism.
        Map<String, Map<String, List<String>>> grouped = new LinkedHashMap<>();
        Map<String, Integer> rawRowsPerLabel = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int malformedRows = 0;
        for (int index = 0; index < rows.size(); index++) {
            String path = "relationshipTypes[" + index + "]";
            try {
                if (!(rows.get(index) instanceof Map<?, ?> fields)) {
                    throw new IllegalArgumentException("row must be an object");
                }
                if (!fields.keySet().equals(java.util.Set.of("type", "connectionFamily", "witnessIds"))) {
                    throw new IllegalArgumentException("row must contain exactly type, connectionFamily and witnessIds");
                }
                String type = fields.get("type") == null ? "" : fields.get("type").toString().trim();
                if (!type.matches("^[A-Z][A-Z0-9_]*$") || type.length() > 48) {
                    throw new IllegalArgumentException("type must be UPPER_SNAKE_CASE of at most 48 characters: " + type);
                }
                String family = fields.get("connectionFamily") == null
                        ? "" : fields.get("connectionFamily").toString().trim();
                if (!trustedFamilies.contains(family)) {
                    throw new IllegalArgumentException("connectionFamily must be one of the trusted families: " + family);
                }
                if (!(fields.get("witnessIds") instanceof List<?> citedRaw) || citedRaw.isEmpty()) {
                    throw new IllegalArgumentException("witnessIds must be a nonempty array");
                }
                List<String> cited = new ArrayList<>();
                for (Object witnessValue : citedRaw) {
                    if (!(witnessValue instanceof String witnessId) || witnessId.isBlank()) {
                        throw new IllegalArgumentException("witnessIds entries must be nonblank strings");
                    }
                    if (!knownWitnessIds.contains(witnessId)) {
                        throw new IllegalArgumentException("[WITNESS_UNKNOWN] cited witness id does not exist in this request: " + witnessId);
                    }
                    if (!cited.contains(witnessId)) {
                        cited.add(witnessId);
                    }
                }
                if (authoritativeLabels.contains(type)) {
                    // Discovery cannot redefine trusted types; accounting only, no failure.
                    continue;
                }
                grouped.computeIfAbsent(type, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(family, ignored -> new ArrayList<>())
                        .addAll(cited);
                rawRowsPerLabel.merge(type, 1, Integer::sum);
            } catch (IllegalArgumentException invalidRow) {
                errors.add(path + ": " + conciseErrorMessage(invalidRow));
                malformedRows++;
            }
        }
        // Deterministic reduction: exact-label grouping; family conflict quarantines the
        // label; same-family duplicates merge their unique citations.
        List<SupportedRelationship> supported = new ArrayList<>();
        int quarantinedLabels = 0;
        int sameFamilyMerges = 0;
        for (Map.Entry<String, Map<String, List<String>>> labelEntry : grouped.entrySet()) {
            String label = labelEntry.getKey();
            Map<String, List<String>> byFamily = labelEntry.getValue();
            if (byFamily.size() > 1) {
                List<String> families = new ArrayList<>(byFamily.keySet());
                java.util.Collections.sort(families);
                errors.add("[WITNESS_FAMILY_CONFLICT] relationship type " + label
                        + " was proposed under conflicting trusted families "
                        + families + "; the label is quarantined for this consolidation scope");
                quarantinedLabels++;
                continue;
            }
            String family = byFamily.keySet().iterator().next();
            List<String> citations = byFamily.get(family);
            sameFamilyMerges += Math.max(0, rawRowsPerLabel.getOrDefault(label, 1) - 1);
            supported.add(new SupportedRelationship(new RelationshipType(label,
                    "Corpus-supported relationship " + label + ".", null, List.of(), family),
                    List.copyOf(citations)));
        }
        supported.sort(java.util.Comparator.comparing(supportedRelationship ->
                supportedRelationship.type().getType()));
        return new WitnessConsolidationResult(
                List.copyOf(supported), List.copyOf(errors),
                rows.size(), supported.size(), sameFamilyMerges, quarantinedLabels,
                malformedRows);
    }

    /** Witness-aware consolidation result: supported predicates plus per-row diagnostics
     * and structured reduction counts (never report raw rows as retained predicates). */
    record WitnessConsolidationResult(
            List<SupportedRelationship> supported,
            List<String> errors,
            int rawRows,
            int uniqueRetainedPredicates,
            int sameFamilyDuplicatesMerged,
            int labelsQuarantinedForFamilyConflict,
            int otherRejectedRows) {
        WitnessConsolidationResult {
            supported = supported == null ? List.of() : List.copyOf(supported);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        /** Backward-compatible two-field construction (counts zeroed). */
        WitnessConsolidationResult(List<SupportedRelationship> supported, List<String> errors) {
            this(supported, errors, 0, supported == null ? 0 : supported.size(), 0, 0, 0);
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
