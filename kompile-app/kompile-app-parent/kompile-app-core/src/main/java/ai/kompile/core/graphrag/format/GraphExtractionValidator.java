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

package ai.kompile.core.graphrag.format;

import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy.FailureMode;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates, imports, and exports graph extraction data using the standardized schema.
 */
public final class GraphExtractionValidator {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private GraphExtractionValidator() {
    }

    private static final Pattern TYPE_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern RELATION_PATTERN = Pattern.compile(
            "^\\(\\s*([A-Za-z][A-Za-z0-9_]*)\\s*\\)\\s*-\\s*\\[\\s*:\\s*([A-Za-z][A-Za-z0-9_]*)\\s*]\\s*->\\s*\\(\\s*([A-Za-z][A-Za-z0-9_]*)\\s*\\)\\s*$");

    /**
     * Validation result. Structural and retry-mode violations are errors;
     * warn-mode semantic violations are returned separately.
     */
    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public ValidationResult(boolean valid, List<String> errors) {
            this(valid, errors, List.of());
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of(), List.of());
        }

        public static ValidationResult ok(List<String> warnings) {
            return new ValidationResult(true, List.of(), warnings);
        }

        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors, List.of());
        }
    }

    /**
     * Validate only the invariant structure of an extraction result.
     *
     * <p>This overload preserves the original API. Production extraction should
     * use {@link #validate(ExtractionResult, GraphExtractionValidationPolicy, GraphSchema)}
     * so project semantic validators are also applied.</p>
     */
    public static ValidationResult validate(ExtractionResult result) {
        List<String> errors = validateStructure(result, Map.of());
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    /**
     * Validate invariant structure plus project-configured semantic rules.
     */
    public static ValidationResult validate(ExtractionResult result,
                                            GraphExtractionValidationPolicy configuredPolicy,
                                            GraphSchema schema) {
        return validate(result, configuredPolicy, schema, Map.of());
    }

    /**
     * Validate one incremental graph delta against entities that are already present in the graph.
     *
     * <p>The model may therefore add a relation to an existing endpoint without redundantly
     * re-emitting that entity. The supplied map is also used for relation-signature validation, so
     * schema enforcement sees the real endpoint types. Entities declared inside the delta take
     * precedence over the supplied type hints. This method only validates; it never mutates either
     * the existing graph or the delta.</p>
     *
     * @param knownEntityTypes existing entity id to entity type; null is treated as empty
     */
    public static ValidationResult validate(ExtractionResult result,
                                            GraphExtractionValidationPolicy configuredPolicy,
                                            GraphSchema schema,
                                            Map<String, String> knownEntityTypes) {
        Map<String, String> known = knownEntityTypes == null ? Map.of() : knownEntityTypes;
        List<String> errors = validateStructure(result, known);
        if (result == null) {
            return ValidationResult.fail(errors);
        }

        GraphExtractionValidationPolicy policy = configuredPolicy == null
                ? GraphExtractionValidationPolicy.defaults()
                : configuredPolicy;
        if (policy.effectiveFailureMode() == FailureMode.DISABLED) {
            return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
        }

        List<String> semanticViolations = validateSemantics(result, policy, schema, known);
        if (policy.effectiveFailureMode() == FailureMode.WARN) {
            return errors.isEmpty()
                    ? ValidationResult.ok(semanticViolations)
                    : new ValidationResult(false, errors, semanticViolations);
        }

        errors.addAll(semanticViolations);
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    private static List<String> validateStructure(ExtractionResult result,
                                                  Map<String, String> knownEntityTypes) {
        List<String> errors = new ArrayList<>();
        if (result == null) {
            errors.add("ExtractionResult is null");
            return errors;
        }

        Set<String> entityIds = new HashSet<>();
        if (knownEntityTypes != null) {
            knownEntityTypes.keySet().stream()
                    .filter(GraphExtractionValidator::hasText)
                    .forEach(entityIds::add);
        }
        Set<String> deltaEntityIds = new HashSet<>();
        for (int i = 0; i < result.entities().size(); i++) {
            ExtractedEntity entity = result.entities().get(i);
            if (entity == null) {
                errors.add("Entity at index " + i + " is null");
                continue;
            }
            if (entity.id() == null || entity.id().isBlank()) {
                errors.add("Entity at index " + i + " has null or blank id");
            } else if (!deltaEntityIds.add(entity.id())) {
                errors.add("Duplicate entity id: " + entity.id());
            } else {
                entityIds.add(entity.id());
            }
            if (entity.name() == null || entity.name().isBlank()) {
                errors.add("Entity '" + entity.id() + "' has null or blank name");
            }
            if (entity.type() == null || entity.type().isBlank()) {
                errors.add("Entity '" + entity.id() + "' has null or blank type");
            }
            if (entity.confidence() != null && (entity.confidence() < 0.0 || entity.confidence() > 1.0)) {
                errors.add("Entity '" + entity.id() + "' has confidence out of range [0,1]: " + entity.confidence());
            }
        }

        for (int i = 0; i < result.relations().size(); i++) {
            ExtractedRelation rel = result.relations().get(i);
            if (rel == null) {
                errors.add("Relation at index " + i + " is null");
                continue;
            }
            if (rel.source() == null || rel.source().isBlank()) {
                errors.add("Relation at index " + i + " has null or blank source");
            } else if (!entityIds.contains(rel.source())) {
                errors.add("Relation at index " + i + " references unknown source entity: " + rel.source());
            }
            if (rel.target() == null || rel.target().isBlank()) {
                errors.add("Relation at index " + i + " has null or blank target");
            } else if (!entityIds.contains(rel.target())) {
                errors.add("Relation at index " + i + " references unknown target entity: " + rel.target());
            }
            if (rel.type() == null || rel.type().isBlank()) {
                errors.add("Relation at index " + i + " has null or blank type");
            }
            if (rel.confidence() != null && (rel.confidence() < 0.0 || rel.confidence() > 1.0)) {
                errors.add("Relation at index " + i + " has confidence out of range [0,1]: " + rel.confidence());
            }
        }
        return errors;
    }

    private static List<String> validateSemantics(ExtractionResult result,
                                                   GraphExtractionValidationPolicy policy,
                                                   GraphSchema schema,
                                                   Map<String, String> knownEntityTypes) {
        List<String> violations = new ArrayList<>();
        for (String unknown : policy.unknownValidatorIds()) {
            violations.add("[VALIDATION_CONFIG] Unknown graph extraction validator id: " + unknown);
        }

        Map<String, NodeType> schemaEntityTypes = schemaEntityTypes(schema);
        Map<String, RelationshipType> schemaRelationTypes = schemaRelationTypes(schema);
        Map<String, ExtractedEntity> entitiesById = new HashMap<>();
        if (knownEntityTypes != null) {
            knownEntityTypes.forEach((id, type) -> {
                if (hasText(id) && hasText(type)) {
                    entitiesById.put(id, new ExtractedEntity(
                            id, id, type, List.of(), null, 1.0, Map.of()));
                }
            });
        }
        Map<String, String> typeByNormalizedName = new HashMap<>();
        Map<String, String> entityIdByNormalizedName = new HashMap<>();
        for (int i = 0; i < result.entities().size(); i++) {
            ExtractedEntity entity = result.entities().get(i);
            if (entity == null) {
                continue;
            }
            if (entity.id() != null && !entity.id().isBlank()) {
                entitiesById.put(entity.id(), entity);
            }

            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.TYPE_NAME_FORMAT)
                    && hasText(entity.type()) && !TYPE_NAME.matcher(entity.type()).matches()) {
                violations.add("[TYPE_NAME_FORMAT] Entity '" + entity.id()
                        + "' type must match [A-Z][A-Z0-9_]*: " + entity.type());
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.ENTITY_TYPE_SCHEMA)) {
                validateEntityType(entity, schemaEntityTypes, violations);
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.PROPERTY_SCHEMA)) {
                validateEntityProperties(entity, schemaEntityTypes, violations);
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.REQUIRED_DESCRIPTIONS)
                    && !hasText(entity.description())) {
                violations.add("[REQUIRED_DESCRIPTIONS] Entity '" + entity.id()
                        + "' must include a source-grounded description");
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.ENTITY_NAME_TYPE_CONSISTENCY)
                    && hasText(entity.name()) && hasText(entity.type())) {
                String normalizedName = entity.name().trim().toLowerCase(Locale.ROOT);
                String previousType = typeByNormalizedName.putIfAbsent(normalizedName, entity.type());
                String previousId = entityIdByNormalizedName.putIfAbsent(normalizedName, entity.id());
                if (previousType != null && !previousType.equals(entity.type())) {
                    violations.add("[ENTITY_NAME_TYPE_CONSISTENCY] Entity name '" + entity.name()
                            + "' is assigned conflicting types " + previousType + " (" + previousId
                            + ") and " + entity.type() + " (" + entity.id() + ")");
                }
            }
        }

        Map<String, List<RelationSignature>> patternsByType =
                parseAllowedPatterns(policy, schema, violations);
        Set<String> requiredOccurredAt = new HashSet<>(
                policy.effectiveRequiredOccurredAtRelationTypes());

        for (int i = 0; i < result.relations().size(); i++) {
            ExtractedRelation relation = result.relations().get(i);
            if (relation == null) {
                continue;
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.TYPE_NAME_FORMAT)
                    && hasText(relation.type()) && !TYPE_NAME.matcher(relation.type()).matches()) {
                violations.add("[TYPE_NAME_FORMAT] Relation at index " + i
                        + " type must match [A-Z][A-Z0-9_]*: " + relation.type());
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.RELATION_TYPE_SCHEMA)) {
                validateRelationType(i, relation, schemaRelationTypes, violations);
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.PROPERTY_SCHEMA)) {
                validateRelationProperties(i, relation, schemaRelationTypes, violations);
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.REQUIRED_DESCRIPTIONS)
                    && !hasText(relation.description())) {
                violations.add("[REQUIRED_DESCRIPTIONS] Relation at index " + i
                        + " must include a source-grounded description");
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.RELATION_SELF_LOOP)
                    && hasText(relation.source()) && relation.source().equals(relation.target())) {
                violations.add("[RELATION_SELF_LOOP] Relation at index " + i
                        + " cannot use the same source and target entity: " + relation.source());
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.OCCURRED_AT_FORMAT)
                    && relation.occurredAt() != null && !isValidOccurredAt(relation.occurredAt())) {
                violations.add("[OCCURRED_AT_FORMAT] Relation at index " + i
                        + " occurredAt must be an ISO-8601 year, year-month, date, or date-time: "
                        + relation.occurredAt());
            }
            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.REQUIRED_RELATION_OCCURRED_AT)
                    && hasText(relation.type())
                    && requiredOccurredAt.contains(normalizeType(relation.type()))
                    && !hasText(relation.occurredAt())) {
                violations.add("[REQUIRED_RELATION_OCCURRED_AT] Relation type " + relation.type()
                        + " requires occurredAt");
            }

            if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.RELATION_SCHEMA_PATTERN)
                    && hasText(relation.type())) {
                validateRelationPattern(i, relation, entitiesById, patternsByType, policy, violations);
            }
        }
        return violations;
    }

    private static Map<String, NodeType> schemaEntityTypes(GraphSchema schema) {
        Map<String, NodeType> types = new HashMap<>();
        if (schema == null || schema.getNodeTypes() == null) {
            return types;
        }
        for (NodeType type : schema.getNodeTypes()) {
            if (type != null && hasText(type.getLabel())) {
                types.put(normalizeType(type.getLabel()), type);
            }
        }
        return types;
    }

    private static Map<String, RelationshipType> schemaRelationTypes(GraphSchema schema) {
        Map<String, RelationshipType> types = new HashMap<>();
        if (schema == null || schema.getRelationshipTypes() == null) {
            return types;
        }
        for (RelationshipType type : schema.getRelationshipTypes()) {
            if (type != null && hasText(type.getType())) {
                types.put(normalizeType(type.getType()), type);
            }
        }
        return types;
    }

    private static void validateEntityType(ExtractedEntity entity,
                                           Map<String, NodeType> schemaTypes,
                                           List<String> violations) {
        if (schemaTypes.isEmpty() || !hasText(entity.type())) {
            return;
        }
        if (!schemaTypes.containsKey(normalizeType(entity.type()))) {
            violations.add("[ENTITY_TYPE_SCHEMA] Entity '" + entity.id() + "' uses type "
                    + entity.type() + "; expected one of " + summarizeNames(
                    schemaTypes.values().stream().map(NodeType::getLabel).toList()));
        }
    }

    private static void validateRelationType(int index,
                                             ExtractedRelation relation,
                                             Map<String, RelationshipType> schemaTypes,
                                             List<String> violations) {
        if (schemaTypes.isEmpty() || !hasText(relation.type())) {
            return;
        }
        String normalized = normalizeType(relation.type());
        if (schemaTypes.containsKey(normalized)) {
            return;
        }
        for (RelationshipType definition : schemaTypes.values()) {
            if (definition.getAliases() != null && definition.getAliases().stream()
                    .filter(GraphExtractionValidator::hasText)
                    .map(GraphExtractionValidator::normalizeType)
                    .anyMatch(normalized::equals)) {
                violations.add("[RELATION_TYPE_SCHEMA] Relation at index " + index + " uses alias "
                        + relation.type() + "; use canonical type " + definition.getType());
                return;
            }
        }
        violations.add("[RELATION_TYPE_SCHEMA] Relation at index " + index + " uses type "
                + relation.type() + "; expected one of " + summarizeNames(
                schemaTypes.values().stream().map(RelationshipType::getType).toList()));
    }

    private static void validateEntityProperties(ExtractedEntity entity,
                                                 Map<String, NodeType> schemaTypes,
                                                 List<String> violations) {
        if (schemaTypes.isEmpty() || !hasText(entity.type()) || entity.properties().isEmpty()) {
            return;
        }
        NodeType definition = schemaTypes.get(normalizeType(entity.type()));
        if (definition != null) {
            validateProperties("Entity '" + entity.id() + "'", entity.properties(),
                    definition.getProperties(), violations);
        }
    }

    private static void validateRelationProperties(int index,
                                                   ExtractedRelation relation,
                                                   Map<String, RelationshipType> schemaTypes,
                                                   List<String> violations) {
        if (schemaTypes.isEmpty() || !hasText(relation.type()) || relation.properties().isEmpty()) {
            return;
        }
        RelationshipType definition = schemaTypes.get(normalizeType(relation.type()));
        if (definition != null) {
            validateProperties("Relation at index " + index, relation.properties(),
                    definition.getProperties(), violations);
        }
    }

    private static void validateProperties(String fact,
                                           Map<String, String> actual,
                                           List<PropertyType> schemaProperties,
                                           List<String> violations) {
        // A null property list means this schema does not declare a property contract.
        if (schemaProperties == null) {
            return;
        }
        Map<String, PropertyType> allowed = new HashMap<>();
        for (PropertyType property : schemaProperties) {
            if (property != null && hasText(property.getName())) {
                allowed.put(property.getName(), property);
            }
        }
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            PropertyType property = allowed.get(entry.getKey());
            if (property == null) {
                violations.add("[PROPERTY_SCHEMA] " + fact + " uses property '" + entry.getKey()
                        + "'; expected one of " + summarizeNames(allowed.keySet()));
            } else if (!matchesPropertyType(entry.getValue(), property.getType())) {
                violations.add("[PROPERTY_SCHEMA] " + fact + " property '" + entry.getKey()
                        + "' must be " + property.getType() + " but was '" + entry.getValue() + "'");
            }
        }
    }

    private static boolean matchesPropertyType(String value, String configuredType) {
        if (!hasText(configuredType)) {
            return true;
        }
        String type = configuredType.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        if ("STRING".equals(type) || "TEXT".equals(type)) {
            return value != null;
        }
        if (value == null) {
            return false;
        }
        try {
            return switch (type) {
                case "INTEGER", "INT", "LONG", "SHORT" -> {
                    Long.parseLong(value);
                    yield true;
                }
                case "NUMBER", "DECIMAL", "DOUBLE", "FLOAT" -> {
                    double number = Double.parseDouble(value);
                    yield Double.isFinite(number);
                }
                case "BOOLEAN", "BOOL" ->
                        "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
                case "DATE" -> {
                    LocalDate.parse(value);
                    yield true;
                }
                case "YEAR" -> {
                    Year.parse(value);
                    yield true;
                }
                case "YEAR_MONTH", "YEARMONTH" -> {
                    YearMonth.parse(value);
                    yield true;
                }
                case "DATE_TIME", "DATETIME", "TIMESTAMP" -> {
                    DateTimeFormatter.ISO_DATE_TIME.parse(value);
                    yield true;
                }
                default -> true;
            };
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            return false;
        }
    }

    private static String summarizeNames(Iterable<String> values) {
        List<String> names = new ArrayList<>();
        for (String value : values) {
            if (hasText(value)) {
                names.add(value.trim());
            }
        }
        names.sort(String::compareTo);
        int shown = Math.min(24, names.size());
        String summary = String.join(", ", names.subList(0, shown));
        return names.size() > shown ? summary + ", ... (" + names.size() + " total)" : summary;
    }

    private static Map<String, List<RelationSignature>> parseAllowedPatterns(
            GraphExtractionValidationPolicy policy,
            GraphSchema schema,
            List<String> violations) {
        Set<String> configured = new LinkedHashSet<>(policy.effectiveRelationPatterns());
        if (schema != null && schema.getPatterns() != null) {
            schema.getPatterns().stream()
                    .filter(GraphExtractionValidator::hasText)
                    .map(String::trim)
                    .forEach(configured::add);
        }

        Map<String, List<RelationSignature>> byType = new HashMap<>();
        if (!policy.isValidatorEnabled(GraphExtractionValidationPolicy.RELATION_SCHEMA_PATTERN)) {
            return byType;
        }
        for (String expression : configured) {
            Optional<RelationSignature> parsed = parseRelationPattern(expression);
            if (parsed.isEmpty()) {
                violations.add("[VALIDATION_CONFIG] Invalid relationship pattern: " + expression);
                continue;
            }
            RelationSignature pattern = parsed.get();
            byType.computeIfAbsent(pattern.relationType(), ignored -> new ArrayList<>()).add(pattern);
        }
        return byType;
    }

    /**
     * Parses one {@code (SOURCE)-[:TYPE]->(TARGET)} signature.
     *
     * <p>Public so that retrieval-side code can offer only the relation types this validator would
     * accept for a given endpoint pair — a candidate the model is allowed to pick should never be
     * one validation later rejects.</p>
     *
     * @return the parsed signature, or empty when the expression is malformed
     */
    public static Optional<RelationSignature> parseRelationPattern(String expression) {
        if (!hasText(expression)) {
            return Optional.empty();
        }
        Matcher matcher = RELATION_PATTERN.matcher(expression.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new RelationSignature(
                normalizeType(matcher.group(1)),
                normalizeType(matcher.group(2)),
                normalizeType(matcher.group(3)),
                expression.trim()));
    }

    private static void validateRelationPattern(int index,
                                                ExtractedRelation relation,
                                                Map<String, ExtractedEntity> entitiesById,
                                                Map<String, List<RelationSignature>> patternsByType,
                                                GraphExtractionValidationPolicy policy,
                                                List<String> violations) {
        String relationType = normalizeType(relation.type());
        List<RelationSignature> allowed = patternsByType.getOrDefault(relationType, List.of());
        if (allowed.isEmpty()) {
            if (policy.isRequirePatternForEveryRelationType()) {
                violations.add("[RELATION_SCHEMA_PATTERN] Relation at index " + index
                        + " has no configured endpoint signature for type " + relation.type());
            }
            return;
        }

        ExtractedEntity source = entitiesById.get(relation.source());
        ExtractedEntity target = entitiesById.get(relation.target());
        if (source == null || target == null || !hasText(source.type()) || !hasText(target.type())) {
            return;
        }
        String sourceType = normalizeType(source.type());
        String targetType = normalizeType(target.type());
        boolean matches = allowed.stream().anyMatch(pattern ->
                pattern.sourceType().equals(sourceType) && pattern.targetType().equals(targetType));
        if (!matches) {
            String expected = allowed.stream()
                    .map(RelationSignature::expression)
                    .distinct()
                    .reduce((left, right) -> left + " or " + right)
                    .orElse(relation.type());
            violations.add("[RELATION_SCHEMA_PATTERN] Relation at index " + index + " is "
                    + sourceType + "-[:" + relationType + "]->" + targetType
                    + " but allowed signature is " + expected);
        }
    }

    /**
     * Returns whether a value can safely inhabit the graph schema's {@code occurredAt} field.
     * Source-relative expressions remain useful evidence, but must be carried as qualifiers until a
     * separate temporal-resolution step can anchor them without inventing precision.
     */
    public static boolean isValidOccurredAt(String value) {
        if (!hasText(value)) {
            return false;
        }
        String candidate = value.trim();
        try {
            Year.parse(candidate);
            return true;
        } catch (DateTimeParseException ignoredYear) {
            try {
                YearMonth.parse(candidate);
                return true;
            } catch (DateTimeParseException ignoredYearMonth) {
                // Continue to full date/date-time parsing below.
            }
        }
        try {
            LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException ignored) {
            try {
                DateTimeFormatter.ISO_DATE_TIME.parse(candidate);
                return true;
            } catch (DateTimeParseException ignoredAgain) {
                return false;
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeType(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * A normalized {@code (SOURCE)-[:TYPE]->(TARGET)} signature: the endpoint types this relation
     * type is permitted to connect, plus the expression it was parsed from (for messages).
     */
    public record RelationSignature(
            String sourceType,
            String relationType,
            String targetType,
            String expression) {
    }

    /**
     * Export an ExtractionResult to JSON string.
     */
    public static String toJson(ExtractionResult result) throws JsonProcessingException {
        return MAPPER.writeValueAsString(result);
    }

    /**
     * Import an ExtractionResult from JSON string.
     */
    public static ExtractionResult fromJson(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, ExtractionResult.class);
    }

    /**
     * Convert an ExtractionResult to the core Graph model.
     */
    public static Graph toGraph(ExtractionResult result) {
        return toGraph(result, null);
    }

    /**
     * Convert an ExtractionResult to the core Graph model and attach the known source passage to
     * every extracted entity and relation. Source attribution is deterministic pipeline context,
     * not a fact the language model should have to reproduce.
     */
    public static Graph toGraph(ExtractionResult result, String sourcePassage) {
        Graph graph = new Graph();

        if (result.metadata() != null) {
            graph.setId(result.metadata().graphId());
            graph.setParentGraphId(result.metadata().parentGraphId());
        }

        List<Entity> entities = new ArrayList<>();
        for (ExtractedEntity ee : result.entities()) {
            Entity entity = new Entity();
            entity.setId(ee.id());
            entity.setTitle(ee.name());
            entity.setType(ee.type());
            entity.setDescription(ee.description());
            entity.setConfidence(ee.confidence());
            entity.setAliases(ee.aliases());

            Map<String, Object> metadata = expandProperties(ee.properties());
            attachExtractionEvidence(metadata, result.metadata(), sourcePassage);
            if (ee.aliases() != null && !ee.aliases().isEmpty()) {
                metadata.put("aliases", ee.aliases());
            }
            entity.setMetadata(metadata);
            entities.add(entity);
        }
        graph.setEntities(entities);

        List<Relationship> relationships = new ArrayList<>();
        for (ExtractedRelation er : result.relations()) {
            Relationship rel = new Relationship();
            rel.setSource(er.source());
            rel.setTarget(er.target());
            rel.setType(er.type());
            rel.setDescription(er.description());
            rel.setConfidence(er.confidence());
            rel.setWeight(er.confidence());
            rel.setOccurredAt(er.occurredAt());

            Map<String, Object> metadata = expandProperties(er.properties());
            attachExtractionEvidence(metadata, result.metadata(), sourcePassage);
            if (hasText(er.occurredAt())) {
                metadata.put("occurredAt", er.occurredAt());
            }
            rel.setMetadata(metadata);
            relationships.add(rel);
        }
        graph.setRelationships(relationships);

        return graph;
    }

    private static void attachExtractionEvidence(Map<String, Object> target,
                                                 ExtractionMetadata extraction,
                                                 String sourcePassage) {
        if (extraction == null) {
            return;
        }
        putIfText(target, "sourceChunkId", extraction.sourceChunkId());
        putIfText(target, "sourceDocumentId", extraction.sourceDocumentId());
        putIfText(target, "extractionModel", extraction.extractionModel());
        putIfText(target, "extractionTimestamp", extraction.extractionTimestamp());

        if (!hasText(extraction.sourceChunkId()) || target.containsKey("supportingEvidence")) {
            return;
        }
        String existingQuote = target.get("evidenceQuote") instanceof String value ? value : null;
        String quote = hasText(existingQuote) ? existingQuote : sourcePassage;
        if (!hasText(quote)) {
            return;
        }
        quote = quote.strip();
        target.putIfAbsent("evidenceQuote", quote);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceChunkId", extraction.sourceChunkId());
        if (hasText(extraction.sourceDocumentId())) {
            evidence.put("sourceDocumentId", extraction.sourceDocumentId());
        }
        evidence.put("evidenceQuote", quote);
        target.put("supportingEvidence", List.of(Map.copyOf(evidence)));
    }

    private static void putIfText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.putIfAbsent(key, value);
        }
    }

    /**
     * Convert a core Graph model to an ExtractionResult.
     */
    @SuppressWarnings("unchecked")
    public static ExtractionResult fromGraph(Graph graph, String model) {
        List<ExtractedEntity> entities = new ArrayList<>();
        if (graph.getEntities() != null) {
            for (Entity e : graph.getEntities()) {
                LinkedHashSet<String> aliases = new LinkedHashSet<>();
                if (e.getAliases() != null) {
                    e.getAliases().stream().filter(GraphExtractionValidator::hasText).forEach(aliases::add);
                }
                Map<String, String> props = new HashMap<>();
                if (e.getMetadata() != null) {
                    Object aliasObj = e.getMetadata().get("aliases");
                    if (aliasObj instanceof List<?>) {
                        ((List<?>) aliasObj).stream().map(Object::toString)
                                .filter(GraphExtractionValidator::hasText).forEach(aliases::add);
                    }
                    for (Map.Entry<String, Object> entry : e.getMetadata().entrySet()) {
                        if (!"aliases".equals(entry.getKey()) && !"metadataJson".equals(entry.getKey())
                                && entry.getValue() != null) {
                            props.put(entry.getKey(), entry.getValue().toString());
                        }
                    }
                    props.put("metadataJson", metadataJson(e.getMetadata()));
                }
                entities.add(new ExtractedEntity(
                        e.getId(), e.getTitle(), e.getType(),
                        List.copyOf(aliases), e.getDescription(), e.getConfidence(), props
                ));
            }
        }

        List<ExtractedRelation> relations = new ArrayList<>();
        if (graph.getRelationships() != null) {
            for (Relationship r : graph.getRelationships()) {
                Map<String, String> props = new HashMap<>();
                String occurredAt = r.getOccurredAt();
                if (r.getMetadata() != null) {
                    for (Map.Entry<String, Object> entry : r.getMetadata().entrySet()) {
                        if (entry.getValue() != null) {
                            if ("occurredAt".equals(entry.getKey())) {
                                occurredAt = entry.getValue().toString();
                            } else if (!"metadataJson".equals(entry.getKey())) {
                                props.put(entry.getKey(), entry.getValue().toString());
                            }
                        }
                    }
                    props.put("metadataJson", metadataJson(r.getMetadata()));
                }
                relations.add(new ExtractedRelation(
                        r.getSource(), r.getTarget(), r.getType(),
                        r.getDescription(), r.getConfidence(), props, occurredAt
                ));
            }
        }

        return ExtractionResult.of(entities, relations,
                GraphExtractionSchema.ExtractionMetadata.forChunkInGraph(null, null, model, graph.getId(), graph.getParentGraphId()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> expandProperties(Map<String, String> properties) {
        Map<String, Object> metadata = new HashMap<>();
        if (properties == null) {
            return metadata;
        }
        String structured = properties.get("metadataJson");
        if (hasText(structured)) {
            try {
                Object parsed = MAPPER.readValue(structured, Map.class);
                if (parsed instanceof Map<?, ?> values) {
                    values.forEach((key, value) -> metadata.put(String.valueOf(key), value));
                }
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid structured graph metadata", e);
            }
        }
        properties.forEach((key, value) -> {
            if (!"metadataJson".equals(key) && value != null) {
                metadata.putIfAbsent(key, value);
            }
        });
        return metadata;
    }

    private static String metadataJson(Map<String, Object> metadata) {
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Graph metadata is not JSON serializable", e);
        }
    }

    /**
     * Build single-source extraction instructions using production defaults.
     */
    public static String getExtractionPromptInstructions() {
        return getExtractionPromptInstructions(GraphExtractionValidationPolicy.defaults(), null);
    }

    /**
     * Build single-source instructions aligned with a project's validation policy.
     */
    public static String getExtractionPromptInstructions(GraphExtractionValidationPolicy policy,
                                                         GraphSchema schema) {
        return """
                Extract a knowledge graph ONLY from the SOURCE TEXT supplied after these instructions.
                Never copy names, facts, types, or relationships from these instructions, and never invent facts.
                If the source supports no graph facts, return {"entities":[],"relations":[]}.

                Return exactly one valid JSON object with top-level arrays "entities" and "relations".
                Start from this empty structure and populate it only with source-supported facts:
                {"entities":[],"relations":[]}

                Core rules:
                - First resolve repeated mentions of the same real-world item to one entity id.
                - Each entity MUST have string fields id, name, and type plus confidence in [0.0, 1.0].
                - Entity names MUST be exact, meaningful source mentions; never emit generic category placeholders.
                - Entity objects may also use aliases (string array), description, and properties (object); obey any MUST rules below.
                - Each relation MUST have source, target, type, and confidence in [0.0, 1.0].
                - Relation source and target MUST refer to ids in the entities array.
                - Emit a relation only when the source text supports the source, relationship, and target together.
                - Relation objects may also use description, properties (object), and occurredAt; obey any MUST rules below.
                - Every non-structural value MUST be supported by the source text.
                - Output ONLY valid JSON, with no markdown fences or explanations.
                """ + semanticPromptInstructions(policy, schema);
    }

    /**
     * Build multi-chunk extraction instructions using production defaults.
     */
    public static String getMultiChunkExtractionPromptInstructions() {
        return getMultiChunkExtractionPromptInstructions(GraphExtractionValidationPolicy.defaults(), null);
    }

    /**
     * Build multi-chunk instructions aligned with a project's validation policy.
     */
    public static String getMultiChunkExtractionPromptInstructions(
            GraphExtractionValidationPolicy policy,
            GraphSchema schema) {
        return """
                Extract a knowledge graph ONLY from the MULTIPLE SOURCE TEXT chunks provided below.
                Each chunk is preceded by:
                  ===== CHUNK <k> | source=<chunkId> =====

                Never copy names, facts, types, or relationships from these instructions, and never invent facts.
                If the chunks support no graph facts, return {"entities":[],"relations":[]}.

                Return exactly one valid JSON object with top-level arrays "entities" and "relations".
                Start from this empty structure and populate it only with chunk-supported facts:
                {"entities":[],"relations":[]}

                Core rules:
                - First resolve repeated mentions of the same real-world item to one entity id.
                - Each entity MUST have string fields id, name, type, and chunkId plus confidence in [0.0, 1.0].
                - Entity names MUST be exact, meaningful source mentions; never emit generic category placeholders.
                - Entity chunkId MUST equal the source value from its nearest preceding chunk header.
                - Entity objects may also use aliases (string array), description, and properties (object); obey any MUST rules below.
                - Each relation MUST have source, target, type, and chunkId plus confidence in [0.0, 1.0].
                - Relation source and target MUST refer to ids in the entities array.
                - Emit a relation only when one source chunk supports the source, relationship, and target together.
                - Relation chunkId MUST name that supporting chunk; do not join unrelated facts across chunks.
                - Relation objects may also use description, properties (object), and occurredAt; obey any MUST rules below.
                - Every non-structural value MUST be supported by a source chunk.
                - Output ONLY valid JSON, with no markdown fences or explanations.
                - If an entity is supported by multiple chunks, use the chunkId where it was first mentioned.
                """ + semanticPromptInstructions(policy, schema);
    }

    /**
     * Render only the rules controlled by {@link GraphExtractionValidationPolicy}.
     * Other graph constructors can append this to their own JSON shape without
     * duplicating or drifting from production validation behavior.
     */
    public static String semanticPromptInstructions(GraphExtractionValidationPolicy configuredPolicy,
                                                    GraphSchema schema) {
        GraphExtractionValidationPolicy policy = configuredPolicy == null
                ? GraphExtractionValidationPolicy.defaults()
                : configuredPolicy;
        if (policy.effectiveFailureMode() == FailureMode.DISABLED) {
            return "";
        }

        StringBuilder rules = new StringBuilder("\nProject validation rules:\n");
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.TYPE_NAME_FORMAT)) {
            rules.append("- Entity and relation types MUST match [A-Z][A-Z0-9_]*.\n");
        }
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.ENTITY_TYPE_SCHEMA)
                && schema != null && !schema.getAllNodeLabels().isEmpty()) {
            rules.append("- Entity types MUST use the standardized schema vocabulary: ")
                    .append(String.join(", ", schema.getAllNodeLabels().stream().sorted().toList()))
                    .append(".\n");
        }
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.RELATION_TYPE_SCHEMA)
                && schema != null && !schema.getAllRelationshipTypes().isEmpty()) {
            rules.append("- Relation types MUST use the standardized schema vocabulary: ")
                    .append(String.join(", ", schema.getAllRelationshipTypes().stream().sorted().toList()))
                    .append(".\n");
        }
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.PROPERTY_SCHEMA)
                && schema != null) {
            rules.append("- When the standardized schema declares properties for a type, use only those property names and value types.\n");
        }
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.REQUIRED_DESCRIPTIONS)) {
            rules.append("- Every entity and relation MUST have a concise description grounded in the same source evidence.\n");
        }
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.ENTITY_NAME_TYPE_CONSISTENCY)) {
            rules.append("- The same normalized entity name MUST NOT appear with different types; reuse one id and one type.\n");
        }
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.RELATION_SELF_LOOP)) {
            rules.append("- Never emit a relation whose source and target are the same entity id.\n");
        }

        List<String> patterns = effectivePatternExpressions(policy, schema);
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.RELATION_SCHEMA_PATTERN)) {
            if (patterns.isEmpty() && policy.isRequirePatternForEveryRelationType()) {
                rules.append("- Do not emit relations because this project has no allowed relationship signatures.\n");
            } else if (!patterns.isEmpty()) {
                rules.append("- For the listed relation types, source and target entity types MUST match one allowed signature exactly:\n");
                patterns.forEach(pattern -> rules.append("  - ").append(pattern).append('\n'));
                if (policy.isRequirePatternForEveryRelationType()) {
                    rules.append("- Do not emit relationship types absent from the allowed signatures.\n");
                }
            }
        }
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.OCCURRED_AT_FORMAT)) {
            rules.append("- If occurredAt is present, it MUST be an ISO-8601 year, year-month, date, or date-time copied or normalized from source evidence; never invent day or month precision absent from the source.\n");
        }
        if (policy.isValidatorEnabled(GraphExtractionValidationPolicy.REQUIRED_RELATION_OCCURRED_AT)
                && !policy.effectiveRequiredOccurredAtRelationTypes().isEmpty()) {
            rules.append("- occurredAt is REQUIRED for these relation types: ")
                    .append(String.join(", ", policy.effectiveRequiredOccurredAtRelationTypes()))
                    .append(".\n");
        }
        return rules.toString();
    }

    private static List<String> effectivePatternExpressions(
            GraphExtractionValidationPolicy policy,
            GraphSchema schema) {
        Set<String> patterns = new LinkedHashSet<>(policy.effectiveRelationPatterns());
        if (schema != null && schema.getPatterns() != null) {
            schema.getPatterns().stream()
                    .filter(GraphExtractionValidator::hasText)
                    .map(String::trim)
                    .forEach(patterns::add);
        }
        return List.copyOf(patterns);
    }
}
